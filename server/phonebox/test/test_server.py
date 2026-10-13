#!/usr/bin/env python3
"""phonebox 端到端测试：podman（或 docker）起 pb7-ami（假 AMI）和 pb7-phonebox（server.py），从宿主机发请求。

用法（在 server/ 下）：python3 -m unittest -v phonebox/test/test_server.py
镜像默认 python:3.12-slim，可用 PB7_IMAGE 换成镜像站地址。
数据全部是虚构号码（138000000xx 段等），PIN 是测试专用的随机值。测试结束删除容器和临时目录。
"""
import hashlib
import http.client
import json
import os
import secrets
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
PB_DIR = Path(os.environ.get('PB7_APP', HERE.parent))
IMAGE = os.environ.get('PB7_IMAGE', 'python:3.12-slim')
ENGINE = shutil.which('podman') or 'docker'
PORT, AMI_PORT = 38443, 35038
ORIGIN = f'http://127.0.0.1:{PORT}'
PIN = f'{secrets.randbelow(10**6):06d}'
WORK = None


def sh(*a, check=True):
    return subprocess.run(a, check=check, capture_output=True, text=True)


def setUpModule():
    global WORK
    WORK = Path(tempfile.mkdtemp(prefix='pb7-'))
    sh('python3', str(HERE / 'gen-data.py'), str(WORK))
    (WORK / 'state').mkdir(exist_ok=True)
    (WORK / 'state' / 'passcode').write_text(PIN + '\n')
    (WORK / 'ami-secret').write_text(secrets.token_hex(8) + '\n')
    (WORK / 'ami.log').write_text('')
    os.chmod(WORK, 0o755)
    for n in ('pb7-ami', 'pb7-phonebox'):
        sh(ENGINE, 'rm', '-f', n, check=False)
    sh(ENGINE, 'run', '-d', '--name', 'pb7-ami', '--network', 'host',
       '-v', f'{HERE}:/t:ro,z', '-v', f'{WORK}:/w:z',
       IMAGE, 'python3', '/t/fake_ami.py', str(AMI_PORT), '/w/ami-secret', '/w/ami.log')
    sh(ENGINE, 'run', '-d', '--name', 'pb7-phonebox', '--network', 'host',
       '-v', f'{PB_DIR}:/app:ro,z', '-v', f'{HERE}/model:/model:ro,z',
       '-v', f'{WORK}/mast.db:/mast.db:ro,z', '-v', f'{WORK}/data:/data:z',
       '-v', f'{WORK}/state:/state:z', '-v', f'{WORK}/ami-secret:/run/ami-secret:ro,z',
       '-e', 'PHONEBOX_AMI_HOST=127.0.0.1', '-e', 'PHONEBOX_MAST=/mast.db', '-e', f'PHONEBOX_AMI_PORT={AMI_PORT}',
       '-e', f'PHONEBOX_ORIGIN={ORIGIN}', '-e', 'PHONEBOX_BIND=127.0.0.1', '-e', f'PHONEBOX_PORT={PORT}',
       '-e', 'PHONEBOX_TELO_URL=http://127.0.0.1:9/', '-e', 'PHONEBOX_SSE_PING=2',
       IMAGE, 'python3', '/app/server.py')
    for _ in range(40):
        try:
            if req('GET', '/health')[0] == 200:
                return
        except OSError:
            pass
        time.sleep(0.25)
    raise RuntimeError('phonebox 没起来：' + sh(ENGINE, 'logs', 'pb7-phonebox', check=False).stderr)


def tearDownModule():
    for n in ('pb7-ami', 'pb7-phonebox'):
        sh(ENGINE, 'rm', '-f', n, check=False)
    # 容器里以 root 写过的文件宿主机可能删不掉，交给 podman unshare
    sh(ENGINE, 'unshare', 'rm', '-rf', str(WORK), check=False)
    shutil.rmtree(WORK, ignore_errors=True)


def req(method, path, body=None, headers=None, timeout=10):
    c = http.client.HTTPConnection('127.0.0.1', PORT, timeout=timeout)
    h = dict(headers or {})
    if isinstance(body, (dict, list)):
        body = json.dumps(body)
        h.setdefault('Content-Type', 'application/json')
    c.request(method, path, body=body, headers=h)
    r = c.getresponse()
    data = r.read()
    c.close()
    return r.status, dict(r.getheaders()), data


