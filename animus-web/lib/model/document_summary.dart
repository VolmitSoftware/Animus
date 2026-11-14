/// The few fields a list shows for a document without reading it whole:
/// the catalog `summary` of the editor protocol (UX protocol-W section 1).
/// The server sends it with every catalog entry; [summarizeDocument] works
/// it out the same way from a document held here, so a list shows an edit
/// at once and a local workspace has one too.
library;

import 'dart:convert';

import '../logic/gui/gui_text.dart';
import '../mc/npc/mc_text.dart';
import 'document_kind.dart';

class DocumentSummary {
  const DocumentSummary(this.fields);

  static const DocumentSummary empty = DocumentSummary(<String, Object?>{});

  /// The summary as the protocol carries it.
  final Map<String, Object?> fields;

  String? _string(String key) => fields[key] is String ? fields[key]! as String : null;

  int? _int(String key) => fields[key] is num ? (fields[key]! as num).toInt() : null;

  double? _double(String key) => fields[key] is num ? (fields[key]! as num).toDouble() : null;

  /// Plain display name; null when the document has none.
  String? get name => _string('name');

  /// NPC body type, as written (`minecraft:player`).
  String? get type => _string('type');
  String? get world => _string('world');
  double? get x => _double('x');
  double? get y => _double('y');
  double? get z => _double('z');
  bool? get spawned => fields['spawned'] is bool ? fields['spawned']! as bool : null;

  /// The NPC's folder under `npcs/`, `''` at the root.
  String? get folder => _string('folder');
  List<String> get tags => <String>[for (final Object? tag in fields['tags'] is List<Object?> ? fields['tags']! as List<Object?> : const <Object?>[]) '$tag'];

  /// NPC features in protocol order: route, recording, schedule, combat,
  /// shop, behavior, rig, hologram, mount.
  List<String> get features =>
      <String>[for (final Object? feature in fields['features'] is List<Object?> ? fields['features']! as List<Object?> : const <Object?>[]) '$feature'];

  /// The skin document id an NPC wears.
  String? get skin => _string('skin');

  /// A skin's texture URL, and whether it uses the slim arms.
  String? get texture => _string('texture');
  bool get slim => fields['slim'] == true;

  /// Kind-specific counts: shop offers and pages, inventory rows, hologram
  /// lines, text-animation frames, server behaviors, route points, quest
  /// stages, rig bones, trait group fields.
  int? count(String key) => _int(key);

  /// Recording and animation length.
  double? get seconds => _double('seconds');

  /// Behavior trigger events, in order.
  List<String> get triggers =>
      <String>[for (final Object? trigger in fields['triggers'] is List<Object?> ? fields['triggers']! as List<Object?> : const <Object?>[]) '$trigger'];

  /// Pack version.
  String? get version => _string('version');

  static DocumentSummary fromJson(Object? json) => json is Map<String, Object?> ? DocumentSummary(json) : empty;

  @override
  bool operator ==(Object other) => other is DocumentSummary && jsonEncode(other.fields) == jsonEncode(fields);

  @override
  int get hashCode => jsonEncode(fields).hashCode;
}

/// Text as a list shows it, as the server's summaries do: Gloss animation
/// tokens, colour codes, hex colours and MiniMessage tags removed,
/// whitespace collapsed, at most 64 characters; null when nothing visible is
/// left.
String? plainName(Object? text) {
  if (text is! String) return null;
  final String runs = guiTextRuns(text.replaceAll(_glossToken, ''), sample: false).map((McTextRun run) => run.text).join();
  final String plain = _strip(runs.replaceAll(_leftoverTag, '').replaceAll(_spaces, ' '));
  if (plain.isEmpty) return null;
  return _bounded(plain);
}

/// The whitespace the server's `\s` collapses: ASCII only, so a non-breaking
/// space stays.
final RegExp _spaces = RegExp(r'[ \t\n\x0B\f\r]+');

/// Java's `String.strip()`: trims what `Character.isWhitespace` accepts, the
/// Unicode space separators except the non-breaking ones, and the ASCII
/// controls tab to carriage return and file to unit separator.
String _strip(String text) {
  int start = 0;
  int end = text.length;
  while (start < end && _javaWhitespace(text.codeUnitAt(start))) {
    start++;
  }
  while (end > start && _javaWhitespace(text.codeUnitAt(end - 1))) {
    end--;
  }
  return text.substring(start, end);
}

bool _javaWhitespace(int unit) =>
    (unit >= 0x09 && unit <= 0x0D) ||
    (unit >= 0x1C && unit <= 0x20) ||
    unit == 0x1680 ||
    (unit >= 0x2000 && unit <= 0x2006) ||
    (unit >= 0x2008 && unit <= 0x200A) ||
    unit == 0x2028 ||
    unit == 0x2029 ||
    unit == 0x205F ||
    unit == 0x3000;

