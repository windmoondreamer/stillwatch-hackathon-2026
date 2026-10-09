import { interpretAudio } from './audio.mjs';

export class MobileTerminal {
  constructor({ monitor, settings, now = Date.now, audioFn = interpretAudio, journal }) {
    Object.assign(this, { monitor, settings, now, audioFn, journal });
    this.devices = new Map(); this.worker = null; this.commands = new Map(); this.events = new Set();
  }
  snapshot() { return [...this.devices.values()].map(device => ({ ...device, connected: this.now() - device.lastSeen < 15000 })); }
  device(input) {
    if (!/^[a-f0-9-]{36}$/.test(input.deviceId || '') || !['worker', 'manager'].includes(input.role)) throw new Error('단말 ID 또는 역할이 올바르지 않습니다.');
    if (input.role === 'worker' && this.worker && this.worker !== input.deviceId && this.now() - (this.devices.get(this.worker)?.lastSeen || 0) < 15000) throw new Error('다른 작업자 단말이 대기 중입니다.');
    if (input.role === 'worker') this.worker = input.deviceId;
    const existing = this.devices.get(input.deviceId);
    const value = { id: input.deviceId, role: input.role, armed: input.armed === true, smsReady: input.smsReady === true, lastSeen: this.now(), lastError: existing?.lastError || '' };
    if (!existing) this.monitor.log('phone', `${input.role === 'worker' ? '작업자' : '관리자'} Android 단말 연결`);
    this.devices.set(input.deviceId, value); return value;
  }
  poll(input) {
    const device = this.device(input); this.monitor.tick();
    const state = this.monitor.snapshot(), incident = state.incident;
    let command = null;
    if (device.armed && incident && !incident.resolvedAt) {
      if (incident.contact?.status === 'device_pending' && incident.contact.deliveryDevice === device.id && device.smsReady) {
        return { state: state.state, incidentId: incident.id, contactStatus: 'device_pending', mode: state.config.mode, serverTime: this.now(),
          command: { id: incident.contact.idempotencyKey, incidentId: incident.id, kind: 'send_contact', recipient: incident.contact.payload.recipient, message: incident.contact.payload.message } };
      }
      const key = device.id + ':' + incident.id;
      let tracker = this.commands.get(key);
      if (!tracker) { tracker = { count: 0, lastAt: 0, pending: null, warned: false, managerAlerted: false }; this.commands.set(key, tracker); }
      if (device.role === 'manager' && state.state === 'ALERTED' && state.config.managerEnabled && !tracker.managerAlerted) {
        command = { id: incident.id + ':manager', incidentId: incident.id, kind: 'manager_alert', prompt: `${incident.room} 작업자 상태 확인이 필요합니다. 앱에서 확인 결과를 알려주세요.`, recordSeconds: 0 };
        tracker.managerAlerted = true;
      }
      if (device.role === 'worker' && state.state === 'CHECKING' && state.config.voiceOutput !== 'browser') {
        const remaining = incident.deadline - this.now();
        if (remaining <= 5000 && !tracker.warned) {
          command = { id: incident.id + ':warning', incidentId: incident.id, kind: 'warning', prompt: state.config.managerEnabled ? '응답이 없으면 관리자에게 알리고, 확인이 늦으면 등록된 연락처로 도움을 요청하겠습니다.' : '응답이 없으면 등록된 연락처로 도움을 요청하겠습니다.', recordSeconds: 0 };
          tracker.warned = true;
        } else if (remaining > 7000 && tracker.count < 3 && this.now() - tracker.lastAt > 8000) {
          tracker.count++; tracker.lastAt = this.now();
          command = { id: incident.id + ':question:' + tracker.count, incidentId: incident.id, kind: 'check', prompt: tracker.count === 1 ? '괜찮으신가요? 문제가 있거나 도움이 필요하신가요?' : '응답이 확인되지 않았습니다. 도움이 필요하신지 말씀해주세요. 괜찮으시면 확인 버튼을 눌러주세요.', recordSeconds: 5 };
        }
      }
      if (command) tracker.pending = command;
      else if (tracker.pending && (!tracker.pending.deadline || this.now() < tracker.pending.deadline)) command = tracker.pending;
      if (command) command.deadline = device.role === 'worker' ? incident.deadline : incident.managerDeadline || this.now() + 60000;
      if (command && this.now() >= command.deadline) command = null;
    }
    return { state: state.state, incidentId: incident?.id || null, contactStatus: incident?.contact?.status || null,
      deadline: incident?.deadline || null, managerDeadline: incident?.managerDeadline || null,
      mode: state.config.mode, command, serverTime: this.now() };
  }
  event(input) {
    this.device(input);
    const item = this.monitor.incident;
    if (!item || item.id !== input.incidentId) return { ignored: true };
    const key = input.deviceId + ':' + item.id, tracker = this.commands.get(key);
    if (input.kind === 'received' && tracker?.pending?.id === input.commandId) tracker.pending = null;
    const unique = input.deviceId + ':' + input.commandId + ':' + input.kind;
    if (this.events.has(unique)) return { recorded: true };
    this.events.add(unique); if (this.events.size > 500) this.events.delete(this.events.values().next().value);
    if (input.kind === 'spoken' && typeof input.text === 'string' && input.text.trim()) {
      if (input.role === 'worker') {
        this.monitor.transcript(item.id, 'assistant', input.text.slice(0, 1000));
        this.monitor.voiceStatus(item.id, 'connected');
      } else { item.managerNotificationReceivedAt = this.now(); this.monitor.emit(); }
    }
    if (input.kind === 'failed') {
      this.devices.get(input.deviceId).lastError = String(input.error || '음성 실패').slice(0, 200);
      this.monitor.voiceStatus(item.id, 'failed');
    }
    return { recorded: true };
  }
  async audio(input) {
    if (input.deviceId !== this.worker || input.role !== 'worker') throw new Error('작업자 음성 단말이 아닙니다.');
    this.monitor.activeIncident(input.incidentId);
    const encoded = input.wav;
    if (typeof encoded !== 'string' || encoded.length > 400000 || !/^[A-Za-z0-9+/=]+$/.test(encoded)) throw new Error('음성 파일 형식이 올바르지 않습니다.');
    const bytes = Buffer.from(encoded, 'base64');
    if (bytes.length < 44 || bytes.subarray(0, 4).toString() !== 'RIFF' || bytes.subarray(8, 12).toString() !== 'WAVE') throw new Error('WAV 음성만 지원합니다.');
    const config = await this.settings();
    if (this.monitor.config.mode !== 'live') return { category: 'unclear', quote: '', reply: '시험 모드입니다. 정상 확인이나 도움 요청 버튼으로 응답해주세요.', aiUsed: false };
    const result = await this.audioFn(bytes, { key: config.key, model: config.reportModel, transcriptionModel: config.transcriptionModel });
    this.monitor.activeIncident(input.incidentId); // API latency never extends the server deadline.
    if (result.error) return result;
    if (result.quote) {
      this.monitor.transcript(input.incidentId, 'worker', result.quote);
      this.monitor.response(input.incidentId, result.category, result.quote, 'ai');
    }
    return { ...result, aiUsed: true };
  }
  release(input) {
    const device = this.devices.get(input.deviceId);
    if (device) { device.armed = false; device.lastSeen = 0; }
    if (this.worker === input.deviceId) this.worker = null;
    return { released: true };
  }
  queueSms(item) {
    const candidates = this.snapshot().filter(device => device.armed && device.connected && device.smsReady);
    const device = candidates.find(device => device.id === this.worker) || candidates[0];
    if (!device) throw new Error('문자 발송 가능한 Android 단말이 없습니다. SIM·SMS 권한·앱 설정을 확인해주세요.');
    if (!/^\+?[0-9]{3,15}$/.test(item.contact.recipient)) throw new Error('SMS 발송에는 실제 전화번호를 등록해주세요.');
    Object.assign(item.contact, { status: 'device_pending', channel: 'android_sms', deliveryDevice: device.id }); this.monitor.emit();
  }
  async claimSms(input) {
    this.monitor.tick(); const item = this.monitor.incident;
    if (!item || item.resolvedAt || item.id !== input.incidentId || item.contact?.status !== 'device_pending' || item.contact.deliveryDevice !== input.deviceId || item.contact.idempotencyKey !== input.commandId) throw new Error('취소되었거나 이미 처리된 문자 요청입니다.');
    item.contact.status = 'sending'; item.contact.attemptedAt = this.now(); this.monitor.emit();
    await this.journal?.chain;
    return { accepted: true, recipient: item.contact.payload.recipient, message: item.contact.payload.message, commandId: item.contact.idempotencyKey };
  }
  smsResult(input) {
    const item = this.monitor.incidents.find(item => item.id === input.incidentId);
    if (!item?.contact || !['sending', 'unknown'].includes(item.contact.status) || item.contact.deliveryDevice !== input.deviceId || item.contact.idempotencyKey !== input.commandId) throw new Error('문자 결과의 사건 식별자가 올바르지 않습니다.');
    if (!Number.isInteger(input.part) || !Number.isInteger(input.total) || input.total < 1 || input.total > 100 || input.part < 0 || input.part >= input.total) throw new Error('문자 분할 결과 형식을 확인해주세요.');
    if (item.contact.smsTotal && item.contact.smsTotal !== input.total && input.sent === true) throw new Error('문자 분할 수가 일치하지 않습니다.');
    item.contact.smsTotal = input.total; item.contact.smsParts ||= {};
    item.contact.smsParts[input.part] = input.sent === true;
    if (input.sent === true && Object.keys(item.contact.smsParts).length < input.total) { this.monitor.emit(); return { recorded: true, pendingParts: true }; }
    Object.assign(item.contact, { status: input.sent === true ? 'sent' : 'failed', sentAt: input.sent === true ? this.now() : null,
      error: input.sent === true ? null : String(input.error || '기기 문자 전송 실패').slice(0, 200), receipt: input.sent === true ? 'Android SMS 발송 콜백 · 최종 수신 미확인' : null });
    this.monitor.log('contact', input.sent === true ? 'Android 문자 발송 성공 콜백 · 최종 수신/신고 접수는 별도입니다.' : 'Android 문자 발송 실패'); this.monitor.emit();
    return { recorded: true };
  }
}
