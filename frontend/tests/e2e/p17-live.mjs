/** Real browser acceptance for the P1.6/P1.7 MQTT onboarding journey.
 * Run against disposable Postgres/Mosquitto and the real backend/frontend.
 * Prerequisite location IDs may be seeded through the API; all Phase 1 objects
 * below are created through the actual browser UI.
 */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { EventEmitter } from 'node:events';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import readline from 'node:readline';
import { chromium } from 'playwright';

const dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(dirname, '../../..');
const suffix = process.env.E2E_SUFFIX;
const siteId = process.env.E2E_SITE_ID;
const buildingId = process.env.E2E_BUILDING_ID;
const spaceId = process.env.E2E_SPACE_ID;
if (!suffix || !siteId || !buildingId || !spaceId) throw new Error('E2E_SUFFIX and E2E_{SITE,BUILDING,SPACE}_ID are required');
const key = `soil_moisture_e2e_${suffix}`;
const display = `Soil Moisture E2E ${suffix}`;
const templateKey = `p17_probe_${suffix}`;
const templateName = `E2E-P17-Template-${suffix}`;
const serial = `E2E-P17-DEVICE-${suffix}`;
const ruleName = `E2E-P17-Rule-${suffix}`;
const output = path.join(root, '.nexus', 'e2e', suffix);
fs.mkdirSync(output, { recursive: true });
const apiBase = 'http://localhost:8080/api/v1';
const frontend = 'http://localhost:5173';
const evidence = { suffix, key, templateKey, serial, ruleName, steps: [], browserErrors: [] };
const log = (step, details = {}) => { evidence.steps.push({ step, at: new Date().toISOString(), ...details }); console.log(`PASS ${step}`, details); };
const save = () => fs.writeFileSync(path.join(output, 'evidence.json'), JSON.stringify(evidence, null, 2));
const screenshot = async (page, name) => page.screenshot({ path: path.join(output, `${name}.png`), fullPage: true });
const api = async endpoint => {
  const response = await fetch(`${apiBase}${endpoint}`);
  if (!response.ok) throw new Error(`GET ${endpoint}: ${response.status} ${await response.text()}`);
  return response.json();
};
const waitFor = async (label, predicate, timeoutMs = 20000) => {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    try { const value = await predicate(); if (value) return value; }
    catch (error) { last = error; }
    await new Promise(resolve => setTimeout(resolve, 250));
  }
  throw new Error(`Timed out waiting for ${label}: ${last ?? 'condition stayed false'}`);
};
const responseFor = (page, method, suffix) => page.waitForResponse(response =>
  response.request().method() === method && response.url().endsWith(suffix), { timeout: 20000 });

function mqttDevice(deviceId) {
  const py = path.join(root, 'simulator', '.venv', 'Scripts', 'python.exe');
  const script = path.join(root, 'tests', 'e2e', 'p17_mqtt_device.py');
  const child = spawn(py, [script, '--device-id', deviceId, '--capability-key', key,
    '--client-id', `E2E-P17-${suffix}`, '--broker-port', '11883'], { stdio: ['pipe', 'pipe', 'pipe'] });
  const events = [];
  const emitter = new EventEmitter();
  readline.createInterface({ input: child.stdout }).on('line', line => {
    try { const parsed = JSON.parse(line); events.push(parsed); console.log('MQTT', parsed); emitter.emit(parsed.event, parsed); }
    catch { console.log('MQTT stdout', line); }
  });
  child.stderr.on('data', chunk => console.error('MQTT stderr', chunk.toString()));
  child.on('exit', code => emitter.emit('exit', { code }));
  return {
    events,
    send: text => { child.stdin.write(`${text}\n`); },
    wait: (event, timeoutMs = 20000) => {
      const existing = events.find(item => item.event === event);
      if (existing) return Promise.resolve(existing);
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => { emitter.off(event, onEvent); reject(new Error(`MQTT ${event} timeout; events=${JSON.stringify(events)}`)); }, timeoutMs);
        const onEvent = value => { clearTimeout(timer); resolve(value); };
        emitter.once(event, onEvent);
      });
    },
    stop: async () => {
      if (child.exitCode !== null) return;
      child.stdin.write('quit\n');
      await new Promise(resolve => { child.once('exit', resolve); setTimeout(resolve, 3000); });
      if (child.exitCode === null) child.kill();
    },
  };
}

