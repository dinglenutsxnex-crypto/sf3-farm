"""duel_burst.py — time-boxed duel grind for ONE account (GitHub Actions worker).
One job = one runner = one egress IP. Stuck-duel recovery + relogin built in.
Never logs credentials (worker index only).

Usage: duel_burst.py --slot N --minutes M [--max-wins W]
Env: FARM_ACCOUNTS_JSON = [{"guid":..,"sysid":..,"host":..}, ...]
"""
import sys, time, hashlib, json, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sf3 import Client, login_payload, envelope, send_frame, rd_frame, parse_fields, fvar, fbytes, ping_payload, D_SUM, CONFIG_VER
import sf3 as S

X = 'D61109D768EDAA3AD2EFA9EF357BD1AE33D5F0AB'
WI = bytes.fromhex('0a0508d10c10040a0508d20c10040a0508d93410020a0508dc341002')
WS = bytes.fromhex('0802101f1a020101220209052a02010132080000803f0000803f3a08000000000000000042086666e63e6666e63e4a02030352020000')
RE = [bytes.fromhex(s) for s in ('08031001', '08041002', '08051003', '08061002', '0807')]

def arg(name, default=None):
    if name in sys.argv:
        return sys.argv[sys.argv.index(name) + 1]
    return default

slot = int(arg('--slot', '0'))
minutes = float(arg('--minutes', '15'))
maxwins = int(arg('--max-wins', '0'))
ACCTS = json.loads(os.environ['FARM_ACCOUNTS_JSON'])
A = ACCTS[slot % len(ACCTS)]
GUID, SYSID, HOST = A['guid'], A['sysid'], A.get('host', '52.66.28.201')
T_END = time.time() + minutes * 60

c = [None]
sess = [None]

def connect_login():
    if c[0] is not None:
        try:
            c[0].close()
        except Exception:
            pass
    c[0] = Client(HOST, timeout=15)
    c[0].handshake()
    # node IP = server-observed egress = proof of distinct runner IPs
    print('slot=%d egress-node=%s' % (slot, c[0].node if hasattr(c[0], 'node') else '?'), flush=True)
    sess[0] = c[0].session
    fv = hashlib.sha1((sess[0] + X).encode()).hexdigest().upper()
    pw = hashlib.md5((sess[0] + GUID).encode()).hexdigest()
    c[0].req = 0
    c[0]._send('LOGIN', login_payload(GUID, pw, SYSID, fv))
    err = c[0]._recv().get(4, [None])[0]
    c[0].drain(timeout=2)
    return err is None

def raw(cmd, pay=None):
    c[0].req += 1
    send_frame(c[0].s, envelope(c[0].req, cmd, pay))
    o = c[0].s.gettimeout(); c[0].s.settimeout(14)
    try:
        _, fb = rd_frame(c[0].s)
    finally:
        c[0].s.settimeout(o)
    f = parse_fields(fb)
    e = f.get(4, [None])[0]
    return e, (f.get(3, [b''])[0] if 3 in f else b'')

def close_stale():
    try:
        s = hashlib.sha1((sess[0] + D_SUM).encode()).hexdigest().upper()
        h = S.fstr(1, 'sum') + S.fstr(2, s)
        e, p = raw('get_player', S.fstr(1, CONFIG_VER) + fbytes(2, b'') + fbytes(3, h) + S.fstr(4, 'google Pixel 4') + S.fstr(5, '1.45.5'))
        if e is not None or not len(p):
            return False
        top = parse_fields(p)
        if 1 not in top or not isinstance(top[1][0], bytes):
            return False
        inner = parse_fields(top[1][0])
        if 13 not in inner:
            return True
        blob = parse_fields(inner[13][0])[1][0]
        params = (fbytes(1, blob) + fvar(2, 1) + fvar(3, 2) + b''.join(fbytes(4, x) for x in RE) + fvar(5, 2) + fbytes(6, WI) + fbytes(7, WS))
        e, pr = raw('brawler_finish', params)
        return e is None
    except Exception:
        return False

t0 = time.time()
if not connect_login():
    print('slot=%d LOGIN FAIL' % slot, flush=True)
    raise SystemExit(1)
close_stale()
wins = fails = 0
i = 0
while time.time() < T_END and (maxwins <= 0 or wins < maxwins):
    i += 1
    ok = False
    for att in range(4):
        try:
            if i % 10 == 1 and att == 0:
                try:
                    c[0]._send('ping', ping_payload(sess[0]))
                    c[0]._recv()
                except Exception:
                    pass
            e, p = raw('brawler_start', None)
            if e == 50003:
                close_stale()
                continue
            if e is not None or not len(p):
                if e is None:
                    break
                connect_login()
                continue
            blob = parse_fields(p)[1][0]
            params = (fbytes(1, blob) + fvar(2, 1) + fvar(3, 2) + b''.join(fbytes(4, x) for x in RE) + fvar(5, 2) + fbytes(6, WI) + fbytes(7, WS))
            e, pr = raw('brawler_finish', params)
            if e is None and len(pr):
                wins += 1
                ok = True
                break
            connect_login()
            break
        except Exception:
            connect_login()
            continue
    if not ok:
        fails += 1
    if i % 25 == 0:
        el = int(time.time() - t0)
        print('slot=%d duels=%d wins=%d fails=%d %ds left=%ds' % (slot, i, wins, fails, el, max(0, int(T_END - time.time()))), flush=True)
try:
    c[0].close()
except Exception:
    pass
print('slot=%d DONE wins=%d fails=%d duels=%d wall=%ds' % (slot, wins, fails, i, int(time.time() - t0)), flush=True)
