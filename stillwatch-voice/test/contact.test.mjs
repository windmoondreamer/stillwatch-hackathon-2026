import test from 'node:test';
import assert from 'node:assert/strict';
import { Monitor } from '../engine.mjs';
import { ContactCoordinator } from '../contact.mjs';
import { templateReport } from '../report.mjs';
import { MobileTerminal } from '../mobile.mjs';
import { interpretAudio } from '../audio.mjs';

const settle = () => new Promise(resolve => setImmediate(resolve));
const smsDevice = { deviceId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', role: 'worker', armed: true, smsReady: true };
function make(extra = {}, options = {}) {
  let time = 100000, coordinator;
  const monitor = new Monitor({ now: () => time, onChange: state => coordinator?.observe(state) });
  coordinator = new ContactCoordinator({ monitor, settings: async () => ({ key: '', deliveryMode: 'outbox', ...options.config }), journal: { chain: Promise.resolve() }, ...options.coordinator });
  monitor.start({ room: '기계실', address: '확인한 주소', floor: '지하 1층', entrance: '동쪽 출입구', workerName: '작업자',
    mode: 'test', stillSeconds: 2, responseSeconds: 10, managerSeconds: 5, autoContact: true, managerEnabled: true, recipient: 'test', ...extra });
  monitor.motion(false, 'demo');
  return { monitor, coordinator, advance(ms) { time += ms; monitor.tick(); }, now: () => time };
}
test('draft exists during worker check before any manager notice; manager timeout records once', async () => {
  const { monitor, advance } = make(); advance(2000); await settle();
  assert.equal(monitor.state, 'CHECKING'); assert.equal(monitor.incident.notice, null);
  assert.equal(monitor.incident.draft.status, 'fallback'); assert.match(monitor.incident.report.report_119_ko, /확인 중/);
  advance(10000); assert.equal(monitor.incident.contact.status, 'awaiting_manager');
  const deadline = monitor.incident.managerDeadline;
  monitor.managerFeedback(monitor.incident.id, 'seen'); monitor.managerFeedback(monitor.incident.id, 'checking');
  assert.equal(monitor.incident.managerDeadline, deadline);
  advance(5000); await settle();
  assert.equal(monitor.incident.contact.status, 'recorded');
  assert.equal(monitor.incident.contact.payload.facts.contact_reason, 'manager_timeout');
  assert.match(monitor.incident.contact.payload.message, /확인된 위치: 확인한 주소/);
  const key = monitor.incident.contact.idempotencyKey; advance(100000); await settle();
  assert.equal(monitor.incident.contact.idempotencyKey, key);
  assert.equal(monitor.logs.filter(l => l.message.includes('연락함에 기록')).length, 1);
});
test('manager normal and worker confirmation cancel pending contact without waiting for AI', async () => {
  for (const who of ['manager', 'worker']) {
    const { monitor, advance } = make(); advance(12000); const item = monitor.incident;
    if (who === 'manager') monitor.managerFeedback(item.id, 'normal', '직접 통화로 확인'); else monitor.confirm('worker');
    advance(100000); await settle(); assert.equal(item.contact.status, 'cancelled'); assert.equal(item.contact.payload, undefined);
  }
});
test('help bypasses manager waiting, and no-manager timeout queues directly', async () => {
  const first = make(); first.advance(2000);
  first.monitor.response(first.monitor.incident.id, 'help_requested', '손이 끼었어요', 'ai'); await settle();
  assert.equal(first.monitor.incident.contact.status, 'recorded'); assert.equal(first.monitor.incident.managerDeadline, undefined);
  const second = make({ managerEnabled: false }); second.advance(2000); second.advance(10000); await settle();
  assert.equal(second.monitor.incident.contact.status, 'recorded');
});
test('late AI draft is discarded after facts change or confirmation', async () => {
  let release;
  const { monitor, advance } = make({ mode: 'live' }, { coordinator: { reportFn: snapshot => new Promise(resolve => { release = () => resolve({ ...templateReport(snapshot), source: 'ai', status: 'complete' }); }) } });
  advance(2000); await settle(); const item = monitor.incident;
  monitor.confirm('worker'); release(); await settle();
  assert.equal(item.contact.status, 'cancelled'); assert.notEqual(item.report.source, 'ai');
});
test('stale draft is not sent, queued cancellation wins an asynchronous dispatch race', async () => {
  let release, calls = 0;
  const configPromise = new Promise(resolve => release = resolve);
  const { monitor, advance, coordinator } = make({ mode: 'live', managerEnabled: false }, { coordinator: { fetchImpl: async () => { calls++; throw new Error(); } } });
  coordinator.settings = () => configPromise;
  advance(2000); advance(10000); const item = monitor.incident;
  monitor.confirm('worker'); release({ deliveryMode: 'webhook', deliveryUrl: 'http://127.0.0.1:9', deliveryToken: 'test' }); await settle();
  assert.equal(calls, 0); assert.equal(item.contact.status, 'cancelled');
});
test('webhook gets one idempotent request; timeout stays unknown without automatic retry', async () => {
  for (const accepted of [true, false]) {
    let calls = 0, payload;
    const { monitor, advance } = make({ mode: 'live', managerEnabled: false, input: 'device' }, {
      config: { deliveryMode: 'webhook', deliveryUrl: 'http://127.0.0.1:1234', deliveryToken: 'test' },
      coordinator: { fetchImpl: async (_url, options) => { calls++; payload = JSON.parse(options.body); assert.equal(options.headers['Idempotency-Key'], payload.idempotencyKey); if (!accepted) throw new Error('timeout'); return new Response(JSON.stringify({ accepted: true, receipt: 'receipt-1' })); } }
    });
    monitor.motion(false, 'device'); advance(2000); monitor.response(monitor.incident.id, 'help_requested', '도와주세요'); await settle(); await settle();
    assert.equal(monitor.incident.contact.status, accepted ? 'sent' : 'unknown'); assert.equal(payload.recipient, 'test');
    advance(100000); await settle(); assert.equal(calls, 1);
  }
});
test('restart marks an in-flight send unknown and never resumes a queued contact', () => {
  const { monitor, advance } = make(); advance(2000); monitor.incident.contact.status = 'sending';
  const restored = new Monitor({ history: structuredClone(monitor.snapshot()) });
  assert.equal(restored.incidents[0].contact.status, 'unknown'); assert.equal(restored.state, 'IDLE');
});
test('mobile requires one armed worker and receipt is different from spoken question', () => {
  const { monitor, advance, now } = make(); const mobile = new MobileTerminal({ monitor, now, settings: async () => ({}) });
  const input = { deviceId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', role: 'worker', armed: true };
  advance(2000); const value = mobile.poll(input); assert.equal(value.command.kind, 'check'); assert.equal(monitor.incident.questionCount, 0);
  mobile.event({ ...input, incidentId: monitor.incident.id, commandId: value.command.id, kind: 'received' });
  mobile.event({ ...input, incidentId: monitor.incident.id, commandId: value.command.id, kind: 'spoken', text: value.command.prompt });
  mobile.event({ ...input, incidentId: monitor.incident.id, commandId: value.command.id, kind: 'spoken', text: value.command.prompt });
  assert.equal(monitor.incident.questionCount, 1);
  assert.throws(() => mobile.poll({ ...input, deviceId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb' }));
  advance(16000); assert.equal(mobile.snapshot()[0].connected, false);
});
test('native audio result arriving after deadline cannot change the incident', async () => {
  const { monitor, advance, now } = make(); let release;
  const mobile = new MobileTerminal({ monitor, now, settings: async () => ({ key: 'fake' }), audioFn: () => new Promise(resolve => { release = resolve; }) });
  const input = { deviceId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', role: 'worker', armed: true };
  advance(2000); mobile.poll(input); monitor.config.mode = 'live';
  const bytes = Buffer.alloc(44); bytes.write('RIFF'); bytes.write('WAVE', 8);
  const pending = mobile.audio({ ...input, incidentId: monitor.incident.id, wav: bytes.toString('base64') });
  await settle(); advance(10000); release({ category: 'responded', quote: '괜찮아요', reply: '확인해주세요' });
  await assert.rejects(pending); assert.equal(monitor.incident.response, null);
});
test('audio chain interprets transcription using strict schema and returns truthful API failures', async () => {
  let calls = 0;
  const result = await interpretAudio(Buffer.from('test'), { key: 'fake', fetchImpl: async (url, options) => {
    calls++; if (url.endsWith('transcriptions')) return new Response(JSON.stringify({ text: '도와주세요' }));
    assert.equal(JSON.parse(options.body).text.format.strict, true);
    return new Response(JSON.stringify({ output: [{ content: [{ type: 'output_text', text: JSON.stringify({ category: 'help_requested', reply: '도움 요청을 기록하겠습니다.' }) }] }] }));
  } });
  assert.equal(result.category, 'help_requested'); assert.equal(result.quote, '도와주세요'); assert.equal(calls, 2);
  assert.equal((await interpretAudio(Buffer.from('test'), {})).code, 'no_key');
  assert.equal((await interpretAudio(Buffer.from('test'), { key: 'fake', fetchImpl: async () => { throw new Error(); } })).code, 'unavailable');
});

test('Android SMS requires a capable device, one claim and every multipart send callback', async () => {
  const { monitor, advance, now } = make({ recipient: '01000000000' });
  const mobile = new MobileTerminal({ monitor, now, settings: async () => ({}), journal: { chain: Promise.resolve() } });
  advance(2000); monitor.response(monitor.incident.id, 'help_requested', '도와주세요', 'test'); await settle();
  const item = monitor.incident;
  assert.throws(() => mobile.queueSms(item));
  mobile.poll(smsDevice); mobile.queueSms(item);
  const command = mobile.poll(smsDevice).command;
  const claim = { ...smsDevice, incidentId: item.id, commandId: command.id };
  await assert.rejects(mobile.claimSms({ ...claim, deviceId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb' }));
  assert.equal((await mobile.claimSms(claim)).recipient, '01000000000');
  await assert.rejects(mobile.claimSms(claim));
  mobile.smsResult({ ...claim, sent: true, part: 0, total: 2 });
  assert.equal(item.contact.status, 'sending');
  mobile.smsResult({ ...claim, sent: true, part: 0, total: 2 });
  assert.equal(item.contact.status, 'sending');
  mobile.smsResult({ ...claim, sent: true, part: 1, total: 2 });
  assert.equal(item.contact.status, 'sent'); assert.match(item.contact.receipt, /최종 수신 미확인/);
});

test('normal confirmation cancels unclaimed SMS; missing callback is unknown even after resolution', async () => {
  const { monitor, advance, now } = make({ recipient: '01000000000' });
  const mobile = new MobileTerminal({ monitor, now, settings: async () => ({}) });
  advance(2000); monitor.response(monitor.incident.id, 'help_requested', '도와주세요', 'test'); await settle();
  mobile.poll(smsDevice); mobile.queueSms(monitor.incident);
  const item = monitor.incident, command = mobile.poll(smsDevice).command;
  monitor.confirm('worker'); assert.equal(item.contact.status, 'cancelled');
  await assert.rejects(mobile.claimSms({ ...smsDevice, incidentId: item.id, commandId: command.id }));
  item.contact.status = 'sending'; item.contact.attemptedAt = now(); item.contact.channel = 'android_sms';
  advance(30000); assert.equal(item.contact.status, 'unknown');
});

test('SMS failure is retained; idle handset and lost device never count as delivery', async () => {
  const { monitor, advance, now } = make({ recipient: '01000000000' });
  const mobile = new MobileTerminal({ monitor, now, settings: async () => ({}) });
  advance(2000); monitor.response(monitor.incident.id, 'help_requested', '도와주세요', 'test'); await settle();
  mobile.poll(smsDevice); mobile.queueSms(monitor.incident);
  advance(30000); assert.equal(monitor.incident.contact.status, 'failed');
  mobile.poll(smsDevice); mobile.queueSms(monitor.incident);
  const command = mobile.poll(smsDevice).command, input = { ...smsDevice, incidentId: monitor.incident.id, commandId: command.id };
  await mobile.claimSms(input); mobile.smsResult({ ...input, sent: false, part: 0, total: 2, error: 'no service' });
  assert.equal(monitor.incident.contact.status, 'failed');
  assert.throws(() => mobile.smsResult({ ...input, sent: true, part: 1, total: 2 }));
});
