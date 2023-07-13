package eu.digidrip.connect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

public class SensorNodeActivity extends AppCompatActivity {
    public static final String TAG = SensorNodeActivity.class.getSimpleName();

    private SensorNode mSensorNode;

    private double mLatitude = 0.0;
    private double mLongitude = 0.0;
    private double mAltitude = 0.0;
    private double mAccuracy = 0.0;

    private LocationManager mLocationManager = null;
    private LocationSensorData mSenseDataLocationListener = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sensor_node);

        Bundle extras = getIntent().getExtras();

        mSensorNode = BluetoothLeService.getSensorNodeList().get(extras.getInt("position"));

        TextView nameText = (TextView) findViewById(R.id.sensor_node_name);
        nameText.setText(mSensorNode.getRemoteDeviceName());

        Button closeBtn = (Button) findViewById(R.id.sensor_node_close);
        closeBtn.setOnClickListener(view -> finish());

        final Button btnConnect = (Button) findViewById(R.id.connect_node);
        btnConnect.setOnClickListener(view -> {
            if (mSensorNode.isConnected()) {
                mSensorNode.setSyncEnabled(true);
                mSensorNode.disconnect();
                btnConnect.setText(R.string.Connect);
                //disableSensorDataClicks();

            } else {
                mSensorNode.setSyncEnabled(false);
                mSensorNode.connectAndSyncData();
                btnConnect.setText(R.string.Disconnect);
            }
        });

        Button btn = (Button) findViewById(R.id.sync_time_node);
        btn.setOnClickListener(view -> mSensorNode.connectAndSyncTime());

        btn = (Button) findViewById(R.id.sync_data_node);
        btn.setOnClickListener(view -> mSensorNode.connectAndSyncData());

        btn = (Button) findViewById(R.id.node_send_calibration_dry);
        btn.setOnClickListener(view -> {
            JSONObject data = mSensorNode.serializeGenericAttributesToJson("calibration_dry_moisture", mSensorNode.getMoisture());
            mSensorNode.publishMqttDeviceAttributes(data.toString());

            data = mSensorNode.serializeGenericAttributesToJson("calibration_dry_temperature", mSensorNode.getTemperature());
            mSensorNode.publishMqttDeviceAttributes(data.toString());
        });

        btn = (Button) findViewById(R.id.node_send_calibration_wet);
        btn.setOnClickListener(view -> {
            JSONObject data = mSensorNode.serializeGenericAttributesToJson("calibration_wet_moisture", mSensorNode.getMoisture());
            mSensorNode.publishMqttDeviceAttributes(data.toString());

            data = mSensorNode.serializeGenericAttributesToJson("calibration_wet_temperature", mSensorNode.getTemperature());
            mSensorNode.publishMqttDeviceAttributes(data.toString());
        });

        btn = (Button) findViewById(R.id.node_send_gps_position);
        btn.setOnClickListener(view -> {
            JSONObject data = mSensorNode.serializeGenericAttributesToJson("latitude", mLatitude);
            mSensorNode.publishMqttDeviceAttributes(data.toString());

            data = mSensorNode.serializeGenericTelemetryValuesToJson("latitude", mLatitude);
            mSensorNode.publishMqttDeviceTelemetry(data.toString());

            data = mSensorNode.serializeGenericAttributesToJson("longitude", mLongitude);
            mSensorNode.publishMqttDeviceAttributes(data.toString());

            data = mSensorNode.serializeGenericTelemetryValuesToJson("longitude", mLongitude);
            mSensorNode.publishMqttDeviceTelemetry(data.toString());

            data = mSensorNode.serializeGenericAttributesToJson("altitude", mAltitude);
            mSensorNode.publishMqttDeviceAttributes(data.toString());

            data = mSensorNode.serializeGenericTelemetryValuesToJson("altitude", mAltitude);
            mSensorNode.publishMqttDeviceTelemetry(data.toString());

            data = mSensorNode.serializeGenericAttributesToJson("location_accuracy", mAccuracy);
            mSensorNode.publishMqttDeviceAttributes(data.toString());

            data = mSensorNode.serializeGenericTelemetryValuesToJson("location_accuracy", mAccuracy);
            mSensorNode.publishMqttDeviceTelemetry(data.toString());
        });

        TextView tv = (TextView) findViewById(R.id.tvLabelStatusValue);
        tv.setText(mSensorNode.getStateString());

        registerReceiver(broadcastReceiver, makeUpdateIntentFilter());

        mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        Criteria criteria = new Criteria();
        String provider = mLocationManager.getBestProvider(criteria, false);
        mSenseDataLocationListener = new SensorNodeActivity.LocationSensorData();

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
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        unregisterReceiver(broadcastReceiver);
    }

    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if(BluetoothLeService.ACTION_LOCATION_UPDATE.equals(action)) {
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

            if(SensorNode.ACTION_STATE_CHANGED.equals(action)) {
                TextView tv = (TextView) findViewById(R.id.tvLabelStatusValue);

                tv.setText(mSensorNode.getStateString());

                if(mSensorNode.isConnected()) {
                    enableSensorDataClicks();
                } else {
                    disableSensorDataClicks();
                }
            }

            if(SensorNode.ACTION_GATT_READ_BATTERY.equals(action)) {
                TextView tv = (TextView) findViewById(R.id.tvLabelBatteryValue);
                tv.setText("" + intent.getIntExtra(SensorNode.EXTRA_DATA_BATTERY, 0));
                tv.setTextColor(ContextCompat.getColor(getApplicationContext(),
                        android.R.color.tab_indicator_text));
            }

            if(SensorNode.ACTION_GATT_READ_TEMPERATURE.equals(action)) {
                TextView tv = (TextView) findViewById(R.id.tvLabelTempValue);
                tv.setText("" + mSensorNode.getTemperature() + " °C");
                tv.setTextColor(ContextCompat.getColor(getApplicationContext(),
                        android.R.color.tab_indicator_text));
            }

            if(SensorNode.ACTION_GATT_READ_MOISTURE.equals(action)) {
                TextView tv = (TextView) findViewById(R.id.tvLabelMoistureValue);
                tv.setText("" + mSensorNode.getMoisture());
                tv.setTextColor(ContextCompat.getColor(getApplicationContext(),
                        android.R.color.tab_indicator_text));
            }

            if(SensorNode.ACTION_GATT_DATA_AVAILABLE.equals(action)) {
                TextView tv = (TextView) findViewById(R.id.tvDatasetTimestamp);
                int numDatasets = intent.getIntExtra(SensorNode.EXTRA_DATA_NUM_DATASETS, 0);
                String datasetTimestmap = intent.getStringExtra(SensorNode.EXTRA_DATA_DATASET_TIMESTAMP);
                String datasetMqttString = intent.getStringExtra(SensorNode.EXTRA_DATA_JSON_STRING);
                tv.setText("" + numDatasets + " / " + datasetTimestmap);

                tv = (TextView) findViewById(R.id.tvDatasetMqttString);
                tv.setText(datasetMqttString);
            }
        }
    };

    private static IntentFilter makeUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();

        intentFilter.addAction(BluetoothLeService.ACTION_LOCATION_UPDATE);

        intentFilter.addAction(SensorNode.ACTION_STATE_CHANGED);
        intentFilter.addAction(SensorNode.ACTION_GATT_READ_BATTERY);
        intentFilter.addAction(SensorNode.ACTION_GATT_READ_TEMPERATURE);
        intentFilter.addAction(SensorNode.ACTION_GATT_READ_MOISTURE);
        intentFilter.addAction(SensorNode.ACTION_GATT_DATA_AVAILABLE);

        return intentFilter;
    }

    private void enableSensorDataClicks() {
        TableRow row = (TableRow) findViewById(R.id.tvLabelRowBattery);
        row.setOnClickListener(view -> {
            mSensorNode.readBatteryValue();
            ((TextView)findViewById(R.id.tvLabelBatteryValue)).setTextColor(
                    ContextCompat.getColor(getApplicationContext(), R.color.outdated));
        });

        row = (TableRow) findViewById(R.id.tvLabelRowTemperature);
        row.setOnClickListener(view -> {
            mSensorNode.readTemperatureValue();
            ((TextView)findViewById(R.id.tvLabelTempValue)).setTextColor(
                    ContextCompat.getColor(getApplicationContext(), R.color.outdated));
        });

        row = (TableRow) findViewById(R.id.tvLabelRowMoisture);
        row.setOnClickListener(view -> {
            mSensorNode.readMoistureValue();
            ((TextView)findViewById(R.id.tvLabelMoistureValue)).setTextColor(
                    ContextCompat.getColor(getApplicationContext(), R.color.outdated));
        });
    }

    private void disableSensorDataClicks() {
        TableRow row = (TableRow) findViewById(R.id.tvLabelRowBattery);
        row.setOnClickListener(null);

        row = (TableRow) findViewById(R.id.tvLabelRowTemperature);
        row.setOnClickListener(null);

        row = (TableRow) findViewById(R.id.tvLabelRowMoisture);
        row.setOnClickListener(null);
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
