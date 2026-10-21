#!/usr/bin/env python3
"""push.py 的纯函数测试，不起容器。用法（在 server/ 下）：python3 -m unittest -v phonebox/test/test_push.py
WebPush 加密用 RFC 8291 附录 A 的官方中间值逐字节核对；本机没装 cryptography 时跳过这一条。"""
import base64
import hashlib
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import push  # noqa: E402


def b64(s):
    s = s.replace(' ', '')
    return base64.urlsafe_b64decode(s + '=' * (-len(s) % 4))


class TMessage(unittest.TestCase):
    item = {'from': '13800000077', 'body': '您的验证码是 246810', 'kind': '验证码', 'sig': ''}

    def test_levels(self):
        self.assertEqual(push.build_message('sms', self.item, 'wake'), ('电话信箱', '有新短信', {'t': 'sms'}))
        t, b, _ = push.build_message('sms', self.item, 'preview')
        self.assertEqual((t, b), ('新短信', '来自 尾号0077 · 验证码'))
        self.assertNotIn('246810', t + b)
        t, b, _ = push.build_message('sms', self.item, 'full')
        self.assertIn('246810', b)
        self.assertEqual(push.build_message('call')[2], {'t': 'call'})

    def test_mask(self):
        self.assertEqual(push.mask_sender('+8613800000077'), '尾号0077')
        self.assertEqual(push.mask_sender('95588'), '95588')
        self.assertEqual(push.mask_sender('【某银行】'), '【某银行】')


class TValidate(unittest.TestCase):
    def test_entries(self):
        ok, err = push.validate_entry({'provider': 'hms', 'token': 'A' * 40})
        self.assertIsNone(err)
        self.assertEqual(ok, {'provider': 'hms', 'token': 'A' * 40})
        for bad in ({'provider': 'xx', 'token': 'A' * 40}, {'provider': 'hms', 'token': 'short'},
                    {'provider': 'hms', 'token': 'A' * 20 + '\nB'}, {'provider': 'hms', 'token': 7}, []):
            self.assertIsNotNone(push.validate_entry(bad)[1], bad)

    def test_unifiedpush_endpoint(self):
        os.environ.pop('PUSH_UP_ALLOW_HTTP', None)
        self.assertIsNone(push.validate_entry({'provider': 'unifiedpush', 'token': 'https://ntfy.example.com/upAbc123'})[1])
        self.assertEqual(push.validate_entry({'provider': 'unifiedpush', 'token': 'http://192.168.1.2/up'})[1], 'invalid_endpoint')
        self.assertEqual(push.validate_entry({'provider': 'unifiedpush', 'token': 'file:///etc/passwd'})[1], 'invalid_endpoint')
        keys = {'pub': base64.urlsafe_b64encode(b'\x04' + b'\x01' * 64).decode(), 'auth': base64.urlsafe_b64encode(b'\x02' * 16).decode()}
        e, err = push.validate_entry({'provider': 'unifiedpush', 'token': 'https://ntfy.example.com/up1', **keys})
        self.assertIsNone(err)
        self.assertEqual(e['pub'], keys['pub'])
        self.assertEqual(push.validate_entry({'provider': 'unifiedpush', 'token': 'https://n.example.com/up1', 'pub': 'AAAA', 'auth': keys['auth']})[1], 'invalid_keys')


class TRegistry(unittest.TestCase):
    def test_register_replace_prune(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / 'push.json'
            r = push.Registry(p)
            r.register('dev1', {'provider': 'hms', 'token': 't1' * 8})
            r.register('dev1', {'provider': 'hms', 'token': 't2' * 8})   # 同通道覆盖
            r.register('dev1', {'provider': 'unifiedpush', 'token': 'https://x.example.com/1'})
            r.register('dev2', {'provider': 'hms', 'token': 't3' * 8})
            self.assertEqual([e['token'] for e in r.devices()['dev1']], ['t2' * 8, 'https://x.example.com/1'])
            self.assertEqual(oct(p.stat().st_mode & 0o777), '0o600')
            r.drop_token('dev1', 't2' * 8)
            r.prune({'dev1'})
            r2 = push.Registry(p)
            self.assertEqual(list(r2.devices()), ['dev1'])
            self.assertEqual(r2.devices()['dev1'][0]['provider'], 'unifiedpush')


class TVendorBodies(unittest.TestCase):
    def test_hms_sms_is_notification_call_is_data_only(self):
        b = push.hms_body('tok', '新短信', '来自 95588', {'t': 'sms'}, False)
        m = b['message']
        self.assertEqual(m['token'], ['tok'])
        self.assertEqual(m['android']['notification']['title'], '新短信')
        self.assertEqual(json.loads(m['data']), {'t': 'sms'})
        c = push.hms_body('tok', 'x', 'y', {'t': 'call'}, True)['message']
        self.assertNotIn('notification', c)
        self.assertEqual(c['android']['urgency'], 'HIGH')

    def test_meizu_sign(self):
        p = {'appId': '100', 'pushIds': 'abc', 'messageJson': '{}'}
        expect = hashlib.md5(b'appId=100messageJson={}pushIds=abcSECRET').hexdigest()
        self.assertEqual(push.meizu_sign(p, 'SECRET'), expect)
        params = push.meizu_params('abc', 't', 'b', {}, '100', 'SECRET')
        self.assertEqual(params['sign'], push.meizu_sign({k: v for k, v in params.items() if k != 'sign'}, 'SECRET'))


class TSend(unittest.TestCase):
    def test_unconfigured_is_skipped(self):
        os.environ.pop('HMS_APP_ID', None)
        self.assertEqual(push.send({'provider': 'hms', 'token': 'x' * 10}, 'sms'), (False, False, '通道未配置'))

    def test_retry_then_permanent(self):
        calls = []

        def flaky(entry, *a):
            calls.append(1)
            if len(calls) == 1:
                raise push.PushError('HTTP 500')
            raise push.PushError('gone', permanent=True)
        old = push.SENDERS['unifiedpush']
        push.SENDERS['unifiedpush'] = flaky
        try:
            push.time.sleep, sleep = (lambda s: None), push.time.sleep
            self.assertEqual(push.send({'provider': 'unifiedpush', 'token': 'https://x.example.com/1'}, 'sms'), (False, True, 'gone'))
            self.assertEqual(len(calls), 2)
        finally:
            push.SENDERS['unifiedpush'] = old
            push.time.sleep = sleep


class TWebPush(unittest.TestCase):
    def test_rfc8291_appendix_a(self):
        try:
            from cryptography.hazmat.primitives.asymmetric import ec
        except ImportError:
            self.skipTest('没装 cryptography')
        as_priv = ec.derive_private_key(int.from_bytes(b64('yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw'), 'big'), ec.SECP256R1())
        out = push.webpush_encrypt(
            b64('V2hlbiBJIGdyb3cgdXAsIEkgd2FudCB0byBiZSBhIHdhdGVybWVsb24')[:],
            'BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4',
            'BTBZMqHH6r4Tts7J_aSIgg', salt=b64('DGv6ra1nlYgDCS1FRnbzlw'), server_key=as_priv)
        header = b64('DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z 9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml mlMoZIIgDll6e3vCYLocInmYWAmS6Tlz AC8wEqKK6PBru3jl7A8')
        cipher = b64('8pfeW0KbunFT06SuDKoJH9Ql87S1QUrd irN6GcG7sFz1y1sqLgVi1VhjVkHsUoEs bI_0LpXMuGvnzQ')
        self.assertEqual(out, header + cipher)


if __name__ == '__main__':
    unittest.main()
