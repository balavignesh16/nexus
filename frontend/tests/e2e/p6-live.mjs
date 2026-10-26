// Real browser + running backend. No route interception, fake API responses or seeded answers.
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { chromium } from 'playwright';

const base = process.env.P6_UI_URL ?? 'http://localhost:5173';
const api = 'http://localhost:8080/api/v1';
const evidence = process.env.P6_UI_EVIDENCE ?? '../.nexus/validation/p6-ui-live';
await mkdir(evidence, { recursive: true });
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1080 } });
const checks = [];
const faults = [];
const network = [];
const trendsOnly = process.argv.includes('--trends-only');
page.on('pageerror', (error) => faults.push(error.message));
page.on('response', (response) => { if (response.url().startsWith(api)) network.push({ url: response.url(), status: response.status() }); });
// Read-only inputs from the actual running database, not a fixture or demo repository.
const response = await fetch(`${api}/devices`);
assert.equal(response.status, 200);
const devices = await response.json();
assert.ok(devices.length, 'A stored device is needed for this live smoke');
const device = devices[0];
const matches = await (await fetch(`${api}/rule-matches/history?limit=2`)).json();
let trendDevice = device;
let storedReading;
for (const candidate of devices.slice(0, 8)) {
  const readingsResponse = await fetch(`${api}/devices/${candidate.id}/telemetry?limit=1`);
  if (!readingsResponse.ok) continue;
  const readings = await readingsResponse.json();
  if (readings.records?.length) { trendDevice = candidate; storedReading = readings.records[0]; break; }
}
async function requestByClick(path, button, expectedStatus = 200) {
  for (let attempt = 0; attempt < 3; attempt++) {
    const [result] = await Promise.all([
      page.waitForResponse((r) => r.url().includes(path) && r.request().method() !== 'OPTIONS'),
      button.click(),
    ]);
    if (result.status() === 429 && attempt < 2) {
      await page.waitForTimeout(15000); // Honor the real production limiter; never replace or bypass it.
      continue;
    }
    assert.equal(result.status(), expectedStatus, `${path}: unexpected HTTP status`);
    return result.json();
  }
}
try {
  if (!trendsOnly) {
  await page.goto(base);
  await page.getByRole('link', { name: 'NEXUS Assistant', exact: true }).first().click();
  await page.getByRole('heading', { name: 'NEXUS Assistant', exact: true }).waitFor();
  const question = `What is the status of ${device.name}?`;
  await page.getByLabel('Ask NEXUS', { exact: true }).fill(question);
  const answer = await requestByClick('/assistant/ask', page.getByRole('button', { name: 'Ask assistant', exact: true }));
  assert.equal(answer.status, 'ANSWERED');
  assert.equal(answer.routedBy, 'DETERMINISTIC');
  assert.ok(answer.sources.length > 0);
  await page.getByTestId('assistant-answer').waitFor();
  for (const statement of answer.statements) assert.ok((await page.getByTestId('assistant-answer').innerText()).includes(statement.text));
  await page.getByText(`Supporting evidence (${answer.sources.length})`, { exact: true }).click();
  await page.screenshot({ path: `${evidence}/assistant.png`, fullPage: true });
  checks.push({ name: 'click navigation, submit question, render real cited deterministic response', status: answer.status, question, sourceCount: answer.sources.length, evaluatedAt: answer.evaluatedAt });

  await page.getByLabel('Ask NEXUS', { exact: true }).fill('Is it online?');
  const followup = await requestByClick('/assistant/ask', page.getByRole('button', { name: 'Ask assistant', exact: true }));
  assert.equal(followup.conversationId, answer.conversationId);
  assert.equal(await page.getByTestId('assistant-answer').count(), 2);
  checks.push({ name: 'follow-up uses actual backend conversation and keeps history', status: followup.status });

  await page.getByRole('navigation', { name: 'Phase 6', exact: true }).getByRole('link', { name: 'Energy Intelligence', exact: true }).click();
  const energy = await requestByClick('/intelligence/energy', page.getByRole('button', { name: 'Load energy analysis', exact: true }));
  await page.getByRole('heading', { name: 'Consumption and provenance' }).waitFor();
  if (!energy.available) assert.ok((await page.locator('main').innerText()).includes(energy.unavailableReason));
  await page.screenshot({ path: `${evidence}/energy.png`, fullPage: true });
  checks.push({ name: 'real energy report renders available or unavailable honestly', available: energy.available, reason: energy.unavailableReason });
  }

  if (trendsOnly) await page.goto(`${base}/trends`);
  else await page.getByRole('navigation', { name: 'Phase 6', exact: true }).getByRole('link', { name: 'Trends', exact: true }).click();
  // The outgoing Energy form has the same device label. Wait for the destination before selecting.
  await page.getByRole('heading', { name: 'Trends', exact: true }).waitFor();
  await page.getByLabel('Stored device', { exact: true }).selectOption(trendDevice.id);
  if (storedReading?.capabilityKey) await page.getByLabel('Capability key', { exact: true }).fill(storedReading.capabilityKey);
  const [trend, actualReadings] = await Promise.all([
    requestByClick('/intelligence/trends/', page.getByRole('button', { name: 'Load trend and readings', exact: true })),
    page.waitForResponse((r) => r.url().includes(`/devices/${trendDevice.id}/telemetry?`) && r.request().method() === 'GET', { timeout: 60000 }),
  ]);
  assert.ok(actualReadings.ok());
  const storedBody = await actualReadings.json();
  await page.getByRole('heading', { name: 'Stored readings', exact: false }).waitFor();
  assert.ok((await page.locator('main').innerText()).includes(trend.trend.direction));
  if (storedReading) {
    await page.getByText(storedReading.id, { exact: true }).waitFor();
    assert.ok((await page.locator('main').innerText()).includes(storedReading.timestamp));
  }
  await page.screenshot({ path: `${evidence}/trends.png`, fullPage: true });
  checks.push({ name: 'trend analysis and separate stored-reading request render', direction: trend.trend.direction, sampleCount: trend.trend.sampleCount,
    storedReadings: storedBody.returnedCount, rawSampleTimestamp: storedReading?.timestamp });

  if (!trendsOnly) {
  await page.getByRole('navigation', { name: 'Phase 6', exact: true }).getByRole('link', { name: 'AI Insights', exact: true }).click();
  await page.getByRole('heading', { name: 'AI Insights', exact: true }).waitFor();
  const recommendations = await requestByClick('/intelligence/recommendations', page.getByRole('button', { name: 'Load insights', exact: true }));
  await page.getByRole('heading', { name: 'Recommendations (advice only)', exact: true }).waitFor();
  checks.push({ name: 'recommendations use actual backend report', reportPresent: !!recommendations.report });
  // Give the real limiter time to replenish: loading device insights makes two requests.
  await page.waitForTimeout(26000);
  await page.getByLabel('Stored device', { exact: true }).selectOption(device.id);
  const anomalies = await requestByClick('/intelligence/anomalies/', page.getByRole('button', { name: 'Load insights', exact: true }));
  await page.getByRole('heading', { name: 'Anomalies', exact: true }).waitFor();
  checks.push({ name: 'device anomaly report renders without invented findings', evaluatedAt: anomalies.evaluatedAt });
  if (matches.length) {
    await page.waitForTimeout(40000); // Three sequential requests: recommendations, anomalies, non-match.
    await page.getByLabel('Rule ID (optional non-match)', { exact: true }).fill(matches[0].ruleId);
    const nonMatch = await requestByClick('/non-match', page.getByRole('button', { name: 'Load insights', exact: true }));
    await page.getByRole('heading', { name: 'Rule non-match explanation', exact: true }).waitFor();
    checks.push({ name: 'stored rule non-match explanation renders', responsePresent: !!nonMatch });
  }
  await page.screenshot({ path: `${evidence}/insights.png`, fullPage: true });
  await page.getByRole('link', { name: 'Open recorded rule-match explanations and resulting actions', exact: false }).click();
  await page.getByRole('heading', { name: 'NEXUS Intelligence', exact: true }).waitFor();
  checks.push({ name: 'preserved rule-match explanation screen is reachable', passed: true });
  if (matches.length) {
    await page.waitForTimeout(15000);
    const explanation = await requestByClick('/ai/explanations/rule-match/', page.getByTestId(`rule-match-${matches[0].matchId}`));
    assert.equal(explanation.generatedBy, 'DETERMINISTIC_FALLBACK');
    await page.getByRole('heading', { name: 'Explanation', exact: true }).waitFor();
    assert.ok((await page.locator('body').innerText()).includes(explanation.summary));
    checks.push({ name: 'real recorded rule-match explanation renders without a model', generatedBy: explanation.generatedBy });
  }

  await page.goto(`${base}/ai/drafts`);
  const drafts = await requestByClick('/intelligence/drafts', page.getByRole('button', { name: 'Load stored drafts', exact: true }));
  assert.ok(Array.isArray(drafts));
  assert.equal(await page.getByRole('button', { name: /approve|activate/i }).count(), 0);
  await page.screenshot({ path: `${evidence}/drafts.png`, fullPage: true });
  checks.push({ name: 'stored drafts and lifecycle inspectable without approval/activation controls', count: drafts.length });
  await page.getByLabel('Proposed rule goal (requires a configured, consented model)', { exact: true }).fill('Draft a temperature alert rule');
  const refusal = await requestByClick('/intelligence/drafts', page.getByRole('button', { name: 'Generate draft only', exact: true }), 422);
  assert.ok(refusal.code);
  await page.getByText(refusal.code, { exact: true }).waitFor();
  const afterDrafts = await (await fetch(`${api}/intelligence/drafts`)).json();
  assert.equal(afterDrafts.length, drafts.length, 'Disabled-model refusal stores no draft');
  checks.push({ name: 'disabled-model draft request is honestly refused and stores nothing', code: refusal.code, modelRefusal: refusal.modelRefusal });
  }
  assert.deepEqual(faults, [], 'No browser runtime errors');
  await writeFile(`${evidence}/report.json`, JSON.stringify({ verdict: 'PASS', base, api, checks, browserErrors: faults }, null, 2));
  console.log(JSON.stringify({ verdict: 'PASS', checks }, null, 2));
} catch (error) {
  await page.screenshot({ path: `${evidence}/failure.png`, fullPage: true });
  await writeFile(`${evidence}/report.json`, JSON.stringify({ verdict: 'FAIL', checks, browserErrors: faults, network, error: String(error) }, null, 2));
  throw error;
} finally { await browser.close(); }
