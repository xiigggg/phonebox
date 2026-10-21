#!/usr/bin/env python3
# phonebox: 网页密码门 + 短信与通话记录展示 + App 接口（数据由 Asterisk 脚本写入 JSONL）
# 环境变量（docker-compose.yml 会按 .env 设置；默认值适合容器内运行）：
#   PHONEBOX_DATA / PHONEBOX_STATE：数据目录、状态目录（默认 /data、/state）
#   PHONEBOX_AMI_HOST / PHONEBOX_AMI_PORT / PHONEBOX_AMI_SECRET_FILE（默认 127.0.0.1、5038、/run/ami-secret）
#   PHONEBOX_ORIGIN：cookie 请求和 /api/token 校验的 Origin（默认 https://sms.example.com，必须改成实际访问地址）
#   PHONEBOX_BIND / PHONEBOX_PORT / PHONEBOX_TLS_CERT / PHONEBOX_TLS_KEY（默认 127.0.0.1:8080 明文，TLS 由反代终结）
#   PHONEBOX_MAST：离线号码库路径（默认 /mast/mast.db，版本号读同目录 mast.version）
#   PHONEBOX_MODEL_DIR：PixelText 模型目录（默认 /model：spam_classifier.tflite、vocab.txt、rules.json）
#   PHONEBOX_LOOKUP_IPS：免登录可调 /api/lookup、/api/sms-score 的来源（默认 127.0.0.1）
#   PHONEBOX_SSE_PING：SSE 心跳秒数（默认 25，只为测试缩短）
#   PHONEBOX_SENT_LOG：仅测试用钩子，设了就把每次 /api/send 的 to/text 追加到该文件
#   PUSH_*：推送唤醒，见 push.py 文件头；PUSH_GRACE：新短信多少秒内没在 SSE 上送达才推（默认 3）
import json, os, re, secrets, time, threading
from collections import deque
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from http.cookies import SimpleCookie
from pathlib import Path

DATA = Path(os.environ.get('PHONEBOX_DATA', '/data'))
ROOT = Path(__file__).parent
STATE = Path(os.environ.get('PHONEBOX_STATE', '/state'))  # 容器重建后仍保存会话
MODEL_DIR = Path(os.environ.get('PHONEBOX_MODEL_DIR', '/model'))
PASSCODE_FILE = STATE / 'passcode'
PASSCODE = PASSCODE_FILE.read_text().strip()
if not (PASSCODE.isdigit() and len(PASSCODE) == 6):
    raise RuntimeError('invalid_passcode_file')
SESSIONS_FILE = STATE / 'sessions.json'
SESSION_TTL = 30 * 24 * 3600
LOGIN_WINDOW = 15 * 60
LOGIN_MAX_FAILURES = 5
LOGIN_LOCKOUT = 15 * 60
_sessions_lock = threading.Lock()
_login_failures = {}

import hashlib as _hashlib, ipaddress as _ipaddress


def _session_key(token):
    return _hashlib.sha256(token.encode()).hexdigest()


def load_sessions():
    try:
        sessions = json.loads(SESSIONS_FILE.read_text())
    except (OSError, ValueError):
        return {}
    out = {}
    for k, v in sessions.items():
        if not isinstance(v, (int, float)) or v <= time.time():
            continue
        is_hash = len(k) == 64 and all(c in '0123456789abcdef' for c in k)
        out[k if is_hash else _session_key(k)] = v      # 旧版明文令牌 → 哈希
    return out

def save_sessions(sessions):
    STATE.mkdir(parents=True, exist_ok=True)
    tmp = SESSIONS_FILE.with_suffix('.new')
    tmp.write_text(json.dumps(sessions, separators=(',', ':')))
    tmp.replace(SESSIONS_FILE)

SESSIONS = load_sessions()
save_sessions(SESSIONS)   # 立即把旧明文键落盘为哈希


def _load_json(path, default):
    try:
        v = json.loads(path.read_text())
        return v if isinstance(v, type(default)) else default
    except (OSError, ValueError):
        return default

def _save_json(path, obj):
    STATE.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix('.new')
    tmp.write_text(json.dumps(obj, ensure_ascii=False, separators=(',', ':')))
    tmp.replace(path)

# ---- 设备令牌：App 用 PIN 换一次，长期有效，可吊销；和会话一样只存 sha256 ----
TOKENS_FILE = STATE / 'tokens.json'
TOKEN_TOUCH_INTERVAL = 300   # last_used 最多 5 分钟落盘一次，避免每个请求都写文件
_tokens_lock = threading.Lock()
TOKENS = {k: v for k, v in _load_json(TOKENS_FILE, {}).items()
          if len(k) == 64 and isinstance(v, dict)}

# ---- 设置与通讯录哈希（App 写入，/api/lookup 读取）----
SETTINGS_FILE = STATE / 'settings.json'
CONTACTS_FILE = STATE / 'contacts.json'
CONTACTS_MAX = 5000
_state_lock = threading.Lock()
SETTINGS = {'hint_only': False}
SETTINGS.update({k: v for k, v in _load_json(SETTINGS_FILE, {}).items() if k in SETTINGS})
CONTACTS = set(_load_json(CONTACTS_FILE, {}).get('hashes') or [])
_HEX64 = re.compile(r'[0-9a-f]{64}')

def norm_number(n):
    """通讯录哈希前的规范化：去空格/横线，去掉 +86 / 0086 前缀。App 侧必须用同一规则。"""
    n = re.sub(r'[\s\-]', '', n or '')
    for p in ('+86', '0086'):
        if n.startswith(p):
            return n[len(p):]
    return n

def contact_hash(n):
    return _hashlib.sha256(norm_number(n).encode()).hexdigest()

TOKENS_MAX = 50

