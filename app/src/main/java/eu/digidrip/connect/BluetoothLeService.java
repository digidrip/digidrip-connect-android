package eu.digidrip.connect;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class BluetoothLeService extends Service {
    public static final String TAG = BluetoothLeService.class.getSimpleName();

    public final UUID UUID_EDDYSTONE =
            UUID.fromString("0000feaa-0000-1000-8000-00805f9b34fb");

    public static final String URI_EDDYSTONE_DIGIDRIP = new String((char) 0x01 + "digidrip.eu/");

    public static final String URI_EDDYSTONE_AFARCLOUD = new String((char) 0x00 + "afarcloud.eu/");

    private final IBinder mBinder = new LocalBinder();

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBluetoothLeScanner;

    private Runnable delayedStartScanning = () -> startScanning();

    private final Runnable delayedRestartScanning = new Runnable() {
        @Override
        public void run() {
            stopScanning();
            mHandler.postDelayed(delayedStartScanning, SCAN_PAUSE_PERIOD);
        }
    };

    private Runnable delayedStopScanning = new Runnable() {
        @Override
        public void run() {
            Runnable stopScanningHandler = new Runnable() {
                @Override
                public void run() {
                    stopScanning();
                }
            };
            mHandler.postDelayed(stopScanningHandler, 15000);
        }
    };

    private Handler mHandler;

    private static final long SCAN_PAUSE_PERIOD = 50000;
    public static final String EXTRA_DATA_LATI =
            TAG + ".EXTRA_DATA_LATI";
    public static final String EXTRA_DATA_LONG =
            TAG + ".EXTRA_DATA_LONG";
    public static final String EXTRA_DATA_ACCU =
            TAG + ".EXTRA_DATA_ACCU";
    public static final String EXTRA_DATA_ALTI =
            TAG + ".EXTRA_DATA_ALTI";

    public static final String ACTION_LOCATION_UPDATE =
            TAG + ".ACTION_LOCATION_UPDATE";

    public static final String ACTION_SENSOR_NODE_FOUND =
            TAG + ".ACTION_SENSOR_NODE_FOUND";

    public static final String ACTION_BEACONS_FOUND =
            BluetoothLeService.class.getSimpleName() + ".ACTION_BEACONS_FOUND";
    public static final String EXTRA_DATA_BEACONS_JSON_STRING =
            TAG + ".EXTRA_DATA_BEACONS_JSON_STRING";

    public static final String ACTION_SCANNING =
            TAG + ".ACTION_SCANNING";
    public static final String ACTION_SCAN_STOPPED =
            TAG + ".ACTION_SCAN_STOPPED";

    private static HashMap<String, SensorNode> mSensorNodes = new HashMap<String, SensorNode>();
    private LocationManager mLocationManager;
    private LocationSensorData mSenseDataLocationListener;

    public BluetoothLeService() {
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate()");

        mHandler = new Handler();

        if (mBluetoothManager == null) {
            mBluetoothManager =
                    (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
            }
        }

        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
        }

        mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();

        registerReceiver(mBroadcastReceiver, makeIntentFilter());

        mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        Criteria criteria = new Criteria();
        String provider = mLocationManager.getBestProvider(criteria, false);
        mSenseDataLocationListener = new LocationSensorData();
        try {
            mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    5000, 25, mSenseDataLocationListener, null);
        } catch (SecurityException se) {
            Log.e(TAG, "No location permissions granted.");
        }

        if (provider != null && !provider.equals("")) {
            if (!provider.contains("GPS")) {
                final Intent poke = new Intent();
                poke.setClassName("com.android.settings",
                        "com.android.settings.widget.SettingsAppWidgetProvider");
                poke.addCategory(Intent.CATEGORY_ALTERNATIVE);
                poke.setData(Uri.parse("3"));
                sendBroadcast(poke);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy()");
        stopScanning();
        unregisterReceiver(mBroadcastReceiver);
        mHandler.removeCallbacks(delayedStartScanning);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public static class LocalBinder extends Binder {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        for (SensorNode node : mSensorNodes.values()) {
            mSensorNodes.remove(node.getRemoteDeviceName());
            node.disconnect();
        }
        return super.onUnbind(intent);
    }

    public void startScanning() {
        Log.d(TAG, "startScanning()");

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(100)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .build();
        List<ScanFilter> filters = new ArrayList<>();
        ScanFilter scanFilter = new ScanFilter.Builder()
                .build();
        filters.add(scanFilter);

        mBluetoothLeScanner.startScan(filters, settings, mScanCallback);

        final Intent intent = new Intent(ACTION_SCANNING);
        sendBroadcast(intent);
    }

    public void stopScanning() {
        Log.i(TAG, "stopScanning()");
        mBluetoothLeScanner.stopScan(mScanCallback);
        final Intent intent = new Intent(ACTION_SCAN_STOPPED);
        sendBroadcast(intent);
        startSynchronizationOfSensorNode();
    }

    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);

            Log.d(TAG, "onScanResult(): "
                    + result.getDevice().getName()
                    + result.getDevice().getAddress());
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
            Log.d(TAG, "onBatchScanResults()");

            for(ScanResult result: results) {

                SensorNode sensorNode = null;

                if(mSensorNodes.containsKey(result.getDevice().getAddress())) {
                    sensorNode = mSensorNodes.get(result.getDevice().getAddress());
                    assert sensorNode != null;
                    sensorNode.initialize(result);
                }

                else if (result.getScanRecord().getDeviceName() != null
                        && checkEddystoneServiceUuids(result)) {
                    sensorNode = new SensorNode(getBaseContext());
                    sensorNode.initialize(result);
                    mSensorNodes.put(sensorNode.getRemoteDeviceName(), sensorNode);
                    Log.d(TAG, "Found compatible eddstone URI " + sensorNode.getRemoteDeviceName());
                }

                if (sensorNode != null) {
                    final Intent intent = new Intent(ACTION_SENSOR_NODE_FOUND);
                    sendBroadcast(intent);
                }
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.i(TAG, "onScanFailed()");
        }
    };

    private void startSynchronizationOfSensorNode() {

        long now = Instant.now().getEpochSecond() - 3600;

        for (SensorNode node: mSensorNodes.values()) {
            if (node.isConnected()) {
                return;
            }
        }

        for (SensorNode sensorNode: mSensorNodes.values()) {
            if (sensorNode.getTimeLastSync() < now) {
                sensorNode.connectAndSyncData();
                return;
            }
        }
    }

    public static List<SensorNode> getSensorNodeList() {
        ArrayList<SensorNode> sensorNodes = new ArrayList(mSensorNodes.values());

        sensorNodes.sort((s1, s2) -> {
            if (s1.getRssi() == s2.getRssi()) {
                return 0;
            }
            return s1.getRssi() > s2.getRssi() ? -1 : 1; // if you want to short by name
        });
        return sensorNodes;
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            Log.d(TAG, "onReceive()");

            if (action.equals(MainActivity.ACTION_START_SCAN)) {
                if (mBluetoothLeScanner == null)
                    mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();
                if (mBluetoothAdapter.isEnabled()) {
                    startScanning();
                    delayedStopScanning.run();
                }
                return;
            }

            if (action.equals(MainActivity.ACTION_STOP_SCAN)) {
                if (mBluetoothLeScanner == null)
                    return;
                if (mBluetoothAdapter.isEnabled())
                    stopScanning();
                return;
            }

            if (action.equals(SensorNode.ACTION_GATT_DISCONNECTED)) {
                startSynchronizationOfSensorNode();
            }
        }
    };

    private IntentFilter makeIntentFilter() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(MainActivity.ACTION_START_SCAN);
        intentFilter.addAction(MainActivity.ACTION_STOP_SCAN);
        intentFilter.addAction(SensorNode.ACTION_GATT_DISCONNECTED);
        return intentFilter;
    }

    private class LocationSensorData implements LocationListener {

        private List<SensorNode> mBeacons;
        private long mScanTime;

        public LocationSensorData() {
            mScanTime = 0;
            mBeacons = null;
        }

        @Override
        public void onLocationChanged(Location location) {
            Intent locationIntent = new Intent(ACTION_LOCATION_UPDATE);

            locationIntent.putExtra(EXTRA_DATA_LATI, location.getLatitude());
            Log.d(TAG, "latitude:  " + location.getLatitude());

            locationIntent.putExtra(EXTRA_DATA_LONG, location.getLongitude());
            Log.d(TAG, "longitude: " + location.getLongitude());

            if(location.hasAccuracy()) {
                locationIntent.putExtra(EXTRA_DATA_ACCU, location.getAccuracy());
                Log.d(TAG, "accuracy: " + location.getAccuracy());
            }
            if(location.hasAltitude()) {
                locationIntent.putExtra(EXTRA_DATA_ALTI, location.getAltitude());
                Log.d(TAG, "altitude: " + location.getAltitude());
            }

            sendBroadcast(locationIntent);

            JSONObject parentObject = new JSONObject();
            JSONObject object = new JSONObject();
            JSONArray objectArray = new JSONArray();
            try {
                object.put("loc_time", System.currentTimeMillis());
                object.put("latitude", location.getLatitude());
                object.put("longitude", location.getLongitude());
                if(location.hasAccuracy())
                    object.put("loc_accuracy", location.getAccuracy());
                if(location.hasAltitude())
                    object.put("altitude", location.getAltitude());
                if(mScanTime != 0) {
                    object.put("scan_time", mScanTime);
                }
                if (mBeacons != null) {
                    object.put("devices",
                            android.util.Base64.encodeToString(
                                    serializeJsonFromBeacons(mBeacons).toString().getBytes(StandardCharsets.UTF_8), 16).replaceAll("\n", ""));
                    mBeacons = null;
                    mScanTime = 0;
                }
                parentObject.put("ts", System.currentTimeMillis());
                parentObject.put("values", object);

                objectArray.put(parentObject);
            } catch(JSONException e) {
                Log.e(TAG, e.getMessage());
            }

            final Intent intent = new Intent(ACTION_BEACONS_FOUND);
            intent.putExtra(EXTRA_DATA_BEACONS_JSON_STRING,
                    objectArray.toString());
            sendBroadcast(intent);
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

        private JSONArray serializeJsonFromBeacons(List<SensorNode> beacons)
        {
            JSONArray bObjects = new JSONArray();

            for(SensorNode s: beacons)
            {
                JSONObject bObject = new JSONObject();
                try {
                    bObject.put(s.getRemoteDeviceName(), s.getRssi());
                } catch (JSONException e) {
                    Log.e(TAG, e.getMessage());
                }
                bObjects.put(bObject);
            }

            return bObjects;
        }
    }

    private boolean checkEddystoneServiceUuids(ScanResult result) {

        if (result.getScanRecord().getServiceUuids() == null)
            return false;

        for (ParcelUuid serviceUuid: result.getScanRecord().getServiceUuids()) {
            if (serviceUuid.getUuid().compareTo(UUID_EDDYSTONE) == 0) {
                byte[] data = result.getScanRecord().getServiceData(serviceUuid);
                byte frameType = data[0];

                if(frameType != 0x10) {
                    return false;
                }

                String uri_ = new String(data, 2, data.length - 2);

                Log.d(TAG, result.getDevice().getAddress()
                        + ": " + serviceUuid.getUuid().toString()
                        + " = " + uri_);

                if (uri_.compareTo(URI_EDDYSTONE_AFARCLOUD) == 0)
                    return true;
                if (uri_.compareTo(URI_EDDYSTONE_DIGIDRIP) == 0)
                    return true;
            }
        }

        return false;
    }
}