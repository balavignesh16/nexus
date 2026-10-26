"""Local behavioural ESP demonstration; NEVER connects to USB or REAL device identities."""
import sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT/'.nexus/usb-deps'), str(ROOT/'sdk/python'), str(ROOT/'usb-integration')]
import argparse
import hashlib
import json
import math
import queue
import random
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import paho.mqtt.client as mqtt
from nexus_device_sdk.client import DeviceClient
from nexus_device_sdk.transport import MqttTransport
from nexus_contract.validator import validate
from register_devices import api

PROFILES = {
 'ESP1': ['temperature','humidity','pressure','motion','button','demo_led_state','demo_led_mode','led'],
 'ESP2': ['demo_gas_adc','demo_alarm','demo_rgb','demo_buzzer_state','buzzer'],
 'ESP3': ['demo_servo_angle','demo_servo_target','demo_led_state','led','servo'],
}
CUSTOM = {
 'demo_led_state': ('BOOLEAN','BOOLEAN',None), 'demo_led_mode':('STRING',None,None),
 'demo_gas_adc':('NUMBER','ADC_COUNTS',4095), 'demo_alarm':('STRING',None,None),
 'demo_rgb':('STRING',None,None), 'demo_buzzer_state':('BOOLEAN','BOOLEAN',None),
 'demo_servo_angle':('NUMBER','DEGREES',180), 'demo_servo_target':('NUMBER','DEGREES',180),
}

def provision():
    """Idempotent ordinary APIs. Existing USB devices, hierarchy and rules are not modified."""
    def ensure(path, name, data):
        return next((x for x in api(path) if x['name']==name),None) or api(path,dict(name=name,**data))
    site=ensure('/sites','NEXUS Live Demo',{'description':'Explicit software simulation, separate from physical USB boards'})
    building=ensure('/sites/'+site['id']+'/buildings','Three-Room Demonstration',{})
    definitions={d['key']:d for d in api('/capability-definitions')}
    for key,(kind,unit,maximum) in CUSTOM.items():
        if key not in definitions:
            definitions[key]=api('/capability-definitions',dict(key=key,displayName=key.replace('_',' ').title(),
                direction='READ',valueType=kind,unit=unit,allowedUnits=[] if unit is None else [unit],
                minimum=0 if kind=='NUMBER' else None,maximum=maximum,metadata={'source':'SIMULATED behavioural demo'}))
    templates={t['key']:t for t in api('/device-templates')}
    config=[]
    for board,room in [('ESP1','Living Room'),('ESP2','Safety Room'),('ESP3','Automation Room')]:
        space=ensure('/buildings/'+building['id']+'/spaces',room,{'spaceType':'ROOM'})
        key='demo_three_'+board.lower()+'_v1'
        template=templates.get(key) or api('/device-templates',dict(key=key,displayName='DEMO '+board+' '+room,
            manufacturer='NEXUS Simulation',model='Behavioural ESP32 v1',protocolType='MQTT',enabled=True,
            metadata={'source':'SIMULATED','notPhysical':True}))
        present={c['capabilityDefinitionId'] for c in api('/device-templates/'+template['id']+'/capabilities')}
        for index,cap in enumerate(PROFILES[board]):
            definition=definitions[cap]
            if definition['id'] not in present:
                api('/device-templates/'+template['id']+'/capabilities',dict(capabilityDefinitionId=definition['id'],
                    required=False,defaultUnit=definition['unit'],displayOrder=index,freshnessThresholdSeconds=15))
        session=api('/device-onboarding',dict(requestKey=key,templateId=template['id'],spaceId=space['id'],
            name='DEMO-'+board+' '+room,serialNumber='NEXUS-DEMO-V1-'+board,mode='SIMULATED',bindings={}))
        device=api('/devices/'+session['deviceId'])
        if device['mode']!='SIMULATED': raise ValueError('Refusing non-simulated identity')
        if session['state'] in ('STATUS_TIMEOUT','TELEMETRY_REJECTED'):
            api('/device-onboarding/'+session['id']+'/retry',{})
        caps=api('/devices/'+device['id']+'/capabilities')
        for cap in PROFILES[board]:
            definition=definitions[cap]
            if definition['direction']=='WRITE' and not any(c['capabilityType']==cap.upper() for c in caps):
                api('/devices/'+device['id']+'/capabilities',dict(capabilityType=cap.upper(),direction='WRITE',unit=definition['unit'],metadata={'source':'SIMULATED'}))
        config.append(dict(board=board,deviceId=device['id'],name=device['name'],space=space['name'],mode='SIMULATED',
            readings={k:definitions[k]['unit'] for k in PROFILES[board] if definitions[k]['direction']=='READ'}))
    return config