let browser;
let page;
let device;
try {
  browser = await chromium.launch({ channel: 'msedge', headless: true });
  page = await browser.newPage({ viewport: { width: 1440, height: 950 } });
  page.on('pageerror', error => evidence.browserErrors.push(error.message));
  await page.goto(`${frontend}/framework/capabilities`);
  await page.getByRole('heading', { name: 'Capability Definitions' }).waitFor();
  await page.getByLabel('Key').fill(key);
  await page.getByLabel('Display name').fill(display);
  await page.getByLabel('Unit', { exact: true }).fill('PERCENT');
  await page.getByLabel('Allowed units (comma separated)').fill('PERCENT');
  await page.getByLabel('Minimum').fill('0');
  await page.getByLabel('Maximum').fill('100');
  const capabilityResponse = responseFor(page, 'POST', '/capability-definitions');
  await page.getByRole('button', { name: 'Save definition' }).click();
  const capabilityHttp = await capabilityResponse;
  assert.equal(capabilityHttp.status(), 201, await capabilityHttp.text());
  const capability = await capabilityHttp.json();
  assert.equal(capability.key, key);
  await page.getByRole('button', { name: new RegExp(display) }).waitFor();
  await screenshot(page, '01-capability');
  log('capability_created_in_browser', { capabilityId: capability.id });

  await page.getByRole('link', { name: 'Templates', exact: true }).click();
  await page.getByRole('heading', { name: 'Device Templates' }).waitFor();
  await page.getByLabel('Key').fill(templateKey);
  await page.getByLabel('Display name').fill(templateName);
  const templateResponse = responseFor(page, 'POST', '/device-templates');
  await page.getByRole('button', { name: 'Create template' }).click();
  const templateHttp = await templateResponse;
  assert.equal(templateHttp.status(), 201, await templateHttp.text());
  const template = await templateHttp.json();
  assert.equal(template.key, templateKey);
  const addSelect = page.getByLabel('Add capability');
  await addSelect.waitFor();
  await addSelect.selectOption(capability.id);
  let associationResponse = responseFor(page, 'POST', `/device-templates/${template.id}/capabilities`);
  await page.getByRole('button', { name: 'Add capability' }).click();
  assert.equal((await associationResponse).status(), 201);
  await page.getByText(key, { exact: false }).first().waitFor();
  const definitions = await api('/capability-definitions');
  const led = definitions.find(item => item.key === 'led');
  assert.ok(led, 'seeded LED definition');
  await addSelect.selectOption(led.id);
  associationResponse = responseFor(page, 'POST', `/device-templates/${template.id}/capabilities`);
  await page.getByRole('button', { name: 'Add capability' }).click();
  assert.equal((await associationResponse).status(), 201);
  const members = await api(`/device-templates/${template.id}/capabilities`);
  assert.deepEqual(new Set(members.map(member => member.capabilityKey)), new Set([key, 'led']));
  await screenshot(page, '02-template');
  log('template_and_two_capabilities_created_in_browser', { templateId: template.id, bindingCount: members.length });

  await page.getByRole('link', { name: 'Onboarding', exact: true }).click();
  await page.getByRole('heading', { name: 'Device Onboarding' }).waitFor();
  await page.getByLabel('Enabled MQTT template').selectOption(template.id);
  await page.getByRole('button', { name: 'Next' }).click();
  await page.getByLabel(/^Site /).selectOption(siteId);
  await page.getByLabel(/^Building /).selectOption(buildingId);
  await page.getByLabel(/^Space /).selectOption(spaceId);
  await page.getByLabel('Name', { exact: true }).fill(`E2E-P17-Probe-${suffix}`);
  await page.getByLabel('Serial number').fill(serial);
  await page.getByRole('button', { name: 'Next' }).click();
  await page.getByRole('button', { name: 'Review' }).click();
  const provisionResponse = responseFor(page, 'POST', '/device-onboarding');
  await page.getByRole('button', { name: 'Confirm provisioning' }).click();
  const provisionHttp = await provisionResponse;
  assert.equal(provisionHttp.status(), 201, await provisionHttp.text());
  const session = await provisionHttp.json();
  assert.equal(session.state, 'WAITING_FOR_STATUS');
  await page.waitForURL(new RegExp(`/framework/onboarding/${session.id}$`));
  await page.getByRole('heading', { name: 'WAITING_FOR_STATUS' }).waitFor();
  evidence.sessionId = session.id;
  evidence.deviceId = session.deviceId;
  await screenshot(page, '03-waiting-status');
  log('provisioned_in_browser', { sessionId: session.id, deviceId: session.deviceId });

  const contract = await api(`/devices/${session.deviceId}/contract`);
  assert.equal(contract.deviceId, session.deviceId);
  for (const topic of Object.values(contract.topics)) await page.getByText(topic, { exact: true }).waitFor();
  assert.ok(contract.capabilities.some(item => item.key === key && item.direction === 'READ'));
  assert.ok(contract.capabilities.some(item => item.key === 'led' && item.direction === 'WRITE'));
  await screenshot(page, '04-contract');
  log('backend_contract_displayed', { topics: contract.topics, contractVersion: contract.contractVersion });

  device = mqttDevice(session.deviceId);
  await device.wait('ready');
  device.send('status');
  await device.wait('status_published');
  await page.getByRole('heading', { name: 'WAITING_FOR_TELEMETRY' }).waitFor({ timeout: 20000 });
  assert.equal((await api(`/device-onboarding/${session.id}`)).state, 'WAITING_FOR_TELEMETRY');
  await screenshot(page, '05-waiting-telemetry');
  log('mqtt_status_advanced_onboarding');

  device.send('telemetry:50');
  await device.wait('telemetry_published');
  await page.getByRole('heading', { name: 'OPERATIONAL' }).waitFor({ timeout: 20000 });
  const operational = await api(`/device-onboarding/${session.id}`);
  assert.equal(operational.state, 'OPERATIONAL');
  const realDevice = await api(`/devices/${session.deviceId}`);
  assert.equal(realDevice.status, 'ACTIVE');
  await page.getByText('50 PERCENT', { exact: true }).waitFor({ timeout: 20000 });
  const twin = await api(`/devices/${session.deviceId}/twin`);
  assert.equal(twin.reportedState[key].resolutionSource, 'GENERIC_BINDING');
  assert.equal(Number(twin.reportedState[key].typedValue), 50);
  await screenshot(page, '06-operational-typed-twin');
  log('generic_telemetry_activated_device_and_twin', { typedValue: twin.reportedState[key].typedValue,
    definitionId: twin.reportedState[key].definitionId, bindingId: twin.reportedState[key].bindingId });

  await page.getByRole('link', { name: 'Create generic Rule' }).click();
  await page.getByRole('heading', { name: 'Generic Rule Builder' }).waitFor();
  await page.getByLabel('Rule name').fill(ruleName);
  await page.getByLabel('Readable capability').selectOption(key);
  await page.getByLabel('Operator').selectOption('GT');
  await page.getByLabel('Comparison value').fill('70');
  await page.getByLabel('Writable capability').selectOption('led');
  await page.getByLabel('Command').selectOption('SET_STATE');
  await page.getByLabel('Action value').selectOption('true');
  assert.equal(await page.getByLabel('Operator').inputValue(), 'GT', 'numeric operator must remain GT before submit');
  const ruleResponse = responseFor(page, 'POST', '/rules');
  await page.getByRole('button', { name: 'Create generic Rule' }).click();
  const ruleHttp = await ruleResponse;
  assert.equal(ruleHttp.status(), 201, await ruleHttp.text());
  const rule = await ruleHttp.json();
  assert.equal(rule.conditions.find(condition => condition.capabilityKey === key)?.operator, 'GT',
    'persisted browser-created Rule must keep GT');
  evidence.ruleId = rule.ruleId;
  await page.getByRole('status').getByText(/created by backend/).waitFor();
  await screenshot(page, '07-rule-created');
  log('generic_rule_created_in_browser', { ruleId: rule.ruleId });

  device.send('telemetry:80');
  await waitFor('80 typed Twin', async () => {
    const state = await api(`/devices/${session.deviceId}/twin`);
    return Number(state.reportedState[key]?.typedValue) === 80 && state;
  });
  const command = await device.wait('command', 25000);
  assert.equal(command.capability, contract.capabilities.find(item => item.key === 'led').externalCommandIdentifier);
  assert.equal(command.ledState, true);
  const ack = await device.wait('ack_published', 20000);
  assert.equal(ack.commandId, command.commandId);
  evidence.commandId = command.commandId;
  log('mqtt_command_received_and_ack_published', { commandId: command.commandId });

  const matches = await waitFor('persisted generic Rule Match', async () => {
    const history = await api('/rule-matches/history?limit=50');
    return history.find?.(item => item.ruleId === rule.ruleId) && history;
  });
  const actions = await waitFor('acknowledged ActionHistory', async () => {
    const history = await api('/actions/history?limit=50');
    const records = Array.isArray(history) ? history : history.content ?? history.actions ?? [];
    return records.find(item => item.commandId === command.commandId || item.requestId === command.commandId) && records;
  });
  evidence.ruleMatches = matches;
  evidence.actions = actions;
  await page.goto(`${frontend}/rules`);
  await page.getByText(ruleName, { exact: true }).first().waitFor({ timeout: 20000 });
  await screenshot(page, '08-rule-result');
  await page.goto(`${frontend}/actions`);
  await page.getByText('ACCEPTED', { exact: false }).first().waitFor({ timeout: 20000 });
  await screenshot(page, '09-action-ack');
  log('browser_final_rule_and_action_evidence');
  save();
  console.log(`E2E PASSED evidence=${output}`);
} catch (error) {
  console.error(`E2E FAILED at ${evidence.steps.at(-1)?.step ?? 'initial UI'}:`, error);
  if (page) await screenshot(page, 'failure').catch(() => {});
  evidence.failure = { message: String(error), stack: error?.stack };
  save();
  process.exitCode = 1;
} finally {
  if (device) await device.stop();
  if (browser) await browser.close();
}
