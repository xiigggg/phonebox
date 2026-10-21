"""推送唤醒：App 的 SSE 实时连接断了时，经 UnifiedPush（如自建 ntfy）把它叫醒。

原则：
- 推送只是“唤醒信号 + 不含正文的提示”，App 收到后自己经 /api/feed 拉正文；
- 只推给没在 SSE 上收到这条事件的设备（由 server.py 判断），SSE 活着就不经第三方；
- 经推送服务器的内容由 PUSH_CONTENT 决定：wake（只有“有新短信”）/ preview（默认，加上发件方与类别）/
  full（连正文一起，验证码会经过推送服务器）。

配置全走环境变量，没配的通道自动跳过：
  PUSH_CONTENT=wake|preview|full
  UnifiedPush 不需要配置：设备上报的 endpoint 就是发送地址（自建 ntfy 等）。
"""
import base64
import hashlib
import json
import os
import struct
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

CONTENT = os.environ.get('PUSH_CONTENT', 'preview')
if CONTENT not in ('wake', 'preview', 'full'):
    CONTENT = 'preview'
HTTP_TIMEOUT = 8
PROVIDERS = ('unifiedpush',)
# 每台设备最多这么多条推送注册（重装、换通道会留下旧的）
MAX_PER_DEVICE = 3


class PushError(Exception):
    """permanent=True 表示 token 已失效（卸载、换机），应从注册表删掉"""
    def __init__(self, msg, permanent=False):
        super().__init__(msg)
        self.permanent = permanent


# ---------------- 消息内容 ----------------

def mask_sender(s):
    """号码只留后 4 位；短信签名（【银行】这类）和服务号原样，它们不是个人信息"""
    s = str(s or '')
    digits = ''.join(c for c in s if c.isdigit())
    if len(digits) >= 7 and len(digits) >= len(s) - 2:
        return '尾号' + digits[-4:]
    return s[:20]


def build_message(kind, item=None, content=CONTENT):
    """返回 (title, body, data)。data 只给 App 当提示，App 不信任它、一律回头拉 /api/feed。"""
    item = item or {}
    if kind == 'call':
        return '电话信箱', '有来电，正在恢复连接…', {'t': 'call'}
    if kind == 'test':
        return '电话信箱', '推送测试：收到这条说明推送通道可用', {'t': 'test'}
    if content == 'wake':
        return '电话信箱', '有新短信', {'t': 'sms'}
    who = item.get('sig') or mask_sender(item.get('from'))
    cat = item.get('kind') or item.get('cat') or ''
    if content == 'preview':
        return '新短信', '来自 %s%s' % (who, ' · ' + cat if cat else ''), {'t': 'sms'}
    body = str(item.get('body') or '')[:120]
    return str(who or '新短信'), body or '有新短信', {'t': 'sms'}


# ---------------- 注册表 ----------------

class Registry:
    """{设备令牌哈希: [{"provider", "token", "pub", "auth", "updated"}...]}，落盘为 push.json"""

    def __init__(self, path):
        self.path = path
        self.lock = threading.Lock()
        try:
            self.data = json.loads(path.read_text())
            if not isinstance(self.data, dict):
                self.data = {}
        except (OSError, ValueError):
            self.data = {}

    def _save(self):
        tmp = self.path.with_suffix('.tmp')
        tmp.write_text(json.dumps(self.data, ensure_ascii=False))
        os.chmod(tmp, 0o600)
        tmp.replace(self.path)

    def register(self, dev_key, entry):
        with self.lock:
            lst = [e for e in self.data.get(dev_key, []) if e.get('provider') != entry['provider']]
            lst.append(dict(entry, updated=int(time.time())))
            self.data[dev_key] = lst[-MAX_PER_DEVICE:]
            self._save()

    def unregister(self, dev_key, provider=None):
        with self.lock:
            if provider is None:
                self.data.pop(dev_key, None)
            else:
                self.data[dev_key] = [e for e in self.data.get(dev_key, []) if e.get('provider') != provider]
            self._save()

    def drop_token(self, dev_key, token):
        with self.lock:
            self.data[dev_key] = [e for e in self.data.get(dev_key, []) if e.get('token') != token]
            self._save()

    def devices(self):
        with self.lock:
            return {k: list(v) for k, v in self.data.items() if v}

    def prune(self, valid_keys):
        """设备令牌被吊销后它的推送注册一并删除"""
        with self.lock:
            gone = [k for k in self.data if k not in valid_keys]
            for k in gone:
                self.data.pop(k)
            if gone:
                self._save()


