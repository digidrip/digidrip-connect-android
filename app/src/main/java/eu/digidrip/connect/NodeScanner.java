package eu.digidrip.connect;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.preference.PreferenceManager;
import android.util.Log;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class NodeScanner {

    public static final String TAG = NodeScanner.class.getSimpleName();

    public final UUID UUID_EDDYSTONE =
            UUID.fromString("0000feaa-0000-1000-8000-00805f9b34fb");

    public static final String URI_EDDYSTONE_DIGIDRIP = new String((char) 0x01 + "digidrip.eu/");

    public static final String URI_EDDYSTONE_AFARCLOUD = new String((char) 0x00 + "afarcloud.eu/");

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private android.bluetooth.le.BluetoothLeScanner mBluetoothLeScanner;

    private Handler mHandler;

    private static HashMap<String, Node> mSensorNodes = new HashMap<String, Node>();

    private List<NodeScannerListener> mNodeScannerListeners = new ArrayList<>();

    private static NodeScanner scanner = null;

    private Context mContext = null;

    private NodeScanner(Context context) {
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());
    }

    public static NodeScanner getInstance(Context context) {
        if (scanner == null) {
            scanner = new NodeScanner(context);
        }

        if (scanner.mBluetoothLeScanner == null) {
            scanner.initBle();
        }

        return scanner;
    }

    private void initBle() {
        if (mBluetoothManager == null) {
            mBluetoothManager =
                    (BluetoothManager) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
            }
        }

        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
        }

        mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();
    }

    public void removeAndDisconnectNodes() {
        for (Node node : mSensorNodes.values()) {
            mSensorNodes.remove(node.getRemoteDeviceName());
            node.disconnect();
        }
    }

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

    public void addNodeScannerListener(NodeScannerListener listener) {
        for (NodeScannerListener l: mNodeScannerListeners) {
            if (l == listener) {
                return;
            }
        }
        mNodeScannerListeners.add(listener);
    }

    public void removeNodeScannerListener(NodeScannerListener listener) {
        mNodeScannerListeners.remove(listener);
    }

    public boolean startScanning() {
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

        if (mBluetoothLeScanner == null) {
            Log.e(TAG, "cant't start scanning, no BLE device found");
            return false;
        }

        mBluetoothLeScanner.startScan(filters, settings, mScanCallback);

        delayedStopScanning.run();

        for (NodeScannerListener listener: mNodeScannerListeners) {
            listener.scanStarted();
        }

        return true;
    }

    public boolean stopScanning() {
        Log.i(TAG, "stopScanning()");

        if (mBluetoothLeScanner == null) {
            Log.e(TAG, "no BLE device available, can't stop scanning");
            return false;
        }

        mBluetoothLeScanner.stopScan(mScanCallback);
        synchronizeSensorNodes();

        for (NodeScannerListener listener: mNodeScannerListeners) {
            listener.scanStopped();
        }

        return true;
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

                Node node = null;

                if(mSensorNodes.containsKey(result.getDevice().getAddress())) {
                    node = mSensorNodes.get(result.getDevice().getAddress());
                    assert node != null;
                    node.initialize(result);
                }

                else if (result.getScanRecord().getDeviceName() != null
                        && checkEddystoneServiceUuids(result)) {
                    node = new Node(mContext);
                    node.initialize(result);
                    mSensorNodes.put(node.getRemoteDeviceName(), node);
                    Log.d(TAG, "Found compatible eddstone URI " + node.getRemoteDeviceName());
                }

                if (node != null) {
                    for (NodeScannerListener listener: mNodeScannerListeners) {
                        listener.foundNode();
                    }
                }
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.i(TAG, "onScanFailed()");
        }
    };

    public void synchronizeSensorNodes() {

        if (!getAutoSyncPreference()) {
            Log.d(TAG, "auto sync disabled, not synchronizing");
            return;
        }

        Log.d(TAG, "synchronizeSensorNodes()");
        long now = Instant.now().getEpochSecond() - 3600;

        for (Node node: mSensorNodes.values()) {
            if (node.isConnected()) {
                return;
            }
        }

        for (Node node : mSensorNodes.values()) {
            if (node.getTimeLastSync() < now) {
                Log.d(TAG, "start synchronization of " + node.getRemoteDeviceName());
                node.connectAndSyncData();
                return;
            }
        }

        Log.d(TAG, "synchronizeSensorNodes() finished");
    }

    public static List<Node> getSensorNodeList() {
        ArrayList<Node> nodes = new ArrayList(mSensorNodes.values());

        nodes.sort((s1, s2) -> {
            if (s1.getRssi() == s2.getRssi()) {
                return 0;
            }
            return s1.getRssi() > s2.getRssi() ? -1 : 1; // if you want to short by name
        });
        return nodes;
    }

    public boolean getAutoSyncPreference() {
        return PreferenceManager.getDefaultSharedPreferences(
                mContext).getBoolean(MainActivity.PREFERENCE_AUTO_SYNC, false);
    }

    public boolean getAutoScanPreference() {
        return PreferenceManager.getDefaultSharedPreferences(
                mContext).getBoolean(MainActivity.PREFERENCE_AUTO_SCAN, false);
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
