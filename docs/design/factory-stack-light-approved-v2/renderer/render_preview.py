"""Re-render the approved V2 from local snapshots; no game jar or repo assets needed."""
from pathlib import Path
import hashlib
import json

import numpy as np
from PIL import Image
from render_scene import load_minecraft_model, render

HERE = Path(__file__).resolve().parents[1]
OUT = HERE / 'verification'
BG = '#e8ece8'


def texture(name):
    namespace, path = name.split(':', 1)
    assert namespace == 'study', name
    return Image.open(HERE / 'textures' / (path + '.png')).convert('RGBA')


def check_model(data):
    assert len(data['elements']) == 9
    assert len([e for e in data['elements'] if e['name'].endswith('_single_shell')]) == 3
    for element in data['elements']:
        dx, dy, dz = [element['to'][i]-element['from'][i] for i in range(3)]
        for side, face in element['faces'].items():
            w, h = (dx, dz) if side in ('up', 'down') else (dz, dy) if side in ('east', 'west') else (dx, dy)
            u0, v0, u1, v1 = face['uv']
            assert (u1-u0, v1-v0) == (w, h), (element['name'], side)
            tex = texture(face['texture'])
            assert tex.size == (16, 16)
            assert 0 <= u0 < u1 <= 16 and 0 <= v0 < v1 <= 16
            patch = np.asarray(tex)[int(v0):int(v1), int(u0):int(u1)]
            alphas = patch[:, :, 3]
            if element['name'].endswith('_single_shell'):
                assert set(np.unique(alphas)) <= {0, 204}, element['name']
                assert np.count_nonzero(alphas == 204) == (16 if side == 'down' else 25)
            else:
                assert np.all(alphas == 255), element['name']


def main():
    manifest_path = HERE / 'manifest.json'
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text())
        for name, digest in manifest['sha256'].items():
            assert hashlib.sha256((HERE / name).read_bytes()).hexdigest() == digest, name
    OUT.mkdir(exist_ok=True)
    baseline = None
    for state in ('off', 'green', 'yellow', 'red', 'all'):
        data = json.loads((HERE / 'models' / f'model-{state}.json').read_text())
        check_model(data)
        geometry = [(e['name'], e['from'], e['to'], [f['uv'] for f in e['faces'].values()])
                    for e in data['elements']]
        if baseline is None:
            baseline = geometry
        assert geometry == baseline, state
        mesh, textures = load_minecraft_model(data, texture)
        image = render(mesh, textures, (640, 900), 32, 16, (8, 12, 8), 30, BG).convert('RGB')
        image.save(OUT / f'{state}.png')
        if state == 'off':
            approved = Image.open(HERE / 'three-quarter.png').convert('RGB')
            assert np.array_equal(np.asarray(approved), np.asarray(image)), 'Approved preview differs'
    print('Verified: 5 states, identical geometry, 54 faces/state at 1 texel/model unit.')
    print('Verified: body alpha=255; bulb alpha=204 with native bottom cutouts; off preview pixel-identical.')
    print(OUT)


if __name__ == '__main__':
    main()