def issue_token(name):
    token = secrets.token_urlsafe(32)
    now = int(time.time())
    with _tokens_lock:
        TOKENS[_session_key(token)] = {'name': name, 'created': now, 'last_used': now}
        if len(TOKENS) > TOKENS_MAX:   # 防止反复登录把文件撑大：挤掉最久没用的
            for k in sorted(TOKENS, key=lambda k: TOKENS[k].get('last_used', 0))[:len(TOKENS) - TOKENS_MAX]:
                TOKENS.pop(k, None)
        _save_json(TOKENS_FILE, TOKENS)
    return token

def check_token(token):
    key = _session_key(token)
    with _tokens_lock:
        meta = TOKENS.get(key)
        if meta is None:
            return None
        now = int(time.time())
        if now - meta.get('last_used', 0) >= TOKEN_TOUCH_INTERVAL:
            meta['last_used'] = now
            _save_json(TOKENS_FILE, TOKENS)
    return key

def revoke_token(key):
    with _tokens_lock:
        if TOKENS.pop(key, None) is not None:
            _save_json(TOKENS_FILE, TOKENS)
    PUSH_REG.unregister(key)

# ---- 推送唤醒：SSE 没送达的设备才经 UnifiedPush 叫醒（逻辑见 push.py） ----
import push as _push
PUSH_REG = _push.Registry(STATE / 'push.json')
with _tokens_lock:
    PUSH_REG.prune(set(TOKENS))
PUSH_GRACE = float(os.environ.get('PUSH_GRACE', '3'))
_sse_delivered = {}            # 设备令牌哈希 → 已经写进它 SSE 连接的最大事件键
_sse_delivered_lock = threading.Lock()
_push_test_at = {}
_push_log = deque(maxlen=50)   # (时间, 通道, 种类, 结果)：只给 /api/push/status 看，不含号码和正文

def _push_one(dev_key, entry, kind, item):
    ok, permanent, info = _push.send(entry, kind, item)
    if permanent:
        PUSH_REG.drop_token(dev_key, entry['token'])
    _push_log.append((int(time.time()), entry['provider'], kind, 'ok' if ok else info[:60]))
    return ok

def push_devices(kind, item=None, event_key=None, only=None):
    """给已注册推送的设备发唤醒；event_key 给定时跳过 SSE 已送达该事件的设备。返回成功条数。"""
    sent = 0
    with _tokens_lock:
        valid = set(TOKENS)
    for dev_key, entries in PUSH_REG.devices().items():
        if dev_key not in valid or (only is not None and dev_key != only):
            continue
        if event_key is not None:
            with _sse_delivered_lock:
                if _sse_delivered.get(dev_key, (0, 0)) >= event_key:
                    continue
        for e in entries:
            if _push_one(dev_key, e, kind, item):
                sent += 1
                break   # 一台设备有一个通道送到就够了
    return sent

def _push_after_grace(event_key, name, item):
    time.sleep(PUSH_GRACE)
    try:
        push_devices(name, item, event_key)
    except Exception:
        pass

# /api/send 每个凭据每分钟上限：令牌泄露时把损失（真实短信费用、骚扰他人）限制住
SEND_PER_MIN = 10
_send_hist = {}
_send_lock = threading.Lock()

def send_allowed(key):
    now = time.time()
    with _send_lock:
        h = [t for t in _send_hist.get(key, []) if now - t < 60]
        if len(h) >= SEND_PER_MIN:
            _send_hist[key] = h
            return False, int(60 - (now - h[0])) + 1
        h.append(now)
        _send_hist[key] = h
        return True, 0

LOGIN_HTML = '''<!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><title>电话信箱</title>
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-title" content="电话信箱">
<meta name="theme-color" content="#2e7d4f">
<link rel="manifest" href="/manifest.json">
<link rel="apple-touch-icon" href="/apple-touch-icon.png">
<style>
body{margin:0;min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;background:#191713;font-family:system-ui,"PingFang SC",sans-serif;color:#e8e2d6;user-select:none;-webkit-user-select:none}
h1{font-size:16px;margin:0 0 18px}
.dots{display:flex;gap:14px;margin-bottom:26px}
.dot{width:14px;height:14px;border-radius:50%;border:2px solid #3a352b;transition:all .12s}
.dot.on{background:#e08a5c;border-color:#e08a5c}
.pad{display:grid;grid-template-columns:repeat(3,72px);gap:12px}
.key{width:72px;height:60px;border-radius:12px;border:1px solid #3a352b;background:#221f19;color:#e8e2d6;font-size:22px;cursor:pointer;display:flex;align-items:center;justify-content:center}
.key:active{background:#e08a5c;color:#fff;transform:scale(.95)}
.key.ghost{border:0;background:none;cursor:default}
.err{color:#e07a70;font-size:12.5px;height:18px;margin-top:12px}
.shake{animation:shake .3s}
@keyframes shake{25%{transform:translateX(-6px)}75%{transform:translateX(6px)}}
</style></head><body>
<h1>📮 电话信箱</h1>
<div class="dots" id="dots"><span class="dot"></span><span class="dot"></span><span class="dot"></span><span class="dot"></span><span class="dot"></span><span class="dot"></span></div>
<div class="pad" id="pad"></div>
<div class="err" id="err"></div>
<form id="f" method="post" action="/login" style="display:none"><input name="code" id="code"></form>
<script>
var code='',dots=document.querySelectorAll('.dot'),err=document.getElementById('err');
var keys=['1','2','3','4','5','6','7','8','9','','0','⌫'];
var pad=document.getElementById('pad');
keys.forEach(function(k){
  var b=document.createElement('div');
  b.className='key'+(k===''?' ghost':'');
  b.textContent=k;
  if(k)b.onclick=function(){press(k)};
  pad.appendChild(b);
});
function draw(){dots.forEach(function(d,i){d.classList.toggle('on',i<code.length)})}
function press(k){
  err.textContent='';
  if(k==='⌫'){code=code.slice(0,-1);draw();return}
  if(code.length>=6)return;
  code+=k;draw();
  if(code.length===6)submit();
}
function submit(){
  fetch('/login',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'code='+encodeURIComponent(code),credentials:'same-origin'}).then(function(r){
    if(r.ok||r.redirected){location.href='/app'}
    else{fail()}
  }).catch(fail);
}
function fail(){
  err.textContent='密码不对';
  var d=document.getElementById('dots');d.classList.remove('shake');void d.offsetWidth;d.classList.add('shake');
  code='';draw();
}
document.addEventListener('keydown',function(e){
  if(e.key>='0'&&e.key<='9')press(e.key);
  else if(e.key==='Backspace')press('⌫');
  else if(e.key==='Enter'&&code.length===6)submit();
});
</script></body></html>'''

