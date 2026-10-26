"""USB gateway to the EXISTING MQTT intake. No command-creation or backend-write API.
Run from this worktree; dependency install is isolated in .nexus/usb-deps.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import logging
import os
from pathlib import Path
import queue
import sqlite3
import sys
import threading
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT / '.nexus/usb-deps'), str(ROOT / 'sdk/python')]
import serial
import paho.mqtt.client as mqtt
from nexus_contract.validator import validate
from nexus_device_sdk.client import DeviceClient
from nexus_device_sdk.transport import MqttTransport
from diagnose import open_port

MAX_FRAME = 1024
LOG = logging.getLogger('nexus-usb')


class Journal:
    """Crash-safe serial-effect barrier. An uncertain write is NEVER automatically sent again."""
    def __init__(self, path, device):
        self.device = device
        self.db = sqlite3.connect(path, timeout=2, check_same_thread=False)
        self.db.execute('PRAGMA journal_mode=WAL')
        self.db.execute('PRAGMA synchronous=FULL')
        self.db.execute('CREATE TABLE IF NOT EXISTS deliveries(device TEXT,id TEXT,digest TEXT,ack BLOB,PRIMARY KEY(device,id))')
        self.db.commit()

    def claim(self, command):
        digest = hashlib.sha256(json.dumps(command, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        row = self.db.execute('SELECT digest,ack FROM deliveries WHERE device=? AND id=?', (self.device, command['commandId'])).fetchone()
        if row:
            if row[0] != digest: raise ValueError('COMMAND_ID_CONFLICT')
            return False, row[1]
        if self.db.execute('SELECT COUNT(*) FROM deliveries').fetchone()[0] >= 100000:
            raise ValueError('JOURNAL_CAPACITY')
        self.db.execute('INSERT INTO deliveries VALUES(?,?,?,NULL)', (self.device, command['commandId'], digest))
        self.db.commit()  # Durable BEFORE serial write, even when the outcome will be uncertain.
        return True, None

    def complete(self, command_id, ack):
        self.db.execute('UPDATE deliveries SET ack=? WHERE device=? AND id=?', (ack, self.device, command_id))
        self.db.commit()


def parse_frame(raw: bytes) -> dict:
    if len(raw) > MAX_FRAME:
        raise ValueError('FRAME_TOO_LARGE')
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('DUPLICATE_KEY')
            result[key] = value
        return result
    value = json.loads(raw, object_pairs_hook=unique, parse_constant=lambda _: (_ for _ in ()).throw(ValueError('NONFINITE')))
    if not isinstance(value, dict) or type(value.get('serialVersion')) is not int or value['serialVersion'] != 1:
        raise ValueError('SERIAL_VERSION')
    if value.get('kind') not in ('hello', 'telemetry', 'ack'):
        raise ValueError('FRAME_KIND')
    return value


def check_command(raw: bytes, board: str, now: int) -> dict:
    if len(raw) > 700:
        raise ValueError('COMMAND_TOO_LARGE')
    result = validate('command', raw, reference_time=now, freshness_window=30)
    if result.verdict != 'ACCEPT':
        raise ValueError('COMMAND_CONTRACT')
    command = json.loads(raw)
    if command['command'] != 'SET_STATE' or abs(now - command['timestamp']) > 30:
        raise ValueError('COMMAND_UNSUPPORTED_OR_STALE')
    cap, value = command['capability'], command['value']
    if cap == 'LED' and board in ('ESP1', 'ESP3') and type(value) is bool:
        return command
    if cap == 'SERVO' and board == 'ESP3' and type(value) is int and value in (60, 90, 120):
        return command
    raise ValueError('COMMAND_UNSUPPORTED')


class Worker:
    def __init__(self, config, mqtt_client, stop):
        self.config, self.mqtt, self.stop = config, mqtt_client, stop
        self.device = str(uuid.UUID(config['deviceId']))
        self.board = config['board']
        self.client = DeviceClient(self.device, MqttTransport(mqtt_client))
        self.commands = queue.Queue(maxsize=16)
        self.pending = {}  # At most 16 written requests; never retransmit serial effects.
        self.session, self.boot = None, None
        self.bound, self.ready = False, False
        self.sequence = -1
        self.epoch_synced = 0.0
        self.last_invalid = 0.0
        self.last_values = {}
        self.last_published = {}
        self.last_online = 0.0
        journal = ROOT / '.nexus/usb-delivery.sqlite3'
        journal.parent.mkdir(exist_ok=True)
        self.journal = Journal(str(journal), self.device)

    def enqueue(self, raw):
        # Never ACK acceptance from MQTT delivery or serial write. Only a matching DEVICE frame can do so.
        try:
            command = check_command(raw, self.board, int(time.time()))
            self.commands.put_nowait(command)
        except (ValueError, queue.Full, KeyError, TypeError):
            LOG.warning('%s command refused before serial dispatch; no device ACK fabricated', self.board)

    def write(self, link, value):
        raw = json.dumps(value, separators=(',', ':'), allow_nan=False).encode() + b'\n'
        if len(raw) > MAX_FRAME:
            raise ValueError('FRAME_TOO_LARGE')
        if link.write(raw) != len(raw):
            raise serial.SerialException('partial serial write; outcome uncertain')

    def on_frame(self, frame, link):
        if frame.get('board') != self.board or frame.get('chipId') != self.config['chipId']:
            raise ValueError('BOARD_IDENTITY_MISMATCH')
        boot = frame.get('boot')
        if not isinstance(boot, str) or not 1 <= len(boot) <= 32:
            raise ValueError('BOOT_ID')
        if frame['kind'] == 'hello':
            new_boot = boot != self.boot
            if boot != self.boot:
                self.pending.clear()  # Uncertain previous effects are never replayed after reboot.
                self.sequence = -1
                self.boot = boot
            previous_device, previous_session = frame.get('deviceId'), frame.get('session')
            if previous_device and previous_device != self.device:
                raise ValueError('BOUND_TO_ANOTHER_DEVICE')
            if previous_session:
                self.session = str(uuid.UUID(previous_session))
            elif not self.session or not previous_device:
                self.session = str(uuid.uuid4())
            self.ready = frame.get('outputsReady') is True
            self.write(link, {'serialVersion': 1, 'kind': 'bind', 'board': self.board, 'boot': boot,
                              'deviceId': self.device, 'session': self.session, 'epoch': int(time.time())})
            self.bound = False  # Require subsequent telemetry echo before forwarding commands/status.
            self.epoch_synced = time.monotonic()
            if new_boot: LOG.info('%s chip/boot verified; serial binding sent', self.board)
            return
        if frame.get('boot') != self.boot or frame.get('deviceId') != self.device:
            return
        if frame['kind'] == 'telemetry':
            sequence = frame.get('sequence')
            if type(sequence) is not int or sequence <= self.sequence:
                return
            readings = frame.get('readings')
            if not isinstance(readings, dict) or len(readings) > 12:
                raise ValueError('READINGS')
            messages = []
            for key, value in readings.items():
                mapping = self.config['readings'].get(key)
                if mapping is None:
                    continue
                kind = mapping['type']
                valid = ((kind == 'BOOLEAN' and type(value) is bool) or
                         (kind == 'NUMBER' and type(value) in (int, float) and 0 <= value <= 4095) or
                         (kind == 'STRING' and isinstance(value, str) and len(value) <= 64))
                if not valid:
                    raise ValueError('READING_TYPE')
                mid = hashlib.sha256(f'{self.device}:{self.boot}:{sequence}:{key}'.encode()).hexdigest()[:48]
                message = self.client.build_telemetry(mapping['capabilityKey'], value, mapping.get('unit'),
                                                      capability_key=mapping['capabilityKey'], message_id=mid)
                self.client.check(message)
                messages.append((key,value,message))
            if not messages:
                raise ValueError('NO_SUPPORTED_READINGS')
            # Validate the WHOLE frame before emitting any status or capability reading.
            self.bound = True
            now=time.monotonic()
            if now-self.last_online>=5:
                self.client.publish_status(True); self.last_online=now
            for key,value,message in messages:
                # Changes go immediately; unchanged values heartbeat every5 s (binding freshness10 s).
                # This is sampling/coalescing, not a claim to retain every UART report.
                if key not in self.last_values or self.last_values[key]!=value or now-self.last_published[key]>=5:
                    self.client.send(message)
                    self.last_values[key]=value; self.last_published[key]=now
            self.sequence = sequence
            if sequence % 60 == 0: LOG.info('%s physical telemetry forwarded', self.board)
        elif frame['kind'] == 'ack':
            command_id = frame.get('commandId')
            if frame.get('session') != self.session or command_id not in self.pending:
                return
            status, reason = frame.get('status'), frame.get('reason')
            allowed = {'PROCESSED_PHYSICALLY_UNVERIFIED', 'UNSUPPORTED', 'MALFORMED', 'STALE',
                       'CLOCK_SYNC_EXPIRED', 'OUTPUTS_UNVERIFIED', 'DEDUPE_CAPACITY', 'COMMAND_ID_CONFLICT',
                       'LOCAL_ALARM_ONLY', 'UNSUPPORTED_OR_INVALID_VALUE'}
            if status not in ('ACCEPTED', 'REJECTED') or reason not in allowed:
                raise ValueError('ACK_FIELDS')
            if status == 'ACCEPTED' and reason != 'PROCESSED_PHYSICALLY_UNVERIFIED':
                raise ValueError('ACK_TRUTHFULNESS')
            ack = self.client.build_acknowledgement(command_id, status, reason)
            self.client.check(ack)
            self.journal.complete(command_id, ack.payload)
            self.client.send(ack)
            self.pending.pop(command_id, None)

    def run(self):
        while not self.stop.is_set():
            try:
                with open_port(self.config['port']) as link:
                    LOG.info('%s opened configured serial port', self.board)
                    self.bound = False
                    last_contact = time.monotonic()
                    frame = bytearray()
                    discard = False
                    while not self.stop.is_set():
                        block = link.read(256)
                        for byte in block:
                            if byte == 10:
                                if frame and not discard:
                                    try:
                                        parsed = parse_frame(bytes(frame))
                                        self.on_frame(parsed, link)
                                        if parsed.get('board') == self.board and parsed.get('chipId') == self.config['chipId']:
                                            last_contact = time.monotonic()
                                    except (ValueError, KeyError, TypeError):
                                        if time.monotonic() - self.last_invalid > 5:
                                            LOG.warning('%s invalid frame dropped (rate-limited log)', self.board)
                                            self.last_invalid = time.monotonic()
                                frame.clear(); discard = False
                            elif not discard:
                                if len(frame) < MAX_FRAME:
                                    frame.append(byte)
                                else:
                                    frame.clear(); discard = True
                        now = time.monotonic()
                        for command_id, sent in list(self.pending.items()):
                            if now - sent > 30:
                                self.pending.pop(command_id)
                                LOG.warning('%s device response absent; outcome uncertain, no retry', self.board)
                        if now - last_contact > 10:
                            raise serial.SerialException('USB heartbeat timeout')
                        if self.bound and self.ready and self.mqtt.is_connected() and len(self.pending) < 16:
                            try:
                                command = self.commands.get_nowait()
                            except queue.Empty:
                                continue
                            if abs(time.time() - command['timestamp']) > 30:
                                continue
                            if command['commandId'] in self.pending:
                                continue
                            fresh, recorded_ack = self.journal.claim(command)
                            if not fresh:
                                if recorded_ack:
                                    self.mqtt.publish(self.client.ack_topic, recorded_ack, qos=1)
                                else:
                                    LOG.warning('%s command already uncertain; unsafe retransmission blocked', self.board)
                                continue
                            self.pending[command['commandId']] = now
                            self.write(link, {'serialVersion': 1, 'kind': 'command', 'board': self.board,
                                              'boot': self.boot, 'session': self.session, 'deviceId': self.device,
                                              'command': command})
            except (serial.SerialException, OSError, ValueError, sqlite3.Error):
                LOG.warning('%s serial unavailable or unsafe; reconnect in 2 s; no command replay', self.board)
            finally:
                self.bound = False
                self.pending.clear()
                while not self.commands.empty():
                    try: self.commands.get_nowait()
                    except queue.Empty: break
                if self.mqtt.is_connected(): self.client.publish_status(False)
            self.stop.wait(2)


def load_config(path):
    config = json.loads(Path(path).read_text(encoding='utf-8'))
    devices = config['devices']
    if not 1 <= len(devices) <= 3:
        raise ValueError('Configure one to three boards for staged testing')
    for key in ('port', 'board', 'deviceId', 'chipId'):
        if len({d[key] for d in devices}) != len(devices):
            raise ValueError('Duplicate identity/port')
    for d in devices:
        if d['board'] not in ('ESP1', 'ESP2', 'ESP3') or not d['chipId']:
            raise ValueError('Set measured chip IDs before bridge use')
        if uuid.UUID(d['deviceId']).int == 0:
            raise ValueError('Set real NEXUS UUIDs; no synthetic device identity')
        if d.get('physicalChecksConfirmed') is not True:
            raise ValueError('Physical/reset safety checks must be confirmed before port opening')
    return config


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--config', required=True)
    p.add_argument('--boards', nargs='+', choices=['ESP1','ESP2','ESP3'])
    args = p.parse_args()
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s', force=True)
    config = load_config(args.config)
    if args.boards:
        config['devices'] = [d for d in config['devices'] if d['board'] in args.boards]
        if not config['devices']: raise ValueError('No selected configured boards')
    stop = threading.Event()
    workers = []
    clients = []
    for d in config['devices']:
        # One MQTT connection/will per device, so a crashed bridge cannot leave all devices online forever.
        client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f'nexus-usb-{d["deviceId"]}')
        client.max_queued_messages_set(64)
        if d.get('usernameEnv'):
            client.username_pw_set(os.environ[d['usernameEnv']], os.environ[d['passwordEnv']])
        client.will_set(f'nexus/devices/{d["deviceId"]}/status', b'offline', qos=1, retain=True)
        worker = Worker(d, client, stop)
        def on_connect(c, userdata, flags, reason, properties, w=worker):
            if not reason.is_failure: c.subscribe(f'nexus/devices/{w.device}/commands', qos=1)
        def on_message(c, userdata, message, w=worker):
            if not message.retain: w.enqueue(message.payload)  # retained actuator commands are never executed
        client.on_connect, client.on_message = on_connect, on_message
        client.connect(config['mqtt']['host'], config['mqtt'].get('port', 1883), keepalive=15)
        client.loop_start()
        clients.append(client)
        thread = threading.Thread(target=worker.run, daemon=True)
        thread.start(); workers.append(thread)
    try:
        while all(t.is_alive() for t in workers): stop.wait(1)
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        for thread in workers: thread.join(3)
        for client in clients: client.disconnect(); client.loop_stop()


if __name__ == '__main__':
    main()
