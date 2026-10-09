"""Generate a private development CA and a LAN-only HTTPS certificate.
Does not install or change trust certificates on Windows or any phone.
"""
import datetime as dt
import ipaddress
import json
import pathlib
import sys
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID, ExtendedKeyUsageOID

values = json.load(sys.stdin)
folder = pathlib.Path(values['folder']).resolve()
folder.mkdir(parents=True, exist_ok=True)
addresses = sorted(set(values['addresses'] + ['127.0.0.1']))
now = dt.datetime.now(dt.timezone.utc)
ca_key_file, ca_file = folder / 'ca-key.pem', folder / 'ca.pem'
def save_key(file, key):
    file.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    file.chmod(0o600)
if ca_file.exists() and ca_key_file.exists():
    ca = x509.load_pem_x509_certificate(ca_file.read_bytes())
    ca_key = serialization.load_pem_private_key(ca_key_file.read_bytes(), None)
else:
    ca_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'StillWatch Phone Local CA')])
    ca = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(ca_key.public_key())
          .serial_number(x509.random_serial_number()).not_valid_before(now - dt.timedelta(days=1))
          .not_valid_after(now + dt.timedelta(days=3650))
          .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
          .add_extension(x509.KeyUsage(False, False, False, False, False, True, True, False, False), critical=True)
          .sign(ca_key, hashes.SHA256()))
    save_key(ca_key_file, ca_key)
    ca_file.write_bytes(ca.public_bytes(serialization.Encoding.PEM))
(folder / 'StillWatch-Phone-CA.cer').write_bytes(ca.public_bytes(serialization.Encoding.DER))
key_file, cert_file, meta_file = folder / 'server-key.pem', folder / 'server.pem', folder / 'addresses.json'
reuse = False
if key_file.exists() and cert_file.exists() and meta_file.exists():
    cert = x509.load_pem_x509_certificate(cert_file.read_bytes())
    reuse = json.loads(meta_file.read_text()) == addresses and cert.not_valid_after_utc > now + dt.timedelta(days=7)
if not reuse:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    cert = (x509.CertificateBuilder()
            .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'StillWatch Phone')]))
            .issuer_name(ca.subject).public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - dt.timedelta(days=1)).not_valid_after(now + dt.timedelta(days=90))
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .add_extension(x509.SubjectAlternativeName([x509.DNSName('localhost')] + [x509.IPAddress(ipaddress.ip_address(ip)) for ip in addresses]), critical=False)
            .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
            .add_extension(x509.KeyUsage(True, False, True, False, False, False, False, False, False), critical=True)
            .sign(ca_key, hashes.SHA256()))
    save_key(key_file, key)
    cert_file.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    meta_file.write_text(json.dumps(addresses))
print(json.dumps({'fingerprint': ca.fingerprint(hashes.SHA256()).hex()}))
