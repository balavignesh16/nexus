"""Receive-only diagnostic. Opening a USB UART can STILL reset the ESP32.
No serial writes, control-line pulses, bootloader entry or physical commands.
"""
import argparse
import json
import time
from pathlib import Path
import serial


def open_port(port):
    link = serial.Serial(port=None, baudrate=115200, timeout=0.2, write_timeout=0.5,
                         rtscts=False, dsrdtr=False, xonxoff=False)
    link.dtr = False
    link.rts = False
    link.port = port
    link.open()  # Windows opens exclusively; an occupied port fails, never gets stolen.
    return link


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--ports', nargs='+', required=True)
    p.add_argument('--seconds', type=int, default=8)
    p.add_argument('--reset-risk-confirmed', action='store_true')
    args = p.parse_args()
    if not args.reset_risk_confirmed or not 1 <= args.seconds <= 30:
        p.error('Confirm possible reset/local-output startup and select 1..30 seconds.')
    directory = Path(__file__).parent / 'diagnostics'
    directory.mkdir(exist_ok=True)
    for port in args.ports:
        result = {'port': port, 'receiveOnly': True, 'resetPossible': True, 'bytes': 0, 'output': '', 'error': None}
        try:
            captured = bytearray()
            with open_port(port) as link:
                deadline = time.monotonic() + args.seconds
                while time.monotonic() < deadline and len(captured) < 32768:
                    captured.extend(link.read(min(512, 32768 - len(captured))))
            result.update(bytes=len(captured), output=captured.decode('utf-8', errors='replace'))
        except (serial.SerialException, OSError) as error:
            result['error'] = type(error).__name__  # never dump configuration/secrets
        path = directory / f'{time.time_ns()}-{port}.json'
        path.write_text(json.dumps(result, indent=2), encoding='utf-8')
        print(json.dumps({'port':port,'bytes':result['bytes'],'error':result['error'],
                          'preview':result['output'][:300],'capture':str(path)}))


if __name__ == '__main__':
    main()
