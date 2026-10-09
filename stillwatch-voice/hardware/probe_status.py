import json
from improv import serial, packet, frames

port = serial.Serial(port=None, baudrate=115200, timeout=0.2)
port.dtr = False
port.rts = False
port.port = 'COM4'
port.open()
try:
    port.reset_input_buffer()
    port.write(packet(3))
    port.write(packet(2))
    for kind, data in frames(port, 3):
        # Only responses to device-info/status requests; never credentials.
        print(json.dumps({'kind': kind, 'payload': list(data)}))
finally:
    port.close()
