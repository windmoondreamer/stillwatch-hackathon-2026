"""ESPectre external CSI marker traffic with a monotonic, high-resolution clock."""
import argparse
import ipaddress
import socket
import sys
import threading
import time

parser = argparse.ArgumentParser()
parser.add_argument('--ip', required=True)
parser.add_argument('--port', type=int, default=5555)
parser.add_argument('--pps', type=int, default=200)
args = parser.parse_args()
address = ipaddress.IPv4Address(args.ip)
if not address.is_private or address.is_loopback or address.is_link_local or not 1024 <= args.port <= 65535 or not 20 <= args.pps <= 200:
    raise SystemExit('Invalid local CSI traffic destination or rate')
stop = threading.Event()
# EOF stops the helper if the parent is stopped or crashes.
threading.Thread(target=lambda: (sys.stdin.buffer.read(), stop.set()), daemon=True).start()
marker = '👻'.encode('utf-8')
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
interval = 1 / args.pps
next_packet = time.perf_counter()
try:
    while not stop.is_set():
        sock.sendto(marker, (args.ip, args.port))
        next_packet += interval
        delay = next_packet - time.perf_counter()
        if delay > 0:
            # Python 3.12 uses a high-resolution waitable timer on Windows.
            time.sleep(delay)
        elif delay < -interval:
            next_packet = time.perf_counter()
finally:
    sock.close()
