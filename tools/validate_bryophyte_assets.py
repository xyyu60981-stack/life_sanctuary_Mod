"""Check native moss/liverwort resource links without starting Minecraft or Gradle."""
import itertools
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/assets"
MOD = "life_sanctuary"
visited = set()


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def resource(name, kind, suffix):
    namespace, name = name.split(":", 1) if ":" in name else ("minecraft", name)
    return ROOT / namespace / kind / (name + suffix)


def model(name):
    if not name.startswith(MOD + ":") or name in visited:
        return
    visited.add(name)
    path = resource(name, "models", ".json")
    assert path.is_file(), f"Missing model: {path}"
    data = read(path)
    if "parent" in data:
        model(data["parent"])
    textures = data.get("textures", {})
    for value in textures.values():
        if value.startswith(MOD + ":"):
            assert resource(value, "textures", ".png").is_file(), f"Missing texture: {value}"
    for element in data.get("elements", []):
        for face in element.get("faces", {}).values():
            value = face["texture"]
            seen = set()
            while value.startswith("#"):
                key = value[1:]
                assert key not in seen, f"Cyclic texture reference: {path}"
                seen.add(key)
                assert key in textures, f"Undefined texture {value}: {path}"
                value = textures[key]


def applications(value):
    for entry in value if isinstance(value, list) else [value]:
        model(entry["model"])
        assert entry.get("weight", 1) > 0
        assert entry.get("y", 0) in (0, 90, 180, 270)


for block in ("attached_moss", "marchantia_polymorpha", "lichen_photobiont", "marchantia_stellatus"):
    data = read(ROOT / MOD / "blockstates" / (block + ".json"))
    if "variants" in data:
        for value in data["variants"].values():
            applications(value)
    else:
        for entry in data["multipart"]:
            applications(entry["apply"])
    model(MOD + ":item/" + block)

moss = read(ROOT / MOD / "blockstates/attached_moss.json")["multipart"]
assert len(moss) == 4
assert {tuple(entry["when"].items()) for entry in moss} == {
    ((direction, "true"),) for direction in ("north", "east", "south", "west")
}
for bits in itertools.product((False, True), repeat=4):
    state = dict(zip(("north", "east", "south", "west"), (str(bit).lower() for bit in bits)))
    selected = [entry for entry in moss if all(state[key] == value for key, value in entry["when"].items())]
    assert len(selected) == sum(bits), "Multipart models must match exactly the attached faces"
for entry in moss:
    assert len(entry["apply"]) == 4, "Each attachment needs four native variants"
    for application in entry["apply"]:
        data = read(resource(application["model"], "models", ".json"))
        assert len(data["elements"]) == 1
        element = data["elements"][0]
        assert element["from"][2] == element["to"][2], "Moss must remain a plane"
        assert set(element["faces"]) == {"north", "south"}, "Plane needs both sides"
        assert all("cullface" not in face for face in element["faces"].values())

climbable = read(ROOT.parent / "data/minecraft/tags/block/climbable.json")
assert not climbable.get("replace", False)
assert MOD + ":attached_moss" in climbable["values"]
for side in ("north", "east", "south", "west"):
    for variant in range(2):
        name = MOD + f":block/luminous_moss_overlay_{side}_{variant}"
        model(name)
        element = read(resource(name, "models", ".json"))["elements"][0]
        assert element["from"][1] == -16 and element["to"][1] == 0
        assert set(element["faces"]) == {side}
        assert "cullface" not in element["faces"][side], "Culling uses the lower neighbor via ModelData"
        u1, v1, u2, v2 = element["faces"][side]["uv"]
        assert abs((u2 - u1) * 204 / 16 - 64) < 0.00001
        assert abs((v2 - v1) * 136 / 16 - 64) < 0.00001
star = read(ROOT / MOD / "blockstates/marchantia_stellatus.json")["variants"]
assert len(star) == 8
for count in range(2, 6):
    plain = star[f"receptacle_count={count},reproductive=false"]
    assert plain["model"] == MOD + ":block/star_moss/body"
    variants = star[f"receptacle_count={count},reproductive=true"]
    assert len(variants) == 8
    for entry in variants:
        elements = read(resource(entry["model"], "models", ".json"))["elements"]
        assert len(elements) == 1 + count * 2, "One carpet and exactly two elements per receptacle"
        for element in elements:
            assert all(0 <= a <= b <= 16 for a, b in zip(element["from"], element["to"]))
            assert element["to"][1] <= 1.38
print(f"Validated four bryophyte resource trees ({len(visited)} models), lower-support overlays, star-moss counts and climbing tag.")