def get_token(name='test'):
    st, _, b = req('POST', '/api/token', f'code={PIN}&name={name}',
                   {'Content-Type': 'application/x-www-form-urlencoded', 'X-Phonebox': '1', 'Origin': ORIGIN})
    assert st == 200, st
    return json.loads(b)['token']


def bearer(t):
    return {'Authorization': f'Bearer {t}'}


def ami_cmds():
    return [json.loads(l) for l in (WORK / 'ami.log').read_text().splitlines() if l.strip()]


def append(name, row):
    with open(WORK / 'data' / name, 'a', encoding='utf-8') as f:
        f.write(json.dumps(row, ensure_ascii=False) + '\n')


def lookup(n, fmt=''):
    import urllib.parse
    st, _, b = req('GET', f'/api/lookup?n={urllib.parse.quote(n)}' + ('&fmt=txt' if fmt else ''))
    assert st == 200
    return b.decode() if fmt else json.loads(b)


class SSE:
    """最小 SSE 读取器：后台线程把事件放进列表。"""
    def __init__(self, headers):
        self.sock = socket.create_connection(('127.0.0.1', PORT), timeout=30)
        lines = [f'GET /api/events HTTP/1.1', 'Host: x'] + [f'{k}: {v}' for k, v in headers.items()]
        self.sock.sendall(('\r\n'.join(lines) + '\r\n\r\n').encode())
        self.f = self.sock.makefile('rb')
        self.status = int(self.f.readline().split()[1])
        self.headers = {}
        while True:
            l = self.f.readline().decode().strip()
            if not l:
                break
            k, _, v = l.partition(':')
            self.headers[k.lower()] = v.strip()
        self.events = []
        if self.status == 200:
            threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        cur = {}
        try:
            for raw in self.f:
                l = raw.decode().rstrip('\n')
                if not l:
                    if cur:
                        cur['t'] = time.time()
                        self.events.append(cur)
                    cur = {}
                    continue
                k, _, v = l.partition(':')
                cur[k] = v[1:] if v.startswith(' ') else v
        except (OSError, ValueError):
            pass

    def wait(self, pred, timeout):
        end = time.time() + timeout
        while time.time() < end:
            for e in self.events:
                if pred(e):
                    return e
            time.sleep(0.05)
        return None

    def close(self):
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.sock.close()


class T1Token(unittest.TestCase):
    def test_issue_hash_revoke(self):
        tok = get_token('pixel')
        self.assertGreaterEqual(len(tok), 43)   # token_urlsafe(32) = 32 字节熵
        stored = json.loads((WORK / 'state' / 'tokens.json').read_text())
        key = hashlib.sha256(tok.encode()).hexdigest()
        self.assertIn(key, stored)
        self.assertNotIn(tok, (WORK / 'state' / 'tokens.json').read_text())
        self.assertEqual(stored[key]['name'], 'pixel')
        for f in ('created', 'last_used'):
            self.assertIsInstance(stored[key][f], int)
        self.assertEqual(req('GET', '/api/feed', headers=bearer(tok))[0], 200)
        self.assertEqual(req('DELETE', '/api/token', headers=bearer(tok))[0], 204)
        self.assertNotIn(key, json.loads((WORK / 'state' / 'tokens.json').read_text()))
        self.assertEqual(req('GET', '/api/feed', headers=bearer(tok))[0], 401)
        self.assertEqual(req('DELETE', '/api/token', headers=bearer(tok))[0], 401)
        self.assertEqual(req('DELETE', '/api/token')[0], 401)

    def test_token_request_constraints(self):
        form = {'Content-Type': 'application/x-www-form-urlencoded'}
        # 缺 X-Phonebox / Origin 不对：403，且不计入失败次数（没走到 PIN 校验）
        self.assertEqual(req('POST', '/api/token', f'code={PIN}', {**form, 'Origin': ORIGIN})[0], 403)
        self.assertEqual(req('POST', '/api/token', f'code={PIN}', {**form, 'X-Phonebox': '1', 'Origin': 'https://evil.example'})[0], 403)
        wrong = f'{(int(PIN) + 1) % 10**6:06d}'
        st, _, _ = req('POST', '/api/token', f'code={wrong}', {**form, 'X-Phonebox': '1', 'Origin': ORIGIN})
        self.assertEqual(st, 403)
        self.assertEqual(req('GET', '/api/feed', headers=bearer('nonsense'))[0], 401)

    def test_cookie_still_works(self):
        st, h, _ = req('POST', '/login', f'code={PIN}', {'Content-Type': 'application/x-www-form-urlencoded'})
        self.assertEqual(st, 303)
        ck = h['Set-Cookie'].split(';')[0]
        self.assertEqual(req('GET', '/api/feed', headers={'Cookie': ck})[0], 200)
        # cookie 写请求维持原有 Origin + X-Phonebox 检查
        body = {'to': '13800000001', 'text': 'cookie 测试'}
        self.assertEqual(req('POST', '/api/send', body, {'Cookie': ck})[0], 403)
        self.assertEqual(req('POST', '/api/lists', {'list': 'black', 'number': '13800000050', 'action': 'add'}, {'Cookie': ck})[0], 403)
        self.assertEqual(req('POST', '/api/send', body, {'Cookie': ck, 'Origin': ORIGIN, 'X-Phonebox': '1'})[0], 200)
        # 带了无效 Bearer 时不回退到 cookie
        self.assertEqual(req('GET', '/api/feed', headers={'Cookie': ck, **bearer('bad')})[0], 401)


