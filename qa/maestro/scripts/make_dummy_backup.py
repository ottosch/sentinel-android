#!/usr/bin/env python3
"""Make dummy Sentinel backup blobs (same format as the app: OpenSSL 'Salted__' + PBKDF2-SHA256
15000 + AES-256-CBC, wrapped in {version,time,payload}) holding NO real data: 0 collections, no Dojo.
It is encrypted with a random password that is thrown away, so the suite can only ever use it to
check the wrong-password and corrupt-backup errors (SET-05/SET-06); it can never import anything.
DUMMY_BACKUP_NET is the same construction plus the #404 envelope declaration "network": "mainnet",
so a device check can see the network segment of the preview line; still zero key material.
DUMMY_SAMOURAI_SHAPE is a foreign-shape carrier {"external", "payload"} with inert values: it
classifies to the SAMOURAI arm and previews as "Valid backup file", but never decrypts or imports.
Writes data/backup.env with DUMMY_BACKUP, DUMMY_BACKUP_TRUNC, DUMMY_BACKUP_NET, DUMMY_SAMOURAI_SHAPE
(public, no secrets)."""
import os, json, base64, hashlib, time
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
pw = base64.b64encode(os.urandom(24))          # discarded
salt = os.urandom(8)
k = hashlib.pbkdf2_hmac('sha256', pw, salt, 15000, 48)
pt = json.dumps({"collections": [], "prefs": {"testnet": True}}).encode()
pad = 16 - len(pt) % 16; pt += bytes([pad]) * pad
e = Cipher(algorithms.AES(k[:32]), modes.CBC(k[32:])).encryptor()
ct = e.update(pt) + e.finalize()
env = {"version": 2, "time": int(time.time() * 1000),
       "payload": base64.b64encode(b'Salted__' + salt + ct).decode()}
blob = json.dumps(env, separators=(',', ':'))
blob_net = json.dumps(dict(env, network='mainnet'), separators=(',', ':'))
samourai_shape = json.dumps({"external": "zero-key-material",
                             "payload": "Salted__" + base64.b64encode(os.urandom(48)).decode()},
                            separators=(',', ':'))
trunc = blob[:len(blob) // 2] + '"}'
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'data', 'backup.env')
with open(out, 'w') as f:
    f.write('# Dummy backup blobs for SET-05/06: empty payload, random discarded password. Regenerate: scripts/make_dummy_backup.py\n')
    f.write(f'DUMMY_BACKUP={blob}\nDUMMY_BACKUP_TRUNC={trunc}\n'
            f'DUMMY_BACKUP_NET={blob_net}\nDUMMY_SAMOURAI_SHAPE={samourai_shape}\n')
print('wrote', os.path.normpath(out), len(blob), len(blob_net), len(samourai_shape))
