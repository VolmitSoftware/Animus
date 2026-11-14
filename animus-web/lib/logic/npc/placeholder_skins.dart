/// Built-in placeholder skins: original 64x64 PNGs under
/// `web/assets/skins/placeholder/` (drawn by
/// `tool/generate_placeholder_skins.dart`), worn by the browser preview when
/// an NPC has no skin texture here. The skin picker also offers them as
/// built-in skins: picking one makes a skin document from its PNG.
library;

import 'dart:convert';

/// Where the site serves the placeholder PNGs and their `index.json`.
const String placeholderSkinBase = 'assets/skins/placeholder';

/// One placeholder character, drawn in both arm models.
final class PlaceholderLook {
  const PlaceholderLook(this.id, this.label);

  final String id;
  final String label;
}

/// The placeholder characters, in picker order.
const List<PlaceholderLook> placeholderLooks = <PlaceholderLook>[
  PlaceholderLook('guard', 'Guard'),
  PlaceholderLook('merchant', 'Merchant'),
  PlaceholderLook('villager', 'Villager'),
  PlaceholderLook('noble', 'Noble'),
  PlaceholderLook('miner', 'Miner'),
  PlaceholderLook('traveler', 'Traveler'),
  PlaceholderLook('smith', 'Smith'),
  PlaceholderLook('scholar', 'Scholar'),
];

/// One PNG: a look in one arm model.
final class PlaceholderSkin {
  const PlaceholderSkin({required this.id, required this.look, required this.label, required this.slim, required this.file});

  factory PlaceholderSkin.of(PlaceholderLook look, {required bool slim}) {
    final String id = slim ? '${look.id}-slim' : look.id;
    return PlaceholderSkin(id: id, look: look.id, label: look.label, slim: slim, file: '$id.png');
  }

  /// An `index.json` entry. Throws [FormatException] when a field is missing
  /// or of the wrong type, or `arms` is neither `classic` nor `slim`.
  factory PlaceholderSkin.fromJson(Object? json) {
    if (json is! Map<String, Object?>) throw const FormatException('a placeholder skin entry is an object');
    String text(String key) {
      final Object? value = json[key];
      if (value is! String || value.isEmpty) throw FormatException('a placeholder skin entry needs a "$key" string');
      return value;
    }

    final String arms = text('arms');
    if (arms != 'classic' && arms != 'slim') throw FormatException('arms is "classic" or "slim", not "$arms"');
    final String file = text('file');
    if (file.contains('/') || !file.endsWith('.png')) throw FormatException('file is a PNG name in the placeholder folder, not "$file"');
    return PlaceholderSkin(id: text('id'), look: text('look'), label: text('label'), slim: arms == 'slim', file: file);
  }

  final String id;

  /// The [PlaceholderLook] id, shared by both arm models.
  final String look;
  final String label;
  final bool slim;

  /// The PNG name inside [placeholderSkinBase].
  final String file;

  String get arms => slim ? 'slim' : 'classic';

  /// The runtime image id stages bind.
  String get textureId => 'placeholder:$id';

  /// The PNG's address relative to the site.
  String get url => '$placeholderSkinBase/$file';

  Map<String, Object?> toJson() => <String, Object?>{'id': id, 'look': look, 'label': label, 'arms': arms, 'file': file};

  @override
  bool operator ==(Object other) =>
      other is PlaceholderSkin && other.id == id && other.look == look && other.label == label && other.slim == slim && other.file == file;

  @override
  int get hashCode => Object.hash(id, look, label, slim, file);
}

/// Every placeholder PNG: each look classic, then slim.
final List<PlaceholderSkin> placeholderSkins = <PlaceholderSkin>[
  for (final PlaceholderLook look in placeholderLooks) ...<PlaceholderSkin>[
    PlaceholderSkin.of(look, slim: false),
    PlaceholderSkin.of(look, slim: true),
  ],
];

/// `index.json` for [skins].
String placeholderIndexJson(List<PlaceholderSkin> skins) =>
    '${const JsonEncoder.withIndent('  ').convert(<String, Object?>{'skins': <Object?>[for (final PlaceholderSkin skin in skins) skin.toJson()]})}\n';

/// Reads `index.json`. Throws [FormatException] on a malformed index or a
/// repeated id.
List<PlaceholderSkin> parsePlaceholderIndex(String text) {
  final Object? json = jsonDecode(text);
  final Object? entries = json is Map<String, Object?> ? json['skins'] : null;
  if (entries is! List<Object?>) throw const FormatException('the placeholder index is an object with a "skins" list');
  final List<PlaceholderSkin> skins = <PlaceholderSkin>[for (final Object? entry in entries) PlaceholderSkin.fromJson(entry)];
  final Set<String> ids = <String>{};
  for (final PlaceholderSkin skin in skins) {
    if (!ids.add(skin.id)) throw FormatException('placeholder skin "${skin.id}" is listed twice');
  }
  return skins;
}

/// The look an NPC wears by default: FNV-1a of its document id, so the same
/// NPC always gets the same character on every platform.
PlaceholderLook defaultPlaceholderLook(String npcId) {
  int hash = 0x811c9dc5;
  for (final int unit in utf8.encode(npcId)) {
    hash = (hash ^ unit) & 0xffffffff;
    // Times the prime 2^24 + 0x193, kept under 2^53 for JavaScript numbers.
    hash = (hash * 0x193 + ((hash & 0xff) << 24)) & 0xffffffff;
  }
  return placeholderLooks[hash % placeholderLooks.length];
}

/// The placeholder NPC [npcId] wears without a skin texture, in [slim]
/// arms: the stage and the NPC lists both draw this one.
PlaceholderSkin npcPlaceholderSkin(String npcId, {required bool slim}) => placeholderSkinFor(defaultPlaceholderLook(npcId), slim: slim);

/// The PNG of [look] in the arm model the stage draws.
PlaceholderSkin placeholderSkinFor(PlaceholderLook look, {required bool slim}) =>
    placeholderSkins.firstWhere((PlaceholderSkin skin) => skin.look == look.id && skin.slim == slim);