class T2Feed(unittest.TestCase):
    def test_since(self):
        tok = get_token()
        full = json.loads(req('GET', '/api/feed', headers=bearer(tok))[2])
        since = sorted(s['ts'] for s in full['sms'])[-3]
        part = json.loads(req('GET', f'/api/feed?since={since}', headers=bearer(tok))[2])
        self.assertEqual(part['sms'], [s for s in full['sms'] if s['ts'] >= since])
        self.assertEqual(part['calls'], [c for c in full['calls'] if c['ts'] >= since])
        self.assertEqual(set(part), {'sms', 'calls', 'versions'})
        self.assertEqual(json.loads(req('GET', '/api/feed?since=abc', headers=bearer(tok))[2]), full)


class T3Events(unittest.TestCase):
    def test_push_replay_ping_and_limit(self):
        tok = get_token()
        self.assertEqual(SSE({}).status, 401)
        a = SSE(bearer(tok))
        self.assertEqual(a.status, 200)
        self.assertTrue(a.headers['content-type'].startswith('text/event-stream'))
        self.assertEqual(a.headers['cache-control'], 'no-store')
        time.sleep(0.3)
        now = int(time.time())
        row = {'ts': now, 'from': '13800000077', 'body': '【测试】您的验证码是 246810，5分钟内有效。', 'cat': '', 'level': 'high'}
        t0 = time.time()
        append('sms.jsonl', row)
        e1 = a.wait(lambda e: e.get('event') == 'sms', 3)
        self.assertIsNotNone(e1)
        self.assertLess(e1['t'] - t0, 1.0)
        # data 与 /api/feed 中的同一条完全相同
        feed = json.loads(req('GET', '/api/feed', headers=bearer(tok))[2])
        same = [s for s in feed['sms'] if s['ts'] == now and s['from'] == '13800000077']
        self.assertEqual(json.loads(e1['data']), same[0])
        self.assertRegex(e1['id'], r'^\d{13}-\d+$')
        # 截断后（日志轮转）新行照样推送，旧行不重复推
        (WORK / 'data' / 'calls.jsonl').write_text('')
        append('calls.jsonl', {'ts': now, 'number': '13800000078', 'dir': 'in', 'disposition': 'NO ANSWER', 'billsec': 0, 'tag': ''})
        e2 = a.wait(lambda e: e.get('event') == 'call', 3)
        self.assertIsNotNone(e2)
        self.assertEqual(json.loads(e2['data'])['number'], '13800000078')
        self.assertGreater(tuple(map(int, e2['id'].split('-'))), tuple(map(int, e1['id'].split('-'))))
        # ping：不带 id，data 为空
        p = a.wait(lambda e: e.get('event') == 'ping', 4)
        self.assertIsNotNone(p)
        self.assertNotIn('id', p)
        self.assertEqual(p.get('data'), '')
        a.close()
        # Last-Event-ID 补发 e1 之后的事件（即 e2）
        b = SSE({**bearer(tok), 'Last-Event-ID': e1['id']})
        r = b.wait(lambda e: e.get('id') == e2['id'], 2)
        self.assertIsNotNone(r)
        self.assertIsNone(b.wait(lambda e: e.get('id') == e1['id'], 0.3))
        b.close()
        time.sleep(1.5)
        # 并发上限 8：第 9 个 503；关掉一个后又能连
        conns = [SSE(bearer(tok)) for _ in range(8)]
        self.assertTrue(all(c.status == 200 for c in conns))
        extra = SSE(bearer(tok))
        self.assertEqual(extra.status, 503)
        extra.close()
        conns[0].close()
        ok = None
        for _ in range(6):   # 服务端每秒探测读端 EOF，断开后 1 秒内释放槽位
            time.sleep(0.5)
            s = SSE(bearer(tok))
            if s.status == 200:
                ok = s
                break
            s.close()
        self.assertIsNotNone(ok)
        for c in conns[1:] + [ok]:
            c.close()


