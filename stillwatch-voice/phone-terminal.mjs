export class PhoneTerminal {
  constructor({ monitor, now = Date.now } = {}) {
    this.monitor = monitor; this.now = now; this.owner = null;
    monitor.phone = { armed: false, connected: false, foreground: false, microphoneReady: false, lastSeen: null };
  }
  assertOwner(id) {
    if (!this.owner || this.owner !== id || !this.monitor.phone.connected || !this.monitor.phone.armed || !this.monitor.phone.foreground) throw new Error('휴대폰에서 음성 준비 버튼을 눌러주세요.');
  }
  arm(id) {
    if (typeof id !== 'string' || !/^[a-f0-9-]{36}$/.test(id)) throw new Error('휴대폰 식별자를 확인해주세요.');
    if (this.monitor.config.voiceOutput !== 'phone' || ['IDLE', 'ENDED'].includes(this.monitor.state)) throw new Error('관리 화면에서 휴대폰 음성 모드로 작업을 시작해주세요.');
    if (this.owner && this.owner !== id && this.monitor.phone.connected) throw new Error('다른 휴대폰이 음성 단말로 연결되어 있습니다.');
    this.owner = id;
    Object.assign(this.monitor.phone, { armed: true, connected: true, foreground: true, microphoneReady: true, lastSeen: this.now() });
    this.monitor.log('phone', '휴대폰 음성 단말 준비 완료'); this.monitor.emit();
  }
  heartbeat(id, { foreground, microphoneReady }) {
    if (id !== this.owner) throw new Error('준비된 휴대폰이 아닙니다.');
    if (typeof foreground !== 'boolean' || typeof microphoneReady !== 'boolean') throw new Error('휴대폰 상태 형식이 올바르지 않습니다.');
    Object.assign(this.monitor.phone, { lastSeen: this.now(), foreground, microphoneReady });
    if (!foreground || !microphoneReady) this.unavailable('휴대폰 앱을 벗어나 음성 확인이 중단되었습니다.');
    return this.monitor.phone;
  }
  unavailable(message) {
    const changed = this.monitor.phone.connected || this.monitor.phone.armed;
    Object.assign(this.monitor.phone, { connected: false, armed: false, microphoneReady: false });
    if (changed) { this.monitor.log('phone', message); this.monitor.emit(); }
  }
  release(id) { if (id === this.owner) { this.unavailable('휴대폰 음성 단말 연결 해제'); this.owner = null; } }
  tick() {
    if (this.monitor.phone.connected && this.now() - this.monitor.phone.lastSeen >= 6000) this.unavailable('휴대폰 연결 끊김 · 기존 경보 제한시간 유지');
    if (['IDLE', 'ENDED'].includes(this.monitor.state) && this.monitor.phone.armed) this.release(this.owner);
  }
}
