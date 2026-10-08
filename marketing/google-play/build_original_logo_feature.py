from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont
import math, shutil

ROOT=Path('/home/ubuntu/JoyeDrama_repo/marketing/google-play')
OUT=ROOT/'deliverables'
SRC=ROOT/'assets/source_screenshots'
UPLOADED=Path('/home/ubuntu/upload/pasted_file_H4tNfI_joyedrama_app_icon.png')
# Use the user's uploaded Logo byte-for-byte; do not edit it.
shutil.copy2(UPLOADED, OUT/'app_icon_512x512.png')

W,H=1024,500
# Logo-matched palette: the icon's warm ivory surface, orange-red body, and golden accents.
# The accent field is a cleaner rose-coral pink rather than a muddy orange-pink.
# A light field makes the original cream Logo blend into the banner instead of looking boxed in.
feature=Image.new('RGBA',(W,H),(255,248,232,255))
px=feature.load()
for y in range(H):
    t=y/(H-1)
    for x in range(W):
        u=x/(W-1)
        warm=math.exp(-(((u-.78)/.48)**2+((t-.48)/.95)**2))
        px[x,y]=(255, int(253-63*warm), int(247-25*warm),255)
# Soft coral/orange light behind the phone group, without introducing a second visual language.
glow=Image.new('RGBA',(W,H),(0,0,0,0)); gd=ImageDraw.Draw(glow,'RGBA')
gd.ellipse((570,-260,1130,520),fill=(235,61,101,135))
gd.ellipse((660,35,1080,450),fill=(255,178,34,75))
feature=Image.alpha_composite(feature,glow.filter(ImageFilter.GaussianBlur(58)))
fd=ImageDraw.Draw(feature,'RGBA')
pts=[(360+i*15,int(290+62*math.sin(i/8))) for i in range(48)]
fd.line(pts,fill=(255,101,38,190),width=18,joint='curve')
fd.line([(x,y-24) for x,y in pts],fill=(255,205,86,125),width=4,joint='curve')

# Place three staggered phone cards on the right, each showing a real drama image.
files=sorted(SRC.glob('*showcase.jpg'))
placements=[(635,58,176,384,-10),(758,8,176,384,4),(875,55,176,384,15)]
for path,(x,y,sw,sh,angle) in zip(files[:3],placements):
    with Image.open(path) as src:
        # Use the upper visual area of each source so the cards read as drama stills.
        img=src.convert('RGB').resize((sw,sh),Image.Resampling.LANCZOS)
    card=Image.new('RGBA',(sw+20,sh+20),(0,0,0,0)); cd=ImageDraw.Draw(card,'RGBA')
    cd.rounded_rectangle((0,0,sw+19,sh+19),radius=26,fill=(6,6,12,245),outline=(239,112,35,245),width=4)
    mask=Image.new('L',(sw,sh),0); ImageDraw.Draw(mask).rounded_rectangle((0,0,sw-1,sh-1),radius=17,fill=255)
    card.paste(img,(10,10),mask)
    card=card.rotate(angle,Image.Resampling.BICUBIC,expand=True)
    # small shadow to separate the staggered devices from the background
    shadow=Image.new('RGBA',card.size,(0,0,0,0)); shadow.alpha_composite(card)
    shadow=shadow.filter(ImageFilter.GaussianBlur(7))
    feature.alpha_composite(shadow,(x+8,y+12))
    feature.alpha_composite(card,(x,y))

# User-provided original logo in the brand header.
# Use the original Logo artwork in the banner, but remove only its border-connected
# cream background so the Logo itself blends into the banner field.
with Image.open(UPLOADED) as source_logo:
    logo=source_logo.convert('RGBA').resize((108,108),Image.Resampling.LANCZOS)
lp=logo.load(); lw,lh=logo.size; corner=lp[0,0]
def logo_bg(c): return max(abs(c[i]-corner[i]) for i in range(3)) < 9
seen=set(); stack=[]
for x in range(lw): stack.extend([(x,0),(x,lh-1)])
for y in range(lh): stack.extend([(0,y),(lw-1,y)])
while stack:
    x,y=stack.pop()
    if (x,y) in seen or not (0<=x<lw and 0<=y<lh): continue
    seen.add((x,y))
    if not logo_bg(lp[x,y]): continue
    for nx,ny in ((x-1,y),(x+1,y),(x,y-1),(x,y+1)):
        if (nx,ny) not in seen: stack.append((nx,ny))
for x,y in seen: lp[x,y]=(0,0,0,0)
feature.alpha_composite(logo,(58,44))
fd=ImageDraw.Draw(feature,'RGBA')
bold=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',55)
sub=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',24)
fd.text((188,62),'JoyeDrama',font=bold,fill=(106,26,48,255))
fd.text((188,128),'Short drama, made for your moment',font=sub,fill=(137,57,43,255))
fd.multiline_text((58,260),'Watch. Discover.\nStay for the story.',font=bold,fill=(106,26,48,255),spacing=3)
fd.text((60,431),'A beautiful home for short drama',font=sub,fill=(137,57,43,255))
feature.convert('RGB').save(OUT/'feature_graphic_1024x500.png','PNG',optimize=True)
print('Restored user logo and created phone-still feature graphic')
