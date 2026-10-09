import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
test('local HTTP flow, API-key absence, request validation and file isolation', async t => {
  const temp = await mkdtemp(path.join(os.tmpdir(), 'stillwatch-http-'));
  t.after(() => rm(temp, { recursive: true, force: true }));
  const child = spawn(process.execPath, [fileURLToPath(new URL('../server.mjs', import.meta.url))], {
    env: { ...process.env, PORT: '0', OPENAI_API_KEY: '', STILLWATCH_SETTINGS_FILE: path.join(temp, '.env'), STILLWATCH_JOURNAL_FILE: path.join(temp, 'events.json'), STILLWATCH_DEVICE_AUTO_CONNECT: '0' }, stdio: ['ignore', 'pipe', 'pipe']
  });
  t.after(() => child.kill());
  const base = await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('server startup timeout')), 5000);
    child.on('error', reject);
    child.stdout.on('data', data => {
      const match = data.toString().match(/http:\/\/127\.0\.0\.1:\d+/);
      if (match) { clearTimeout(timeout); resolve(match[0]); }
    });
  });
  const post = async (route, value, headers = {}) => fetch(base + route, {
    method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(value)
  });
  assert.equal((await fetch(base + '/')).status, 200);
  assert.equal((await fetch(base + '/.env')).status, 404);
  assert.equal((await fetch(base + '/api/config')).status, 200);
  assert.equal((await (await fetch(base + '/api/config')).json()).keyConfigured, false);
  const job = { room: '테스트실', task: '점검', mode: 'test', stillSeconds: 2, responseSeconds: 10 };
  assert.equal((await post('/api/start', { ...job, mode: 'live' })).status, 503);
  assert.equal((await post('/api/start', job, { Origin: 'https://example.com' })).status, 403);
  assert.equal((await post('/api/start', job)).status, 200);
  assert.equal((await post('/api/demo-motion', { motion: 'false' })).status, 400);
  assert.equal((await post('/api/demo-motion', { motion: false })).status, 200);
  await new Promise(resolve => setTimeout(resolve, 2250));
  const check = await (await fetch(base + '/api/state')).json();
  assert.equal(check.state, 'CHECKING');
  assert.equal((await post('/api/test-response', { incidentId: check.incident.id, category: 'help_requested', quote: '도와주세요' })).status, 200);
  const alert = await (await fetch(base + '/api/state')).json();
  assert.equal(alert.state, 'ALERTED');
  assert.equal(alert.incident.notice.room, '테스트실');
  assert.equal((await post('/api/confirm', { who: 'worker' })).status, 400);
  assert.equal((await post('/api/confirm', { who: 'manager' })).status, 200);
  assert.equal((await post('/api/end', {})).status, 200);
  assert.equal((await post('/api/start', { ...job, input: 'device' })).status, 200);
  assert.equal((await (await fetch(base + '/api/state')).json()).sensor.connected, false);
  assert.equal((await post('/api/demo-motion', { motion: false })).status, 409);
  assert.equal((await post('/api/sensor', { motion: true })).status, 200);
  assert.equal((await post('/api/end', {})).status, 200);
  const exported = await (await fetch(base + '/api/state')).json();
  assert.equal(exported.incidents.length, 1);
  assert.equal(exported.incidents[0].resolvedBy, 'manager');
  assert.equal(exported.incidents[0].report.source, 'template');
  assert.equal((await post('/api/config/key', { key: 'sk-test\nOTHER=value' })).status, 400);
  const fakeKey = 'sk-test_placeholder_no_real_key_123456789';
  assert.equal((await post('/api/config/key', { key: fakeKey }, { Origin: 'https://example.com' })).status, 403);
  assert.equal((await post('/api/config/key', { key: fakeKey })).status, 200);
  const configResponse = await (await fetch(base + '/api/config')).text();
  assert.equal(configResponse.includes(fakeKey), false);
  assert.equal(JSON.parse(configResponse).keyConfigured, true);
  assert.match(await readFile(path.join(temp, '.env'), 'utf8'), /OPENAI_API_KEY=sk-test_placeholder/);
  assert.equal((await post('/api/mqtt/connect', { url: 'mqtt://example.com', deviceId: '0123456789abcdef' })).status, 400);
});
