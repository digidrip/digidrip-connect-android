package eu.digidrip.connect;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.DateFormat;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.Semaphore;

public class SensorNode {

    public static final String TAG = SensorNode.class.getSimpleName();

    private final Context mContext;
    private final Handler mHandler;
    private ScanResult mScanResult = null;

    private final Semaphore mTimestampSemaphore = new Semaphore(1);

    private BluetoothGatt mBluetoothGatt;

    private BluetoothGattCharacteristic mCTSCharacteristics = null;
    private BluetoothGattCharacteristic mBASCharacteristics = null;
    private BluetoothGattCharacteristic mTempCharacteristics = null;
    private BluetoothGattCharacteristic mHumiCharacteristics = null;
    private BluetoothGattCharacteristic mSyncCharacteristics = null;
    private BluetoothGattCharacteristic mNameCharacteristics = null;

    private String mDeviceName = null;

    private final Stack<BluetoothGattCharacteristic> mReadCharacteristics = new Stack<>();

    private long mTsCtsCalled;
    private long mTsConnectStart;
    private int  mSyncedDataSets;

    private boolean mAbortDataSynchronization = false;

    public static final int CTS_DATA        = 0b1;
    public static final int BAS_DATA        = 0b10;
    public static final int TEMP_DATA       = 0b100;
    public static final int HUMI_DATA       = 0b1000;
    public static final int RAIN_DATA       = 0b10000;
    public static final int CTS_REF_DATA    = 0b100000;

    private int mAllData = 0;

    JSONObject mSensorDataJson;

    private int mSyncedDataFlags = 0;

    private float mTempValue = 0.0f;
    private int mMoistureValue = 0;

    private int mConnectionState = BluetoothProfile.STATE_DISCONNECTED;

    private boolean mSyncFinished = false;

    private long mTimeLastSync = 0;

    private boolean mSyncEnabled = true;
    private boolean mIsSynchronizing = false;
    private boolean mSyncFailed = false;

    public final static String ACTION_GATT_CONNECTED =
            TAG + ".ACTION_GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED =
            TAG + ".ACTION_GATT_DISCONNECTED";
    public final static String ACTION_GATT_SERVICES_DISCOVERED =
            TAG + ".ACTION_GATT_SERVICES_DISCOVERED";
    public final static String ACTION_GATT_DATA_AVAILABLE =
            TAG + ".ACTION_GATT_DATA_AVAILABLE";
    public final static String EXTRA_DATA_JSON_STRING =
            TAG + ".EXTRA_DATA_JSON_STRING";
    public final static String EXTRA_DATA_DEVICE_ADDRESS =
            TAG + ".EXTRA_DATA_DEVICE_ADDRESS";
    public final static String EXTRA_DATA_DEVICE_ATTRIBUTES_JSON_STRING =
            TAG + ".EXTRA_DATA_DEVICE_ATTRIBUTES_JSON_STRING";
    public final static String EXTRA_DATA_NUM_DATASETS =
            TAG + ".EXTRA_DATA_NUM_DATASETS";
    public final static String EXTRA_DATA_DATASET_TIMESTAMP =
            TAG + ".EXTRA_DATA_DATASET_TIMESTAMP";


    public final static String ACTION_GATT_READ_BATTERY =
            TAG + ".ACTION_GATT_READ_BATTERY";
    public final static String EXTRA_DATA_BATTERY =
            TAG + ".EXTRA_DATA_BATTERY";

    public final static String ACTION_GATT_READ_TEMPERATURE =
            TAG + ".ACTION_GATT_READ_TEMPERATURE";
    public final static String EXTRA_DATA_TEMPERATURE =
            TAG + ".EXTRA_DATA_TEMPERATURE";

    public final static String ACTION_GATT_READ_MOISTURE =
            TAG + ".ACTION_GATT_READ_MOISTURE";
    public final static String EXTRA_DATA_MOISTURE =
            TAG + ".EXTRA_DATA_MOISTURE";

    public final static String ACTION_STATE_CHANGED =
            TAG + ".ACTION_STATE_CHANGED";

    public final static String ACTION_PUBLISH_ATTRIBUTE =
            TAG + ".ACTION_PUBLISH_ATTRIBUTE";

    public SensorNode(Context context) {
        mContext = context;
        mHandler = new Handler(mContext.getMainLooper());
    }

