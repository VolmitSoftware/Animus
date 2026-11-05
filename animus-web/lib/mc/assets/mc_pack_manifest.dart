/// The pack index the tool writes to `web/assets/mc/<version>/pack.json` and
/// the browser reads once. Everything the NPC stage needs to find an atlas
/// tile or an entity texture goes through here, so the URL layout is stated in
/// exactly one place. Adapted from Gloss Web's pack manifest; Animus ships no
/// baked player skin, HUD or sky sprites.
library;

import '../mesh/mc_mesh.dart';

/// Relative: works from the hosted root.
String mcPackBaseUrl(String version) => 'assets/mc/$version';

final class McAtlasEntry {
  const McAtlasEntry({
    required this.id,
    required this.x,
    required this.y,
    required this.width,
    required this.height,
    required this.frames,
  });

  factory McAtlasEntry.fromJson(String id, Map<String, Object?> json) => McAtlasEntry(
    id: id,
    x: (json['x'] as num).toInt(),
    y: (json['y'] as num).toInt(),
    width: (json['w'] as num).toInt(),
    height: (json['h'] as num).toInt(),
    frames: (json['frames'] as num?)?.toInt() ?? 1,
  );

  final String id;
  final int x;
  final int y;
  final int width;

  /// Full strip height; an animated texture is `frames` tiles tall.
  final int height;
  final int frames;

  Map<String, Object?> toJson() => <String, Object?>{
    'x': x,
    'y': y,
    'w': width,
    'h': height,
    if (frames != 1) 'frames': frames,
  };
}

final class McAtlas {
  const McAtlas({
    required this.image,
    required this.width,
    required this.height,
    required this.entries,
  });

  factory McAtlas.fromJson(Map<String, Object?> json) => McAtlas(
    image: json['image'] as String,
    width: (json['width'] as num).toInt(),
    height: (json['height'] as num).toInt(),
    entries: <String, McAtlasEntry>{
      for (final MapEntry<String, Object?> entry
          in (json['entries'] as Map<String, Object?>).entries)
        entry.key: McAtlasEntry.fromJson(entry.key, entry.value as Map<String, Object?>),
    },
  );

  final String image;
  final int width;
  final int height;
  final Map<String, McAtlasEntry> entries;

  /// Frame 0 of [textureId] in 0..1 atlas coordinates, or null when the atlas
  /// has no such texture.
  McUvRect? uv(String textureId) {
    final McAtlasEntry? entry = entries[textureId];
    if (entry == null) return null;
    final double frameHeight = entry.height / entry.frames;
    return McUvRect(
      entry.x / width,
      entry.y / height,
      (entry.x + entry.width) / width,
      (entry.y + frameHeight) / height,
    );
  }

  Map<String, Object?> toJson() => <String, Object?>{
    'image': image,
    'width': width,
    'height': height,
    'entries': <String, Object?>{
      for (final MapEntry<String, McAtlasEntry> entry in entries.entries)
        entry.key: entry.value.toJson(),
    },
  };
}

final class McPackManifest {
  const McPackManifest({
    required this.version,
    required this.jarSha256,
    required this.generatedAt,
    required this.blocks,
    required this.items,
    required this.entityTextures,
    required this.counts,
    required this.spriteFallbacks,
  });

  factory McPackManifest.fromJson(Map<String, Object?> json) => McPackManifest(
    version: json['version'] as String,
    jarSha256: json['jarSha256'] as String,
    generatedAt: json['generatedAt'] as String,
    blocks: McAtlas.fromJson(json['blocks'] as Map<String, Object?>),
    items: McAtlas.fromJson(json['items'] as Map<String, Object?>),
    entityTextures: _strings(json['entityTextures']),
    counts: <String, int>{
      for (final MapEntry<String, Object?> entry
          in (json['counts'] as Map<String, Object?>).entries)
        entry.key: (entry.value as num).toInt(),
    },
    spriteFallbacks: <String>[
      for (final Object? id in json['spriteFallbacks'] as List<Object?>) id as String,
    ],
  );

  final String version;
  final String jarSha256;
  final String generatedAt;
  final McAtlas blocks;
  final McAtlas items;

  /// Texture id (`entity/horse/horse_brown`, `entity/equipment/humanoid/iron`)
  /// to a pack-relative PNG path. Ids are the jar paths under `textures/`
  /// without `.png`, so a rig names its texture exactly as the client does.
  final Map<String, String> entityTextures;
  final Map<String, int> counts;

  /// Item keys whose definition resolved to a sprite fallback, sorted.
  final List<String> spriteFallbacks;

  String get modelsPath => 'models.json';

  Map<String, Object?> toJson() => <String, Object?>{
    'version': version,
    'jarSha256': jarSha256,
    'generatedAt': generatedAt,
    'blocks': blocks.toJson(),
    'items': items.toJson(),
    'entityTextures': entityTextures,
    'counts': counts,
    'spriteFallbacks': spriteFallbacks,
  };
}

Map<String, String> _strings(Object? value) => <String, String>{
  for (final MapEntry<String, Object?> entry
      in ((value as Map<String, Object?>?) ?? const <String, Object?>{}).entries)
    entry.key: entry.value as String,
};
