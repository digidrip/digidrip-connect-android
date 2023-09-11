package eu.digidrip.connect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

public class NodeActivity extends AppCompatActivity {
    public static final String TAG = NodeActivity.class.getSimpleName();

    private Node mNode;

    ToggleButton tbtnManual;
    ToggleButton tbtnConstant;
    ToggleButton tbtnHyteresis;

    private int outputPositionRelativeSetter = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_node);

        Bundle extras = getIntent().getExtras();
        mNode = NodeScanner.getNode(extras.getString("address"));

        setTitle(mNode.getRemoteDeviceName());

        final Button btnConnect = (Button) findViewById(R.id.btn_node_connect);
        btnConnect.setOnClickListener(view -> {
            if (mNode.isConnected()) {
                mNode.setSyncEnabled(true);
                mNode.disconnect();
                //disableSensorDataClicks();
            } else {
                mNode.setSyncEnabled(false);
                mNode.connectAndSyncData();
            }
            updateUI();
        });

        Button btn = (Button) findViewById(R.id.sync_time_node);
        btn.setOnClickListener(view -> mNode.connectAndSyncTime());

        btn = (Button) findViewById(R.id.sync_data_node);
        btn.setOnClickListener(view -> mNode.connectAndSyncData());

        TextView tv = (TextView) findViewById(R.id.tv_label_connection_state);
        tv.setText(mNode.getStateString());

        registerReceiver(broadcastReceiver, makeUpdateIntentFilter());

        btn = findViewById(R.id.btn_node_refresh);
        btn.setOnClickListener(view -> {
            mNode.readAllData();
        });

        tbtnManual    = findViewById(R.id.irrigation_set_manual);
        tbtnConstant  = findViewById(R.id.irrigation_set_constant);
        tbtnHyteresis = findViewById(R.id.irrigation_set_hysteresis);

        tbtnManual.setOnClickListener(view -> {
            mNode.setOutputMode(0);
            updateUI();
        });

        tbtnConstant.setOnClickListener(view -> {
            mNode.setOutputMode(1);
            updateUI();
        });

        tbtnHyteresis.setOnClickListener(view -> {
            mNode.setOutputMode(2);
            updateUI();
        });

        SeekBar sbIrrigationValue = findViewById(R.id.irrigation_slider);
        sbIrrigationValue.setEnabled(false);
        sbIrrigationValue.setMin(0);
        sbIrrigationValue.setMax(1000);
        sbIrrigationValue.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                TextView tvIrrigationValue = findViewById(R.id.irrigation_value_label);
                tvIrrigationValue.setText("" + (float)i / 10.0f + " %");

                switch (mNode.getOutputMode()) {
                    case 1:
                    case 2:
                        mNode.setOutputValueHigh(i * 10);
                    case 0:
                    default:
                        outputPositionRelativeSetter = i * 10;
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                ProgressBar pbActuatorOutput = findViewById(R.id.pb_actuator_output);
                pbActuatorOutput.setProgress(mNode.getActuatorValueRelative(), false);

                switch (mNode.getOutputMode()) {
                    case 0:
                        mNode.writeActuator(mNode.getOutputMode(), outputPositionRelativeSetter / 100);
                        break;
                    case 1:
                        mNode.writeActuator(mNode.getOutputMode(), mNode.getOutputValueHigh());
                        break;
                    case 2:
                    default:
                        mNode.writeActuator(mNode.getOutputMode(), mNode.getOutputValueHigh(), 0);
                }
            }
        });

        updateUI();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.activity_node_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_settings:
                Intent i = new Intent(NodeActivity.this, NodeSettingsActivity.class);
                i.putExtra("address", getIntent().getExtras().getString("address"));
                startActivity(i);
                break;
            case R.id.action_close:
                finish();
                break;
        }
        return super.onOptionsItemSelected(item);
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

            if(Node.ACTION_STATE_CHANGED.equals(action)) {
                TextView tv = findViewById(R.id.tv_label_connection_state);
                tv.setText(mNode.getStateString());
                updateUI();
            }

            if (Node.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                updateUI();
            }

            if (Node.ACTION_DATA_AVAILABLE.equals(action)) {
                updateUI();
            }
        }
    };

    private void updateUI() {

        setTitle(mNode.getRemoteDeviceName());

        tbtnManual.setEnabled(mNode.hasActuator());
        tbtnConstant.setEnabled(mNode.hasActuator());
        tbtnHyteresis.setEnabled(mNode.hasActuator());

        Button btn;

        btn = findViewById(R.id.btn_node_connect);
        btn.setEnabled(true);
        btn.setText(mNode.isConnected() ? R.string.Disconnect : R.string.Connect);

        btn = findViewById(R.id.btn_node_refresh);
        btn.setEnabled(mNode.isConnected());

        btn = findViewById(R.id.sync_time_node);
        btn.setEnabled(mNode.isConnected());

        TextView tv = findViewById(R.id.tv_label_temperature);
        tv.setText(String.format("%5.1f", mNode.getTemperature()));

        tv = findViewById(R.id.tv_label_humidity);
        tv.setText(String.format("%5.1f", mNode.getMoisture()));

        tv = findViewById(R.id.tv_label_battery);
        tv.setText(String.format("%5.1f", (double)mNode.getBatteryValue()));

        ProgressBar pbActuatorOutput = findViewById(R.id.pb_actuator_output);
        pbActuatorOutput.setProgress(mNode.getActuatorValueRelative(), false);

        SeekBar seekBar = findViewById(R.id.irrigation_slider);
        seekBar.setEnabled(mNode.hasActuator());

        switch (mNode.getOutputMode()) {
            case 1:
                tbtnManual.setChecked(false);
                tbtnConstant.setChecked(true);
                tbtnHyteresis.setChecked(false);
                seekBar.setProgress(mNode.getOutputValueHigh() / 10, true);
                break;
            case 2:
                tbtnManual.setChecked(false);
                tbtnConstant.setChecked(false);
                tbtnHyteresis.setChecked(true);
                seekBar.setProgress(mNode.getOutputValueHigh() / 10, true);
                break;
            case 0:
            default:
                tbtnManual.setChecked(true);
                tbtnConstant.setChecked(false);
                tbtnHyteresis.setChecked(false);
                seekBar.setProgress(mNode.getOutputPositionRelative() * 10, true);
                break;
        }
    }

    private static IntentFilter makeUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();

        intentFilter.addAction(BluetoothLeService.ACTION_LOCATION_UPDATE);

        intentFilter.addAction(Node.ACTION_STATE_CHANGED);
        intentFilter.addAction(Node.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(Node.ACTION_GATT_READ_BATTERY);
        intentFilter.addAction(Node.ACTION_GATT_READ_TEMPERATURE);
        intentFilter.addAction(Node.ACTION_GATT_READ_MOISTURE);
        intentFilter.addAction(Node.ACTION_GATT_DATA_AVAILABLE);
        intentFilter.addAction(Node.ACTION_GATT_READ_ACTUATOR_STATE);
        intentFilter.addAction(Node.ACTION_DATA_AVAILABLE);

        return intentFilter;
    }
}
