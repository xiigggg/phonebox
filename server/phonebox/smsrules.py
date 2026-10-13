"""短信 / 来电的本地识别（电话信箱网页与 App 接口共用）。

- 短信：PixelText 官方 rules.json（/model/rules.json，随模型每周自动更新）识别验证码、快递、动账、车票、
  航班、流量等卡片；没有命中规则时，只在正文自称验证码/动态码时才回退到数字匹配。
- 分类：骚扰 > 验证码 > 快递 > 金融 > 运营商 > 出行 > 个人 > 通知，一条短信只归一类。
- 来电：PixelTelo 离线号码库（/mast/mast.db）标签 + 号码形态。
全部在本机完成，正文与号码不出本机。
"""
import json
import os
import re
import sqlite3
import threading
import time
import urllib.request
from pathlib import Path

RULES_PATH = Path(os.environ.get('PHONEBOX_RULES', os.path.join(os.environ.get('PHONEBOX_MODEL_DIR', '/model'), 'rules.json')))
MAST_PATH = Path(os.environ.get('PHONEBOX_MAST', '/mast/mast.db'))

CODE = re.compile(r'(?<!\d)(\d{4,8})(?!\d)')
CODE_HINT = re.compile(r'验证码|校验码|动态码|动态密码|随机码|确认码|安全码|verification|code|OTP', re.I)
SIG = re.compile(r'【([^】]{1,20})】')
PERSONAL = re.compile(r'(?:\+86)?1[3-9]\d{9}')

CARD_LABEL = {'VerificationCode': '验证码', 'ExpressDelivery': '快递', 'BankTransaction': '银行动账',
              'TrainTicket': '火车票', 'Flight': '航班', 'DataUsage': '流量', 'MissedCall': '未接提醒'}
BLOCK_TAGS = {'骚扰电话', '广告推销', '疑似欺诈', '诈骗电话', '响一声', '广告营销', '营销推广', '营销推广电话',
              '房产中介', '保险理财', '招聘猎头', '诈骗', '骚扰'}

# ---------------- PixelText 规则 ----------------
_rules_cache = {'mtime': None, 'rules': []}


def _compile_rules():
    try:
        st = RULES_PATH.stat()
    except OSError:
        return []
    if _rules_cache['mtime'] == st.st_mtime_ns:
        return _rules_cache['rules']
    try:
        raw = json.loads(RULES_PATH.read_text(encoding='utf-8'))['rules']
    except (OSError, ValueError, KeyError):
        return _rules_cache['rules']
    out = []
    for r in sorted(raw, key=lambda r: -int(r.get('priority') or 0)):
        pat = (r.get('conditions') or {}).get('content_regex')
        if not pat:
            continue
        try:   # 规则用 Kotlin 命名分组 (?<name>…)，转成 Python 写法；不兼容的规则跳过
            rx = re.compile(re.sub(r'\(\?<([A-Za-z_]\w*)>', r'(?P<\1>', pat), re.S)
        except re.error:
            continue
        ff = r.get('fast_fail') or {}
        out.append((r['target_card'], rx, ff.get('keywords') or [], ff.get('signature_equals')))
    _rules_cache.update(mtime=st.st_mtime_ns, rules=out)
    return out


def rules_version():
    try:
        return (RULES_PATH.parent / 'rules.version').read_text().strip()
    except OSError:
        return ''


def signature(body):
    m = SIG.search(body or '')
    return m.group(1).strip() if m else ''


def classify(body):
    """返回 (卡片类型, 命名分组字典)；没有命中返回 ('', {})。"""
    sigs = set(SIG.findall(body))
    for card, rx, keywords, sig in _compile_rules():
        if sig and sig not in sigs:
            continue
        if any(k not in body for k in keywords):
            continue
        m = rx.search(body)
        if m:
            return card, {k: v.strip() for k, v in m.groupdict().items() if v}
    return '', {}


