from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont
import math

ROOT = Path('/home/ubuntu/JoyeDrama_repo/marketing/google-play')
SOURCE = ROOT / 'assets/source_screenshots'
OUT = ROOT / 'deliverables'
SCREEN_OUT = OUT / 'screenshots'
BG_OUT = ROOT / 'redesign/logo_backgrounds'
SCREEN_OUT.mkdir(parents=True, exist_ok=True)
BG_OUT.mkdir(parents=True, exist_ok=True)

CANVAS = (1440, 2560)
FINAL = (1080, 1920)
# Full source screenshot retained, including Android status bar and bottom gesture area.
# 720/1600 = 0.45. The phone screen fills this exact aspect ratio.
# A wide, realistic phone body with enough breathing room for the larger subtitle.
# The screen itself remains the exact 720:1600 source ratio and keeps the status bar.
PHONE = (230, 360, 1210, 2538)  # 980 x 2178, exact 0.45 ratio

ITEMS = [
    ('Screenshot_2026-09-28-08-56-42-616_com.dramaflow.app.showcase.jpg', '01_home_categories.jpg', 'Your stories.\nYour moment.', 'Discover short drama in one beautiful place.'),
    ('Screenshot_2026-09-28-08-58-03-609_com.dramaflow.app.showcase.jpg', '02_explore_library.jpg', 'Find something\nyou will love.', 'Browse stories made for every mood.'),
    ('Screenshot_2026-09-28-08-58-17-243_com.dramaflow.app.showcase.jpg', '03_trending_stories.jpg', 'Browse. Play.\nRepeat.', 'A simpler way to discover short drama.'),
    ('Screenshot_2026-09-28-09-08-36-670_com.dramaflow.app.showcase.jpg', '04_story_details.jpg', 'Step into\na new story.', 'Meet your next favorite in one tap.'),
    ('Screenshot_2026-09-28-09-09-06-919_com.dramaflow.app.showcase.jpg', '05_player.jpg', 'Press play.\nStay awhile.', 'Short episodes. Big feelings.'),
]

# Logo-derived palette: warm cream, golden yellow, orange-red, with a deep plum
# backdrop to keep the real app screen readable.
def make_background(index: int, title: str, subtitle: str) -> Image.Image:
    w, h = CANVAS
    im = Image.new('RGBA', CANVAS, (255, 253, 247, 255))
    px = im.load()
    for y in range(h):
        t = y / (h - 1)
        for x in range(w):
            u = x / (w - 1)
            # Match the feature graphic: warm ivory base, coral light on the right,
            # and a restrained orange glow around the phone.
            glow = math.exp(-(((u - .78) / .48) ** 2 + ((t - .52) / .9) ** 2))
            warm = math.exp(-(((u - (.20 + index*.12)) / .36) ** 2 + ((t - .22) / .45) ** 2))
            r = int(255)
            g = int(253 - 63*glow - 7*warm)
            b = int(247 - 25*glow - 8*warm)
            px[x, y] = (r, g, b, 255)
    d = ImageDraw.Draw(im, 'RGBA')
    # Soft logo-colored orbs and a simple ribbon, no show imagery.
    for cx, cy, radius, color in [
        (180 + index*210, 350, 280, (255, 178, 34, 75)),
        (1240, 250 + index*100, 350, (235, 61, 101, 125)),
        (1180, 2200, 420, (255, 132, 33, 65)),
    ]:
        orb = Image.new('RGBA', CANVAS, (0,0,0,0))
        od = ImageDraw.Draw(orb, 'RGBA')
        od.ellipse((cx-radius, cy-radius, cx+radius, cy+radius), fill=color)
        im = Image.alpha_composite(im, orb.filter(ImageFilter.GaussianBlur(95)))
    d = ImageDraw.Draw(im, 'RGBA')
    points=[]
    for x in range(-100, w+200, 18):
        y = int(690 + 150*math.sin((x/190)+index*.7))
        points.append((x,y))
    d.line(points, fill=(239, 88, 35, 190), width=24, joint='curve')
    d.line([(x,y+30) for x,y in points], fill=(255, 194, 62, 120), width=5, joint='curve')
    # Brand copy above the device. Subheading is intentionally large.
    bold = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 90)
    sub = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 42)
    d.multiline_text((720, 60), title, anchor='ma', align='center', font=bold,
                     fill=(106, 26, 48, 255), spacing=8)
    d.text((720, 250), subtitle, anchor='ma', font=sub, fill=(137, 57, 43, 255))
    return im

