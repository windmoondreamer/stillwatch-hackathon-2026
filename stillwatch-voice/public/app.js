const $ = id => document.getElementById(id);
let state, apiConfig, preparedStream, pc, channel, voiceIncident, pendingVoice, voiceToken = 0;
let errorTimer, testIncident, closingVoiceTimer, deviceConnected = false;
let historySignature = '', conversationSignature = '', timelineSignature = '';
const voices = new Map();
const states = {
  IDLE: ['READY', '작업을 등록해주세요', '정지 감지 후 응답 확인을 시작합니다.'],
  WATCHING: ['WATCHING', '작업 중 · 움직임 관찰', '센서 입력 또는 움직임 정지 시험을 기다립니다.'],
  EMPTY: ['EMPTY', '등록된 작업자가 없습니다', '움직임이 감지되면 입장 등록을 확인합니다.'],
  CHECKING: ['CHECK-IN', '응답을 확인하고 있습니다', '제한시간 안에 응답 버튼을 눌러주세요. 음성만으로 경보가 해제되지는 않습니다.'],
  ALERTED: ['ACTION NEEDED', '담당자 확인이 필요합니다', '확인 요청이 오른쪽 담당자 화면에 등록되었습니다.'],
  ENDED: ['FINISHED', '작업이 종료되었습니다', '기존 사건 기록은 보관됩니다. 새 작업을 등록할 수 있습니다.']
};
function fail(message) {
  $('error').textContent = message;
  $('error').hidden = false;
  clearTimeout(errorTimer);
  errorTimer = setTimeout(() => $('error').hidden = true, 8000);
}
async function post(url, value) {
  const res = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value) });
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || '요청 실패');
  return data;
}
async function config() {
  apiConfig = await (await fetch('/api/config')).json();
  $('api-status').textContent = apiConfig.keyConfigured ? 'API 키 설정됨 · 실제 음성 시험 가능' : 'API 키 미설정 · 동작 시험 가능';
  $('api-status').classList.toggle('ready', apiConfig.keyConfigured);
  $('mode').querySelector('[value="live"]').disabled = !apiConfig.keyConfigured;
}
function text(tag, value, className = '') { const el = document.createElement(tag); el.textContent = value; el.className = className; return el; }
function clock(at) { return at ? new Date(at).toLocaleTimeString('ko-KR', { hour12: false }) : '미확인'; }
function render() {
  if (!state) return;
  const [kicker, title, description] = states[state.state];
  $('state-kicker').textContent = kicker;
  $('state-title').textContent = title;
  $('state-description').textContent = state.sensor.connected ? description : '감시 중단 · 센서 신호를 확인해주세요. 진행 중인 경보 제한시간은 유지됩니다.';
  $('state-orb').className = 'state-orb ' + state.state.toLowerCase();
  const active = !['IDLE', 'ENDED'].includes(state.state);
  for (const el of $('setup-form').elements) el.disabled = active;
  $('mode').querySelector('[value="live"]').disabled = !apiConfig?.keyConfigured;
  $('mode-badge').textContent = active ? (state.config.mode === 'live' ? '실제 AI 음성' : '동작 시험 · AI 연결 없음') : '작업 대기';
  $('mode-badge').classList.toggle('live', active && state.config.mode === 'live');
  $('sensor-status').textContent = active ? (state.sensor.connected ? `${state.sensor.source === 'demo' ? '시험 입력' : '센서 입력'} · ${state.sensor.motion ? '움직임 있음' : '움직임 정지'}` : '센서 신호 없음 · 감시 중단') : '센서 시험 입력 대기';
  $('voice-status').textContent = active && state.config.mode === 'test' ? '음성 API 연결 없음' : ({ idle: '마이크 꺼짐', connecting: '음성 연결 중', connected: 'AI 음성 연결됨', failed: '음성 연결 실패', closed: '음성 연결 종료' }[state.incident?.voiceStatus] || '마이크 꺼짐');
  $('confirm').disabled = state.state !== 'CHECKING';
  $('end').disabled = !['WATCHING', 'EMPTY'].includes(state.state);
  $('entry').disabled = $('exit').disabled = !['WATCHING', 'EMPTY'].includes(state.state);
  $('manager-confirm').hidden = state.state !== 'ALERTED';
  for (const name of ['simulate-still', 'simulate-motion', 'simulate-disconnect']) $(name).disabled = !active || state.config.input !== 'demo';
  $('headcount').textContent = `${state.headcount}명`;
  $('threshold').textContent = `${state.config.stillSeconds}초`;
  const baseline = state.baseline;
  $('baseline-status').textContent = !active ? '입장 후 정지 기준선을 관찰할 수 있습니다.' : !baseline ? '고정 정지 기준 사용' : baseline.status === 'collecting' ? `기준선 관찰 ${Math.floor(baseline.observedMs / 1000)}/60초 · 관찰 중에도 경보는 작동합니다.` : `기준선 완료 · 최대 정지 ${(baseline.longestIdleMs / 1000).toFixed(1)}초 × 1.5 → ${baseline.threshold}초 (10~30초 제한)`;
  if (baseline && state.headcount > 1) $('baseline-status').textContent += ' · 여러 명일 때는 공간 전체의 기준선입니다.';
  $('chart-label').textContent = state.config.input === 'demo' ? '시험 점수는 예시 값입니다' : '실제 센서 점수 · 신호 없으면 공백';
  drawChart(); renderHistory();
  $('test-response').hidden = state.config.mode === 'live';
  $('test-note').hidden = state.config.mode === 'live';
  for (const btn of document.querySelectorAll('[data-response]')) btn.disabled = state.state !== 'CHECKING';
  $('conversation-label').textContent = state.config.mode === 'live' ? '실제 음성 대화 기록' : '시험용 입력 기록';
  const records = state.incident ? state.transcripts.filter(item => item.incidentId === state.incident.id) : [];
  const conversationKey = JSON.stringify([records, state.state]);
  if (conversationKey !== conversationSignature) {
  conversationSignature = conversationKey;
  $('conversation').replaceChildren();
  for (const record of records) {
    const bubble = text('div', '', `bubble ${record.role}`);
    bubble.append(text('div', record.role === 'worker' ? '작업자' : (state.config.mode === 'test' ? '시험 안내' : 'AI'), 'speaker'), text('p', record.text));
    $('conversation').append(bubble);
  }
  if (!records.length) {
    const empty = text('div', '', 'empty');
    empty.append(text('span', '◌', 'empty-symbol'), text('p', state.state === 'CHECKING' ? '응답을 기다리고 있습니다.' : '움직임이 멈추면 응답 확인이 시작됩니다.'));
    $('conversation').append(empty);
  }
  $('conversation').scrollTop = $('conversation').scrollHeight;
  }
  $('notice').replaceChildren();
  if (state.incident?.notice) {
    const notice = state.incident.notice;
    $('notice').append(text('p', notice.reason === 'help_requested' ? '작업자의 도움 요청' : '응답 확인 제한시간 종료', 'notice-title'));
    const grid = text('dl', '', 'notice-grid');
    const fields = [['공간', notice.room], ['작업', notice.task], ['등록 시각', clock(notice.at)], ['마지막 움직임', clock(notice.lastMotion)], ['작업자 발화', notice.workerQuote || '기록 없음'], ['센서 연결', notice.sensorConnected ? '연결됨' : '감시 중단']];
    for (const [label, value] of fields) grid.append(text('dt', label), text('dd', value));
    $('notice').append(grid);
    if (state.incident.report) {
      $('notice').append(text('p', state.incident.report.summary_ko, 'report-summary'), text('p', state.incident.report.source === 'ai' ? 'AI 요약 · 원본 사실은 이력에서 확인' : '기본 문구 · AI 요약 미사용', 'field-note'));
    }
  } else {
    const empty = text('div', '', 'empty');
    empty.append(text('span', '↗', 'empty-symbol'), text('p', '도움 요청 또는 제한시간 종료 시 확인 요청을 표시합니다.'));
    $('notice').append(empty);
  }
  const timelineKey = JSON.stringify(state.logs);
  if (timelineKey !== timelineSignature) {
  timelineSignature = timelineKey;
  $('timeline').replaceChildren();
  for (const record of [...state.logs].reverse()) {
    const row = text('li', '', record.type === 'alert' ? 'alert-log' : '');
    row.append(text('time', clock(record.at)), text('span', record.message));
    $('timeline').append(row);
  }
  if (!state.logs.length) $('timeline').append(text('li', '작업 등록을 기다리고 있습니다.', 'empty-log'));
  }
  updateTimer();
}
function updateTimer() {
  if (state) $('still-duration').textContent = state.sensor.connected && state.stillSince != null ? `${Math.max(0, Math.floor((Date.now() + (state.offset || 0) - state.stillSince) / 1000))}초` : '—';
  const checking = state?.state === 'CHECKING';
  $('countdown').hidden = !checking;
  if (!checking) { $('progress-fill').style.width = '0'; return; }
  const remaining = Math.max(0, state.incident.deadline - (Date.now() + (state.offset || 0)));
  $('seconds').textContent = Math.ceil(remaining / 1000);
  $('progress-fill').style.width = `${remaining / (state.config.responseSeconds * 1000) * 100}%`;
}
function drawChart() {
  const canvas = $('motion-chart'), ratio = window.devicePixelRatio || 1;
  const width = canvas.clientWidth, height = 150;
  if (!width) return;
  canvas.width = width * ratio; canvas.height = height * ratio;
  const ctx = canvas.getContext('2d'); ctx.scale(ratio, ratio);
  const left = 30, right = width - 8, top = 8, bottom = 112;
  ctx.font = '10px sans-serif'; ctx.fillStyle = '#8b978f'; ctx.strokeStyle = '#e8eeea';
  for (const score of [0, 0.5, 1]) { const y = bottom - score * (bottom - top); ctx.fillText(String(score), 0, y + 3); ctx.beginPath(); ctx.moveTo(left, y); ctx.lineTo(right, y); ctx.stroke(); }
  const at = state.serverTime, x = time => left + (time - at + 60000) / 60000 * (right - left);
  ctx.strokeStyle = '#258a6b'; ctx.lineWidth = 1.6; ctx.beginPath();
  let drawing = false;
  for (const sample of state.samples) {
    if (sample.score === null) { drawing = false; continue; }
    const px = x(sample.at), py = bottom - sample.score * (bottom - top);
    if (drawing) ctx.lineTo(px, py); else ctx.moveTo(px, py);
    drawing = true;
  }
  ctx.stroke();
  for (let index = 0; index < state.samples.length; index++) {
    const sample = state.samples[index];
    ctx.fillStyle = sample.motion === null ? '#dce2de' : sample.motion ? '#79b79e' : '#efce81';
    ctx.fillRect(x(sample.at), 122, Math.max(1, x(state.samples[index + 1]?.at || at) - x(sample.at)), 7);
  }
  ctx.fillStyle = '#8b978f'; ctx.fillText('60초 전', left, 146); ctx.fillText('현재', right - 20, 146);
}
function renderHistory() {
  const signature = JSON.stringify(state.incidents);
  if (signature === historySignature) return;
  historySignature = signature;
  const expanded = new Set([...$('incident-history').querySelectorAll('details[open]')].map(el => el.dataset.id));
  $('incident-history').replaceChildren();
  for (const item of [...state.incidents].reverse()) {
    const details = text('details', '', 'history-item'); details.dataset.id = item.id; details.open = expanded.has(item.id);
    const labels = { timeout: '응답 제한시간 종료', help_requested: '도움 요청', manager_confirmed: '담당자 처리', button_confirmed: '응답 버튼 확인', interrupted: '서버 재시작 · 미처리 사건' };
    details.append(text('summary', `${clock(item.startedAt)} · ${item.room} · ${labels[item.result] || '응답 확인 중'}`));
    if (item.report) {
      details.append(text('p', item.report.summary_ko), text('p', '신고 시 참고 초안 · 실제 신고 전 현장 정보를 확인해주세요.', 'field-note'), text('p', item.report.report_119_ko, 'report-summary'));
      const facts = text('pre', JSON.stringify(item.report.fields_echo, null, 2)); details.append(facts);
    }
    for (const record of item.transcripts || []) details.append(text('p', `${record.role === 'worker' ? '작업자' : '안내'}: ${record.text}`));
    if (item.resolvedAt) details.append(text('p', `${clock(item.resolvedAt)} · ${item.resolvedBy === 'manager' ? '담당자' : '작업자'} 확인`, 'field-note'));
    if (item.interruptedAt) details.append(text('p', '서버 재시작으로 감시가 중단되었습니다. 현장 상태와 이전 사건을 확인한 뒤 새 작업을 등록해주세요.', 'field-note'));
    $('incident-history').append(details);
  }
  if (!state.incidents.length) $('incident-history').append(text('p', '아직 확인 요청이 없습니다.', 'field-note'));
}
function closeVoice(releaseMicrophone = false, markClosed = true) {
  const closingId = voiceIncident;
  if (markClosed && closingId && state?.incident?.id === closingId && ['connecting', 'connected'].includes(state.incident.voiceStatus)) post('/api/voice-status', { incidentId: closingId, status: 'closed' }).catch(() => {});
  clearTimeout(closingVoiceTimer);
  closingVoiceTimer = null;
  voiceToken++;
  if (pendingVoice) pendingVoice.abort();
  pendingVoice = null;
  channel?.close();
  pc?.close();
  preparedStream?.getTracks().forEach(track => { if (releaseMicrophone) track.stop(); else track.enabled = false; });
  if (releaseMicrophone) preparedStream = null;
  pc = null; channel = null; voiceIncident = null;
  $('remote-audio').srcObject = null;
  $('play-audio').hidden = true;
}
function sendEvent(event) { if (channel?.readyState === 'open') channel.send(JSON.stringify(event)); }
async function recordTranscript(id, role, value) {
  if (!value?.trim()) return;
  try { await post('/api/transcript', { incidentId: id, role, text: value.slice(0, 3000) }); }
  catch (error) { fail(error.message); }
}
async function onVoiceEvent(id, event) {
  if (voiceIncident !== id) return;
  if (event.type === 'conversation.item.input_audio_transcription.completed') await recordTranscript(id, 'worker', event.transcript);
  if (event.type === 'response.output_audio_transcript.done') await recordTranscript(id, 'assistant', event.transcript);
  if (event.type === 'error') fail(event.error?.message || '음성 대화 오류');
  if (event.type === 'response.function_call_arguments.done') {
    if (event.name !== 'report_worker_response' || voices.has(event.call_id)) return;
    voices.set(event.call_id, true);
    try {
      const args = JSON.parse(event.arguments);
      const result = await post('/api/response', { incidentId: id, category: args.category, quote: args.quote });
      sendEvent({ type: 'conversation.item.create', item: { type: 'function_call_output', call_id: event.call_id, output: JSON.stringify(result) } });
      sendEvent({ type: 'response.create' });
    } catch (error) {
      sendEvent({ type: 'conversation.item.create', item: { type: 'function_call_output', call_id: event.call_id, output: JSON.stringify({ recorded: false, error: error.message }) } });
    }
  }
}
async function startVoice(id) {
  if (voiceIncident === id || state.incident?.voiceClaimed) return;
  voiceIncident = id;
  const token = ++voiceToken;
  try {
    if (!preparedStream || !preparedStream.getAudioTracks().some(track => track.readyState === 'live')) throw new Error('마이크 사용을 위해 작업을 종료한 뒤 실제 음성 모드로 다시 시작해주세요.');
    preparedStream.getAudioTracks().forEach(track => track.enabled = true);
    const connection = new RTCPeerConnection();
    pc = connection;
    connection.ontrack = event => {
      $('remote-audio').srcObject = event.streams[0];
      $('remote-audio').play().catch(() => $('play-audio').hidden = false);
    };
    connection.onconnectionstatechange = () => {
      if (['failed', 'disconnected'].includes(connection.connectionState) && voiceIncident === id) {
        post('/api/voice-status', { incidentId: id, status: 'failed' }).catch(() => {});
        fail('음성 연결이 끊겼습니다. 응답 버튼과 제한시간 경보는 유지됩니다.');
        closeVoice(false, false);
      }
    };
    preparedStream.getTracks().forEach(track => connection.addTrack(track, preparedStream));
    const dc = connection.createDataChannel('oai-events');
    channel = dc;
    dc.onmessage = event => { try { onVoiceEvent(id, JSON.parse(event.data)).catch(error => fail(error.message)); } catch { fail('음성 이벤트 형식을 확인할 수 없습니다.'); } };
    dc.onopen = () => {
      if (voiceIncident !== id) return;
      post('/api/voice-status', { incidentId: id, status: 'connected' }).catch(() => {});
      sendEvent({ type: 'response.create', response: { instructions: '지금 첫 확인 질문을 한국어로 하세요. 아직 작업자가 말하지 않았으므로 도구를 호출하지 마세요.' } });
    };
    const offer = await connection.createOffer();
    await connection.setLocalDescription(offer);
    pendingVoice = new AbortController();
    const res = await fetch('/api/session', { method: 'POST', headers: { 'Content-Type': 'application/sdp', 'X-Incident-Id': id }, body: offer.sdp, signal: pendingVoice.signal });
    if (!res.ok) throw new Error((await res.json()).error);
    const answer = await res.text();
    if (token !== voiceToken || state.state !== 'CHECKING' || state.incident.id !== id) return;
    await connection.setRemoteDescription({ type: 'answer', sdp: answer });
  } catch (error) {
    if (token !== voiceToken) return;
    post('/api/voice-status', { incidentId: id, status: 'failed' }).catch(() => {});
    fail(error.message);
    closeVoice(false, false);
  }
}
async function testPrompt(id) {
  if (testIncident === id) return;
  testIncident = id;
  try { await post('/api/transcript', { incidentId: id, role: 'assistant', text: '[시험 안내] 움직임이 감지되지 않습니다. 도움이 필요하신가요?' }); }
  catch (error) { fail(error.message); }
}
$('setup-form').addEventListener('submit', async event => {
  event.preventDefault();
  $('start').disabled = true;
  try {
    if ($('mode').value === 'live') {
      preparedStream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true }, video: false });
      preparedStream.getAudioTracks().forEach(track => track.enabled = false);
    }
    await post('/api/start', { room: $('room').value, task: $('task').value, mode: $('mode').value, input: $('sensor-input').value,
      stillSeconds: Number($('still-seconds').value), responseSeconds: Number($('response-seconds').value), baselineEnabled: $('baseline-enabled').checked });
  } catch (error) { closeVoice(true); fail(error.message); $('start').disabled = false; }
});
function click(id, action) { $(id).addEventListener('click', () => action().catch(error => fail(error.message))); }
click('simulate-still', () => post('/api/demo-motion', { motion: false }));
click('simulate-motion', () => post('/api/demo-motion', { motion: true }));
click('simulate-disconnect', () => post('/api/disconnect', {}));
click('confirm', () => post('/api/confirm', { who: 'worker' }));
click('manager-confirm', () => post('/api/confirm', { who: 'manager' }));
click('end', () => post('/api/end', {}));
click('entry', () => post('/api/presence', { action: 'entry' }));
click('exit', () => post('/api/presence', { action: 'exit' }));
click('refresh-config', config);
$('key-form').addEventListener('submit', async event => {
  event.preventDefault();
  try { await post('/api/config/key', { key: $('api-key').value.trim() }); await config(); }
  catch (error) { fail(error.message); }
  finally { $('api-key').value = ''; }
});
$('mqtt-form').addEventListener('submit', async event => {
  event.preventDefault();
  try { await post('/api/mqtt/connect', { url: $('mqtt-url').value.trim(), deviceId: $('mqtt-device').value.trim(), controller: $('mqtt-controller').value.trim() }); }
  catch (error) { fail(error.message); }
});
click('usb-status', async () => {
  const result = await post('/api/device/usb-status', {});
  $('device-message').textContent = `${result.device?.[0] || 'ESP'} ${result.device?.[1] || ''} · ${result.state === 4 ? 'Wi-Fi 연결됨' : 'Wi-Fi 설정 대기'}`;
});
click('device-connect', () => post('/api/device/connect', { ip: $('device-ip').value.trim() }));
$('wifi-form').addEventListener('submit', async event => {
  event.preventDefault();
  $('wifi-submit').disabled = true;
  $('wifi-submit').textContent = 'Wi-Fi 연결 중…';
  try {
    const result = await post('/api/device/provision', { ssid: $('wifi-ssid').value, password: $('wifi-password').value });
    $('device-message').textContent = result.ok ? 'Wi-Fi 설정 완료 · 센서 연결 상태를 확인해주세요.' : 'Wi-Fi 설정 실패';
  } catch (error) { fail(error.message); }
  finally { $('wifi-password').value = ''; $('wifi-submit').disabled = false; $('wifi-submit').textContent = 'USB로 Wi-Fi 설정'; }
});
click('play-audio', async () => { await $('remote-audio').play(); $('play-audio').hidden = true; });
const examples = { responded: '가만히 작업 중이에요', unclear: '네?', help_requested: '도와주세요' };
for (const btn of document.querySelectorAll('[data-response]')) btn.addEventListener('click', async () => {
  try {
    const category = btn.dataset.response;
    await post('/api/test-response', { incidentId: state.incident.id, category, quote: examples[category] });
  } catch (error) { fail(error.message); }
});
$('download').addEventListener('click', () => {
  if (!state) return;
  const blob = new Blob([JSON.stringify({ exportedAt: new Date().toISOString(), ...state }, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url; link.download = `stillwatch-${new Date().toISOString().replace(/[:.]/g, '-')}.json`; link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
});
const events = new EventSource('/api/events');
function renderDevice(data) {
  if (data.connected && !deviceConnected && state && ['IDLE', 'ENDED'].includes(state.state)) $('sensor-input').value = 'device';
  deviceConnected = data.connected;
  $('device-message').textContent = data.message;
  if (data.ip) $('device-ip').value = data.ip;
  $('device-score').textContent = data.score !== null ? `움직임 점수 ${data.score.toFixed(3)} · ${data.motion ? '움직임 있음' : '움직임 없음'}` : '유효한 움직임 데이터 대기';
}
events.addEventListener('device', event => renderDevice(JSON.parse(event.data)));
events.addEventListener('mqtt', event => {
  const data = JSON.parse(event.data);
  $('mqtt-status').textContent = `${data.message} · 버튼 장치 ${data.controllerOnline ? '연결됨' : '연결 없음'}${data.outputFailed ? ' · 출력 확인 실패' : ''}`;
});
events.onmessage = event => {
  state = JSON.parse(event.data);
  state.offset = state.serverTime - Date.now();
  render();
  if (state.state === 'CHECKING') {
    if (state.config.mode === 'test') testPrompt(state.incident.id);
    else startVoice(state.incident.id);
  } else if (state.state === 'ALERTED' && voiceIncident) {
    // Register the alert immediately and stop input. Allow the tool result and a
    // short acknowledgement to finish without extending the alarm deadline.
    preparedStream?.getAudioTracks().forEach(track => track.enabled = false);
    if (!closingVoiceTimer) closingVoiceTimer = setTimeout(() => closeVoice(), 5000);
  } else if (voiceIncident || (preparedStream && ['IDLE', 'ENDED'].includes(state.state))) closeVoice(['IDLE', 'ENDED'].includes(state.state));
};
events.onerror = () => fail('로컬 서버 연결이 끊겼습니다. 서버 실행 상태를 확인해주세요.');
setInterval(updateTimer, 200);
config().catch(error => fail(error.message));
window.addEventListener('beforeunload', () => closeVoice(true));
window.addEventListener('resize', () => { if (state) drawChart(); });
