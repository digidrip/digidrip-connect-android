package eu.digidrip.connect;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.time.Instant;
import java.util.List;

public class SensorNodeAdapater extends RecyclerView.Adapter<SensorNodeAdapater.MyViewHolder> {

    private List<SensorNode> sensorNodeList;

    public static class MyViewHolder extends RecyclerView.ViewHolder {
        public TextView title, address, status;
        public ImageView statusIcon;

        public MyViewHolder(View view) {
            super(view);
            title = (TextView) view.findViewById(R.id.title);
            address = (TextView) view.findViewById(R.id.address);
            status = (TextView) view.findViewById(R.id.status);
            statusIcon = (ImageView) view.findViewById(R.id.statusIcon);
        }
    }

    public SensorNodeAdapater(List<SensorNode> sensorNodeList) {
        this.sensorNodeList = sensorNodeList;
    }

    @NonNull
    @Override
    public MyViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.sensor_node_list_row, parent, false);

        return new MyViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(MyViewHolder holder, int position) {
        SensorNode sensorNode = sensorNodeList.get(position);

        holder.title.setText(sensorNode.getRemoteDeviceName());
        holder.address.setText("RSSI: " + sensorNode.getRssi());
        holder.status.setText(sensorNode.getStateString());

        if (sensorNode.getStateStringId() == R.string.connecting
            || sensorNode.getStateStringId() == R.string.connected
            || sensorNode.getStateStringId() == R.string.synchronizing) {
            holder.statusIcon.setImageResource(R.drawable.ic_sync_blue_24dp);
            return;
        }

        if (sensorNode.getStateStringId() == R.string.sync_failed) {
            holder.statusIcon.setImageResource(R.drawable.ic_priority_high_red_24dp);
            return;
        }

        if (sensorNode.getTimeLastSync() + 3600 < Instant.now().getEpochSecond()) {
            holder.statusIcon.setImageResource(R.drawable.ic_priority_high_red_24dp);
            return;
        }

        holder.statusIcon.setImageResource(R.drawable.ic_check_green_24dp);
    }

    @Override
    public int getItemCount() {
        return sensorNodeList.size();
    }

    public void setSensorNodeList(List<SensorNode> sensorNodeList) {
        this.sensorNodeList = sensorNodeList;
    }
}
