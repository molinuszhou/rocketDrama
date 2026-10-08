from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path('/home/ubuntu/JoyeDrama_repo/marketing/google-play')
SOURCE = ROOT / 'assets' / 'source_screenshots'
BACKGROUNDS = ROOT / 'redesign' / 'backgrounds'
OUT = ROOT / 'deliverables' / 'screenshots'
OUT.mkdir(parents=True, exist_ok=True)

FINAL_SIZE = (1080, 1920)
CANVAS_SIZE = (1440, 2560)
STATUS_BAR_HEIGHT = 76
GESTURE_AREA_HEIGHT = 50
# Source screenshots are 720x1600. After chrome removal they are 720x1474.
# This box preserves that exact ratio: 920 / 1883 = 720 / 1474.
UI_BOX = (260, 560, 1180, 2443)
PANEL_BOX = UI_BOX
CORNER_RADIUS = 34

ITEMS = [
    (BACKGROUNDS / '01_brand_home.png', 'Screenshot_2026-09-28-08-56-42-616_com.dramaflow.app.showcase.jpg', '01_home_categories.jpg', 'All your stories.\nIn one place.', 'Discover short drama made for your moment'),
    (BACKGROUNDS / '02_brand_library.png', 'Screenshot_2026-09-28-08-58-03-609_com.dramaflow.app.showcase.jpg', '02_explore_library.jpg', 'Find your next\nfavorite.', 'A library of short stories, ready to play'),
    (BACKGROUNDS / '03_brand_discover.png', 'Screenshot_2026-09-28-08-58-17-243_com.dramaflow.app.showcase.jpg', '03_trending_stories.jpg', 'Browse. Play.\nRepeat.', 'Simple discovery for every mood'),
    (BACKGROUNDS / '04_brand_story.png', 'Screenshot_2026-09-28-09-08-36-670_com.dramaflow.app.showcase.jpg', '04_story_details.jpg', 'Every story\nstarts here.', 'Meet a new world in just one tap'),
    (BACKGROUNDS / '05_brand_player.png', 'Screenshot_2026-09-28-09-09-06-919_com.dramaflow.app.showcase.jpg', '05_player.jpg', 'Press play.\nStay awhile.', 'Short episodes. Big feelings.'),
]

bold_path = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
regular_path = '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
headline_font = ImageFont.truetype(bold_path, 84)
subtitle_font = ImageFont.truetype(regular_path, 30)

for backdrop_path, source_name, output_name, headline, subtitle in ITEMS:
    with Image.open(backdrop_path) as image:
        base = image.convert('RGB').resize(CANVAS_SIZE, Image.Resampling.LANCZOS).convert('RGBA')
    with Image.open(SOURCE / source_name) as original:
        app = original.convert('RGB').crop((0, STATUS_BAR_HEIGHT, original.width, original.height - GESTURE_AREA_HEIGHT))

    x1, y1, x2, y2 = UI_BOX
    target_w, target_h = x2 - x1, y2 - y1
    # Exact resize, not thumbnail/contain: the real UI reaches every edge of the outer frame.
    app = app.resize((target_w, target_h), Image.Resampling.LANCZOS)

    # Cover any generated safe-area placeholder, then add app-focused copy.
    panel = ImageDraw.Draw(base)
    panel.rounded_rectangle(PANEL_BOX, radius=74, fill=(20, 12, 29), outline=(231, 107, 91), width=3)
    panel.multiline_text((720, 155), headline, anchor='ma', align='center', font=headline_font,
                         fill=(255, 240, 226), spacing=10)
    panel.text((720, 365), subtitle, anchor='ma', font=subtitle_font, fill=(205, 224, 235))

    # Shadow only follows the real screenshot boundary, not a larger invented device frame.
    shadow = Image.new('RGBA', CANVAS_SIZE, (0, 0, 0, 0))
    shadow_draw = ImageDraw.Draw(shadow)
    shadow_draw.rounded_rectangle((x1 - 14, y1 - 14, x2 + 14, y2 + 14), radius=CORNER_RADIUS + 12, fill=(0, 0, 0, 180))
    base = Image.alpha_composite(base, shadow.filter(ImageFilter.GaussianBlur(14)))

    mask = Image.new('L', app.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, app.width - 1, app.height - 1), radius=CORNER_RADIUS, fill=255)
    base.paste(app, (x1, y1), mask)
    ImageDraw.Draw(base).rounded_rectangle((x1, y1, x2, y2), radius=CORNER_RADIUS, outline=(247, 117, 95, 210), width=3)

    final = base.convert('RGB').resize(FINAL_SIZE, Image.Resampling.LANCZOS)
    final.save(OUT / output_name, 'JPEG', quality=96, optimize=True, progressive=True)

print(f'Wrote {len(ITEMS)} designed screenshots to {OUT}')
