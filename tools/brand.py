"""Renders Aurelia's icon and TV banner (a minimal glass jellyfish on the launcher's dark backdrop).

    python3 tools/brand.py OUT_DIR launcher/app/src/main/res/font/outfit.ttf

writes icon2_512.png (a large preview), ic_launcher2.png (192 px, for mipmap-xhdpi/ic_launcher.jpg) and
banner2.png (320 x 180, for drawable-nodpi/banner.jpg), plus a large banner preview. Needs Pillow.
"""
import math, sys
from PIL import Image, ImageDraw, ImageFilter, ImageChops, ImageFont

OUT, FONT = sys.argv[1], sys.argv[2]
PURPLE = (170, 92, 195)
BLUE = (0, 164, 220)
LAVENDER = (214, 196, 255)
BASE = (11, 13, 20)

def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(len(a)))

def backdrop(w, h):
    im = Image.new('RGB', (w, h), BASE)
    glow = Image.new('RGB', (w, h), BASE)
    d = ImageDraw.Draw(glow)
    r = max(w, h)
    d.ellipse([-r * 0.45, -r * 0.55, r * 0.55, r * 0.45], fill=lerp(BASE, PURPLE, 0.5))
    d.ellipse([w - r * 0.5, h - r * 0.45, w + r * 0.5, h + r * 0.55], fill=lerp(BASE, BLUE, 0.45))
    return Image.blend(im, glow.filter(ImageFilter.GaussianBlur(r * 0.22)), 0.9)

def vgrad(size, top, bottom, y0, y1):
    """RGBA vertical gradient between rows y0 and y1."""
    w, h = size
    g = Image.new('RGBA', size)
    d = ImageDraw.Draw(g)
    for y in range(h):
        t = min(1, max(0, (y - y0) / max(1, (y1 - y0))))
        d.line([(0, y), (w, y)], fill=lerp(top, bottom, t))
    return g

def white(alpha_mask):
    return Image.merge('RGBA', [Image.new('L', alpha_mask.size, 255)] * 3 + [alpha_mask])

def bell_outline(cx, cy, R, base, corner):
    """The bell: a half circle of radius R over a base this deep, its bottom corners rounded."""
    pts = []
    for i in range(361):
        a = math.pi + math.pi * i / 360
        pts.append((cx + R * math.cos(a), cy + R * math.sin(a)))
    for i in range(91):
        a = (math.pi / 2) * i / 90
        pts.append((cx + R - corner + corner * math.cos(a), cy + base - corner + corner * math.sin(a)))
    for i in range(91):
        a = math.pi / 2 + (math.pi / 2) * i / 90
        pts.append((cx - R + corner + corner * math.cos(a), cy + base - corner + corner * math.sin(a)))
    return pts

