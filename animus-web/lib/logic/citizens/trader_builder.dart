/// Ports of core's `TraderMapping` (dtlTraders `trader` trait) and `BuilderMapping` (`builder` and `constructor` traits).
library;

import 'java_json.dart';
import 'java_lang.dart';

const String builderTrait = 'builder';
const String constructorTrait = 'constructor';

/// `TraderMapping.path`.
String traderPath(String name) {
  String lowered = javaLower(name)
      .replaceAll(RegExp('&[0-9a-fk-or]'), '')
      .replaceAll(RegExp('[^a-z0-9_-]+', unicode: true), '-')
      .replaceAll(RegExp('-{2,}'), '-');
  lowered = lowered.replaceAll(RegExp(r'^-+|-+$'), '');
  if (lowered.isEmpty) lowered = 'shop';
  return lowered.length > 64 ? lowered.substring(0, 64) : lowered;
}

class TraderResult {
  const TraderResult(this.module, this.warning);

  final JObject? module;
  final String? warning;
}

TraderResult mapTrader(JElement? value, String npcName) {
  if (value is! JObject) return const TraderResult(null, 'trader trait is not a mapping; kept for review');
  final JElement? converted = value.get('converted-to-v5');
  final bool legacy = value.has('type') && !(converted is JPrimitive && converted.asBoolean);
  final String name;
  if (legacy) {
    name = 'converted-${javaLower(npcName).replaceAll(RegExp('[^a-z]', unicode: true), '')}';
  } else {
    final JElement? gui = value.get('guiName');
    if (gui is! JPrimitive || javaIsBlank(gui.asString) || gui.asString == 'null') {
      return const TraderResult(null, 'trader trait names no shop; the NPC has no shop');
    }
    name = gui.asString;
  }
  final JObject module = JObject();
  module.addString('shop', 'dtltraders:${traderPath(name)}');
  module.addString('openOn', 'right');
  final JObject imported = JObject();
  imported.addString('source', 'dtlTraders');
  imported.addString('shopName', name);
  module.add('imported', imported);
  return TraderResult(module, null);
}

const Set<String> _builderConverted = <String>{
  'Schematic', 'PatternXY', 'IgnoreAir', 'IgnoreLiquid', 'Excavate', 'HoldItems', 'RequireMaterials', 'MoveTimeoutSeconds', 'YOffset', //
  'YLayers', 'Origin', 'Silent',
};
const Set<String> _builderKept = <String>{'State', 'NeededMaterials', 'ContinueLoc', 'onstart', 'oncomplete', 'oncancel', 'toggled'};
const int _maximumOffset = 1024;
const List<String> _patterns = <String>['spiral', 'reversespiral', 'linear', 'reverselinear'];

class BuilderResult {
  const BuilderResult(this.module, this.warnings);

  final JObject module;
  final List<String> warnings;
}

String? _text(JObject object, String key) {
  final JElement? value = object.get(key);
  return value is JPrimitive ? value.asString : null;
}

double? _number(JObject object, String key) {
  final JElement? value = object.get(key);
  if (value is! JPrimitive) return null;
  try {
    final double number = javaParseDouble(value.asString);
    return number.isFinite ? number : null;
  } on FormatException {
    return null;
  }
}

bool _bool(JObject object, String key) => _text(object, key)?.toLowerCase() == 'true';

int _clamp(BigInt value, int minimum, int maximum) {
  if (value < BigInt.from(minimum)) return minimum;
  if (value > BigInt.from(maximum)) return maximum;
  return value.toInt();
}

