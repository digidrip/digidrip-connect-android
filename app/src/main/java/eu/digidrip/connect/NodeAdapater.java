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

public class NodeAdapater extends RecyclerView.Adapter<NodeAdapater.MyViewHolder> {

    private List<Node> nodeList;

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

    public NodeAdapater(List<Node> nodeList) {
        this.nodeList = nodeList;
    }

    @NonNull
    @Override
    public MyViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.listrow_node, parent, false);

        return new MyViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(MyViewHolder holder, int position) {
        Node node = nodeList.get(position);

        holder.title.setText(node.getRemoteDeviceName());
        holder.address.setText("RSSI: " + node.getRssi());
        holder.status.setText(node.getStateString());

        if (node.getStateStringId() == R.string.connecting
            || node.getStateStringId() == R.string.connected
            || node.getStateStringId() == R.string.synchronizing) {
            holder.statusIcon.setImageResource(R.drawable.ic_sync_blue_24dp);
            return;
        }

        if (node.getStateStringId() == R.string.sync_failed) {
            holder.statusIcon.setImageResource(R.drawable.ic_priority_high_red_24dp);
            return;
        }

        if (node.getTimeLastSync() + 3600 < Instant.now().getEpochSecond()) {
            holder.statusIcon.setImageResource(R.drawable.ic_priority_high_red_24dp);
            return;
        }

        holder.statusIcon.setImageResource(R.drawable.ic_check_green_24dp);
    }

    @Override
    public int getItemCount() {
        return nodeList.size();
    }

    public void setNodeList(List<Node> nodeList) {
        this.nodeList = nodeList;
    }
}
