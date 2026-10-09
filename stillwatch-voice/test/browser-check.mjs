import { createRequire } from 'node:module';
import { mkdir, mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import assert from 'node:assert/strict';
const require = createRequire(import.meta.url);
const { chromium } = require('C:/Users/User/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
await mkdir(new URL('../test-artifacts/', import.meta.url), { recursive: true });
const temp = await mkdtemp(path.join(os.tmpdir(), 'stillwatch-browser-'));
const child = spawn(process.execPath, [fileURLToPath(new URL('../server.mjs', import.meta.url))], {
  env: { ...process.env, PORT: '0', OPENAI_API_KEY: '', STILLWATCH_REPORT_ENABLED: '0', STILLWATCH_SETTINGS_FILE: path.join(temp, '.env'), STILLWATCH_JOURNAL_FILE: path.join(temp, 'events.json'), STILLWATCH_DEVICE_AUTO_CONNECT: '0' }, stdio: ['ignore', 'pipe', 'pipe']
});
const base = await new Promise((resolve, reject) => {
  const timeout = setTimeout(() => reject(new Error('server startup timeout')), 5000);
  child.on('error', reject);
  child.stdout.on('data', data => {
    const match = data.toString().match(/http:\/\/127\.0\.0\.1:\d+/);
    if (match) { clearTimeout(timeout); resolve(match[0]); }
  });
});
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1200 } });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(base, { waitUntil: 'domcontentloaded' });
  await page.locator('#api-status').filter({ hasText: 'API 키 미설정' }).waitFor();
  await page.screenshot({ path: fileURLToPath(new URL('../test-artifacts/desktop.png', import.meta.url)), fullPage: true });
  await page.locator('#still-seconds').fill('2');
  await page.locator('#response-seconds').fill('10');
  await page.locator('#baseline-enabled').uncheck();
  await page.locator('#start').click();
  await page.locator('#simulate-still').click();
  await page.getByRole('heading', { name: '응답을 확인하고 있습니다' }).waitFor();
  await page.locator('[data-response="responded"]').click();
  assert.equal(await page.locator('#confirm').isEnabled(), true);
  await page.locator('#confirm').click();
  await page.getByRole('heading', { name: '작업 중 · 움직임 관찰' }).waitFor();
  await page.locator('#simulate-still').click();
  await page.getByRole('heading', { name: '응답을 확인하고 있습니다' }).waitFor();
  await page.locator('[data-response="help_requested"]').click();
  await page.getByRole('heading', { name: '담당자 확인이 필요합니다' }).waitFor();
  assert.match(await page.locator('#notice').innerText(), /도와주세요/);
  await page.screenshot({ path: fileURLToPath(new URL('../test-artifacts/alert.png', import.meta.url)), fullPage: true });
  await page.locator('#manager-confirm').click();
  await page.locator('#simulate-still').click();
  await page.getByRole('heading', { name: '응답을 확인하고 있습니다' }).waitFor();
  await page.getByRole('heading', { name: '담당자 확인이 필요합니다' }).waitFor({ timeout: 14000 });
  assert.match(await page.locator('#notice').innerText(), /제한시간 종료/);
  await page.locator('#manager-confirm').click();
  await page.locator('#simulate-disconnect').click();
  await page.locator('#sensor-status').filter({ hasText: '감시 중단' }).waitFor();
  assert.match(await page.locator('#sensor-status').innerText(), /감시 중단/);
  await page.locator('#simulate-motion').click();
  await page.locator('#exit').click();
  await page.getByRole('heading', { name: '등록된 작업자가 없습니다' }).waitFor();
  assert.equal(await page.locator('#headcount').innerText(), '0명');
  await page.locator('#entry').click();
  await page.getByRole('heading', { name: '작업 중 · 움직임 관찰' }).waitFor();
  assert.equal(await page.locator('#incident-history details').count(), 3);
  await page.locator('#end').click();
  await page.getByRole('heading', { name: '작업이 종료되었습니다' }).waitFor();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: fileURLToPath(new URL('../test-artifacts/mobile.png', import.meta.url)), fullPage: true });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth), false);
  assert.deepEqual(errors, []);
  console.log('Browser checks passed: normal response, help request, timeout, disconnect, recovery, job end, mobile layout.');

  // Mock only the microphone and WebRTC transport; tool responses operate the
  // real local state machine. This proves wiring, not model quality or audio.
  await page.addInitScript(() => {
    const track = { enabled: true, readyState: 'live', stop() { this.readyState = 'ended'; } };
    window.voiceHarness = { track, sent: [], connections: [] };
    Object.defineProperty(navigator.mediaDevices, 'getUserMedia', { value: async () => ({ getTracks: () => [track], getAudioTracks: () => [track] }) });
    window.RTCPeerConnection = class {
      constructor() { window.voiceHarness.connections.push(this); }
      addTrack() {}
      createDataChannel() {
        this.dc = { readyState: 'connecting', send: value => window.voiceHarness.sent.push(JSON.parse(value)), close() { this.readyState = 'closed'; } };
        window.voiceHarness.channel = this.dc; return this.dc;
      }
      async createOffer() { return { type: 'offer', sdp: 'v=0\r\nmock-browser-offer' }; }
      async setLocalDescription(value) { this.localDescription = value; }
      async setRemoteDescription() { this.dc.readyState = 'open'; this.dc.onopen(); }
      close() { this.closed = true; }
    };
  });
  let sessionRequests = 0;
  await page.route('**/api/session', route => { sessionRequests++; return route.fulfill({ status: 200, contentType: 'application/sdp', body: 'v=0\r\nmock-server-answer' }); });
  await page.setViewportSize({ width: 1440, height: 1000 }); await page.reload();
  await page.locator('#key-form').evaluate(el => el.closest('details').open = true);
  await page.locator('#api-key').fill('sk-test_placeholder_no_real_key_123456789');
  await page.locator('#key-form button').click();
  await page.locator('#api-status').filter({ hasText: 'API 키 설정됨' }).waitFor();
  assert.equal(await page.locator('#api-key').inputValue(), '');
  await page.locator('#mode').selectOption('live'); await page.locator('#still-seconds').fill('2');
  await page.locator('#baseline-enabled').uncheck(); await page.locator('#start').click();
  assert.equal(await page.evaluate(() => window.voiceHarness.track.enabled), false);
  await page.locator('#simulate-still').click(); await page.locator('#voice-status').filter({ hasText: 'AI 음성 연결됨' }).waitFor();
  assert.equal(sessionRequests, 1);
  assert.equal(await page.evaluate(() => window.voiceHarness.track.enabled), true);
  const before = await (await page.request.get(base + '/api/state')).json();
  const emit = event => page.evaluate(event => window.voiceHarness.channel.onmessage({ data: JSON.stringify(event) }), event);
  await emit({ type: 'conversation.item.input_audio_transcription.completed', transcript: '네?' });
  await emit({ type: 'response.function_call_arguments.done', name: 'report_worker_response', call_id: 'ambiguous-call', arguments: JSON.stringify({ category: 'unclear', quote: '네?' }) });
  await page.waitForFunction(() => window.voiceHarness.sent.some(item => item.item?.call_id === 'ambiguous-call'));
  const unclear = await (await page.request.get(base + '/api/state')).json();
  assert.equal(unclear.state, 'CHECKING'); assert.equal(unclear.incident.deadline, before.incident.deadline);
  await emit({ type: 'conversation.item.input_audio_transcription.completed', transcript: '도와주세요' });
  await emit({ type: 'response.function_call_arguments.done', name: 'report_worker_response', call_id: 'help-call', arguments: JSON.stringify({ category: 'help_requested', quote: '도와주세요' }) });
  await page.getByRole('heading', { name: '담당자 확인이 필요합니다' }).waitFor();
  await page.waitForFunction(() => window.voiceHarness.track.enabled === false);
  assert.match(await page.locator('#notice').innerText(), /도와주세요/);
  await page.locator('#voice-status').filter({ hasText: '음성 연결 종료' }).waitFor({ timeout: 8000 });
  await page.locator('#manager-confirm').click(); await page.locator('#end').click();
  await page.waitForFunction(() => window.voiceHarness.track.readyState === 'ended');
  assert.equal(await page.evaluate(() => window.voiceHarness.connections[0].closed), true);
  assert.deepEqual(errors, []);
  console.log('Voice wiring checks passed with mocked WebRTC: microphone gating, SDP, transcription, tool response, deadline, help alert and microphone release. No live AI call was made.');
} finally { await browser.close(); child.kill(); await new Promise(resolve => child.once('close', resolve)); await rm(temp, { recursive: true, force: true }); }
