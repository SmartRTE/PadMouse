"""
PadMouse 图标生成器
左半：鼠标箭头   右半：手柄右半部分   中间一道竖直分割线
输出：自适应图标前景（透明）+ 传统整图（带底色）
"""
import os
import math
from PIL import Image, ImageDraw

SS = 6                        # 超采样倍数
BG = (15, 20, 25, 255)        # #0F1419
FG = (246, 249, 253, 255)     # 近白
ACCENT = (91, 157, 255, 245)  # #5B9DFF
SHADOW = (0, 0, 0, 70)

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_RES = os.path.join(HERE, "..", "app", "src", "main", "res")
PREVIEW = os.path.join(HERE, "_icon_preview.png")

FILL = 0.55      # 内容直径 / 画布边长（自适应安全圆约 0.611）
LINE = 1.5       # 分割线半宽

# 鼠标箭头（尖端在原点，朝左上）
ARROW = [(0, 0), (0, 96), (21, 70), (34, 98), (47, 92), (34, 63), (62, 62)]
ARROW_POS = (-68, -46)

# 手柄右半轮廓（左侧故意越过分割线，稍后裁掉；用 Chaikin 平滑）
PAD = [
    (-14, -34), (20, -38), (42, -35), (56, -26), (61, -12), (61, 0),
    (54, 12), (52, 20), (62, 36), (60, 56), (50, 70), (36, 74),
    (26, 64), (22, 34), (14, 20), (-14, 16),
]

BX, BY = 33, -13          # ABXY 菱形中心
STICK = (16, 9)           # 右摇杆中心
STICK_R = 11.0


def chaikin(pts, iterations=2):
    for _ in range(iterations):
        out = []
        n = len(pts)
        for i in range(n):
            p, q = pts[i], pts[(i + 1) % n]
            out.append((0.75 * p[0] + 0.25 * q[0], 0.75 * p[1] + 0.25 * q[1]))
            out.append((0.25 * p[0] + 0.75 * q[0], 0.25 * p[1] + 0.75 * q[1]))
        pts = out
    return pts


def build(size, with_bg, supersample=SS):
    big = size * supersample
    base = BG if with_bg else (0, 0, 0, 0)
    img = Image.new("RGBA", (big, big), base)

    k = big * FILL / 156.0
    c = big / 2.0

    def P(x, y):
        return (c + x * k, c + y * k)

    def box(cx0, cy0, r):
        return [P(cx0 - r, cy0 - r), P(cx0 + r, cy0 + r)]

    # 柔和阴影需要混合绘制
    soft = ImageDraw.Draw(img, "RGBA")
    # 实心形状用不混合的绘制器（alpha 0 即擦除，用来挖孔）
    hard = ImageDraw.Draw(img)

    # ---------------- 分割线 ----------------
    soft.rounded_rectangle([P(-LINE, -74), P(LINE, 74)],
                           radius=LINE * k, fill=ACCENT)

    # ---------------- 左侧：鼠标箭头 ----------------
    ax, ay = ARROW_POS
    soft.polygon([P(x + ax + 3, y + ay + 4) for (x, y) in ARROW], fill=SHADOW)
    hard.polygon([P(x + ax, y + ay) for (x, y) in ARROW], fill=FG)

    # ---------------- 右侧：手柄右半 ----------------
    pad = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    pd = ImageDraw.Draw(pad)          # 不混合，便于挖孔
    pd.polygon([P(x, y) for (x, y) in chaikin(PAD)], fill=FG)
    # ABXY 镂空
    for (dx, dy) in [(0, -15), (-15, 0), (15, 0), (0, 15)]:
        pd.ellipse(box(BX + dx, BY + dy, 6.0), fill=(0, 0, 0, 0))
    # 右摇杆镂空圆环
    ring = P(STICK[0], STICK[1])
    pd.ellipse(box(STICK[0], STICK[1], STICK_R), fill=(0, 0, 0, 0))
    pd.ellipse(box(STICK[0], STICK[1], STICK_R - 4.2), fill=FG)

    # 裁到分割线右侧，保证那道线完整可见
    cut = int(P(LINE, 0)[0])
    pad = pad.crop((cut, 0, big, big))
    img.paste(pad, (cut, 0), pad)

    return img.resize((size, size), Image.LANCZOS)


def ink_radius(img):
    w = img.size[0]
    px = img.load()
    c = w / 2.0
    best = 0.0
    for y in range(0, w, 2):
        for x in range(0, w, 2):
            if px[x, y][3] > 40:
                best = max(best, math.hypot(x - c, y - c))
    return best / w


def main():
    prev = build(512, True)
    prev.save(PREVIEW)
    print("ink radius (transparent fg) = %.3f   safe ~0.306" % ink_radius(build(432, False)))

    mask = Image.new("L", (512, 512), 0)
    ImageDraw.Draw(mask).ellipse([8, 8, 504, 504], fill=255)
    masked = Image.new("RGBA", (512, 512), (44, 48, 52, 255))
    masked.paste(prev, (0, 0), mask)
    masked.save(PREVIEW.replace(".png", "_masked.png"))

    small = Image.new("RGBA", (220, 100), (44, 48, 52, 255))
    for i, px in enumerate((72, 48)):
        icon = build(px, True, supersample=4)
        small.paste(icon, (20 + i * 90, (100 - px) // 2), icon)
    small.save(PREVIEW.replace(".png", "_small.png"))

    for dpi, px in [("mdpi", 108), ("hdpi", 162), ("xhdpi", 216),
                    ("xxhdpi", 324), ("xxxhdpi", 432)]:
        build(px, False).save(
            os.path.join(OUT_RES, f"mipmap-{dpi}", "ic_launcher_foreground.png"))
    for dpi, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                    ("xxhdpi", 144), ("xxxhdpi", 192)]:
        build(px, True).save(
            os.path.join(OUT_RES, f"mipmap-{dpi}", "ic_launcher.png"))

    print("icons written")


if __name__ == "__main__":
    main()
