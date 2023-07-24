package eu.digidrip.connect;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class ScanWorker extends Worker {

    private final static String TAG = SynchronizationWorker.class.getSimpleName();

    public ScanWorker(@NonNull Context context,
                                 @NonNull WorkerParameters params) {
        super(context, params);
    }

    @Override
    public Result doWork() {
        Log.d(TAG, "starting periodic scan for BLE devices");

        NodeScanner.getInstance(getApplicationContext()).startScanning();

        return Result.success();
    }
}
