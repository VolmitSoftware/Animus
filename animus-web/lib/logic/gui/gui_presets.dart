/// The Gloss presets document (`gui/presets.json`, schema/gloss/
/// gloss-presets.schema.json) and the fix for a `preset` reference. Animus
/// never resolves presets: core refuses any document that names one with
/// "Gloss presets are not available in Animus; inline the preset's fields".
/// The fix copies what Gloss would have applied (the kind's `defaults`, then
/// the preset's `extends` chain from the base down) under the document's
/// own fields, and removes `preset`.
library;

import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import 'gui_json.dart';

/// Core's wording for a document that names a preset.
const String presetRefusal = "Gloss presets are not available in Animus; inline the preset's fields";

/// The 26 preset kinds the schema allows, in its order.
const List<String> presetKinds = <String>[
  'animations', 'bubbles', 'previews', 'damage-indicators', 'emoji', 'entity-overlays', 'holograms', 'menus', 'names', //
  'motd', 'panels', 'real-drops', 'boards', 'tablist', 'surfaces', 'nametags', 'channels', 'strings', 'leaderboards', //
  'inventories', 'markers', 'nameplates', 'waypoints', 'behaviors', 'glyphs', 'connections',
];

/// Value keys a preset may not set.
const List<String> presetReservedKeys = <String>['schemaVersion', 'revision', 'id', 'uuid', 'preset'];

/// The preset kind an Animus document kind reads, for the kinds core
/// refuses a `preset` on.
String? presetKindFor(DocumentKind kind) => switch (kind) {
      DocumentKind.inventory => 'inventories',
      DocumentKind.hologram => 'holograms',
      DocumentKind.textAnimation => 'animations',
      _ => null,
    };

/// Whether [name] is a preset name the schema allows.
bool validPresetName(String name) =>
    name.isNotEmpty && !name.contains('..') && !name.contains('/') && !name.contains(r'\') && name.trimLeft() == name;

/// The workspace's presets document, if it has one that parses.
JsonMap? workspacePresets(Iterable<AnimusDocument> documents) {
  for (final AnimusDocument document in documents) {
    if (document.kind == DocumentKind.presets && document.json is Map<String, Object?>) return document.json! as Map<String, Object?>;
  }
  return null;
}

final class PresetValues {
  const PresetValues(this.values, this.problem);

  /// The merged values, or null with [problem].
  final JsonMap? values;
  final String? problem;
}

/// The values preset [name] of [kind] applies: `defaults.<kind>`, then the
/// `extends` chain from its base down to [name], later ones winning.
PresetValues resolvePreset(JsonMap? presets, String kind, String name) {
  if (presets == null) return const PresetValues(null, 'this workspace has no presets document (gui/presets.json)');
  final JsonMap named = asMap(asMap(presets['presets'])[kind]);
  final List<JsonMap> chain = <JsonMap>[];
  final Set<String> seen = <String>{};
  String? next = name;
  while (next != null) {
    if (!seen.add(next)) return PresetValues(null, "preset '$name' extends itself through '$next'");
    final Object? preset = named[next];
    if (preset is! Map<String, Object?>) {
      return PresetValues(null, next == name ? "presets.$kind has no preset '$name'" : "preset '$name' extends '$next', which presets.$kind does not have");
    }
    chain.insert(0, asMap(preset['values']));
    next = asString(preset['extends']);
  }
  JsonMap merged = _withoutReserved(asMap(asMap(presets['defaults'])[kind]));
  for (final JsonMap values in chain) {
    merged = mergeUnder(_withoutReserved(values), merged);
  }
  return PresetValues(merged, null);
}

JsonMap _withoutReserved(JsonMap values) => <String, Object?>{
      for (final MapEntry<String, Object?> entry in values.entries)
        if (!presetReservedKeys.contains(entry.key)) entry.key: deepCopy(entry.value),
    };

/// [top] laid over [base]: objects merge key by key, anything else in [top]
/// replaces [base]'s value.
JsonMap mergeUnder(JsonMap top, JsonMap base) {
  final JsonMap out = <String, Object?>{for (final MapEntry<String, Object?> entry in base.entries) entry.key: deepCopy(entry.value)};
  for (final MapEntry<String, Object?> entry in top.entries) {
    final Object? below = out[entry.key];
    out[entry.key] = entry.value is Map<String, Object?> && below is Map<String, Object?>
        ? mergeUnder(entry.value! as Map<String, Object?>, below)
        : deepCopy(entry.value);
  }
  return out;
}

/// [document] with its `preset` copied in: the preset's values under the
/// document's own fields, `preset` removed, key order kept with new keys
/// last. Null with a reason when the preset cannot be read.
(JsonMap?, String?) inlinePreset(JsonMap document, DocumentKind kind, JsonMap? presets) {
  final String? presetKind = presetKindFor(kind);
  final Object? reference = document['preset'];
  if (presetKind == null) return (null, '${kind.label} documents have no presets');
  if (reference is! String) return (null, 'preset is not a preset name');
  final PresetValues resolved = resolvePreset(presets, presetKind, reference);
  if (resolved.values == null) return (null, resolved.problem);
  final JsonMap own = <String, Object?>{...document}..remove('preset');
  final JsonMap merged = mergeUnder(own, resolved.values!);
  return (<String, Object?>{
    for (final String key in own.keys) key: merged[key],
    for (final MapEntry<String, Object?> entry in merged.entries)
      if (!own.containsKey(entry.key)) entry.key: entry.value,
  }, null);
}
