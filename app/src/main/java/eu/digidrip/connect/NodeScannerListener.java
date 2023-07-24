package eu.digidrip.connect;

public interface NodeScannerListener {

    public void scanStopped();

    public void scanStarted();

    public void foundNode();
}
