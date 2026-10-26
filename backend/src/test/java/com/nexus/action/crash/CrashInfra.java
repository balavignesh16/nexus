package com.nexus.action.crash;

import crashnode.TcpProxy;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The disposable infrastructure shared by the crash-recovery campaign (one PostgreSQL + TimescaleDB container and one Mosquitto container
 * for the whole test JVM, started on first use and removed by Testcontainers' reaper when the JVM ends): a direct JDBC connection for the
 * tests' own assertions, a TCP proxy in front of each container for the child backend processes (so a dependency can be taken away
 * without touching the container), and an MQTT observer that counts every command published to the broker, per command id, from the
 * broker's point of view. Nothing here touches the development database or the development broker.
 */
final class CrashInfra {
    private static CrashInfra instance;

    final PostgreSQLContainer<?> postgres;
    final GenericContainer<?> mosquitto;
    final TcpProxy dbProxy;
    final TcpProxy brokerProxy;
    final JdbcTemplate jdbc;
    final MqttClient observer;
    /** command id to the number of times the broker received a command message carrying that id. */
    final Map<String, AtomicInteger> published = new ConcurrentHashMap<>();

    static synchronized CrashInfra get() {
        if (instance == null) {
            try {
                instance = new CrashInfra();
            } catch (Exception e) {
                throw new IllegalStateException("crash infrastructure could not start", e);
            }
        }
        return instance;
    }

    private CrashInfra() throws Exception {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));
        mosquitto = new GenericContainer<>("eclipse-mosquitto:2.0").withExposedPorts(1883)
                .withCopyFileToContainer(MountableFile.forClasspathResource("mosquitto-test/mosquitto.conf"), "/mosquitto/config/mosquitto.conf")
                .waitingFor(Wait.forListeningPort());
        postgres.start();
        mosquitto.start();
        dbProxy = new TcpProxy(postgres.getHost(), postgres.getMappedPort(5432));
        brokerProxy = new TcpProxy(mosquitto.getHost(), mosquitto.getMappedPort(1883));
        DriverManagerDataSource direct = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(direct);
        // The schema is created once here (the same migrations every backend process would run), so the tests can create devices before any
        // process starts and the processes' own Flyway run is a no-op.
        org.flywaydb.core.Flyway.configure().dataSource(direct).locations("classpath:db/migration").load().migrate();

        observer = new MqttClient("tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883), "crash-observer-" + UUID.randomUUID(), new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setAutomaticReconnect(true);
        observer.connect(options);
        observer.subscribe("nexus/devices/+/commands", 1, (topic, message) -> {
            String body = new String(message.getPayload(), StandardCharsets.UTF_8);
            int at = body.indexOf("\"commandId\":\"");
            if (at < 0) return;
            String id = body.substring(at + 13, body.indexOf('"', at + 13));
            published.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
        });
    }

    int publishedCount(UUID commandId) {
        AtomicInteger count = published.get(commandId.toString());
        return count == null ? 0 : count.get();
    }

    /** What a device would send: an acknowledgement on the real ACK topic through the real broker. */
    void deviceAck(UUID deviceId, UUID commandId, String status, long epochSeconds) throws Exception {
        String json = "{\"commandId\":\"" + commandId + "\",\"status\":\"" + status + "\",\"timestamp\":" + epochSeconds + "}";
        MqttMessage message = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
        message.setQos(1);
        observer.publish("nexus/devices/" + deviceId + "/commands/ack", message);
    }
}