    public void initialize(ScanResult result) {
        mScanResult = result;
        reconnectAttempts = 0;
        if (mDeviceName == null) {
            mDeviceName = getRemoteDeviceName();
        }
        broadcastStateChanged();
    }

    public void connectAndSyncData() throws SecurityException {
        mHandler.post(() -> {

            mSyncFinished = false;
            mSyncFailed = false;

            if (isSynchronizing()) {
                mAbortDataSynchronization = true;
                mHandler.postDelayed(this::disconnect, 1000);
            }
            if(mConnectionState != BluetoothProfile.STATE_DISCONNECTED)
                return;
            mBluetoothGatt = mScanResult.getDevice().connectGatt(mContext,
                    false, getGattCallback(), BluetoothDevice.TRANSPORT_LE);
            mConnectionState = BluetoothProfile.STATE_CONNECTING;
            if (mBluetoothGatt.connect()) {
                Log.i(TAG, "connecting to " + mScanResult.getDevice().getAddress());
            } else {
                Log.e(TAG, "error connecting to " + mScanResult.getDevice().getAddress());
            }

            broadcastStateChanged();
        });
    }

    public void connectAndSyncTime() throws SecurityException {
        mHandler.post(() -> {

            if(mConnectionState != BluetoothProfile.STATE_DISCONNECTED)
                return;

            mBluetoothGatt = mScanResult.getDevice().connectGatt(mContext,
                    false, new BluetoothSyncTimeGattCallback(), BluetoothDevice.TRANSPORT_LE);
            mConnectionState = BluetoothProfile.STATE_CONNECTING;
            if (mBluetoothGatt.connect()) {
                Log.i(TAG, "connecting to " + mScanResult.getDevice().getAddress());
            } else {
                Log.e(TAG, "error connecting to " + mScanResult.getDevice().getAddress());
            }

            broadcastStateChanged();
        });
    }

    public void disconnect() throws SecurityException {
        Log.d(TAG, "disconnecting; state = " + mConnectionState);

        if(mConnectionState == BluetoothProfile.STATE_DISCONNECTED || mConnectionState == BluetoothProfile.STATE_DISCONNECTING)
            return;

        mConnectionState = BluetoothProfile.STATE_DISCONNECTING;

        mHandler.post(() -> {
            mBluetoothGatt.disconnect();
            mBluetoothGatt.close();
        });

        mCTSCharacteristics = null;
        mBASCharacteristics = null;
        mHumiCharacteristics = null;
        mTempCharacteristics = null;
        mSyncCharacteristics = null;
        mNameCharacteristics = null;
        mReadCharacteristics.empty();
        mAllData = 0;

        final Intent intent = new Intent(ACTION_GATT_DISCONNECTED);
        mContext.sendBroadcast(intent);

        broadcastStateChanged();
    }

    private int reconnectAttempts = 0;

    private class BluetoothSyncDataGattCallback extends BluetoothGattCallback {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) throws SecurityException {
            super.onConnectionStateChange(gatt, status, newState);

            Intent intent = new Intent();

            Log.d(TAG, "onConnectionStateChange() - status=" + status + " newState=" + newState);

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mTsConnectStart = System.currentTimeMillis();

                Log.i(TAG, "connected to " + getRemoteDeviceName());
                mConnectionState = newState;

                mHandler.post(() -> {
                    if (mBluetoothGatt.discoverServices())
                        Log.i(TAG, "discovering services");
                });

