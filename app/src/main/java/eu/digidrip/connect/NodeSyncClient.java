package eu.digidrip.connect;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Semaphore;

public class NodeSyncClient implements MqttCallbackExtended {

    public static final String TAG = NodeSyncClient.class.getSimpleName();

    private MqttAsyncClient v3Client;
    private MqttConnectOptions mMqttConnectOptions;

    private String mClientId;

    private Handler mHandler;

    private final Semaphore mSemaSend = new Semaphore(1);

    private static final long RECONNECT_PERIOD = 10000;

    public static final String MQTT_SERVER_URI =
            NodeSyncClient.class.getName() + "MQTT_SERVER_URI";
    public static final String MQTT_CLIENT_ID  =
            NodeSyncClient.class.getName() + "MQTT_CLIENT_ID";
    public static final String MQTT_CLIENT_ACCESS_TOKEN =
            NodeSyncClient.class.getName() + "MQTT_CLIENT_ACCESS_TOKEN";
    public static final String MQTT_FILE_NAME =
            NodeSyncClient.class.getName() + "MQTT_QUEUE_FILE";

    final String gatewayConnectTopic =
            "v1/gateway/connect";
    final String gatewayDisconnectTopic =
            "v1/gateway/disconnect";
    final String gatewayTelemetryTopic =
            "v1/gateway/telemetry";
    final String gatewayAttributesTopic =
            "v1/gateway/attributes";

    final String gatewayMeAttributesTopic =
            "v1/devices/me/attributes";
    final String gatewayMeTelemetryTopic =
            "v1/devices/me/telemetry";

    final String[] mqttTopics = {
            "v1/gateway/attributes",
            "v1/gateway/attributes/response"
    };
    final int[] mqttTopicQos = {
            0, 0
    };

    private LinkedList<MqttQueueMessage> mQueue;

    private LinkedList<NodeSyncClientListener> mListeners;

    private Context mContext = null;
    private static NodeSyncClient mInstance = null;

    private int actionTimeout = 5000;

