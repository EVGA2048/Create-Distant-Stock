#!/usr/bin/env python3
"""Fail the build on broken local block models, textures, or lamp variants."""

from __future__ import annotations

import json
import re
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/distantstock"
MODELS = ASSETS / "models"
TEXTURES = ASSETS / "textures"
BLOCKSTATES = ASSETS / "blockstates"
ALLOWED_ELEMENT_ANGLES = {-45, -22.5, 0, 22.5, 45}

errors: list[str] = []


def load_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        errors.append(f"invalid JSON: {path.relative_to(ROOT)}: {exc}")
        return {}


def local_path(reference: str, kind: str) -> Path | None:
    if not reference.startswith("distantstock:"):
        return None
    value = reference.split(":", 1)[1]
    base = MODELS if kind == "model" else TEXTURES
    suffix = ".json" if kind == "model" else ".png"
    return base / f"{value}{suffix}"


def require_reference(owner: Path, reference: str, kind: str) -> None:
    path = local_path(reference, kind)
    if path is not None and not path.is_file():
        errors.append(
            f"missing {kind}: {owner.relative_to(ROOT)} -> {reference}"
        )


def collect_model_references(value: object) -> list[str]:
    found: list[str] = []
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "model" and isinstance(child, str):
                found.append(child)
            else:
                found.extend(collect_model_references(child))
    elif isinstance(value, list):
        for child in value:
            found.extend(collect_model_references(child))
    return found


def merged_textures(path: Path, seen: set[Path] | None = None) -> dict[str, str]:
    seen = set() if seen is None else seen
    if path in seen:
        errors.append(f"model parent cycle: {path.relative_to(ROOT)}")
        return {}
    seen.add(path)
    data = load_json(path)
    textures: dict[str, str] = {}
    parent = data.get("parent")
    parent_path = local_path(parent, "model") if isinstance(parent, str) else None
    if parent_path is not None and parent_path.is_file():
        textures.update(merged_textures(parent_path, seen))
    textures.update({k: v for k, v in data.get("textures", {}).items() if isinstance(v, str)})
    return textures


def png_dimensions(path: Path) -> tuple[int, int] | None:
    try:
        header = path.read_bytes()[:24]
        if header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
            raise ValueError("invalid PNG header")
        return struct.unpack(">II", header[16:24])
    except Exception as exc:
        errors.append(f"invalid PNG: {path.relative_to(ROOT)}: {exc}")
        return None


for path in sorted(BLOCKSTATES.glob("*.json")):
    data = load_json(path)
    for reference in collect_model_references(data):
        require_reference(path, reference, "model")

for path in sorted(MODELS.rglob("*.json")):
    data = load_json(path)
    parent = data.get("parent")
    if isinstance(parent, str):
        require_reference(path, parent, "model")
    textures = merged_textures(path)
    for reference in textures.values():
        if not reference.startswith("#"):
            require_reference(path, reference, "texture")
    for element in data.get("elements", []):
        if not isinstance(element, dict):
            continue
        rotation = element.get("rotation")
        if isinstance(rotation, dict) and rotation.get("angle") not in ALLOWED_ELEMENT_ANGLES:
            errors.append(
                f"invalid element rotation: {path.relative_to(ROOT)} -> {rotation.get('angle')}"
            )
        for face in element.get("faces", {}).values():
            if not isinstance(face, dict):
                continue
            reference = face.get("texture")
            if isinstance(reference, str) and reference.startswith("#"):
                key = reference[1:]
                if key not in textures:
                    errors.append(
                        f"missing texture variable: {path.relative_to(ROOT)} -> {reference}"
                    )