final RegExp _glossToken = RegExp(r'\|[a-z0-9_.:-]{1,64}\|');
final RegExp _leftoverTag = RegExp(r'</?[a-zA-Z_#!][^<>]{0,96}>');
final RegExp _texture = RegExp(r'^https?://textures\.minecraft\.net/texture/[0-9a-f]{1,128}$');
const Map<String, int> _rowsOf = <String, int>{'9x1': 1, '9x2': 2, '9x3': 3, '9x4': 4, '9x5': 5, '9x6': 6, '5x1': 1, '3x3': 3};
const Set<String> _shopModes = <String>{'trade', 'buy', 'sell'};

String _bounded(String text) => text.length > 64 ? text.substring(0, 64) : text;

Map<String, Object?> _map(Object? value) => value is Map<String, Object?> ? value : const <String, Object?>{};

List<Object?>? _array(Object? value) => value is List<Object?> ? value : null;

String? _text(Object? value) => value is String && value.trim().isNotEmpty ? _bounded(value) : null;

double? _number(Object? value) => value is num ? value.toDouble() : null;

double? _seconds(Object? ticks) => ticks is num ? (ticks < 0 ? 0 : ticks) / 20 : null;

/// The summary of [kind] document [id] whose parsed JSON is [json], field
/// for field as the server's catalog summary (core `EditorSummaries`).
DocumentSummary summarizeDocument(DocumentKind kind, String id, Object? json) {
  if (json is! Map<String, Object?>) return DocumentSummary.empty;
  final Map<String, Object?> fields = switch (kind) {
    DocumentKind.npc => _npc(id, json),
    DocumentKind.skin => _skin(json),
    DocumentKind.shop => _shop(json),
    DocumentKind.inventory => <String, Object?>{
        'name': plainName(json['title']),
        'rows': _rowsOf[json['resolution']] ?? _array(json['mask'])?.length,
      },
    DocumentKind.hologram => _hologram(json),
    DocumentKind.textAnimation => <String, Object?>{'frames': _array(json['frames'])?.length},
    DocumentKind.behavior => <String, Object?>{'triggers': _triggers(json)},
    DocumentKind.serverBehaviors => <String, Object?>{'behaviors': _array(json['behaviors'])?.length},
    DocumentKind.pack => <String, Object?>{'name': plainName(json['name']), 'version': _text(json['version'])},
    DocumentKind.route => <String, Object?>{'points': _array(json['points'])?.length},
    DocumentKind.recording => <String, Object?>{'name': plainName(json['description']), 'seconds': _seconds(json['durationTicks']), 'world': _text(json['world'])},
    DocumentKind.group => <String, Object?>{'fields': json['values'] is Map<String, Object?> ? _map(json['values']).length : null},
    DocumentKind.quest => <String, Object?>{'name': plainName(json['title']), 'stages': _array(json['stages'])?.length},
    DocumentKind.rig => <String, Object?>{'bones': _array(json['bones'])?.length},
    DocumentKind.animation => <String, Object?>{'seconds': _seconds(json['durationTicks'])},
    DocumentKind.menu || DocumentKind.presets => const <String, Object?>{},
  };
  return DocumentSummary(<String, Object?>{
    for (final MapEntry<String, Object?> entry in fields.entries)
      if (entry.value != null) entry.key: entry.value,
  });
}

