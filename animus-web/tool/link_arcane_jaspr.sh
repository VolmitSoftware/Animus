#!/bin/sh
# Points .deps/arcane_jaspr at a local arcane_jaspr checkout (default: the
# sibling ArcaneArts checkout). CI clones the repository there instead.
set -eu
cd "$(dirname "$0")/.."
target="${1:-$HOME/Developer/RemoteGit/ArcaneArts/arcane_jaspr}"
if [ ! -f "$target/pubspec.yaml" ]; then
  echo "No arcane_jaspr checkout at $target" >&2
  exit 1
fi
mkdir -p .deps
rm -f .deps/arcane_jaspr
ln -s "$target" .deps/arcane_jaspr
echo ".deps/arcane_jaspr -> $target"
