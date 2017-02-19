package com.gpetuhov.android.sneakeyes;


import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.location.Location;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.vk.sdk.VKAccessToken;
import com.vk.sdk.VKSdk;
import com.vk.sdk.api.VKApi;
import com.vk.sdk.api.VKApiConst;
import com.vk.sdk.api.VKError;
import com.vk.sdk.api.VKParameters;
import com.vk.sdk.api.VKRequest;
import com.vk.sdk.api.VKResponse;
import com.vk.sdk.api.model.VKApiPhoto;
import com.vk.sdk.api.model.VKAttachments;
import com.vk.sdk.api.model.VKPhotoArray;
import com.vk.sdk.api.model.VKWallPostResult;
import com.vk.sdk.api.photo.VKImageParameters;
import com.vk.sdk.api.photo.VKUploadImage;

import javax.inject.Inject;

// Service takes pictures, gets location info and posts them to VK.
// Implements PhotoTaker.Callback to receive callbacks from PhotoTaker, when photo is ready.
// Service runs on the application MAIN thread!
public class SneakingService extends Service implements
        PhotoTaker.Callback,
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener,
        LocationListener {

    // Tag for logging
    private static final String LOG_TAG = SneakingService.class.getName();

    // Sneak interval in minutes (for testing)
    private static final int SNEAK_INTERVAL = 1;

    // One minute in milliseconds
    private static final int SNEAK_INTERVAL_MINUTE = 60 * 1000;

    // Hashtag for VK wall posts
    public static final String VK_HASHTAG = "#SneakEyesApp";

    // Keeps instance of PhotoTaker. Injected by Dagger.
    @Inject PhotoTaker mPhotoTaker;

    private Bitmap mPhotoBitmap;

    private GoogleApiClient mGoogleApiClient;

    private LocationRequest mLocationRequest;

    private Location mLocation;

    // Create new intent to start this service
    public static Intent newIntent(Context context) {
        return new Intent(context, SneakingService.class);
    }

    // Set AlarmManager to start or stop this service depending on settings in SharedPreferences
    public static void setServiceAlarm(Context context) {

        // Create new intent to start this service
        Intent i = SneakingService.newIntent(context);

        // Get pending intent with this intent.
        // If pending intent for such intent already exists,
        // getService returns reference to it.
        // Otherwise new pending intent is created.
        PendingIntent pi = PendingIntent.getService(context, 0, i, 0);

        // Get reference to AlarmManager
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        // Get SharedPreferences
        SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context);

        // Get Enabled/Disabled setting value from SharedPreferences
        boolean sneakingEnabled =
                sharedPreferences.getBoolean(context.getString(R.string.pref_onoff_key), true);

        // TODO: Get sneak interval from SharedPreferences
        int sneakInterval = SNEAK_INTERVAL;

        // If sneaking is enabled
        if (sneakingEnabled) {

            // Calculate sneak interval in milliseconds
            int sneakIntervalMillis = sneakInterval * SNEAK_INTERVAL_MINUTE;

            // Turn on AlarmManager for inexact repeating
            // (every sneak interval AlarmManager will send pending request to start this service).
            // Time base is set to elapsed time since last system startup.
            // First time AlarmManager will trigger after first sneak interval from current time.
            // AlarmManager will wake the device if it goes off.
            alarmManager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + sneakIntervalMillis,
                    sneakIntervalMillis, pi);

        } else {
            // Otherwise turn AlarmManager off

            // Cancel AlarmManager
            alarmManager.cancel(pi);

            // Cancel pending intent
            pi.cancel();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // Inject PhotoTaker instance into this service field
        SneakEyesApp.getAppComponent().inject(this);

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(LocationServices.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
    }

    // Method is called, when Service is started by incoming intent
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        // IMPORTANT: This runs on the application MAIN thread!

        // If the user is logged in to VK
        if (VKSdk.isLoggedIn()) {
            // Take photo from the camera
            Log.d(LOG_TAG, "User is logged in. Sneaking...");
            mPhotoTaker.takePhoto(this);
        } else {
            // Otherwise (user is not logged in), do nothing and stop service
            Log.d(LOG_TAG, "User is NOT logged in. Stopping...");
            stopSelf();
        }

        // Don't restart service if killed
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        // We don't use binding.
        return null;
    }

    // Method is called by PhotoTaker, when photo is taken.
    @Override
    public void onPhotoTaken(Bitmap photoBitmap) {

        // Save photo
        mPhotoBitmap = photoBitmap;

        // Connect to GoogleApiClient to get location info
        mGoogleApiClient.connect();
    }

    // Method is called, when GoogleApiClient connection established
    @Override
    public void onConnected(Bundle bundle) {
        // Create request for current location
        mLocationRequest = LocationRequest.create();
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        mLocationRequest.setNumUpdates(1);  // We need only one update
        mLocationRequest.setInterval(0);    // We need it as soon as possible

        // Send request
        LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClient, mLocationRequest, this);
    }

    // Method is called, when GoogleApiClient connection suspended
    @Override
    public void onConnectionSuspended(int i) {
        Log.d(LOG_TAG, "GoogleApiClient connection has been suspend");

        mGoogleApiClient.disconnect();

        // GoogleApiClient connection has been suspend.
        // Start uploading photo to VK wall (photo will be posted without location info).
        loadPhotoToVKWall();
    }

    // Method is called, when GoogleApiClient connection failed
    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        Log.i(LOG_TAG, "GoogleApiClient connection has failed");

        mGoogleApiClient.disconnect();

        // GoogleApiClient connection has failed.
        // Start uploading photo to VK wall (photo will be posted without location info).
        loadPhotoToVKWall();
    }

    // Method is called, when location information received
    @Override
    public void onLocationChanged(Location location) {
        Log.d(LOG_TAG, "Location: " + location.toString());

        mGoogleApiClient.disconnect();

        // Save location info
        mLocation = location;

        // Start uploading photo to VK wall (photo will be posted with location info).
        loadPhotoToVKWall();
    }

    // Loading photo to VK wall is done in 2 steps:
    // 1. Upload photo to the server
    // 2. Make wall post with this uploaded photo
    void loadPhotoToVKWall() {
        if (mPhotoBitmap != null) {
            VKRequest request =
                    VKApi.uploadWallPhotoRequest(
                            new VKUploadImage(mPhotoBitmap, VKImageParameters.jpgImage(0.9f)),
                            getUserVKId(), 0);
            request.executeWithListener(new VKRequest.VKRequestListener() {
                @Override
                public void onComplete(VKResponse response) {
                    // Photo is uploaded to the server.
                    // Ready to make wall post with it.
                    VKApiPhoto photoModel = ((VKPhotoArray) response.parsedModel).get(0);
                    makePostToVKWall(new VKAttachments(photoModel), createWallPostMessage(), getUserVKId());
                }
                @Override
                public void onError(VKError error) {
                    // Error uploading photo to server.
                    // Stop service.
                    stopSelf();
                }
            });
        }
    }

    // Return VK user ID
    private int getUserVKId() {
        final VKAccessToken vkAccessToken = VKAccessToken.currentToken();
        return vkAccessToken != null ? Integer.parseInt(vkAccessToken.userId) : 0;
    }

    // Make post to the user's VK wall with provided attachments and message
    private void makePostToVKWall(VKAttachments att, String msg, final int ownerId) {
        VKParameters parameters = new VKParameters();
        parameters.put(VKApiConst.OWNER_ID, String.valueOf(ownerId));
        parameters.put(VKApiConst.ATTACHMENTS, att);
        parameters.put(VKApiConst.MESSAGE, msg);
        VKRequest post = VKApi.wall().post(parameters);
        post.setModelClass(VKWallPostResult.class);
        post.executeWithListener(new VKRequest.VKRequestListener() {
            @Override
            public void onComplete(VKResponse response) {
                // Post was added
                // All work is done. Service can be stopped.
                // (Services must be stopped manually)
                stopSelf();
            }
            @Override
            public void onError(VKError error) {
                // Error
                // Stop Service anyway, because
                // Services must be stopped manually.
                stopSelf();
            }
        });
    }

    // Return message for VK wall post
    private String createWallPostMessage() {
        String message;

        // If location info is available
        if (mLocation != null) {
            // Convert latitude and longitude to string
            String latitude = Double.toString(mLocation.getLatitude());
            String longitude = Double.toString(mLocation.getLongitude());
            // Construct message
            message = "Current location: " + latitude + ", " + longitude + " " + VK_HASHTAG;
        } else {
            // If location info is not available, include only hashtag into message
            message = VK_HASHTAG;
        }

        return message;
    }
}
