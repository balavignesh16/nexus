import sys
import tempfile
from pathlib import Path
import json
import unittest
from unittest.mock import Mock, patch
import threading
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from bridge import parse_frame, check_command, Journal, Worker, load_config

ID='11111111-1111-4111-8111-111111111111'
def command(value=True, capability='LED', timestamp=1000):
    return {'commandId':ID,'capability':capability,'command':'SET_STATE','value':value,'timestamp':timestamp}

class ProtocolTests(unittest.TestCase):
    def test_malformed_frames_fail_closed(self):
        for raw in (b'{}', b'x'*1025, b'{"serialVersion":1,"serialVersion":1,"kind":"hello"}',
                    b'{"serialVersion":true,"kind":"hello"}', b'{"serialVersion":1,"kind":"ack","value":NaN}'):
            with self.subTest(raw=raw[:30]), self.assertRaises((ValueError,UnicodeError)):
                parse_frame(raw)
    def test_valid_typed_frame(self):
        self.assertEqual('hello',parse_frame(b'{"serialVersion":1,"kind":"hello"}')['kind'])
    def test_only_supported_board_commands_and_freshness(self):
        self.assertEqual(True,check_command(json.dumps(command()).encode(),'ESP1',1000)['value'])
        for item, board in ((command(),'ESP2'),(command(180,'SERVO'),'ESP3'),
                            (command(1),'ESP1'),(command(timestamp=969),'ESP3')):
            with self.subTest(item=item,board=board), self.assertRaises(ValueError):
                check_command(json.dumps(item).encode(),board,1000)
        self.assertEqual(120,check_command(json.dumps(command(120,'SERVO')).encode(),'ESP3',1000)['value'])
    def test_uncertain_effect_not_replayed_after_process_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            path=str(Path(directory)/'journal.sqlite')
            journal=Journal(path,'device')
            self.assertEqual((True,None),journal.claim(command()))
            journal.db.close()
            restarted=Journal(path,'device')
            self.assertEqual((False,None),restarted.claim(command()))
            restarted.complete(ID,b'actual-device-ack')
            self.assertEqual((False,b'actual-device-ack'),restarted.claim(command()))
            with self.assertRaisesRegex(ValueError,'CONFLICT'): restarted.claim(command(False))
            restarted.db.close()

class WorkerTests(unittest.TestCase):
    def setUp(self):
        self.client=Mock()
        self.config={'board':'ESP2','port':'COM8','chipId':'123456abcdef','deviceId':ID,
                     'readings':{'alarm_status':{'capabilityKey':'usb_alarm_status','type':'STRING','unit':None}}}
        with patch('bridge.Journal'):
            self.worker=Worker(self.config,self.client,threading.Event())
        self.worker.boot='boot'; self.worker.session=ID
        self.link=Mock()
    def frame(self,kind,**values):
        return {'kind':kind,'board':'ESP2','chipId':'123456abcdef','boot':'boot','deviceId':ID,**values}
    def test_valid_typed_telemetry_uses_existing_wire_contract(self):
        self.worker.on_frame(self.frame('telemetry',sequence=1,readings={'alarm_status':'NORMAL'}),self.link)
        calls=self.client.publish.call_args_list
        self.assertEqual(2,len(calls))
        payload=json.loads(calls[1].args[1])
        self.assertEqual('usb_alarm_status',payload['capabilityKey'])
        self.assertEqual(payload['capabilityKey'],payload['sensorType'])
        self.assertIsNone(payload['unit'])
        self.assertEqual('NORMAL',payload['value'])
        self.assertEqual(ID,payload['deviceId'])
    def test_invalid_type_does_not_publish_online_or_partial_telemetry(self):
        with self.assertRaises(ValueError):
            self.worker.on_frame(self.frame('telemetry',sequence=1,readings={'alarm_status':True}),self.link)
        self.client.publish.assert_not_called()
    def test_wrong_device_chip_is_rejected(self):
        frame=self.frame('telemetry',sequence=1,readings={'alarm_status':'NORMAL'});frame['chipId']='wrong'
        with self.assertRaises(ValueError): self.worker.on_frame(frame,self.link)
        self.client.publish.assert_not_called()
    def test_uncorrelated_device_ack_never_becomes_backend_acceptance(self):
        self.worker.on_frame(self.frame('ack',session=ID,commandId=ID,status='ACCEPTED',
                                       reason='PROCESSED_PHYSICALLY_UNVERIFIED'),self.link)
        self.client.publish.assert_not_called()
    def test_correlated_device_ack_is_recorded_and_forwarded(self):
        self.worker.pending[ID]=0
        self.worker.on_frame(self.frame('ack',session=ID,commandId=ID,status='ACCEPTED',
                                       reason='PROCESSED_PHYSICALLY_UNVERIFIED'),self.link)
        payload=json.loads(self.client.publish.call_args.args[1])
        self.assertEqual('ACCEPTED',payload['status'])
        self.assertEqual(ID,payload['commandId'])
        self.worker.journal.complete.assert_called_once()
    def test_serial_write_is_not_an_ack(self):
        self.link.write.side_effect=lambda raw:len(raw)
        self.worker.write(self.link,{'serialVersion':1,'kind':'bind'})
        self.client.publish.assert_not_called()

if __name__=='__main__': unittest.main()
