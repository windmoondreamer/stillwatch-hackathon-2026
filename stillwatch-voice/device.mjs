import { spawn } from 'node:child_process';
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

// Match ESPectre's official CLI transport. Stock firmware requires this Origin.
const directHeaders = { Origin: 'https://test.espectre.dev', Accept: 'application/json', 'Cache-Control': 'no-store' };

export function deviceAddress(ip) {
  if (typeof ip !== 'string' || !/^\d{1,3}(\.\d{1,3}){3}$/.test(ip)) throw new Error('장치의 IPv4 주소를 입력해주세요.');
  const parts = ip.split('.').map(Number);
  if (parts.some(part => part > 255) || !parts.every((part, index) => String(part) === ip.split('.')[index])) throw new Error('IPv4 주소를 확인해주세요.');
  if (!(parts[0] === 10 || (parts[0] === 192 && parts[1] === 168) || (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31))) throw new Error('같은 네트워크의 사설 IPv4 주소를 입력해주세요.');
  return `http://${ip}:62587/espectre/v1`;
}
export class Device {
  constructor({ root, onMotion, onUnavailable, onChange, fetchImpl = fetch } ) {
    this.root = root; this.onMotion = onMotion; this.onUnavailable = onUnavailable; this.onChange = onChange; this.fetch = fetchImpl;
    this.state = { port: 'COM4', ip: null, connected: false, ready: false, score: null, motion: null, lastSeen: null,
      device: null, message: 'ESPectre 연결 대기 · USB 상태 또는 장치 IP를 확인해주세요.' };
    this.serialBusy = false; this.generation = 0;
  }
  snapshot() { return this.state; }
  change(values) { this.state = { ...this.state, ...values }; this.onChange?.(this.state); }
  async serial(command, values = {}) {
    if (this.serialBusy) throw new Error('USB 설정이 진행 중입니다. 완료를 기다려주세요.');
    this.serialBusy = true;
    try {
      const python = process.env.STILLWATCH_PYTHON || 'C:\\Users\\User\\.cache\\codex-runtimes\\codex-primary-runtime\\dependencies\\python\\python.exe';
      const result = await new Promise((resolve, reject) => {
        const child = spawn(python, [path.join(this.root, 'hardware', 'improv.py')], { cwd: this.root, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
        let output = '';
        const timeout = setTimeout(() => { child.kill(); reject(new Error('USB 설정 제한시간을 초과했습니다.')); }, 45000);
        child.on('error', () => { clearTimeout(timeout); reject(new Error('USB 설정 도구를 실행할 수 없습니다.')); });
        child.stdout.on('data', chunk => output += chunk.toString('utf8'));
        child.stderr.on('data', () => {});
        child.on('close', () => {
          clearTimeout(timeout);
          try {
            const data = JSON.parse(output.trim());
            if (!data.ok) reject(new Error(data.error)); else resolve(data);
          } catch { reject(new Error('USB 설정 응답을 읽을 수 없습니다.')); }
        });
        child.stdin.end(JSON.stringify({ command, port: this.state.port, ...values }));
      });
      if (result.device) this.change({ device: { firmwareName: result.device[0], firmware: result.device[1], chip: result.device[2], name: result.device[3] }, message: result.state === 4 ? 'Wi-Fi 연결됨' : 'Wi-Fi 설정 대기' });
      let address;
      for (const value of result.urls || []) {
        try {
          const url = new URL(value);
          const candidates = [url.hostname, url.searchParams.get('target'), url.searchParams.get('host'), url.searchParams.get('ip'), url.searchParams.get('device')];
          const ip = candidates.find(candidate => candidate && /^\d+(\.\d+){3}$/.test(candidate));
          if (ip) { address = ip; break; }
        } catch { /* Unknown optional URL formats do not supply an address. */ }
      }
      if (address) {
        try { await this.connect(address); }
        catch { this.change({ ip: address, message: 'Wi-Fi 설정 완료 · PC와 같은 네트워크인지 확인하고 IP 연결을 눌러주세요.' }); }
      }
      return result;
    } catch (error) {
      this.change({ message: error.message });
      throw error;
    } finally { this.serialBusy = false; }
  }
  async resume() {
    try {
      const saved = JSON.parse(await readFile(path.join(this.root, 'hardware', 'device.json'), 'utf8'));
      if (saved.ip) await this.connect(saved.ip);
    } catch { /* No saved device, or no device on this network. */ }
  }
  async connect(ip) {
    const base = deviceAddress(ip);
    const response = await this.fetch(`${base}/capabilities`, { headers: directHeaders, signal: AbortSignal.timeout(4000), redirect: 'error' });
    if (!response.ok) throw new Error(`ESPectre 장치 API 연결 실패 (${response.status}).`);
    const caps = await response.json();
    if (caps.protocol_version !== '1.0' || !caps.events?.includes('motion') || !caps.resources?.includes('sensing')) throw new Error('지원하는 ESPectre 3.0 장치가 아닙니다.');
    // Windows hotspot can block inbound ping. The official external UDP marker
    // gives the station a steady stream of downlink frames for CSI capture.
    if (ip.startsWith('192.168.137.') && caps.operations?.some(item => item.name === 'update_sensing')) {
      const update = await this.fetch(`${base}/sensing`, { method: 'PATCH', headers: { ...directHeaders, 'Content-Type': 'application/json' },
        body: JSON.stringify({ traffic_generator_mode: 'external', detector: 'high_accuracy' }), signal: AbortSignal.timeout(4000), redirect: 'error' });
      if (!update.ok || !(await update.json()).accepted) throw new Error('PC 핫스팟의 센서 입력 설정에 실패했습니다.');
    }
    this.stopTraffic(); this.controller?.abort(); clearTimeout(this.reconnectTimer);
    const generation = ++this.generation;
    this.change({ ip, connected: false, ready: false, score: null, motion: null, message: '장치 연결 중' });
    await writeFile(path.join(this.root, 'hardware', 'device.json'), JSON.stringify({ ip, port: this.state.port }, null, 2));
    this.stream(base, generation);
  }
  async stream(base, generation) {
    if (generation !== this.generation) return;
    const controller = new AbortController(); this.controller = controller;
    const timeout = setTimeout(() => controller.abort(), 6000);
    let watchdog;
    try {
      const requests = await Promise.all(['device', 'sensing'].map(async resource => {
        const res = await this.fetch(`${base}/${resource}`, { headers: directHeaders, signal: controller.signal, redirect: 'error' });
        if (!res.ok) throw new Error('장치 상태 확인 실패');
        return res.json();
      }));
      if (generation !== this.generation) return;
      const [device, sensing] = requests;
      this.sensing = sensing;
      this.traffic(sensing);
      this.lastDeviceTime = undefined;
      const res = await this.fetch(`${base}/events`, { headers: { ...directHeaders, Accept: 'text/event-stream' }, signal: controller.signal, redirect: 'error' });
      clearTimeout(timeout);
      if (!res.ok || !res.headers.get('content-type')?.includes('text/event-stream')) throw new Error('센서 이벤트 연결 실패');
      const ready = this.isReady(sensing);
      this.change({ connected: true, ready, device, message: ready ? '움직임 감지 중' : '센서 보정 또는 준비 중' });
      if (!ready) this.onUnavailable?.();
      let buffer = '', lastData = Date.now();
      watchdog = setInterval(() => { if (Date.now() - lastData > 15000) controller.abort(); }, 1000);
      const decoder = new TextDecoder();
      for await (const chunk of res.body) {
        if (generation !== this.generation) return;
        lastData = Date.now();
        buffer = (buffer + decoder.decode(chunk, { stream: true })).replace(/\r\n/g, '\n');
        if (buffer.length > 131072) throw new Error('센서 이벤트 버퍼 초과');
        let index;
        while ((index = buffer.indexOf('\n\n')) !== -1) {
          const frame = buffer.slice(0, index); buffer = buffer.slice(index + 2);
          const name = frame.split('\n').find(line => line.startsWith('event:'))?.slice(6).trim();
          const data = frame.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5).trim()).join('\n');
          if (!data) continue;
          this.event(name, JSON.parse(data));
        }
      }
      throw new Error('센서 이벤트 연결 종료');
    } catch {
      if (generation !== this.generation) return;
      this.change({ connected: false, ready: false, score: null, motion: null, message: '센서 연결 중단 · 재연결 중' });
      this.stopTraffic();
      this.onUnavailable?.();
      this.reconnectTimer = setTimeout(() => this.stream(base, generation), 3000);
    } finally { clearTimeout(timeout); clearInterval(watchdog); }
  }
  isReady(data) { return data?.ready === true && data.enabled === true && data.derived_events_paused === false && data.mode === 'sensing'; }
  stopTraffic() { const child = this.trafficProcess; this.trafficProcess = null; child?.stdin.end(); }
  traffic(data) {
    if (data?.traffic_generator_mode !== 'external' || !data.enabled) { this.stopTraffic(); return; }
    if (this.trafficProcess || !this.state.ip) return;
    const port = Number.isInteger(data.csi_traffic_udp_port) && data.csi_traffic_udp_port >= 1024 && data.csi_traffic_udp_port <= 65535 ? data.csi_traffic_udp_port : 5555;
    const pps = Math.min(200, Math.max(20, Number(data.csi_target_pps) || 100));
    const python = process.env.STILLWATCH_PYTHON || 'C:\\Users\\User\\.cache\\codex-runtimes\\codex-primary-runtime\\dependencies\\python\\python.exe';
    const child = spawn(python, [path.join(this.root, 'hardware', 'traffic.py'), '--ip', this.state.ip, '--port', String(port), '--pps', String(pps)], { windowsHide: true, stdio: ['pipe', 'ignore', 'ignore'] });
    this.trafficProcess = child;
    child.stdin.on('error', () => {});
    const unavailable = () => {
      if (this.trafficProcess !== child) return;
      this.stopTraffic(); this.change({ ready: false, message: 'CSI 패킷 전송 실패 · 센서 연결을 다시 확인해주세요.' }); this.onUnavailable?.();
    };
    child.on('error', unavailable); child.on('exit', unavailable);
  }
  event(name, data) {
    if (name === 'sensing') {
      this.sensing = data;
      this.traffic(data);
      const ready = this.isReady(data);
      this.change({ ready, message: ready ? '움직임 감지 중' : '센서 보정 또는 준비 중' });
      if (!ready) this.onUnavailable?.();
    }
    if (name === 'motion') {
      if (!this.isReady(this.sensing)) return;
      if (!['idle', 'motion'].includes(data.state) || !Number.isFinite(data.score) || data.score < 0 || data.score > 1 || !Number.isFinite(data.timestamp_ms)) return;
      if (this.lastDeviceTime !== undefined && data.timestamp_ms <= this.lastDeviceTime) return;
      this.lastDeviceTime = data.timestamp_ms;
      this.change({ score: data.score, motion: data.state === 'motion', lastSeen: Date.now(), ready: true, message: '움직임 감지 중' });
      this.onMotion?.(data.state === 'motion');
    }
    if (name === 'fault' || (name === 'health' && (!data.online || data.status !== 'ok'))) {
      this.sensing = { ...this.sensing, ready: false };
      this.change({ ready: false, message: '센서 상태 확인 필요' }); this.onUnavailable?.();
      this.controller?.abort();
    }
  }
  disconnect() {
    this.stopTraffic();
    this.generation++; this.controller?.abort(); clearTimeout(this.reconnectTimer);
    this.change({ connected: false, ready: false, score: null, motion: null, message: '센서 연결 해제' });
    this.onUnavailable?.();
  }
}