def validate_entry(body):
    """校验 App 上报的注册；返回 (entry, None) 或 (None, 错误码)"""
    if not isinstance(body, dict):
        return None, 'invalid_body'
    provider = body.get('provider')
    token = body.get('token')
    if provider not in PROVIDERS:
        return None, 'invalid_provider'
    if not isinstance(token, str) or not (8 <= len(token) <= 1024) or any(c in token for c in '\r\n\x00 '):
        return None, 'invalid_token'
    entry = {'provider': provider, 'token': token}
    if provider == 'unifiedpush':
        u = urllib.parse.urlsplit(token)
        # endpoint 是服务端要去 POST 的地址：只认 https，防止被拿来探测内网（自建 ntfy 走局域网时用 PUSH_UP_ALLOW_HTTP）
        if u.scheme != 'https' and not (u.scheme == 'http' and os.environ.get('PUSH_UP_ALLOW_HTTP') == '1'):
            return None, 'invalid_endpoint'
        if not u.hostname:
            return None, 'invalid_endpoint'
        pub, auth = body.get('pub'), body.get('auth')
        if pub is not None or auth is not None:
            try:
                if len(_b64d(pub)) != 65 or len(_b64d(auth)) != 16:
                    return None, 'invalid_keys'
            except (TypeError, ValueError):
                return None, 'invalid_keys'
            entry.update(pub=pub, auth=auth)
    return entry, None


# ---------------- 发送 ----------------

def _b64d(s):
    s = str(s)
    return base64.urlsafe_b64decode(s + '=' * (-len(s) % 4))


def _b64e(b):
    return base64.urlsafe_b64encode(b).rstrip(b'=').decode()


def _http(url, data=None, headers=None, method='POST', timeout=HTTP_TIMEOUT):
    req = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def webpush_encrypt(plaintext, pub_b64, auth_b64, salt=None, server_key=None):
    """RFC 8291（aes128gcm）。依赖 cryptography；没装时抛 PushError，调用方退回发空消息。"""
    try:
        from cryptography.hazmat.primitives.asymmetric import ec
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.kdf.hkdf import HKDF
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ImportError:
        raise PushError('需要 cryptography 才能加密 WebPush')
    ua_pub = _b64d(pub_b64)
    auth = _b64d(auth_b64)
    ua_key = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), ua_pub)
    as_key = server_key or ec.generate_private_key(ec.SECP256R1())
    as_pub = as_key.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
    shared = as_key.exchange(ec.ECDH(), ua_key)
    ikm = HKDF(hashes.SHA256(), 32, auth, b'WebPush: info\x00' + ua_pub + as_pub).derive(shared)
    salt = salt or os.urandom(16)
    cek = HKDF(hashes.SHA256(), 16, salt, b'Content-Encoding: aes128gcm\x00').derive(ikm)
    nonce = HKDF(hashes.SHA256(), 12, salt, b'Content-Encoding: nonce\x00').derive(ikm)
    record = AESGCM(cek).encrypt(nonce, plaintext + b'\x02', None)
    return salt + struct.pack('!IB', 4096, len(as_pub)) + as_pub + record


def send_unifiedpush(entry, title, body, data, urgent):
    payload = json.dumps(data, separators=(',', ':')).encode()
    headers = {'TTL': '600', 'Urgency': 'high' if urgent else 'normal'}
    if entry.get('pub') and entry.get('auth'):
        payload = webpush_encrypt(payload, entry['pub'], entry['auth'])
        headers['Content-Encoding'] = 'aes128gcm'
    headers['Content-Type'] = 'application/octet-stream'
    st, resp = _http(entry['token'], payload, headers)
    if st in (404, 410):
        raise PushError('endpoint 已失效 HTTP %d' % st, permanent=True)
    if not 200 <= st < 300:
        raise PushError('HTTP %d' % st)


SENDERS = {'unifiedpush': send_unifiedpush}
REQUIRED_ENV = {'unifiedpush': ()}


def configured(provider):
    return all(os.environ.get(k) for k in REQUIRED_ENV.get(provider, ('-',)))


def send(entry, kind, item=None, retries=2):
    """发一条；失败按 1s、3s 退避重试，permanent 失败不重试。返回 (ok, permanent, info)"""
    provider = entry.get('provider')
    if provider not in SENDERS or not configured(provider):
        return False, False, '通道未配置'
    title, body, data = build_message(kind, item)
    urgent = kind in ('call', 'test')
    delay = 1
    for attempt in range(retries + 1):
        try:
            SENDERS[provider](entry, title, body, data, urgent)
            return True, False, 'ok'
        except PushError as e:
            if e.permanent or attempt == retries:
                return False, e.permanent, str(e)
        except (OSError, ValueError) as e:
            if attempt == retries:
                return False, False, type(e).__name__
        time.sleep(delay)
        delay *= 3
    return False, False, 'unreachable'
