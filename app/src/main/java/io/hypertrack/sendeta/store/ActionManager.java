/*
The MIT License (MIT)

Copyright (c) 2015-2017 HyperTrack (http://hypertrack.com)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
*/
package io.hypertrack.sendeta.store;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationServices;
import com.hypertrack.lib.HyperTrack;
import com.hypertrack.lib.internal.common.logging.HTLog;
import com.hypertrack.lib.internal.common.util.HTTextUtils;
import com.hypertrack.lib.models.Action;
import com.hypertrack.lib.models.Place;

import java.util.ArrayList;
import java.util.List;

import io.hypertrack.sendeta.callback.ActionManagerCallback;
import io.hypertrack.sendeta.callback.ActionManagerListener;
import io.hypertrack.sendeta.service.GeofenceTransitionsIntentService;
import io.hypertrack.sendeta.util.CrashlyticsWrapper;

/**
 * Created by piyush on 15/08/16.
 */
public class ActionManager implements GoogleApiClient.ConnectionCallbacks {

    private static final String TAG = ActionManager.class.getSimpleName();

    private static final int LOITERING_DELAY_MS = 30000;
    private static final int NOTIFICATION_RESPONSIVENESS_MS = 5000;
    private static final float GEOFENCE_RADIUS_IN_METERS = 100;
    private static final String GEOFENCE_REQUEST_ID = "io.hypertrack.meta:GeoFence";
    private GoogleApiClient mGoogleAPIClient;
    private GeofencingRequest geofencingRequest;
    private PendingIntent mGeofencePendingIntent;
    private boolean addGeofencingRequest;

    private static ActionManager sharedManager;

    private Context mContext;
    private String actionID;
    private Action hyperTrackAction;
    private Place place;
    private ActionManagerListener actionCompletedListener;

    private ActionManager(Context mContext) {
        this.mContext = mContext;
    }

    public static ActionManager getSharedManager(Context context) {
        if (sharedManager == null) {
            sharedManager = new ActionManager(context);
        }

        return sharedManager;
    }

    public boolean shouldRestoreState() {
        // Restore the current task with locally cached data
        this.getSavedActionData();

        // Check if current Task exists in Shared Preference or not
        if (this.hyperTrackAction != null) {
            // Start Refreshing the task without any delay
            return true;

        }

        if (actionCompletedListener != null)
            actionCompletedListener.OnCallback();
        return false;
    }

    public void completeAction(final ActionManagerCallback callback) {

        if (HTTextUtils.isEmpty(this.getHyperTrackActionId())) {
            if (callback != null) {
                callback.OnError();
            }
            return;
        }

        if (this.actionID == null) {
            if (callback != null) {
                callback.OnError();
            }
            return;
        }

        HyperTrack.completeAction(actionID);

        if (callback != null)
            callback.OnSuccess();

    }

    public void onActionStart() {
        this.setupGeofencing();
    }

    /**
     * Geofencing Methods
     */

    // Method to setup GoogleApiClient to add geofence request
    private void setupGoogleAPIClient() {
        if (this.mGoogleAPIClient == null) {
            this.mGoogleAPIClient = new GoogleApiClient.Builder(mContext)
                    .addApi(LocationServices.API)
                    .addConnectionCallbacks(this)
                    .build();
        }

        this.mGoogleAPIClient.connect();
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        if (this.addGeofencingRequest) {
            addGeofencingRequest();
        }
    }

    private void setupGeofencing() {
        try {
            geofencingRequest = this.getGeofencingRequest();

            if (geofencingRequest != null) {
                // Save this request to SharedPreferences (to be restored later if removed)
                SharedPreferenceManager.setGeofencingRequest(geofencingRequest);

                // Add Geofencing Request
                addGeofencingRequest();
            }
        } catch (Exception exception) {
            exception.printStackTrace();
            CrashlyticsWrapper.log(exception);
        }
    }

    public void addGeofencingRequest() {
        if (geofencingRequest == null) {
            return;
        }

        if (this.mGoogleAPIClient == null || !this.mGoogleAPIClient.isConnected()) {
            this.addGeofencingRequest = true;
            setupGoogleAPIClient();
            return;
        }

        try {
            Intent geofencingIntent = new Intent(mContext, GeofenceTransitionsIntentService.class);
            mGeofencePendingIntent = PendingIntent.getService(mContext, 0, geofencingIntent, PendingIntent.FLAG_UPDATE_CURRENT);

            LocationServices.GeofencingApi.addGeofences(mGoogleAPIClient, geofencingRequest, mGeofencePendingIntent).setResultCallback(new ResultCallback<Status>() {
                @Override
                public void onResult(@NonNull Status status) {

                    if (status.isSuccess()) {
                        HTLog.i(TAG, "Geofence set at Expected Place");
                        addGeofencingRequest = false;
                    } else {
                        HTLog.e(TAG, "Geofence error at Expected Place" + status.getStatusMessage());
                        addGeofencingRequest = true;
                    }
                }
            });
        } catch (SecurityException | IllegalArgumentException exception) {
            HTLog.e(TAG, "Geofence error at Expected Place" + exception.getMessage());
            CrashlyticsWrapper.log(exception);
        }
    }

