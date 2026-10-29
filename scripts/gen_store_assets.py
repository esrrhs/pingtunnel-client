"""生成 Play 上架素材：512 图标、1024x500 置顶图（图标取自 ic_launcher_foreground.xml 的几何）。"""
from PIL import Image, ImageDraw, ImageFont
import os
BG=(0x1C,0x14,0x08); AMBER=(0xFF,0xB7,0x4D); S=4
out=os.path.join(os.path.dirname(__file__),"..","store")

def logo(size, view=(22,22,86,86)):
    """按 108 视口几何绘制图标，裁出 view 区域，返回 RGBA 透明底。"""
    k=size*S/(view[2]-view[0]); W=size*S
    im=Image.new("RGBA",(W,W),(0,0,0,0)); d=ImageDraw.Draw(im)
    p=lambda x,y:((x-view[0])*k,(y-view[1])*k)
    def arch(r,col):
        cx,cy=54,54
        d.pieslice([*p(cx-r,cy-r),*p(cx+r,cy+r)],180,360,fill=col)
        d.rectangle([*p(cx-r,cy),*p(cx+r,76)],fill=col)
    arch(22,AMBER); arch(13,(0,0,0,0))
    # 内圈需真正挖空
    mask=Image.new("L",(W,W),0); md=ImageDraw.Draw(mask)
    md.pieslice([*p(41,41),*p(67,67)],180,360,fill=255); md.rectangle([*p(41,54),*p(67,76)],fill=255)
    im.paste((0,0,0,0),mask=mask)
    pts=[p(*q) for q in [(44,62),(48.5,62),(51,55),(57,69),(59.5,62),(64,62)]]
    d.line(pts,fill=(255,255,255,255),width=int(3.4*k),joint="curve")
    for x,y in (pts[0],pts[-1]):
        r=1.7*k; d.ellipse([x-r,y-r,x+r,y+r],fill="white")
    return im.resize((size,size),Image.LANCZOS)

icon=Image.new("RGBA",(512,512),BG+(255,)); icon.alpha_composite(logo(512,(18,18,90,90)))
icon.convert("RGB").save(f"{out}/icon-512.png")

g=Image.new("RGB",(1024,500),BG); d=ImageDraw.Draw(g)
for i in range(500):  # 简单渐变
    c=tuple(int(BG[j]+(v-BG[j])*0.0) for j,v in enumerate(BG)); 
g.paste(logo(300,(22,22,86,86)),(90,100),logo(300,(22,22,86,86)))
def font(sz):
    for f in ["/System/Library/Fonts/Helvetica.ttc","/System/Library/Fonts/PingFang.ttc"]:
        try: return ImageFont.truetype(f,sz)
        except Exception: pass
    return ImageFont.load_default()
d.text((430,170),"PingTunnel",font=font(86),fill=AMBER)
d.text((434,285),"Tunnel your traffic through ICMP ping",font=font(30),fill=(235,225,210))
g.save(f"{out}/feature-graphic-1024x500.png")
