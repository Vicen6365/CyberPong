"""Generate Android app icons for Cyber Pong"""
from PIL import Image, ImageDraw, ImageFont
import os

SIZES = {
    'mipmap-mdpi': 48,
    'mipmap-hdpi': 72,
    'mipmap-xhdpi': 96,
    'mipmap-xxhdpi': 144,
    'mipmap-xxxhdpi': 192,
}

def create_icon(size):
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Neon circle background
    margin = size // 8
    cx, cy = size // 2, size // 2
    r = size // 2 - margin

    # Outer glow
    for i in range(5, 0, -1):
        alpha = int(30 / (i + 1))
        draw.ellipse(
            [cx - r - i*2, cy - r - i*2, cx + r + i*2, cy + r + i*2],
            outline=(0, 255, 204, alpha),
            width=1
        )

    # Main circle
    draw.ellipse(
        [cx - r, cy - r, cx + r, cy + r],
        outline=(0, 255, 204, 220),
        width=max(2, size // 24)
    )

    # Inner glow
    draw.ellipse(
        [cx - r//2, cy - r//2, cx + r//2, cy + r//2],
        outline=(0, 255, 204, 60),
        width=1
    )

    # Paddle symbol (two parallel lines with a dot between them)
    pw = size // 6
    ph = size // 3
    gap = size // 6

    # Left paddle
    px1 = cx - gap // 2 - pw
    py1 = cy - ph // 2
    draw.rounded_rectangle(
        [px1, py1, px1 + pw, py1 + ph],
        radius=max(1, pw // 3),
        fill=(0, 255, 204, 200),
        outline=None
    )

    # Right paddle
    px2 = cx + gap // 2
    py2 = cy - ph // 2
    draw.rounded_rectangle(
        [px2, py2, px2 + pw, py2 + ph],
        radius=max(1, pw // 3),
        fill=(255, 51, 102, 200),
        outline=None
    )

    # Ball (dot in center)
    br = max(2, size // 20)
    draw.ellipse(
        [cx - br, cy - br, cx + br, cy + br],
        fill=(255, 255, 255, 230)
    )

    # Small particle dots
    import random
    random.seed(42)
    for _ in range(6):
        angle = random.random() * 3.14159 * 2
        dist = r * 0.7 + random.random() * r * 0.25
        px = cx + int(dist * __import__('math').cos(angle))
        py = cy + int(dist * __import__('math').sin(angle))
        pr = max(1, size // 30)
        draw.ellipse(
            [px - pr, py - pr, px + pr, py + pr],
            fill=(0, 255, 204, 100 + int(random.random() * 80))
        )

    return img

base = os.path.dirname(os.path.abspath(__file__))
res_dir = os.path.join(base, 'app', 'src', 'main', 'res')

for folder, size in SIZES.items():
    out_dir = os.path.join(res_dir, folder)
    os.makedirs(out_dir, exist_ok=True)
    icon = create_icon(size)
    icon.save(os.path.join(out_dir, 'ic_launcher.png'))
    print(f"Created {size}px icon → {folder}/ic_launcher.png")

print("All icons generated!")