class Model:
    """Seeded bounded sensor noise, first-order response, hysteresis and finite actuator speed.
    Not electrical/circuit simulation, gas calibration, or hardware safety validation.
    """
    def __init__(self):
        self.rng=random.Random(32)
        self.elapsed=0.; self.temperature=25.; self.humidity=50.; self.pressure=1013.25
        self.targets={'temperature':25.,'humidity':50.,'pressure':1013.25,'gas':200.}
        self.gas=200.; self.motion=False; self.button=False; self.mode=0; self.hold=0.
        self.led1=False; self.led3=False; self.servo=90.; self.target=90.; self.severity=0
        self.override_until=0.; self.buzzer_override=False
    def inputs(self, board, values):
        allowed={'ESP1':{'temperature':(5,50),'humidity':(10,95),'pressure':(900,1100)},'ESP2':{'gas':(0,4095)},'ESP3':{}}[board]
        for key,value in values.items():
            if key in allowed:
                lo,hi=allowed[key]
                if type(value) not in (int,float) or not math.isfinite(value) or not lo<=value<=hi: raise ValueError('Invalid sensor input')
            elif board=='ESP1' and key in ('motion','button'):
                if type(value) is not bool: raise ValueError('Boolean input required')
            else: raise ValueError('Only simulated sensor inputs can be changed here')
        for key,value in values.items():
            if key=='motion': self.motion=value
            elif key=='button':
                if value and not self.button: self.mode=(self.mode+1)%3
                self.button=value
            else: self.targets[key]=value
    def command(self, board, cap, value):
        if cap=='LED' and type(value) is bool and board in ('ESP1','ESP3'):
            if board=='ESP1': self.mode=1 if value else 2; self.led1=value
            else: self.led3=value
        elif board=='ESP3' and cap=='SERVO' and type(value) in (float,int) and 0<=value<=180: self.target=float(value)
        elif board=='ESP2' and cap=='BUZZER' and type(value) is bool:
            self.buzzer_override=value; self.override_until=self.elapsed+10
        else: raise ValueError('Unsupported command')
    def step(self, dt):
        self.elapsed+=dt
        alpha=1-math.exp(-dt/4)
        for key in ('temperature','humidity','pressure'):
            setattr(self,key,getattr(self,key)+(self.targets[key]-getattr(self,key))*alpha)
        self.gas+=(self.targets['gas']-self.gas)*(1-math.exp(-dt/1.2))
        if self.motion: self.hold=self.elapsed+3
        self.led1=self.mode==1 or (self.mode==0 and self.elapsed<self.hold)
        delta=self.gas-200
        if delta>=600: self.severity=2
        elif self.severity==2 and delta>=500: pass
        elif delta>=300: self.severity=1
        elif self.severity>=1 and delta>=200: self.severity=1
        else: self.severity=0
        distance=self.target-self.servo
        self.servo+=max(-60*dt,min(60*dt,distance))
    def readings(self):
        noise=lambda scale:self.rng.uniform(-scale,scale)
        buzzer=self.severity==2 or (self.elapsed<self.override_until and self.buzzer_override)
        return {
          'ESP1':dict(temperature=round(self.temperature+noise(.03),2),humidity=round(self.humidity+noise(.08),2),pressure=round(self.pressure+noise(.05),2),motion=self.motion,button=self.button,demo_led_state=self.led1,demo_led_mode=['AUTO','FORCE_ON','FORCE_OFF'][self.mode]),
          'ESP2':dict(demo_gas_adc=max(0,min(4095,round(self.gas+noise(2)))),demo_alarm=['NORMAL','WARNING','ALARM'][self.severity],demo_rgb=['GREEN','YELLOW','RED'][self.severity],demo_buzzer_state=buzzer),
          'ESP3':dict(demo_servo_angle=round(self.servo,1),demo_servo_target=self.target,demo_led_state=self.led3),
        }

