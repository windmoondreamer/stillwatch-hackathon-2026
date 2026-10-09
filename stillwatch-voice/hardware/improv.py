"""USB Wi-Fi setup. Credentials arrive on stdin and are never printed or saved."""
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'hardware-tools' / 'python'))
import serial

HEADER = b'IMPROV'

def packet(command, data=b''):
    payload = bytes([command, len(data)]) + data
    frame = HEADER + bytes([1, 3, len(payload)]) + payload
    # Official Native/ESPHome clients append LF to reset the completed-frame
    # parser before the next RPC. The LF is outside the checksummed frame.
    return frame + bytes([sum(frame) & 255]) + b'\n'

def frames(port, duration, observe=None):
    buffer = bytearray()
    end = time.monotonic() + duration
    while time.monotonic() < end:
        incoming = port.read(max(1, port.in_waiting))
        if observe:
            observe(incoming)
        buffer.extend(incoming)
        while True:
            start = buffer.find(HEADER)
            if start < 0:
                buffer = buffer[-5:]
                break
            if start:
                del buffer[:start]
            if len(buffer) < 10:
                break
            length = buffer[8] + 10
            if len(buffer) < length:
                break
            frame = bytes(buffer[:length])
            if frame[6] == 1 and sum(frame[:-1]) & 255 == frame[-1]:
                del buffer[:length]
                yield frame[7], frame[9:-1]
            else:
                del buffer[0]

def strings(data):
    # ESPHome's Improv SDK includes one zero byte reserved for its own checksum
    # inside the RPC payload. Native removes it before serial framing.
    if len(data) >= 3 and len(data) == data[1] + 3 and data[-1] == 0:
        data = data[:-1]
    if len(data) < 2 or data[1] != len(data) - 2:
        raise ValueError('Invalid Improv result')
    items = []
    index = 2
    while index < len(data):
        length = data[index]
        index += 1
        if index + length > len(data):
            raise ValueError('Invalid Improv string')
        items.append(data[index:index + length].decode('utf-8'))
        index += length
    return items

def run(params):
    name = params.get('port', 'COM4')
    if not name.startswith('COM') or not name[3:].isdigit():
        raise ValueError('COM port required')
    port = serial.Serial(port=None, baudrate=115200, timeout=0.2, write_timeout=2)
    port.dtr = False
    port.rts = False
    port.port = name
    port.open()
    try:
        port.reset_input_buffer()
        port.write(b'\n')
        if params.get('command') == 'provision':
            ssid = str(params.get('ssid', '')).encode('utf-8')
            password = str(params.get('password', '')).encode('utf-8')
            if not 1 <= len(ssid) <= 32 or len(password) > 63:
                raise ValueError('SSID/password length invalid')
            port.write(packet(1, bytes([len(ssid)]) + ssid + bytes([len(password)]) + password))
            port.flush()
            last_state = None
            for _ in range(4):
                for kind, data in frames(port, 8):
                    if kind == 1 and data:
                        last_state = data[0]
                    if kind == 2 and data and data[0] != 0:
                        messages = {1: '보드가 Wi-Fi 설정을 받지 못했습니다. 잠시 기다린 뒤 다시 설정해주세요.', 2: '펌웨어가 USB Wi-Fi 설정을 지원하지 않습니다.', 3: 'Wi-Fi 연결 실패 · 이름, 비밀번호, 공유기의 연결 제한을 확인해주세요.'}
                        raise ValueError(messages.get(data[0], '보드의 USB Wi-Fi 설정 오류가 발생했습니다.'))
                    if kind == 4 and data and data[0] in (1, 2):
                        urls = strings(data)
                        if urls:
                            return {'ok': True, 'state': 4, 'urls': urls}
                port.write(packet(2))
            raise TimeoutError('Wi-Fi 연결 완료 응답이 없습니다.' if last_state == 3 else 'USB Wi-Fi 설정 응답이 없습니다. 보드 연결 상태를 확인해주세요.')
        result = {'ok': True, 'port': name, 'state': None, 'device': None, 'urls': []}
        for _ in range(3):
            port.write(packet(2))
            port.flush()
            time.sleep(0.1)
            port.write(packet(3))
            for kind, data in frames(port, 2):
                if kind == 1 and data:
                    result['state'] = data[0]
                if kind == 4 and data:
                    if data[0] == 3:
                        result['device'] = strings(data)
                    if data[0] in (1, 2):
                        result['urls'] = strings(data)
            if result['device'] and result['state'] is not None:
                return result
        raise TimeoutError('No ESPectre Improv response on the USB port')
    finally:
        port.close()

if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    try:
        params = json.loads(sys.stdin.read())
        print(json.dumps(run(params), ensure_ascii=False))
    except Exception as error:
        # Do not include raw serial output or credentials in diagnostics.
        print(json.dumps({'ok': False, 'error': str(error)}, ensure_ascii=False))
        sys.exit(1)
