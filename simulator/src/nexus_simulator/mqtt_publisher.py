import datetime
import json
import logging
import time

import paho.mqtt.client as mqtt

from nexus_simulator.publishers import TelemetryPublisher

logger = logging.getLogger(__name__)

STATUS_TOPIC_TEMPLATE = "nexus/devices/{device_id}/status"
COMMAND_TOPIC_TEMPLATE = "nexus/devices/{device_id}/commands"
ACK_TOPIC_TEMPLATE = "nexus/devices/{device_id}/commands/ack"


class MQTTPublisher(TelemetryPublisher):
    """One Paho MQTT client per simulated device - mirrors a physical
    ESP32's single independent MQTT connection. Owns telemetry publish,
    LWT/birth status, command subscription, and ACK publish for exactly
    one device."""

    def __init__(self, config, device_id: str, simulated_device):
        self.config = config
        self.device_id = device_id
        self.simulated_device = simulated_device

        client_id = (
            f"nexus-sim-{config.instance_id}-{device_id}"
            if getattr(config, "instance_id", None)
            else f"nexus-sim-{device_id}"
        )
        self.client = mqtt.Client(client_id=client_id, clean_session=True)

        status_topic = STATUS_TOPIC_TEMPLATE.format(device_id=device_id)
        self.client.will_set(status_topic, payload="offline", qos=1, retain=True)

        self.client.on_connect = self._on_connect
        self.client.on_disconnect = self._on_disconnect
        self.client.on_message = self._on_message
        self.client.reconnect_delay_set(min_delay=1, max_delay=30)

        try:
            self.client.connect(self.config.mqtt_broker, self.config.mqtt_port, 60)
            self.client.loop_start()
            logger.info(f"[{device_id}] MQTTPublisher connecting to "
                        f"{self.config.mqtt_broker}:{self.config.mqtt_port}")
        except Exception as e:
            logger.error(f"[{device_id}] Failed to connect to MQTT broker at "
                         f"{self.config.mqtt_broker}:{self.config.mqtt_port} - {e}")

    def _on_connect(self, client, userdata, flags, rc):
        if rc != 0:
            logger.error(f"[{self.device_id}] MQTT connect failed, rc={rc}")
            return
        status_topic = STATUS_TOPIC_TEMPLATE.format(device_id=self.device_id)
        client.publish(status_topic, "online", qos=1, retain=True)
        command_topic = COMMAND_TOPIC_TEMPLATE.format(device_id=self.device_id)
        client.subscribe(command_topic, qos=1)
        logger.info(f"[{self.device_id}] connected, subscribed to {command_topic}")

    def _on_disconnect(self, client, userdata, rc):
        if rc != 0:
            logger.warning(f"[{self.device_id}] unexpected MQTT disconnect (rc={rc}); "
                            f"Paho will attempt a bounded reconnect")

    def _on_message(self, client, userdata, msg):
        command_id, status, reason = self.simulated_device.command_handler.handle(msg.payload)
        if command_id is None:
            # Nothing to correlate an ACK against.
            return
        self._publish_ack(command_id, status, reason)

    def _publish_ack(self, command_id: str, status: str, reason) -> None:
        payload = {
            "commandId": command_id,
            "status": status,
            "timestamp": int(time.time()),
        }
        if reason:
            payload["reason"] = reason
        ack_topic = ACK_TOPIC_TEMPLATE.format(device_id=self.device_id)
        try:
            self.client.publish(ack_topic, json.dumps(payload), qos=1, retain=False)
        except Exception as e:
            logger.error(f"[{self.device_id}] Failed to publish ACK for {command_id}: {e}")

    def publish(
        self, device_id: str, sensor_type: str, value: any, unit: str
    ) -> tuple[bool, float]:
        start = time.time()

        if not device_id:
            logger.error("No deviceId in telemetry_data. Cannot publish over MQTT.")
            return False, 0.0

        topic = self.config.mqtt_topic_template.format(device_id=device_id)
        payload = {
            "deviceId": device_id,
            "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "sensorType": sensor_type,
            "value": value,
            "unit": unit
        }
        payload_str = json.dumps(payload)

        try:
            result = self.client.publish(topic, payload_str, qos=self.config.mqtt_qos)
            result.wait_for_publish()
            latency = (time.time() - start) * 1000
            return True, latency
        except Exception as e:
            latency = (time.time() - start) * 1000
            logger.error(f"Failed to publish MQTT message to {topic} - {e}")
            return False, latency

    def close(self) -> None:
        if not self.client:
            return
        status_topic = STATUS_TOPIC_TEMPLATE.format(device_id=self.device_id)
        try:
            result = self.client.publish(status_topic, "offline", qos=1, retain=True)
            result.wait_for_publish()
        except Exception as e:
            logger.warning(f"[{self.device_id}] Failed to publish graceful offline status: {e}")
        self.client.loop_stop()
        self.client.disconnect()
