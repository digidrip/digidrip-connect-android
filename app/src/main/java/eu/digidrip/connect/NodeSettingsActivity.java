package eu.digidrip.connect;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

public class NodeSettingsActivity extends AppCompatActivity {
    public static final String TAG = NodeActivity.class.getSimpleName();

    private Node mNode;

    private double mLatitude = 0.0;
    private double mLongitude = 0.0;
    private double mAltitude = 0.0;
    private double mAccuracy = 0.0;

    private boolean calibrateDry = true;

    private LocationManager mLocationManager = null;
    private NodeSettingsActivity.LocationSensorData mSenseDataLocationListener = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_node_settings);

        Bundle extras = getIntent().getExtras();
        mNode = NodeScanner.getNode(extras.getString("address"));

        setTitle(String.format("%s: Settings", mNode.getRemoteDeviceName()));

        registerReceiver(broadcastReceiver, makeUpdateIntentFilter());

        final Button btnConnect = (Button) findViewById(R.id.btn_node_connect);
        btnConnect.setOnClickListener(view -> {
            if (mNode.isConnected()) {
                mNode.setSyncEnabled(true);
                mNode.disconnect();
                btnConnect.setText(R.string.Connect);
                //disableSensorDataClicks();

            } else {
                mNode.setSyncEnabled(false);
                mNode.connectAndSyncData();
                btnConnect.setText(R.string.Disconnect);
            }
        });

        Button btn;

        btn = findViewById(R.id.button_node_settings_sensor_wet);
        btn.setOnClickListener(view -> {
            calibrateDry = false;
            mNode.readRawMoistureValue();
        });

        btn = findViewById(R.id.button_node_settings_sensor_dry);
        btn.setOnClickListener(view -> {
            calibrateDry = true;
            mNode.readRawMoistureValue();
        });

        btn = findViewById(R.id.node_send_gps_position);
        btn.setOnClickListener(view -> {
            JSONObject data = mNode.serializeGenericAttributesToJson("latitude", mLatitude);
            mNode.publishMqttDeviceAttributes(data.toString());

            data = mNode.serializeGenericTelemetryValuesToJson("latitude", mLatitude);
            mNode.publishMqttDeviceTelemetry(data.toString());

            data = mNode.serializeGenericAttributesToJson("longitude", mLongitude);
            mNode.publishMqttDeviceAttributes(data.toString());

            data = mNode.serializeGenericTelemetryValuesToJson("longitude", mLongitude);
            mNode.publishMqttDeviceTelemetry(data.toString());

            data = mNode.serializeGenericAttributesToJson("altitude", mAltitude);
            mNode.publishMqttDeviceAttributes(data.toString());

            data = mNode.serializeGenericTelemetryValuesToJson("altitude", mAltitude);
            mNode.publishMqttDeviceTelemetry(data.toString());

            data = mNode.serializeGenericAttributesToJson("location_accuracy", mAccuracy);
            mNode.publishMqttDeviceAttributes(data.toString());

            data = mNode.serializeGenericTelemetryValuesToJson("location_accuracy", mAccuracy);
            mNode.publishMqttDeviceTelemetry(data.toString());
        });

        mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        Criteria criteria = new Criteria();
        String provider = mLocationManager.getBestProvider(criteria, false);
        mSenseDataLocationListener = new NodeSettingsActivity.LocationSensorData();

        if(provider != null && !provider.equals("")) {
            if(!provider.contains("GPS")) {
                final Intent poke = new Intent();
                poke.setClassName("com.android.settings",
                        "com.android.settings.widget.SettingsAppWidgetProvider");
                poke.addCategory(Intent.CATEGORY_ALTERNATIVE);
                poke.setData(Uri.parse("3"));
                sendBroadcast(poke);
            }
        }

        btn = (Button) findViewById(R.id.node_get_gps_position);
        btn.setOnClickListener(view -> {
            try {
                mLocationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER,
                        mSenseDataLocationListener, null);
            } catch (SecurityException se) {
                Log.e(TAG, "No location permissions granted.");
            }
        });

        btn = findViewById(R.id.calib_open_open);
        btn.setOnClickListener(view -> {
            mNode.writeActuatorCalibration(3);
        });

        btn = findViewById(R.id.calib_open_close);
        btn.setOnClickListener(view -> {
            mNode.writeActuatorCalibration(4);
        });

        btn = findViewById(R.id.calib_close_open);
        btn.setOnClickListener(view -> {
            mNode.writeActuatorCalibration(6);
        });

        btn = findViewById(R.id.calib_close_close);
        btn.setOnClickListener(view -> {
            mNode.writeActuatorCalibration(5);
        });

        updateUI();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.activity_node_settings_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_close:
                finish();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateUI() {
        Button btn;
        LinearLayout layout;

        layout = findViewById(R.id.layout_node_settings_sensor_calibration);
        layout.setVisibility(mNode.hasActuator() ? View.VISIBLE : View.GONE);

        btn = findViewById(R.id.button_node_settings_sensor_dry);
        btn.setEnabled(mNode.isConnected());

        btn = findViewById(R.id.button_node_settings_sensor_wet);
        btn.setEnabled(mNode.isConnected());

        layout = findViewById(R.id.node_calib_actuator_layout);
        layout.setVisibility(mNode.hasActuator() ? View.VISIBLE : View.GONE);

        btn = findViewById(R.id.calib_open_open);
        btn.setEnabled(mNode.isConnected() && mNode.hasActuator());

        btn = findViewById(R.id.calib_open_close);
        btn.setEnabled(mNode.isConnected() && mNode.hasActuator());

        btn = findViewById(R.id.calib_close_open);
        btn.setEnabled(mNode.isConnected() && mNode.hasActuator());

        btn = findViewById(R.id.calib_close_close);
        btn.setEnabled(mNode.isConnected() && mNode.hasActuator());

        btn = findViewById(R.id.btn_node_connect);
        btn.setText(mNode.isConnected() ? R.string.Disconnect : R.string.Connect);
    }

    private void sensorCalibrationDialogWet(int value) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Wet sensor calibration");

        TextView tv = new TextView(this);

        tv.setText(String.format(getResources().getString(R.string.calibration_store_text), value));
        tv.setGravity(Gravity.CENTER);
        builder.setView(tv);

        builder.setPositiveButton(R.string.Yes, (dialog, which) -> {
            mNode.writeSensorCalibration(0x11);
        });
        builder.setNegativeButton(R.string.No, (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void sensorCalibrationDialogDry(int value) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Dry sensor calibration");

        TextView tv = new TextView(this);

        tv.setText(String.format(getResources().getString(R.string.calibration_store_text), value));
        tv.setGravity(Gravity.CENTER);
        builder.setView(tv);

        builder.setPositiveButton(R.string.Yes, (dialog, which) -> {
            mNode.writeSensorCalibration(0x21);
        });
        builder.setNegativeButton(R.string.No, (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (BluetoothLeService.ACTION_LOCATION_UPDATE.equals(action)) {
                mLatitude = intent.getDoubleExtra(BluetoothLeService.EXTRA_DATA_LATI, 0.0);
                mLongitude = intent.getDoubleExtra(BluetoothLeService.EXTRA_DATA_LONG, 0.0);
                mAccuracy = intent.getFloatExtra(BluetoothLeService.EXTRA_DATA_ACCU, 0.0f);
                mAltitude = intent.getDoubleExtra(BluetoothLeService.EXTRA_DATA_ALTI, 0.0);

                TextView tv = (TextView) findViewById(R.id.tvLabelGeneralLatitdue);
                tv.setText(" " + mLatitude);

                tv = (TextView) findViewById(R.id.tvLabelGeneralLongitude);
                tv.setText(" " + mLongitude);

                tv = (TextView) findViewById(R.id.tvLabelGeneralAccuracy);
                tv.setText(" " + mAccuracy);
            }

            if(Node.ACTION_STATE_CHANGED.equals(action)) {
                updateUI();
            }

            if (Node.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                updateUI();
            }

            if (Node.ACTION_DATA_AVAILABLE.equals(action)) {
                updateUI();
            }

            if (Node.ACTION_GATT_READ_MOISTURE_DIFF_RAW.equals(action)) {
                if (calibrateDry) {
                    sensorCalibrationDialogDry(mNode.getRawMoisture());
                }
                else {
                    sensorCalibrationDialogWet(mNode.getRawMoisture());
                }
            }
        }
    };

    private static IntentFilter makeUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();

        intentFilter.addAction(BluetoothLeService.ACTION_LOCATION_UPDATE);

        intentFilter.addAction(Node.ACTION_STATE_CHANGED);
        intentFilter.addAction(Node.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(Node.ACTION_GATT_DATA_AVAILABLE);
        intentFilter.addAction(Node.ACTION_GATT_READ_ACTUATOR_STATE);
        intentFilter.addAction(Node.ACTION_DATA_AVAILABLE);
        intentFilter.addAction(Node.ACTION_GATT_READ_MOISTURE_DIFF_RAW);

        return intentFilter;
    }

    private class LocationSensorData implements LocationListener {

        @Override
        public void onLocationChanged(Location location) {
            TextView tv;

            mLatitude = location.getLatitude();
            tv = (TextView) findViewById(R.id.tvLabelGeneralLatitdue);
            tv.setText("" + mLatitude);

            mLongitude = location.getLongitude();
            tv = (TextView) findViewById(R.id.tvLabelGeneralLongitude);
            tv.setText("" + mLongitude);

            if(location.hasAccuracy()) {
                mAccuracy = location.getAccuracy();
                tv = (TextView) findViewById(R.id.tvLabelGeneralAccuracy);
                tv.setText("" + mAccuracy);
            }
        }

        @Override
        public void onStatusChanged(String s, int i, Bundle bundle) {
            Log.d(TAG, "onStatusChanged");
            updateUI();
        }
        @Override
        public void onProviderEnabled(String s) {
            Log.d(TAG, "onProviderEnabled");
        }

        @Override
        public void onProviderDisabled(String s) {
            Log.d(TAG, "onProviderDisabled");
        }
    }
}