def mark(W):
    """A minimal glass jellyfish: a half-circle bell on a short rounded base, three even, round-ended waves."""
    layer = Image.new('RGBA', (W, W), (0, 0, 0, 0))
    R = W * 0.30
    cx, cy = W * 0.5, W * 0.46            # centre of the half circle
    base = R * 0.2                        # the bell's straight part under the half circle
    corner = R * 0.2

    # Tentacles: three waves of one width, ends rounded, fading as they fall; behind the bell.
    tent = Image.new('RGBA', (W, W), (0, 0, 0, 0))
    td = ImageDraw.Draw(tent)
    width = R * 0.105
    for k, (dx, length, phase) in enumerate([(-0.48, 0.95, 0.0), (0.0, 1.2, math.pi), (0.48, 0.95, 0.0)]):
        x0 = cx + dx * R
        top = cy + base * 0.6
        steps = 240
        for s in range(steps + 1):
            t = s / steps
            y = top + t * length * R
            x = x0 + math.sin(t * math.pi * 1.6 + phase) * R * 0.07 * t
            col = lerp(LAVENDER, BLUE, 0.35 + 0.55 * t)
            a = int(225 * (1 - t) ** 1.15)
            td.ellipse([x - width / 2, y - width / 2, x + width / 2, y + width / 2], fill=col + (a,))
    layer = Image.alpha_composite(layer, tent)

    # The bell's shape.
    outline = bell_outline(cx, cy, R, base, corner)
    m = Image.new('L', (W, W), 0)
    ImageDraw.Draw(m).polygon(outline, fill=255)

    # Glass: lavender at the crown to blue at the rim, see-through.
    fill = vgrad((W, W), LAVENDER + (215,), BLUE + (150,), cy - R, cy + base)
    bell = Image.new('RGBA', (W, W), (0, 0, 0, 0))
    bell.paste(fill, (0, 0), m)
    layer = Image.alpha_composite(layer, bell)

    # The mouth: the glass deepening towards its lower edge, the underside seen through it.
    lip = vgrad((W, W), BASE + (0,), BASE + (60,), cy - base * 0.2, cy + base)
    shade = Image.new('RGBA', (W, W), (0, 0, 0, 0))
    shade.paste(lip, (0, 0), m)
    layer = Image.alpha_composite(layer, shade)

    # Rim light along the crown, and one clean highlight arc inside it.
    # Rim light: an even band just inside the outline, bright over the crown and fading down the sides.
    edge = R * 0.035
    inner = Image.new('L', (W, W), 0)
    ImageDraw.Draw(inner).polygon(bell_outline(cx, cy, R - edge, base - edge, corner - edge), fill=255)
    rim = ImageChops.subtract(m, inner)
    fade = vgrad((W, W), (255, 255, 255, 180), (255, 255, 255, 0), cy - R * 0.15, cy + base * 0.9).split()[3]
    rim = ImageChops.multiply(rim, fade)
    layer = Image.alpha_composite(layer, white(rim))
    hi = Image.new('L', (W, W), 0)
    k = 0.74
    ImageDraw.Draw(hi).arc([cx - R * k, cy - R * k, cx + R * k, cy + R * k], 205, 250, fill=200,
                           width=max(2, int(R * 0.075)))
    # Round the arc's ends.
    hd = ImageDraw.Draw(hi)
    for ang in (205, 250):
        a = math.radians(ang)
        rr = R * k - R * 0.0375
        x, y = cx + rr * math.cos(a), cy + rr * math.sin(a)
        e = R * 0.0375
        hd.ellipse([x - e, y - e, x + e, y + e], fill=200)
    layer = Image.alpha_composite(layer, white(hi))

    # Its own soft light on the ground.
    halo = Image.new('L', (W, W), 0)
    ImageDraw.Draw(halo).ellipse([cx - R * 1.35, cy - R * 1.3, cx + R * 1.35, cy + R * 1.1], fill=60)
    halo = halo.filter(ImageFilter.GaussianBlur(W * 0.08))
    glow = Image.merge('RGBA', [Image.new('L', (W, W), c) for c in lerp(PURPLE, BLUE, 0.5)] + [halo])
    return Image.alpha_composite(glow, layer)

def icon(px):
    S = px * 4
    bg = backdrop(S, S).convert('RGBA')
    bg.alpha_composite(mark(S), (0, 0))
    return bg.convert('RGB').resize((px, px), Image.LANCZOS)

def banner(w, h, name):
    S = 4
    W, H = w * S, h * S
    bg = backdrop(W, H).convert('RGBA')
    size = int(H * 1.0)
    bg.alpha_composite(mark(size), (int(W * 0.05), 0))
    font = ImageFont.truetype(FONT, int(H * 0.2))
    font.set_variation_by_axes([400])
    d = ImageDraw.Draw(bg)
    box = d.textbbox((0, 0), name, font=font)
    x = int(W * 0.05 + size * 0.86)
    y = int(H * 0.46) - (box[3] - box[1]) // 2 - box[1]
    d.text((x, y), name, font=font, fill=(255, 255, 255, 255))
    return bg.convert('RGB').resize((w, h), Image.LANCZOS)

icon(512).save(OUT + '/icon2_512.png')
icon(192).save(OUT + '/ic_launcher2.png')
banner(320, 180, 'Aurelia').save(OUT + '/banner2.png')
banner(1280, 720, 'Aurelia').save(OUT + '/banner2_large.png')
print('ok')