    public void OnGeoFenceSuccess() {

        if (actionCompletedListener != null) {
            Log.d(TAG, "OnGeoFenceSuccess: ");
            actionCompletedListener.OnCallback();
        } else {
            completeAction(null);
            Log.d(TAG, "OnGeoFenceSuccess: action completed listener is null");
        }
    }

    private GeofencingRequest getGeofencingRequest() {
        if (this.place == null || this.place.getLocation() == null) {
            return null;
        }

        // called when the transition associated with the Geofence is triggered)
        List<Geofence> geoFenceList = new ArrayList<Geofence>();
        geoFenceList.add(new Geofence.Builder()
                .setRequestId(GEOFENCE_REQUEST_ID)
                .setCircularRegion(this.place.getLocation().getLatitude(),
                        this.place.getLocation().getLongitude(),
                        GEOFENCE_RADIUS_IN_METERS
                )
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_DWELL | Geofence.GEOFENCE_TRANSITION_ENTER)
                .setLoiteringDelay(LOITERING_DELAY_MS)
                .setNotificationResponsiveness(NOTIFICATION_RESPONSIVENESS_MS)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .build());

        GeofencingRequest.Builder builder = new GeofencingRequest.Builder();
        builder.setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER | GeofencingRequest.INITIAL_TRIGGER_DWELL);
        builder.addGeofences(geoFenceList);
        return builder.build();
    }

    /**
     * Call this method once the task has been completed successfully on the SDK.
     */
    public void clearState() {
        this.stopGeofencing();
        this.clearListeners();
        this.clearPlace();
        this.clearAction();
        // Remove GeoFencingRequest from SharedPreferences
        SharedPreferenceManager.removeGeofencingRequest();
    }

    private void stopGeofencing() {
        if (this.mGeofencePendingIntent != null) {
            LocationServices.GeofencingApi.removeGeofences(mGoogleAPIClient, mGeofencePendingIntent);
            mGeofencePendingIntent = null;
        }
    }

    /**
     * Getters and Setters
     */

    // Method to get saved ActionData
    private void getSavedActionData() {
        this.hyperTrackAction = SharedPreferenceManager.getAction(mContext);
        this.actionID = SharedPreferenceManager.getActionID(mContext);
        this.place = SharedPreferenceManager.getActionPlace();
    }

    public boolean isActionLive() {
        return hyperTrackAction != null && !HTTextUtils.isEmpty(hyperTrackAction.getId());
    }

    private void clearListeners() {
        this.actionCompletedListener = null;
    }

    public void setGeofencingRequest(GeofencingRequest request) {
        this.geofencingRequest = request;
    }

    public Place getPlace() {
        return place;
    }

    public void setPlace(Place place) {
        this.place = place;
        this.savePlace();
    }

    private void clearPlace() {
        this.deletePlace();
        this.place = null;
    }

    public void setActionComletedListener(ActionManagerListener listener) {
        this.actionCompletedListener = listener;
    }

    public Action getHyperTrackAction() {
        if (this.hyperTrackAction == null) {
            this.hyperTrackAction = SharedPreferenceManager.getAction(mContext);
        }

        return this.hyperTrackAction;
    }

    public void setHyperTrackAction(Action action) {
        this.hyperTrackAction = action;
        this.actionID = action.getId();
        SharedPreferenceManager.setAction(action);
        SharedPreferenceManager.setActionID(actionID);
    }

    public String getHyperTrackActionId() {
        if (this.actionID == null) {
            this.actionID = SharedPreferenceManager.getActionID(mContext);
        }

        // For Backward compatibility of running trips on app-upgrade
        if (this.actionID == null && getHyperTrackAction() != null) {
            this.actionID = getHyperTrackAction().getId();
            SharedPreferenceManager.setActionID(this.actionID);
        }

        return this.actionID;
    }

    public String getHyperTrackActionLookupId() {
        return getHyperTrackAction() == null ? null : getHyperTrackAction().getLookupId();
    }

    private void savePlace() {
        SharedPreferenceManager.setPlace(this.place);
    }

    private void deletePlace() {
        SharedPreferenceManager.deletePlace();
    }

    private void clearAction() {
        SharedPreferenceManager.deleteAction();
        SharedPreferenceManager.deleteActionID();
        this.actionID = null;
        this.hyperTrackAction = null;
    }

    @Override
    public void onConnectionSuspended(int i) {
    }
}
