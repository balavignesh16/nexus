"""Disposable MQTT device for the live P1.7 browser acceptance scenario.

It fetches its topics/capabilities from the backend contract and accepts simple
stdin commands: status, telemetry:<number>, quit. Each stdout line is JSON.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
import threading
import time
import urllib.request

import paho.mqtt.client as mqtt


def emit(**fields: object) -> None:
    print(json.dumps(fields), flush=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--backend", default="http://localhost:8080")
    parser.add_argument("--broker-host", default="localhost")
    parser.add_argument("--broker-port", type=int, default=11883)
    parser.add_argument("--device-id", required=True)
    parser.add_argument("--capability-key", required=True)
    parser.add_argument("--client-id", required=True)
    args = parser.parse_args()
    with urllib.request.urlopen(
        f"{args.backend}/api/v1/devices/{args.device_id}/contract", timeout=10
    ) as response:
        contract = json.load(response)
    assert contract["deviceId"] == args.device_id
    read = next(cap for cap in contract["capabilities"] if cap["key"] == args.capability_key)
    led = next(cap for cap in contract["capabilities"] if cap["key"] == "led")
    assert read["direction"] == "READ" and read["valueType"] == "NUMBER"
    assert led["direction"] == "WRITE" and led["valueType"] == "BOOLEAN"
    assert "SET_STATE" in led["supportedCommands"]
    topics = contract["topics"]
    connected = threading.Event()
    subscribed = threading.Event()
    pending_ack_mids: dict[int, str] = {}
    led_state = False

    def on_connect(client: mqtt.Client, _userdata: object, _flags: object, rc: int) -> None:
        if rc != 0:
            emit(event="connection_error", returnCode=rc)
            return
        connected.set()
        client.subscribe(topics["commands"], qos=1)

    def on_subscribe(_client: mqtt.Client, _userdata: object, _mid: int, _qos: object) -> None:
        subscribed.set()
        emit(event="ready", deviceId=args.device_id, contractVersion=contract["contractVersion"])

    def on_message(client: mqtt.Client, _userdata: object, message: mqtt.MQTTMessage) -> None:
        nonlocal led_state
        try:
            payload = json.loads(message.payload)
            assert message.topic == topics["commands"]
            assert payload["capability"] == led["externalCommandIdentifier"]
            assert payload["command"] in led["supportedCommands"]
            assert isinstance(payload["value"], bool)
            command_id = payload["commandId"]
            led_state = payload["value"]
            emit(event="command", commandId=command_id, capability=payload["capability"],
                 command=payload["command"], ledState=led_state)
            ack = {"commandId": command_id, "status": "ACCEPTED", "timestamp": int(time.time())}
            publication = client.publish(topics["commandAck"], json.dumps(ack), qos=1)
            pending_ack_mids[publication.mid] = command_id
        except Exception as error:
            emit(event="command_error", message=str(error))

    def on_publish(_client: mqtt.Client, _userdata: object, mid: int) -> None:
        if mid in pending_ack_mids:
            emit(event="ack_published", commandId=pending_ack_mids.pop(mid),
                 status="ACCEPTED", mid=mid)

    client = mqtt.Client(client_id=args.client_id, clean_session=True)
    client.on_connect = on_connect
    client.on_subscribe = on_subscribe
    client.on_message = on_message
    client.on_publish = on_publish
    client.connect(args.broker_host, args.broker_port, keepalive=30)
    client.loop_start()
    if not connected.wait(10) or not subscribed.wait(10):
        emit(event="startup_timeout")
        return 1
    for line in sys.stdin:
        command = line.strip()
        if command == "quit":
            break
        if command == "status":
            client.publish(topics["status"], "online", qos=1).wait_for_publish()
            emit(event="status_published", topic=topics["status"])
        elif command.startswith("telemetry:"):
            value = float(command.split(":", 1)[1])
            payload = {"deviceId": args.device_id,
                       "timestamp": dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z"),
                       "capabilityKey": read["key"], "value": value, "unit": read["unit"]}
            client.publish(topics["telemetry"], json.dumps(payload), qos=1).wait_for_publish()
            emit(event="telemetry_published", topic=topics["telemetry"], value=value)
        else:
            emit(event="unknown_command", command=command)
    client.loop_stop()
    client.disconnect()
    emit(event="stopped", ledState=led_state)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
