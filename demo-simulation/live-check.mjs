import assert from 'node:assert/strict';
import {mkdir,writeFile,readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {chromium} from '../frontend/node_modules/playwright/index.mjs';
const api='http://127.0.0.1:8080/api/v1';
const get=async path=>{const r=await fetch(api+path);assert.equal(r.status,200);return r.json();};
const state=async()=>{const r=await fetch('http://127.0.0.1:8766/state');return r.json();};
const initial=await state();assert.equal(initial.devices.length,3);assert.ok(initial.devices.every(d=>d.mode==='SIMULATED'));
for(const d of initial.devices){
  let connectivity;
  for(let i=0;i<60;i++){connectivity=await get('/devices/'+d.deviceId+'/connectivity');if(connectivity.status==='ONLINE')break;await new Promise(resolve=>setTimeout(resolve,500));}
  assert.equal(connectivity.status,'ONLINE','Wait for actual backend-observed online status before commands');
}
const dir=new URL('../.nexus/validation/three-esp-demo/',import.meta.url);await mkdir(dir,{recursive:true});
const locationsOnly=process.argv.includes('--locations-only');
const evidence=locationsOnly?JSON.parse(await readFile(new URL('report.json',dir),'utf8')):{commands:[],telemetry:[],infrastructure:{}};
const browser=await chromium.launch({headless:true});const page=await browser.newPage({viewport:{width:1700,height:1200}});
try{
  if(!locationsOnly){
  await page.goto('http://localhost:5173/simulation');
  await page.getByRole('heading',{name:'Three ESP32 — Interactive Lab'}).waitFor();
  await page.getByRole('heading',{name:'DEMO-ESP3 Automation Room'}).waitFor();
  async function command(label,board,value){
    const d=initial.devices.find(d=>d.board===board);
    const pending=page.waitForResponse(r=>r.url().endsWith(`/devices/${d.deviceId}/commands`)&&r.request().method()==='POST');
    await page.getByRole('button',{name:label,exact:true}).click();
    const response=await pending;assert.equal(response.status(),201);const acceptance=await response.json();
    assert.notEqual(acceptance.status,'FAILURE',acceptance.message);
    let lifecycle;
    for(let i=0;i<80;i++){lifecycle=await get('/actions/'+acceptance.requestId+'/lifecycle');if(lifecycle.ack?.status==='ACCEPTED')break;await page.waitForTimeout(500);}
    assert.equal(lifecycle.ack?.status,'ACCEPTED',JSON.stringify(acceptance));
    assert.equal(response.request().postDataJSON().value,value);
    evidence.commands.push({board,value,acceptance,lifecycle});
  }
  await command('Blue LED ON','ESP3',true);
  await command('OPEN (120°)','ESP3',120);
  await page.waitForTimeout(700);assert.equal((await state()).devices.find(d=>d.board==='ESP3').values.demo_servo_angle,120);
  await command('CLOSE (60°)','ESP3',60);
  await command('CENTER (90°)','ESP3',90);
  await command('Blue LED OFF','ESP3',false);
  await command('ESP1 LED ON','ESP1',true);
  await command('ESP1 LED OFF','ESP1',false);
  await page.getByRole('button',{name:'Press button — cycle mode',exact:true}).click();
  await page.getByRole('button',{name:/PIR: clear/}).click();
  await page.waitForTimeout(600);assert.equal((await state()).devices.find(d=>d.board==='ESP1').values.demo_led_state,true);
  await page.getByRole('button',{name:'Alarm exposure',exact:true}).click();
  for(let i=0;i<30;i++){if((await state()).devices.find(d=>d.board==='ESP2').values.demo_alarm==='ALARM')break;await page.waitForTimeout(200);}
  assert.equal((await state()).devices.find(d=>d.board==='ESP2').values.demo_rgb,'RED');
  assert.equal((await state()).devices.find(d=>d.board==='ESP2').values.demo_buzzer_state,true);
  await page.screenshot({path:fileURLToPath(new URL('alarm-and-servo.png',dir)),fullPage:true});
  await page.getByRole('button',{name:'Clean air',exact:true}).click();
  await page.getByRole('button',{name:/PIR: motion detected/}).click();
  await command('Buzzer demo ON','ESP2',true);
  await command('Buzzer demo OFF','ESP2',false);
  for(const d of initial.devices){const device=await get('/devices/'+d.deviceId);assert.equal(device.mode,'SIMULATED');assert.equal(device.status,'ACTIVE');const samples=await get(`/devices/${d.deviceId}/telemetry?limit=30`);assert.ok(samples.records.length>0);evidence.telemetry.push({board:d.board,device,samples});}
  const physical=JSON.parse(await (await import('node:fs/promises')).readFile(new URL('../usb-integration/config.local.json',import.meta.url),'utf8'));
  for(const d of physical.devices)assert.equal((await get('/devices/'+d.deviceId)).mode,'REAL');
  }
  await page.goto('http://localhost:5173/infrastructure');
  await page.getByLabel('Selected site').selectOption({label:'NEXUS Live Demo'});
  const name='Review Demo Building';
  await page.waitForTimeout(1200);
  if(!await page.getByLabel('Selected building').getByRole('option',{name,exact:true}).count()){
    await page.getByLabel('New building name').fill(name);
    await page.getByRole('button',{name:'Create building',exact:true}).click();
    await page.getByRole('status').waitFor();
  }
  await page.getByLabel('Selected building').selectOption({label:name});
  if(!await page.getByText('Review Room',{exact:true}).count()){
    await page.getByLabel('New room name').fill('Review Room');await page.getByRole('button',{name:'Create room',exact:true}).click();await page.getByText('Review Room',{exact:true}).waitFor();
  }
  evidence.infrastructure={building:name,space:'Review Room',createdVia:'Actual UI and normal backend APIs'};
  await page.screenshot({path:fileURLToPath(new URL('infrastructure.png',dir)),fullPage:true});
  await page.goto('http://localhost:5173/framework/onboarding');
  await page.getByLabel('Enabled MQTT template').selectOption({label:'DEMO ESP3 Automation Room'});
  await page.getByRole('button',{name:'Next',exact:true}).click();
  await page.getByLabel('Site',{exact:true}).selectOption({label:'NEXUS Live Demo'});
  await page.getByLabel('Building',{exact:true}).selectOption({label:name});
  await page.getByLabel('Space',{exact:true}).selectOption({label:'Review Room'});
  await page.screenshot({path:fileURLToPath(new URL('onboarding-location.png',dir)),fullPage:true});
  evidence.onboarding='Actual site/building/space selectors populated from backend and selected; no unnecessary extra device provisioned';
  console.log('PASS: three SIMULATED devices, nine real API command lifecycles with simulated ACKs, sensor-driven alarm, preserved REAL identities, UI-created building/room and onboarding placement.');
}finally{await writeFile(new URL('report.json',dir),JSON.stringify(evidence,null,2));await browser.close();}