def summarize(card, g):
    """卡片的一行摘要与码的称呼（验证码 / 取件码）。"""
    if card == 'ExpressDelivery':
        return ' · '.join(x for x in (g.get('company'), g.get('location')) if x), '取件码'
    if card == 'BankTransaction':
        return (f"{g.get('type', '')} ¥{g['amount']}".strip() if g.get('amount') else g.get('details', '')), ''
    if card == 'TrainTicket':
        return ' '.join(x for x in (g.get('trainNo'), g.get('departureStation'), g.get('departureTime')) if x), ''
    if card == 'Flight':
        route = f"{g.get('departureCity', '')}→{g.get('arrivalCity', '')}" if g.get('departureCity') else ''
        return ' '.join(x for x in (g.get('flightNo'), route, g.get('departureTime')) if x), ''
    if card == 'DataUsage':
        return ' '.join(x for x in (g.get('planName'), g.get('usedData') or g.get('remainingData')) if x), ''
    return '', ('验证码' if card == 'VerificationCode' else '')


def analyze_sms(body, sender='', cat=''):
    """一条短信的完整识别结果。"""
    body = body or ''
    card, groups = classify(body)
    if groups.get('code'):
        code = groups['code']
    else:
        found = CODE.search(body)
        code = found.group(1) if found and CODE_HINT.search(body) else None
    summary, code_label = summarize(card, groups)
    label = CARD_LABEL.get(card, '')
    cat = cat or ''
    if cat.startswith('骚扰'):
        kind = '骚扰'
    elif code and label in ('', '验证码'):
        kind = '验证码'
    elif label == '快递':
        kind = '快递'
    elif label == '银行动账' or cat == '金融':
        kind = '金融'
    elif label == '流量' or cat == '运营商':
        kind = '运营商'
    elif label in ('火车票', '航班'):
        kind = '出行'
    elif PERSONAL.fullmatch((sender or '').strip()):
        kind = '个人'
    else:
        kind = '通知'
    return {'kind': kind, 'card': label, 'code': code,
            'code_label': code_label or ('验证码' if code else ''),
            'summary': summary[:48], 'sig': signature(body)}


# ---------------- PixelTelo 号码库 ----------------
_mast = {'conn': None, 'mtime': None}
# 同一个 sqlite 连接被多个请求线程和事件流线程共用，并发 execute 会报 InterfaceError，所以串行化
_mast_lock = threading.Lock()


def lookup_tag(number):
    """号码库标签（只读）；库不存在或未命中返回空串。号码库每周替换，按修改时间重连。"""
    try:
        mtime = MAST_PATH.stat().st_mtime_ns
    except OSError:
        return ''
    n = (number or '').strip()
    cands = [n]
    if n.startswith('+86'):
        cands.append(n[3:])
    elif not n.startswith('+'):
        cands.append('+86' + n)
    with _mast_lock:
        if _mast['conn'] is None or _mast['mtime'] != mtime:
            _mast['conn'] = sqlite3.connect(f'file:{MAST_PATH}?mode=ro', uri=True, check_same_thread=False)
            _mast['mtime'] = mtime
        for c in cands:
            row = _mast['conn'].execute('SELECT tag FROM spam_numbers WHERE phone_number=?', (c,)).fetchone()
            if row:
                return row[0]
    return ''


def number_kind(number):
    """号码形态（号码库未命中时用来描述来电性质）。"""
    n = (number or '').strip()
    if n.startswith('+86'):
        n = n[3:]
    elif n.startswith('0086'):
        n = n[4:]
    if re.fullmatch(r'17[01]\d{8}', n) or re.fullmatch(r'16[2-7]\d{8}', n):
        return '虚拟运营商'
    if re.fullmatch(r'1[3-9]\d{9}', n):
        return '手机'
    if n.startswith('+') and not n.startswith('+86'):
        return '国际号码'
    if re.fullmatch(r'\d{12,}', n):
        return '异常长号码'
    if re.fullmatch(r'(400|800)\d{7}', n):
        return '企业热线'
    if re.fullmatch(r'010\d{7,8}', n) or re.fullmatch(r'[2-8]\d{7}', n):
        return '北京固话'
    if re.fullmatch(r'0[2-9]\d{8,10}', n):
        return '外地固话'
    if re.fullmatch(r'(95|96|12|10)\d{1,6}', n):
        return '服务号'
    return ''


