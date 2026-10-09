import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { Device, deviceAddress } from '../device.mjs';
import { Monitor } from '../engine.mjs';

const ready = { enabled: true, ready: true, mode: 'sensing', derived_events_paused: false };
function setup() {
  const samples = []; let unavailable = 0;
  const device = new Device({ root: '.', onMotion: value => samples.push(value), onUnavailable: () => unavailable++ });
  return { device, samples, unavailable: () => unavailable };
}
test('only canonical private IPv4 addresses form device endpoints', () => {
  assert.equal(deviceAddress('192.168.0.8'), 'http://192.168.0.8:62587/espectre/v1');
  for (const value of ['127.0.0.1', '8.8.8.8', '192.168.0.999', '192.168.0.01', 'http://192.168.0.8', undefined]) assert.throws(() => deviceAddress(value));
});
test('calibration, disabled sensing and CSI collection never produce stillness samples', () => {
  const { device, samples } = setup();
  for (const settings of [{ ...ready, ready: false }, { ...ready, enabled: false }, { ...ready, mode: 'csi_collection' }, { ...ready, derived_events_paused: true }, { ready: true }]) {
    device.event('sensing', settings);
    device.event('motion', { timestamp_ms: 1, state: 'idle', score: 0 });
  }
  assert.deepEqual(samples, []);
  device.event('sensing', ready);
  device.event('motion', { timestamp_ms: 2, state: 'idle', score: 0.02 });
  assert.deepEqual(samples, [false]);
});
test('invalid and repeated motion records are ignored; faults invalidate detector output', () => {
  const { device, samples, unavailable } = setup();
  device.event('sensing', ready);
  for (const data of [{ state: 'idle', score: 2, timestamp_ms: 1 }, { state: 'unknown', score: 0, timestamp_ms: 1 }, { state: 'idle', score: 0 }]) device.event('motion', data);
  device.event('motion', { state: 'motion', score: 0.8, timestamp_ms: 100 });
  device.event('motion', { state: 'idle', score: 0, timestamp_ms: 100 });
  device.event('motion', { state: 'idle', score: 0, timestamp_ms: 99 });
  device.event('fault', { message: 'runtime fault' });
  device.event('motion', { state: 'idle', score: 0, timestamp_ms: 101 });
  assert.deepEqual(samples, [true]);
  assert.equal(unavailable(), 1);
  assert.equal(device.snapshot().ready, false);
});
test('starting a hardware job waits for evidence; valid idle samples trigger a check', () => {
  let now = 0;
  const monitor = new Monitor({ now: () => now });
  monitor.start({ mode: 'test', input: 'device', stillSeconds: 2, responseSeconds: 10 });
  now = 10000; monitor.tick();
  assert.equal(monitor.state, 'WATCHING');
  assert.equal(monitor.sensor.connected, false);
  const device = new Device({ root: '.', onMotion: value => monitor.motion(value, 'device'), onUnavailable: () => monitor.disconnect() });
  device.event('sensing', ready);
  device.event('motion', { state: 'idle', score: 0.01, timestamp_ms: 1 });
  now += 1000; device.event('motion', { state: 'idle', score: 0.01, timestamp_ms: 2 });
  now += 1000; monitor.tick();
  assert.equal(monitor.state, 'CHECKING');
  device.event('sensing', { ...ready, ready: false });
  now += 10000; monitor.tick();
  assert.equal(monitor.state, 'ALERTED');
  assert.equal(monitor.incident.notice.sensorConnected, false);
});
test('Direct API SSE handles split CRLF frames and stops when disconnected', async t => {
  const root = await mkdtemp(path.join(tmpdir(), 'stillwatch-device-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  await mkdir(path.join(root, 'hardware'));
  const encoder = new TextEncoder();
  let eventController;
  const events = new ReadableStream({ start(controller) {
    eventController = controller;
    controller.enqueue(encoder.encode('event: motion\r'));
    controller.enqueue(encoder.encode('\ndata: {"state":"motion","score":0.8,"timestamp_ms":10}\r\n\r'));
    controller.enqueue(encoder.encode('\n'));
  } });
  let resolveSample;
  const sample = new Promise(resolve => resolveSample = resolve);
  const device = new Device({ root, onMotion: value => { resolveSample(value); device.disconnect(); }, fetchImpl: async (url, options) => {
    assert.equal(options.headers.Origin, 'https://test.espectre.dev');
    const name = url.split('/').at(-1);
    if (name === 'events') {
      options.signal.addEventListener('abort', () => eventController.error(new Error('aborted')));
      return new Response(events, { headers: { 'Content-Type': 'text/event-stream' } });
    }
    return Response.json(name === 'capabilities' ? { protocol_version: '1.0', resources: ['sensing'], events: ['motion'] } : name === 'sensing' ? ready : { firmware: '3.0.0', chip: 'esp32' });
  } });
  t.after(() => device.disconnect());
  await device.connect('192.168.0.8');
  assert.equal(await Promise.race([sample, new Promise((_, reject) => { const timer = setTimeout(() => reject(new Error('missing SSE sample')), 1000); timer.unref(); })]), true);
  assert.equal(device.snapshot().connected, false);
});