def read_jsonl(name, limit=300):
    p = DATA / name
    if not p.exists():
        return []
    out = []
    for line in p.read_text(encoding='utf-8', errors='replace').strip().splitlines()[-limit:]:
        try:
            out.append(json.loads(line))
        except ValueError:
            pass
    return out[::-1]

AMI_SECRET = Path(os.environ.get('PHONEBOX_AMI_SECRET_FILE', '/run/ami-secret')).read_text().strip()
AMI_ADDR = (os.environ.get('PHONEBOX_AMI_HOST', '127.0.0.1'), int(os.environ.get('PHONEBOX_AMI_PORT', '5038')))
ORIGIN = os.environ.get('PHONEBOX_ORIGIN', 'https://sms.example.com')
SENT_LOG = os.environ.get('PHONEBOX_SENT_LOG', '')
LOOKUP_IPS = tuple(os.environ.get('PHONEBOX_LOOKUP_IPS', '127.0.0.1').split(','))

def ami_command(cmd):
    import socket
    with socket.create_connection(AMI_ADDR, timeout=8) as s:
        f = s.makefile('rw')
        f.write(f'Action: Login\r\nUsername: phonebox\r\nSecret: {AMI_SECRET}\r\n\r\n')
        f.flush()
        resp = ''
        while '\r\n\r\n' not in resp:
            chunk = s.recv(4096)
            if not chunk:
                raise RuntimeError('ami_closed')
            resp += chunk.decode('utf-8', 'replace')
        if 'Success' not in resp:
            raise RuntimeError('ami_auth_failed')
        f.write(f'Action: Command\r\nCommand: {cmd}\r\n\r\n')
        f.flush()
        out = ''
        s.settimeout(8)
        # Asterisk 14+ 的 Command 响应是一个以空行结尾的 Output: 块，不再有 --END COMMAND--；
        # 原版只认后者，实际每次都要等满 8 秒超时
        while '--END COMMAND--' not in out and '\r\n\r\n' not in out:
            try:
                chunk = s.recv(4096)
            except socket.timeout:
                break
            if not chunk:
                break
            out += chunk.decode('utf-8', 'replace')
        f.write('Action: Logoff\r\n\r\n')
        f.flush()
        return out

SMS_RE = re.compile(r'[0-9+]{3,20}')

# ---- 离线骚扰号码库（PixelTelo mast.db，只读）----
import sqlite3 as _sq
MAST_DB = Path(os.environ.get('PHONEBOX_MAST', '/mast/mast.db'))
_mast = None
_mast_lock = threading.Lock()
def mast_conn():
    global _mast
    if _mast is None and MAST_DB.exists():
        _mast = _sq.connect(f'file:{MAST_DB}?mode=ro', uri=True, check_same_thread=False)
    return _mast

def lookup_number(number):
    """返回标签或空串。仅本机/内网调用。"""
    db = mast_conn()
    if db is None:
        return ''
    n = number.strip()
    cands = [n]
    if n.startswith('+86'):
        cands.append(n[3:])
    elif not n.startswith('+'):
        cands.append('+86' + n)
    with _mast_lock:  # 连接被多线程共用，并发 execute 会报 InterfaceError
        for c in cands:
            row = db.execute('SELECT tag FROM spam_numbers WHERE phone_number=?', (c,)).fetchone()
            if row:
                return row[0]
    return ''

BLOCK_TAGS = {'骚扰电话','广告推销','疑似欺诈','诈骗电话','响一声','广告营销','营销推广','营销推广电话','房产中介','保险理财','招聘猎头','诈骗','骚扰'}

# ---- PixelText 离线垃圾短信模型（TFLite，本地推理，正文不出本机）----
import re as _re, threading as _th
_ws_re = _re.compile(r'\s+')
_spam = None
_spam_lock = _th.Lock()
def spam_score(text):
    """返回 0~1 骚扰概率；模型不可用时返回 -1。仅本机/内网调用。"""
    global _spam
    try:
        with _spam_lock:
            if _spam is None:
                try:
                    from tflite_runtime.interpreter import Interpreter
                except ImportError:
                    from ai_edge_litert.interpreter import Interpreter
                it = Interpreter(model_path=str(MODEL_DIR / 'spam_classifier.tflite'))
                it.allocate_tensors()
                vocab = {}
                with open(MODEL_DIR / 'vocab.txt', encoding='utf-8') as vf:
                    for i, line in enumerate(vf):
                        vocab[line.rstrip('\n')] = i + 1
                _spam = (it, it.get_input_details(), it.get_output_details(), vocab)
            it, inp, outp, vocab = _spam
            arr = [0] * 40
            for idx, ch in enumerate(_ws_re.sub(' ', text)[:40]):
                arr[idx] = vocab.get(ch, 1)
            import numpy as np
            it.set_tensor(inp[0]['index'], np.array([arr], dtype=inp[0]['dtype']))
            it.invoke()
            return float(it.get_tensor(outp[0]['index'])[0][0])
    except Exception:
        return -1.0

