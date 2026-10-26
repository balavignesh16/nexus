package com.nexus.infrastructure.mqtt;
import org.junit.jupiter.api.Test;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.context.event.EventListener;
import org.springframework.context.event.ContextRefreshedEvent;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class MqttStartupOrderingTest {
    @Test void clientCannotReceivePersistentMessagesBeforeTheListenerExists() throws Exception {
        var configuration=new MQTTConfiguration();
        ReflectionTestUtils.setField(configuration,"brokerUrl","tcp://127.0.0.1:1");
        ReflectionTestUtils.setField(configuration,"clientId","test-not-connected");
        // An attempted network connect to this closed port would fail the test rather than be mocked away.
        var client=configuration.mqttClient(configuration.mqttConnectOptions());
        assertFalse(client.isConnected());
        client.close();
        var start=MqttReconnectController.class.getDeclaredMethod("init");
        assertArrayEquals(new Class<?>[]{ContextRefreshedEvent.class},start.getAnnotation(EventListener.class).value());
    }
}
