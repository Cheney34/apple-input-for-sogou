#!/usr/bin/env python3
# 生成 iOS 风格的分类图标（白色线稿，84x84，4x 超采样）
from PIL import Image, ImageDraw

S = 84
K = 4
W = 5 * K          # 线宽
C = (255, 255, 255, 235)
OUT = '/root/work/sogou_lsposed/icons'


def canvas():
    im = Image.new('RGBA', (S*K, S*K), (0, 0, 0, 0))
    return im, ImageDraw.Draw(im)


def save(im, name):
    im = im.resize((S, S), Image.LANCZOS)
    im.save('%s/%s.png' % (OUT, name))


def p(v):
    return v * K


def smiley():
    im, d = canvas()
    d.ellipse([p(14), p(14), p(70), p(70)], outline=C, width=W)
    d.ellipse([p(29), p(33), p(37), p(41)], fill=C)     # 左眼
    d.ellipse([p(47), p(33), p(55), p(41)], fill=C)     # 右眼
    d.arc([p(27), p(30), p(57), p(58)], 25, 155, fill=C, width=W)  # 嘴
    return im


def people():
    im, d = canvas()
    d.ellipse([p(32), p(12), p(52), p(32)], outline=C, width=W)
    d.arc([p(18), p(36), p(66), p(84)], 180, 360, fill=C, width=W)
    return im


def component():
    im, d = canvas()
    r = p(7)
    for x, y in ((14, 14), (44, 14), (14, 44), (44, 44)):
        d.rounded_rectangle([p(x), p(y), p(x+26), p(y+26)], radius=r, outline=C, width=W)
    return im


def rabbit():
    im, d = canvas()
    # 两只耳朵
    d.ellipse([p(24), p(6), p(36), p(40)], outline=C, width=W)
    d.ellipse([p(48), p(6), p(60), p(40)], outline=C, width=W)
    # 头
    d.ellipse([p(22), p(30), p(62), p(70)], outline=C, width=W)
    d.ellipse([p(34), p(44), p(40), p(50)], fill=C)
    d.ellipse([p(44), p(44), p(50), p(50)], fill=C)
    # 鼻子
    d.ellipse([p(39), p(54), p(45), p(60)], fill=C)
    return im


def burger():
    im, d = canvas()
    # 上面包
    d.arc([p(12), p(26), p(72), p(66)], 180, 360, fill=C, width=W)
    # 夹层
    d.line([p(16), p(48), p(68), p(48)], fill=C, width=W)
    # 下面包
    d.arc([p(12), p(42), p(72), p(82)], 0, 180, fill=C, width=W)
    # 芝麻（三个点）
    for x in (30, 42, 54):
        d.ellipse([p(x), p(34), p(x+4), p(38)], fill=C)
    return im


def globe():
    im, d = canvas()
    d.ellipse([p(14), p(14), p(70), p(70)], outline=C, width=W)
    d.line([p(14), p(42), p(70), p(42)], fill=C, width=int(W*0.8))
    d.arc([p(14), p(26), p(70), p(58)], 0, 360, fill=C, width=int(W*0.8))
    d.arc([p(20), p(8), p(64), p(76)], 250, 290, fill=C, width=int(W*0.8))
    return im


def ball():
    im, d = canvas()
    d.ellipse([p(14), p(14), p(70), p(70)], outline=C, width=W)
    # 中间五边形
    import math
    cx, cy, r = 42, 42, 13
    pts = []
    for i in range(5):
        a = math.radians(-90 + i * 72)
        pts.append((p(cx + r * math.cos(a)), p(cy + r * math.sin(a))))
    d.polygon(pts, outline=C, width=W)
    # 向外的短线
    for i in range(5):
        a = math.radians(-90 + i * 72)
        x1 = cx + (r + 2) * math.cos(a)
        y1 = cy + (r + 2) * math.sin(a)
        x2 = cx + (r + 15) * math.cos(a)
        y2 = cy + (r + 15) * math.sin(a)
        d.line([p(x1), p(y1), p(x2), p(y2)], fill=C, width=int(W*0.8))
    return im


def bulb():
    im, d = canvas()
    d.ellipse([p(24), p(10), p(60), p(46)], outline=C, width=W)
    d.line([p(32), p(50), p(52), p(50)], fill=C, width=W)
    d.line([p(33), p(59), p(51), p(59)], fill=C, width=W)
    d.line([p(36), p(68), p(48), p(68)], fill=C, width=W)
    # 灯丝
    d.line([p(37), p(34), p(42), p(40)], fill=C, width=int(W*0.7))
    d.line([p(42), p(40), p(47), p(34)], fill=C, width=int(W*0.7))
    return im


def hash_():
    im, d = canvas()
    d.line([p(30), p(16), p(24), p(68)], fill=C, width=W)
    d.line([p(52), p(16), p(46), p(68)], fill=C, width=W)
    d.line([p(16), p(32), p(68), p(32)], fill=C, width=W)
    d.line([p(14), p(52), p(66), p(52)], fill=C, width=W)
    return im


def flag():
    im, d = canvas()
    d.line([p(24), p(12), p(24), p(74)], fill=C, width=W)
    d.line([p(24), p(16), p(64), p(16)], fill=C, width=W)
    d.line([p(64), p(16), p(64), p(40)], fill=C, width=W)
    # 三角旗
    d.polygon([(p(24), p(16)), (p(64), p(16)), (p(24), p(40))], outline=C, width=W)
    return im


if __name__ == '__main__':
    import os
    os.makedirs(OUT, exist_ok=True)
    mapping = {
        'smileys_emotion': smiley,
        'people_body': people,
        'component': component,
        'animals_nature': rabbit,
        'food_drink': burger,
        'travel_places': globe,
        'activities': ball,
        'objects': bulb,
        'symbols': hash_,
        'flags': flag,
    }
    for name, fn in mapping.items():
        save(fn(), name)
    print('done', len(mapping))