def number_kind(number):
    """号码库未命中时，按号码形态给一个类型，供来电显示。"""
    n = number.strip()
    if n.startswith('+86'):
        n = n[3:]
    elif n.startswith('0086'):
        n = n[4:]
    if _re.fullmatch(r'1[3-9]\d{9}', n):
        return '手机'
    if _re.fullmatch(r'(400|800)\d{7}', n):
        return '企业热线'
    if _re.fullmatch(r'010\d{7,8}', n) or _re.fullmatch(r'[2-8]\d{7}', n):
        return '北京固话'
    if _re.fullmatch(r'0[2-9]\d{8,10}', n):
        return '外地固话'
    if _re.fullmatch(r'(95|96|12|10)\d{1,6}', n):
        return '服务号'
    return ''

# ---- 黑白名单：真源是 Asterisk astdb（拨号计划 anti-spam 直接读它），这里只做读写代理 + 内存缓存 ----
# 选择缓存而不是每次来电都走 AMI：lookup 在来电路径上，AMI 往返要登录、最坏 8 秒超时；
# 缓存由后台线程每分钟从 astdb 全量刷新，本接口写入成功后立即同步，AMI 不可用时保留旧值。
LIST_NUM_RE = re.compile(r'\+?[0-9]{3,20}')
LIST_FAMILIES = {'black': 'blacklist', 'white': 'whitelist'}
LIST_REFRESH = 60
_lists = {'blacklist': set(), 'whitelist': set()}
_lists_meta = {'ok': False, 'at': 0}
_lists_lock = threading.Lock()

def valid_list_number(n):
    # fullmatch：防止 `$` 放过结尾换行；AMI 命令里只可能出现 + 和数字
    return isinstance(n, str) and LIST_NUM_RE.fullmatch(n) is not None

def refresh_lists():
    fresh = {}
    for fam in LIST_FAMILIES.values():
        out = ami_command(f'database show {fam}')
        if '--END COMMAND--' not in out and 'Response: Success' not in out:
            raise RuntimeError('ami_incomplete')
        fresh[fam] = set(re.findall(r'/' + fam + r'/(\+?[0-9]{3,20})\s*:', out))
    with _lists_lock:
        _lists.update(fresh)
        _lists_meta.update(ok=True, at=int(time.time()))

def _lists_loop():
    while True:
        try:
            refresh_lists()
        except Exception:
            pass   # AMI 暂时不可用：保留上次的名单
        time.sleep(LIST_REFRESH)

def list_write(which, number, action):
    fam = LIST_FAMILIES.get(which)
    if fam is None or action not in ('add', 'del'):
        return False, 'invalid_list'
    if not valid_list_number(number):
        return False, 'invalid_number'
    if action == 'add':
        out = ami_command(f'database put {fam} {number} 1')
        ok = 'Updated database successfully' in out
    else:
        out = ami_command(f'database del {fam} {number}')
        ok = 'Database entry removed' in out or 'Database entry does not exist' in out
    if not ok:
        return False, 'ami_failed'
    with _lists_lock:
        (_lists[fam].add if action == 'add' else _lists[fam].discard)(number)
    return True, 'ok'

def _in_list(fam, number):
    n = number.strip()
    cands = {n, norm_number(n)}
    if not n.startswith('+'):
        cands.add('+86' + norm_number(n))
    with _lists_lock:
        return any(c in _lists[fam] for c in cands)

def lookup_json(number):
    # 判定顺序对齐 PixelTelo：通讯录 > 白名单 > 黑名单 > 离线库 > 联网（2 秒超时，失败放行）
    kind0 = number_kind(number)
    if number and contact_hash(number) in CONTACTS:
        return {'found': False, 'tag': '', 'block': False, 'kind': kind0, 'src': '通讯录'}
    if _in_list('whitelist', number):
        return {'found': False, 'tag': '', 'block': False, 'kind': kind0, 'src': '白名单'}
    if _in_list('blacklist', number):
        return {'found': True, 'tag': '黑名单', 'block': True, 'kind': kind0, 'src': '黑名单'}
    try:
        from smsrules import analyze_call
        a = analyze_call(number, online=True)
        kind = a['ntype']
        if a.get('loc'):
            kind = (kind + '·' if kind else '') + a['loc']
        r = {'found': bool(a['tag']), 'tag': a['tag'], 'block': a['block'], 'kind': kind, 'src': a.get('src', '')}
    except Exception:
        tag = lookup_number(number)
        r = {'found': bool(tag), 'tag': tag, 'block': tag in BLOCK_TAGS, 'kind': kind0}
    # 仅提示不拦截：只作用于号码库/联网的判定，用户亲手加的黑名单照拦
    if r['block'] and SETTINGS.get('hint_only'):
        r['block'] = False
        r['hint'] = True
    return r

_CTRL_RE = re.compile(r'[\x00-\x1f\x7f-\x9f  ]')

def send_sms(to, text):
    if not SMS_RE.fullmatch(to):
        return False, 'invalid_number'
    if not text or len(text) > 600:
        return False, 'invalid_text'
    # 原版只换了 " 和 \r\n；其余控制字符同样会破坏 AMI 帧，一并换成空格。
    # Asterisk CLI 解析参数时把 \ 当转义符，结尾的 \ 会吞掉右引号，所以要双写。
    safe = _CTRL_RE.sub(' ', text.replace('"', "'")).replace('\\', '\\\\')
    if SENT_LOG:   # 仅测试用钩子，见文件头
        with open(SENT_LOG, 'a', encoding='utf-8') as f:
            f.write(json.dumps({'ts': int(time.time()), 'to': to, 'text': text}, ensure_ascii=False) + '\n')
    ami_command(f'quectel sms quectel0 {to} "{safe}"')
    return True, 'queued'

# ---- feed / events 共用的单条构造：保证 SSE 推的和 /api/feed 返回的完全一致 ----
def sms_item(r):
    from smsrules import analyze_sms
    body = str(r.get('body') or '')
    sender = str(r.get('from') or '')
    item = {'ts': int(r.get('ts') or 0), 'from': sender, 'body': body,
            'cat': str(r.get('cat') or ''), 'level': str(r.get('level') or '')}
    item.update(analyze_sms(body, sender, item['cat']))
    return item

