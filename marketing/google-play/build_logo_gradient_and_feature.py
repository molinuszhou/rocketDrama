from pathlib import Path
from collections import deque
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT=Path('/home/ubuntu/JoyeDrama_repo/marketing/google-play')
SRC_ICON=Path('/home/ubuntu/JoyeDrama_repo/android/app/src/main/res/drawable-nodpi/joyedrama_app_icon.png')
OUT=ROOT/'deliverables'
SRC=ROOT/'assets/source_screenshots'

# Exact logo-preserving background replacement. Flood-fill only the border-connected
# off-white background, so enclosed white logo highlights remain intact.
with Image.open(SRC_ICON) as im:
    original=im.convert('RGBA').resize((512,512),Image.Resampling.LANCZOS)
pix=original.load(); w,h=original.size
corner=pix[0,0]
def near_bg(c):
    # Keep the threshold tight so the cream strap and white play mark remain intact.
    return max(abs(c[i]-corner[i]) for i in range(3)) < 9
seen=set(); q=deque()
for x in range(w): q.extend([(x,0),(x,h-1)])
for y in range(h): q.extend([(0,y),(w-1,y)])
while q:
    x,y=q.popleft()
    if (x,y) in seen or not (0<=x<w and 0<=y<h): continue
    seen.add((x,y))
    if not near_bg(pix[x,y]): continue
    for nx,ny in ((x-1,y),(x+1,y),(x,y-1),(x,y+1)):
        if (nx,ny) not in seen: q.append((nx,ny))
# gradient base: pure-color plum to coral, no pattern.
base=Image.new('RGBA',(w,h))
bp=base.load()
for y in range(h):
    t=y/(h-1)
    top=(86,18,82); bottom=(248,64,60)
    for x in range(w):
        # tiny horizontal warmth variation, keeping the field visually plain.
        u=abs(x-w/2)/(w/2)
        bp[x,y]=(int(top[0]*(1-t)+bottom[0]*t), int(top[1]*(1-t)+bottom[1]*t), int(top[2]*(1-t)+bottom[2]*t),255)
for x,y in seen:
    pix[x,y]=(0,0,0,0)
base.alpha_composite(original)
base.convert('RGB').save(OUT/'app_icon_512x512.png','PNG',optimize=True)

# Feature graphic: App-first composition with three translucent screenshot silhouettes.
W,H=1024,500
feature=Image.new('RGBA',(W,H),(19,8,35,255))
fd=ImageDraw.Draw(feature,'RGBA')
# Smooth pure-color background with a subtle logo-inspired warm glow.
for y in range(H):
    t=y/(H-1)
    col=(int(18+50*t),int(8+14*t),int(38+18*t),255)
    fd.line((0,y,W,y),fill=col)
# right-side warm gradient glow
layer=Image.new('RGBA',(W,H),(0,0,0,0)); ld=ImageDraw.Draw(layer,'RGBA')
ld.ellipse((520,-230,1190,600),fill=(244,73,45,145))
ld.ellipse((670,-80,1130,430),fill=(255,178,43,80))
feature=Image.alpha_composite(feature,layer.filter(ImageFilter.GaussianBlur(65)))

# Screenshot silhouettes behind the brand copy: readable as device-like shapes, not content cards.
positions=[(650,20,820,430,-8),(775,65,945,475,5),(880,15,1050,425,14)]
files=sorted(SRC.glob('*showcase.jpg'))[0:3]
for path,(x,y,sw,sh,angle) in zip(files,positions):
    with Image.open(path) as src:
        thumb=src.convert('RGBA').resize((sw,sh),Image.Resampling.LANCZOS)
    # desaturate and darken to make a silhouette
    gray=thumb.convert('L').point(lambda p:int(p*.27))
    silhouette=Image.new('RGBA',thumb.size,(245,155,80,0)); silhouette.putalpha(gray.point(lambda p:int(p*.62)))
    silhouette=silhouette.rotate(angle,Image.Resampling.BICUBIC,expand=True)
    mask=Image.new('L',silhouette.size,0); ImageDraw.Draw(mask).rounded_rectangle((0,0,silhouette.width-1,silhouette.height-1),radius=22,fill=220)
    silhouette.putalpha(Image.composite(silhouette.getchannel('A'),Image.new('L',silhouette.size,0),mask))
    feature.alpha_composite(silhouette,(x,y))

# Original logo, now with a pure gradient background, and exact brand copy.
with Image.open(OUT/'app_icon_512x512.png') as icon:
    feature.alpha_composite(icon.convert('RGBA').resize((112,112),Image.Resampling.LANCZOS),(64,48))
fd=ImageDraw.Draw(feature,'RGBA')
bold=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',57)
sub=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',25)
fd.text((196,63),'JoyeDrama',font=bold,fill=(255,246,230,255))
fd.text((196,132),'Short drama, made for your moment',font=sub,fill=(246,220,187,255))
fd.multiline_text((62,260),'Watch. Discover.\nStay for the story.',font=bold,fill=(255,245,225,255),spacing=3)
fd.text((64,430),'A beautiful home for short drama',font=sub,fill=(244,219,183,255))
feature.convert('RGB').save(OUT/'feature_graphic_1024x500.png','PNG',optimize=True)
print('Wrote exact-logo gradient icon and silhouette feature graphic')
