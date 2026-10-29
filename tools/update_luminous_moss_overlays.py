"""Regenerate native side-overlay geometry; no image edits or gameplay state changes."""
import json
import struct
from pathlib import Path

ASSETS = Path(__file__).resolve().parents[1] / "src/main/resources/assets/life_sanctuary"
MODELS = ASSETS / "models/block"
atlas = (ASSETS / "textures/block/guangxian_atlas.png").read_bytes()
assert atlas[:8] == b"\x89PNG\r\n\x1a\n"
assert struct.unpack(">II", atlas[16:24]) == (204, 136), "Atlas changed: review the two overlay regions"
regions = [(70, 70, 134, 134), (138, 70, 202, 134)]
planes = {
    "north": ([0, -16, -0.01], [16, 0, -0.01]),
    "east": ([16.01, -16, 0], [16.01, 0, 16]),
    "south": ([0, -16, 16.01], [16, 0, 16.01]),
    "west": ([-0.01, -16, 0], [-0.01, 0, 16]),
}
for side, (start, end) in planes.items():
    for variant, (x1, y1, x2, y2) in enumerate(regions):
        uv = [x1 * 16 / 204, y1 * 16 / 136, x2 * 16 / 204, y2 * 16 / 136]
        model = {"ambientocclusion": False, "render_type": "minecraft:cutout",
                 "textures": {"atlas": "life_sanctuary:block/guangxian_atlas", "particle": "#atlas"},
                 "elements": [{"from": start, "to": end, "faces": {side: {"uv": uv, "texture": "#atlas"}}}]}
        (MODELS / f"luminous_moss_overlay_{side}_{variant}.json").write_text(json.dumps(model, indent=2) + "\n", encoding="utf-8")
for name in ("lichen_photobiont", "lichen_photobiont_sprouts", "lichen_photobiont_capsules"):
    path = MODELS / (name + ".json")
    model = json.loads(path.read_text(encoding="utf-8"))
    base = next(e for e in model["elements"] if e["from"] == [0, 0, 0] and e["to"] == [16, 1, 16])
    top = base["faces"]["up"]["uv"]
    for side in planes:
        if side in base["faces"]:
            base["faces"][side]["uv"] = [top[0], top[1], top[2], top[1] + 16 / 136]
    path.write_text(json.dumps(model, indent=2) + "\n", encoding="utf-8")
print("Updated eight lower-support overlays and three one-pixel carpet edges.")
