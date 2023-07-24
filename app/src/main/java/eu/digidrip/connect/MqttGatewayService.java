package eu.digidrip.connect;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.List;

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

    private Handler mHandler;

    private NodeSyncClientListener mNodeSyncClientListener = new NodeSyncClientListener() {
        @Override
        public void connectionStatusChanged() {
            broadcastConnectionStatus();
        }

        @Override
        public void pendingMessagesChanged() {
            broadcastPendingMessages();
        }
    };

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate()");
        mHandler = new Handler();

        registerReceiver(mBroadcastReceiver, makeIntentFilter());

        NodeSyncClient.getInstance(getApplicationContext()).addNodeSyncClientListener(mNodeSyncClientListener);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        List<MqttQueueMessage> queue = NodeSyncClient.getInstance(getApplicationContext()).getPendingQueue();

        unregisterReceiver(mBroadcastReceiver);
        NodeSyncClient.getInstance(getApplicationContext()).removeNodeSyncClientListener(mNodeSyncClientListener);

        Log.i(TAG, "onDestroy()");
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
                data = "{ \"" + NodeSyncClient.getInstance(getApplicationContext()).getClientId() + "\": "
                        + intent.getStringExtra(BluetoothLeService.EXTRA_DATA_BEACONS_JSON_STRING)
                        + " }";
                Log.i(TAG, "publish message: " + data);
                NodeSyncClient.getInstance(getApplicationContext()).publishTelemetryMessage(data);
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
                NodeSyncClient.getInstance(getApplicationContext()).publishTelemetryMessage(data);
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
                NodeSyncClient.getInstance(getApplicationContext()).publishAttributeMessage(data);
                return;
            }

            if (action.equals(MainActivity.ACTION_MQTT_CREDENTIALS_UPDATED)) {
                Log.d(TAG, "credetianls updated, reconnecting");
                if (NodeSyncClient.getInstance(getApplicationContext()).isConnected()) {
                    NodeSyncClient.getInstance(getApplicationContext()).disconnect();
                    NodeSyncClient.getInstance(getApplicationContext()).connect();
                }
                return;
            }

            if (action.equals(MainActivity.ACTION_MQTT_CONNECT)) {
                Log.d(TAG, "connect to MQTT broker");
                NodeSyncClient.getInstance(getApplicationContext()).connect();
            }
        }
    };

    private IntentFilter makeIntentFilter() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(SensorNode.ACTION_GATT_CONNECTED);
        intentFilter.addAction(SensorNode.ACTION_GATT_DATA_AVAILABLE);
        intentFilter.addAction(SensorNode.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(SensorNode.ACTION_PUBLISH_ATTRIBUTE);
        intentFilter.addAction(BluetoothLeService.ACTION_BEACONS_FOUND);
        intentFilter.addAction(MainActivity.ACTION_MQTT_CREDENTIALS_UPDATED);
        intentFilter.addAction(MainActivity.ACTION_MQTT_CONNECT);

        return intentFilter;
    }

    private void broadcastConnectionStatus() {
        Intent intent = new Intent(ACTION_CONNECTION_STATUS_CHANGED);
        boolean isConnected = NodeSyncClient.getInstance(getApplicationContext()).isConnected();
        intent.putExtra(EXTRA_DATA_CONNECTION_STATUS, isConnected);
        sendBroadcast(intent);
    }

    private void broadcastPendingMessages() {
        Intent intent = new Intent(ACTION_PENDING_MESSAGES_CHANGED);
        intent.putExtra(EXTRA_DATA_PENDING_MESSAGES, NodeSyncClient.getInstance(getApplicationContext()).getPendingQueue().size());
        sendBroadcast(intent);
    }
}