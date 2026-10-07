"""Python pixel study: compact wall sounder/strobe, 16 texels per block."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

HERE=Path(__file__).resolve().parent
TEX=HERE/'textures'
TEX.mkdir(parents=True,exist_ok=True)
BG='#e9e8df'
ON_MS=100
OFF_MS=1400


def casing():
    im=Image.new('RGBA',(16,16))
    d=ImageDraw.Draw(im)
    # Eight pixels across, twelve tall; stepped shoulders, flush lower rim.
    shape=[(5,2),(10,2),(11,3),(11,12),(10,13),(5,13),(4,12),(4,3)]
    d.polygon(shape,fill='#adbfc0')
    d.line(shape+[shape[0]],fill='#4d6469')
    d.line((5,2,10,2),fill='#e1e9db')
    d.line((4,3,4,11),fill='#bacfd0')
    d.rectangle((5,3,10,7),fill='#c5d2c9')
    d.line((5,3,5,7),fill='#dce4d5')
    # Three coarse sound apertures; no tiny words or decorative screws.
    for y in (3,5,7):
        d.line((6,y,9,y),fill='#3b5053')
        if y<7: d.line((6,y+1,9,y+1),fill='#97aaaa')
    d.line((5,8,10,8),fill='#a99568')
    d.point((5,8),fill='#dbc38f')
    # Pale reflector behind the tinted translucent lens.
    d.rectangle((5,9,10,12),fill='#9daaa1')
    d.rectangle((6,10,9,11),fill='#dce3cd')
    d.line((6,13,9,13),fill='#657a7b')
    return im


def lens(color,on):
    im=Image.new('RGBA',(16,16));d=ImageDraw.Draw(im)
    if color=='red':
        colors=([(226,68,49,218),(255,137,94,224),(255,219,157,250),(169,42,36,225)] if on else
                [(137,48,41,212),(182,96,78,214),(166,78,59,205),(104,40,38,221)])
    else:
        colors=([(234,134,37,218),(255,186,77,224),(255,236,178,250),(171,80,25,225)] if on else
                [(162,101,38,212),(201,145,69,214),(181,119,44,205),(119,75,33,221)])
    body,shine,core,edge=colors
    d.rectangle((5,9,10,12),fill=body)
    d.line((5,9,9,9),fill=shine)
    d.line((5,10,5,11),fill=shine)
    d.line((6,12,10,12),fill=edge)
    d.line((10,10,10,11),fill=edge)
    # Two bright cells form a flash tube behind the lens, rather than a white fill.
    d.line((7,10,8,10),fill=core)
    d.line((7,11,8,11),fill=shine if on else body)
    return im


def face(color,on):
    im=casing();im.alpha_composite(lens(color,on));return im


def label(im,xy,text,size=22,color='#344139'):
    ImageDraw.Draw(im).text(xy,text,font=ImageFont.truetype('/System/Library/Fonts/STHeiti Medium.ttc',size),fill=color)


def checker(size,step=24):
    im=Image.new('RGBA',size,BG);d=ImageDraw.Draw(im)
    for y in range(0,size[1],step):
        for x in range(0,size[0],step):
            if (x//step+y//step)%2:d.rectangle((x,y,x+step-1,y+step-1),fill='#d8dcd3')
    return im


def main():
    base=casing();base.save(TEX/'sounder_casing.png')
    states={}
    for color in ('red','orange'):
        for on in (False,True):
            suffix='on' if on else 'off'
            im=face(color,on);states[color,suffix]=im
            im.save(TEX/f'sounder_{color}_{suffix}.png')
            lens(color,on).save(TEX/f'lens_{color}_{suffix}.png')
            assert im.size==(16,16)
            assert im.getbbox()==(4,2,12,14)
        off,on=states[color,'off'],states[color,'on']
        changed=[(x,y) for y in range(16) for x in range(16) if off.getpixel((x,y))!=on.getpixel((x,y))]
        assert all(5<=x<=10 and 9<=y<=12 for x,y in changed)
    sheet=Image.new('RGBA',(1380,980),BG)
    label(sheet,(42,28),'壁挂声光报警器 / 贴图试稿',36)
    label(sheet,(44,83),'上蜂鸣器 · 下闪光灯 · 8×12 小面板 · 16×16 贴图画布',21,'#717a6a')
    d=ImageDraw.Draw(sheet);d.line((42,122,1338,122),fill='#b8beaf')
    hero=checker((480,480),30)
    hero.alpha_composite(states['red','off'].resize((480,480),Image.Resampling.NEAREST))
    sheet.alpha_composite(hero,(34,146))
    label(sheet,(94,639),'浅蓝灰外壳 / 黄铜分隔条',25)
    label(sheet,(94,682),'三道声孔，下方一体式有色灯罩。',20,'#717a6a')
    for i,color in enumerate(('red','orange')):
        for j,status in enumerate(('off','on')):
            x=620+j*355;y=150+i*270
            tile=checker((224,224),14)
            tile.alpha_composite(states[color,status].resize((224,224),Image.Resampling.NEAREST))
            sheet.alpha_composite(tile,(x,y))
            label(sheet,(x+35,y+224),('红色' if color=='red' else '橙色')+(' / 熄灯' if status=='off' else ' / 短闪'),21)
    # Timeline uses actual candidate durations, no simulated sound is embedded.
    label(sheet,(45,769),'闪烁节奏候选',25)
    start=315;length=1000;yy=789
    d.rectangle((start,yy,start+length,yy+19),fill='#b5beb3')
    for t in (0,1500):
        x=start+round(length*t/3000)
        d.rectangle((x,yy,x+round(length*ON_MS/3000)-1,yy+19),fill='#d9714c')
    label(sheet,(315,825),'亮 0.10 秒 → 灭 1.40 秒 → 重复',22)
    label(sheet,(45,920),'本轮仅贴图与无声动画预览。音效方向：清楚的柔和短提示音、缓起音，不用尖锐警笛或爆裂声。',18,'#717a6a')
    sheet.save(HERE/'wall_sounder_design_sheet.png')
    compact=checker((640,320),20)
    for i,color in enumerate(('red','orange')):
        compact.alpha_composite(states[color,'off'].resize((320,320),Image.Resampling.NEAREST),(i*320,0))
    compact.save(HERE/'wall_sounder_preview.png')
    frames=[]
    for status in ('off','on'):
        frame=Image.new('RGBA',(640,360),'#45545a')
        for i,color in enumerate(('red','orange')):
            frame.alpha_composite(states[color,status].resize((320,320),Image.Resampling.NEAREST),(i*320,0))
        label(frame,(107,324),'红色灯罩',19,'#e5e9df')
        label(frame,(427,324),'橙色灯罩',19,'#e5e9df')
        frames.append(frame.convert('RGB'))
    frames[0].save(HERE/'wall_sounder_pulse.gif',save_all=True,append_images=frames[1:],duration=[OFF_MS,ON_MS],loop=0,disposal=2)
    # Native-size checks, enlarged only by exact integer factors.
    sizes=Image.new('RGBA',(480,160),BG)
    for i,s in enumerate((1,2,3,4)):
        im=states['red','on'].resize((16*s,16*s),Image.Resampling.NEAREST)
        sizes.alpha_composite(im,(25+i*115,20));label(sizes,(25+i*115,110),f'{s}×',18)
    sizes.save(HERE/'readability.png')
    gif=Image.open(HERE/'wall_sounder_pulse.gif')
    durations=[]
    for i in range(gif.n_frames):gif.seek(i);durations.append(gif.info['duration'])
    assert durations==[OFF_MS,ON_MS]
    print('9 native textures; 8x12 footprint; flash changes confined to lens; GIF timing verified:',durations)


if __name__=='__main__':main()