for i, (source_name, output_name, title, subtitle) in enumerate(ITEMS):
    bg = make_background(i, title, subtitle)
    bg.save(BG_OUT / f'{i+1:02d}_logo_brand_background.png')
    with Image.open(SOURCE / source_name) as source:
        # Keep the complete source capture, including status bar.
        screen = source.convert('RGB').resize((PHONE[2]-PHONE[0], PHONE[3]-PHONE[1]), Image.Resampling.LANCZOS)
    x1,y1,x2,y2 = PHONE
    # Realistic phone body, wider than the screen by 26px each side.
    body = Image.new('RGBA', CANVAS, (0,0,0,0))
    bd = ImageDraw.Draw(body, 'RGBA')
    bd.rounded_rectangle((x1-26,y1-26,x2+26,y2+26), radius=92, fill=(7,7,12,255), outline=(255,176,72,220), width=5)
    body = body.filter(ImageFilter.GaussianBlur(0.4))
    bg = Image.alpha_composite(bg, body)
    mask = Image.new('L', screen.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0,0,screen.width-1,screen.height-1), radius=62, fill=255)
    bg.paste(screen, (x1,y1), mask)
    edge = ImageDraw.Draw(bg, 'RGBA')
    edge.rounded_rectangle((x1,y1,x2,y2), radius=62, outline=(255,191,108,220), width=3)
    bg.convert('RGB').resize(FINAL, Image.Resampling.LANCZOS).save(SCREEN_OUT/output_name, 'JPEG', quality=96, optimize=True, progressive=True)

# Rebuild the feature graphic around the original Logo, with a warmer coordinated backdrop.
feature = Image.new('RGBA', (1024,500), (11,8,27,255))
fd = ImageDraw.Draw(feature, 'RGBA')
for r,c,a in [(430,(255,130,25),100),(300,(238,54,30),70),(220,(255,200,70),65)]:
    layer=Image.new('RGBA',feature.size,(0,0,0,0)); ld=ImageDraw.Draw(layer,'RGBA')
    ld.ellipse((650-r,245-r,650+r,245+r),fill=(*c,a)); feature=Image.alpha_composite(feature,layer.filter(ImageFilter.GaussianBlur(55)))
fd=ImageDraw.Draw(feature,'RGBA')
pts=[(420+i*14, int(270+65*math.sin(i/8))) for i in range(48)]
fd.line(pts,fill=(255,111,35,210),width=20,joint='curve')
fd.line([(x,y-24) for x,y in pts],fill=(255,208,90,130),width=4,joint='curve')
with Image.open(OUT/'app_icon_512x512.png') as icon:
    feature.alpha_composite(icon.convert('RGBA').resize((138,138),Image.Resampling.LANCZOS),(70,64))
fb=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',60)
fs=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',27)
fd=ImageDraw.Draw(feature,'RGBA')
fd.text((236,75),'JoyeDrama',font=fb,fill=(255,246,226,255))
fd.text((236,151),'Short drama, made for your moment',font=fs,fill=(243,218,184,255))
fd.text((72,286),'Watch. Discover.\nStay for the story.',font=fb,fill=(255,245,223,255),spacing=4)
fd.text((74,438),'A beautiful home for short drama',font=fs,fill=(244,220,187,255))
feature.convert('RGB').save(OUT/'feature_graphic_1024x500.png','PNG',optimize=True)
print('Wrote logo-based phone screenshots and feature graphic')
