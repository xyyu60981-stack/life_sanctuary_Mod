"""Import the approved external star-moss atlas and native models; leave originals unchanged."""
import argparse
import copy
import json
import random
import shutil
from pathlib import Path


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def run(source):
    assets = Path(__file__).resolve().parents[1] / "src/main/resources/assets/life_sanctuary"
    texture = "life_sanctuary:block/star_moss_atlas"
    models = []
    for name in ["body", "receptacle_1", "receptacle_2", "receptacle_3"]:
        model = json.loads((source / ("xingtai_" + name + ".json")).read_text(encoding="utf-8"))
        model["textures"] = {"atlas": texture, "particle": "#atlas"}
        for element in model["elements"]:
            assert all(a <= b for a, b in zip(element["from"], element["to"]))
            for face in element["faces"].values():
                face.pop("cullface", None)
        models.append(model)
    assert models[0]["elements"][0]["from"] == [0, 0, 0]
    assert models[0]["elements"][0]["to"] == [16, 1, 16]
    (assets / "textures/block").mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source / "xingtai_atlas.png", assets / "textures/block/star_moss_atlas.png")
    write(assets / "models/block/star_moss/body.json", models[0])
    variants = {}
    centers = [(4, 4), (12, 4), (8, 8), (4, 12), (12, 12)]
    for count in range(2, 6):
        for reproductive in (False, True):
            key = f"receptacle_count={count},reproductive={str(reproductive).lower()}"
            variants[key] = {"model": "life_sanctuary:block/star_moss/body"}
            if not reproductive:
                continue
            weighted = []
            for variant in range(8):
                rng = random.Random(6000 + count * 100 + variant)
                model = copy.deepcopy(models[0])
                positions = centers.copy()
                rng.shuffle(positions)
                for index, (x, z) in enumerate(positions[:count]):
                    parts = copy.deepcopy(models[1 + rng.randrange(3)]["elements"])
                    dx, dz = x - 8 + rng.uniform(-0.4, 0.4), z - 8 + rng.uniform(-0.4, 0.4)
                    for element in parts:
                        element["name"] = f"receptacle_{index}/" + element.get("name", "part")
                        for field in ("from", "to"):
                            element[field][0] = round(element[field][0] + dx, 5)
                            element[field][2] = round(element[field][2] + dz, 5)
                        if "rotation" in element:
                            origin = element["rotation"]["origin"]
                            origin[0] += dx
                            origin[2] += dz
                    model["elements"].extend(parts)
                name = f"reproductive_{count}_{variant}"
                write(assets / "models/block/star_moss" / (name + ".json"), model)
                weighted.append({"model": "life_sanctuary:block/star_moss/" + name})
            variants[key] = weighted
    write(assets / "blockstates/marchantia_stellatus.json", {"variants": variants})
    write(assets / "models/item/marchantia_stellatus.json", {"parent": "life_sanctuary:block/star_moss/body"})
    print("Imported star moss: approved atlas, body, 32 reproductive variants and 8 block states.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path)
    run(parser.parse_args().source)
