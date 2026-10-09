import { incidentFacts, templateReport, generateReport } from './report.mjs';

const core = facts => JSON.stringify(Object.fromEntries(Object.entries(facts).filter(([key]) => !['contact_reason', 'manager_status', 'manager_note'].includes(key))));
export function finalMessage(incident, report) {
  const facts = incidentFacts(incident);
  return `${report.report_119_ko}\n\n확인된 위치: ${facts.address || '주소 미등록'} / ${facts.floor || '층 미등록'} / ${facts.room}\n진입 방법: ${facts.entrance || '미등록'}\n작업자: ${facts.worker_name || '미등록'} · 회신: ${facts.callback || '미등록'}\n마지막 움직임: ${facts.last_motion_at ? new Date(facts.last_motion_at).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul', hour12: false }) : '미확인'}\n질문 기록: ${facts.question_count}회 · 실제 응답: ${facts.worker_quote || '기록 없음'}\n관리자 상태: ${facts.manager_status}${facts.manager_note ? ' / ' + facts.manager_note : ''}\n연락 사유: ${incident.contact.reason}\n자동 감지에 따른 확인 요청입니다. 의식·호흡 상태와 정확한 재실 위치는 확인되지 않았습니다.`;
}

export class ContactCoordinator {
  constructor({ monitor, settings, journal, fetchImpl = fetch, reportFn = generateReport, androidSender }) {
    Object.assign(this, { monitor, settings, journal, fetchImpl, reportFn, androidSender });
    this.jobs = new Map(); this.dispatching = new Set();
  }
  observe(state) {
    const item = state.incident;
    if (!item?.contact || item.resolvedAt) return;
    const signature = JSON.stringify(incidentFacts(item));
    let job = this.jobs.get(item.id);
    if (!job) { job = { signature: '', running: false }; this.jobs.set(item.id, job); }
    if (job.signature !== signature) {
      job.signature = signature;
      item.draft = { status: 'preparing', requestedAt: this.monitor.now(), factsSignature: signature };
      if (!job.running) this.prepare(item.id, job);
    }
    if (item.contact.status === 'queued' && !this.dispatching.has(item.id)) {
      this.dispatching.add(item.id);
      // Persist the queued event before any external side effect.
      Promise.resolve().then(() => this.dispatch(item)).catch(() => {
        item.contact.status = 'unknown'; item.contact.error = '전송 결과를 확인할 수 없습니다.'; this.monitor.emit();
      });
    }
  }
  async prepare(id, job) {
    job.running = true;
    const item = this.monitor.incident;
    if (!item || item.id !== id || item.resolvedAt) { job.running = false; return; }
    const snapshot = structuredClone(item), signature = job.signature;
    try {
      const config = await this.settings();
      const report = this.monitor.config.mode === 'live' && config.reportEnabled !== false ? await this.reportFn(snapshot, { key: config.key, model: config.reportModel, fetchImpl: this.fetchImpl }) : { ...templateReport(snapshot), status: 'test_mode' };
      const active = this.monitor.incident;
      if (active?.id === id && !active.resolvedAt && JSON.stringify(incidentFacts(active)) === signature) {
        active.report = report;
        active.draft = { status: report.source === 'ai' ? 'ready' : 'fallback', source: report.source, completedAt: this.monitor.now(), factsSignature: signature };
        this.monitor.emit();
      }
    } catch { /* The fixed template remains available. */ }
    finally {
      job.running = false;
      if (job.signature !== signature && this.monitor.incident?.id === id && !this.monitor.incident.resolvedAt) this.prepare(id, job);
    }
  }
  async dispatch(item) {
    await this.journal?.chain;
    const config = await this.settings();
    // Re-check cancellation and the event identity after asynchronous work.
    if (this.monitor.incident !== item || item.resolvedAt || item.contact.status !== 'queued') return;
    const fresh = incidentFacts(item);
    const report = item.report && core(item.report.fields_echo) === core(fresh) ? item.report : templateReport(item);
    const payload = { incidentId: item.id, idempotencyKey: item.contact.idempotencyKey,
      kind: 'contact_request', recipient: item.contact.recipient, message: finalMessage(item, report),
      facts: fresh, draftSource: report.source, createdAt: this.monitor.now() };
    item.contact.payload = payload;
    if (!['webhook', 'android_sms'].includes(config.deliveryMode) || this.monitor.config.mode !== 'live') {
      Object.assign(item.contact, { status: 'recorded', channel: 'local_outbox', recordedAt: this.monitor.now() });
      this.monitor.log('contact', '연락함에 기록 완료 · 외부 전송은 하지 않았습니다.'); this.monitor.emit(); return;
    }
    if (['112', '119', '911'].includes(payload.recipient) && this.monitor.config.input === 'demo') {
      Object.assign(item.contact, { status: 'failed', error: '시험 센서 입력으로 긴급번호에 발송할 수 없습니다.' }); this.monitor.emit(); return;
    }
    if (config.deliveryMode === 'android_sms') {
      try { this.androidSender(item); } catch(error) { Object.assign(item.contact, { status: 'failed', error: error.message }); this.monitor.emit(); }
      return;
    }
    if (!config.deliveryUrl || !config.deliveryToken) {
      Object.assign(item.contact, { status: 'failed', error: '전송 URL 또는 전송 토큰 미설정' }); this.monitor.emit(); return;
    }
    let url;
    try {
      url = new URL(config.deliveryUrl);
      if (url.username || url.password || !(url.protocol === 'https:' || (url.protocol === 'http:' && ['127.0.0.1', 'localhost'].includes(url.hostname)))) throw new Error();
    } catch { Object.assign(item.contact, { status: 'failed', error: '전송 URL은 HTTPS 또는 로컬 HTTP여야 합니다.' }); this.monitor.emit(); return; }
    item.contact.status = 'sending'; item.contact.attemptedAt = this.monitor.now(); this.monitor.emit();
    await this.journal?.chain;
    try {
      const response = await this.fetchImpl(url, { method: 'POST', redirect: 'error', signal: AbortSignal.timeout(5000),
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${config.deliveryToken}`, 'Idempotency-Key': payload.idempotencyKey }, body: JSON.stringify(payload) });
      const result = await response.json();
      if (!response.ok || result.accepted !== true) throw new Error('수신 확인 없음');
      Object.assign(item.contact, { status: 'sent', channel: 'webhook', sentAt: this.monitor.now(), receipt: String(result.receipt || '').slice(0, 200) });
      this.monitor.log('contact', '전송 수신기가 요청을 받았습니다. 최종 문자 수신·119 접수와는 별도입니다.');
    } catch {
      Object.assign(item.contact, { status: 'unknown', error: '전송 결과 미확인 · 중복 방지를 위해 자동 재시도하지 않습니다.' });
      this.monitor.log('contact', '연락 전송 결과 미확인 · 수신기 기록을 확인해주세요.');
    }
    this.monitor.emit();
  }
}