INJECT = ['13800000001\r\nAction: Originate', '13800000001 1', '138000"00001', '13800000001;', '1380000000\n',
          "1380'0000001", '13800000001\x00', '+86 13800000001', '12', '1' * 21, '+', '', None, 13800000001]


class T4Lists(unittest.TestCase):
    def test_lists_and_injection(self):
        tok = get_token()
        h = bearer(tok)
        before = len(ami_cmds())
        for bad in INJECT:
            st, _, b = req('POST', '/api/lists', {'list': 'black', 'number': bad, 'action': 'add'}, h)
            self.assertEqual(st, 400, repr(bad))
        for bad in [{'list': 'grey', 'number': '13800000001', 'action': 'add'},
                    {'list': 'black', 'number': '13800000001', 'action': 'drop'}, ['x']]:
            self.assertEqual(req('POST', '/api/lists', bad, h)[0], 400)
        self.assertEqual(len(ami_cmds()), before, '非法输入不应产生任何 AMI 命令')

        self.assertEqual(req('POST', '/api/lists', {'list': 'black', 'number': '+8613800000051', 'action': 'add'}, h)[0], 200)
        self.assertEqual(req('POST', '/api/lists', {'list': 'white', 'number': '13800000052', 'action': 'add'}, h)[0], 200)
        self.assertIn('database put blacklist +8613800000051 1', [c['cmd'] for c in ami_cmds()])
        d = json.loads(req('GET', '/api/lists', headers=h)[2])
        self.assertIn('+8613800000051', d['black'])
        self.assertIn('13800000052', d['white'])
        self.assertTrue(d['ok'])
        self.assertEqual(req('POST', '/api/lists', {'list': 'white', 'number': '13800000052', 'action': 'del'}, h)[0], 200)
        self.assertNotIn('13800000052', json.loads(req('GET', '/api/lists', headers=h)[2])['white'])
        for c in ami_cmds():
            self.assertNotIn('unexpected', c)
            self.assertRegex(c['cmd'], r'^(database (put|del|show) (blacklist|whitelist)( \+?[0-9]{3,20}( 1)?)?|quectel sms .*)$')
        self.assertEqual(req('GET', '/api/lists')[0], 401)