BuilderResult convertBuilder(JElement? trait) {
  final List<String> warnings = <String>[];
  final JObject module = JObject();
  final JObject source = trait is JObject ? trait : JObject();
  if (trait is! JObject) warnings.add('builder trait is not a mapping; kept for review only');
  final String? name = _text(source, 'Schematic');
  if (name == null || javaIsBlank(name)) {
    warnings.add('builder trait names no schematic; set animus:builder.schematic by hand');
    module.addString('schematic', 'missing.schem');
  } else {
    final String base = name
        .replaceAll(RegExp(r'\.schematic(?=(?:\r\n|[\n\r\u0085  ])?$)'), '')
        .replaceAll(RegExp('[^A-Za-z0-9_.-]', unicode: true), '_');
    module.addString('schematic', '$base.schem');
    warnings.add('Builder used $name in the old .schematic format, which Animus cannot read; save it again as $base.schem in the Animus schematics folder');
  }
  final JObject options = JObject();
  options.addString('anchor', 'center');
  final String? pattern = _text(source, 'PatternXY');
  if (pattern != null) {
    final String lowered = javaLower(pattern).replaceAll('_', '').replaceAll('-', '');
    if (_patterns.contains(lowered)) {
      options.addString('pattern', lowered);
    } else {
      warnings.add('builder pattern $pattern is unknown; the default spiral is used');
    }
  }
  void flag(String key, String target) {
    if (source.has(key)) options.addBool(target, _bool(source, key));
  }

  flag('IgnoreAir', 'ignoreAir');
  flag('IgnoreLiquid', 'ignoreLiquids');
  flag('Excavate', 'excavate');
  flag('HoldItems', 'holdBlock');
  flag('Silent', 'silent');
  if (_bool(source, 'RequireMaterials')) {
    options.addString('materials', 'donate');
    if (source.has('NeededMaterials')) {
      warnings.add("Builder's outstanding materials use old numeric item IDs; they are kept under imported and the build counts its needs again");
    }
  }
  final double? timeout = _number(source, 'MoveTimeoutSeconds');
  if (timeout != null) options.addInt('moveTimeoutSeconds', _clamp(javaRound(timeout), 1, 300));
  final double? offset = _number(source, 'YOffset');
  if (offset != null) {
    final JObject shift = JObject();
    shift.addInt('x', 0);
    shift.addInt('y', _clamp(javaRound(offset), -_maximumOffset, _maximumOffset));
    shift.addInt('z', 0);
    options.add('offset', shift);
  }
  final double? layers = _number(source, 'YLayers');
  if (layers != null) options.addInt('layers', _clamp(javaRound(layers), 1, 256));
  module.add('options', options);
  final JElement? origin = source.get('Origin');
  if (origin is JObject) {
    final double? x = _number(origin, 'x');
    final double? y = _number(origin, 'y');
    final double? z = _number(origin, 'z');
    if (x != null && y != null && z != null) {
      final JObject point = JObject();
      point.addInt('x', javaDoubleToInt(x.floorToDouble()));
      point.addInt('y', javaDoubleToInt(y.floorToDouble()));
      point.addInt('z', javaDoubleToInt(z.floorToDouble()));
      module.add('origin', point);
      warnings.add("builder origin is used in the NPC's own world; its saved world was ${_text(origin, 'world') ?? 'null'}");
    } else {
      warnings.add('builder origin is incomplete; the build will start where the NPC stands');
    }
  }
  final String? state = _text(source, 'State');
  if (state != null && (javaLower(state) == 'building' || javaLower(state) == 'collecting')) {
    warnings.add("Builder was ${javaLower(state)} when saved; the build starts again on the NPC's first spawn. Blocks already in place are skipped");
  }
  for (final String task in const <String>['onstart', 'oncomplete', 'oncancel']) {
    if (source.has(task)) warnings.add('builder $task names a Denizen task; recreate it with a build_finished or build_paused trigger');
  }
  final List<String> unknown = <String>[
    for (final String key in source.keys)
      if (!_builderConverted.contains(key) && !_builderKept.contains(key)) key,
  ];
  if (unknown.isNotEmpty) warnings.add('builder fields kept for review without conversion: ${unknown.join(', ')}');
  final JObject imported = JObject();
  imported.add('builder', source.deepCopy());
  imported.add('notes', JArray(<JElement>[for (final String warning in warnings) JString(warning)]));
  module.add('imported', imported);
  return BuilderResult(module, List<String>.unmodifiable(warnings));
}

/// `BuilderMapping.resume`: a behavior that starts a build Builder saved mid-way once, on the NPC's first spawn.
JObject? builderResume(JElement? trait, String key) {
  if (trait is! JObject) return null;
  final String? state = _text(trait, 'State');
  if (state == null || (javaLower(state) != 'building' && javaLower(state) != 'collecting')) return null;
  JObject marker() => JObject()
    ..addString('name', 'builder-$key-resumed')
    ..addString('scope', 'npc');
  JObject step(String capability, JObject arguments) => JObject()
    ..addString('type', 'action')
    ..addString('capability', capability)
    ..add('arguments', arguments);
  final JObject resume = JObject()
    ..addString('type', 'condition')
    ..addString('capability', 'animus:flag-state')
    ..add('arguments', marker())
    ..add('then', JObject()
      ..addString('type', 'sequence')
      ..add('steps', JArray()))
    ..add('else', JObject()
      ..addString('type', 'sequence')
      ..add('steps', JArray(<JElement>[step('animus:build', JObject()..addString('operation', 'start')), step('animus:set-flag', marker())])));
  return JObject()
    ..addInt('schemaVersion', 1)
    ..addInt('revision', 1)
    ..addString('id', 'citizens:builder-$key')
    ..addString('entry', 'resume')
    ..add('routines', JObject()..add('resume', resume))
    ..add('triggers', JArray(<JElement>[
      JObject()
        ..addString('id', 'spawn')
        ..addString('event', 'spawn')
        ..addString('routine', 'resume')
    ]));
}

List<String> constructorWarnings(JElement? trait) {
  final String keys = trait is JObject ? trait.keys.join(', ') : '(not a mapping)';
  return <String>[
    "constructor trait kept for review only: Constructor's saved keys are not published, so nothing was converted; fields: $keys",
    'copy Constructor schematics (.schem or .nbt) into the Animus schematics folder and start the build with /animus build start',
  ];
}
