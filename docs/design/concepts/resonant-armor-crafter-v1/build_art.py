#!/usr/bin/env python3
"""Draw the four suit recipes as they are actually laid out on a mechanical crafter.

The patterns are 7 wide and 5 tall, which is bigger than a crafting table and bigger than anything
Create ships, so a picture is the only honest way to show them: the player has to place a 35-block
array and match it cell for cell. Everything here is read out of the shipped recipe JSON rather than
transcribed, so the sheet cannot drift away from what the game will accept -- and that includes the
grid's own dimensions and the panel stride, both of which are derived rather than typed in. They were
typed in once, and when the patterns went from 5 wide to 7 the panels were drawn on top of each
other while the canvas around them grew correctly.

Item icons come from three places: our own resources, Create's jar, and the vanilla client jar.
"""
from __future__ import annotations

import json
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = next(a for a in HERE.parents if (a / "src/main/resources").exists())
RECIPES = ROOT / "src/main/resources/data/distantstock/recipe"
CREATE = Path.home() / ("Documents/minecraft_launcher/.minecraft/versions/"
                        "ES2_Firmament_1.21.1_9th_9.3.2_SunlightSignal/mods/create-1.21.1-6.0.10.jar")
VANILLA = ROOT / "build/moddev/artifacts/neoforge-21.1.231-client-extra-aka-minecraft-resources.jar"

FONT = "/System/Library/Fonts/STHeiti Medium.ttc"
BG = "#d8d8d8"          # page
CELL = 46              # one crafter block on the sheet
PAD = 26               # page margin
GAP = 56               # between the four panels, so they never read as one grid
TITLE_H = 30

PIECES = [("helmet", "头盔"), ("chestplate", "胸甲"), ("leggings", "护腿"), ("boots", "靴子")]

ARCHIVES: dict[str, zipfile.ZipFile] = {}


def sprite(namespace: str, path: str) -> Image.Image:
    """`path` is relative to textures/ and carries no extension."""
    if namespace == "distantstock":
        return Image.open(ROOT / f"src/main/resources/assets/distantstock/textures/{path}.png")
    if namespace not in ARCHIVES:
        ARCHIVES[namespace] = zipfile.ZipFile({"create": CREATE, "minecraft": VANILLA}[namespace])
    return Image.open(ARCHIVES[namespace].open(f"assets/{namespace}/textures/{path}.png"))


def model(namespace: str, path: str) -> dict | None:
    if namespace == "distantstock":
        file = ROOT / f"src/main/resources/assets/distantstock/models/{path}.json"
        return json.loads(file.read_text(encoding="utf-8")) if file.exists() else None
    if namespace not in ARCHIVES:
        ARCHIVES[namespace] = zipfile.ZipFile({"create": CREATE, "minecraft": VANILLA}[namespace])
    name = f"assets/{namespace}/models/{path}.json"
    archive = ARCHIVES[namespace]
    return json.loads(archive.read(name)) if name in archive.namelist() else None


def icon(item_id: str) -> Image.Image:
    """Resolve an item to its sprite, following the model when the name is not the texture name.

    Several of the parts are block items -- tower casing, fluid tank -- whose sprite lives under
    block/ and is named after the model rather than the item.
    """
    namespace, path = item_id.split(":", 1)
    for folder in ("item", "block"):
        try:
            return sprite(namespace, f"{folder}/{path}")
        except (FileNotFoundError, KeyError):
            pass
    # Block items often inherit their sprite from a parent block model rather than naming one.
    data = model(namespace, f"item/{path}") or {}
    for _ in range(6):
        textures = data.get("textures") or {}
        reference = textures.get("layer0") or textures.get("all")
        if reference:
            ref_ns, ref_path = reference.split(":", 1)
            return sprite(ref_ns, ref_path)
        parent = data.get("parent")
        if not parent or parent.startswith("minecraft:item/"):
            break
        parent_ns, parent_path = parent.split(":", 1) if ":" in parent else ("minecraft", parent)
        data = model(parent_ns, parent_path) or {}
    raise FileNotFoundError(item_id)


def main() -> None:
    grids = []
    for piece, label in PIECES:
        recipe = json.loads((RECIPES / f"ether_casing_{piece}.json").read_text(encoding="utf-8"))
        grids.append((label, recipe))

    # Read the shape off the recipes rather than assuming it. Every dimension below is derived from
    # it, including the panel stride -- hard-coding that one is how the panels ended up drawn on top
    # of each other when these went from 5 wide to 7.
    columns = max(len(r) for _, recipe in grids for r in recipe["pattern"])
    rows = max(len(recipe["pattern"]) for _, recipe in grids)

    panel_w, panel_h = columns * CELL, rows * CELL
    width = PAD * 2 + len(grids) * panel_w + (len(grids) - 1) * GAP
    height = PAD * 2 + TITLE_H + panel_h
    sheet = Image.new("RGBA", (width, height), BG)
    draw = ImageDraw.Draw(sheet)
    title = ImageFont.truetype(FONT, 21)

    for index, (label, recipe) in enumerate(grids):
        left = PAD + index * (panel_w + GAP)
        top = PAD + TITLE_H
        # A panel behind each grid, so where one ends and the next begins is never in doubt.
        draw.rectangle((left - 9, PAD - 6, left + panel_w + 9, top + panel_h + 9),
                       fill="#b8b8b8", outline="#8f8f8f")
        draw.text((left, PAD - 4), label, font=title, fill="#1a1a1a")
        for row, line in enumerate(recipe["pattern"]):
            for col, symbol in enumerate(line):
                if symbol == " ":
                    continue
                x = left + col * CELL
                y = top + row * CELL
                draw.rectangle((x, y, x + CELL - 3, y + CELL - 3), fill="#8b8b8b", outline="#373737")
                tile = icon(recipe["key"][symbol]["item"]).convert("RGBA")
                tile = tile.resize((32, 32), Image.Resampling.NEAREST)
                sheet.alpha_composite(tile, (x + 6, y + 6))

    sheet.save(HERE / "crafter_layout.png")
    print(HERE / "crafter_layout.png")


if __name__ == "__main__":
    main()
