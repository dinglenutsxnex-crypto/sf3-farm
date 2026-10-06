"""SF3 'Nebuchadnezzar' client — updated 2026-10-04.

Wire (captured via PCAPdroid MITM):
  TLS on port 443, SNI = ec2-{ip-dashes}.{region}.compute.amazonaws.com
  After TLS: plain protobuf frames (no inner TLS).
  Frame: [flag:1B][length][body]
    0x01 = raw  + 1-byte  length  (body <= 255 B)
    0x00 = raw  + 4-byte LE length
    0x03 = deflate + 1-byte  length
    0x02 = deflate + 4-byte LE length
  Out envelope: {1:reqId varint, 2:cmd str, 3:payload bytes}
  In  envelope: {1:reqId, 2:cmd, 3:payload, 4:errCode, 5:errText}

Proven create_player sequence from pcapng captures:
  HANDSHAKE -> LOGIN -> join_zone (f1=None for fresh acct, OK)
  -> ping(net_data) -> log(BUNDLE_EVENT) -> ping x4
  -> log(BUNDLE_DOWNLOAD x2) -> ping x4
  -> log(BUNDLES_LOADED + START_EXTRACT) -> ping
  -> create_player -> SUCCESS

Fingerprint:
  sum      = SHA1hex(session + "9C4483BC").upper()   [static for 1.45.5 APK]
  net_data = SHA1hex(session + "2137978293").upper() [static D2]
"""

import socket
import ssl
import struct
import zlib
import json
import hashlib
import uuid
import random
import time
import sys

# constants
APP_ID = "com.nekki.shadowfight3"
BNAME = "UnityClient_ShadowFight3_UnityClientShadowFight3Release_ConfigurationAndroid"
V = "19217"
CONFIG_VER = "1.45.0.175.16722-prod"
APP_VER = "1.45.5"
# Static APK-signing-cert hash (Nekki upload cert, RSA-1024, from the .apks
# v2/v3 block). Login extData `f` = SHA1hex(session + X_CERT).upper().
# Verified 7/7 against real captures. Server validates it at
# get_player/create_player time (login itself accepts anything).
X_CERT = "D61109D768EDAA3AD2EFA9EF357BD1AE33D5F0AB"
# Static file-integrity value behind hashes["sum"]:
# hex8(CRC32(LE32(CRC32(libil2cpp.so)) + LE32(classes.dex CRCs sorted))).
# Verified against 5 real (session, sum) pairs.
D_SUM = "9C4483BC"
SYSID_REAL = "3a95c987d48cb3ee"
D_SUM      = "9C4483BC"
D2_NET     = "2137978293"


# protobuf helpers
def varint(n):
    out = b""
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out += bytes([b | 0x80])
        else:
            return out + bytes([b])

def read_varint(buf, pos):
    shift = res = 0
    while True:
        b = buf[pos]; pos += 1
        res |= (b & 0x7F) << shift
        if not (b & 0x80):
            return res, pos
        shift += 7

def fvar(n, v):    return varint((n << 3) | 0) + varint(v)
def fbytes(n, d):  return varint((n << 3) | 2) + varint(len(d)) + d
def fstr(n, s):    return fbytes(n, s.encode())
def fdouble(n, d): return varint((n << 3) | 1) + struct.pack("<d", d)

def parse_fields(buf):
    pos, out = 0, {}
    while pos < len(buf):
        try:
            tag, pos = read_varint(buf, pos)
        except Exception:
            break
        num, wire = tag >> 3, tag & 7
        try:
            if wire == 0:
                v, pos = read_varint(buf, pos)
            elif wire == 2:
                ln, pos = read_varint(buf, pos)
                v, pos = buf[pos:pos+ln], pos+ln
            elif wire == 1:
                v, pos = buf[pos:pos+8], pos+8
            elif wire == 5:
                v, pos = buf[pos:pos+4], pos+4
            else:
                break
        except Exception:
            break
        out.setdefault(num, []).append(v)
    return out


