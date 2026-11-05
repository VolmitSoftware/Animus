#!/usr/bin/env python3
"""Writes a tiny vanilla structure .nbt (gzip, big-endian NBT) that Animus master's
SchematicReader.structure() accepts, and prints the expected blocks as JSON on stdout.

Usage: python3 make-nbt.py <output.nbt> [--data-version N]

Layout (size 3 x 2 x 3, every cell filled, no air, no structure void):
  y=0: nine minecraft:stone
  y=1: minecraft:oak_planks on the four corners and the centre,
       minecraft:glass on the four edge middles
Positions are relative to the structure's minimum corner; with
`anchor=corner` the build places cell (x, y, z) at origin + (x, y, z).
"""
import gzip
import json
import struct
import sys

TAG_END, TAG_INT, TAG_STRING, TAG_LIST, TAG_COMPOUND = 0, 3, 8, 9, 10
WIDTH, HEIGHT, LENGTH = 3, 2, 3
# Animus ignores DataVersion; 4790 is the world_version of the 26.1.2 server jar
# (26.2 is 4903, 26.3 is 5023), so vanilla structure blocks on every target read it too.
DEFAULT_DATA_VERSION = 4790
PALETTE = ["minecraft:stone", "minecraft:oak_planks", "minecraft:glass"]


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


def compound_list(key: str, payloads: list) -> bytes:
    # Each payload is the inside of a compound (named tags) without its TAG_End.
    if not payloads:
        return bytes([TAG_LIST]) + name(key) + bytes([TAG_END]) + struct.pack(">i", 0)
    body = bytes([TAG_COMPOUND]) + struct.pack(">i", len(payloads))
    body += b"".join(p + bytes([TAG_END]) for p in payloads)
    return bytes([TAG_LIST]) + name(key) + body


def layout() -> list:
    blocks = []
    for y in range(HEIGHT):
        for z in range(LENGTH):
            for x in range(WIDTH):
                if y == 0:
                    block = "minecraft:stone"
                elif (x + z) % 2 == 0:
                    block = "minecraft:oak_planks"  # corners and centre
                else:
                    block = "minecraft:glass"  # edge middles
                blocks.append({"pos": [x, y, z], "name": block})
    return blocks


def structure(data_version: int, blocks: list) -> bytes:
    palette = [string_tag("Name", block) for block in PALETTE]
    entries = [int_list("pos", b["pos"]) + int_tag("state", PALETTE.index(b["name"])) for b in blocks]
    root = int_tag("DataVersion", data_version)
    root += int_list("size", [WIDTH, HEIGHT, LENGTH])
    root += compound_list("palette", palette)
    root += compound_list("blocks", entries)
    root += compound_list("entities", [])
    return bytes([TAG_COMPOUND]) + name("") + root + bytes([TAG_END])


def main(argv: list) -> int:
    if len(argv) not in (2, 4) or (len(argv) == 4 and argv[2] != "--data-version"):
        print("usage: python3 make-nbt.py <output.nbt> [--data-version N]", file=sys.stderr)
        return 2
    output = argv[1]
    data_version = int(argv[3]) if len(argv) == 4 else DEFAULT_DATA_VERSION
    blocks = layout()
    raw = structure(data_version, blocks)
    with open(output, "wb") as handle:
        handle.write(gzip.compress(raw, mtime=0))
    print(json.dumps({"size": [WIDTH, HEIGHT, LENGTH], "dataVersion": data_version, "blocks": blocks}))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