# 远仓机壳的观察窗：**powered=true 的每一个变体都必须指向一个声明了 translucent 的模型**。
#
# 这条是踩出来的，不是想出来的。模型进哪个渲染层只由它自己的 `render_type` 决定，而**实体层不混合
# alpha** —— 观察窗那张 CT 图集的窗芯是 alpha 34 的玻璃，画在实体层里就是一块不透明的淡蓝色板子。
# 四个 port 模型当时就是漏了这一个字段：不带接口的机壳通上红石会变透明，带接口的不会，而两者的几何
# 和贴图完全一样 —— 玩家看到的就是"只有带接口的那个不透明"。alpha 来回调了三轮都没修好，因为调的
# 根本不是那个东西。
#
# 只管机壳这一个方块。别的方块上 `powered` 是"有红石信号"，那是另一件事，它们的贴图本来就不透明
# （打包机的红石灯、红石请求器的灯芯），拿这条去要求它们只会逼出一堆没用的 translucent。
CASING_STATE = BLOCKSTATES / "tower_casing.json"
if CASING_STATE.is_file():
    for key, variant in load_json(CASING_STATE).get("variants", {}).items():
        if "powered=true" not in key.replace(" ", ""):
            continue
        for entry in (variant if isinstance(variant, list) else [variant]):
            reference = entry.get("model") if isinstance(entry, dict) else None
            if not isinstance(reference, str):
                continue
            model_path = local_path(reference, "model")
            if model_path is None or not model_path.is_file():
                continue
            declared = load_json(model_path).get("render_type")
            if declared != "minecraft:translucent":
                errors.append(
                    f"powered variant is not in the translucent layer: "
                    f"{CASING_STATE.relative_to(ROOT)} [{key}] -> {model_path.relative_to(ROOT)} "
                    f"(render_type={declared!r})"
                )

lamp_names = ["cyan", "orange", "red", "green", "white", "brass"]
faces = ["floor", "ceiling", "wall"]
facings = ["north", "east", "south", "west"]
for color in lamp_names:
    name = f"{color}_indicator_lamp"
    state_path = BLOCKSTATES / f"{name}.json"
    if not state_path.is_file():
        errors.append(f"missing lamp blockstate: {state_path.relative_to(ROOT)}")
        continue
    variants = load_json(state_path).get("variants", {})
    expected = {
        f"face={face},facing={facing},lit={str(lit).lower()}"
        for face in faces for facing in facings for lit in (False, True)
    }
    missing = sorted(expected - set(variants))
    if missing:
        errors.append(f"missing variants: {state_path.relative_to(ROOT)} -> {', '.join(missing)}")
    for suffix in ("", "_lit"):
        model = MODELS / "block" / f"{name}{suffix}.json"
        if not model.is_file():
            errors.append(f"missing lamp model: {model.relative_to(ROOT)}")

for material in ("andesite", "brass"):
    for color in ("cyan", "orange", "red", "green", "white"):
        for state in ("off", "on"):
            path = MODELS / "block/signal_panel" / f"{material}_{color}_{state}.json"
            if not path.is_file():
                errors.append(f"missing panel partial: {path.relative_to(ROOT)}")

for path in sorted(TEXTURES.rglob("*.png")):
    dimensions = png_dimensions(path)
    if dimensions and path.parent.name == "block" and "lamp" in path.name:
        if dimensions != (16, 16):
            errors.append(f"lamp texture must be 16x16: {path.relative_to(ROOT)} -> {dimensions}")

# Any source file may register blocks; the fluid blocks live with their fluids, for instance.
registered = set()
for source in (ROOT / "src/main/java").rglob("*.java"):
    text = source.read_text(encoding="utf-8")
    registered.update(re.findall(r'BLOCKS\.register\("([a-z0-9_]+)"', text))
    registered.update(re.findall(r'lamp\("([a-z0-9_]+)"\)', text))
for name in sorted(registered):
    path = BLOCKSTATES / f"{name}.json"
    if not path.is_file():
        errors.append(f"registered block has no blockstate: {name}")

if errors:
    print("Client asset verification FAILED:")
    for error in errors:
        print(f"  - {error}")
    raise SystemExit(1)

print(f"Client asset verification PASSED ({len(registered)} blocks, {len(list(MODELS.rglob('*.json')))} models)")
