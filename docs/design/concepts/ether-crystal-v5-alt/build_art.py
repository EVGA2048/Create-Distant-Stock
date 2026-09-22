from pathlib import Path
from PIL import Image, ImageDraw

OUT = Path(__file__).resolve().parent

def build():
    im = Image.new('RGBA', (16,16), (0,0,0,0))
    d = ImageDraw.Draw(im)
    # Short oblique hexagonal prism. Broad faces, not a glowing core.
    border=(64,101,121,242)
    dark=(74,122,150,232)
    blue=(103,157,181,215)
    mid=(148,196,207,182)
    light=(184,223,225,193)
    glass=(208,240,235,218)
    white=(237,251,242,255)
    # Broad hexagonal cut end: a short and heavy prism, rather than a tube.
    d.polygon([(9,1),(12,2),(14,5),(13,7),(8,12),(6,14),(3,12),(1,9),(4,5)],fill=border)
    d.polygon([(9,2),(7,5),(2,10),(2,8),(5,4)],fill=(172,216,224,235))
    d.polygon([(7,5),(11,8),(6,13),(2,10)],fill=(141,192,209,204))
    d.polygon([(11,8),(14,5),(13,7),(8,12),(6,13)],fill=(92,148,179,236))
    # Single large front cut with a slim reflected lower edge.
    d.polygon([(9,2),(11,2),(13,4),(13,5),(11,7),(8,6),(7,4)],fill=(203,234,231,237))
    d.polygon([(10,3),(11,3),(12,4),(12,5),(11,6),(9,5),(9,4)],fill=(176,213,218,217))
    d.line([(8,6),(10,7),(11,7),(12,6)],fill=(116,171,193,230),width=1)
    # Upper bevel and sparse brilliant corners.
    d.line([(7,5),(6,6),(5,7),(4,8),(3,9)],fill=(208,237,234,250),width=1)
    d.point((8,3),fill=white)
    d.point((7,5),fill=white)
    d.point((4,8),fill=(228,244,236,248))
    # Two broad transmission planes; no ornament or luminous crystal core.
    d.polygon([(8,7),(9,8),(7,10),(5,11),(4,10)],fill=(174,209,218,174))
    d.line([(8,9),(9,9)],fill=(118,168,190,190),width=1)
    d.line([(2,10),(3,11),(5,13),(6,13)],fill=(117,169,194,240),width=1)
    d.point((3,10),fill=(189,226,228,238))
    d.point((6,12),fill=(182,216,223,222))
    # Remove one-pixel protrusions to keep both cut ends deliberately blunt.
    for xy in [(9,1),(1,9),(6,14)]:
        im.putpixel(xy,(0,0,0,0))
    return im

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    im=build()
    im.save(OUT/'texture.png')
    sheet=Image.new('RGB',(864,472),(232,228,217))
    draw=ImageDraw.Draw(sheet)
    for i,bg in enumerate([(235,231,220),(49,64,72)]):
        tile=Image.new('RGBA',(16,16),bg+(255,));tile.alpha_composite(im)
        sheet.paste(tile.resize((384,384),Image.Resampling.NEAREST).convert('RGB'),(24+i*432,24))
        sheet.paste(tile.resize((32,32),Image.Resampling.NEAREST).convert('RGB'),(192+i*432,424))
    sheet.save(OUT/'preview.png')

if __name__=='__main__': main()
