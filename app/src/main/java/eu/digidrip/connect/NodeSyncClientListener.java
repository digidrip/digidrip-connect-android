package eu.digidrip.connect;

public interface NodeSyncClientListener {

    public void connectionStatusChanged();

    public void pendingMessagesChanged();
}
