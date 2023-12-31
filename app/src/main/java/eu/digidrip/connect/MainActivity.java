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
import android.widget.ToggleButton;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

@RequiresApi(api = Build.VERSION_CODES.S)
public class MainActivity extends AppCompatActivity {
    private final static String TAG = MainActivity.class.getSimpleName();

    public final static String ACTION_START_SCAN = TAG + ".ACTION_START_SCAN";
    public final static String ACTION_STOP_SCAN = TAG + ".ACTION_STOP_SCAN";
    public final static String ACTION_MQTT_CREDENTIALS_UPDATED =
            TAG + ".ACTION_MQTT_CREDENTIALS_UPDATED";
    public final static String ACTION_MQTT_CONNECT = TAG + ".ACTION_MQTT_CONNECT";

    public final static String PREFERENCE_AUTO_SCAN = TAG + ".PREFERENCE_AUTO_SCAN";
    public final static String PREFERENCE_AUTO_SYNC = TAG + ".PREFERENCE_AUTO_SYNC";
    private static final int PERMISSION_ENABLE_BT = 1;

    private static NodeAdapater mSensorNodeAdapter;
    private static Intent mBleServiceIntent;
    private static Intent mMqttServiceIntent;

    private View.OnClickListener mBtnScanStartListener;
    private View.OnClickListener mBtnScanStopListener;

    private static final String[] PERMISSIONS_LOCATION = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_LOCATION_EXTRA_COMMANDS,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_PRIVILEGED
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate()");
        Log.d(TAG, "Build.VERSION.SDK_INT = " + Build.VERSION.SDK_INT);

        super.onCreate(savedInstanceState);
        SplashScreen.installSplashScreen(this);
        setContentView(R.layout.activity_main);

        RecyclerView recyclerView = findViewById(R.id.sensor_list);

        if(savedInstanceState == null) {
            mSensorNodeAdapter = new NodeAdapater(new ArrayList<>());
        }

        RecyclerView.LayoutManager mLayoutManager = new LinearLayoutManager(getApplicationContext());
        recyclerView.setLayoutManager(mLayoutManager);
        recyclerView.setItemAnimator(new DefaultItemAnimator());
        recyclerView.addItemDecoration(new DividerItemDecoration(this, LinearLayoutManager.VERTICAL));
        recyclerView.setAdapter(mSensorNodeAdapter);
        recyclerView.addOnItemTouchListener(new NodeTouchListener(getApplicationContext(),
                recyclerView, new NodeTouchListener.ClickListener() {
            @Override
            public void onClick(View view, int position) {
                Intent i = new Intent(MainActivity.this, NodeActivity.class);
                i.putExtra("address",
                        NodeScanner.getNodeList().get(position).getAddress());
                startActivity(i);
            }

            @Override
            public void onLongClick(View view, int position) {
                Intent i = new Intent(MainActivity.this, NodeActivity.class);
                i.putExtra("address",
                        NodeScanner.getNodeList().get(position).getAddress());
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
        if (getAutoScanPreference()) {
            btn.setOnClickListener(mBtnScanStopListener);
        } else {
            btn.setOnClickListener(mBtnScanStartListener);
        }

        final Boolean autoScan = getAutoScanPreference();
        final Boolean autoSync = getAutoSyncPreference();

        ToggleButton tbtn = findViewById(R.id.tb_auto_scan);
        tbtn.setChecked(autoScan);
        tbtn.setText(autoScan == true ? R.string.auto_scan_on : R.string.auto_scan_off);
        tbtn.setOnClickListener(view -> {
            ToggleButton vtbtn = view.findViewById(R.id.tb_auto_scan);
            vtbtn.setText(vtbtn.isChecked() == true ? R.string.auto_scan_on : R.string.auto_scan_off);
            SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(
                    getApplicationContext()).edit();
            editor.putBoolean(PREFERENCE_AUTO_SCAN, vtbtn.isChecked());
            editor.apply();
        });

        tbtn = findViewById(R.id.tb_auto_sync);
        tbtn.setChecked(autoSync);
        tbtn.setText(autoSync == true ? R.string.auto_sync_on : R.string.auto_sync_off);
        tbtn.setOnClickListener(view -> {
            ToggleButton vtbtn = view.findViewById(R.id.tb_auto_sync);
            vtbtn.setText(vtbtn.isChecked() == true ? R.string.auto_sync_on : R.string.auto_sync_off);
            SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(
                    getApplicationContext()).edit();
            editor.putBoolean(PREFERENCE_AUTO_SYNC, vtbtn.isChecked());
            editor.apply();
        });

        registerReceiver(mGattUpdateReceiver, makeUpdateIntentFilter());

        mBleServiceIntent = new Intent(this, BluetoothLeService.class);
        mMqttServiceIntent = new Intent(this, MqttGatewayService.class);

        startService(mBleServiceIntent);
        startService(mMqttServiceIntent);

        if(savedInstanceState == null) {
            checkForBlePermissions();
        }

        askMqttCredentials(false);

        Constraints constraints = new Constraints();
        constraints.requiresBatteryNotLow();
        PeriodicWorkRequest periodicScanWorkRequest = new PeriodicWorkRequest.Builder(
                ScanWorker.class, 15, TimeUnit.MINUTES)
                // Constraints
                .setConstraints(constraints)
                .build();
        PeriodicWorkRequest periodicSyncWorkRequest = new PeriodicWorkRequest.Builder(
                SynchronizationWorker.class, 15, TimeUnit.MINUTES)
                // Constraints
                .setConstraints(constraints)
                .build();

        if (getAutoSyncPreference()) {
            WorkManager.getInstance(getApplicationContext()).enqueueUniquePeriodicWork(
                    NodeScanner.TAG,
                    ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, periodicScanWorkRequest);
            WorkManager.getInstance(getApplicationContext()).enqueueUniquePeriodicWork(
                    NodeSyncClient.TAG,
                    ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, periodicSyncWorkRequest);
        } else {
            WorkManager.getInstance(getApplicationContext()).cancelUniqueWork(NodeScanner.TAG);
            WorkManager.getInstance(getApplicationContext()).cancelUniqueWork(NodeSyncClient.TAG);
        }

        if (getAutoScanPreference()) {
            sendStartBleScanBroadcast();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mSensorNodeAdapter.setNodeList(NodeScanner.getNodeList());
        mSensorNodeAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PERMISSION_ENABLE_BT) {
            if (resultCode == Activity.RESULT_OK) {
                if (getAutoScanPreference()) {
                    sendStartBleScanBroadcast();
                }
            }
        }
    }

    public boolean getAutoSyncPreference() {
        return PreferenceManager.getDefaultSharedPreferences(
                getApplicationContext()).getBoolean(PREFERENCE_AUTO_SYNC, false);
    }

    public boolean getAutoScanPreference() {
        return PreferenceManager.getDefaultSharedPreferences(
                getApplicationContext()).getBoolean(PREFERENCE_AUTO_SCAN, false);
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
        if (NodeScanner.getInstance(getApplicationContext()).startScanning()) {
            Button btn = findViewById(R.id.scan_nodes);
            btn.setText(R.string.scanning);
            btn.setOnClickListener(mBtnScanStopListener);
        }
    }

    private void sendStopBleScanBroadcast() {
        Log.d(TAG, "sendStopBleScanBroadcast");
        NodeScanner.getInstance(getApplicationContext()).stopScanning();
    }

    ActivityResultLauncher<Intent> activityResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    Log.e(TAG,"activityResultLauncher: OK");
                    if (getAutoScanPreference()) {
                        sendStartBleScanBroadcast();
                    }
                }
            });

