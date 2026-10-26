import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { deviceApi, type CapabilityType } from '../api/deviceApi';
import { Button } from '../components/foundation/Button';

type Board={board:string;deviceId:string;name:string;space:string;mode:string;connected:boolean;values:Record<string,string|number|boolean>};
type Snapshot={source:string;timestamp:number;devices:Board[];acks:{board:string;commandId:string;capability:string;value:unknown;timestamp:number}[]};
const origin='http://127.0.0.1:8766';
function Lamp({on,color='deepskyblue'}:{on:boolean;color?:string}) {return <div aria-label={on?'LED illuminated in simulation':'LED off in simulation'} className="w-14 h-14 rounded-full border-4 border-slate-600 m-3 transition-all" style={{background:on?color:'#293342',boxShadow:on?`0 0 35px ${color}`:'none'}}/>;}

export default function SimulationPage(){
  const [snapshot,setSnapshot]=useState<Snapshot>(),[error,setError]=useState(''),[notice,setNotice]=useState('');
  const [busy,setBusy]=useState(false),[live,setLive]=useState(false);
  useEffect(()=>{let active=true;const refresh=async()=>{try{const r=await fetch(origin+'/state');if(!r.ok)throw new Error('Simulator unavailable');const data=await r.json();if(active){setSnapshot(data);setLive(true);}}catch{if(active)setLive(false);}};void refresh();const timer=setInterval(()=>void refresh(),400);return()=>{active=false;clearInterval(timer);};},[]);
  async function inputs(board:string,values:Record<string,number|boolean>){try{const r=await fetch(origin+'/inputs',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({board,values})});if(!r.ok)throw new Error('Sensor input refused');setError('');}catch(e){setError(String(e));}}
  async function command(d:Board,capability:CapabilityType,value:boolean|number){
    setBusy(true);setError('');setNotice('');
    try{const r=await deviceApi.sendDeviceCommand(d.deviceId,{capability,command:'SET_STATE',value});setNotice(`${d.board}: ${r.message}. Command ${r.requestId}. A simulated ACK is listed below only after the model receives it.`);}
    catch(e){setError(e instanceof Error?e.message:String(e));}finally{setBusy(false);}
  }
  const disabled=busy||!live;
  return <main className="h-full overflow-auto p-5 lg:p-8 text-foundation-fg" style={{backgroundColor:'var(--foundation-bg)'}}><div className="max-w-7xl mx-auto space-y-5">
    <div className="flex flex-wrap justify-between gap-3"><div><p className="text-xs tracking-widest text-amber-500">SIMULATED • NO PHYSICAL ACTUATION</p><h1 className="text-3xl font-semibold">Three ESP32 — Interactive Lab</h1></div><nav className="flex gap-4"><Link to="/infrastructure">Create infrastructure</Link><Link to="/framework/onboarding">Onboard device</Link><Link to="/explorer">Explorer</Link></nav></div>
    <p className="text-sm text-foundation-muted-fg">Behavioural models, not an electrical circuit emulator. Sensor inputs drive simulated telemetry through MQTT into NEXUS. Actuator buttons use the normal NEXUS command lifecycle. Physical USB devices remain separate and unchanged.</p>
    {!live&&<div role="alert" className="rounded-xl border border-amber-500 p-4">Simulator disconnected. Displayed values are stale, not live. Start: <code>python demo-simulation/server.py --provision</code></div>}
    {notice&&<p role="status" className="text-sm break-all">{notice}</p>}{error&&<p role="alert" className="text-red-500">{error}</p>}
    <div className="grid xl:grid-cols-3 gap-5">{snapshot?.devices.map(d=>{const v=d.values;return <section key={d.deviceId} className="rounded-3xl border border-foundation-border p-5 space-y-5" style={{background:'var(--glass-bg)'}}>
      <header><span className="text-xs text-amber-500">SIMULATED · {live&&d.connected?'MQTT connected':'disconnected'}</span><h2 className="text-xl font-semibold">{d.name}</h2><p className="text-xs">{d.space}</p></header>
      <div className="rounded-xl bg-slate-900 p-4 text-slate-100"><div className="flex justify-between"><strong>ESP32</strong><span className="text-xs text-emerald-400">MODEL v1</span></div><div className="h-12 my-3 border-2 border-slate-500 rounded flex items-center justify-center font-mono">{d.board} • USB-free software model</div><p className="text-xs break-all">{d.deviceId}</p></div>
      {d.board==='ESP1'&&<>
        <div className="grid grid-cols-3 gap-2 text-center rounded-xl border border-foundation-border p-3"><div>{Number(v.temperature).toFixed(2)} °C<small className="block">BME280 temperature</small></div><div>{Number(v.humidity).toFixed(1)} %<small className="block">Humidity</small></div><div>{Number(v.pressure).toFixed(1)} hPa<small className="block">Pressure</small></div></div>
        {([['temperature','Room temperature',5,50,25],['humidity','Humidity target',10,95,50],['pressure','Pressure target',900,1100,1013]] as const).map(([key,label,min,max,initial])=><label key={key} className="block text-sm">{label}<input aria-label={label} className="w-full" type="range" min={min} max={max} defaultValue={initial} disabled={!live} onChange={e=>void inputs(d.board,{[key]:Number(e.target.value)})}/></label>)}
        <Button disabled={!live} onClick={()=>void inputs(d.board,{motion:!v.motion})}>PIR: {v.motion?'motion detected':'clear'} — toggle</Button>
        <Button disabled={!live} onClick={async()=>{await inputs(d.board,{button:true});setTimeout(()=>void inputs(d.board,{button:false}),150);}}>Press button — cycle mode</Button>
        <div className="flex items-center"><Lamp on={!!v.demo_led_state} color="#51e797"/><span>LED · {String(v.demo_led_mode)}<small className="block">AUTO: 3-second PIR hold</small></span></div>
        <div className="flex gap-2"><Button disabled={disabled} onClick={()=>void command(d,'LED',true)}>ESP1 LED ON</Button><Button disabled={disabled} onClick={()=>void command(d,'LED',false)}>ESP1 LED OFF</Button></div>
      </>}
      {d.board==='ESP2'&&<>
        <div className="text-3xl font-mono">{String(v.demo_gas_adc)} <small className="text-sm">ADC counts</small></div><p className="text-xs">MQ-2 model: baseline 200; warning +300, alarm +600, hysteresis 100. Uncalibrated demonstration, never PPM or a gas-safety limit. Warm-up/baseline acquisition is omitted for this ready-to-demo model.</p>
        <label className="block">MQ-2 exposure target<input aria-label="MQ-2 exposure target" type="range" className="w-full" min="0" max="1800" defaultValue="200" disabled={!live} onChange={e=>void inputs(d.board,{gas:Number(e.target.value)})}/></label>
        <div className="flex flex-wrap gap-2">{[['Clean air',200],['Warning exposure',650],['Alarm exposure',1100]].map(([label,value])=><Button key={String(label)} disabled={!live} onClick={()=>void inputs(d.board,{gas:Number(value)})}>{label}</Button>)}</div>
        <div className="flex items-center"><Lamp on color={v.demo_rgb==='RED'?'#ff4040':v.demo_rgb==='YELLOW'?'#ffcf40':'#45e287'}/><div>RGB: {String(v.demo_rgb)}<strong className="block">{String(v.demo_alarm)}</strong></div></div>
        <div className={`rounded-xl p-4 border ${v.demo_buzzer_state?'border-red-500 animate-pulse':'border-foundation-border'}`}>Buzzer: {v.demo_buzzer_state?'ON — visual indication (no browser audio)':'OFF'}</div>
        <div className="flex gap-2"><Button disabled={disabled} onClick={()=>void command(d,'BUZZER',true)}>Buzzer demo ON</Button><Button disabled={disabled} onClick={()=>void command(d,'BUZZER',false)}>Buzzer demo OFF</Button></div><p className="text-xs">Manual ON expires in 10 seconds. OFF cannot silence the simulated local ALARM.</p>
      </>}
      {d.board==='ESP3'&&<>
        <div className="flex items-center"><Lamp on={!!v.demo_led_state}/><span>Blue LED: {v.demo_led_state?'ON':'OFF'}</span></div>
        <div className="relative mx-auto w-44 h-44 rounded-full border-4 border-slate-500 bg-slate-800"><div className="absolute left-1/2 top-1/2 w-16 h-3 bg-white rounded origin-left" style={{transform:`rotate(${Number(v.demo_servo_angle)-180}deg)`,transition:'transform 0.35s linear'}}/><div className="absolute left-1/2 top-1/2 w-4 h-4 -translate-x-1/2 -translate-y-1/2 bg-slate-400 rounded-full"/></div>
        <p className="text-center">SG90 model: {Number(v.demo_servo_angle).toFixed(1)}° → target {String(v.demo_servo_target)}°<small className="block">Modelled travel 60°/second, not measured physical motion</small></p>
        <div className="flex gap-2"><Button disabled={disabled} onClick={()=>void command(d,'LED',true)}>Blue LED ON</Button><Button disabled={disabled} onClick={()=>void command(d,'LED',false)}>Blue LED OFF</Button></div>
        <div className="flex flex-wrap gap-2">{([['OPEN',120],['CLOSE',60],['CENTER',90]] as const).map(([label,angle])=><Button key={label} disabled={disabled} onClick={()=>void command(d,'SERVO',angle)}>{label} ({angle}°)</Button>)}</div>
      </>}
    </section>;})}</div>
    <section className="rounded-2xl border border-foundation-border p-5"><h2 className="font-semibold">Model-received commands (simulated ACKs)</h2><p className="text-xs">Inspect Actions in NEXUS for backend-persisted lifecycle evidence. This list is the simulator's own receipt log.</p>{snapshot?.acks.slice(-8).reverse().map(a=><p className="text-xs font-mono break-all my-2" key={a.commandId}>{a.board} · {a.capability}={String(a.value)} · {a.commandId}</p>)}</section>
  </div></main>;
}