def call_item(r):
    from smsrules import analyze_call
    number = str(r.get('number') or '')
    item = {'ts': int(r.get('ts') or 0), 'number': number, 'dir': r.get('dir') or 'in',
            'disposition': r.get('disposition') or '', 'billsec': int(r.get('billsec') or 0)}
    item.update(analyze_call(number, str(r.get('tag') or '')))
    return item

# ---- SSE：后台线程轮询 jsonl 追加，新行广播给所有 /api/events 连接 ----
SSE_PING = float(os.environ.get('PHONEBOX_SSE_PING', '25'))
SSE_MAX = 8
SSE_WRITE_TIMEOUT = 15
SSE_KEEP, SSE_KEEP_SECS = 500, 3600
_ev_cond = threading.Condition()
_ev_buf = deque(maxlen=SSE_KEEP)   # (key, id, name, data, 收到时间)
_ev_state = {'seq': 0}
_sse_slots = threading.BoundedSemaphore(SSE_MAX)

def _ev_key(eid):
    try:
        ms, seq = eid.split('-', 1)
        return (int(ms), int(seq))
    except (AttributeError, ValueError):
        return None

def publish(name, item):
    data = json.dumps(item, ensure_ascii=False, separators=(',', ':'))
    with _ev_cond:
        _ev_state['seq'] += 1
        ms = int(time.time() * 1000)
        if _ev_buf and ms < _ev_buf[-1][0][0]:
            ms = _ev_buf[-1][0][0]   # 墙钟回拨时保持单调
        key = (ms, _ev_state['seq'])
        _ev_buf.append((key, f'{ms}-{key[1]}', name, data, time.time()))
        _ev_cond.notify_all()
    if name == 'sms' and PUSH_REG.devices():
        threading.Thread(target=_push_after_grace, args=(key, name, item), daemon=True).start()

class _Tail:
    """跟踪一个只追加的 jsonl；被截断或换 inode 时从头重读，靠已见行的哈希去重，不会重复推旧记录。"""
    def __init__(self, path):
        self.path, self.ino, self.pos, self.buf = path, None, 0, b''
        self.seen = deque(maxlen=2000)
        self.seen_set = set()
        try:
            data = path.read_bytes()
            self.ino, self.pos = path.stat().st_ino, len(data)
            for line in data.splitlines()[-2000:]:
                self._mark(line.strip())
        except OSError:
            pass

    def _mark(self, line):
        h = _hashlib.sha256(line).digest()
        if h in self.seen_set:
            return False
        if len(self.seen) == self.seen.maxlen:
            self.seen_set.discard(self.seen[0])
        self.seen.append(h); self.seen_set.add(h)
        return True

    def poll(self):
        try:
            st = self.path.stat()
        except OSError:
            return []
        if st.st_ino != self.ino or st.st_size < self.pos:
            self.ino, self.pos, self.buf = st.st_ino, 0, b''
        if st.st_size == self.pos:
            return []
        with open(self.path, 'rb') as f:
            f.seek(self.pos)
            chunk = f.read(st.st_size - self.pos)
        self.pos += len(chunk)
        lines = (self.buf + chunk).split(b'\n')
        self.buf = lines.pop()   # 写了一半的行留到下次
        out = []
        for line in lines:
            line = line.strip()
            if not line or not self._mark(line):
                continue
            try:
                out.append(json.loads(line.decode('utf-8', 'replace')))
            except ValueError:
                pass
        return out

def _watch_loop():
    tails = [('sms', _Tail(DATA / 'sms.jsonl'), sms_item), ('call', _Tail(DATA / 'calls.jsonl'), call_item)]
    while True:
        for name, tail, build in tails:
            try:
                for r in tail.poll():
                    if isinstance(r, dict):
                        publish(name, build(r))
            except Exception:
                pass
        time.sleep(0.5)

STATIC = {
    '/manifest.json': 'application/manifest+json',
    '/sw.js': 'text/javascript',
    '/sw-v3.js': 'text/javascript',
    '/icon-192.png': 'image/png',
    '/icon-512.png': 'image/png',
    '/icon-maskable-512.png': 'image/png',
    '/apple-touch-icon.png': 'image/png',
}