class T5Lookup(unittest.TestCase):
    def test_order_and_hint_only(self):
        tok = get_token()
        h = bearer(tok)
        spam = '4000000001'   # 合成号码库里标为"广告推销"
        self.assertTrue(lookup(spam, 'txt').startswith('BLOCK|广告推销|'))
        # 黑名单 > 离线库
        req('POST', '/api/lists', {'list': 'black', 'number': '13800000061', 'action': 'add'}, h)
        self.assertTrue(lookup('13800000061', 'txt').startswith('BLOCK|黑名单|'))
        self.assertTrue(lookup('+8613800000061', 'txt').startswith('BLOCK|黑名单|'))
        # 白名单 > 黑名单 > 离线库
        req('POST', '/api/lists', {'list': 'black', 'number': spam, 'action': 'add'}, h)
        req('POST', '/api/lists', {'list': 'white', 'number': spam, 'action': 'add'}, h)
        self.assertEqual(lookup(spam)['src'], '白名单')
        self.assertTrue(lookup(spam, 'txt').startswith('PASS|'))
        req('POST', '/api/lists', {'list': 'white', 'number': spam, 'action': 'del'}, h)
        self.assertEqual(lookup(spam)['src'], '黑名单')
        # 通讯录 > 一切
        hs = [hashlib.sha256(n.encode()).hexdigest() for n in (spam, '13800000061')]
        st, _, b = req('POST', '/api/contacts', {'hashes': hs}, h)
        self.assertEqual((st, json.loads(b)['count']), (200, 2))
        self.assertEqual(lookup(spam)['src'], '通讯录')
        self.assertEqual(lookup('+86 138-0000-0061')['src'], '通讯录')
        self.assertTrue(lookup('13800000061', 'txt').startswith('PASS|'))
        self.assertEqual(req('POST', '/api/contacts', ['zz'], h)[0], 400)
        self.assertEqual(req('POST', '/api/contacts', ['0' * 64] * 5001, h)[0], 400)
        self.assertEqual(req('POST', '/api/contacts', [], h)[0], 200)   # 整表替换
        self.assertTrue(json.loads((WORK / 'state' / 'contacts.json').read_text())['hashes'] == [])
        req('POST', '/api/lists', {'list': 'black', 'number': spam, 'action': 'del'}, h)
        # 仅提示不拦截：号码库判定变 PASS 但带标签；用户黑名单照拦
        self.assertEqual(req('POST', '/api/settings', {'hint_only': True}, h)[0], 200)
        self.assertTrue(json.loads((WORK / 'state' / 'settings.json').read_text())['hint_only'])
        self.assertTrue(lookup(spam, 'txt').startswith('PASS|广告推销|'))
        self.assertTrue(lookup(spam)['hint'])
        self.assertTrue(lookup('13800000061', 'txt').startswith('BLOCK|黑名单|'))
        self.assertEqual(json.loads(req('GET', '/api/settings', headers=h)[2]), {'hint_only': True})
        self.assertEqual(req('POST', '/api/settings', {'hint_only': 'yes'}, h)[0], 400)
        req('POST', '/api/settings', {'hint_only': False}, h)
        self.assertTrue(lookup(spam, 'txt').startswith('BLOCK|广告推销|'))


class T6Send(unittest.TestCase):
    def test_bearer_send_sanitize_and_rate_limit(self):
        tok = get_token()
        h = bearer(tok)   # 不带 Origin / X-Phonebox
        evil = '你好"\x1b[31m\x00\x07 end\\'
        st, _, b = req('POST', '/api/send', {'to': '13800000001', 'text': evil}, h)
        self.assertEqual((st, json.loads(b)['ok']), (200, True))
        cmd = [c['cmd'] for c in ami_cmds() if c['cmd'].startswith('quectel')][-1]
        self.assertEqual(cmd, 'quectel sms quectel0 13800000001 "你好\' [31m   end\\\\"')
        for bad in ('138000\n00001', '138 00000001', '1380000000"1'):
            self.assertEqual(req('POST', '/api/send', {'to': bad, 'text': 'x'}, h)[0], 400)
        self.assertEqual(req('POST', '/api/send', {'to': '13800000001', 'text': 'x' * 601}, h)[0], 400)
        tok2 = get_token()
        codes = [req('POST', '/api/send', {'to': '13800000001', 'text': f'第{i}条'}, bearer(tok2))[0] for i in range(11)]
        self.assertEqual(codes[:10], [200] * 10)
        self.assertEqual(codes[10], 429)
        # 限流按令牌计：另一个令牌不受影响
        self.assertEqual(req('POST', '/api/send', {'to': '13800000001', 'text': 'y'}, bearer(get_token()))[0], 200)


class T9Lockout(unittest.TestCase):
    def test_token_shares_login_lockout(self):
        form = {'Content-Type': 'application/x-www-form-urlencoded', 'X-Phonebox': '1', 'Origin': ORIGIN}
        wrong = f'{(int(PIN) + 1) % 10**6:06d}'
        codes = [req('POST', '/api/token', f'code={wrong}', form)[0] for _ in range(5)]
        self.assertIn(429, [req('POST', '/api/token', f'code={PIN}', form)[0]])
        st, h, _ = req('POST', '/login', f'code={PIN}', {'Content-Type': 'application/x-www-form-urlencoded'})
        self.assertEqual(st, 429)
        self.assertGreater(int(h['Retry-After']), 0)
        self.assertTrue(all(c in (403, 429) for c in codes))


if __name__ == '__main__':
    unittest.main()
