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

    private static HashMap<String, SensorNode> mSensorNodes = new HashMap<String, SensorNode>();

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
        for (SensorNode node : mSensorNodes.values()) {
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

        delayedStopScanning.run();

        for (NodeScannerListener listener: mNodeScannerListeners) {
            listener.scanStarted();
        }
    }

    public void stopScanning() {
        Log.i(TAG, "stopScanning()");
        mBluetoothLeScanner.stopScan(mScanCallback);
        synchronizeSensorNode();

        for (NodeScannerListener listener: mNodeScannerListeners) {
            listener.scanStopped();
        }
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
                    sensorNode = new SensorNode(mContext);
                    sensorNode.initialize(result);
                    mSensorNodes.put(sensorNode.getRemoteDeviceName(), sensorNode);
                    Log.d(TAG, "Found compatible eddstone URI " + sensorNode.getRemoteDeviceName());
                }

                if (sensorNode != null) {
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

    public void synchronizeSensorNode() {

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