Map<String, Object?> _npc(String id, Map<String, Object?> json) {
  final Map<String, Object?> position = _map(json['position']);
  final Map<String, Object?> modules = _map(json['modules']);
  final Map<String, Object?>? navigation = modules['animus:navigation'] is Map<String, Object?> ? _map(modules['animus:navigation']) : null;
  final Map<String, Object?> gui = _map(modules['animus:gui']);
  final Object? mount = modules['animus:mount'];
  final List<Object?> points = _array(navigation?['points']) ?? const <Object?>[];
  final Object? skin = json['skin'];
  return <String, Object?>{
    'name': plainName(json['name']),
    'type': _text(json['type']),
    'world': _text(position['world']),
    'x': _number(position['x']),
    'y': _number(position['y']),
    'z': _number(position['z']),
    'spawned': json['spawned'] is bool ? json['spawned'] : null,
    'folder': id.contains('/') ? id.substring(0, id.lastIndexOf('/')) : '',
    'tags': json['tags'] is List<Object?> ? <String>[for (final Object? tag in _array(json['tags'])!) if (tag is String) _bounded(tag)].take(16).toList() : null,
    'features': <String>[
      if (navigation != null && (navigation['route'] is String || points.isNotEmpty)) 'route',
      if (navigation?['recording'] is String) 'recording',
      if (modules['animus:schedule'] is Map<String, Object?>) 'schedule',
      if (_map(modules['animus:combat'])['enabled'] == true) 'combat',
      if (modules['animus:shop'] is Map<String, Object?> || _map(gui['open'])['kind'] == 'shop') 'shop',
      if ((_array(json['behaviors']) ?? const <Object?>[]).isNotEmpty) 'behavior',
      if (json['rig'] is Map<String, Object?>) 'rig',
      if ((_array(_map(json['hologram'])['lines']) ?? const <Object?>[]).isNotEmpty || gui['hologram'] is Map<String, Object?>) 'hologram',
      if (mount is Map<String, Object?> && _mountOn(mount['enabled'])) 'mount',
    ],
    // Its own route only: a named route counts in its route document.
    'points': points.isNotEmpty && navigation?['route'] is! String && navigation?['recording'] is! String ? points.length : null,
    'skin': skin is String && RegExp(r'^[a-f0-9]{64}$').hasMatch(skin) ? skin : null,
  };
}

/// Whether a mount module's `enabled` leaves it on, as the server reads it:
/// absent or null is on; any other JSON value is on only when it reads as
/// "true" (Gson's `getAsBoolean`).
bool _mountOn(Object? enabled) {
  if (enabled == null || enabled is Map || enabled is List) return true;
  if (enabled is bool) return enabled;
  return '$enabled'.toLowerCase() == 'true';
}

/// The bytes of [text] as Java's basic base64 decoder reads them: the
/// standard alphabet only, padding optional; null when it is not base64.
List<int>? _base64(String text) {
  if (text.contains('-') || text.contains('_')) return null;
  final int rest = text.length % 4;
  if (rest == 1) return null;
  try {
    return base64.decode(rest == 0 ? text : text + '=' * (4 - rest));
  } on FormatException {
    return null;
  }
}

Map<String, Object?> _skin(Map<String, Object?> json) {
  final Object? raw = json['textureRaw'];
  if (raw is! String) return const <String, Object?>{};
  final List<int>? bytes = _base64(_strip(raw));
  if (bytes == null) return const <String, Object?>{};
  try {
    final Map<String, Object?> skin = _map(_map(_map(jsonDecode(utf8.decode(bytes, allowMalformed: true)))['textures'])['SKIN']);
    final Object? url = skin['url'];
    if (url is! String || !_texture.hasMatch(url)) return const <String, Object?>{};
    return <String, Object?>{
      'texture': url.startsWith('http://') ? 'https://${url.substring(7)}' : url,
      'slim': _map(skin['metadata'])['model'] == 'slim',
    };
  } on FormatException {
    return const <String, Object?>{};
  }
}

Map<String, Object?> _shop(Map<String, Object?> json) {
  final List<Object?>? pages = _array(json['pages']);
  return <String, Object?>{
    'name': plainName(json['title']),
    if (pages != null) ...<String, Object?>{
      'offers': <int>[
        for (final Object? page in pages)
          for (final MapEntry<String, Object?> mode in _map(page).entries)
            if (_shopModes.contains(mode.key) && mode.value is Map<String, Object?>) _map(mode.value).length,
      ].fold<int>(0, (int sum, int offers) => sum + offers),
      'pages': pages.length,
    },
  };
}

Map<String, Object?> _hologram(Map<String, Object?> json) {
  final List<Object?>? lines = _array(json['lines']) ?? _array(_map(_array(json['pages'])?.firstOrNull)['lines']);
  String? name;
  for (final Object? line in lines ?? const <Object?>[]) {
    name = plainName(line is String ? line : _map(line)['text']);
    if (name != null) break;
  }
  final Map<String, Object?> anchor = _map(json['anchor']);
  final List<Object?> position = _array(anchor['position']) ?? const <Object?>[];
  return <String, Object?>{
    'name': name,
    'lines': lines?.length,
    'world': _text(anchor['world']),
    if (position.length == 3) ...<String, Object?>{'x': _number(position[0]), 'y': _number(position[1]), 'z': _number(position[2])},
  };
}

List<String>? _triggers(Map<String, Object?> json) {
  final List<Object?>? triggers = _array(json['triggers']);
  if (triggers == null) return null;
  final List<String> events = <String>[];
  for (final Object? trigger in triggers) {
    final Object? event = _map(trigger)['event'];
    if (event is String && events.length < 8 && !events.contains(_bounded(event))) events.add(_bounded(event));
  }
  return events;
}
