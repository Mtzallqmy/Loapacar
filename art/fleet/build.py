"""Build the editable station-loft bodyshells. Pass --obj DIR for Blender interchange."""
import argparse
import csv
import math
import struct
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE.parents[1] / 'app/src/main/assets/fleet'

def rings(p, count):
    for step in range(count + 1):
        z = step / count - .5
        tip = max(0, (abs(z) - .25) * 4) ** 1.8
        end = 'nose' if z > 0 else 'tail'
        w = .5 * (1 - tip * (1 - p[end + '_width']))
        crown = .5 - tip * (1 - p[end + '_height'])
        offset = (abs(z) - p['arch_center']) / p['arch_radius']
        arch = math.sqrt(max(0, 1 - offset * offset)) * .63
        shoulder = p['shoulder']
        section = [(0,crown),(.85*w,crown-.025),(w,crown-shoulder),(w*.995,-.49+arch),
                   (w*.77,-.5+arch),(-w*.77,-.5+arch),(-w*.995,-.49+arch),
                   (-w,crown-shoulder),(-.85*w,crown-.025)]
        yield [(x, y, z) for x, y in section]

def triangles(p, steps):
    sections = list(rings(p, steps))
    for front, back in zip(sections, sections[1:]):
        for i in range(len(front)):
            j = (i + 1) % len(front)
            yield front[i], back[j], front[j]
            yield front[i], back[i], back[j]
    for ring, reverse in [(sections[0],False),(sections[-1],True)]:
        center = (0, -.08, ring[0][2])
        for i,a in enumerate(ring):
            b = ring[(i+1) % len(ring)]
            yield (center,b,a) if reverse else (center,a,b)

def encode(tris):
    buf = bytearray()
    for a,b,c in tris:
        u = [j-i for i,j in zip(a,b)]
        v = [j-i for i,j in zip(a,c)]
        normal = [u[(i+1)%3]*v[(i+2)%3]-u[(i+2)%3]*v[(i+1)%3] for i in range(3)]
        length = math.sqrt(sum(x*x for x in normal))
        if length < 1e-10: continue
        for x,y,z in (a,b,c):
            buf.extend(struct.pack('<8f',x,y,z,*(n/length for n in normal),z+.5,y+.5))
    return buf

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--obj', type=Path)
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    with (HERE/'shapes.csv').open() as table:
        for row in csv.DictReader(table):
            name = row.pop('id')
            params = {k:float(v) for k,v in row.items()}
            for lod,steps in [('high',40),('low',14)]:
                faces = list(triangles(params,steps))
                (OUT/f'{name}_{lod}.mesh').write_bytes(encode(faces))
                if args.obj:
                    args.obj.mkdir(parents=True,exist_ok=True)
                    with (args.obj/f'{name}_{lod}.obj').open('w') as obj:
                        for face in faces:
                            for vert in face: print('v', *vert, file=obj)
                            print('f -3 -2 -1', file=obj)
if __name__ == '__main__':
    main()
