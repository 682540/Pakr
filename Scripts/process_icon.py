import urllib.request, os, io
from PIL import Image, ImageDraw

url = os.environ.get('ICON_URL', '').strip()
FALLBACK = 'logo.jpg'

def load_fallback():
    if os.path.exists(FALLBACK):
        try:
            tmp = Image.open(FALLBACK)
            tmp.load()
            print(f'Using fallback {FALLBACK}: {tmp.format} {tmp.size}')
            return tmp.convert('RGBA')
        except Exception as e:
            print(f'Fallback {FALLBACK} failed: {e}')
    print('No fallback available, keeping default Android launcher icon.')
    return None

img = None
if not url:
    print('No icon URL provided, trying fallback logo.jpg.')
    img = load_fallback()
else:
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=15) as r:
            raw = r.read()
        tmp = Image.open(io.BytesIO(raw))
        tmp.load()
        img = tmp.convert('RGBA')
        print(f'Image OK: {tmp.format} {tmp.size}')
    except Exception as e:
        print(f'Download/open failed ({e}), trying fallback logo.jpg.')
        img = load_fallback()

if img is not None:
    # 1) launcher icons for all 5 densities
    for density, size in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]:
        out = img.resize((size, size), Image.LANCZOS)
        base = f'app/src/main/res/mipmap-{density}'
        os.makedirs(base, exist_ok=True)
        out.save(f'{base}/ic_launcher.png', format='PNG')
        mask = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=(255, 255, 255, 255))
        result = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        result.paste(out, mask=mask)
        result.save(f'{base}/ic_launcher_round.png', format='PNG')
        print(f'  mipmap-{density}/{size}px OK')

    # 2) bare snowflake drawable: keep only the white mark, drop the blue rounded board
    W, H = img.size
    px = img.load()
    flake = Image.new('RGBA', (W, H), (255, 255, 255, 0))
    fp = flake.load()
    for y in range(H):
        for x in range(W):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            mn = min(r, g, b)
            ch = max(r, g, b) - mn
            v = (mn - 1.15 * ch - 40) * 3.2
            al = 0 if v < 0 else (255 if v > 255 else int(v))
            if al:
                fp[x, y] = (255, 255, 255, al)
    box = flake.getbbox()
    if box:
        flake = flake.crop(box)
    target = 512
    inner = int(target * 0.84)
    w, h = flake.size
    scale = min(inner / float(w), inner / float(h))
    flake = flake.resize((max(1, int(w * scale)), max(1, int(h * scale))), Image.LANCZOS)
    canvas = Image.new('RGBA', (target, target), (255, 255, 255, 0))
    canvas.paste(flake, ((target - flake.size[0]) // 2, (target - flake.size[1]) // 2), flake)
    db = 'app/src/main/res/drawable'
    os.makedirs(db, exist_ok=True)
    canvas.save(f'{db}/snowflake.png', format='PNG')
    print(f'  drawable/snowflake.png {target}px OK')

    print('Icon ALL OK')
