/**
 * Copyright 2015 Google Inc. All Rights Reserved.
 * Copyright 2017 Vector Creations Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.gcm;

import android.text.TextUtils;

import org.matrix.androidsdk.util.Log;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.google.gson.JsonParser;

import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.data.RoomState;
import org.matrix.androidsdk.rest.model.Event;

import java.util.Map;

import im.vector.Matrix;
import im.vector.VectorApp;
import im.vector.activity.CommonActivityUtils;
import im.vector.services.EventStreamService;

/**
 * Class implementing GcmListenerService.
 */
public class MatrixGcmListenerService extends FirebaseMessagingService {

    private static final String LOG_TAG = "GcmListenerService";

    // Tells if the events service running state has been tested
    private Boolean mCheckLaunched = false;

    // UI handler
    private android.os.Handler mUIHandler = null;

    /**
     * Try to create an event from the GCM data
     * @param data the GCM data
     * @return the event
     */
    private Event parseEvent(Map<String, String> data) {
        // accept only event with room id.
        if ((null == data) || !data.containsKey("room_id")) {
            return null;
        }

        Event event = new Event();

        try {
            event.eventId = data.get("id");
            event.sender = data.get("sender");
            event.roomId = data.get("room_id");
            event.setType(data.get("type"));
            event.updateContent((new JsonParser()).parse(data.get("content")).getAsJsonObject());

            return event;
        } catch (Exception e) {
            Log.e(LOG_TAG, "buildEvent fails " + e.getLocalizedMessage());
            event = null;
        }

        return event;
    }

    /**
     * Internal receive method
     *
     * @param data Data map containing message data as key/value pairs.
     *             For Set of keys use data.keySet().
     */
    private void onMessageReceivedInternal(final Map<String, String> data) {
        try {
            // privacy
                /*for (String key : data.keySet()) {
                    Log.d(LOG_TAG, "## onMessageReceived() >>> " + key + " : " + data.get(key));
                }*/

            int unreadCount = 0;

            if ((null != data) && data.containsKey("unread")) {
                unreadCount = Integer.parseInt(data.get("unread"));
            }

            // update the badge counter
            CommonActivityUtils.updateBadgeCount(getApplicationContext(), unreadCount);

            GcmRegistrationManager gcmManager = Matrix.getInstance(getApplicationContext()).getSharedGCMRegistrationManager();

            if (!gcmManager.areDeviceNotificationsAllowed()) {
                Log.d(LOG_TAG, "## onMessageReceived() : the notifications are disabled");
                return;
            }

            if (!gcmManager.isBackgroundSyncAllowed() && VectorApp.isAppInBackground()) {
                Log.d(LOG_TAG, "## onMessageReceived() : the background sync is disabled");

                EventStreamService eventStreamService = EventStreamService.getInstance();

                if (null != eventStreamService) {
                    Event event = parseEvent(data);

                    if (null != event) {
                        // TODO the session id should be provided by the server
                        MXSession session = Matrix.getInstance(getApplicationContext()).getDefaultSession();
                        RoomState roomState = null;

                        if (null != session) {
                            try {
                                roomState = session.getDataHandler().getRoom(event.roomId).getLiveState();
                            } catch (Exception e) {
                                Log.e(LOG_TAG, "Fail to retrieve the roomState of " + event.roomId);
                            }

                            if (TextUtils.equals(event.getType(), Event.EVENT_TYPE_MESSAGE_ENCRYPTED) && session.isCryptoEnabled()) {
                                session.getCrypto().decryptEvent(event, null);
                            }

                            eventStreamService.prepareNotification(event, roomState, session.getDataHandler().getBingRulesManager().fulfilledBingRule(event));
                            eventStreamService.refreshMessagesNotification();
                        }

                        Log.d(LOG_TAG, "## onMessageReceived() : trigger a notification");
                    } else {
                        Log.d(LOG_TAG, "## onMessageReceived() : fail to parse the notification data");
                    }

                } else {
                    Log.d(LOG_TAG, "## onMessageReceived() : there is no event service so nothing is done");
                }

                return;
            }

            // check if the application has been launched once
            // the first GCM event could have been triggered whereas the application is not yet launched.
            // so it is required to create the sessions and to start/resume event stream
            if (!mCheckLaunched && (null != Matrix.getInstance(getApplicationContext()).getDefaultSession())) {
                CommonActivityUtils.startEventStreamService(MatrixGcmListenerService.this);
                mCheckLaunched = true;
            }

            CommonActivityUtils.catchupEventStream(MatrixGcmListenerService.this);
        } catch (Exception e) {
            Log.d(LOG_TAG, "## onMessageReceivedInternal() failed : " + e.getMessage());
        }
    }

    /**
     * Called when message is received.
     *
     * @param message the message
     */
    @Override
    public void onMessageReceived(RemoteMessage message) {
        final Map<String, String> data = message.getData();

        Log.d(LOG_TAG, "## onMessageReceived() --------------------------------");

        if (null == mUIHandler) {
            mUIHandler = new android.os.Handler(VectorApp.getInstance().getMainLooper());
        }

        // prefer running in the UI thread
        if (null != mUIHandler) {
            mUIHandler.post(new Runnable() {
                @Override
                public void run() {
                    onMessageReceivedInternal(data);
                }
            });
        } else {
            Log.d(LOG_TAG, "## onMessageReceived() : failed to retrieve the UI thread");
            onMessageReceivedInternal(data);
        }
    }
}