    private void checkForBlePermissions() {

        // check if location permissions are granted
        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) !=
                PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                    this,
                    PERMISSIONS_LOCATION,
                    1
            );
        }

        // check if bluetooth is enabled
        BluetoothAdapter bluetoothAdapter =
                ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();

        if(bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            activityResultLauncher.launch(intent);
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
            NodeSyncClient.getInstance(getApplicationContext()).connect();
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

        intentFilter.addAction(Node.ACTION_STATE_CHANGED);
        intentFilter.addAction(Node.ACTION_GATT_READ_BATTERY);
        intentFilter.addAction(Node.ACTION_GATT_READ_TEMPERATURE);
        intentFilter.addAction(Node.ACTION_GATT_READ_MOISTURE);

        return intentFilter;
    }

    private void askMqttCredentials(boolean force) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        if(!preferences.contains(NodeSyncClient.MQTT_CLIENT_ACCESS_TOKEN) || force) {
            openMqttClientAccessTokenSettingsDialog();
        }

        if(!preferences.contains(NodeSyncClient.MQTT_CLIENT_ID) || force) {
            //openMqttClientIdSettingsDialog();
        }

        if(!preferences.contains(NodeSyncClient.MQTT_SERVER_URI) || force) {
            openMqttServerUriSettingsDialog();
        }
    }

    private void sendMqttCredentialsUpdatedBroadcast() {
        Intent intent = new Intent(ACTION_MQTT_CREDENTIALS_UPDATED);
        sendBroadcast(intent);
    }

    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {

        @SuppressLint("SetTextI18n")
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if(BluetoothLeService.ACTION_SENSOR_NODE_FOUND.equals(action)) {
                Log.d(TAG, "received BluetoothLeService.ACTION_SENSOR_NODE_FOUND");
                mSensorNodeAdapter.setNodeList(NodeScanner.getNodeList());
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

            if(Node.ACTION_STATE_CHANGED.equals(action)) {
                mSensorNodeAdapter.notifyDataSetChanged();
            }
        }
    };

    private void openMqttServerUriSettingsDialog() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        String uri = preferences.getString(NodeSyncClient.MQTT_SERVER_URI, "tcp://example.com:1883");

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
            editor.putString(NodeSyncClient.MQTT_SERVER_URI, input.getText().toString()); // value to store
            editor.apply();

            sendMqttCredentialsUpdatedBroadcast();
        });
        builder.setNegativeButton(R.string.cancel, (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void openMqttClientIdSettingsDialog() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        String clientId = preferences.getString(NodeSyncClient.MQTT_CLIENT_ID,
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
            editor.putString(NodeSyncClient.MQTT_CLIENT_ID, input.getText().toString()); // value to store
            editor.apply();

            sendMqttCredentialsUpdatedBroadcast();
        });
        builder.setNegativeButton(R.string.cancel, (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void openMqttClientAccessTokenSettingsDialog() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        String accessToken = preferences.getString(NodeSyncClient.MQTT_CLIENT_ACCESS_TOKEN,
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
            editor.putString(NodeSyncClient.MQTT_CLIENT_ACCESS_TOKEN, input.getText().toString()); // value to store
            editor.apply();

            sendMqttCredentialsUpdatedBroadcast();
        });
        builder.setNegativeButton(R.string.cancel, (dialog, which) -> dialog.cancel());

        builder.show();
    }

}