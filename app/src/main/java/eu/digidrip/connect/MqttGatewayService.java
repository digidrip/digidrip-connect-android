package eu.digidrip.connect;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.LinkedList;
import java.util.concurrent.Semaphore;

public class MqttGatewayService extends Service {

    public static final String TAG = MqttGatewayService.class.getSimpleName();

    private final IBinder mBinder = new LocalBinder();

    public static final String ACTION_CONNECTION_STATUS_CHANGED =
            TAG + ".ACTION_CONNECTION_STATUS_CHANGED";
    public static final String ACTION_PENDING_MESSAGES_CHANGED =
            TAG + ".ACTION_PENDING_MESSAGES_CHANGED";

    public static final String EXTRA_DATA_CONNECTION_STATUS =
            TAG + ".EXTRA_DATA_CONNECTION_STATUS";
    public static final String EXTRA_DATA_PENDING_MESSAGES =
            TAG + ".EXTRA_DATA_PENDING_MESSAGES";

    private MqttConnectOptions mMqttConnectOptions;
    private MqttAndroidClient mqttAndroidClient;

    private String mClientId;

    private Handler mHandler;

    private final Semaphore mSemaSend = new Semaphore(1);

    private static final long RECONNECT_PERIOD = 10000;

    public static final String MQTT_SERVER_URI =
            MqttGatewayService.class.getName() + "MQTT_SERVER_URI";
    public static final String MQTT_CLIENT_ID  =
            MqttGatewayService.class.getName() + "MQTT_CLIENT_ID";
    public static final String MQTT_CLIENT_ACCESS_TOKEN =
            MqttGatewayService.class.getName() + "MQTT_CLIENT_ACCESS_TOKEN";
    public static final String MQTT_FILE_NAME =
            MqttGatewayService.class.getName() + "MQTT_QUEUE_FILE";

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

    LinkedList<MqttQueueMessage> mQueue;

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate()");
        mHandler = new Handler();
        mQueue = new LinkedList<>();

