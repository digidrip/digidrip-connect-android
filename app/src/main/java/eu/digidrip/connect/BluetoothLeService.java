package eu.digidrip.connect;

import android.app.Service;
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
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class BluetoothLeService extends Service {
    public static final String TAG = BluetoothLeService.class.getSimpleName();

    private final IBinder mBinder = new LocalBinder();

    private static final long SCAN_PAUSE_PERIOD = 50000;

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

    private LocationManager mLocationManager;
    private LocationSensorData mSenseDataLocationListener;

    public BluetoothLeService() {
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate()");

        mHandler = new Handler();

        NodeScanner.getInstance(getApplicationContext()).addNodeScannerListener(mNodeScannerListener);

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
        NodeScanner.getInstance(getApplicationContext()).removeNodeScannerListener(mNodeScannerListener);
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
        NodeScanner.getInstance(getApplicationContext()).removeAndDisconnectNodes();
        return super.onUnbind(intent);
    }

    public void startScanning() {
        NodeScanner.getInstance(getApplicationContext()).startScanning();
    }

    public void stopScanning() {
        NodeScanner.getInstance(getApplicationContext()).stopScanning();
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            Log.d(TAG, "onReceive()");

            if (action.equals(MainActivity.ACTION_START_SCAN)) {
                startScanning();
                return;
            }

            if (action.equals(MainActivity.ACTION_STOP_SCAN)) {
                stopScanning();
                return;
            }

            if (action.equals(Node.ACTION_GATT_DISCONNECTED)) {
                //TODO
            }
        }
    };

    private IntentFilter makeIntentFilter() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(MainActivity.ACTION_START_SCAN);
        intentFilter.addAction(MainActivity.ACTION_STOP_SCAN);
        intentFilter.addAction(Node.ACTION_GATT_DISCONNECTED);
        return intentFilter;
    }

    private NodeScannerListener mNodeScannerListener = new NodeScannerListener() {

        @Override
        public void scanStarted() {
            final Intent intent = new Intent(ACTION_SCANNING);
            sendBroadcast(intent);
        }

        @Override
        public void scanStopped() {
            final Intent intent = new Intent(ACTION_SCAN_STOPPED);
            sendBroadcast(intent);
        }

        @Override
        public void foundNode() {
            final Intent intent = new Intent(ACTION_SENSOR_NODE_FOUND);
            sendBroadcast(intent);
        }
    };

    private class LocationSensorData implements LocationListener {

        private List<Node> mBeacons;
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

        private JSONArray serializeJsonFromBeacons(List<Node> beacons)
        {
            JSONArray bObjects = new JSONArray();

            for(Node s: beacons)
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
}