    private NodeSyncClient(Context context) {
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());
        mQueue = new LinkedList<>();
        mListeners = new LinkedList<>();
    }

    public static NodeSyncClient getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new NodeSyncClient(context);
        }

        return mInstance;
    }


    private void initialize() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(
                mContext);

        final String serverUri = preferences.getString(MQTT_SERVER_URI, "");
        mClientId = preferences.getString(MQTT_CLIENT_ID, "");
        final String accessToken = preferences.getString(MQTT_CLIENT_ACCESS_TOKEN, "");

        /* TODO: better startup */
        if(serverUri.length() == 0)
            return;
        if(mClientId.length() == 0) {
            mClientId = "digidrip_connect_app_" + System.currentTimeMillis();

            SharedPreferences.Editor editor = preferences.edit();
            editor.putString(MQTT_CLIENT_ID, mClientId);
            editor.apply();
        }
        if(accessToken.length() == 0)
            return;

        try {
            mMqttConnectOptions = new MqttConnectOptions();
            mMqttConnectOptions.setAutomaticReconnect(false);
            mMqttConnectOptions.setCleanSession(true);
            mMqttConnectOptions.setUserName(accessToken);

            v3Client = new MqttAsyncClient(serverUri, mClientId, new MemoryPersistence());
            v3Client.setCallback(this);

        } catch (MqttException e) {
            Log.e(TAG, "error creating MqttAsyncClient: " + e.getMessage());
        }

        readQueueFromFile();
    }

    public void connect() {

        if (v3Client == null) {
            initialize();
            if (v3Client == null) {
                Log.e(TAG, "cloud not initialize MQTT v3 client");
                return;
            }
        }

        try {
            IMqttToken connectToken = v3Client.connect(mMqttConnectOptions);
            /*, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    DisconnectedBufferOptions disconnectedBufferOptions = new DisconnectedBufferOptions();
                    disconnectedBufferOptions.setBufferEnabled(true);
                    disconnectedBufferOptions.setBufferSize(10000);
                    disconnectedBufferOptions.setPersistBuffer(false);
                    disconnectedBufferOptions.setDeleteOldestMessages(false);
                    v3Client.setBufferOpts(disconnectedBufferOptions);

                    //subscribeToTopics();
                    //publishConnectMessage(mClientId);

                    if (v3Client.isConnected()) {
                        Log.i(TAG, "successfully connected: IMqttActionListener.onSuccess(...)");
                    }
                    else {
                        mHandler.postDelayed(() -> connect(), RECONNECT_PERIOD);
                    }
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.e(TAG,"IMqttActionListener.onFailure(...)");

                    mHandler.postDelayed(() -> connect(), RECONNECT_PERIOD);
                }
            }); */
            connectToken.waitForCompletion();

        } catch (MqttException ex) {
            Log.e(TAG, "exception in connect(): "  + ex.getMessage());
            ex.printStackTrace();
            closeClientAndExit();
        }
    }

    public void disconnect() {

        if (v3Client == null) {
            return;
        }

        if(v3Client.isConnected()) {
            //publishDisconnectMessage(mClientId);

            try {
                IMqttToken disconnectToken = v3Client.disconnect();
                disconnectToken.waitForCompletion(actionTimeout);
            } catch (MqttException e) {
                Log.e(TAG, "error disconnecting MQTT: " + e.getMessage());
            }
        }

        closeClientAndExit();
    }

    private void subscribeToTopics() {
        try {
            v3Client.subscribe( mqttTopics, mqttTopicQos, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    Log.i(TAG,"IMqttActionListener.onSuccess(): Subscribed to topic.");
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.i(TAG,"IMqttActionListener.onFailure(): Not subscribed to topic.");
                }
            });

        } catch (MqttException ex) {
            Log.e(TAG, "Exception in subscribeToTopics()");
            ex.printStackTrace();
        }
    }

    private void publishConnectMessage(final String device) {
        JSONObject data = new JSONObject();
        try {
            data.put("device", device);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        mQueue.add(new MqttQueueMessage(gatewayConnectTopic, data.toString().getBytes()));
        publishMqttQueueMessages();
    }

    private void publishDisconnectMessage(final String device) {
        JSONObject data = new JSONObject();
        try {
            data.put("device", device);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        mQueue.add(new MqttQueueMessage(gatewayDisconnectTopic, data.toString().getBytes()));
        publishMqttQueueMessages();
    }

    public void publishAttributeMessage(final String jsonData) {
        mQueue.add(new MqttQueueMessage(gatewayAttributesTopic, jsonData.getBytes()));
        publishMqttQueueMessages();
    }

    public void publishMeAttributesMessage(final String jsonData) {
        mQueue.add(new MqttQueueMessage(gatewayMeAttributesTopic, jsonData.getBytes()));
        publishMqttQueueMessages();
    }

    public void publishTelemetryMessage(final String jsonData) {
        mQueue.add(new MqttQueueMessage(gatewayTelemetryTopic, jsonData.getBytes()));
        publishMqttQueueMessages();
    }

    public void publishMeTelemetryMessage(final String jsonData) {
        mQueue.add(new MqttQueueMessage(gatewayMeTelemetryTopic, jsonData.getBytes()));
        publishMqttQueueMessages();
    }

    private void publishMqttQueueMessages() {
        publishMqttQueueMessages(null);
    }

    private void publishMqttQueueMessages(IMqttDeliveryToken token) {
        if(token != null && !mQueue.isEmpty()) {
            mQueue.removeFirst();
        }

        notifyPendingMessagesChanged();

        if(mQueue.isEmpty()) {
            mHandler.postDelayed(() -> disconnect(), 1000);

            return;
        }

        if(!isConnected()) {
            connect();
            return;
        }

        if(!mSemaSend.tryAcquire())
            return;

        try {
            MqttQueueMessage queueMessage = mQueue.getFirst();
            MqttMessage message = new MqttMessage();
            message.setPayload(queueMessage.payload);
            v3Client.publish(queueMessage.topic, message);
        } catch (MqttException e) {
            Log.e(TAG, e.getMessage());
            mSemaSend.release();
        }
    }

    public boolean isConnected() {
        return v3Client != null && v3Client.isConnected();
    }

    public String getClientId() {
        return mClientId;
    }

    public List<MqttQueueMessage> getPendingQueue() {
        return mQueue;
    }

    public void addNodeSyncClientListener(NodeSyncClientListener listener) {
        for (NodeSyncClientListener listener1: mListeners) {
            if (listener1 == listener) {
                return;
            }
        }

        mListeners.add(listener);
    }

    public void removeNodeSyncClientListener(NodeSyncClientListener listener) {
        mListeners.remove(listener);
    }

    private void closeClientAndExit() {
        // Close the client
        Log.i(TAG, "Closing Connection.");
        try {
            v3Client.close();
            Log.i(TAG, "Client Closed.");
        } catch (MqttException e) {
            // End the Application
            Log.e(TAG, "failed to close the connection");
        } catch (NullPointerException e) {
            Log.e(TAG, "v3Client == null");
        }

        writeQueueToFile();

        v3Client = null;

        notifyConnectionStatusChanged();
    }

    private void writeQueueToFile() {
        if(!mQueue.isEmpty()) {
            Log.i(TAG, "writing " + mQueue.size() + " MQTT messages to file");
            try {
                FileOutputStream file = mContext.openFileOutput(NodeSyncClient.MQTT_FILE_NAME,
                        Context.MODE_PRIVATE);
                ObjectOutputStream os = new ObjectOutputStream(file);
                os.writeObject(mQueue);
                os.close();
                file.close();
            } catch (IOException e) {
                Log.e(TAG, e.getMessage());
            }
        }

        mQueue = new LinkedList<>();
    }

    private int readQueueFromFile() {
        LinkedList<MqttQueueMessage> readQueue;
        try {
            FileInputStream inFile = mContext.openFileInput(MQTT_FILE_NAME);
            ObjectInputStream is = new ObjectInputStream(inFile);
            readQueue = (LinkedList<MqttQueueMessage>) is.readObject();
            mQueue.addAll(readQueue);
            is.close();
            inFile.close();
            mContext.deleteFile(MQTT_FILE_NAME);
            Log.i(TAG, "read " + mQueue.size() + " MQTT messages");
        } catch (FileNotFoundException e) {
            Log.e(TAG, "FileNotFoundException: " + e.getMessage());
            return -1;
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "ClassNotFoundException: " + e.getMessage());
            return -2;
        } catch (IOException e) {
            Log.e(TAG, "IOException: " + e.getMessage());
            return -3;
        }

        notifyPendingMessagesChanged();
        return 0;
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        Log.i(TAG, "MqttCallbackExtend.connectComplete(" + reconnect + ", ...)");
        notifyConnectionStatusChanged();
        publishMqttQueueMessages();
    }

    private void notifyConnectionStatusChanged() {
        for (NodeSyncClientListener listener: mListeners) {
            listener.connectionStatusChanged();
        }
    }

    private void notifyPendingMessagesChanged() {
        for (NodeSyncClientListener listener: mListeners) {
            listener.pendingMessagesChanged();
        }
    }

    @Override
    public void connectionLost(Throwable cause) {

        notifyConnectionStatusChanged();

        /*
        if (v3Client..iconnsAutomaticReconnectEnabled()) {
            Log.w(TAG, String.format("The connection to the server was lost, cause: %s. Waiting to reconnect.",
                    cause.getMessage()));
        }
        */

        if (cause != null) {
            Log.w(TAG, "MqttCallbackExtend.connectionLost(...): "
                    + cause.getLocalizedMessage());
        }
        closeClientAndExit();
        /*
        if (v3ConnectionParameters.isAutomaticReconnectEnabled()) {
            logMessage(String.format("The connection to the server was lost, cause: %s. Waiting to reconnect.",
                    cause.getMessage()), true);
        } else {
            logMessage(String.format("The connection to the server was lost, cause: %s. Closing Client",
                    cause.getMessage()), true);
            closeClientAndExit();
        }

         */
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        Log.i(TAG, "MqttCallbackExtend.messageArrived(...): "
                + new String(message.getPayload()));
        /*
        String messageContent = new String(message.getPayload());
        if (v3SubscriptionParameters.isVerbose()) {
            logMessage(String.format("%s %s", topic, messageContent), false);
        } else {
            logMessage(messageContent, false);
        }

         */
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        Log.i(TAG, "MqttCallbackExtend.deliveryComplete(...)");
        mSemaSend.release();
        publishMqttQueueMessages(token);
        //logMessage(String.format("Message %d was delivered.", token.getMessageId()), true);
    }
}