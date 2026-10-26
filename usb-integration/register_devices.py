"""Explicit, idempotent HTTP provisioning; never changes existing devices/rules or creates commands."""
import argparse
import json
from pathlib import Path
from urllib.request import Request, urlopen

BASE='http://localhost:8080/api/v1'

def api(path, data=None):
    request=Request(BASE+path, data=None if data is None else json.dumps(data).encode(),
                    headers={'Content-Type':'application/json'})
    with urlopen(request,timeout=10) as response:
        return json.load(response)

READINGS={
    'ESP1': {'motion':('BOOLEAN','BOOLEAN'), 'button':('BOOLEAN','BOOLEAN'),
             'led_state':('BOOLEAN','BOOLEAN'), 'led_mode':('STRING',None)},
    'ESP2': {'gas_adc':('NUMBER','ADC_COUNTS'),'gas_baseline_adc':('NUMBER','ADC_COUNTS'),
             'alarm_status':('STRING',None),'alarm_demo_override':('BOOLEAN','BOOLEAN'),
             'buzzer_requested':('BOOLEAN','BOOLEAN')},
    'ESP3': {'servo_requested_angle':('NUMBER','DEGREES'),'led_state':('BOOLEAN','BOOLEAN'),
             'outputs_ready':('BOOLEAN','BOOLEAN')},
}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--space',required=True)
    p.add_argument('--inventory',required=True,help='JSON with ESP1/ESP2/ESP3 chipId values from actual hello frames')
    p.add_argument('--output',default=str(Path(__file__).parent/'config.local.json'))
    p.add_argument('--boards',nargs='+',choices=['ESP1','ESP2','ESP3'],default=['ESP1','ESP2','ESP3'])
    args=p.parse_args()
    inventory=json.loads(Path(args.inventory).read_text())
    api('/spaces/'+args.space) # Verify caller-specified existing hierarchy, not guessed IDs.
    definitions={d['key']:d for d in api('/capability-definitions')}
    templates={t['key']:t for t in api('/device-templates')}
    config={'mqtt':{'host':'127.0.0.1','port':1883},'devices':[]}
    for board,port,room in [('ESP1','COM5','Living Room'),('ESP2','COM8','Safety Room'),('ESP3','COM10','Automation Room')]:
        if board not in args.boards: continue
        chip=inventory[board]['chipId']
        if not isinstance(chip,str) or len(chip)!=12 or any(c not in '0123456789abcdef' for c in chip):
            raise ValueError('Expected actual 12-hex chip ID from firmware hello')
        mappings={}
        members=[]
        for source,(kind,unit) in READINGS[board].items():
            key='usb_'+source
            if key not in definitions:
                definitions[key]=api('/capability-definitions',{
                    'key':key,'displayName':'USB '+source.replace('_',' '),'direction':'READ','valueType':kind,
                    'unit':unit,'allowedUnits':[] if unit is None else [unit],
                    'minimum':0 if kind=='NUMBER' else None,
                    'maximum':180 if source=='servo_requested_angle' else 4095 if kind=='NUMBER' else None,
                    'metadata':{'provenance':'physical USB; reported/requested state, not independent actuation proof'}})
            definition=definitions[key]
            if definition['direction']!='READ' or definition['valueType']!=kind or definition['unit']!=unit:
                raise ValueError('Existing definition differs: '+key)
            members.append(definition)
            mappings[source]={'capabilityKey':key,'type':kind,'unit':unit}
        if board in ('ESP1','ESP3'): members.append(definitions['led'])
        if board=='ESP3': members.append(definitions['servo'])
        template_key='usb_'+board.lower()+'_v1'
        template=templates.get(template_key)
        if template is None:
            template=api('/device-templates',{'key':template_key,'displayName':board+' USB '+room,
                         'manufacturer':'Espressif','model':'ESP32 USB reconstructed v1','protocolType':'MQTT',
                         'enabled':True,'metadata':{'wire':'USB bridge to MQTT','noWifi':True}})
        present={c['capabilityDefinitionId'] for c in api('/device-templates/'+template['id']+'/capabilities')}
        for index,definition in enumerate(members):
            if definition['id'] not in present:
                api('/device-templates/'+template['id']+'/capabilities',{'capabilityDefinitionId':definition['id'],
                    'required':False,'defaultUnit':definition['unit'],'displayOrder':index,'freshnessThresholdSeconds':10})
        session=api('/device-onboarding',{'requestKey':'usb-v1-'+board+'-'+chip,'templateId':template['id'],
                    'spaceId':args.space,'name':'USB-'+board+' '+room,'serialNumber':'USB-'+board+'-'+chip,
                    'mode':'REAL','bindings':{}})
        device_id=session['deviceId']
        # Legacy WRITE projection used by the existing LED dashboard control, same authoritative command path.
        caps=api('/devices/'+device_id+'/capabilities')
        for cap,unit in ([('LED','BOOLEAN')] if board=='ESP1' else [('LED','BOOLEAN'),('SERVO','DEGREES')] if board=='ESP3' else []):
            if not any(c['capabilityType']==cap and c['direction']=='WRITE' for c in caps):
                api('/devices/'+device_id+'/capabilities',{'capabilityType':cap,'direction':'WRITE','unit':unit,
                    'metadata':{'physicalVerification':'NONE','usbBoard':board}})
        config['devices'].append({'board':board,'port':port,'chipId':chip,'deviceId':device_id,
                                 'physicalChecksConfirmed':True,'readings':mappings,'onboardingSessionId':session['id']})
    output=Path(args.output)
    output.write_text(json.dumps(config,indent=2),encoding='utf-8')
    print('Provisioned through NEXUS API; local config:',output)
    for device in config['devices']: print(device['board'],device['port'],device['deviceId'])

if __name__=='__main__': main()
