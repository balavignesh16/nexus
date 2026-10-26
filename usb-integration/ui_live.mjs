// Explicit physical LED bench test through the ACTUAL existing dashboard; no request interception.
import assert from 'node:assert/strict';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { chromium } from '../frontend/node_modules/playwright/index.mjs';
const api='http://localhost:8080/api/v1';
const config=JSON.parse(await readFile(new URL('./config.local.json',import.meta.url),'utf8'));
const d=config.devices.find(d=>d.board==='ESP3');
async function get(path) {const r=await fetch(api+path);assert.equal(r.status,200);return r.json();}
const device=await get('/devices/'+d.deviceId);
assert.equal(device.mode,'REAL');assert.equal(device.status,'ACTIVE');
const space=await get('/spaces/'+device.spaceId);
const building=await get('/buildings/'+space.buildingId);
const site=await get('/sites/'+building.siteId);
const directory=new URL('../.nexus/validation/usb-ui-live/',import.meta.url);
await mkdir(directory,{recursive:true});
const browser=await chromium.launch({headless:true});
const page=await browser.newPage({viewport:{width:1500,height:1300}});
const commands=[];
try {
  await page.goto('http://localhost:5173/explorer');
  for(const node of [site,building,space]) {
    const row=page.getByRole('button',{name:node.name,exact:true}).first();
    await row.waitFor();
    if(await row.getAttribute('aria-expanded')==='false') await row.click();
  }
  await page.getByRole('button',{name:device.name,exact:true}).first().click();
  await page.getByRole('button',{name:'Turn LED ON',exact:true}).waitFor();
  const steps=process.argv.includes('--servo')
    ? [{value:120,label:'Door OPEN (120°)'},{value:60,label:'Door CLOSE (60°)'},{value:90,label:'Door CENTER (90°)'}]
    : [{value:true,label:'Turn LED ON'},{value:false,label:'Turn LED OFF'}];
  for(const step of steps) {
    const pending=page.waitForResponse(r=>r.url().endsWith(`/devices/${d.deviceId}/commands`)&&r.request().method()==='POST');
    await page.getByRole('button',{name:step.label,exact:true}).click();
    const response=await pending;assert.equal(response.status(),201);
    const request=response.request().postDataJSON();assert.equal(request.value,step.value);
    const accepted=await response.json();
    assert.ok(accepted.requestId);
    let lifecycle;
    for(let attempt=0;attempt<30;attempt++) {
      lifecycle=await get('/actions/'+accepted.requestId+'/lifecycle');
      if(lifecycle.ack?.status==='ACCEPTED') break;
      await page.waitForTimeout(500);
    }
    assert.equal(lifecycle.ack.status,'ACCEPTED','No real device acceptance recorded');
    assert.ok(lifecycle.facts.some(f=>f.fact.includes('ACK')));
    const readings=await get(`/devices/${d.deviceId}/telemetry?limit=25`);
    commands.push({request,accepted,lifecycle,recentReadings:readings});
    await page.screenshot({path:new URL(`command-${step.value}.png`,directory).pathname.replace(/^\/([A-Za-z]:)/,'$1'),fullPage:true});
  }
  await writeFile(new URL(process.argv.includes('--servo')?'servo-report.json':'report.json',directory),JSON.stringify({device,commands,physicalVerification:'GPIO/software reports only; human output observation still required'},null,2));
  console.log('PASS: actual dashboard commands, correlated device ACKs and persisted lifecycle; physical output not independently measured.');
} finally {await browser.close();}