        try {
            FileInputStream inFile = getApplicationContext().openFileInput(MQTT_FILE_NAME);
            ObjectInputStream is = new ObjectInputStream(inFile);
            mQueue = (LinkedList<MqttQueueMessage>) is.readObject();
            is.close();
            inFile.close();
            getApplicationContext().deleteFile(MQTT_FILE_NAME);
            Log.i(TAG, "read " + mQueue.size() + " MQTT messages");
        } catch (FileNotFoundException e) {
            Log.e(TAG, "FileNotFoundException: " + e.getMessage());
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "ClassNotFoundException: " + e.getMessage());

        } catch (IOException e) {
            Log.e(TAG, "IOException: " + e.getMessage());
        }

        registerReceiver(mBroadcastReceiver, makeIntentFilter());

        initialize();
        connect();
    }

    private void initialize() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(
                getApplicationContext());

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

        mqttAndroidClient = new MqttAndroidClient(getApplicationContext(), serverUri, mClientId);
        mqttAndroidClient.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                Log.i(TAG, "MqttCallbackExtend.connectComplete(" + reconnect + ", ...)");
                broadcastConnectionStatus();
                publishMqttQueueMessages();
            }

            @Override
            public void connectionLost(Throwable cause) {
                broadcastConnectionStatus();
                if (cause != null) {
                    Log.w(TAG, "MqttCallbackExtend.connectionLost(...): "
                            + cause.getLocalizedMessage());
                }
            }

            @Override
            public void messageArrived(String topic, org.eclipse.paho.client.mqttv3.MqttMessage message) {
                Log.i(TAG, "MqttCallbackExtend.messageArrived(...): "
                        + new String(message.getPayload()));
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                Log.i(TAG, "MqttCallbackExtend.deliveryComplete(...)");
                mSemaSend.release();
                publishMqttQueueMessages(token);
            }
        });

        mMqttConnectOptions = new MqttConnectOptions();
        mMqttConnectOptions.setAutomaticReconnect(false);
        mMqttConnectOptions.setCleanSession(false);
        mMqttConnectOptions.setUserName(accessToken);
    }

    private void connect() {

        if (mqttAndroidClient == null) {
            return;
        }

        try {
            mqttAndroidClient.connect(mMqttConnectOptions, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    DisconnectedBufferOptions disconnectedBufferOptions = new DisconnectedBufferOptions();
                    disconnectedBufferOptions.setBufferEnabled(true);
                    disconnectedBufferOptions.setBufferSize(10000);
                    disconnectedBufferOptions.setPersistBuffer(false);
                    disconnectedBufferOptions.setDeleteOldestMessages(false);
                    mqttAndroidClient.setBufferOpts(disconnectedBufferOptions);

                    //subscribeToTopics();
                    //publishConnectMessage(mClientId);

                    if (mqttAndroidClient.isConnected()) {
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
            });
        } catch (MqttException ex) {
            Log.e(TAG, "exception in connect(): "  + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private void disconnect() {

        if (mqttAndroidClient == null) {
            return;
        }

        mqttAndroidClient.close();

        if(mqttAndroidClient.isConnected()) {
            //publishDisconnectMessage(mClientId);

            try {
                mqttAndroidClient.disconnect();
            } catch (MqttException e) {
                Log.e(TAG, "error disconnecting MQTT: " + e.getMessage());
            }
        }

        mqttAndroidClient = null;
    }

    private void subscribeToTopics() {
        try {
            mqttAndroidClient.subscribe( mqttTopics, mqttTopicQos, null, new IMqttActionListener() {
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

    private void publishAttributeMessage(final String jsonData) {
        mQueue.add(new MqttQueueMessage(gatewayAttributesTopic, jsonData.getBytes()));
        publishMqttQueueMessages();
    }

    private void publishMeAttributesMessage(final String jsonData) {
        mQueue.add(new MqttQueueMessage(gatewayMeAttributesTopic, jsonData.getBytes()));
        publishMqttQueueMessages();
    }

    private void publishTelemetryMessage(final String jsonData) {
        mQueue.add(new MqttQueueMessage(gatewayTelemetryTopic, jsonData.getBytes()));
        publishMqttQueueMessages();
    }

    private void publishMeTelemetryMessage(final String jsonData) {
        mQueue.add(new MqttQueueMessage(gatewayMeTelemetryTopic, jsonData.getBytes()));
        publishMqttQueueMessages();
    }

    private void publishMqttQueueMessages() {
        publishMqttQueueMessages(null);
    }

    private void publishMqttQueueMessages(IMqttDeliveryToken token) {
        if(token != null && !mQueue.isEmpty())
            mQueue.removeFirst();
        broadcastPendingMessages();

        if(mQueue.isEmpty())
            return;

        if(!mqttAndroidClient.isConnected()) {
            connect();
            return;
        }

        if(!mSemaSend.tryAcquire())
            return;

        try {
            MqttQueueMessage queueMessage = mQueue.getFirst();
            MqttMessage message = new MqttMessage();
            message.setPayload(queueMessage.payload);
            mqttAndroidClient.publish(queueMessage.topic, message);
        } catch (MqttException e) {
            Log.e(TAG, e.getMessage());
            mSemaSend.release();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        disconnect();

        unregisterReceiver(mBroadcastReceiver);

        Log.i(TAG, "onDestroy()");

        if(!mQueue.isEmpty()) {
            Log.i(TAG, "writing " + mQueue.size() + " MQTT messages to file");
            try {
                FileOutputStream file = getApplicationContext().openFileOutput(MQTT_FILE_NAME,
                        Context.MODE_PRIVATE);
                ObjectOutputStream os = new ObjectOutputStream(file);
                os.writeObject(mQueue);
                os.close();
                file.close();
            } catch (IOException e) {
                Log.e(TAG, e.getMessage());
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public static class LocalBinder extends Binder {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            String data = "";

            if (action.equals(BluetoothLeService.ACTION_BEACONS_FOUND)) {
                data = "{ \"" + mClientId + "\": "
                        + intent.getStringExtra(BluetoothLeService.EXTRA_DATA_BEACONS_JSON_STRING)
                        + " }";
                Log.i(TAG, "publish message: " + data);
                publishTelemetryMessage(data);
                return;
            }

            if (action.equals(SensorNode.ACTION_GATT_CONNECTED)) {
                String deviceAddress =
                        intent.getStringExtra(SensorNode.EXTRA_DATA_DEVICE_ADDRESS);
                String deviceAttributes =
                        intent.getStringExtra(SensorNode.EXTRA_DATA_DEVICE_ATTRIBUTES_JSON_STRING);
                //publishConnectMessage(deviceAddress);
                //publishAttributeMessage(deviceAttributes);
                //publishAttributeMessage(deviceAttributes);
                Log.d(TAG, "device connected: " + deviceAddress);
                return;
            }

            if (action.equals(SensorNode.ACTION_GATT_DATA_AVAILABLE)) {
                data = intent.getStringExtra(SensorNode.EXTRA_DATA_JSON_STRING);
                publishTelemetryMessage(data);
                return;
            }

            if (action.equals(SensorNode.ACTION_GATT_DISCONNECTED)) {
                String deviceAddress =
                        intent.getStringExtra(SensorNode.EXTRA_DATA_DEVICE_ADDRESS);
                Log.d(TAG, "device disconnected: " + deviceAddress);
                //publishDisconnectMessage(deviceAddress);
                return;
            }

            if (action.equals(SensorNode.ACTION_PUBLISH_ATTRIBUTE)) {
                data = intent.getStringExtra(SensorNode.EXTRA_DATA_DEVICE_ATTRIBUTES_JSON_STRING);
                publishAttributeMessage(data);
                return;
            }

            if (action.equals(SensorNode.ACTION_PUBLISH_TELEMETRY)) {
                data = intent.getStringExtra(SensorNode.EXTRA_DATA_JSON_STRING);
                publishTelemetryMessage(data);
                return;
            }

            if (action.equals(MainActivity.ACTION_MQTT_CREDENTIALS_UPDATED)) {
                Log.d(TAG, "credetianls updated, reconnecting");
                disconnect();
                initialize();
                connect();
                return;
            }

            if (action.equals(MainActivity.ACTION_MQTT_CONNECT)) {
                Log.d(TAG, "connect to MQTT broker");
                disconnect();
                initialize();
                connect();
            }
        }
    };

    private IntentFilter makeIntentFilter() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(SensorNode.ACTION_GATT_CONNECTED);
        intentFilter.addAction(SensorNode.ACTION_GATT_DATA_AVAILABLE);
        intentFilter.addAction(SensorNode.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(SensorNode.ACTION_PUBLISH_ATTRIBUTE);
        intentFilter.addAction(SensorNode.ACTION_PUBLISH_TELEMETRY);
        intentFilter.addAction(BluetoothLeService.ACTION_BEACONS_FOUND);
        intentFilter.addAction(MainActivity.ACTION_MQTT_CREDENTIALS_UPDATED);
        intentFilter.addAction(MainActivity.ACTION_MQTT_CONNECT);

        return intentFilter;
    }

    private void broadcastConnectionStatus() {
        Intent intent = new Intent(ACTION_CONNECTION_STATUS_CHANGED);
        boolean isConnected = mqttAndroidClient != null && mqttAndroidClient.isConnected();
        intent.putExtra(EXTRA_DATA_CONNECTION_STATUS, isConnected);
        sendBroadcast(intent);
    }

    private void broadcastPendingMessages() {
        Intent intent = new Intent(ACTION_PENDING_MESSAGES_CHANGED);
        intent.putExtra(EXTRA_DATA_PENDING_MESSAGES, mQueue.size());
        sendBroadcast(intent);
    }
}