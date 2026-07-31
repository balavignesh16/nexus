import json
import logging
import paho.mqtt.client as mqtt
from nexus_simulator.publishers import TelemetryPublisher

logger = logging.getLogger(__name__)

class MQTTPublisher(TelemetryPublisher):
    """Publishes telemetry to the backend over MQTT."""

    def __init__(self, config):
        self.config = config
        self.client = mqtt.Client(client_id="nexus-simulator-pub", clean_session=True)
        
        # We assume localhost without auth for M4
        try:
            self.client.connect(self.config.mqtt_broker, self.config.mqtt_port, 60)
            self.client.loop_start()  # Run a background thread to handle network traffic
            logger.info(f"MQTTPublisher connected to {self.config.mqtt_broker}:{self.config.mqtt_port}")
        except Exception as e:
            logger.error(f"Failed to connect to MQTT broker at {self.config.mqtt_broker}:{self.config.mqtt_port} - {e}")

    def publish(self, device_id: str, sensor_type: str, value: any, unit: str) -> tuple[bool, float]:
        import time
        import datetime
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
            # We enforce QoS=1 based on M4 requirements
            result = self.client.publish(topic, payload_str, qos=self.config.mqtt_qos)
            
            # Wait for publish to complete (optional, but ensures delivery check)
            result.wait_for_publish()
            
            latency = (time.time() - start) * 1000
            return True, latency
        except Exception as e:
            latency = (time.time() - start) * 1000
            logger.error(f"Failed to publish MQTT message to {topic} - {e}")
            return False, latency

    def close(self):
        if self.client:
            self.client.loop_stop()
            self.client.disconnect()
