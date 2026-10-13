#!/usr/bin/env python3
"""生成本地 phonebox 的合成数据：sms.jsonl、calls.jsonl、mast.db。

全部号码和正文都是虚构的（138000000xx 段、95555/10086 这类公共服务号），不含任何真实短信。
用法：gen-data.py <输出目录> [--force]
"""
import json
import sqlite3
import sys
import time
from pathlib import Path

NOW = int(time.time())
H = 3600

# (几小时前, 发件人, 正文, cat, level)；cat/level 模仿 Asterisk 侧 sms-notify.sh 写入的粗分类
SMS = [
    (0.1, '95555', '【招商银行】您的验证码是 123456，用于登录手机银行，5分钟内有效。请勿泄露给他人。', '金融', 'high'),
    (0.5, '1069000000001', '【某某快递】您的快递已到达小区驿站，取件码 8-3-2041，请于今日 20:00 前取件。', '快递', 'normal'),
    (1, '95588', '【工商银行】您尾号0001的账户10月10日09:12支出人民币58.00元，余额1234.56元。', '金融', 'normal'),
    (2, '1069000000002', '【某某商城】双十一预售开启，全场满300减50，点击 t.cn/xxxx 抢购，回T退订', '营销', 'low'),
    (3, '13800000001', '晚上一起吃饭吗？七点老地方见。', '', 'normal'),
    (5, '10086', '【中国移动】尊敬的客户，截至10月10日，您本月套餐内通用流量已使用 18.5GB，剩余 1.5GB。', '运营商', 'normal'),
    (8, '1069000000003', '【某某出行】您的验证码为 4821，请在页面中输入完成验证。如非本人操作请忽略。', '', 'high'),
    (20, '1069000000004', '【某某贷】您有一笔 50000 元额度待领取，最快 3 分钟到账，回T退订', '骚扰', 'low'),
    (26, '13800000002', '周末球赛改到周日下午两点，记得带球鞋。', '', 'normal'),
    (30, '12306', '【铁路客服】订单E000000001，张三您已购10月12日G1次8车5F号，北京南站08:00开。', '出行', 'normal'),
    # R2-b 信息分类与富卡片：正文按 PixelText rules.json 的格式虚构，好让服务端和客户端都识别出卡片
    (0.2, '1069000000005', '【菜鸟驿站】您的中通包裹已到阳光小区驿站，请今日20:00前取件，取件码3-2-1108。', '快递', 'normal'),
    (0.7, '95555', '【招商银行】您账户1234于10月10日09:12消费人民币128.00元（某某超市）', '金融', 'normal'),
    (4, '1069000000006', '【汇联易甄选】购票成功，2026-10-12 08:00:00出发，北京南—上海虹桥，G1，检票口：A12，张三 二等座 08车厢05F号', '出行', 'normal'),
    (6, '95530', '【东方航空】您已购买2026年10月20日08:30 上海虹桥机场（T2）至北京首都机场（T3），MU5101航班，请提前到达。', '出行', 'normal'),
    (7, '10086', '【剩余流量查询】尊敬的客户，本月套餐内流量共20GB，已使用18.5GB。目前国内通用流量剩余1.5GB。', '运营商', 'normal'),
    (9, '10010', '【中国联通】您本月话费账单已生成，本期应缴 39.00 元，可通过营业厅 App 查询明细。', '运营商', 'normal'),
    (10, '1069000000007', '【某某会员】您的积分即将到期，详情见 App。', '', 'normal'),
    (11, '1069000000008', '【某某健身】国庆特惠年卡 999 元，名额有限，拒收请回复R', '', 'low'),
    (12, '1069000000009', '【某某理财】恭喜获得高额授信，点击 t.cn/yyyy 立即提现', '骚扰（模型）', 'none'),
    (14, '17000000001', '您的账户存在异常，请添加客服处理，否则将被冻结', '骚扰（模型）', 'none'),
    (15, '4000000001', '尊敬的客户，您有一份专属福利待领取', '', 'normal'),  # 号码在拦截来电里出现过
]

