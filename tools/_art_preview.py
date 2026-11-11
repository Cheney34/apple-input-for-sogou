"""离线复刻 IosSkin.paintKeyFace + 九宫格切片，用来看"真机上会长什么样"。

用法：python tools/_art_preview.py
输出：preview_compare.png（左=搜狗原图 / 中=旧画法 / 右=新画法，均按 225x260 的键位渲染）
"""
from PIL import Image, ImageDraw, ImageChops

SRC = '../sogou_src/theme/theme_default/1080/res/black/key.png'
DEST = (225, 260)          # 九宫格一颗键的真机像素（按截图量的）
S = 1.6                    # 九宫格密度缩放（由截图反推）
INSETS = (28, 30, 38, 40)  # images_config.ini  [key_png] IMAGE=key.png,28,30,38,40
BG = (0x29, 0x29, 0x29)
COLOR = (0x6D, 0x6D, 0x6F)  # 预设 key 色（iOS 深色）

# 两套高光参数，对应 v2.3.1（旧）与本次（新）
OLD = dict(sheen=0x22, shade=0x1F, rim=0x66, rim_bottom=0x2E, top=0.13, sheen_h=0.30)
NEW = dict(sheen=0x48, shade=0x33, rim=0xC4, rim_bottom=0x38, top=0.14, sheen_h=0.24)


def lighten(c, t):
    return tuple(min(255, int(v + (255 - v) * t)) for v in c)


def darken(c, t):
    return tuple(max(0, int(v * (1 - t))) for v in c)


def paint(src, frac, cfg):
    w, h = src.size
    box = src.split()[3].getbbox()
    gap = (box[3] - box[1]) * 0.018
    l, t, r, b = box[0] + gap, box[1] + gap, box[2] - gap, box[3] - gap
    rw, rh = r - l, b - t
    rad = max(2, min(rw * frac, min(rw, rh) * 0.5))

    mask = Image.new('L', (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([l, t, r, b], radius=rad, fill=255)

    top, dark = lighten(COLOR, 0.06), darken(COLOR, 0.03)
    grad = Image.new('RGBA', (w, h))
    gp = grad.load()
    for y in range(h):
        if y <= t:
            c = top
        elif y >= b:
            c = dark
        else:
            k = (y - t) / (b - t)
            c = tuple(int(top[i] + (dark[i] - top[i]) * k) for i in range(3))
        for x in range(w):
            gp[x, y] = c + (255,)
    base = Image.composite(grad, Image.new('RGBA', (w, h), (0, 0, 0, 0)), mask)

    def overlay(fn):
        ov = Image.new('RGBA', (w, h), (0, 0, 0, 0))
        fn(ov.load())
        return Image.composite(ov, Image.new('RGBA', (w, h), (0, 0, 0, 0)), mask)

    def sheen(px):
        y_end = t + rh * cfg['sheen_h']       # 必须停在切片拉伸带(y=38..50)之前
        for y in range(int(t), int(y_end)):
            k = 1 - (y - t) / (y_end - t)
            al = int(cfg['sheen'] * k)
            for x in range(w):
                px[x, y] = (255, 255, 255, al)
    base = Image.alpha_composite(base, overlay(sheen))

    def shade(px):
        ys = b - rh * 0.26
        for y in range(int(ys), int(b)):
            k = (y - ys) / (b - ys)
            al = int(cfg['shade'] * k)
            for x in range(w):
                px[x, y] = (0, 0, 0, al)
    base = Image.alpha_composite(base, overlay(shade))

    sw = max(1, int(round(rh * 0.022)))
    e1 = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(e1).rounded_rectangle([l, t, r, b], radius=rad,
                                         outline=(255, 255, 255, cfg['rim']), width=sw)
    c1 = Image.new('L', (w, h), 0)
    ImageDraw.Draw(c1).rectangle([0, 0, w, t + rh * cfg['top']], fill=255)
    e1.putalpha(ImageChops.multiply(e1.split()[3], c1))
    base = Image.alpha_composite(base, e1)

    e2 = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(e2).rounded_rectangle([l, t, r, b], radius=rad,
                                         outline=(0, 0, 0, cfg['rim_bottom']), width=sw)
    c2 = Image.new('L', (w, h), 0)
    ImageDraw.Draw(c2).rectangle([0, b - rh * 0.10, w, h], fill=255)
    e2.putalpha(ImageChops.multiply(e2.split()[3], c2))
    return Image.alpha_composite(base, e2)


def ninepatch(img, dw, dh, s=S):
    """按 IMAGE=key.png,28,30,38,40 切片。

    这组数字是**源图像素**（60×90 的图：左 28 + 右 30 + 中间 2 拉伸），
    不是缩放后的值 —— 早先这里把 28/30 也乘了 S，导致 45+48 超过图宽，
    切片直接算崩，预览图看着"差不多"其实完全是错的。
    显示时固定区按 density 放大 S 倍，只有中间那条被拉伸。
    """
    w, h = img.size
    l2, r2, t2, b2 = INSETS                     # 源图里的固定区宽度
    dl, dr = min(l2 * s, dw - 2), min(r2 * s, dw - 2)
    dt, db = min(t2 * s, dh - 2), min(b2 * s, dh - 2)
    xs = [(0, l2), (l2, w - r2), (w - r2, w)]
    ys = [(0, t2), (t2, h - b2), (h - b2, h)]
    dxs = [(0, dl), (dl, dw - dr), (dw - dr, dw)]
    dys = [(0, dt), (dt, dh - db), (dh - db, dh)]
    out = Image.new('RGBA', (dw, dh), (0, 0, 0, 0))
    for (sx0, sx1), (dx0, dx1) in zip(xs, dxs):
        for (sy0, sy1), (dy0, dy1) in zip(ys, dys):
            if sx1 <= sx0 or sy1 <= sy0:
                continue
            piece = img.crop((sx0, sy0, sx1, sy1))
            pw, ph = max(1, int(round(dx1 - dx0))), max(1, int(round(dy1 - dy0)))
            if piece.size != (pw, ph):
                piece = piece.resize((pw, ph), Image.NEAREST)
            out.alpha_composite(piece, (int(round(dx0)), int(round(dy0))))
    return out


def on_bg(img, bg=BG):
    cv = Image.new('RGBA', img.size, bg + (255,))
    return Image.alpha_composite(cv, img).convert('RGB')


def labeled(img, text):
    im = img.copy()
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, im.width, 22], fill=(0, 0, 0))
    d.text((8, 6), text, fill=(230, 230, 230))
    return im


src = Image.open(SRC).convert('RGBA')
panels = [
    ('sogou original', on_bg(ninepatch(src, *DEST))),
    ('old rim 40%', on_bg(ninepatch(paint(src, 0.38, OLD), *DEST))),
    ('new rim 70%', on_bg(ninepatch(paint(src, 0.38, NEW), *DEST))),
]
gap = 18
combo = Image.new('RGB', (DEST[0] * 3 + gap * 2, DEST[1]), (0, 0, 0))
for i, (name, im) in enumerate(panels):
    combo.paste(labeled(im, name), (i * (DEST[0] + gap), 0))
combo.save('preview_compare.png')
print('saved preview_compare.png', combo.size)
