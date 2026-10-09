const $ = id => document.getElementById(id);
let state, config, stream, peer, channel, voiceId, closedTimer;
const handled = new Set();
const call = async (url, data) => {
  const response = await fetch(url, data === undefined ? {} : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) });
  const value = await response.json(); if (!response.ok) throw new Error(value.error || '요청 실패'); return value;
};
function error(e) { $('error').textContent = e.message || String(e); }
function click(id, action) { $(id).onclick = () => Promise.resolve().then(action).catch(error); }
async function loadConfig() {
  config = await call('/api/config'); $('api-status').textContent = `${config.keyConfigured ? 'OpenAI 키 설정됨' : 'OpenAI 키 미설정'} · ${config.externalDelivery ? '전송 수신기 연결 설정됨' : '외부 전송 미설정 · 연락함만 기록'}`;
  $('mode').querySelector('[value=live]').disabled = !config.keyConfigured;
  $('phones').textContent = config.phoneDevices.map(d => `${d.role}: ${d.connected && d.armed ? '대기 중' : '연결 끊김'}${d.lastError ? ' / ' + d.lastError : ''}`).join(' · ') || 'Android 단말 미연결';
}
$('key-form').onsubmit = async e => { e.preventDefault(); try { await call('/api/config/key', { key: $('key').value }); $('key').value = ''; await loadConfig(); } catch(e) { error(e); } };
$('setup').onsubmit = async e => {
  e.preventDefault(); $('error').textContent = '';
  try {
    if ($('mode').value === 'live' && $('voice').value === 'browser') {
      stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
      stream.getTracks().forEach(t => t.enabled = false);
    }
    await call('/api/start', { room: $('room').value, task: $('task').value, input: $('input').value, mode: $('mode').value,
      voiceOutput: $('voice').value, stillSeconds: Number($('still').value), responseSeconds: Number($('response').value), baselineEnabled: false,
      autoContact: true, managerEnabled: $('manager-enabled').checked, managerSeconds: Number($('manager-seconds').value),
      address: $('address').value, floor: $('floor').value, entrance: $('entrance').value, workerName: $('worker').value, callback: $('callback').value, recipient: $('recipient').value });
  } catch(e) { closeVoice(true); error(e); }
};
click('end', () => call('/api/end', {}));
click('still-button', () => call('/api/demo-motion', { motion: false }));
click('motion-button', () => call('/api/demo-motion', { motion: true }));
click('disconnect-button', () => call('/api/disconnect', {}));
click('normal', () => call('/api/confirm', { who: 'worker', incidentId: state.incident?.id }));
click('help', () => call('/api/help', { incidentId: state.incident?.id }));
click('unclear', () => call('/api/test-response', { incidentId: state.incident?.id, category: 'unclear', quote: $('quote').value }));
click('device-connect', () => call('/api/device/connect', { ip: $('device-ip').value }));
click('pair', async () => { const value = await call('/api/mobile/pairing'); $('pairing').textContent = 'Android 앱의 연결 코드에 입력:\n' + value.token; });
for (const button of document.querySelectorAll('[data-feedback]')) button.onclick = () => call('/api/manager-feedback', { incidentId: state.incident?.id, action: button.dataset.feedback, note: $('manager-note').value }).catch(error);
const send = value => { if (channel?.readyState === 'open') channel.send(JSON.stringify(value)); };
function closeVoice(release = false) {
  clearTimeout(closedTimer); closedTimer = null; peer?.close(); channel?.close(); peer = channel = null; voiceId = null;
  stream?.getTracks().forEach(t => { if (release) t.stop(); else t.enabled = false; }); if (release) stream = null;
  $('remote-audio').srcObject = null;
}
async function voice(id) {
  if (voiceId === id || state.incident.voiceClaimed) return;
  voiceId = id;
  try {
    if (!stream) throw new Error('마이크 준비가 필요합니다. 브라우저 음성 모드로 다시 작업을 시작해주세요.');
    stream.getTracks().forEach(t => t.enabled = true);
    const connection = new RTCPeerConnection(); peer = connection; stream.getTracks().forEach(t => connection.addTrack(t, stream));
    connection.ontrack = e => { $('remote-audio').srcObject = e.streams[0]; $('remote-audio').play().catch(() => $('play-audio').hidden = false); };
    const dc = connection.createDataChannel('oai-events'); channel = dc;
    dc.onopen = () => { call('/api/voice-status', { incidentId: id, status: 'connected' }).catch(error); send({ type: 'response.create', response: { instructions: '괜찮으신가요? 문제가 있거나 도움이 필요하신가요? 라고 첫 질문을 하세요. 작업자가 말하기 전 도구를 호출하지 마세요.' } }); };
    dc.onmessage = async e => {
      try {
        const event = JSON.parse(e.data);
        if (event.type === 'conversation.item.input_audio_transcription.completed' || event.type === 'response.output_audio_transcript.done') await call('/api/transcript', { incidentId: id, role: event.type.startsWith('conversation') ? 'worker' : 'assistant', text: event.transcript });
        if (event.type === 'response.function_call_arguments.done' && event.name === 'report_worker_response' && !handled.has(event.call_id)) {
          handled.add(event.call_id); const args = JSON.parse(event.arguments);
          const result = await call('/api/response', { incidentId: id, ...args });
          send({ type: 'conversation.item.create', item: { type: 'function_call_output', call_id: event.call_id, output: JSON.stringify(result) } }); send({ type: 'response.create' });
        }
      } catch(e) { error(e); }
    };
    const offer = await connection.createOffer(); await connection.setLocalDescription(offer);
    const response = await fetch('/api/session', { method: 'POST', headers: { 'Content-Type': 'application/sdp', 'X-Incident-Id': id }, body: offer.sdp });
    if (!response.ok) throw new Error((await response.json()).error);
    const sdp = await response.text(); if (voiceId !== id || state.state !== 'CHECKING') return;
    await connection.setRemoteDescription({ type: 'answer', sdp });
  } catch(e) { await call('/api/voice-status', { incidentId: id, status: 'failed' }).catch(() => {}); closeVoice(); error(e); }
}
click('play-audio', async () => { await $('remote-audio').play(); $('play-audio').hidden = true; });
function render() {
  if (!state) return;
  const item = state.incident, now = Date.now() + (state.offset || 0);
  $('status').textContent = `${state.state} · ${state.sensor.connected ? state.sensor.motion ? '움직임 있음' : '움직임 미감지' : '감시 불가'}${state.state === 'CHECKING' ? ' · 확인 ' + Math.max(0, Math.ceil((item.deadline - now) / 1000)) + '초 남음' : ''}`;
  $('manager-status').textContent = item?.contact?.status === 'awaiting_manager' ? `관리자 피드백 대기 ${Math.max(0, Math.ceil((item.managerDeadline - now) / 1000))}초 · 읽음/확인 중은 마감을 연장하지 않습니다.` : item?.managerFeedback ? JSON.stringify(item.managerFeedback) : '피드백 없음';
  $('draft-status').textContent = item?.draft ? `${item.draft.status} · ${item.report?.source === 'ai' ? 'AI 생성' : '기본 양식'}` : '미발송 초안';
  $('draft').textContent = item?.report?.report_119_ko || '사건 발생 시 관리자 응답 전에 작성합니다.';
  $('contact').textContent = item?.contact ? JSON.stringify(item.contact, null, 2) : '연락 대기';
  $('conversation').textContent = (item?.transcripts || []).map(t => `${t.role}: ${t.text}`).join('\n');
  $('logs').textContent = state.logs.slice(-12).map(l => new Date(l.at).toLocaleTimeString('ko-KR') + ' ' + l.message).join('\n');
  $('history').textContent = state.incidents.slice(-8).map(i => `${new Date(i.startedAt).toLocaleTimeString('ko-KR')} ${i.room} · ${i.result || '확인 중'} · 연락 ${i.contact?.status || '없음'}`).join('\n');
  for (const control of $('setup').elements) control.disabled = !['IDLE', 'ENDED'].includes(state.state);
  $('normal').disabled = !item || !['CHECKING', 'ALERTED'].includes(state.state) || (state.state === 'ALERTED' && !['awaiting_manager', 'queued', 'device_pending'].includes(item.contact?.status));
  $('help').disabled = $('unclear').disabled = state.state !== 'CHECKING';
  $('unclear').hidden = state.config.mode === 'live';
  for (const button of document.querySelectorAll('[data-feedback]')) button.disabled = !item || !state.config.managerEnabled;
  for (const id of ['still-button', 'motion-button', 'disconnect-button']) $(id).disabled = ['IDLE', 'ENDED'].includes(state.state) || state.config.input !== 'demo';
  $('end').disabled = !['WATCHING', 'EMPTY'].includes(state.state);
  if (state.state === 'CHECKING' && state.config.mode === 'live' && state.config.voiceOutput === 'browser') voice(item.id);
  if (state.state === 'ALERTED' && voiceId) { stream?.getTracks().forEach(t => t.enabled = false); if (!closedTimer) closedTimer = setTimeout(() => closeVoice(), 3000); }
  if (!['CHECKING', 'ALERTED'].includes(state.state) && voiceId) closeVoice(['IDLE', 'ENDED'].includes(state.state));
}
const events = new EventSource('/api/events');
events.onmessage = e => { state = JSON.parse(e.data); state.offset = state.serverTime - Date.now(); $('connection').textContent = '서버 연결됨'; render(); };
events.addEventListener('device', e => { $('device-status').textContent = JSON.parse(e.data).message; });
events.onerror = () => $('connection').textContent = '서버 연결 끊김';
setInterval(render, 1000); setInterval(() => loadConfig().catch(error), 5000);
loadConfig().catch(error); addEventListener('beforeunload', () => closeVoice(true));
