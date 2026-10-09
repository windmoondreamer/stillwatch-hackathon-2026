import { mkdir, readFile, writeFile, rename } from 'node:fs/promises';
import path from 'node:path';
export async function readJournal(file) {
  try {
    const value = JSON.parse(await readFile(file, 'utf8'));
    if (!Array.isArray(value.logs) || !Array.isArray(value.incidents) || !Array.isArray(value.transcripts)) return {};
    return { logs: value.logs.slice(-500), incidents: value.incidents.slice(-100), transcripts: value.transcripts.slice(-80) };
  } catch (error) { if (error.code !== 'ENOENT') console.warn('기존 사건 기록을 읽을 수 없습니다. 원본 파일은 유지됩니다.'); return {}; }
}
export class Journal {
  constructor(file) { this.file = file; this.last = ''; this.chain = Promise.resolve(); }
  save(state) {
    const json = JSON.stringify({ logs: state.logs, incidents: state.incidents, transcripts: state.transcripts });
    if (json === this.last) return;
    this.last = json;
    this.chain = this.chain.then(async () => {
      await mkdir(path.dirname(this.file), { recursive: true });
      await writeFile(this.file + '.tmp', json, 'utf8');
      await rename(this.file + '.tmp', this.file);
    }).catch(() => console.warn('사건 기록 파일 저장 실패 · 화면에서 기록을 내려받아주세요.'));
  }
}
