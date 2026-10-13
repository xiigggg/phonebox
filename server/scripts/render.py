#!/usr/bin/env python3
"""把 asterisk/templates/*.tmpl 渲染成 Asterisk 配置，由 init.sh 调用。

用法：render.py <模板目录> <输出目录> <变量文件>...（后面的文件覆盖前面的同名变量）
语法：{{VAR}} 替换；{{#VAR}}...{{/VAR}} 在 VAR 为真（非空且不是 no/false/0）时保留。
模板里用到却没定义的变量直接报错，免得渲染出半残配置。
"""
import os
import re
import sys
from pathlib import Path

FALSY = {'', '0', 'no', 'false', 'off'}


def load_env(path):
    out = {}
    for line in Path(path).read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith('#') or '=' not in line:
            continue
        k, v = line.split('=', 1)
        v = v.strip()
        if len(v) >= 2 and v[0] == v[-1] and v[0] in '"\'':
            v = v[1:-1]
        out[k.strip()] = v
    return out


def render(text, env, name):
    block = re.compile(r'\{\{#(\w+)\}\}\n?(.*?)\{\{/\1\}\}\n?', re.S)
    while True:
        new = block.sub(lambda m: m.group(2) if env.get(m.group(1), '').strip().lower() not in FALSY else '', text)
        if new == text:
            break
        text = new

    def var(m):
        k = m.group(1)
        if k not in env:
            raise SystemExit(f'{name}: 变量 {k} 未定义（检查 .env）')
        return env[k]
    return re.sub(r'\{\{(\w+)\}\}', var, text)


def main():
    tdir, odir, *envs = sys.argv[1:]
    env = {}
    for e in envs:
        env.update(load_env(e))
    for k, v in env.items():
        # 配置文件和 shell 片段都按行解析，换行和引号会破坏结构
        if any(c in v for c in '\n\r\'"'):
            raise SystemExit(f'变量 {k} 含有换行或引号，不允许')
    os.makedirs(odir, exist_ok=True)
    for t in sorted(Path(tdir).glob('*.tmpl')):
        out = Path(odir) / t.name[:-5]
        tmp = out.with_suffix(out.suffix + '.new')
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, 'w', encoding='utf-8') as f:
            f.write(render(t.read_text(encoding='utf-8'), env, t.name))
        os.replace(tmp, out)
        print(f'  渲染 {out}')


if __name__ == '__main__':
    main()