                intent.setAction(ACTION_GATT_CONNECTED);
                intent.putExtra(EXTRA_DATA_DEVICE_ADDRESS, gatt.getDevice().getAddress());
                mContext.sendBroadcast(intent);

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "disconnected from " + getRemoteDeviceName() + " (status: " + status + ")");
                if ((mConnectionState == BluetoothProfile.STATE_CONNECTED)
                        && reconnectAttempts < 3) {
                    reconnectAttempts++;
                    mConnectionState = newState;
                    Log.i(TAG, "lost connection, trying to reconnect to " + getRemoteDeviceName()
                            + " ("+ reconnectAttempts + ". attempt).");
                    disconnect();
                    connectAndSyncData();
                    return;
                }
                mConnectionState = newState;
                intent.setAction(ACTION_GATT_DISCONNECTED);
                intent.putExtra(EXTRA_DATA_DEVICE_ADDRESS, gatt.getDevice().getAddress());
                mContext.sendBroadcast(intent);

            } else {
                Log.e(TAG, "other error, new state of "
                        + getRemoteDeviceName() + ": " + newState);
            }

            broadcastStateChanged();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) throws SecurityException {
            super.onServicesDiscovered(gatt, status);

            if(status == BluetoothGatt.GATT_SUCCESS) {

                for (BluetoothGattService service: gatt.getServices()) {

                    Log.i(TAG, "found gatt services " + service.getUuid());

                    if (service.getUuid().compareTo(UUIDCollection.CTS_SERVICE) == 0) {
                        mCTSCharacteristics = service.getCharacteristic(UUIDCollection.CTS_CHARACTERISTICS);

                        if (mCTSCharacteristics == null) {
                            Log.e(TAG, "no CTS characteristics found");
                            continue;
                        }
                    }

                    if (service.getUuid().compareTo(UUIDCollection.ASS_SERVICE) == 0) {
                        mSyncCharacteristics = service.getCharacteristic(UUIDCollection.ASS_CHARACTERISTICS);

                        if (mSyncCharacteristics == null) {
                            Log.e(TAG, "no ASS synchronization characteristic found");
                            continue;
                        }

                        for (BluetoothGattDescriptor descriptor : mSyncCharacteristics.getDescriptors())
                        {
                            Log.d(TAG, descriptor.getUuid().toString());
                        }
                    }

                    if (service.getUuid().compareTo(UUIDCollection.ANS_SERVICE) == 0) {
                        mNameCharacteristics = service.getCharacteristic(UUIDCollection.ANS_CHARACTERISTIC);

                        if (mNameCharacteristics == null) {
                            Log.e(TAG, "could not find ANS characteristic");
                        }
                    }
                }
                mHandler.post(() -> {
                    if (mSyncCharacteristics == null) {
                        disconnect();
                        mSyncFailed = true;
                        return;
                    }
                    if (mNameCharacteristics != null) {
                        readRemoteDeviceName();
                    } else {
                        //connectToNotifications();
                        mBluetoothGatt.requestMtu(517);
                    }
                });

            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) throws SecurityException {
            super.onMtuChanged(gatt, mtu, status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "failed to increase MTU size");
            }
            // TODO: check BluetoothGatt.onConnectionUpdate()!!!
            //mBluetoothGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
            connectToNotifications();
        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorRead(gatt, descriptor, status);
            Log.i(TAG, "onDescriptorRead");
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorWrite(gatt, descriptor, status);
            Log.i(TAG, "onDescriptorWrite");

            if (status != BluetoothGatt.GATT_SUCCESS) {
                //todo
                Log.e(TAG, "error writing descriptor");
            }

            if (mSyncFinished) {
                synchronizeTime(mCTSCharacteristics);
            }
            //connectToNotifications();
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status)
                throws SecurityException {
            super.onCharacteristicRead(gatt, characteristic, status);
            Log.i(TAG, "onCharacteristicRead");

            if(status == BluetoothGatt.GATT_SUCCESS) {

                final byte[] data = characteristic.getValue();

                if (data == null)
                    return;
                if (data.length < 1)
                    return;

                if (characteristic.getUuid().compareTo(mNameCharacteristics.getUuid()) == 0) {
                    Log.d(TAG, "received remote device name: " + mDeviceName);
                    mDeviceName = new String(data);
                    //connectToNotifications();
                    mBluetoothGatt.requestMtu(517);
                }

                else if(characteristic.getUuid().compareTo(mCTSCharacteristics.getUuid()) == 0) {
                    long mTsCtsReceived = System.currentTimeMillis();
                    int iRemoteTS = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt();
                    long remote_ts = (iRemoteTS & 0xffffffffL);
                    long host_ts = (mTsCtsCalled + mTsCtsReceived) / 2;

                    Log.d(TAG, "host timestamp:   " + host_ts);
                    Log.d(TAG, "remote timestamp (int):  " + iRemoteTS);
                    Log.d(TAG, "remote timestamp (long): " + remote_ts);

                    if (remote_ts == 0
                            || mSyncedDataSets >= 96
                            || mAbortDataSynchronization) {

                        long ts = System.currentTimeMillis();
                        Log.i(TAG,"synchronization finished, no more data available");
                        long mTsSyncStart = System.currentTimeMillis();
                        Log.i(TAG,"received " + mSyncedDataSets + " data sets in "
                                + (ts - mTsSyncStart)
                                + "ms ("
                                + (ts - mTsConnectStart)
                                + "ms)");

                        Intent intent = new Intent();
                        intent.setAction(ACTION_GATT_CONNECTED);
                        intent.putExtra(EXTRA_DATA_DEVICE_ADDRESS, gatt.getDevice().getAddress());
                        intent.putExtra(EXTRA_DATA_DEVICE_ATTRIBUTES_JSON_STRING,
                                serializeSyncAttributesToJson(mSyncedDataSets,
                                        ts - mTsSyncStart,
                                        ts - mTsConnectStart).toString());

                        disconnect();
                        mIsSynchronizing = false;

                        mContext.sendBroadcast(intent);

                        broadcastStateChanged();

                        return;
                    }

                    mTimestampSemaphore.acquireUninterruptibly();
                    try {
                        long dataset_ts = mSensorDataJson.getLong("timestamp");
                        if(dataset_ts > remote_ts) {
                            Log.e(TAG, "dataset_ts > remote_ts");
                            remote_ts += 0xffffffff;
                        }
                        remote_ts = remote_ts - dataset_ts;
                    } catch (JSONException e) {
                        Log.e(TAG, "no dataset timestamp in json object");
                    }

                    try {
                        mSensorDataJson.put("timestamp", (host_ts - remote_ts));
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                    mTimestampSemaphore.release();

                    handleDataSynchronization(CTS_REF_DATA);

                } else if(characteristic.getUuid().compareTo(mBASCharacteristics.getUuid()) == 0) {
                    int battery = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
                    final Intent intent = new Intent(ACTION_GATT_READ_BATTERY);
                    intent.putExtra(EXTRA_DATA_BATTERY, battery);
                    mContext.sendBroadcast(intent);

                } else if(characteristic.getUuid().compareTo(mTempCharacteristics.getUuid()) == 0) {
                    int temp = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort();
                    mTempValue = ((float) temp) / 16.0f;
                    if(mTempValue < 50.0) {
                        final Intent intent = new Intent(ACTION_GATT_READ_TEMPERATURE);
                        intent.putExtra(EXTRA_DATA_TEMPERATURE, ((float) temp) / 16.0f);
                        mContext.sendBroadcast(intent);
                    }

                } else if(characteristic.getUuid().compareTo(mHumiCharacteristics.getUuid()) == 0) {
                    mMoistureValue = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
                    final Intent intent = new Intent(ACTION_GATT_READ_MOISTURE);
                    intent.putExtra(EXTRA_DATA_MOISTURE, mMoistureValue);
                    mContext.sendBroadcast(intent);
                }
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            Log.i(TAG, "onCharacteristicWrite: " + characteristic.getUuid().toString());

            if (status != BluetoothGatt.GATT_SUCCESS) {
                //todo
                Log.e(TAG, "error writing characteristics");
            }

            if (characteristic.getUuid().compareTo(UUIDCollection.CTS_CHARACTERISTICS) == 0) {
                disconnect();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);

            Log.i(TAG, "onCharacteristicChanged: " + characteristic.getUuid().toString());
            final byte[] data = characteristic.getValue();

            if(data == null)
                return;
            if (data.length < 1)
                return;

            if(characteristic.getUuid().compareTo(UUIDCollection.CTS_CHARACTERISTICS) == 0) {
                long timestamp =
                        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xffffffffL;
                Log.d(TAG, "dataset timestamp: " + timestamp);

                mTimestampSemaphore.acquireUninterruptibly();
                try {
                    timestamp += mSensorDataJson.getLong("timestamp");
                } catch (JSONException e) {
                    Log.e(TAG, "no remote timestamp in json object");
                }

                try {
                    mSensorDataJson.put("timestamp", timestamp);
                    // what if ref timestamp < timestamp?!
                } catch (JSONException e) {
                    Log.e(TAG, "error putting timestamp to json object");
                }
                mTimestampSemaphore.release();

                handleDataSynchronization(CTS_DATA);

            } else if(characteristic.getUuid().compareTo(UUIDCollection.BAS_CHARACTERISTICS) == 0) {
                int battery = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
                try {
                    mSensorDataJson.put("battery", battery);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                handleDataSynchronization(BAS_DATA);

            } else if(characteristic.getUuid().compareTo(UUIDCollection.ESS_TEMP_CHARACTERISTICS) == 0) {
                int temp = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort();
                float fTemp = ((float) temp) / 16.0f;
                try {
                    if (temp > -1000 && fTemp < 50.0) {
                        mSensorDataJson.put("temperature", fTemp);
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                handleDataSynchronization(TEMP_DATA);

            } else if(characteristic.getUuid().compareTo(UUIDCollection.ESS_HUMI_CHARACTERISTICS) == 0) {
                int humidity = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
                try {
                    mSensorDataJson.put("moisture", humidity);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                handleDataSynchronization(HUMI_DATA);

            } else if (characteristic.getUuid().compareTo(UUIDCollection.ESS_RAINFALL_CHARACTERISTICS) == 0) {
                int rainfall = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
                try {
                    mSensorDataJson.put("rainfall", rainfall);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                handleDataSynchronization(RAIN_DATA);
            } else if (characteristic.getUuid().compareTo(UUIDCollection.ASS_CHARACTERISTICS) == 0) {
                processSynchronizationData(mSyncCharacteristics.getDescriptors(), data);
            }
        }
    }

    private void processSynchronizationData(List<BluetoothGattDescriptor> descs, byte[] data) {

        int offset = 1;
        String key;
        int occurrence;
        int numDatasets = data[0] & 0xff;

        if (data.length == 1 && data[0] == 0) {
            Log.d(TAG, "synchronization finished");
            mIsSynchronizing = false;
            mSyncFinished = true;
            synchronizeTime(mCTSCharacteristics);
            mTimeLastSync = Instant.now().getEpochSecond();

            if (mSensorDataJson != null) {
                publishMqttDeviceTelemetry(mSensorDataJson.toString());
                mSensorDataJson = null;
            }

            return;
        }

        JSONArray sensorValues = null;

        if (mSensorDataJson == null) {
            mSensorDataJson = new JSONObject();
            sensorValues = new JSONArray();
            try {
                mSensorDataJson.put(getRemoteDeviceName(), sensorValues);
            } catch (JSONException e) {
                Log.e(TAG, "failed to put array of values" + e.getMessage());
            }
        } else {
            try {
                sensorValues = (JSONArray) mSensorDataJson.get(getRemoteDeviceName());
            } catch (JSONException e) {
                Log.e(TAG, "failed to get array of values: " + e.getMessage());
            }
        }

        for (int i = 0; i < numDatasets; i++) {

            JSONObject sensorValueTSData = new JSONObject();
            JSONObject sensorValueData = new JSONObject();

            for (BluetoothGattDescriptor desc: descs) {

                if (desc.getUuid().compareTo(UUIDCollection.GATT_DDD) == 0) {
                    continue;
                }

                if (desc.getUuid().compareTo(UUIDCollection.ASS_TID) == 0) {
                    key = "ts";
                    long timestamp = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xffffffffL;
                    offset += 4;
                    Log.d(TAG, desc.getUuid() + "(" + key + ")" + " = " + timestamp);
                    try {
                        sensorValueTSData.put(key, timestamp * 1000);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                    continue;
                }

                if (desc.getUuid().compareTo(UUIDCollection.ASS_BVD) == 0) {
                    key = "battery";
                    int batteryValue = ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
                    offset += 2;
                    Log.d(TAG, desc.getUuid() + "(" + key + ")" + " = " + batteryValue);

                    try {
                        double fBatterValue = Math.round((double) batteryValue);
                        fBatterValue *= 0.01;
                        sensorValueData.put(key, fBatterValue);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }

                    continue;
                }

                if (desc.getUuid().compareTo(UUIDCollection.ASS_STD) == 0) {
                    key = "temperature";
                    occurrence = 1;
                    while (sensorValueData.has(key)) {
                        key = "temperature_" + occurrence;
                        occurrence++;
                    }

                    int temp = ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xfffff;
                    offset += 2;
                    Log.d(TAG, desc.getUuid() + "(" + key + ")" + " = " + temp);

                    try {
                        double fTempValue = Math.round((double) temp);
                        fTempValue *= 0.01;
                        sensorValueData.put(key, fTempValue);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }

                    continue;
                }

                if (desc.getUuid().compareTo(UUIDCollection.ASS_SRHD) == 0) {
                    key = "moisture";
                    occurrence = 1;
                    while (sensorValueData.has(key)) {
                        key = "moisture_" + occurrence;
                        occurrence++;
                    }

                    int humi = ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xfffff;
                    offset += 2;
                    Log.d(TAG, desc.getUuid() + "(" + key + ")" + " = " + humi);

                    try {
                        double fHumiValue = Math.round((double) humi);
                        fHumiValue *= 0.01;
                        sensorValueData.put(key, fHumiValue);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }

                    continue;
                }

                if (desc.getUuid().compareTo(UUIDCollection.ASS_RFD) == 0) {
                    key = "precipitation";
                    int rain = ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xfffff;
                    offset += 2;
                    Log.d(TAG, desc.getUuid() + "(" + key + ")" + " = " + rain);

                    try {
                        double dValue = Math.round((double) rain);
                        dValue *= 0.01;
                        sensorValueData.put(key, dValue);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }

                    continue;
                }

                if (desc.getUuid().compareTo(UUIDCollection.ASS_ATD) == 0) {
                    key = "temperature";
                    occurrence = 1;
                    while (sensorValueData.has(key)) {
                        key = "temperature_" + occurrence;
                        occurrence++;
                    }

                    int value = ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xfffff;
                    offset += 2;
                    Log.d(TAG, desc.getUuid() + "(" + key + ")" + " = " + value);

                    try {
                        double dValue = Math.round((double) value);
                        dValue *= 0.01;
                        sensorValueData.put(key, dValue);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }

                    continue;
                }

                if (desc.getUuid().compareTo(UUIDCollection.ASS_ARHD) == 0) {
                    key = "humidity";
                    occurrence = 1;
                    while (sensorValueData.has(key)) {
                        key = "humidity_" + occurrence;
                        occurrence++;
                    }

                    int humi = ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xfffff;
                    offset += 2;
                    Log.d(TAG, desc.getUuid() + "(" + key + ")" + " = " + humi);

                    try {
                        double fHumiValue = Math.round((double) humi);
                        fHumiValue *= 0.01;
                        sensorValueData.put(key, fHumiValue);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }

                    continue;
                }

                if (desc.getUuid().compareTo(UUIDCollection.ASS_APB) == 0) {
                    key = "pressure";
                    occurrence = 1;
                    while (sensorValueData.has(key)) {
                        key = "pressure_" + occurrence;
                        occurrence++;
                    }

                    long value = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xffffffffL;
                    offset += 4;
                    Log.d(TAG, desc.getUuid() + "(" + key + ")" + " = " + value);

                    try {
                        double dValue = Math.round((double) value);
                        dValue *= 0.001;
                        sensorValueData.put(key, dValue);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }

                    continue;
                }

                if (desc.getUuid().compareTo(UUIDCollection.ASS_AOD) == 0) {
                    key = "actuator_out";
                    occurrence = 1;
                    while (sensorValueData.has(key)) {
                        key = "actuator_out_" + occurrence;
                        occurrence++;
                    }

                    int value = data[offset] & 0xff;
                    offset += 1;
                    Log.d(TAG, desc.getUuid() + "(" + key + ")" + " = " + value);

                    try {
                        sensorValueData.put(key, value);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            }

            try {
                sensorValueTSData.put("values", sensorValueData);
                assert sensorValues != null;
                sensorValues.put(sensorValueTSData);
            } catch (JSONException e) {
                Log.e(TAG, "failed to put \"values\" to senorValueData: " + e.getMessage());
            }
        }

        try {
            assert sensorValues != null;
            Log.d(TAG, "JSON data (" + sensorValues.length() + "): "
                    + mSensorDataJson.toString(2));
        } catch (JSONException e) {
            Log.e(TAG, "failed to convert JSON object to string: " + e.getMessage());
        }

        if (sensorValues.length() > 4) {
            if (mSensorDataJson != null) {
                publishMqttDeviceTelemetry(mSensorDataJson.toString());
                mSensorDataJson = null;
            }
        }
    }

    private class BluetoothSyncTimeGattCallback extends BluetoothSyncDataGattCallback {
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {

            if (status == BluetoothGatt.GATT_SUCCESS) {

                BluetoothGattCharacteristic writeCTSCharacteristics = null;

                for (BluetoothGattService service : gatt.getServices()) {
                    writeCTSCharacteristics = service.getCharacteristic(UUIDCollection.CTS_CHARACTERISTICS);
                    if (writeCTSCharacteristics == null) {
                        Log.e(TAG, "no CTS characteristics available");
                        continue;
                    }

                    synchronizeTime(writeCTSCharacteristics);

                    break;
                }

                if (writeCTSCharacteristics == null)
                    disconnect();
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            Log.i(TAG, "onCharacteristicWrite");

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "error writing characteristics");
            }

            disconnect();
        }
    }

    private BluetoothGattCallback getGattCallback() {
        return new BluetoothSyncDataGattCallback();
    }

    private void readRemoteDeviceName() throws SecurityException {
        mBluetoothGatt.readCharacteristic(mNameCharacteristics);
    }

    private void connectToNotifications() throws SecurityException {
        if (!mSyncEnabled) {
            return;
        }

        if(!mIsSynchronizing) {
            mIsSynchronizing = true;
            broadcastStateChanged();
        }

        //mBluetoothGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);

        BluetoothGattDescriptor desc = mSyncCharacteristics.getDescriptor(UUIDCollection.GATT_DDD);
        if(mBluetoothGatt.setCharacteristicNotification(mSyncCharacteristics, true)) {
            Log.i(TAG, "notification enable sent for "
                    + mSyncCharacteristics.getUuid().toString());
        }

        desc.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
        if (mBluetoothGatt.writeDescriptor(desc)) {
            Log.i(TAG, "notification descriptor written");
        }
    }

    public void readBatteryValue() throws SecurityException {
        if(!mBluetoothGatt.readCharacteristic(mBASCharacteristics)) {
            Log.i(TAG, "reading remote battery value failed");
        }
    }

    public void readTemperatureValue() throws SecurityException {
        if(!mBluetoothGatt.readCharacteristic(mTempCharacteristics)) {
            Log.i(TAG, "reading remote temperature value failed");
        }
    }

    public void readMoistureValue() throws SecurityException {
        if(!mBluetoothGatt.readCharacteristic(mHumiCharacteristics)) {
            Log.i(TAG, "reading remote humidity value failed");
        }
    }

    public void setSyncEnabled(Boolean syncEnabled) {
        this.mSyncEnabled = syncEnabled;
    }

    private void startDataSynchronization() throws SecurityException {
        mSensorDataJson = new JSONObject();

        mTsCtsCalled = System.currentTimeMillis();
        if (mReadCharacteristics != null && mBluetoothGatt.readCharacteristic(mCTSCharacteristics)) {
            Log.i(TAG, "reading remote time");
        } else {
            Log.e(TAG, "reading remote time failed");
        }
    }

    private void handleDataSynchronization(int _flag) {
        if (_flag == 0) {
            mSyncedDataFlags = 0;
            startDataSynchronization();
            return;
        }
        mSyncedDataFlags |= _flag;

        if (mSyncedDataFlags != mAllData) {
            return;
        }

        String timestampString = "";
        try {
            try {
                long timestamp = mSensorDataJson.getLong("timestamp");
                timestampString = DateFormat.getDateTimeInstance().format(new Date(timestamp));
                Log.i(TAG, "timestamp of dataset: " + timestampString);
            } catch (JSONException je) {
                Log.e(TAG, "no timestamp in dataset");
            }

            JSONObject deviceWithValues = putValuesToDeviceJson(mSensorDataJson);
            Log.i(TAG, "data record received: " + deviceWithValues.toString(2));

            mSyncedDataSets++;

            Intent intent = new Intent();
            intent.setAction(ACTION_GATT_DATA_AVAILABLE);
            intent.putExtra(EXTRA_DATA_DEVICE_ADDRESS, getRemoteDeviceName());
            intent.putExtra(EXTRA_DATA_NUM_DATASETS, mSyncedDataSets);
            intent.putExtra(EXTRA_DATA_DATASET_TIMESTAMP, timestampString);
            intent.putExtra(EXTRA_DATA_JSON_STRING, deviceWithValues.toString());
            mContext.sendBroadcast(intent);

        } catch (JSONException e) {
            Log.e(TAG, "data record received, but got JSONException");
            e.printStackTrace();
        }

        handleDataSynchronization(0);
    }

    private JSONObject serializeSyncAttributesToJson(int num_data_sets,
                                                     long sync_time_net, long sync_time_gross) {
        JSONObject device = new JSONObject();
        try {
            JSONObject attributes = new JSONObject();
            attributes.put("num_data_sets", num_data_sets);
            attributes.put("sync_time_net", sync_time_net);
            attributes.put("sync_time_gross", sync_time_gross);

            device.put(getRemoteDeviceName(), attributes);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return device;
    }

    public JSONObject serializeGenericAttributesToJson(String attribute, float value) {
        JSONObject device = new JSONObject();
        try {
            JSONObject attributes = new JSONObject();
            attributes.put(attribute, value);

            device.put(getRemoteDeviceName(), attributes);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        return device;
    }

    public JSONObject serializeGenericAttributesToJson(String attribute, double value) {
        JSONObject device = new JSONObject();
        try {
            JSONObject attributes = new JSONObject();
            attributes.put(attribute, value);

            device.put(getRemoteDeviceName(), attributes);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        return device;
    }

    public JSONObject serializeGenericTelemetryValuesToJson(String name, double value) {
        JSONObject deviceValues = new JSONObject();
        JSONObject deviceData = new JSONObject();
        try {
            deviceValues.put("ts", System.currentTimeMillis());

            JSONObject data = new JSONObject();
            data.put(name, value);

            deviceValues.put("values", data);
            deviceData.put(getRemoteDeviceName(), new JSONArray().put(
                    deviceValues));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return deviceData;
    }

    private JSONObject putValuesToDeviceJson(JSONObject valuesObject)
    {
        JSONObject deviceValues = new JSONObject();
        JSONObject deviceData = new JSONObject();
        try {
            deviceValues.put("ts", valuesObject.getLong("timestamp"));
            valuesObject.remove("timestamp");
            deviceValues.put("values", valuesObject);

            deviceData.put(getRemoteDeviceName(), new JSONArray().put(
                    deviceValues));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return deviceData;
    }

    private void broadcastStateChanged() {
        final Intent intent = new Intent(ACTION_STATE_CHANGED);
        mContext.sendBroadcast(intent);
    }

    public void synchronizeTime(BluetoothGattCharacteristic writeCTSCharacteristics)
            throws SecurityException {
        ByteBuffer timebuffer = ByteBuffer.allocate(10);
        OffsetDateTime now = OffsetDateTime.now( ZoneOffset.UTC );

        timebuffer.order(ByteOrder.LITTLE_ENDIAN);
        timebuffer.putShort((short)now.getYear());
        timebuffer.put((byte)now.getMonth().getValue());
        timebuffer.put((byte)now.getDayOfMonth());
        timebuffer.put((byte)now.getHour());
        timebuffer.put((byte)now.getMinute());
        timebuffer.put((byte)now.getSecond());
        timebuffer.put((byte)now.getDayOfWeek().getValue());
        timebuffer.putShort((short) 0);

        writeCTSCharacteristics.setValue(timebuffer.array());
        if (mBluetoothGatt.writeCharacteristic(writeCTSCharacteristics)) {
            Log.d(TAG, "successfully initiated time synchronization");
        } else {
            Log.e(TAG, "error initiating time synchronization");
        }
    }

    public void publishMqttDeviceAttributes(String jsonAttributes) {
        Intent intent = new Intent(ACTION_PUBLISH_ATTRIBUTE);

        intent.putExtra(EXTRA_DATA_DEVICE_ATTRIBUTES_JSON_STRING,
                jsonAttributes);

        mContext.sendBroadcast(intent);
    }

    public void publishMqttDeviceTelemetry(String jsonTelemetry) {
        NodeSyncClient.getInstance(mContext).publishTelemetryMessage(jsonTelemetry);
    }

    public String getRemoteDeviceName() {
        if (mDeviceName == null) {
            return mScanResult.getDevice().getAddress();
        }
        return mDeviceName;
    }

    public boolean isConnected() {
        return mConnectionState == BluetoothProfile.STATE_CONNECTED;
    }

    public boolean isSynchronizing() {
        return mIsSynchronizing;
    }

    public int getRssi() {
        return mScanResult.getRssi();
    }

    public float getTemperature() {
        return mTempValue;
    }

    public float getMoisture() {
        return mMoistureValue;
    }


    public long getTimeLastSync() {
        return mTimeLastSync;
    }

    public int getStateStringId() {
        if(isSynchronizing())
            return R.string.synchronizing;

        if (mSyncFailed) {
            return R.string.sync_failed;
        }

        switch(mConnectionState) {
            case BluetoothProfile.STATE_CONNECTING:
                return R.string.connecting;
            case BluetoothProfile.STATE_CONNECTED:
                return R.string.connected;
            case BluetoothProfile.STATE_DISCONNECTING:
                return R.string.disconnecting;
            case BluetoothProfile.STATE_DISCONNECTED:
                return  R.string.disconnected;
        }

        return R.string.undefinied;
    }

    public String getStateString() {
        return mContext.getResources().getString(getStateStringId());
    }
}