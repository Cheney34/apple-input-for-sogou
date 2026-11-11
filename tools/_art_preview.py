"""离线复刻 IosSkin.paintKeyFace + 九宫格切片，用来看"真机上会长什么样"。

用法：python tools/_art_preview.py
输出：preview_compare.png（左=原图，右=新图，均按 225x260 的键位渲染）
"""
from PIL import Image, ImageDraw, ImageChops

SRC = '../sogou_src/theme/theme_default/1080/res/black/key.png'
DEST = (225, 260)          # 九宫格一颗键的真机像素（按截图量的）
S = 1.6                    # 九宫格密度缩放（由截图反推）
INSETS = (28, 30, 38, 40)  # images_config.ini  [key_png] IMAGE=key.png,28,30,38,40
BG = (0x29, 0x29, 0x29)
COLOR = (0x6D, 0x6D, 0x6F)  # 预设 key 色


def lighten(c, t):
    return tuple(min(255, int(v + (255 - v) * t)) for v in c)


def darken(c, t):
    return tuple(max(0, int(v * (1 - t))) for v in c)


def paint(src, frac, glass=True):
    w, h = src.size
    box = src.split()[3].getbbox()
    gap = (box[3] - box[1]) * 0.018
    l, t, r, b = box[0] + gap, box[1] + gap, box[2] - gap, box[3] - gap
    rw, rh = r - l, b - t
    rad = max(2, min(rw * frac, min(rw, rh) * 0.5))

    mask = Image.new('L', (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([l, t, r, b], radius=rad, fill=255)

    top, dark = lighten(COLOR, 0.06 if glass else 0.02), darken(COLOR, 0.03 if glass else 0.02)
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

    if glass:
        def sheen(px):
            y_end = t + rh * 0.30           # 必须停在切片拉伸带(y=38)之前
            for y in range(int(t), int(y_end)):
                k = 1 - (y - t) / (y_end - t)
                al = int(0x22 * k)
                for x in range(w):
                    px[x, y] = (255, 255, 255, al)
        base = Image.alpha_composite(base, overlay(sheen))

        def shade(px):
            ys = b - rh * 0.26
            for y in range(int(ys), int(b)):
                k = (y - ys) / (b - ys)
                al = int(0x1F * k)
                for x in range(w):
                    px[x, y] = (0, 0, 0, al)
        base = Image.alpha_composite(base, overlay(shade))

    sw = max(1, int(round(rh * 0.022)))
    e1 = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(e1).rounded_rectangle([l, t, r, b], radius=rad,
                                         outline=(255, 255, 255, 0x66), width=sw)
    c1 = Image.new('L', (w, h), 0)
    ImageDraw.Draw(c1).rectangle([0, 0, w, t + rh * 0.13], fill=255)
    e1.putalpha(ImageChops.multiply(e1.split()[3], c1))
    base = Image.alpha_composite(base, e1)

    e2 = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(e2).rounded_rectangle([l, t, r, b], radius=rad,
                                         outline=(0, 0, 0, 0x2E), width=sw)
    c2 = Image.new('L', (w, h), 0)
    ImageDraw.Draw(c2).rectangle([0, b - rh * 0.10, w, h], fill=255)
    e2.putalpha(ImageChops.multiply(e2.split()[3], c2))
    return Image.alpha_composite(base, e2)


def ninepatch(img, dw, dh, s=S):
    """按 IMAGE=key.png,28,30,38,40 切片：横/纵只有 2px 会拉伸，其余 1:1。"""
    w, h = img.size
    l2, r2, t2, b2 = [int(round(v * s)) for v in (INSETS[0], INSETS[1], INSETS[2], INSETS[3])]
    l2, t2 = min(l2, w // 2 - 1), min(t2, h // 2 - 1)
    r2, b2 = min(r2, w - l2 - 1), min(b2, h - t2 - 1)
    xs = [(0, l2), (l2, w - r2), (w - r2, w)]
    ys = [(0, t2), (t2, h - b2), (h - b2, h)]
    dxs = [(0, l2), (l2, dw - r2), (dw - r2, dw)]
    dys = [(0, t2), (t2, dh - b2), (dh - b2, dh)]
    out = Image.new('RGBA', (dw, dh), (0, 0, 0, 0))
    for (sx0, sx1), (dx0, dx1) in zip(xs, dxs):
        for (sy0, sy1), (dy0, dy1) in zip(ys, dys):
            piece = img.crop((sx0, sy0, sx1, sy1))
            pw, ph = max(1, dx1 - dx0), max(1, dy1 - dy0)
            if piece.size != (pw, ph):
                piece = piece.resize((pw, ph), Image.NEAREST)
            out.alpha_composite(piece, (dx0, dy0))
    return out


def on_bg(img, bg=BG):
    cv = Image.new('RGBA', img.size, bg + (255,))
    return Image.alpha_composite(cv, img).convert('RGB')


src = Image.open(SRC).convert('RGBA')
old = on_bg(ninepatch(src, *DEST))
new = on_bg(ninepatch(paint(src, 0.38), *DEST))
gap = 24
combo = Image.new('RGB', (DEST[0] * 2 + gap, DEST[1]), (0, 0, 0))
combo.paste(old, (0, 0))
combo.paste(new, (DEST[0] + gap, 0))
combo.save('preview_compare.png')
print('saved preview_compare.png', combo.size)