# ---------------- PixelTelo 在线查询（本地库未收录时） ----------------
# 官方实时查询接口：只发送号码本身；失败或超时一律放行（Fail Open）。结果缓存在 /state，避免重复查询。
# PHONEBOX_TELO_URL 可覆盖在线查询地址（默认是 PixelTelo 官方接口）；测试时指向不可达端口，
# 让查询立即失败放行，绝不从测试环境联网查号码。另：PHONEBOX_RULES / PHONEBOX_MAST / PHONEBOX_STATE 覆盖路径。
ONLINE_URL = os.environ.get('PHONEBOX_TELO_URL', 'https://pixeltelo.api.mystery0.vip/api/v2/query')
CACHE_PATH = Path(os.environ.get('PHONEBOX_STATE', '/state')) / 'telo-cache.json'
TTL_HIT, TTL_MISS = 30 * 86400, 7 * 86400
ONLINE_BLOCK_CONFIDENCE = 80
_cache = {'data': None}
_cache_lock = threading.Lock()
_prefetch = {'running': False}


def _norm(number):
    n = re.sub(r'[^0-9+]', '', number or '')
    return n[3:] if n.startswith('+86') else n


def _cache_data():
    if _cache['data'] is None:
        try:
            _cache['data'] = json.loads(CACHE_PATH.read_text(encoding='utf-8'))
        except (OSError, ValueError):
            _cache['data'] = {}
    return _cache['data']


def _cache_save():
    tmp = CACHE_PATH.with_name(CACHE_PATH.name + '.tmp')
    try:
        tmp.write_text(json.dumps(_cache['data'], ensure_ascii=False), encoding='utf-8')
        tmp.replace(CACHE_PATH)
    except OSError:
        pass


def cached_online(number):
    """只读缓存（不联网）；过期返回 None。"""
    n = _norm(number)
    with _cache_lock:
        e = _cache_data().get(n)
    if not e:
        return None
    ttl = TTL_HIT if e.get('tag') else TTL_MISS
    return e if time.time() - e.get('t', 0) < ttl else None


def online_query(number, timeout=2.0):
    """在线查询（先查缓存）。国际号码与过短号码不查。失败返回 None。"""
    n = _norm(number)
    if len(n) < 5 or n.startswith('+'):
        return None
    hit = cached_online(n)
    if hit:
        return hit
    try:
        req = urllib.request.Request(ONLINE_URL, data=json.dumps({'number': n, 'sources': []}).encode(),
                                     headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            d = json.loads(r.read().decode('utf-8'))
    except Exception:
        return None
    info = d.get('data') or {}
    city = info.get('city') if info.get('city') != info.get('province') else ''
    loc = ' '.join(x for x in (info.get('province'), city, info.get('cardType')) if x)
    e = {'t': time.time(), 'tag': str(d.get('tag') or ''),
         'spam': bool(d.get('is_spam')), 'conf': int(d.get('confidence') or 0),
         'src': str(d.get('source') or ''), 'loc': loc[:24]}
    with _cache_lock:
        _cache_data()[n] = e
        _cache_save()
    return e


def prefetch(numbers, budget=40, gap=0.4):
    """后台补查未缓存的号码（每次最多 budget 个、间隔 gap 秒），不阻塞页面。"""
    if _prefetch['running']:
        return
    todo = []
    for num in numbers:
        n = _norm(num)
        if n and n not in todo and len(n) >= 5 and not n.startswith('+') and not lookup_tag(n) and not cached_online(n):
            todo.append(n)
    if not todo:
        return

    def run():
        _prefetch['running'] = True
        try:
            for n in todo[:budget]:
                online_query(n, timeout=4.0)
                time.sleep(gap)
        finally:
            _prefetch['running'] = False
    threading.Thread(target=run, name='telo-prefetch', daemon=True).start()


def analyze_call(number, row_tag='', online=False):
    """来电性质：离线库 > 通话记录里的标签 > 在线查询；online=False 时只用在线缓存，不联网。"""
    ntype = number_kind(number)
    local = lookup_tag(number)
    rt = row_tag or ''
    if rt in ('未标记', '未标注') or (ntype and (rt == ntype or rt.startswith(ntype + '·'))):
        rt = ''
    tag, src, conf = local, ('离线库' if local else ''), 100
    if not tag and rt:
        tag, src = rt, '记录'
    on = online_query(number) if online else cached_online(number)
    if not tag and on and on.get('tag'):
        tag, src, conf = on['tag'], '在线·' + (on.get('src') or ''), on.get('conf', 0)
    block = tag in BLOCK_TAGS and (not src.startswith('在线') or conf >= ONLINE_BLOCK_CONFIDENCE)
    return {'tag': tag, 'ntype': ntype, 'block': block, 'loc': (on or {}).get('loc', ''), 'src': src}