# (几小时前, 号码, 方向, 结果, 通话秒数, 标签)；结果取值对齐 Asterisk CDR disposition
CALLS = [
    (0.3, '13800000001', 'in', 'ANSWERED', 65, '13800000001 手机'),
    (1.5, '13800000003', 'in', 'NO ANSWER', 0, '手机'),
    (4, '4000000001', 'in', 'NO ANSWER', 0, '广告推销'),  # 号码库标签 → 被拦截
    (6, '13800000002', 'out', 'ANSWERED', 132, ''),
    (9, '01000000001', 'out', 'NO ANSWER', 0, ''),
    (22, '95555', 'in', 'ANSWERED', 40, '服务号'),
    (27, '13800000099', 'in', 'FAILED', 0, '骚扰电话'),
    # R2-c 通话分类：陌生来电（接通/未接）、更多拦截、带归属地
    (0.8, '13800000005', 'in', 'NO ANSWER', 0, ''),
    (2.5, '13800000006', 'in', 'ANSWERED', 210, '13800000006 手机'),
    (3.5, '13800000098', 'in', 'NO ANSWER', 0, ''),  # 号码库：疑似欺诈 → 拦截
    (12, '02100000001', 'in', 'BUSY', 0, ''),  # 号码库：房产中介 → 拦截
    (30, '13800000007', 'out', 'ANSWERED', 75, ''),
]

# 在线查号缓存（telo-cache.json）里预置归属地；测试环境从不联网，归属地只能从这里来
CALL_LOC = {
    '13800000005': '北京 联通',
    '13800000006': '上海 移动',
    '13800000098': '广东 深圳 电信',
    '13800000003': '北京 移动',
}

# PixelTelo mast.db 只用到 spam_numbers(phone_number, tag)
MAST = [
    ('4000000001', '广告推销'),
    ('+8613800000099', '骚扰电话'),
    ('13800000098', '疑似欺诈'),
    ('02100000001', '房产中介'),
    ('95555', '招商银行'),
]


def write_jsonl(path, rows):
    with open(path, 'w', encoding='utf-8') as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + '\n')


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    out = Path(sys.argv[1])
    force = '--force' in sys.argv
    out.mkdir(parents=True, exist_ok=True)
    data = out / 'data'
    data.mkdir(exist_ok=True)
    if force or not (data / 'sms.jsonl').exists():
        # 文件按时间正序追加，和生产一致（server 读最后 N 行再倒序）
        write_jsonl(data / 'sms.jsonl', [
            {'ts': NOW - int(h * H), 'from': f, 'body': b, 'cat': c, 'level': lv}
            for h, f, b, c, lv in sorted(SMS, key=lambda x: -x[0])])
        write_jsonl(data / 'calls.jsonl', [
            {'ts': NOW - int(h * H), 'number': n, 'dir': d, 'disposition': disp, 'billsec': sec, 'tag': t}
            for h, n, d, disp, sec, t in sorted(CALLS, key=lambda x: -x[0])])
        print(f'已生成 {data}/sms.jsonl calls.jsonl')
    cache = out / 'state' / 'telo-cache.json'
    if force or not cache.exists():
        cache.parent.mkdir(exist_ok=True)
        cache.write_text(json.dumps({n: {'t': NOW, 'tag': '', 'spam': False, 'conf': 0, 'src': '', 'loc': loc}
                                     for n, loc in CALL_LOC.items()}, ensure_ascii=False), encoding='utf-8')
        print(f'已生成 {cache}')
    db = out / 'mast.db'
    if force or not db.exists():
        if db.exists():
            db.unlink()
        con = sqlite3.connect(db)
        con.execute('CREATE TABLE spam_numbers (phone_number TEXT PRIMARY KEY, tag TEXT)')
        con.executemany('INSERT INTO spam_numbers VALUES (?, ?)', MAST)
        con.commit()
        con.close()
        print(f'已生成 {db}')


if __name__ == '__main__':
    main()
