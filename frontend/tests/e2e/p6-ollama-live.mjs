// Real browser/backend metadata smoke. Inference is separately opt-in and resource-gated.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { chromium } from 'playwright';

const api = 'http://localhost:8080/api/v1';
const inference = process.argv.includes('--inference');
const deterministic = process.argv.includes('--deterministic');
assert.ok(!(inference && deterministic), 'Choose one mode');
if (inference && process.platform === 'win32') {
  const freeKiB = Number(execFileSync('powershell', ['-NoProfile', '-Command',
    '(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory'], { encoding: 'utf8' }).trim());
  assert.ok(freeKiB >= 4 * 1024 * 1024, 'HOLD: this supervised smoke requires 4 GiB free RAM before inference. No model request sent.');
}
const directory = process.env.P6_UI_EVIDENCE ?? '../.nexus/validation/p6-ollama-live';
await mkdir(directory, { recursive: true });
const beforeResponse = await fetch(`${api}/assistant/model-status`);
assert.equal(beforeResponse.status, 200);
const before = await beforeResponse.json();
assert.equal(before.mode, deterministic ? 'DETERMINISTIC_ONLY' : 'OLLAMA_LOCAL');
assert.equal(before.gateway.identity.providerId, deterministic ? 'none' : 'ollama-local');
assert.equal(before.gateway.evidenceConsent, !deterministic);
if (!deterministic) {
  assert.equal(before.ollama.inspection.reachable, true);
  assert.equal(before.ollama.inspection.installed, true);
  assert.equal(before.ollama.inspection.remotelyHosted, false);
}
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1080 } });
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
try {
  await page.goto('http://localhost:5173/assistant');
  await page.getByRole('heading', { name: 'Model connection', exact: true }).waitFor();
  await page.getByText(deterministic ? 'Mode: Deterministic-only (no model)' : 'Mode: OLLAMA_LOCAL', { exact: true }).waitFor();
  if (!deterministic) await page.getByText(/Provider: ollama-local/).waitFor();
  let answer = null;
  if (inference || deterministic) {
    const devices = await (await fetch(`${api}/devices`)).json();
    assert.ok(devices.length, 'A real stored device is required');
    const device = devices.find((d) => d.name === 'Verify Device') ?? devices[0];
    await page.getByLabel('Ask NEXUS', { exact: true }).fill(`What is the status of ${device.name}?`);
    const responsePromise = page.waitForResponse((r) => r.url().endsWith('/assistant/ask') && r.request().method() === 'POST', { timeout: 75000 });
    await page.getByRole('button', { name: 'Ask assistant', exact: true }).click();
    const response = await responsePromise;
    assert.equal(response.status(), 200);
    answer = await response.json();
    await page.getByTestId('assistant-answer').waitFor();
    assert.equal(answer.status, 'ANSWERED');
    assert.ok(answer.sources.length > 0);
    await page.getByText(answer.orderedBy === 'MODEL_SELECTED'
      ? 'Validated model-assisted evidence ordering. Factual statements are platform-written.'
      : 'Deterministic answer ordering (model not used or fallback). This response alone does not prove a provider call.', { exact: true }).waitFor();
  }
  const after = await (await fetch(`${api}/assistant/model-status`)).json();
  if (inference) assert.ok(after.gateway.providerAttempts > before.gateway.providerAttempts, 'An ANSWERED response is not proof of a provider invocation');
  else assert.equal(after.gateway.providerAttempts, before.gateway.providerAttempts, 'Opening status must not initiate inference');
  assert.deepEqual(errors, []);
  await page.screenshot({ path: `${directory}/assistant-model-status.png`, fullPage: true });
  await writeFile(`${directory}/report.json`, JSON.stringify({
    scope: inference ? 'live provider invocation' : deterministic ? 'live deterministic answer; no inference' : 'metadata UI only; inference NOT VERIFIED',
    before, after, answer: answer ? { status: answer.status, routedBy: answer.routedBy,
      orderedBy: answer.orderedBy, sourceCount: answer.sources.length } : null,
    browserErrors: errors,
  }, null, 2));
  console.log(inference ? 'PASS: real browser/provider attempt; inspect recorded outcome separately for quality.' : deterministic ? 'PASS: real deterministic backend answer and zero provider calls.' : 'PASS: real model-status UI; no inference requested or claimed.');
} finally { await browser.close(); }
