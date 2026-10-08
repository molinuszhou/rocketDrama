from pathlib import Path
from PIL import Image, ImageFilter

ROOT = Path('/home/ubuntu/JoyeDrama_repo/marketing/google-play')
SOURCE = ROOT / 'assets' / 'source_screenshots'
OUTPUT = ROOT / 'deliverables' / 'screenshots'
OUTPUT.mkdir(parents=True, exist_ok=True)

# Google Play-compatible 9:16 portrait canvas. The original app pixels are preserved
# in full; only a dark blurred side extension is added to bring the source 9:20 screens
# inside Play's 2:1 aspect-ratio limit.
TARGET = (1080, 1920)
ITEMS = [
    ('Screenshot_2026-09-28-08-56-42-616_com.dramaflow.app.showcase.jpg', '01_home_categories.jpg'),
    ('Screenshot_2026-09-28-08-58-03-609_com.dramaflow.app.showcase.jpg', '02_explore_library.jpg'),
    ('Screenshot_2026-09-28-08-58-17-243_com.dramaflow.app.showcase.jpg', '03_trending_stories.jpg'),
    ('Screenshot_2026-09-28-09-08-36-670_com.dramaflow.app.showcase.jpg', '04_story_details.jpg'),
    ('Screenshot_2026-09-28-09-09-06-919_com.dramaflow.app.showcase.jpg', '05_player.jpg'),
]

for source_name, output_name in ITEMS:
    with Image.open(SOURCE / source_name) as original:
        original = original.convert('RGB')
        scaled = original.resize((864, 1920), Image.Resampling.LANCZOS)
        # Create a non-distracting edge fill from the same in-app visual palette.
        backdrop = original.resize(TARGET, Image.Resampling.LANCZOS).filter(ImageFilter.GaussianBlur(36))
        backdrop = backdrop.point(lambda p: int(p * 0.22))
        backdrop.paste(scaled, ((TARGET[0] - scaled.width) // 2, 0))
        backdrop.save(OUTPUT / output_name, 'JPEG', quality=96, optimize=True, progressive=True)

print(f'Wrote {len(ITEMS)} screenshots to {OUTPUT}')
