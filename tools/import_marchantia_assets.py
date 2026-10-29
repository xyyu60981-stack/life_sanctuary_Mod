"""Import user-supplied liverwort assets without modifying their source directory.

Usage: python tools/import_marchantia_assets.py --source <model-directory>
Requires Pillow. Runtime assets use vanilla baked models, never a custom renderer.
"""
import argparse
import copy
import json
import random
import shutil
from pathlib import Path

from PIL import Image


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def periodic_resize(image, size):
    """Resize the central tile with wrapped neighbors instead of clamped image edges."""
    image = image.convert("RGBA")
    tiled = Image.new("RGBA", (image.width * 3, image.height * 3))
    for y in range(3):
        for x in range(3):
            tiled.paste(image, (x * image.width, y * image.height))
    scaled = tiled.resize((size * 3, size * 3), Image.Resampling.LANCZOS)
    return scaled.crop((size, size, size * 2, size * 2))


def ground_model(source, texture):
    model = json.loads(source.read_text(encoding="utf-8"))
    if len(model["elements"]) != 1:
        raise ValueError("Ground must remain one thin cuboid")
    cube = model["elements"][0]
    if cube["from"] != [0, 0, 0] or cube["to"] != [16, 1, 16]:
        raise ValueError("Ground must remain exactly 16 x 1 x 16")
    # Explicit full-face Java UVs also normalize the soil model's 32-unit editor grid.
    row = 16 / 64
    faces = {
        "up": {"uv": [0, 0, 16, 16], "texture": "#surface"},
        "down": {"uv": [0, 0, 16, 16], "texture": "#surface", "cullface": "down"},
        "north": {"uv": [0, 0, 16, row], "texture": "#surface"},
        "south": {"uv": [0, 16 - row, 16, 16], "texture": "#surface"},
        "west": {"uv": [0, 0, row, 16], "rotation": 90, "texture": "#surface"},
        "east": {"uv": [16 - row, 0, 16, 16], "rotation": 90, "texture": "#surface"},
    }
    return {"ambientocclusion": False, "render_type": "minecraft:cutout_mipped",
            "textures": {"surface": texture, "particle": "#surface"},
            "elements": [{"from": [0, 0, 0], "to": [16, 1, 16], "faces": faces}]}


def translated_elements(model, center, rng, prefix):
    # Offsets are pre-baked. Runtime weighted choice is stable at a block position.
    dx = center[0] - 8 + rng.uniform(-0.6, 0.6)
    dz = center[1] - 8 + rng.uniform(-0.6, 0.6)
    elements = copy.deepcopy(model["elements"])
    for element in elements:
        element["name"] = prefix + "/" + element.get("name", "part")
        for field in ("from", "to"):
            element[field][0] = round(element[field][0] + dx, 5)
            element[field][2] = round(element[field][2] + dz, 5)
        if "rotation" in element:
            origin = element["rotation"]["origin"]
            origin[0] = round(origin[0] + dx, 5)
            origin[2] = round(origin[2] + dz, 5)
        for face in element["faces"].values():
            face.pop("cullface", None)
    return elements


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    args = parser.parse_args()
    source = args.source.resolve()
    root = Path(__file__).resolve().parent.parent
    assets = root / "src/main/resources/assets/life_sanctuary"
    models = assets / "models/block/marchantia"
    textures = assets / "textures/block"
    textures.mkdir(parents=True, exist_ok=True)
    with Image.open(source / "地钱1_无缝.png") as image:
        if image.size != (64, 64):
            raise ValueError("Approved soil texture must be 64 x 64")
    with Image.open(source / "marchantia_receptacle.png") as image:
        if image.size != (16, 16):
            raise ValueError("Receptacle palette must be 16 x 16")
    shutil.copyfile(source / "地钱1_无缝.png", textures / "marchantia_soil.png")
    with Image.open(source / "其他贴图/地钱2_无缝.png") as image:
        periodic_resize(image, 64).save(textures / "marchantia_stone.png")
    shutil.copyfile(source / "marchantia_receptacle.png", textures / "marchantia_receptacle.png")
    for kind, filename in (("soil", "地钱1.bbmodel"), ("stone", "地钱2.bbmodel")):
        write_json(models / ("base_" + kind + ".json"),
                   ground_model(source / filename, "life_sanctuary:block/marchantia_" + kind))
    female = json.loads((source / "地钱_雌生殖托.json").read_text(encoding="utf-8"))
    male = json.loads((source / "地钱_雄生殖托.json").read_text(encoding="utf-8"))
    # These slot centers keep all heads inside the tile and separated after jitter.
    male_centers = ((4, 4), (12, 4), (8, 8))
    female_centers = ((4, 12), (12, 12))
    for count in (1, 2):
        for variant in range(4):
            rng = random.Random(7400 + count * 10 + variant)
            elements = []
            for index, center in enumerate(male_centers):
                elements.extend(translated_elements(male, center, rng, "male_" + str(index)))
            for index, center in enumerate(female_centers[:count]):
                elements.extend(translated_elements(female, center, rng, "female_" + str(index)))
            model = {"ambientocclusion": False, "render_type": "minecraft:cutout_mipped",
                     "textures": {"atlas": "life_sanctuary:block/marchantia_receptacle", "particle": "#atlas"},
                     "elements": elements}
            write_json(models / f"receptacles_f{count}_v{variant}.json", model)
    # Species/block ID confirmed by the user; keep active binding and handoff example identical.
    multipart = [{"when": {"surface": kind}, "apply": {"model": "life_sanctuary:block/marchantia/base_" + kind}}
                 for kind in ("soil", "stone")]
    for count in (1, 2):
        multipart.append({"when": {"reproductive": "true", "female_count": str(count)},
                          "apply": [{"model": f"life_sanctuary:block/marchantia/receptacles_f{count}_v{variant}", "weight": 1}
                                    for variant in range(4)]})
    write_json(root / "docs/marchantia-blockstate.example.json", {"multipart": multipart})
    write_json(assets / "blockstates/marchantia_polymorpha.json", {"multipart": multipart})
    print("Imported two thin-carpet models, eight reproductive arrangements and three textures.")


if __name__ == "__main__":
    main()
