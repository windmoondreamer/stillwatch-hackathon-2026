"""Inspect binary provisioning acknowledgements without printing credentials."""
import json
import sys
import time
import re
from improv import serial, packet, frames, strings

params = json.loads(sys.stdin.read())
ssid = params['ssid'].encode('utf-8')
password = params['password'].encode('utf-8')
request = packet(1, bytes([len(ssid)]) + ssid + bytes([len(password)]) + password)
port = serial.Serial(port=None, baudrate=115200, timeout=0.2, write_timeout=2)
port.dtr = False
port.rts = False
port.port = 'COM4'
port.open()
diagnostics = bytearray()
try:
    port.reset_input_buffer()
    port.write(request)
    port.flush()
    print(json.dumps({'request_bytes': len(request), 'checksum': request[-1]}), flush=True)
    for kind, data in frames(port, 20, diagnostics.extend):
        if kind in (1, 2):
            print(json.dumps({'type': kind, 'value': data[0] if data else None}), flush=True)
        if kind == 4 and data:
            print(json.dumps({'result_command': data[0], 'urls': strings(data) if data[0] in (1, 2) else []}), flush=True)
    port.write(packet(2))
    for kind, data in frames(port, 3, diagnostics.extend):
        if kind in (1, 2):
            print(json.dumps({'type': kind, 'value': data[0] if data else None}), flush=True)
        if kind == 4 and data:
            print(json.dumps({'result_command': data[0], 'urls': strings(data) if data[0] in (1, 2) else []}), flush=True)
finally:
    port.close()
    console = diagnostics.decode('utf-8', 'ignore')
    print(json.dumps({
        'received_bytes': len(diagnostics),
        'reset_codes': re.findall(r'rst:(0x[0-9a-fA-F]+)', console),
        'disconnect_reasons': re.findall(r'(?i)reason\s*[:=]?\s*(\d+)', console),
        'brownout': 'brownout' in console.lower(),
        'panic': 'Guru Meditation' in console or 'panic' in console.lower(),
        'watchdog': 'watchdog' in console.lower(),
        'authentication_failure': bool(re.search(r'(?i)auth.*fail|wrong.*password|4-way.*timeout', console)),
        'network_not_found': bool(re.search(r'(?i)no ap found|ap not found', console)),
        'boot_seen': bool(re.search(r'ESP-IDF|boot:|rst:', console)),
        'error_names': sorted(set(re.findall(r'ESP_ERR_[A-Z_]+', console))),
    }), flush=True)