# framing
def send_frame(s, body):
    if len(body) <= 255:
        s.sendall(b"\x01" + bytes([len(body)]) + body)
    else:
        s.sendall(b"\x00" + struct.pack("<I", len(body)) + body)

def _rd(s, n):
    b = b""
    while len(b) < n:
        c = s.recv(n - len(b))
        if not c:
            raise ConnectionError("connection closed by server")
        b += c
    return b

def rd_frame(s):
    flag = _rd(s, 1)[0]
    if flag in (0, 2):
        ln = struct.unpack("<I", _rd(s, 4))[0]
    elif flag in (1, 3):
        ln = _rd(s, 1)[0]
    else:
        raise ValueError(f"unknown frame flag 0x{flag:02x}")
    body = _rd(s, ln)
    if flag in (2, 3):
        body = zlib.decompress(body, -15)
    return flag, body

def envelope(req, cmd, payload=None):
    b = fvar(1, req) + fstr(2, cmd)
    if payload is not None:
        b += fbytes(3, payload)
    return b


# payload builders
def login_payload(user, password, sysid, fval):
    primary = fvar(1, 1) + fstr(2, json.dumps(
        {"login": user, "password": password}, separators=(",", ":")))
    ext = {"platform": "Android", "v": V, "app_id": APP_ID,
           "f": fval, "bnumber": V, "bname": BNAME}
    b = fvar(1, 6) + fbytes(2, primary)
    b += fbytes(3, fvar(1, 6) + fstr(2, sysid))
    b += fbytes(4, json.dumps(ext, separators=(",", ":")).encode())
    return b

def ping_payload(session):
    ts_ms = int(time.time() * 1000)
    net_data_val = hashlib.sha1((session + D2_NET).encode()).hexdigest().upper()
    f1_inner = fvar(1, ts_ms)
    f2_inner = fstr(1, "net_data") + fstr(2, net_data_val)
    return fbytes(1, f1_inner) + fbytes(2, f2_inner)

def log_payload(events):
    b = b""
    for ev in events:
        b += fstr(1, json.dumps(ev, separators=(",", ":")))
    return b

def appearance_payload():
    color_h = fvar(1, 30) + fdouble(2, 0.05)
    color_s = fvar(1, 1)  + fdouble(2, 0.15)
    return fvar(1, 1) + fvar(2, 2) + fbytes(3, color_h) + fbytes(4, color_s) + fvar(5, 7)

def create_player_payload(name, sumval):
    ap   = appearance_payload()
    h    = fstr(1, "sum") + fstr(2, sumval)
    creg = str(int(time.time()))
    return (fstr(1, name)
            + fbytes(2, ap)
            + fstr(3, CONFIG_VER)
            + fbytes(4, fstr(1, "dojo") + fstr(2, "1.1"))
            + fbytes(4, fstr(1, "CREG") + fstr(2, creg))
            + fbytes(5, h)
            + fstr(6, APP_VER))

def get_player_payload(sumval, model="google Pixel 4"):
    h = fstr(1, "sum") + fstr(2, sumval)
    return (fstr(1, CONFIG_VER) + fbytes(2, b"") + fbytes(3, h)
            + fstr(4, model) + fstr(5, APP_VER))


# SNI derivation
def ip_to_sni(ip):
    dashed = ip.replace(".", "-")
    if ip.startswith("52.66.") or ip.startswith("13.126.") or ip.startswith("13.235."):
        region = "ap-south-1"
    elif ip.startswith("35.73.") or ip.startswith("54.248.") or ip.startswith("13.231."):
        region = "ap-northeast-1"
    elif ip.startswith("34.215.") or ip.startswith("34.214."):
        region = "us-west-2"
    elif ip.startswith("202.131."):
        return None
    else:
        region = "ap-south-1"
    return f"ec2-{dashed}.{region}.compute.amazonaws.com"


