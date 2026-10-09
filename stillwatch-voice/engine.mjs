import { randomUUID } from 'node:crypto';
import { templateReport } from './report.mjs';

export class Monitor {
  constructor({ now = Date.now, onChange = () => {}, history = {} } = {}) {
    this.now = now;
    this.onChange = onChange;
    this.logs = history.logs || [];
    this.transcripts = history.transcripts || [];
    this.incidents = history.incidents || [];
    this.samples = [];
    this.headcount = 0;
    this.baseline = null;
    this.lastTick = this.now();
    this.unregisteredMotion = 0;
    this.state = 'IDLE';
    this.config = { room: '연구실 A', task: '야간 장비 점검', input: 'demo', mode: 'test', stillSeconds: 10, responseSeconds: 25 };
    this.incident = null;
    this.sensor = { source: 'demo', connected: true, motion: true, lastSeen: null, lastMotion: null };
    for (const item of this.incidents) {
      if (!item.resolvedAt && !item.interruptedAt) {
        item.interruptedAt = this.now(); item.result = 'interrupted'; item.voiceStatus = 'closed';
        if (item.contact?.status === 'sending') item.contact.status = 'unknown';
        else if (item.contact && !['sent', 'recorded', 'failed', 'unknown'].includes(item.contact.status)) item.contact.status = 'interrupted';
        this.log('restart', '서버 재시작 · 이전 미처리 사건은 이력에 보관됩니다. 현장 확인이 필요합니다.');
      }
    }
  }
  snapshot() {
    return { state: this.state, phase: this.state === 'WATCHING' ? (this.sensor.connected ? 'WORKING' : 'SENSOR_LOST') : ({ CHECKING: 'STILL_CHECK', ALERTED: 'RESCUE' }[this.state] || this.state),
      headcount: this.headcount, entryAt: this.entryAt, baseline: this.baseline, samples: this.samples, incidents: this.incidents,
      stillSince: this.stillSince, stateStartedAt: this.stateStartedAt, config: this.config, incident: this.incident,
      sensor: this.sensor, logs: this.logs, transcripts: this.transcripts, serverTime: this.now() };
  }
  log(type, message) {
    this.logs.push({ id: randomUUID(), at: this.now(), type, message });
    this.logs = this.logs.slice(-500);
  }
  emit() { this.onChange(this.snapshot()); }
  start(config) {
    if (!['IDLE', 'ENDED'].includes(this.state)) throw new Error('현재 작업을 먼저 종료해주세요.');
    if (!['test', 'live'].includes(config.mode)) throw new Error('실행 모드가 올바르지 않습니다.');
    for (const [key, min, max] of [['stillSeconds', 2, 120], ['responseSeconds', 10, 120]]) {
      if (!Number.isInteger(config[key]) || config[key] < min || config[key] > max) throw new Error('시간 설정 범위를 확인해주세요.');
    }
    this.config = { ...config, baselineEnabled: config.baselineEnabled === true,
      input: ['device', 'mqtt'].includes(config.input) ? config.input : 'demo', room: String(config.room || '연구실 A').slice(0, 80), task: String(config.task || '단독작업').slice(0, 160) };
    this.config.autoContact = config.autoContact === true;
    this.config.managerEnabled = config.managerEnabled === true;
    this.config.managerSeconds = Number(config.managerSeconds ?? 30);
    if (!Number.isInteger(this.config.managerSeconds) || this.config.managerSeconds < 2 || this.config.managerSeconds > 600) throw new Error('관리자 대기 시간은 2~600초입니다.');
    for (const key of ['address', 'floor', 'entrance', 'workerName', 'callback', 'recipient']) this.config[key] = String(config[key] || '').trim().slice(0, 200);
    if (this.config.autoContact && (!this.config.recipient || !/^(?:test|\+?[0-9]{3,15})$/.test(this.config.recipient))) throw new Error('연락처에는 전화번호 또는 시험용 test를 입력해주세요.');
    this.incident = null;
    this.samples = [];
    this.headcount = 1;
    this.entryAt = this.now();
    this.unregisteredMotion = 0;
    this.state = 'WATCHING';
    this.stillSince = null;
    const deviceInput = this.config.input !== 'demo';
    this.sensor = { source: this.config.input, connected: !deviceInput, motion: !deviceInput, score: null, lastSeen: deviceInput ? null : this.now(), lastMotion: deviceInput ? null : this.now() };
    this.resetBaseline();
    this.lastTick = this.now();
    this.log('start', `${this.config.room} 작업 등록 · ${this.config.mode === 'live' ? '실제 AI 음성' : '동작 시험'}`);
    this.emit();
  }
  resetBaseline() {
    this.baseline = this.config.baselineEnabled ? { status: 'collecting', observedMs: 0, longestIdleMs: 0, seconds: 60, threshold: this.config.stillSeconds } : null;
  }
  presence(action) {
    if (['IDLE', 'ENDED'].includes(this.state)) throw new Error('작업 등록이 필요합니다.');
    if (!['entry', 'exit'].includes(action)) throw new Error('출입 동작이 올바르지 않습니다.');
    if (['CHECKING', 'ALERTED'].includes(this.state)) throw new Error('진행 중인 경보를 먼저 확인해주세요.');
    if (this.headcount === 0 && action === 'entry') this.entryAt = this.now();
    this.headcount = Math.max(0, Math.min(99, this.headcount + (action === 'entry' ? 1 : -1)));
    this.state = this.headcount ? 'WATCHING' : 'EMPTY';
    this.stillSince = null;
    this.unregisteredMotion = 0;
    this.resetBaseline();
    this.lastTick = this.now();
    this.log('presence', `${action === 'entry' ? '입장' : '퇴장'} 버튼 · 등록 인원 ${this.headcount}명`);
    this.emit();
  }
  motion(motion, source = 'device', score = null) {
    if (typeof motion !== 'boolean') throw new Error('motion에는 true 또는 false를 넣어주세요.');
    if (['IDLE', 'ENDED'].includes(this.state)) throw new Error('작업 등록이 필요합니다.');
    if (!this.sensor.connected) this.log('sensor', '센서 신호가 복구되었습니다.');
    if (score !== null && (!Number.isFinite(score) || score < 0 || score > 1)) throw new Error('점수 범위는 0~1입니다.');
    this.updateBaseline();
    this.sensor = { ...this.sensor, source, connected: true, motion, score, lastSeen: this.now() };
    if (this.state === 'EMPTY') {
      this.unregisteredMotion = motion ? this.unregisteredMotion + 1 : 0;
      if (this.unregisteredMotion === 3) this.log('warning', '미등록 움직임 3회 관찰 · 입장 등록을 확인해주세요.');
      this.stillSince = null;
      this.sample(); this.emit(); return;
    }
    if (motion) {
      this.sensor.lastMotion = this.now();
      this.stillSince = null;
    } else if (this.stillSince === null) {
      this.stillSince = this.now();
      this.log('still', '움직임 정지 관찰을 시작합니다.');
    }
    this.sample(); this.emit();
  }
  disconnect() {
    if (['IDLE', 'ENDED'].includes(this.state)) throw new Error('작업 등록이 필요합니다.');
    if (this.sensor.connected) this.log('sensor', '감시 중단 · 센서 신호를 확인해주세요.');
    this.sensor.connected = false;
    // A loss of evidence must never count as observed stillness.
    this.stillSince = null;
    this.emit();
  }
  sample() {
    const at = this.now();
    if (this.samples.length && at - this.samples.at(-1).at < 200) return;
    this.samples.push({ at, motion: this.sensor.connected ? this.sensor.motion : null,
      score: this.sensor.connected ? (this.sensor.source === 'demo' ? (this.sensor.motion ? 0.85 : 0.08) : this.sensor.score) : null,
      source: this.sensor.source });
    this.samples = this.samples.filter(s => at - s.at <= 60000).slice(-301);
  }
  updateBaseline() {
    if (this.baseline?.status !== 'collecting' || this.state !== 'WATCHING' || !this.sensor.connected) return;
    if (this.stillSince !== null) this.baseline.longestIdleMs = Math.max(this.baseline.longestIdleMs, this.now() - this.stillSince);
  }
  tick() {
    if (['IDLE', 'ENDED'].includes(this.state)) return;
    const delta = Math.max(0, Math.min(500, this.now() - this.lastTick));
    this.lastTick = this.now();
    if (this.sensor.source !== 'demo' && this.sensor.connected && this.now() - this.sensor.lastSeen >= 5000) this.disconnect();
    this.updateBaseline();
    if (this.baseline?.status === 'collecting' && this.state === 'WATCHING' && this.sensor.connected) {
      this.baseline.observedMs += delta;
      if (this.baseline.observedMs >= 60000) {
        this.baseline.status = 'complete';
        this.baseline.threshold = Math.max(10, Math.min(30, Math.ceil(1.5 * this.baseline.longestIdleMs / 1000)));
        this.config.stillSeconds = this.baseline.threshold;
        this.log('baseline', `기준선 관찰 완료 · 최대 정지 ${(this.baseline.longestIdleMs / 1000).toFixed(1)}초 · 정지 기준 ${this.config.stillSeconds}초`);
      }
    }
    this.sample();
    if (this.state === 'WATCHING' && this.sensor.connected && this.stillSince !== null && this.now() - this.stillSince >= this.config.stillSeconds * 1000) {
      const at = this.now();
      this.incident = { id: randomUUID(), startedAt: at, deadline: at + this.config.responseSeconds * 1000, result: null,
        response: null, voiceStatus: 'idle', voiceClaimed: false, notice: null,
        room: this.config.room, task: this.config.task, headcount: this.headcount, entryAt: this.entryAt,
        lastMotion: this.sensor.lastMotion, stillDurationSeconds: Math.floor((at - this.stillSince) / 1000), transcripts: [] };
      Object.assign(this.incident, { address: this.config.address, floor: this.config.floor, entrance: this.config.entrance,
        workerName: this.config.workerName, callback: this.config.callback, sensorConnected: this.sensor.connected,
        managerEnabled: this.config.managerEnabled, managerFeedback: null,
        questionCount: 0, contact: this.config.autoContact ? { status: 'standby', recipient: this.config.recipient, idempotencyKey: randomUUID() } : null });
      this.incident.report = templateReport(this.incident);
      this.incidents.push(this.incident); this.incidents = this.incidents.slice(-100);
      this.state = 'CHECKING';
      this.log('check', '움직임 정지 조건 충족 · 응답 확인 시작');
      this.emit();
    }
    if (this.state === 'CHECKING' && this.now() >= this.incident.deadline) this.escalate('timeout', '응답 확인 제한시간 종료');
    if (this.state === 'ALERTED' && this.incident?.contact?.status === 'awaiting_manager' && this.now() >= this.incident.managerDeadline) this.queueContact('manager_timeout');
    for (const item of this.incidents) {
      if (item.contact?.status === 'sending' && item.contact.channel === 'android_sms' && this.now() - item.contact.attemptedAt >= 30000) {
        item.contact.status = 'unknown'; item.contact.error = '문자 발송 결과 콜백 미확인 · 자동 재시도 없음';
      }
    }
    if (this.incident?.contact?.status === 'device_pending' && this.now() - this.incident.contact.queuedAt >= 30000) {
      this.incident.contact.status = 'failed'; this.incident.contact.error = '문자 발송 단말 요청 수신 시간 초과';
    }
    this.emit();
  }
  activeIncident(id) {
    this.tick();
    if (!this.incident || this.incident.id !== id || this.state !== 'CHECKING') throw new Error('이미 종료되었거나 유효하지 않은 확인 요청입니다.');
  }
  claimVoice(id) {
    this.activeIncident(id);
    if (this.incident.voiceClaimed) throw new Error('이 사건의 음성 연결은 이미 시작되었습니다.');
    this.incident.voiceClaimed = true;
    this.incident.voiceStatus = 'connecting';
    this.emit();
  }
  voiceStatus(id, status) {
    if (!this.incident || this.incident.id !== id) return;
    if (!['connected', 'failed', 'closed'].includes(status)) throw new Error('음성 상태가 올바르지 않습니다.');
    this.incident.voiceStatus = status;
    this.log('voice', { connected: 'AI 음성 연결됨', failed: 'AI 음성 연결 실패 · 기존 경보와 제한시간 유지', closed: 'AI 음성 연결 종료' }[status]);
    this.emit();
  }
  transcript(id, role, text) {
    if (!this.incident || this.incident.id !== id) throw new Error('사건 식별자가 올바르지 않습니다.');
    if (!['worker', 'assistant'].includes(role) || typeof text !== 'string' || !text.trim() || text.length > 3000) throw new Error('대화 기록 형식이 올바르지 않습니다.');
    this.transcripts.push({ id: randomUUID(), at: this.now(), role, text, incidentId: id });
    this.incident.transcripts.push({ at: this.now(), role, text });
    if (role === 'assistant') this.incident.questionCount++;
    this.incident.transcripts = this.incident.transcripts.slice(-80);
    this.transcripts = this.transcripts.slice(-80);
    this.emit();
  }
  response(id, category, quote, source = 'ai') {
    this.activeIncident(id);
    if (!['responded', 'help_requested', 'unclear'].includes(category)) throw new Error('응답 분류가 올바르지 않습니다.');
    if (typeof quote !== 'string' || !quote.trim() || quote.length > 1000) throw new Error('작업자 발화 기록이 필요합니다.');
    this.incident.response = { category, quote, source, at: this.now() };
    this.log('response', `${source === 'test' ? '시험 입력' : source === 'button' ? '도움 요청 버튼' : 'AI 발화 해석'} · ${quote}`);
    if (category === 'help_requested') this.escalate('help_requested', '도움 요청 발화 · 담당자 확인 필요');
    else this.emit(); // Speech never clears an alarm or extends its deadline.
    return { recorded: true, alarmCleared: false, remainingSeconds: Math.max(0, Math.ceil((this.incident.deadline - this.now()) / 1000)) };
  }
  escalate(reason, message) {
    if (this.state !== 'CHECKING') return;
    this.state = 'ALERTED';
    this.incident.result = reason;
    this.incident.escalationReason = reason;
    this.incident.lastMotion = this.sensor.lastMotion;
    this.incident.notice = { id: randomUUID(), at: this.now(), room: this.config.room, task: this.config.task,
      lastMotion: this.sensor.lastMotion, reason, workerQuote: this.incident.response?.quote ?? null,
      sensorConnected: this.sensor.connected, delivery: 'local_dashboard' };
    this.incident.report = templateReport(this.incident);
    if (this.incident.contact) {
      if (this.config.managerEnabled && reason !== 'help_requested') {
        this.incident.managerDeadline = this.now() + this.config.managerSeconds * 1000;
        this.incident.contact.status = 'awaiting_manager';
      } else this.queueContact(reason, false);
    }
    this.log('alert', `${message} · 관리자 화면에 요청 등록`);
    this.emit();
  }
  queueContact(reason, emit = true) {
    const item = this.incident;
    if (!item?.contact || item.resolvedAt || !['standby', 'awaiting_manager'].includes(item.contact.status)) return;
    Object.assign(item.contact, { status: 'queued', reason, queuedAt: this.now() });
    this.log('contact', '자동 연락 조건 충족 · 최신 사실로 발송 준비');
    if (emit) this.emit();
  }
  managerFeedback(id, action, note = '') {
    this.tick();
    if (!this.incident || this.incident.id !== id || !this.config.managerEnabled) throw new Error('관리자 피드백 대상 사건이 없습니다.');
    if (!['seen', 'checking', 'normal', 'help'].includes(action)) throw new Error('관리자 피드백 형식을 확인해주세요.');
    if (typeof note !== 'string' || note.length > 500) throw new Error('확인 메모가 너무 깁니다.');
    if (action === 'normal' && !note.trim()) throw new Error('정상 확인 방법을 기록해주세요.');
    this.incident.managerFeedback = { action, note, at: this.now() };
    this.log('manager', `관리자 피드백: ${action}${note ? ' · ' + note : ''}`);
    if (action === 'normal') return this.confirm('manager');
    if (action === 'help') {
      if (this.state === 'CHECKING') this.escalate('help_requested', '관리자 구조 요청');
      else this.queueContact('manager_help');
    }
    this.emit();
  }
  confirm(who = 'worker') {
    this.tick();
    if (!['CHECKING', 'ALERTED'].includes(this.state)) throw new Error('확인할 경보가 없습니다.');
    if (this.state === 'ALERTED' && who !== 'manager' && !(this.incident.contact && ['awaiting_manager', 'queued', 'device_pending'].includes(this.incident.contact.status))) throw new Error('전달된 경보는 담당자 확인으로 처리해주세요.');
    if (this.incident.contact && ['standby', 'awaiting_manager', 'queued', 'device_pending'].includes(this.incident.contact.status)) {
      this.incident.contact.status = 'cancelled'; this.incident.contact.cancelledAt = this.now();
    }
    this.incident.result = who === 'manager' ? 'manager_confirmed' : 'button_confirmed';
    this.incident.resolvedAt = this.now();
    this.incident.resolvedBy = who;
    this.incident.voiceStatus = 'closed';
    this.state = 'WATCHING';
    this.stillSince = null;
    // Another stillness interval is needed, even if no fresh motion occurred.
    this.incident = null;
    this.log('confirm', who === 'manager' ? '담당자 확인 · 감시 재개' : '작업자 응답 버튼 확인 · 감시 재개');
    this.emit();
  }
  end() {
    if (['CHECKING', 'ALERTED'].includes(this.state)) throw new Error('현재 경보를 먼저 확인해주세요.');
    if (['IDLE', 'ENDED'].includes(this.state)) throw new Error('진행 중인 작업이 없습니다.');
    this.state = 'ENDED';
    this.headcount = 0;
    this.log('end', '작업 종료 · 출입 등록 해제');
    this.emit();
  }
}
