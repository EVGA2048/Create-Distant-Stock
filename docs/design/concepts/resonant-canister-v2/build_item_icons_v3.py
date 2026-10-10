from pathlib import Path
import io
import zipfile
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[4]
HERE = Path(__file__).resolve().parent
OUT = HERE / 'renders2d_v3'
OUT.mkdir(exist_ok=True)

CREATE_JAR = Path.home() / 'Documents/minecraft_launcher/.minecraft/versions/ES2_Firmament_1.21.1_9th_9.3.2_SunlightSignal/mods/create-1.21.1-6.0.10.jar'

# Palette sampled directly from Create 6.0.10 item textures.
ANDESITE = {
    'ink': (43, 54, 53, 255),
    'dark': (74, 84, 81, 255),
    'mid': (94, 105, 99, 255),
    'green': (130, 151, 137, 255),
    'light': (169, 175, 161, 255),
    'hi': (201, 202, 186, 255),
    'white': (230, 230, 219, 255),
}
BRASS = {
    'ink': (96, 47, 36, 255),
    'dark': (118, 70, 45, 255),
    'mid': (153, 90, 61, 255),
    'warm': (181, 120, 73, 255),
    'gold': (211, 161, 85, 255),
    'light': (251, 204, 104, 255),
    'hi': (255, 235, 148, 255),
    'white': (255, 249, 199, 255),
}
ETHER = [(198,227,241,255),(218,237,246,255),(234,245,250,255),(244,250,253,255)]
AMETHYST = [(83,53,111,255),(112,71,137,255),(142,91,164,255),(199,149,210,255)]
EMPTY = [(70,76,74,255),(92,99,96,255),(130,136,130,255),(178,181,171,255)]


def p(im, x, y, c):
    if 0 <= x < 16 and 0 <= y < 16:
        im.putpixel((x, y), c)


def icon(liquid):
    """Hand-authored 16x16 sprite, intentionally asymmetrical like Create's generated items."""
    im = Image.new('RGBA', (16, 16), (0,0,0,0))
    d = ImageDraw.Draw(im)

    # Silhouette: a compact cartridge leaning very slightly right, not a front-on bottle.
    body = [(5,3),(9,2),(11,3),(12,5),(11,11),(9,13),(6,14),(4,12),(4,6)]
    d.polygon(body, fill=ANDESITE['ink'])

    # Main andesite shell; uneven facets are deliberate.
    d.polygon([(6,4),(9,3),(10,4),(11,5),(10,10),(9,12),(6,13),(5,11),(5,6)], fill=ANDESITE['mid'])
    d.polygon([(6,4),(8,3),(8,12),(6,13),(5,11),(5,6)], fill=ANDESITE['light'])
    d.polygon([(6,5),(7,4),(7,11),(6,12)], fill=ANDESITE['hi'])
    p(im, 6, 5, ANDESITE['white'])
    p(im, 10, 5, ANDESITE['dark'])
    p(im, 10, 10, ANDESITE['dark'])

    # Brass cap: a small mechanical closure, not a decorative gold band around the whole item.
    d.polygon([(6,2),(9,1),(11,2),(11,4),(9,4),(6,5),(5,4),(5,3)], fill=BRASS['ink'])
    d.polygon([(7,2),(9,2),(10,2),(10,3),(8,3),(6,4),(6,3)], fill=BRASS['mid'])
    p(im, 7, 2, BRASS['hi']); p(im, 8, 2, BRASS['light']); p(im, 6, 3, BRASS['warm'])

    # Fill nipple / valve, offset like a real component.
    p(im, 8, 0, BRASS['dark']); p(im, 9, 0, BRASS['mid'])
    p(im, 8, 1, BRASS['light']); p(im, 9, 1, BRASS['ink'])

    # Small lower retaining shoe, only on the visible corner.
    d.polygon([(5,12),(7,13),(10,12),(10,14),(8,15),(5,15),(4,14),(4,13)], fill=BRASS['ink'])
    d.polygon([(6,13),(8,14),(9,13),(9,14),(8,14),(6,14)], fill=BRASS['gold'])
    p(im, 6, 13, BRASS['hi'])

    # Sight glass: narrow, inset, and partly occluded by the shell; this is the only fluid cue.
    # Black recess first.
    for xy in [(9,5),(10,5),(9,6),(10,6),(9,7),(10,7),(9,8),(10,8),(9,9),(10,9)]:
        p(im,*xy,ANDESITE['ink'])
    # Fluid stripe one pixel wide with one specular pixel; deliberately tiny like Create details.
    cols = EMPTY if liquid == 'empty' else ETHER if liquid == 'ether' else AMETHYST
    for y,c in zip(range(6,10), cols):
        p(im,9,y,c)
    p(im,10,6,(230,230,219,180) if liquid=='empty' else cols[-1])

    # One brass clamp ear and one dark seam keep the sprite mechanically busy without symmetry.
    p(im,3,8,BRASS['ink']); p(im,4,8,BRASS['gold']); p(im,3,9,BRASS['mid'])
    p(im,6,9,ANDESITE['dark']); p(im,7,10,ANDESITE['dark'])

    return im


def reference(name):
    with zipfile.ZipFile(CREATE_JAR) as z:
        return Image.open(io.BytesIO(z.read(f'assets/create/textures/item/{name}.png'))).convert('RGBA')


icons = {k: icon(k) for k in ('empty','ether','amethyst')}
for k,im in icons.items():
    im.save(OUT / f'{k}.png')

# Comparison sheet: real Create originals first, then our candidate at the same native scale.
refs = [('goggles', reference('goggles')),
        ('electron tube', reference('electron_tube')),
        ('precision mechanism', reference('precision_mechanism')),
        ('andesite alloy', reference('andesite_alloy'))]

W,H = 1180,430
sheet = Image.new('RGBA',(W,H),(235,234,226,255))
d = ImageDraw.Draw(sheet)
try:
    font = ImageFont.truetype('/System/Library/Fonts/Helvetica.ttc',16)
    title = ImageFont.truetype('/System/Library/Fonts/Helvetica.ttc',22)
except OSError:
    font = title = ImageFont.load_default()
d.text((24,18),'Create 6.0.10 originals / Resonant Canister V3',font=title,fill=(48,56,54,255))
d.text((24,48),'All icons shown at the same nearest-neighbour scale. Candidate is native 16x16.',font=font,fill=(95,103,99,255))

entries = refs + [('empty',icons['empty']),('ether',icons['ether']),('molten amethyst',icons['amethyst'])]
for i,(name,im) in enumerate(entries):
    x=22+i*164; y=92
    panel=Image.new('RGBA',(148,280),(248,247,242,255))
    pd=ImageDraw.Draw(panel)
    scale=8
    up=im.resize((im.width*scale,im.height*scale),Image.Resampling.NEAREST)
    panel.alpha_composite(up,((148-up.width)//2,24))
    pd.text((10,210),name,font=font,fill=(50,58,56,255))
    pd.text((10,236),f'{im.width}x{im.height}',font=font,fill=(110,116,112,255))
    sheet.alpha_composite(panel,(x,y))

path = HERE/'item_sheet_v3.png'
sheet.save(path)
print(path)
