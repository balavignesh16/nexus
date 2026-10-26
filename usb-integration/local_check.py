"""Finite, explicitly requested bench checks. No MQTT/backend access; no arbitrary command option.
Physical observations are NOT inferred from software reports. Run one board at a time, unloaded.
"""
import argparse
import json
import time
from pathlib import Path
from diagnose import open_port

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--board',choices=['ESP1','ESP2','ESP3'],required=True)
    p.add_argument('--port',required=True)
    args=p.parse_args()
    sequences={
        'ESP1':[('FORCE ON',{'led_mode':'FORCE_ON','led_state':True}),
                ('FORCE OFF',{'led_mode':'FORCE_OFF','led_state':False}),('AUTO',{'led_mode':'AUTO'})],
        'ESP2':[('NORMAL',{'alarm_status':'NORMAL','buzzer_requested':False}),
                ('WARNING',{'alarm_status':'WARNING','buzzer_requested':False}),
                ('ALARM',{'alarm_status':'ALARM','buzzer_requested':True}),
                ('NORMAL',{'alarm_status':'NORMAL','buzzer_requested':False}),
                ('AUTO',{'alarm_demo_override':False})],
        'ESP3':[('LIGHT ON',{'led_state':True}),('LIGHT OFF',{'led_state':False}),
                ('OPEN',{'servo_requested_angle':120}),('CLOSE',{'servo_requested_angle':60}),
                ('CENTER',{'servo_requested_angle':90}),
                ('OPEN LIGHT ON',{'servo_requested_angle':120,'led_state':True}),
                ('CLOSE LIGHT OFF',{'servo_requested_angle':60,'led_state':False}),
                ('OPEN LIGHT OFF',{'servo_requested_angle':120,'led_state':False}),
                ('CLOSE LIGHT ON',{'servo_requested_angle':60,'led_state':True}),
                ('CENTER',{'servo_requested_angle':90}),('LIGHT OFF',{'led_state':False})],
    }
    result={'board':args.board,'port':args.port,'checks':[],'physicalVerification':'NOT_INDEPENDENTLY_MEASURED'}
    with open_port(args.port) as link:
        def frame_until(predicate, seconds=8):
            deadline=time.monotonic()+seconds
            while time.monotonic()<deadline:
                line=link.read_until(b'\n',1025)
                if not line.endswith(b'\n'): continue
                try: frame=json.loads(line)
                except (ValueError,UnicodeError): continue
                if frame.get('serialVersion')!=1 or frame.get('board')!=args.board: continue
                if result.get('boot') and frame.get('boot')!=result['boot']: raise AssertionError('Unexpected reboot/brownout')
                if predicate(frame): return frame
            raise AssertionError('Expected firmware report not observed')
        hello=frame_until(lambda f:f.get('kind')=='hello')
        result.update(chipId=hello['chipId'],boot=hello['boot'])
        if not hello.get('outputsReady'): raise AssertionError('Outputs not armed')
        for text,expected in sequences[args.board]:
            link.write((text+'\n').encode())
            observed=frame_until(lambda f:f.get('kind')=='telemetry' and all(f.get('readings',{}).get(k)==v for k,v in expected.items()))
            result['checks'].append({'command':text,'expected':expected,'reported':observed['readings']})
            time.sleep(0.2)
    directory=Path(__file__).parent/'diagnostics'; directory.mkdir(exist_ok=True)
    path=directory/f'{args.board}-local-check.json'
    path.write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(args.board,'PASS',len(result['checks']),'software-state checks;',result['chipId'],'No independent physical verification.')

if __name__=='__main__': main()
