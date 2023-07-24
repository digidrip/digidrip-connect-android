package eu.digidrip.connect;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.ArrayList;
import java.util.List;

public class SynchronizationWorker extends Worker {

    private final static String TAG = SynchronizationWorker.class.getSimpleName();

    public SynchronizationWorker(@NonNull Context context,
                                 @NonNull WorkerParameters params) {
        super(context, params);
    }

    @Override
    public Result doWork() {
        Log.d(TAG, "starting periodic scan for BLE devices");

        NodeSyncClient.getInstance(getApplicationContext()).connect();

        return Result.success();
    }
}