"""Bake repeatable, tileable road albedo maps. Run with NumPy, Pillow and SciPy."""
from pathlib import Path

import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter

dest = Path(__file__).resolve().parents[2] / "app/src/main/assets/materials"
dest.mkdir(parents=True, exist_ok=True)
rng = np.random.default_rng(32731)
n = 768
y, x = np.mgrid[:n,:n] / n
tau = 2 * np.pi

def noise(scale, stretch=1):
    a = gaussian_filter(rng.standard_normal((n,n)), (scale*stretch,scale), mode="wrap")
    return (a-a.mean()) / max(.00001,a.std())

def save(name, palette, grain):
    lo, hi = np.array(palette)
    t = np.clip(grain*.18+.52, 0, 1)[...,None]
    image = np.uint8(lo + t*(hi-lo))
    Image.fromarray(image).save(dest/f"route_{name}.jpg",quality=88)

broad = noise(38)
grit = noise(.45)
ruts = np.cos(tau*(x*4 + .042*np.sin(tau*y)+.018*np.sin(2*tau*y)))
save("dirt", [[77,48,32],[199,162,111]], .65*broad+.65*noise(3)+.38*grit-.66*ruts**8)
save("sand", [[158,114,66],[242,210,147]], .52*broad+.40*grit+.78*np.cos(tau*(12*y+2*x+1.2*np.sin(tau*x)) + .33*noise(26)))
pebbles = noise(1.6)
save("gravel",[[58,54,50],[191,179,156]], .75*pebbles+1.2*(pebbles-np.roll(pebbles,2,axis=0))+.53*grit+.30*broad)
save("mud",[[47,39,34],[140,108,79]], .72*noise(10,2)+.26*grit+1.16*ruts**6+.31*broad)
strata = np.sin(tau*(5*x-8*y)+1.9*noise(35))
fracture = noise(11)
edge = np.abs(fracture) < .11
save("rock",[[72,65,62],[192,175,149]], .64*strata+.69*noise(4)+.43*grit-edge*1.5)
