package eu.digidrip.connect;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.preference.PreferenceManager;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {
    private final static String TAG = MainActivity.class.getSimpleName();

    public final static String ACTION_START_SCAN = TAG + ".ACTION_START_SCAN";
    public final static String ACTION_STOP_SCAN = TAG + ".ACTION_STOP_SCAN";
    public final static String ACTION_MQTT_CREDENTIALS_UPDATED =
            TAG + ".ACTION_MQTT_CREDENTIALS_UPDATED";
    public final static String ACTION_MQTT_CONNECT = TAG + ".ACTION_MQTT_CONNECT";

    private static final int PERMISSION_REQUEST_COARSE_LOCATION = 456;
    private static final int PERMISSION_REQUEST_FINE_LOCATION = 375;
    private static final int PERMISSION_ENABLE_BT = 1;

    private static SensorNodeAdapater mSensorNodeAdapter;
    private static Intent mBleServiceIntent;
    private static Intent mMqttServiceIntent;

    private View.OnClickListener mBtnScanStartListener;
    private View.OnClickListener mBtnScanStopListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate()");
        Log.d(TAG, "Build.VERSION.SDK_INT = " + Build.VERSION.SDK_INT);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        RecyclerView recyclerView = findViewById(R.id.sensor_list);

        if(savedInstanceState == null) {
            mSensorNodeAdapter = new SensorNodeAdapater(new ArrayList<>());
        }

        RecyclerView.LayoutManager mLayoutManager = new LinearLayoutManager(getApplicationContext());
        recyclerView.setLayoutManager(mLayoutManager);
        recyclerView.setItemAnimator(new DefaultItemAnimator());
        recyclerView.addItemDecoration(new DividerItemDecoration(this, LinearLayoutManager.VERTICAL));
        recyclerView.setAdapter(mSensorNodeAdapter);
        recyclerView.addOnItemTouchListener(new SensorNodeTouchListener(getApplicationContext(),
                recyclerView, new SensorNodeTouchListener.ClickListener() {
            @Override
            public void onClick(View view, int position) {
                Intent i = new Intent(MainActivity.this, SensorNodeActivity.class);
                i.putExtra("position", position);
                //startActivity(i);
            }

            @Override
            public void onLongClick(View view, int position) {
                Intent i = new Intent(MainActivity.this, SensorNodeActivity.class);
                i.putExtra("position", position);
                //startActivity(i);
            }
        }));

        mBtnScanStartListener = view -> {
            view.setOnClickListener(mBtnScanStopListener);
            sendStartBleScanBroadcast();
        };

        mBtnScanStopListener = view -> {
            view.setOnClickListener(mBtnScanStartListener);
            sendStopBleScanBroadcast();
        };

        Button btn = findViewById(R.id.scan_nodes);
        btn.setOnClickListener(mBtnScanStartListener);

        registerReceiver(mGattUpdateReceiver, makeUpdateIntentFilter());

        mBleServiceIntent = new Intent(this, BluetoothLeService.class);
        mMqttServiceIntent = new Intent(this, MqttGatewayService.class);

        startService(mBleServiceIntent);
        startService(mMqttServiceIntent);

        if(savedInstanceState == null) {
            checkForBlePermissions();
            //sendStartBleScanBroadcast();
        }

        askMqttCredentials(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PERMISSION_ENABLE_BT) {
            if (resultCode == Activity.RESULT_OK) {
                //sendStartBleScanBroadcast();
            }
        }
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy()");
        super.onDestroy();

        unregisterReceiver(mGattUpdateReceiver);

        stopService(mBleServiceIntent);
        stopService(mMqttServiceIntent);
    }

    private void sendStartBleScanBroadcast() {
        Log.d(TAG, "sendStartBleScanBroadcast");
        Intent intent = new Intent(ACTION_START_SCAN);
        sendBroadcast(intent);
    }

    private void sendStopBleScanBroadcast() {
        Log.d(TAG, "sendStopBleScanBroadcast");
        Intent intent = new Intent(ACTION_STOP_SCAN);
        sendBroadcast(intent);
    }

    private void checkForBlePermissions() {
        // check if bluetooth is enabled
        BluetoothAdapter bluetoothAdapter =
                ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();

        if(bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, PERMISSION_ENABLE_BT);
        }

        // check if location permissions are granted
        if(this.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                    PERMISSION_REQUEST_COARSE_LOCATION);
        }

        // check if location permissions are granted
        if(this.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    PERMISSION_REQUEST_FINE_LOCATION);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.activity_main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        if (id == R.id.action_update_mqtt_credentials) {
            askMqttCredentials(true);
            return true;
        }

        if (id == R.id.action_connect_mqtt) {
            sendMqttConnectBroadcast();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private static IntentFilter makeUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();

        intentFilter.addAction(BluetoothLeService.ACTION_BEACONS_FOUND);
        intentFilter.addAction(BluetoothLeService.ACTION_SENSOR_NODE_FOUND);
        intentFilter.addAction(BluetoothLeService.ACTION_SCANNING);
        intentFilter.addAction(BluetoothLeService.ACTION_SCAN_STOPPED);
        intentFilter.addAction(BluetoothLeService.ACTION_LOCATION_UPDATE);

        intentFilter.addAction(MqttGatewayService.ACTION_CONNECTION_STATUS_CHANGED);
        intentFilter.addAction(MqttGatewayService.ACTION_PENDING_MESSAGES_CHANGED);

        intentFilter.addAction(SensorNode.ACTION_STATE_CHANGED);
        intentFilter.addAction(SensorNode.ACTION_GATT_READ_BATTERY);
        intentFilter.addAction(SensorNode.ACTION_GATT_READ_TEMPERATURE);
        intentFilter.addAction(SensorNode.ACTION_GATT_READ_MOISTURE);

        return intentFilter;
    }

    private void askMqttCredentials(boolean force) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        if(!preferences.contains(MqttGatewayService.MQTT_CLIENT_ACCESS_TOKEN) || force) {
            openMqttClientAccessTokenSettingsDialog();
        }

        if(!preferences.contains(MqttGatewayService.MQTT_CLIENT_ID) || force) {
            //openMqttClientIdSettingsDialog();
        }

        if(!preferences.contains(MqttGatewayService.MQTT_SERVER_URI) || force) {
            openMqttServerUriSettingsDialog();
        }
    }

    private void sendMqttCredentialsUpdatedBroadcast() {
        Intent intent = new Intent(ACTION_MQTT_CREDENTIALS_UPDATED);
        sendBroadcast(intent);
    }

    private void sendMqttConnectBroadcast() {
        Intent intent = new Intent(ACTION_MQTT_CONNECT);
        sendBroadcast(intent);
    }

    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {

        @SuppressLint("SetTextI18n")
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if(BluetoothLeService.ACTION_SENSOR_NODE_FOUND.equals(action)) {
                Log.d(TAG, "received BluetoothLeService.ACTION_SENSOR_NODE_FOUND");
                mSensorNodeAdapter.setSensorNodeList(BluetoothLeService.getSensorNodeList());
                mSensorNodeAdapter.notifyDataSetChanged();
            }

            if(BluetoothLeService.ACTION_SCANNING.equals(action)) {
                Button btn = findViewById(R.id.scan_nodes);
                btn.setText(R.string.scanning);
                btn.setOnClickListener(mBtnScanStopListener);
            }

            if(BluetoothLeService.ACTION_SCAN_STOPPED.equals(action)) {
                Button btn = findViewById(R.id.scan_nodes);
                btn.setText(R.string.scan);
                btn.setOnClickListener(mBtnScanStartListener);
            }

            if(MqttGatewayService.ACTION_CONNECTION_STATUS_CHANGED.equals(action)) {
                TextView tv = findViewById(R.id.tvLabelMqttStatus);
                boolean isConnected = intent.getBooleanExtra(MqttGatewayService.EXTRA_DATA_CONNECTION_STATUS, false);
                if(isConnected)
                    tv.setText(R.string.connected);
                else
                    tv.setText(R.string.disconnected);
            }

            if(MqttGatewayService.ACTION_PENDING_MESSAGES_CHANGED.equals(action)) {
                TextView tv = findViewById(R.id.tvLabelMqttPedingMessages);
                int pendingMessages = intent.getIntExtra(MqttGatewayService.EXTRA_DATA_PENDING_MESSAGES, 0);
                tv.setText("" + pendingMessages + " messages");
            }

            if(SensorNode.ACTION_STATE_CHANGED.equals(action)) {
                mSensorNodeAdapter.notifyDataSetChanged();
            }
        }
    };

    private void openMqttServerUriSettingsDialog() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        String uri = preferences.getString(MqttGatewayService.MQTT_SERVER_URI, "tcp://example.com:1883");

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("MQTT Server URI");

        // Set up the input
        final EditText input = new EditText(this);
        input.setText(uri);
        // Specify the type of input expected; this, for example, sets the input as a password, and will mask the text
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        builder.setView(input);

        // Set up the buttons
        builder.setPositiveButton(R.string.ok, (dialog, which) -> {
            SharedPreferences preferences1 = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            SharedPreferences.Editor editor = preferences1.edit();
            editor.putString(MqttGatewayService.MQTT_SERVER_URI, input.getText().toString()); // value to store
            editor.apply();

            sendMqttCredentialsUpdatedBroadcast();
        });
        builder.setNegativeButton(R.string.cancel, (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void openMqttClientIdSettingsDialog() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        String clientId = preferences.getString(MqttGatewayService.MQTT_CLIENT_ID,
                "digidrip_connect_" + System.currentTimeMillis());

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("MQTT Client ID");

        // Set up the input
        final EditText input = new EditText(this);
        input.setText(clientId);
        // Specify the type of input expected; this, for example, sets the input as a password, and will mask the text
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        builder.setView(input);

        // Set up the buttons
        builder.setPositiveButton(R.string.ok, (dialog, which) -> {
            SharedPreferences preferences1 = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            SharedPreferences.Editor editor = preferences1.edit();
            editor.putString(MqttGatewayService.MQTT_CLIENT_ID, input.getText().toString()); // value to store
            editor.apply();

            sendMqttCredentialsUpdatedBroadcast();
        });
        builder.setNegativeButton(R.string.cancel, (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void openMqttClientAccessTokenSettingsDialog() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        String accessToken = preferences.getString(MqttGatewayService.MQTT_CLIENT_ACCESS_TOKEN,
                "");

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("MQTT Client Access Token");

        // Set up the input
        final EditText input = new EditText(this);
        input.setText(accessToken);
        // Specify the type of input expected; this, for example, sets the input as a password, and will mask the text
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        builder.setView(input);

        // Set up the buttons
        builder.setPositiveButton(R.string.ok, (dialog, which) -> {
            SharedPreferences preferences1 = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            SharedPreferences.Editor editor = preferences1.edit();
            editor.putString(MqttGatewayService.MQTT_CLIENT_ACCESS_TOKEN, input.getText().toString()); // value to store
            editor.apply();

            sendMqttCredentialsUpdatedBroadcast();
        });
        builder.setNegativeButton(R.string.cancel, (dialog, which) -> dialog.cancel());

        builder.show();
    }

}