import test from 'node:test';
import assert from 'node:assert/strict';
import { Monitor } from '../engine.mjs';
function setup(mode = 'test') {
  let time = 100000;
  const monitor = new Monitor({ now: () => time });
  monitor.start({ room: '연구실 A', task: '장비 점검', mode, stillSeconds: 5, responseSeconds: 25 });
  return { monitor, advance(ms) { time += ms; monitor.tick(); } };
}
function checking() {
  const result = setup();
  result.monitor.motion(false, 'demo');
  result.advance(5000);
  assert.equal(result.monitor.state, 'CHECKING');
  return result;
}
test('short pauses and motion restoration never start a check', () => {
  const { monitor, advance } = setup();
  monitor.motion(false, 'demo'); advance(4900);
  assert.equal(monitor.state, 'WATCHING');
  monitor.motion(true, 'demo'); advance(20000);
  assert.equal(monitor.state, 'WATCHING');
});
test('normal and ambiguous voice responses do not clear or extend an alarm', () => {
  const { monitor, advance } = checking();
  const id = monitor.incident.id;
  const deadline = monitor.incident.deadline;
  advance(24000);
  monitor.response(id, 'responded', '가만히 작업 중이에요');
  monitor.response(id, 'unclear', '네?');
  monitor.motion(true, 'demo');
  assert.equal(monitor.state, 'CHECKING');
  assert.equal(monitor.incident.deadline, deadline);
  advance(1000);
  assert.equal(monitor.state, 'ALERTED');
  assert.equal(monitor.incident.result, 'timeout');
  const notice = monitor.incident.notice.id;
  advance(100000);
  assert.equal(monitor.incident.notice.id, notice);
  assert.equal(monitor.logs.filter(log => log.type === 'alert').length, 1);
});
test('explicit help requests register a local alert immediately', () => {
  const { monitor } = checking();
  monitor.response(monitor.incident.id, 'help_requested', '도와주세요');
  assert.equal(monitor.state, 'ALERTED');
  assert.equal(monitor.incident.notice.workerQuote, '도와주세요');
  assert.equal(monitor.incident.notice.delivery, 'local_dashboard');
  assert.throws(() => monitor.confirm('worker'));
  assert.throws(() => monitor.end());
  monitor.confirm('manager');
  assert.equal(monitor.state, 'WATCHING');
});
test('an on-time button resolves a check but an expired check requires a manager', () => {
  const first = checking();
  first.advance(24999);
  const oldId = first.monitor.incident.id;
  first.monitor.confirm('worker');
  first.advance(10000);
  assert.equal(first.monitor.state, 'WATCHING');
  assert.throws(() => first.monitor.response(oldId, 'help_requested', '늦은 발화'));
  const second = checking();
  second.advance(25000);
  assert.throws(() => second.monitor.confirm('worker'));
});
test('disconnect is missing evidence, not stillness, and does not cancel an existing deadline', () => {
  const first = setup();
  first.monitor.motion(false, 'demo');
  first.advance(1000); first.monitor.disconnect(); first.advance(100000);
  assert.equal(first.monitor.state, 'WATCHING');
  assert.equal(first.monitor.sensor.connected, false);
  const second = checking();
  second.monitor.disconnect(); second.advance(25000);
  assert.equal(second.monitor.state, 'ALERTED');
  assert.equal(second.monitor.incident.notice.sensorConnected, false);
});
test('device heartbeat expiry interrupts monitoring; new samples resume observation', () => {
  const { monitor, advance } = setup();
  monitor.motion(false, 'device'); advance(5000);
  assert.equal(monitor.sensor.connected, false);
  assert.equal(monitor.state, 'WATCHING');
  monitor.motion(false, 'device'); advance(4000);
  monitor.motion(false, 'device'); advance(1000);
  assert.equal(monitor.state, 'CHECKING');
});
test('voice failure and duplicate voice connection cannot alter the check deadline', () => {
  const { monitor, advance } = checking();
  const id = monitor.incident.id;
  monitor.claimVoice(id);
  assert.throws(() => monitor.claimVoice(id));
  monitor.voiceStatus(id, 'failed');
  advance(25000);
  assert.equal(monitor.state, 'ALERTED');
});
test('ending a job is blocked while a check is outstanding', () => {
  const { monitor } = checking();
  assert.throws(() => monitor.end());
  monitor.confirm('worker'); monitor.end();
  assert.equal(monitor.state, 'ENDED');
  assert.throws(() => monitor.motion(false));
});
