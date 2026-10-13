#!/usr/bin/env python3
"""假 AMI：模仿 Asterisk 20 的 Command 响应格式，内存里维护一个 astdb，把收到的每条命令记到日志。

用法：fake_ami.py <端口> <口令文件> <命令日志>
只实现 phonebox 用到的命令：database put/del/show、quectel sms。
"""
import json
import socketserver
import sys
import threading

PORT, SECRET_FILE, LOG = int(sys.argv[1]), sys.argv[2], sys.argv[3]
SECRET = open(SECRET_FILE).read().strip()
DB = {}
LOCK = threading.Lock()


def run_cli(cmd):
    p = cmd.split()
    if p[:2] == ['database', 'put'] and len(p) == 5:
        DB[f'/{p[2]}/{p[3]}'] = p[4]
        return ['Updated database successfully']
    if p[:2] == ['database', 'del'] and len(p) == 4:
        return ['Database entry removed.'] if DB.pop(f'/{p[2]}/{p[3]}', None) else ['Database entry does not exist.']
    if p[:2] == ['database', 'show'] and len(p) == 3:
        rows = [f'{k:<40}: {v}' for k, v in sorted(DB.items()) if k.startswith(f'/{p[2]}/')]
        return rows + [f'{len(rows)} results found.']
    if p[:2] == ['quectel', 'sms']:
        return ['[quectel0] SMS queued for send with id 0x1']
    return [f'No such command \'{cmd}\'']


class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        self.wfile.write(b'Asterisk Call Manager/9.0.0\r\n')
        authed = False
        while True:
            hdr = {}
            while True:
                line = self.rfile.readline()
                if not line:
                    return
                line = line.decode('utf-8', 'replace').rstrip('\r\n')
                if not line:
                    break
                k, _, v = line.partition(':')
                hdr[k.strip().lower()] = v.strip()
            act = hdr.get('action', '').lower()
            if act == 'login':
                authed = hdr.get('secret') == SECRET
                msg = 'Response: Success\r\nMessage: Authentication accepted\r\n\r\n' if authed \
                    else 'Response: Error\r\nMessage: Authentication failed\r\n\r\n'
                self.wfile.write(msg.encode())
            elif act == 'command' and authed:
                cmd = hdr.get('command', '')
                with LOCK:
                    with open(LOG, 'a', encoding='utf-8') as f:
                        f.write(json.dumps({'cmd': cmd, 'raw': hdr}, ensure_ascii=False) + '\n')
                    out = run_cli(cmd)
                body = 'Response: Success\r\nMessage: Command output follows\r\n'
                body += ''.join(f'Output: {o}\r\n' for o in out) + '\r\n'
                self.wfile.write(body.encode())
            elif act == 'logoff':
                self.wfile.write(b'Response: Goodbye\r\nMessage: Thanks for all the fish.\r\n\r\n')
                return
            else:
                with open(LOG, 'a', encoding='utf-8') as f:
                    f.write(json.dumps({'unexpected': hdr}, ensure_ascii=False) + '\n')
                self.wfile.write(b'Response: Error\r\nMessage: Invalid/unknown command\r\n\r\n')


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


Server(('127.0.0.1', PORT), Handler).serve_forever()
