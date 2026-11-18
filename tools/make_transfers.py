"""Tabelle colore per l'anteprima dei profili in ViewerCraftStar: "foto della fotocamera nello stile A -> stile B".

Sul telefono si vede solo l'anteprima JPEG salvata dalla fotocamera (Android non sviluppa i raw della GR IV), che ha già
lo stile scelto allo scatto. Per mostrare la foto con un altro profilo serve passare da uno stile all'altro: per ogni
foto di riferimento si prende la resa neutra di LightCraft (x) e l'anteprima della fotocamera (y, stile A); la foto con
il profilo misurato B è cube_B(x). Da tutte le foto dello stile A si stima la tabella y -> cube_B(x) (stesso metodo di
tools/measure_styles.py di PresetCraftAppLicator) e la si salva come griglia 17x17x17.

Da uno stile in bianco e nero non si torna al colore: la tabella darà grigi (è il limite dell'anteprima, non di LightCraft).

Uso (Python con numpy e Pillow; PresetCraftAppLicator accanto a questo progetto):
    python tools/make_transfers.py CARTELLA_DNG [--work CARTELLA_TEMPORANEA]
Scrive app/src/main/assets/transfers.bin e transfers.json.
"""

import argparse
import json
import os
import re
import struct
import sys

import numpy as np

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PCA = os.path.join(os.path.dirname(HERE), "PresetCraftAppLicator")
sys.path.insert(0, os.path.join(PCA, "src"))
sys.path.insert(0, os.path.join(PCA, "tools"))
import camera_styles as cs  # noqa: E402
import measure_styles as ms  # noqa: E402

N = 17  # lato della griglia salvata nell'app
PROFILES = os.path.join(PCA, "assets", "profiles", "RICOH GR IV")


def read_cube(path):
    size, rows = 0, []
    for line in open(path, encoding="ascii"):
        p = line.split()
        if not p or p[0].startswith("#"):
            continue
        if p[0] == "LUT_3D_SIZE":
            size = int(p[1])
        elif re.match(r"^-?[0-9.]", p[0]) and len(p) == 3:
            rows.append([float(v) for v in p])
    # nel formato .cube il rosso cambia più in fretta: indice = (b*size + g)*size + r
    return np.array(rows).reshape(size, size, size, 3)  # [b][g][r]


def apply_cube(lut, x):
    """Interpolazione trilineare di una LUT [b][g][r] su colori Nx3 (0..1)."""
    n = lut.shape[0]
    p = np.clip(x, 0, 1) * (n - 1)
    i0 = np.minimum(np.floor(p).astype(int), n - 2)
    f = p - i0
    out = np.zeros_like(x)
    for dr in (0, 1):
        for dg in (0, 1):
            for db in (0, 1):
                w = ((f[:, 0] if dr else 1 - f[:, 0]) * (f[:, 1] if dg else 1 - f[:, 1]) *
                     (f[:, 2] if db else 1 - f[:, 2]))
                out += w[:, None] * lut[i0[:, 2] + db, i0[:, 1] + dg, i0[:, 0] + dr]
    return out


def slug(name):
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("folder")
    ap.add_argument("--work", default=os.path.join(os.environ.get("TEMP", "."), "vcs-transfers"))
    args = ap.parse_args()
    os.makedirs(args.work, exist_ok=True)
    meta = json.load(open(os.path.join(PROFILES, "profiles.json"), encoding="utf-8"))["styles"]
    cubes = {int(k.split(":")[1]): read_cube(os.path.join(PROFILES, v["file"])) for k, v in meta.items()}
    names = {int(k.split(":")[1]): v["style"] for k, v in meta.items()}

    # coppie (fotocamera, resa neutra) di ogni foto, raggruppate per stile di scatto
    by_style = {}
    files = sorted(f for f in os.listdir(args.folder) if f.lower().endswith(".dng"))
    for i, name in enumerate(files, 1):
        path = os.path.join(args.folder, name)
        st = cs.read_style(path)
        if st["code"] not in cubes:
            continue
        data = ms.pair(path, args.work, ms.CLI)
        if data is None:
            continue
        x, y = data
        keep = np.random.default_rng(i).choice(len(x), min(len(x), 150000), replace=False)
        by_style.setdefault(st["code"], []).append((x[keep], y[keep]))
        print(f"{i}/{len(files)} {name}: {names[st['code']]}", flush=True)

    lap = ms.laplacian()
    g = np.linspace(0, 1, N)
    rr, gg, bb = np.meshgrid(g, g, g, indexing="ij")  # [r][g][b]
    grid = np.stack([rr, gg, bb], -1).reshape(-1, 3)
    tables = []
    for src, pairs in sorted(by_style.items()):
        x = np.concatenate([p[0] for p in pairs])
        y = np.concatenate([p[1] for p in pairs])
        for dst in sorted(cubes):
            if dst == src:
                continue
            z = apply_cube(cubes[dst], x)
            xs, ys, cnt = ms.bin_samples(y, z)
            model = ms.fit(xs, ys, cnt, lap)
            out = np.clip(model(grid), 0, 1)
            tables.append((src, dst, np.round(out * 255).astype(np.uint8)))
            print(f"  {names[src]} -> {names[dst]} ({len(pairs)} foto)", flush=True)

    assets = os.path.join(HERE, "app", "src", "main", "assets")
    os.makedirs(assets, exist_ok=True)
    with open(os.path.join(assets, "transfers.bin"), "wb") as f:
        f.write(b"VCST" + struct.pack("<HHH", 1, N, len(tables)))
        for src, dst, data in tables:
            f.write(struct.pack("<HH", src, dst) + data.tobytes())  # ordine [r][g][b], RGB
    info = {"grid": N, "camera": "RICOH GR IV",
            "styles": {str(c): {"name": names[c], "slug": slug(names[c]), "photos": len(by_style.get(c, []))}
                       for c in sorted(names)}}
    json.dump(info, open(os.path.join(assets, "transfers.json"), "w", encoding="utf-8"), indent=1, ensure_ascii=False)
    print(f"{len(tables)} tabelle scritte in {assets}")


if __name__ == "__main__":
    main()