# Client
class Client:
    def __init__(self, host, port=443, timeout=15, use_tls=False):
        self.req     = 0
        self.session = None
        self.node    = None
        self.host    = host
        raw = socket.create_connection((host, port), timeout=timeout)
        raw.settimeout(timeout)
        try: raw.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except Exception: pass

        if use_tls:
            sni = ip_to_sni(host) if not host.startswith("ec2-") else host
            ctx = ssl.create_default_context()
            ctx.check_hostname = False
            ctx.verify_mode    = ssl.CERT_NONE
            server_hostname    = sni if sni else host
            try:
                self.s = ctx.wrap_socket(raw, server_hostname=server_hostname)
            except Exception as e:
                raw.close()
                self.s = socket.create_connection((host, port), timeout=timeout)
                self.s.settimeout(timeout)
        else:
            self.s = raw

    def _send(self, cmd, payload=None):
        self.req += 1
        send_frame(self.s, envelope(self.req, cmd, payload))
        return self.req

    def _recv(self):
        _, fb = rd_frame(self.s)
        return parse_fields(fb)

    def _req(self, cmd, payload=None):
        rid = self._send(cmd, payload)
        while True:
            f = self._recv()
            resp_rid = f.get(1, [None])[0]
            resp_cmd = f.get(2, [b""])[0]
            if isinstance(resp_cmd, bytes):
                resp_cmd = resp_cmd.decode(errors="replace")
            if resp_rid == rid or resp_cmd == cmd:
                return f

    def drain(self, timeout=4):
        old = self.s.gettimeout()
        self.s.settimeout(timeout)
        drained = []
        try:
            while True:
                _, fb = rd_frame(self.s)
                f = parse_fields(fb)
                cmd = f.get(2, [b""])[0]
                if isinstance(cmd, bytes):
                    cmd = cmd.decode(errors="replace")
                drained.append((cmd, f))
        except Exception:
            pass
        self.s.settimeout(old)
        return drained

    def close(self):
        try: self.s.close()
        except: pass

    def handshake(self):
        f = self._req("HANDSHAKE", fbytes(1, b"SFA-NEBU-1"))
        pf = parse_fields(f[3][0])
        self.session = pf[2][0].decode()
        conn_id = pf[1][0]
        node    = pf[4][0].decode() if 4 in pf else "?"
        self.node = node
        print(f"  HANDSHAKE ok: connId={conn_id} session={self.session} node={node}")
        return self.session

    def login(self, user=None, sysid=None):
        user  = user or str(uuid.uuid4())
        sysid = sysid or "".join(random.choice("0123456789abcdef") for _ in range(16))
        pw    = hashlib.md5((self.session + user).encode()).hexdigest()
        fval  = hashlib.sha1(user.encode()).hexdigest().upper()
        f     = self._req("LOGIN", login_payload(user, pw, sysid, fval))
        err   = f.get(4, [None])[0]
        print(f"  LOGIN  err={err}  user={user}  sysid={sysid}")
        frames = self.drain(timeout=4)
        player_exists = None
        for cmd, ff in frames:
            if cmd == "join_zone":
                pf = parse_fields(ff.get(3, [b""])[0])
                player_exists = pf.get(1)
                auth_status   = pf.get(5)
                print(f"  join_zone: f1(PlayerExists)={player_exists}  f5(auth)={auth_status}")
        return user, err, player_exists

    def ping_net_data(self):
        rid = self._send("ping", ping_payload(self.session))
        return rid

    def recv_ping(self):
        old = self.s.gettimeout()
        self.s.settimeout(2)
        try:
            _, fb = rd_frame(self.s)
            return parse_fields(fb)
        except Exception:
            return None
        finally:
            self.s.settimeout(old)

    def send_log(self, events):
        rid = self._send("log", log_payload(events))
        old = self.s.gettimeout()
        self.s.settimeout(2)
        try: rd_frame(self.s)
        except: pass
        self.s.settimeout(old)
        return rid

    def create_player(self, name="NEXO"):
        sumval = hashlib.sha1((self.session + D_SUM).encode()).hexdigest().upper()
        print(f"  create_player: name={name!r}  sum={sumval}")
        f = self._req("create_player", create_player_payload(name, sumval))
        err    = f.get(4, [None])[0]
        errTxt = f.get(5, [b""])[0]
        if isinstance(errTxt, bytes): errTxt = errTxt.decode(errors="replace")
        pay    = f.get(3, [b""])[0] if 3 in f else b""
        print(f"  create_player response: err={err} {errTxt.strip()} payloadLen={len(pay)}")
        if err is None and len(pay) > 0:
            print("  *** SUCCESS: player created! ***")
            pf = parse_fields(pay)
            if 3 in pf:
                r3 = parse_fields(pf[3][0])
                if 1 in r3:
                    print(f"  Player GUID: {r3[1][0].decode(errors='replace')}")
        return f

    def get_player(self):
        sumval = hashlib.sha1((self.session + D_SUM).encode()).hexdigest().upper()
        print(f"  get_player: sum={sumval}")
        f = self._req("get_player", get_player_payload(sumval))
        err    = f.get(4, [None])[0]
        errTxt = f.get(5, [b""])[0]
        if isinstance(errTxt, bytes): errTxt = errTxt.decode(errors="replace")
        pay    = f.get(3, [b""])[0] if 3 in f else b""
        print(f"  get_player response: err={err} {errTxt.strip()} payloadLen={len(pay)}")
        if err is None and len(pay) > 0:
            print("  *** SUCCESS: got player data! ***")
        return f


