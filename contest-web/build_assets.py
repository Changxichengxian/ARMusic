"""Render the two original screen-print covers and three demo arrangements."""
from pathlib import Path
import math
import random
import wave

import numpy as np
from PIL import Image, ImageDraw

OUT = Path(__file__).parent / "site" / "assets"
OUT.mkdir(parents=True, exist_ok=True)
W, H = 1000, 620


def poster(night):
    rng = random.Random(43 if night else 51)
    img = Image.new("RGB", (W, H), "#16494c" if night else "#efbc7f")
    d = ImageDraw.Draw(img)
    if night:
        d.ellipse((580, 40, 836, 296), fill="#eedcb2")
        for y in range(140, 430, 12):
            d.line((0, y, W, y + 35), fill="#356160", width=2)
        for layer, base, color in [(0, 340, "#62918b"), (1, 410, "#2c6b6b")]:
            ridge = [(x, base + rng.randint(-65, 45)) for x in range(-50, 1051, 55)]
            d.polygon(ridge + [(W, H), (0, H)], fill=color)
        d.polygon([(0,410),(1000,462),(1000,620),(0,620)],fill="#112e35")
        for x in (60,320,575,808):
            y = 320 + int(x*.06)
            d.polygon([(x,y),(x+9,y),(x+7,y+130),(x,y+130)],fill="#192f36")
            d.line((x-24,y+12,x+24,y+12),fill="#162f38",width=4)
            d.line((x,y+5,x+260,y+20),fill="#142e35",width=2)
        d.rounded_rectangle((205,365,757,428), radius=20, fill="#e8c86f")
        d.rectangle((213,407,750,418),fill="#ce7354")
        for x in range(225,738,34):
            d.rounded_rectangle((x,376,x+24,394),radius=3,fill="#284d53")
        for y in (447,462,495,550):
            d.line((0,y,1000,435+(y-435)//3),fill="#819b92",width=3)
    else:
        d.ellipse((87,60,330,303),fill="#f9e2a4")
        for i, color in enumerate(("#c17d67","#a65f54","#914e47")):
            pts = [(x, 298+i*60+55*math.sin(x/126+i*1.8)+26*math.sin(x/70+i)) for x in range(-10,1021,10)]
            d.polygon(pts+[(W,H),(0,H)],fill=color)
        d.polygon([(0,410),(175,352),(377,403),(565,385),(745,425),(1000,384),(1000,H),(0,H)],fill="#42686a")
        for _ in range(380):
            x,y=rng.randrange(W),rng.randrange(435,H)
            d.line((x,y,x+rng.randrange(5,49),y),fill=rng.choice(["#769691","#cfb996","#325958"]),width=2)
        for x,y,s in [(648,399,1),(750,420,.65),(557,430,.42)]:
            d.polygon([(x,y),(x,y-124*s),(x-58*s,y-4*s)],fill="#ffedc7")
            d.polygon([(x+4*s,y),(x+4*s,y-104*s),(x+47*s,y)],fill="#d58659")
            d.polygon([(x-66*s,y+4*s),(x+58*s,y+4*s),(x+38*s,y+16*s),(x-43*s,y+16*s)],fill="#413e37")
    pixels = np.asarray(img).astype(float)
    noise = np.random.default_rng(14).normal(0, 3.5, (H,W,1))
    Image.fromarray(np.uint8(np.clip(pixels+noise,0,255))).save(OUT / ("rail.jpg" if night else "sail.jpg"),quality=88)


RATE = 22050
RAIL = [(71,1),(74,.5),(76,.5),(78,1),(74,1),(73,1.5),(69,.5),(71,2),
        (67,1),(71,.5),(74,.5),(76,1.5),(74,.5),(73,1),(69,1),(66,2),
        (71,.5),(73,.5),(74,1),(78,1),(81,1),(79,1.5),(76,.5),(74,2),
        (76,1),(73,1),(69,1),(66,1),(71,4)]
SAIL = [(62,1),(65,.5),(69,.5),(72,1),(69,1),(67,1.5),(65,.5),(64,2),
        (65,1),(67,1),(70,1),(74,1),(72,2),(69,2),(67,.5),(69,.5),(70,1),
        (74,1),(77,1),(76,1.5),(72,.5),(69,2),(70,1),(67,1),(64,2),(65,4)]


def render(name, melody, chords, bpm, voice):
    beat = 60/bpm
    out = np.zeros(round((32*beat+1.1)*RATE))

    def note(pitch, start, length, volume, timbre):
        offset = round(start * beat * RATE)
        hold = length*beat
        t = np.arange(min(len(out)-offset, round((hold+.46)*RATE)))/RATE
        phase = 2*np.pi*440*2**((pitch-69)/12)*t
        partials = {"felt":(1,.32,.11),"bell":(1,.11,.42),"reed":(1,.04,.23),"bass":(1,.25,.07)}
        a,b,c=partials[timbre]
        sound = a*np.sin(phase)+b*np.sin(2*phase)+c*np.sin(3*phase)
        attack = .07 if timbre=="reed" else .012
        env = (1-np.exp(-t/attack))*np.exp(-t*(.6 if timbre=="reed" else 1.9))*np.exp(-np.maximum(t-hold,0)*11)
        out[offset:offset+len(t)] += volume*sound*env

    cursor=0
    for pitch, duration in melody:
        note(pitch+(12 if voice=="bell" else 0),cursor,duration*.91,.46,voice)
        cursor+=duration
    for bar,chord in enumerate(chords):
        note(chord[0]-12,bar*4,3.7,.25,"bass")
        for step,i in enumerate([0,1,2,1,0,2,1,2]):
            note(chord[i],bar*4+step*.5,.48,.13,"felt")
    delay = int(.19*RATE)
    out[delay:] += out[:-delay].copy()*.13
    out *= .85/max(abs(out))
    with wave.open(str(OUT/(name+".wav")),"wb") as f:
        f.setparams((1,2,RATE,0,"NONE",""))
        f.writeframes((out*32767).astype("<i2").tobytes())


if __name__ == "__main__":
    poster(True)
    poster(False)
    rail_chords=[(47,54,62),(45,52,61),(43,50,59),(42,49,57)]*2
    render("last-train",RAIL,rail_chords,92,"felt")
    render("last-train-bells",RAIL,rail_chords,92,"bell")
    render("ebb",SAIL,[(50,57,65),(48,55,64),(46,53,62),(48,55,64),
                       (46,53,62),(45,52,60),(48,55,64),(41,53,57)],86,"reed")