class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def client_ip(self):
        # 只信任本机 Caddy 注入的 XFF，防止客户端伪造来源。
        peer = self.client_address[0]
        try:
            trusted = any(_ipaddress.ip_address(peer) in _ipaddress.ip_network(n)
                          for n in ('192.168.0.0/16', '172.16.0.0/12'))
        except ValueError:
            trusted = False
        if trusted:
            return self.headers.get('X-Forwarded-For', '').split(',')[-1].strip() or peer
        return peer

    def bearer(self):
        """有效设备令牌返回其哈希；没带或无效返回 None。"""
        h = self.headers.get('Authorization', '')
        if not h.startswith('Bearer '):
            return None
        tok = h[7:].strip()
        return check_token(tok) if tok else None

    def credential(self):
        """('bearer'|'cookie', 键) 或 None。带了 Authorization 就只认令牌，不回退 cookie。"""
        if self.headers.get('Authorization'):
            k = self.bearer()
            return ('bearer', k) if k else None
        c = SimpleCookie(self.headers.get('Cookie'))
        m = c.get('pb_token')
        if m and self.cookie_authed(m.value):
            return ('cookie', _session_key(m.value))
        return None

    def authed(self):
        return self.credential() is not None

    def csrf_ok(self):
        return self.headers.get('Origin', '') == ORIGIN and self.headers.get('X-Phonebox') == '1'

    def write_ok(self):
        """写接口的鉴权：令牌请求不是浏览器发的，没有 CSRF 面；cookie 请求维持 Origin + X-Phonebox。"""
        cred = self.credential()
        if cred is None:
            self.send_response(401); self.end_headers(); return None
        if cred[0] == 'cookie' and not self.csrf_ok():
            self.send_response(403); self.end_headers(); return None
        return cred

    def read_json(self, limit):
        if not self.headers.get('Content-Type', '').startswith('application/json'):
            self.send_response(415); self.end_headers(); return None
        length = int(self.headers.get('Content-Length', '0') or 0)
        if length > limit:
            self.send_response(413); self.end_headers(); return None
        try:
            return json.loads(self.rfile.read(length).decode('utf-8'))
        except ValueError:
            self.send_response(400); self.end_headers(); return None

    def send_json(self, obj, code=200):
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(b)

    def cookie_authed(self, value):
        with _sessions_lock:
            expires = SESSIONS.get(_session_key(value), 0)
            if expires <= time.time():
                SESSIONS.pop(_session_key(value), None)
                save_sessions(SESSIONS)
                return False
            return True

    def login_allowed(self):
        now = time.time(); ip = self.client_ip()
        failures, locked_until = _login_failures.get(ip, ([], 0))
        failures = [t for t in failures if now - t < LOGIN_WINDOW]
        if locked_until > now:
            _login_failures[ip] = (failures, locked_until)
            return False, int(locked_until - now)
        _login_failures[ip] = (failures, 0)
        return True, 0

    def record_login_failure(self):
        now = time.time(); ip = self.client_ip()
        failures, _ = _login_failures.get(ip, ([], 0))
        failures = [t for t in failures if now - t < LOGIN_WINDOW] + [now]
        locked = now + LOGIN_LOCKOUT if len(failures) >= LOGIN_MAX_FAILURES else 0
        _login_failures[ip] = (failures, locked)

    def send_html(self, html, code=200):
        b = html.encode()
        self.send_response(code)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(b)

    def peer_closed(self):
        # 客户端断开后要等写失败才发现，最长可达两个 ping 周期；每秒探一下读端，读到 EOF 就立刻释放槽位
        import select, socket
        try:
            if select.select([self.connection], [], [], 0)[0]:
                return self.connection.recv(1, socket.MSG_PEEK) == b''
        except OSError:
            return True
        except ValueError:   # TLS 直连（仅测试环境）不支持 MSG_PEEK，退回靠写失败发现
            return False
        return False

    def serve_events(self):
        cred = self.credential()
        dev_key = cred[1] if cred and cred[0] == 'bearer' else None
        if not _sse_slots.acquire(blocking=False):
            self.send_response(503); self.send_header('Retry-After', '30'); self.end_headers(); return
        try:
            last = _ev_key(self.headers.get('Last-Event-ID', ''))
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream; charset=utf-8')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Accel-Buffering', 'no')
            self.end_headers()
            # 写超时：客户端卡死不读时，最多占着这个槽 SSE_WRITE_TIMEOUT 秒
            self.connection.settimeout(SSE_WRITE_TIMEOUT)
            self.close_connection = True
            with _ev_cond:
                if last is None:
                    cursor = _ev_buf[-1][0] if _ev_buf else (0, 0)
                else:   # 补发 Last-Event-ID 之后、且没超过保留时长的事件
                    cursor = last
            next_ping = time.time() + SSE_PING
            while True:
                with _ev_cond:
                    pending = [e for e in _ev_buf if e[0] > cursor and time.time() - e[4] <= SSE_KEEP_SECS]
                    if not pending:
                        _ev_cond.wait(timeout=min(1.0, max(0.0, next_ping - time.time())))
                        pending = [e for e in _ev_buf if e[0] > cursor]
                if not pending and self.peer_closed():
                    break
                out = b''
                for key, eid, name, data, _ in pending:
                    out += f'id: {eid}\nevent: {name}\ndata: {data}\n\n'.encode()
                    cursor = key
                if time.time() >= next_ping:
                    out += b'event: ping\ndata:\n\n'
                    next_ping = time.time() + SSE_PING
                if out:
                    self.wfile.write(out); self.wfile.flush()
                    if dev_key and pending:
                        with _sse_delivered_lock:
                            _sse_delivered[dev_key] = max(_sse_delivered.get(dev_key, (0, 0)), cursor)
        except (OSError, ValueError):
            pass   # 断开 / 写超时：释放槽位
        finally:
            _sse_slots.release()

    def do_GET(self):
        if self.path == '/health':
            self.send_response(200); self.end_headers(); self.wfile.write(b'ok'); return
        # PWA 静态资源免登录（图标/manifest/sw 必须在登录前可达）
        path = self.path.split('?')[0]
        if path in STATIC:
            p = ROOT / path.lstrip('/')
            if p.exists():
                body = p.read_bytes()
                self.send_response(200)
                self.send_header('Content-Type', STATIC[path])
                self.send_header('Cache-Control', 'no-cache' if path.startswith('/sw') else 'public, max-age=86400')
                self.end_headers()
                self.wfile.write(body)
            else:
                self.send_response(404); self.end_headers()
            return
        if not self.authed():
            # 号码查询接口仅放行本机 Asterisk（host 网络容器）
            if path == '/api/lookup' and self.client_address[0] in LOOKUP_IPS:
                import urllib.parse as _up
                q = _up.parse_qs(_up.urlparse(self.path).query)
                n = q.get('n', [''])[0]
                r = lookup_json(n)
                if q.get('fmt', [''])[0] == 'txt':
                    body = (('BLOCK|' if r['block'] else 'PASS|') + r['tag'] + '|' + r['kind']).encode()
                    ctype = 'text/plain; charset=utf-8'
                else:
                    body = json.dumps(r, ensure_ascii=False).encode()
                    ctype = 'application/json; charset=utf-8'
                self.send_response(200)
                self.send_header('Content-Type', ctype)
                self.send_header('Cache-Control', 'no-store')
                self.end_headers()
                self.wfile.write(body)
                return
            if self.path == '/' or self.path.startswith('/?') or path == '/app':
                self.send_html(LOGIN_HTML.replace('ERR', ''))
            else:
                self.send_response(401); self.end_headers()
            return
        if self.path == '/' or self.path.startswith('/?') or path == '/app':
            body = (ROOT / 'index.html').read_bytes()
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            self.wfile.write(body)
        elif path == '/api/feed':
            # 新版网页的数据：短信已按 PixelText 规则分类并提取验证码，来电带 PixelTelo 号码库标签
            from smsrules import rules_version, prefetch
            import urllib.parse as _up
            try:   # 增量：只要 ts >= since（与 api-events.md 一致，边界那一秒由客户端去重）
                since = int(_up.parse_qs(_up.urlparse(self.path).query).get('since', ['0'])[0])
            except ValueError:
                since = 0
            def ver(p):
                try:
                    return Path(p).read_text().strip()
                except OSError:
                    return ''
            sms = [sms_item(r) for r in read_jsonl('sms.jsonl', 400) if int(r.get('ts') or 0) >= since]
            calls = [call_item(r) for r in read_jsonl('calls.jsonl', 400) if int(r.get('ts') or 0) >= since]
            prefetch([c['number'] for c in calls if not c['tag']])   # 后台慢慢补查本地库没收录的号码
            data = {'sms': sms, 'calls': calls,
                    'versions': {'rules': rules_version(), 'mast': ver(MAST_DB.parent / 'mast.version'),
                                 'model': ver(MODEL_DIR / 'model.version')}}
            body = json.dumps(data, ensure_ascii=False).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            self.wfile.write(body)
        elif path == '/api/events':
            self.serve_events()
        elif path == '/api/push/status':
            k = self.bearer()
            if not k:
                self.send_response(401); self.end_headers(); return
            mine = [{'provider': e['provider'], 'updated': e.get('updated', 0), 'configured': _push.configured(e['provider'])}
                    for e in PUSH_REG.devices().get(k, [])]
            self.send_json({'content': _push.CONTENT, 'registered': mine,
                            'providers': {p: _push.configured(p) for p in _push.PROVIDERS},
                            'log': [list(x) for x in list(_push_log)[-10:]]})
        elif path == '/api/lists':
            try:
                refresh_lists()
            except Exception:   # AMI 不通时返回缓存，ok=false 让前端提示"可能不是最新"
                with _lists_lock:
                    _lists_meta['ok'] = False
            with _lists_lock:
                data = {'black': sorted(_lists['blacklist']), 'white': sorted(_lists['whitelist']),
                        'ok': _lists_meta['ok'], 'at': _lists_meta['at']}
            self.send_json(data)
        elif path == '/api/settings':
            with _state_lock:
                self.send_json(dict(SETTINGS))
        elif self.path.startswith('/api/log'):
            data = {'sms': read_jsonl('sms.jsonl'), 'calls': read_jsonl('calls.jsonl')}
            body = json.dumps(data, ensure_ascii=False).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404); self.end_headers()

    def do_DELETE(self):
        if self.path == '/api/push/register':
            k = self.bearer()
            if not k:
                self.send_response(401); self.end_headers(); return
            PUSH_REG.unregister(k)
            self.send_response(204); self.end_headers(); return
        if self.path == '/api/token':
            k = self.bearer()
            if not k:
                self.send_response(401); self.end_headers(); return
            revoke_token(k)
            self.send_response(204); self.end_headers(); return
        self.send_response(404); self.end_headers()

    def issue_device_token(self):
        # 与 /login 共用失败计数和锁定；请求约束见 docs/api-events.md
        if not self.csrf_ok():
            self.send_response(403); self.end_headers(); return
        length = int(self.headers.get('Content-Length', '0') or 0)
        if length > 1024:
            self.send_response(413); self.end_headers(); return
        import urllib.parse as _up
        form = _up.parse_qs(self.rfile.read(length).decode('utf-8', 'replace'))
        code = form.get('code', [''])[0]
        name = re.sub(r'[^\w .\-]', '', form.get('name', [''])[0])[:40] or 'app'
        allowed, retry = self.login_allowed()
        if not allowed:
            self.send_response(429)
            self.send_header('Retry-After', str(retry))
            self.end_headers()
            return
        if not secrets.compare_digest(code.encode(), PASSCODE.encode()):
            self.record_login_failure()
            self.send_response(403); self.end_headers(); return
        _login_failures.pop(self.client_ip(), None)
        self.send_json({'token': issue_token(name)})

    def do_POST(self):
        if self.path == '/api/token':
            self.issue_device_token(); return
        if self.path == '/api/push/wake':
            # 只给本机 Asterisk 拨号计划用：App 分机离线时有来电
            if self.client_address[0] not in LOOKUP_IPS:
                self.send_response(401); self.end_headers(); return
            self.send_json({'sent': push_devices('call')}); return
        if self.path in ('/api/push/register', '/api/push/test'):
            # 只认设备令牌：推送是 App 的事，网页 cookie 会话没有这个需求
            k = self.bearer()
            if not k:
                self.send_response(401); self.end_headers(); return
            if self.path == '/api/push/test':
                now = time.time()
                if now - _push_test_at.get(k, 0) < 30:
                    self.send_json({'ok': False, 'msg': 'too_frequent'}, 429); return
                _push_test_at[k] = now
                self.send_json({'ok': push_devices('test', only=k) > 0}); return
            body = self.read_json(4096)
            if body is None:
                return
            entry, err = _push.validate_entry(body)
            if err:
                self.send_json({'ok': False, 'msg': err}, 400); return
            PUSH_REG.register(k, entry)
            self.send_json({'ok': True, 'configured': _push.configured(entry['provider'])}); return
        if self.path == '/api/lists':
            if self.write_ok() is None:
                return
            body = self.read_json(1024)
            if body is None:
                return
            if not isinstance(body, dict):
                self.send_json({'ok': False, 'msg': 'invalid_body'}, 400); return
            try:
                ok, msg = list_write(body.get('list'), body.get('number'), body.get('action'))
            except Exception as e:
                ok, msg = False, type(e).__name__
            self.send_json({'ok': ok, 'msg': msg}, 200 if ok else (400 if msg.startswith('invalid') else 502))
            return
        if self.path == '/api/contacts':
            if self.write_ok() is None:
                return
            body = self.read_json(CONTACTS_MAX * 70 + 1024)
            if body is None:
                return
            hashes = body.get('hashes') if isinstance(body, dict) else body
            if (not isinstance(hashes, list) or len(hashes) > CONTACTS_MAX
                    or not all(isinstance(h, str) and _HEX64.fullmatch(h) for h in hashes)):
                self.send_json({'ok': False, 'msg': 'invalid_hashes'}, 400); return
            global CONTACTS
            with _state_lock:
                CONTACTS = set(hashes)
                _save_json(CONTACTS_FILE, {'hashes': sorted(CONTACTS), 'at': int(time.time())})
            self.send_json({'ok': True, 'count': len(CONTACTS)})
            return
        if self.path == '/api/settings':
            if self.write_ok() is None:
                return
            body = self.read_json(1024)
            if body is None:
                return
            if not isinstance(body, dict) or not isinstance(body.get('hint_only'), bool):
                self.send_json({'ok': False, 'msg': 'invalid_body'}, 400); return
            with _state_lock:
                SETTINGS['hint_only'] = body['hint_only']
                _save_json(SETTINGS_FILE, SETTINGS)
            self.send_json({'ok': True, **SETTINGS})
            return
        if self.path == '/login':
            length = int(self.headers.get('Content-Length', '0') or 0)
            if length > 1024:
                self.send_response(413); self.end_headers(); return
            form = self.rfile.read(length).decode('utf-8', 'replace')
            code = ''
            for part in form.split('&'):
                if part.startswith('code='):
                    code = part[5:]
            allowed, retry = self.login_allowed()
            if not allowed:
                self.send_response(429)
                self.send_header('Retry-After', str(retry))
                self.end_headers()
                return
            if secrets.compare_digest(code, PASSCODE):
                token = secrets.token_urlsafe(32)
                with _sessions_lock:
                    SESSIONS[_session_key(token)] = time.time() + SESSION_TTL
                    save_sessions(SESSIONS)
                _login_failures.pop(self.client_ip(), None)
                self.send_response(303)
                self.send_header('Location', '/app')
                self.send_header('Set-Cookie', f'pb_token={token}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age={SESSION_TTL}')
                self.end_headers()
            else:
                self.record_login_failure()
                self.send_html(LOGIN_HTML.replace('ERR', '密码不对'), 403)
            return
        cred = self.credential() if self.path == '/api/send' else None
        if self.path == '/logout' or (self.path == '/api/send' and (cred is None or cred[0] == 'cookie')):
            origin_ok = self.headers.get('Origin', '') == ORIGIN
            header_ok = self.headers.get('X-Phonebox') == '1'
            if not (origin_ok and header_ok):
                self.send_response(403); self.end_headers(); return
        if self.path == '/logout':
            c = SimpleCookie(self.headers.get('Cookie'))
            m = c.get('pb_token')
            if m:
                with _sessions_lock:
                    SESSIONS.pop(_session_key(m.value), None)
                    save_sessions(SESSIONS)
            self.send_response(204)
            self.send_header('Set-Cookie', 'pb_token=; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=0')
            self.end_headers()
            return
        if self.path == '/api/send':
            if cred is None:
                self.send_response(401); self.end_headers(); return
            allowed, retry = send_allowed(cred[1])
            if not allowed:
                self.send_response(429); self.send_header('Retry-After', str(retry)); self.end_headers(); return
            if not self.headers.get('Content-Type', '').startswith('application/json'):
                self.send_response(415); self.end_headers(); return
            length = int(self.headers.get('Content-Length', '0') or 0)
            if length > 8192:
                self.send_response(413); self.end_headers(); return
            try:
                body = json.loads(self.rfile.read(length).decode('utf-8'))
                ok, msg = send_sms(str(body.get('to', '')).strip(), str(body.get('text', '')).strip())
            except Exception as e:
                ok, msg = False, type(e).__name__
            out = json.dumps({'ok': ok, 'msg': msg}).encode()
            self.send_response(200 if ok else 400)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(out)
            return
        if self.path == '/api/sms-score':
            if self.client_address[0] not in LOOKUP_IPS:
                self.send_response(401); self.end_headers(); return
            length = int(self.headers.get('Content-Length', '0') or 0)
            if length > 8192:
                self.send_response(413); self.end_headers(); return
            try:
                body = json.loads(self.rfile.read(length).decode('utf-8'))
                score = spam_score(str(body.get('text', ''))[:500])
            except Exception:
                score = -1.0
            out = json.dumps({'score': round(score, 4), 'spam': score >= 0.7}).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(out)
            return
        self.send_response(404); self.end_headers()

_srv = ThreadingHTTPServer((os.environ.get('PHONEBOX_BIND', '127.0.0.1'), int(os.environ.get('PHONEBOX_PORT', '8080'))), H)
if os.environ.get('PHONEBOX_TLS_CERT'):
    import ssl as _ssl
    _ctx = _ssl.SSLContext(_ssl.PROTOCOL_TLS_SERVER)
    _ctx.load_cert_chain(os.environ['PHONEBOX_TLS_CERT'], os.environ['PHONEBOX_TLS_KEY'])
    _srv.socket = _ctx.wrap_socket(_srv.socket, server_side=True)
for _fn in (_watch_loop, _lists_loop):
    threading.Thread(target=_fn, daemon=True).start()
_srv.serve_forever()
