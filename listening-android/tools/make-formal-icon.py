"""Code-native icon: matched circle scale, spacing and squared stroke terminals.
The glyphs are outlined geometry, independent of installed font rendering.
"""
from pathlib import Path
from PIL import Image,ImageDraw
root=Path(__file__).resolve().parents[1];orange='#ff6b00';scale=4
img=Image.new('RGBA',(512*scale,512*scale));d=ImageDraw.Draw(img)
d.rounded_rectangle((0,0,512*scale-1,512*scale-1),radius=90*scale,fill='white')
parts=['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512"><rect width="512" height="512" rx="90" fill="white"/>']
def circle(cx,cy,r,fill):
    d.ellipse(((cx-r)*scale,(cy-r)*scale,(cx+r)*scale,(cy+r)*scale),fill=fill)
    parts.append(f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{fill}"/>')
def poly(points,fill):
    if fill==orange and max(y for x,y in points)<250:points=[(x,(y-169)*1.1+151) for x,y in points]
    d.polygon([(round(x*scale),round(y*scale)) for x,y in points],fill=fill)
    parts.append('<polygon points="'+' '.join(f'{x},{y}' for x,y in points)+'" fill="'+fill+'"/>')
circle(256,151,98,orange);circle(256,151,90,'white');circle(256,354,94,orange)
# 不: straight, uniform strokes with the separated right falling stroke of the reference.
poly([(197,108),(315,108),(315,123),(276,123),(262,140),(262,230),(245,230),(245,160),(204,199),(192,186),(255,123),(197,123)],orange)
poly([(278,143),(318,183),(306,195),(278,167)],orange)
# 听: balance the visual mass of 口 and 斤 inside the lower filled disc.
poly([(198,304),(239,304),(239,395),(198,395)],'white')
poly([(211,317),(226,317),(226,382),(211,382)],orange)
poly([(251,305),(310,295),(313,310),(266,318),(266,338),(320,338),(320,353),(295,353),(295,415),(280,415),(280,353),(266,353),(264,380),(257,399),(246,414),(234,403),(244,389),(249,373),(251,350)],'white')
parts.append('</svg>');(root/'content/app-icon.svg').write_text(''.join(parts),encoding='utf-8')
img=img.resize((512,512),Image.Resampling.LANCZOS)
img.save(root/'content/app-icon.png');img.save(root/'res/drawable/app_icon.png')
