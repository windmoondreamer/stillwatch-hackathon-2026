import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { Monitor } from '../engine.mjs';
import { MqttAdapter } from '../mqtt-adapter.mjs';
function setup(t) {
  let time = 100000;
  const monitor = new Monitor({ now: () => time });
  monitor.start({ mode: 'test', input: 'mqtt', stillSeconds: 2, responseSeconds: 10 });
  const client = new EventEmitter(), commands = [];
  client.publish = (topic, value, options) => commands.push({ topic, value, options });
  client.subscribe = () => {}; client.end = () => {};
  const broker = new MqttAdapter({ monitor, now: () => time, connectImpl: () => client });
  broker.connect({ url: 'mqtt://127.0.0.1:1883', deviceId: '0123456789abcdef' }); client.emit('connect');
  t.after(() => broker.close());
  const send = (topic, value, packet = {}) => client.emit('message', topic, Buffer.from(typeof value === 'string' ? value : JSON.stringify(value)), packet);
  return { monitor, broker, client, commands, send, advance(ms) { time += ms; monitor.tick(); broker.tick(); }, prefix: 'espectre/v1/devices/0123456789abcdef' };
}
test('MQTT needs ready and online; retained, duplicate and invalid motion cannot create stillness', t => {
  const { monitor, send, advance, prefix } = setup(t);
  const idle = { timestamp_ms: 1000, state: 'idle', score: 0.1 };
  send(prefix + '/motion', idle); assert.equal(monitor.sensor.connected, false);
  send(prefix + '/health', { online: true, status: 'ok' }, { retain: true });
  send(prefix + '/sensing', { enabled: true, ready: true, mode: 'sensing', derived_events_paused: false }, { retain: true });
  send(prefix + '/motion', idle, { retain: true }); assert.equal(monitor.sensor.connected, false);
  send(prefix + '/motion', idle); advance(1000);
  send(prefix + '/motion', idle); // Same device timestamp must not renew freshness.
  advance(4000); assert.equal(monitor.sensor.connected, false);
  send(prefix + '/motion', { ...idle, timestamp_ms: 2000, score: 4 }); assert.equal(monitor.sensor.connected, false);
  send(prefix + '/motion', { ...idle, timestamp_ms: 2000 }); advance(1000);
  send(prefix + '/motion', { ...idle, timestamp_ms: 3000 }); advance(1000);
  assert.equal(monitor.state, 'CHECKING');
  send(prefix + '/health', { online: false, status: 'offline' }); advance(10000);
  assert.equal(monitor.state, 'ALERTED');
});
test('controller output requires ack, retries once, and never clears alert on output failure', t => {
  const { broker, monitor, send, advance, commands } = setup(t);
  send('tele/stillwatch_ctrl/LWT', 'Online', { retain: true });
  send('stat/stillwatch_ctrl/POWER1', 'OFF'); send('stat/stillwatch_ctrl/POWER2', 'OFF');
  monitor.state = 'CHECKING'; monitor.incident = { id: 'fake', deadline: 200000 }; broker.sync(monitor.snapshot());
  assert.equal(commands.filter(cmd => cmd.value === 'ON').length, 2);
  advance(2000); assert.equal(commands.filter(cmd => cmd.value === 'ON').length, 4);
  send('stat/stillwatch_ctrl/POWER1', 'ON'); advance(2000);
  assert.equal(broker.state.outputFailed, true);
  assert.equal(monitor.state, 'CHECKING');
  assert.equal(commands.every(cmd => cmd.options.retain === false), true);
});
test('retained controller buttons do not alter headcount and live buttons map to entry/exit', t => {
  const { monitor, send } = setup(t);
  send('stat/stillwatch_ctrl/RESULT', { Button1: { Action: 'SINGLE' } }, { retain: true });
  assert.equal(monitor.headcount, 1);
  send('stat/stillwatch_ctrl/RESULT', { Button1: { Action: 'SINGLE' } }); assert.equal(monitor.headcount, 2);
  send('stat/stillwatch_ctrl/RESULT', { Button1: { Action: 'DOUBLE' } }); assert.equal(monitor.headcount, 1);
  send('stat/stillwatch_ctrl/RESULT', { Button3: { Action: 'SINGLE' } }); assert.equal(monitor.state, 'EMPTY');
});
