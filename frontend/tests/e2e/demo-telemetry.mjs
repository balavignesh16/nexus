import assert from 'node:assert/strict';
import {readFile,mkdir,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {chromium} from 'playwright';
const config=JSON.parse(await readFile(new URL('../../../.nexus/demo-simulation.json',import.meta.url),'utf8'));
const dir=new URL('../../../.nexus/validation/demo-telemetry/',import.meta.url);await mkdir(dir,{recursive:true});
const browser=await chromium.launch({headless:true});const report=[];
try{
  const page=await browser.newPage({viewport:{width:1500,height:1100}});
  await page.goto('http://localhost:5173/telemetry');
  for(const d of config){
    await page.getByLabel('Telemetry device').selectOption(d.deviceId);
    const metric=d.board==='ESP1'?'temperature':d.board==='ESP2'?'demo_gas_adc':'demo_servo_angle';
    await page.getByLabel('Telemetry metric').selectOption(metric);
    await page.getByText(new RegExp(`exact capability: ${metric}(?: ·|$)`)).waitFor();
    await page.locator('.recharts-area').waitFor();
    assert.ok(await page.getByText('Simulated',{exact:true}).count());
    const response=await fetch(`http://127.0.0.1:8080/api/v1/devices/${d.deviceId}/telemetry?limit=50`);
    const history=await response.json();assert.ok(history.records.some(r=>r.capabilityKey===metric));
    report.push({board:d.board,deviceId:d.deviceId,metric,persistedSamples:history.records.length});
    await page.screenshot({path:fileURLToPath(new URL(d.board+'.png',dir)),fullPage:true});
    if(d.board==='ESP1'){
      await page.getByLabel('Telemetry metric').selectOption('motion');
      await page.getByText('State/text telemetry — see readings below.',{exact:true}).waitFor();
      await page.getByRole('cell',{name:/^(true|false)$/}).first().waitFor();
    }
  }
  const before=await page.locator('tbody tr').first().locator('td').first().innerText();
  await page.waitForFunction(previous=>document.querySelector('tbody tr td')?.textContent!==previous,before,{timeout:20000});
  report.push({automaticStoredReadingRefresh:true});
  await writeFile(new URL('report.json',dir),JSON.stringify(report,null,2));
  console.log('PASS: all three demo ESPs selectable, actual stored numeric charts, simulated provenance, Boolean states and automatic refresh.');
}finally{await browser.close();}