class Demo:
    def __init__(self, config):
        self.config=config; self.model=Model(); self.lock=threading.Lock(); self.inbox=queue.Queue(64)
        self.stop=threading.Event(); self.clients=[]; self.results={}; self.acks=[]; self.last_publish=0.
        for d in config:
            if api('/devices/'+d['deviceId'])['mode']!='SIMULATED': raise ValueError('REAL devices prohibited')
            client=mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,client_id='nexus-demo-'+d['deviceId'])
            client.max_queued_messages_set(128)
            client.will_set(f"nexus/devices/{d['deviceId']}/status",b'offline',qos=1,retain=True)
            sdk=DeviceClient(d['deviceId'],MqttTransport(client))
            def connected(c,u,f,r,p,device=d,device_sdk=sdk):
                if not r.is_failure:
                    c.subscribe(f"nexus/devices/{device['deviceId']}/commands",qos=1); device_sdk.publish_status(True)
            def message(c,u,m,device=d,device_sdk=sdk):
                if m.retain or len(m.payload)>4096: return
                try: self.inbox.put_nowait((device,device_sdk,bytes(m.payload)))
                except queue.Full: pass # No ACK or execution on overflow.
            client.on_connect=connected; client.on_message=message
            client.connect('127.0.0.1',1883,15); client.loop_start()
            self.clients.append((d,client,sdk))
    def process(self,d,sdk,payload):
        try:
            if validate('command',payload).verdict=='REJECT': return
            c=json.loads(payload); identity=(d['deviceId'],c['commandId']); digest=hashlib.sha256(payload).hexdigest()
            if identity in self.results:
                previous,ack=self.results[identity]
                if previous==digest: sdk.send(ack)
                else: sdk.publish_acknowledgement(c['commandId'],'REJECTED','COMMAND_ID_CONFLICT')
                return
            if len(self.results)>=10000: return
            if abs(time.time()-c['timestamp'])>30: raise ValueError('STALE')
            if c['command']!='SET_STATE': raise ValueError('Unsupported verb')
            self.model.command(d['board'],c['capability'].upper(),c['value'])
            ack=sdk.build_acknowledgement(c['commandId'],'ACCEPTED','SIMULATED_MODEL_APPLIED_NOT_PHYSICAL')
            self.results[identity]=(digest,ack); sdk.send(ack)
            self.acks.append(dict(board=d['board'],commandId=c['commandId'],capability=c['capability'],value=c['value'],timestamp=time.time()))
            self.acks=self.acks[-20:]
        except (ValueError,KeyError,TypeError):
            # Invalid commands never alter state; valid IDs receive an honest rejection.
            try: sdk.publish_acknowledgement(json.loads(payload)['commandId'],'REJECTED','SIMULATOR_REFUSED')
            except (ValueError,KeyError,TypeError): pass
    def run(self):
        last=time.monotonic()
        while not self.stop.wait(.05):
            now=time.monotonic()
            with self.lock:
                self.model.step(min(now-last,.25)); last=now
                for _ in range(16):
                    try: self.process(*self.inbox.get_nowait())
                    except queue.Empty: break
                if now-self.last_publish>=5:
                    self.last_publish=now; readings=self.model.readings()
                    for d,client,sdk in self.clients:
                        if client.is_connected():
                            sdk.publish_status(True)
                            for key,unit in d['readings'].items(): sdk.publish_telemetry(key,readings[d['board']][key],unit,capability_key=key)
    def snapshot(self):
        with self.lock:
            values=self.model.readings()
            return dict(source='SIMULATED',timestamp=time.time(),devices=[dict(**d,values=values[d['board']],connected=c.is_connected()) for d,c,s in self.clients],acks=self.acks)

def serve(demo):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args): pass
        def allowed(self):
            return self.headers.get('Host') in ('localhost:8766','127.0.0.1:8766') and self.headers.get('Origin') in (None,'http://localhost:5173','http://127.0.0.1:5173')
        def reply(self,code,data):
            self.send_response(code)
            if self.allowed():
                self.send_header('Access-Control-Allow-Origin',self.headers.get('Origin','http://localhost:5173'))
                self.send_header('Vary','Origin')
            self.send_header('Content-Type','application/json'); self.end_headers(); self.wfile.write(json.dumps(data).encode())
        def do_OPTIONS(self):
            if not self.allowed(): return self.reply(403,{'error':'Local demo only'})
            self.send_response(204); self.send_header('Access-Control-Allow-Origin',self.headers.get('Origin','http://localhost:5173'))
            self.send_header('Access-Control-Allow-Headers','Content-Type'); self.send_header('Access-Control-Allow-Methods','GET,POST,OPTIONS'); self.end_headers()
        def do_GET(self):
            if not self.allowed(): return self.reply(403,{'error':'Local demo only'})
            if self.path!='/state': return self.reply(404,{})
            self.reply(200,demo.snapshot())
        def do_POST(self):
            if not self.allowed() or self.headers.get('Content-Type')!='application/json': return self.reply(403,{'error':'Local JSON only'})
            try:
                size=int(self.headers.get('Content-Length','0'))
                if not 0<size<=1024: raise ValueError('Body bound')
                if self.path!='/inputs': return self.reply(404,{})
                body=json.loads(self.rfile.read(size))
                with demo.lock: demo.model.inputs(body['board'],body['values'])
                self.reply(200,{'source':'SIMULATED','accepted':True})
            except (ValueError,KeyError,TypeError): self.reply(400,{'error':'Invalid simulated sensor input'})
    return ThreadingHTTPServer(('127.0.0.1',8766),Handler)

def main():
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('--provision',action='store_true'); args=parser.parse_args()
    path=ROOT/'.nexus/demo-simulation.json'
    # Bind BEFORE provisioning or MQTT so a duplicate instance cannot steal existing client IDs.
    placeholder=type('Pending',(),{})(); server=serve(placeholder)
    config=provision() if args.provision else json.loads(path.read_text())
    path.write_text(json.dumps(config,indent=2))
    demo=Demo(config)
    # Replace the bound handler after initialization without opening a second listening socket.
    placeholder.__dict__.update(demo.__dict__)
    placeholder.snapshot=demo.snapshot
    thread=threading.Thread(target=demo.run,daemon=True); thread.start()
    print('SIMULATED ESP1/ESP2/ESP3 ready: http://localhost:5173/simulation (local service 8766)',flush=True)
    try: server.serve_forever(poll_interval=.25)
    except KeyboardInterrupt: pass
    finally:
        demo.stop.set(); thread.join(3)
        for d,c,s in demo.clients: s.publish_status(False); c.disconnect(); c.loop_stop()
        server.server_close()

if __name__=='__main__': main()
