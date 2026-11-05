#!/usr/bin/env python3
"""Writes the scaffolding QA structure .nbt (gzip, big-endian NBT) and prints its blocks as JSON on stdout.

Usage: python3 make-scaffold-nbt.py <output.nbt> [--data-version N]

Layout (size 5 x 14 x 5; cells not listed are structure void and leave the world alone):
  y=0:     a 5 x 5 stone floor
  y=1..2:  an oak door at x=2 z=0, a sunflower at x=4 z=0 and a rose bush at x=4 z=1, both halves each
  y=1:     a red bed facing south, foot at x=0 z=1 and head at x=0 z=2
  y=1..13: a stone pillar at x=4 z=4, whose top is out of reach from the ground
Positions are relative to the minimum corner; with `anchor=corner` cell (x, y, z) goes to origin + (x, y, z).
"""
import gzip
import json
import struct
import sys

TAG_END, TAG_INT, TAG_STRING, TAG_LIST, TAG_COMPOUND = 0, 3, 8, 9, 10
WIDTH, HEIGHT, LENGTH = 5, 14, 5
DEFAULT_DATA_VERSION = 5023


def name(text: str) -> bytes:
    data = text.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def int_tag(key: str, value: int) -> bytes:
    return bytes([TAG_INT]) + name(key) + struct.pack(">i", value)


def string_tag(key: str, value: str) -> bytes:
    return bytes([TAG_STRING]) + name(key) + name(value)


def int_list(key: str, values: list) -> bytes:
    body = bytes([TAG_INT]) + struct.pack(">i", len(values)) + b"".join(struct.pack(">i", v) for v in values)
    return bytes([TAG_LIST]) + name(key) + body


def compound(key: str, payload: bytes) -> bytes:
    return bytes([TAG_COMPOUND]) + name(key) + payload + bytes([TAG_END])


def compound_list(key: str, payloads: list) -> bytes:
    if not payloads:
        return bytes([TAG_LIST]) + name(key) + bytes([TAG_END]) + struct.pack(">i", 0)
    body = bytes([TAG_COMPOUND]) + struct.pack(">i", len(payloads))
    body += b"".join(p + bytes([TAG_END]) for p in payloads)
    return bytes([TAG_LIST]) + name(key) + body


def state(block: str, **properties) -> str:
    if not properties:
        return "minecraft:" + block
    return "minecraft:" + block + "[" + ",".join(f"{key}={value}" for key, value in sorted(properties.items())) + "]"


def layout() -> list:
    blocks = []
    for z in range(LENGTH):
        for x in range(WIDTH):
            blocks.append({"pos": [x, 0, z], "name": state("stone")})
    door = dict(facing="south", hinge="left", open="false", powered="false")
    blocks.append({"pos": [2, 1, 0], "name": state("oak_door", half="lower", **door)})
    blocks.append({"pos": [2, 2, 0], "name": state("oak_door", half="upper", **door)})
    blocks.append({"pos": [0, 1, 1], "name": state("red_bed", facing="south", occupied="false", part="foot")})
    blocks.append({"pos": [0, 1, 2], "name": state("red_bed", facing="south", occupied="false", part="head")})
    for z, plant in ((0, "sunflower"), (1, "rose_bush")):
        blocks.append({"pos": [4, 1, z], "name": state(plant, half="lower")})
        blocks.append({"pos": [4, 2, z], "name": state(plant, half="upper")})
    for y in range(1, HEIGHT):
        blocks.append({"pos": [4, y, 4], "name": state("stone")})
    return blocks


def palette_entry(text: str) -> bytes:
    if "[" not in text:
        return string_tag("Name", text)
    block, body = text[:-1].split("[", 1)
    properties = b"".join(string_tag(key, value) for key, value in (pair.split("=", 1) for pair in body.split(",")))
    return string_tag("Name", block) + compound("Properties", properties)


def structure(data_version: int, blocks: list) -> bytes:
    palette = []
    for block in blocks:
        if block["name"] not in palette:
            palette.append(block["name"])
    entries = [int_list("pos", b["pos"]) + int_tag("state", palette.index(b["name"])) for b in blocks]
    root = int_tag("DataVersion", data_version)
    root += int_list("size", [WIDTH, HEIGHT, LENGTH])
    root += compound_list("palette", [palette_entry(text) for text in palette])
    root += compound_list("blocks", entries)
    root += compound_list("entities", [])
    return bytes([TAG_COMPOUND]) + name("") + root + bytes([TAG_END])


def main(argv: list) -> int:
    if len(argv) not in (2, 4) or (len(argv) == 4 and argv[2] != "--data-version"):
        print("usage: python3 make-scaffold-nbt.py <output.nbt> [--data-version N]", file=sys.stderr)
        return 2
    data_version = int(argv[3]) if len(argv) == 4 else DEFAULT_DATA_VERSION
    blocks = layout()
    with open(argv[1], "wb") as handle:
        handle.write(gzip.compress(structure(data_version, blocks), mtime=0))
    print(json.dumps({"size": [WIDTH, HEIGHT, LENGTH], "dataVersion": data_version, "blocks": blocks}))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
