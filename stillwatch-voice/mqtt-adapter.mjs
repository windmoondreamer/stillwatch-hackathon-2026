import mqtt from 'mqtt';
import { deviceAddress } from './device.mjs';

export class MqttAdapter {
  constructor({ monitor, onChange = () => {}, now = Date.now, connectImpl = mqtt.connect } = {}) {
    this.monitor = monitor; this.onChange = onChange; this.now = now; this.connectImpl = connectImpl;
    this.state = { connected: false, controllerOnline: false, sensorReady: false, outputFailed: false, outputs: {}, message: 'MQTT 연결 대기' };
    this.pending = new Map(); this.failed = new Set(); this.desired = {}; this.lastMotion = -1;
    this.timer = setInterval(() => this.tick(), 200); this.timer.unref();
  }
  snapshot() { return this.state; }
  change(values) { Object.assign(this.state, values); this.onChange(this.snapshot()); }
  connect({ url, deviceId, controller = 'stillwatch_ctrl', username, password }) {
    const address = new URL(url);
    if (!['mqtt:', 'mqtts:'].includes(address.protocol) || address.username || address.password || address.search || !['', '/'].includes(address.pathname)) throw new Error('MQTT 주소 형식을 확인해주세요.');
    if (!['localhost', '127.0.0.1'].includes(address.hostname)) deviceAddress(address.hostname);
    if (!/^[a-f0-9]{16}$/.test(deviceId) || !/^[a-zA-Z0-9_-]{1,64}$/.test(controller)) throw new Error('장치 ID 또는 컨트롤러 토픽이 올바르지 않습니다.');
    this.disconnect(); this.prefix = `espectre/v1/devices/${deviceId}`; this.controller = controller;
    const client = this.connectImpl(url, { username, password, reconnectPeriod: 2000, connectTimeout: 4000, clean: true }); this.client = client;
    this.change({ url: address.origin === 'null' ? `${address.protocol}//${address.host}` : address.origin, deviceId, controller, message: 'MQTT 연결 중' });
    client.on('connect', () => {
      if (client !== this.client) return;
      this.sensorOnline = false; this.sensing = null; this.lastMotion = -1; this.desired = {};
      client.subscribe([`${this.prefix}/motion`, `${this.prefix}/health`, `${this.prefix}/sensing`, `stat/${controller}/RESULT`, `stat/${controller}/POWER1`, `stat/${controller}/POWER2`, `tele/${controller}/LWT`]);
      this.change({ connected: true, sensorReady: false, message: 'MQTT 연결됨 · 센서 준비 상태 대기' }); this.sync(this.monitor.snapshot());
    });
    client.on('message', (topic, payload, packet) => { if (client === this.client) this.message(topic, payload.toString(), packet); });
    client.on('offline', () => { if (client !== this.client) return; this.unavailable(); this.change({ connected: false, controllerOnline: false, message: 'MQTT 연결 끊김 · 재연결 중' }); });
    client.on('error', () => { if (client === this.client) this.change({ message: 'MQTT 연결 오류 · 주소와 브로커를 확인해주세요.' }); });
  }
  unavailable() {
    this.change({ sensorReady: false });
    if (this.monitor.config.input === 'mqtt' && !['IDLE', 'ENDED'].includes(this.monitor.state) && this.monitor.sensor.connected) this.monitor.disconnect();
  }
  message(topic, payload, packet = {}) {
    if (payload.length > 16000) return;
    try {
      if (topic === `tele/${this.controller}/LWT`) {
        const online = payload === 'Online'; this.desired = {}; this.pending.clear();
        this.change({ controllerOnline: online, outputFailed: !online });
        if (online) this.sync(this.monitor.snapshot()); return;
      }
      if (topic.startsWith(`stat/${this.controller}/POWER`)) {
        const power = topic.slice(-6); const pending = this.pending.get(power);
        if (packet.retain || !pending || payload !== pending.value) return;
        this.pending.delete(power); this.failed.delete(power); this.state.outputs[power] = payload;
        this.change({ outputFailed: this.failed.size > 0 }); return;
      }
      const value = JSON.parse(payload);
      if (topic === `${this.prefix}/health`) {
        this.sensorOnline = value.online === true && value.status === 'ok';
        if (!this.sensorOnline) { this.lastMotion = -1; this.unavailable(); }
        return;
      }
      if (topic === `${this.prefix}/sensing`) {
        this.sensing = value;
        const ready = value.enabled === true && value.ready === true && value.mode === 'sensing' && value.derived_events_paused === false;
        this.change({ sensorReady: ready }); if (!ready) this.unavailable(); return;
      }
      if (topic === `${this.prefix}/motion`) {
        if (packet.retain || !this.sensorOnline || !this.state.sensorReady || !Number.isInteger(value.timestamp_ms) || value.timestamp_ms <= this.lastMotion ||
          !['idle', 'motion'].includes(value.state) || !Number.isFinite(value.score) || value.score < 0 || value.score > 1) return;
        this.lastMotion = value.timestamp_ms;
        if (this.monitor.config.input === 'mqtt' && !['IDLE', 'ENDED'].includes(this.monitor.state)) this.monitor.motion(value.state === 'motion', 'mqtt', value.score);
        return;
      }
      if (topic === `stat/${this.controller}/RESULT` && !packet.retain && !packet.dup) {
        if (['IDLE', 'ENDED'].includes(this.monitor.state)) return;
        if (value.Button1?.Action === 'SINGLE') this.monitor.presence('entry');
        if (value.Button1?.Action === 'DOUBLE' || value.Button3?.Action === 'SINGLE') this.monitor.presence('exit');
        if (value.Button2?.Action === 'SINGLE') this.monitor.confirm('worker');
      }
    } catch (error) { this.change({ message: `MQTT 입력 처리: ${error.message}` }); }
  }
  sync(snapshot) {
    if (!this.state.connected || !this.state.controllerOnline) return;
    const alarm = ['CHECKING', 'ALERTED'].includes(snapshot.state);
    for (const power of ['POWER1', 'POWER2']) {
      const value = alarm ? 'ON' : 'OFF';
      if (this.desired[power] === value) continue;
      this.desired[power] = value;
      this.pending.set(power, { value, sentAt: this.now(), attempts: 1 });
      this.client?.publish(`cmnd/${this.controller}/${power}`, value, { qos: 0, retain: false });
    }
  }
  tick() {
    for (const [power, command] of this.pending) {
      if (this.now() - command.sentAt < 2000) continue;
      if (command.attempts === 1 && this.state.connected && this.state.controllerOnline) {
        command.attempts++; command.sentAt = this.now();
        this.client?.publish(`cmnd/${this.controller}/${power}`, command.value, { qos: 0, retain: false });
      } else {
        this.pending.delete(power);
        this.failed.add(power);
        this.change({ outputFailed: true, message: `ALARM_OUTPUT_FAILED · ${power} 응답 없음` });
        this.monitor.log('controller', `${power} 출력 응답 없음 · 현장 장치 확인 필요`); this.monitor.emit();
      }
    }
  }
  disconnect() {
    const client = this.client; this.client = null; client?.end(true);
    this.pending.clear(); this.failed.clear(); this.desired = {}; this.sensorOnline = false; this.sensing = null;
    this.change({ connected: false, controllerOnline: false, outputFailed: false, outputs: {}, message: 'MQTT 연결 대기' }); this.unavailable();
  }
  close() { clearInterval(this.timer); this.disconnect(); }
}
