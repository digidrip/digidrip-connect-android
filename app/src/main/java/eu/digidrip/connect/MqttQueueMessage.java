package eu.digidrip.connect;

import java.io.Serializable;

public class MqttQueueMessage implements Serializable {
    String topic;
    byte[] payload;

    public MqttQueueMessage(String topic, byte[] payload) {
        this.topic = topic;
        this.payload = payload;
    }
}