# full create_player workflow
def run_create_player(host, name="NEXO", use_tls=True):
    now  = int(time.time() * 1000)
    creg = int(time.time())

    print(f"\n{'='*70}")
    print(f"run_create_player: host={host}  tls={use_tls}  name={name!r}")
    print(f"{'='*70}")

    c = Client(host, use_tls=use_tls)
    c.handshake()
    user, login_err, player_exists = c.login()

    if login_err is not None:
        print(f"  [!] Login failed: err={login_err}")
        c.close(); return None

    if player_exists == [1]:
        print("  [i] Server says player already exists -- trying get_player...")
        c.ping_net_data(); time.sleep(0.3)
        c.get_player()
        c.close(); return None

    # ping 1
    print("  ping #1 (net_data)...")
    c.ping_net_data(); time.sleep(0.5)

    # log 1: OPEN_ERROR_WINDOW (bundle event)
    print("  log #1 (OPEN_ERROR_WINDOW)...")
    c.send_log([{"type": "OPEN_ERROR_WINDOW", "errorType": "UNKNOWN_ERROR",
                 "etype": "BUNDLE_EVENT", "cid": 10, "pl": 1,
                 "cts": now + 1000, "plf": "Android",
                 "v": "1.45.0.175", "fv": CONFIG_VER,
                 "sid": 0, "onl": False, "md": "VideoPlayer", "creg": creg}])

    # pings x4
    print("  pings x4 (bundle download wait)...")
    for _ in range(4):
        c.ping_net_data(); time.sleep(0.5)

    # log 2: BUNDLE_DOWNLOAD x2
    print("  log #2 (BUNDLE_DOWNLOAD x2)...")
    c.send_log([
        {"Errors": {}, "TotalErrors": 0, "TotalSuccess": 0, "Finish": False,
         "TotalSize": 0.0, "TotalExtracted": 0, "TotalExtractedSize": 0.0,
         "TotalTime": 35.157, "Speed": 0.0, "CDN": "https://omzu2og9jo.a.trbcdn.net",
         "etype": "BUNDLE_DOWNLOAD", "cid": 11, "pl": 1,
         "cts": now + 35000, "plf": "Android", "v": "1.45.0.175", "fv": CONFIG_VER,
         "sid": 0, "onl": False, "md": "VideoPlayer", "creg": creg},
        {"Errors": {}, "TotalErrors": 0, "TotalSuccess": 0, "Finish": False,
         "TotalSize": 0.0, "TotalExtracted": 0, "TotalExtractedSize": 0.0,
         "TotalTime": 36.657, "Speed": 0.0, "CDN": "https://omzu2og9jo.a.trbcdn.net",
         "etype": "BUNDLE_DOWNLOAD", "cid": 12, "pl": 1,
         "cts": now + 36500, "plf": "Android", "v": "1.45.0.175", "fv": CONFIG_VER,
         "sid": 0, "onl": False, "md": "VideoPlayer", "creg": creg}
    ])

    # pings x4
    print("  pings x4...")
    for _ in range(4):
        c.ping_net_data(); time.sleep(0.5)

    # log 3: BUNDLES_LOADED + START_EXTRACT_BUNDLES
    print("  log #3 (BUNDLES_LOADED + START_EXTRACT)...")
    c.send_log([
        {"type": "BUNDLES_LOADED", "etype": "BUNDLE_EVENT",
         "cid": 13, "pl": 1, "cts": now + 50000, "plf": "Android",
         "v": "1.45.0.175", "fv": CONFIG_VER,
         "sid": 0, "onl": False, "md": "VideoPlayer", "creg": creg},
        {"type": "START_EXTRACT_BUNDLES", "etype": "BUNDLE_EVENT",
         "cid": 14, "pl": 1, "cts": now + 50100, "plf": "Android",
         "v": "1.45.0.175", "fv": CONFIG_VER,
         "sid": 0, "onl": False, "md": "VideoPlayer", "creg": creg}
    ])

    # ping 10
    print("  ping #10...")
    c.ping_net_data(); time.sleep(0.5)

    # create_player
    print("  create_player...")
    result = c.create_player(name)

    # post-create log
    print("  log #4 (END_EXTRACT_BUNDLES)...")
    c.send_log([
        {"type": "END_EXTRACT_BUNDLES", "etype": "BUNDLE_EVENT",
         "cid": 15, "pl": 1, "cts": now + 57000, "plf": "Android",
         "v": "1.45.0.175", "fv": CONFIG_VER,
         "sid": 0, "onl": False, "md": "VideoPlayer", "creg": creg},
        {"Errors": {}, "TotalErrors": 0, "TotalSuccess": 1, "Finish": True,
         "TotalSize": 78.217, "TotalExtracted": 14, "TotalExtractedSize": 153.277,
         "TotalTime": 56.931, "Speed": 1.374, "CDN": "https://omzu2og9jo.a.trbcdn.net",
         "etype": "BUNDLE_DOWNLOAD", "cid": 16, "pl": 1,
         "cts": now + 57100, "plf": "Android", "v": "1.45.0.175", "fv": CONFIG_VER,
         "sid": 0, "onl": False, "md": "VideoPlayer", "creg": creg}
    ])

    c.close()
    return result


# main
if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("host", nargs="?", default=None)
    ap.add_argument("--no-tls", action="store_true")
    ap.add_argument("--name", default="NEXO")
    ap.add_argument("--get-player", action="store_true")
    args = ap.parse_args()

    use_tls = not args.no_tls
    hosts = [args.host] if args.host else [
        "52.66.28.201",       # Mumbai -- seen in new pcap, player sessions pass
        "13.126.233.176",     # Mumbai -- exact server create_player succeeded on
    ]

    for host in hosts:
        try:
            if args.get_player:
                print(f"\nTrying get_player on {host} (tls={use_tls})...")
                c = Client(host, use_tls=use_tls)
                c.handshake()
                user, login_err, player_exists = c.login()
                if login_err is None:
                    c.ping_net_data(); time.sleep(0.3)
                    c.get_player()
                c.close()
            else:
                run_create_player(host, name=args.name, use_tls=use_tls)
            break
        except Exception as e:
            print(f"  [ERROR] {host}: {e}")
            import traceback; traceback.print_exc()
            continue
