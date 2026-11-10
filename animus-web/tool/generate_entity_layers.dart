/// Writes `lib/mc/rigs/mc_vanilla_layers.g.dart`: the box models of the
/// mobs the preview draws beyond the hand-written rigs, read from a 26.3
/// client. Vanilla entity models are code, not assets, so
/// `tool/entity_layers/DumpEntityLayers.java` runs the client's own
/// `LayerDefinitions.createRoots()` and each model's `setupAnim` with a
/// default render state (an idle entity at tick 0), and prints every layer
/// in `tool/entity_layers/layers.txt` as JSON. A line `LAYER@name=...`
/// records a second pose of the same layer with render state fields set
/// (`isSitting=true`, an animation `start@0`), which the preview applies for
/// a body setting.
///
///   dart run tool/generate_entity_layers.dart \
///     --jar=CLIENT_JAR --classpath=LIBRARY_JARS_JOINED_BY_COLONS
///   dart run tool/generate_entity_layers.dart --json=SAVED_DUMP
///
/// The libraries are the ones a launcher or Loom keeps next to the client
/// (Guava, fastutil, JOML, DataFixerUpper, authlib, Netty, Gson, SLF4J).
library;

import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

const String generatedPath = 'lib/mc/rigs/mc_vanilla_layers.g.dart';
const String layerList = 'tool/entity_layers/layers.txt';

Future<void> main(List<String> arguments) async {
  final Map<String, String> options = <String, String>{
    for (final String argument in arguments)
      if (argument.startsWith('--') && argument.contains('=')) argument.substring(2, argument.indexOf('=')): argument.substring(argument.indexOf('=') + 1),
  };
  final String json;
  if (options['json'] != null) {
    json = File(options['json']!).readAsStringSync();
  } else {
    final String? jar = options['jar'];
    if (jar == null) throw ArgumentError('Pass --jar=<client jar> --classpath=<libraries> or --json=<dump>');
    final List<String> layers = File(layerList).readAsLinesSync().where((String line) => line.trim().isNotEmpty).toList();
    final ProcessResult result = await Process.run(options['java'] ?? 'java', <String>[
      '-cp',
      <String>[jar, if (options['classpath'] != null) options['classpath']!].join(':'),
      'tool/entity_layers/DumpEntityLayers.java',
      ...layers,
    ]);
    if (result.exitCode != 0) {
      stderr.write(result.stderr);
      exitCode = result.exitCode;
      return;
    }
    json = result.stdout as String;
  }
  File(generatedPath).writeAsStringSync(renderLayers(jsonDecode(json) as Map<String, Object?>));
  stdout.writeln('Wrote $generatedPath');
}

String _n(num value) {
  final double rounded = (value * 10000).roundToDouble() / 10000;
  if (rounded == rounded.roundToDouble()) return '${rounded.round()}';
  return rounded.toString();
}

String _deg(num radians) => _n(radians * 180 / math.pi);

String _vec(List<Object?> v) => 'McVec3(${_n(v[0]! as num)}, ${_n(v[1]! as num)}, ${_n(v[2]! as num)})';

String _key(String location) => location.startsWith('minecraft:') ? location.substring(10) : location;

typedef _Pose = ({List<num> pose, bool hidden});

/// Every part's pose by name, the mesh root as `_root`.
Map<String, _Pose> _poses(Map<String, Object?> root) {
  final Map<String, _Pose> out = <String, _Pose>{};
  void walk(String name, Map<String, Object?> part) {
    out[name] = (pose: <num>[for (final Object? v in part['pose']! as List<Object?>) v! as num], hidden: part['hidden'] == 1);
    for (final MapEntry<String, Object?> child in (part['children']! as Map<String, Object?>).entries) {
      walk(child.key, child.value! as Map<String, Object?>);
    }
  }

  walk('_root', root);
  return out;
}

String _part(String name, Map<String, Object?> part, String indent) {
  final List<Object?> pose = part['pose']! as List<Object?>;
  final List<Object?> cubes = part['cubes']! as List<Object?>;
  final Map<String, Object?> children = part['children']! as Map<String, Object?>;
  final StringBuffer out = StringBuffer('McRigPart(name: \'$name\', pivot: ${_vec(pose)}');
  if (pose[3] != 0 || pose[4] != 0 || pose[5] != 0) {
    out.write(', rotationDeg: McVec3(${_deg(pose[3]! as num)}, ${_deg(pose[4]! as num)}, ${_deg(pose[5]! as num)})');
  }
  final num scale = pose[6]! as num;
  if (scale != pose[7] || scale != pose[8]) throw StateError('$name: uneven part scale');
  if (scale != 1) out.write(', scale: ${_n(scale)}');
  if (cubes.isNotEmpty) {
    out.write(', boxes: <McBox>[');
    for (final Object? raw in cubes) {
      final Map<String, Object?> cube = raw! as Map<String, Object?>;
      if (cube['f'] != null || cube['ts'] != null) throw StateError('$name: a box with hidden faces or a texture scale');
      final List<Object?>? grow = cube['g'] as List<Object?>?;
      if (grow != null && (grow[0] != grow[1] || grow[0] != grow[2])) throw StateError('$name: uneven box growth');
      final List<Object?> uv = cube['uv']! as List<Object?>;
      out.write('McBox(origin: ${_vec(cube['o']! as List<Object?>)}, size: ${_vec(cube['s']! as List<Object?>)}, u: ${_n(uv[0]! as num)}, v: ${_n(uv[1]! as num)}');
      if (cube['m'] == 1) out.write(', mirror: true');
      if (grow != null) out.write(', inflate: ${_n(grow[0]! as num)}');
      out.write('), ');
    }
    out.write(']');
  }
  if (children.isNotEmpty) {
    out.write(', children: <McRigPart>[\n');
    for (final MapEntry<String, Object?> child in children.entries) {
      out.write('$indent  ${_part(child.key, child.value! as Map<String, Object?>, '$indent  ')},\n');
    }
    out.write('$indent]');
  }
  out.write(')');
  return out.toString();
}

String renderLayers(Map<String, Object?> dump) {
  final StringBuffer out = StringBuffer()
    ..writeln('// Generated by tool/generate_entity_layers.dart from the 26.3 client. Do not edit.')
    ..writeln('// ignore_for_file: prefer_const_constructors')
    ..writeln()
    ..writeln("import '../scene/mc_math.dart';")
    ..writeln("import 'mc_rig.dart';")
    ..writeln("import 'mc_vanilla_layer_pose.dart';")
    ..writeln()
    ..writeln('/// Each layer as the client builds it and poses it at rest, by `ModelLayerLocation`')
    ..writeln('/// without the namespace (`zombie#main`). The mesh root is the part `_root`.')
    ..writeln('const Map<String, McRig> mcVanillaLayers = <String, McRig>{');
  final Map<String, Map<String, _Pose>> rest = <String, Map<String, _Pose>>{};
  for (final MapEntry<String, Object?> entry in dump.entries) {
    if (entry.key.contains('@')) continue;
    final Map<String, Object?> layer = entry.value! as Map<String, Object?>;
    final Map<String, Object?> root = layer['root']! as Map<String, Object?>;
    final String key = _key(entry.key);
    rest[key] = _poses(root);
    out.writeln("  '$key': McRig(id: '$key', textureWidth: ${layer['w']}, textureHeight: ${layer['h']}, heightBlocks: 0, parts: <McRigPart>[");
    out.writeln('    ${_part('_root', root, '    ')},');
    out.writeln('  ]),');
  }
  out
    ..writeln('};')
    ..writeln()
    ..writeln('/// The parts each layer hides at rest (`ModelPart.visible`), such as the chests of a')
    ..writeln('/// donkey without one.')
    ..writeln('const Map<String, Set<String>> mcVanillaLayerHidden = <String, Set<String>>{');
  for (final MapEntry<String, Map<String, _Pose>> layer in rest.entries) {
    final List<String> hidden = <String>[
      for (final MapEntry<String, _Pose> part in layer.value.entries)
        if (part.value.hidden) "'${part.key}'",
    ];
    if (hidden.isNotEmpty) out.writeln("  '${layer.key}': <String>{${hidden.join(', ')}},");
  }
  out
    ..writeln('};')
    ..writeln()
    ..writeln('/// Second poses of a layer, `zombie#main@name`, as changes from its rest pose.')
    ..writeln('const Map<String, McVanillaLayerPose> mcVanillaLayerPoses = <String, McVanillaLayerPose>{');
  for (final MapEntry<String, Object?> entry in dump.entries) {
    if (!entry.key.contains('@')) continue;
    final String key = _key(entry.key);
    final Map<String, _Pose> base = rest[key.substring(0, key.indexOf('@'))]!;
    final Map<String, _Pose> posed = _poses((entry.value! as Map<String, Object?>)['root']! as Map<String, Object?>);
    final List<String> rotations = <String>[], offsets = <String>[], hides = <String>[], shows = <String>[];
    bool near(num a, num b) => (a - b).abs() < 1e-4;
    for (final MapEntry<String, _Pose> part in posed.entries) {
      final _Pose was = base[part.key]!;
      final List<num> p = part.value.pose, b = was.pose;
      if (!near(p[6], b[6])) throw StateError('$key: ${part.key} changes scale');
      if (!near(p[3], b[3]) || !near(p[4], b[4]) || !near(p[5], b[5])) {
        rotations.add("'${part.key}': McVec3(${_deg(p[3])}, ${_deg(p[4])}, ${_deg(p[5])})");
      }
      if (!near(p[0], b[0]) || !near(p[1], b[1]) || !near(p[2], b[2])) {
        offsets.add("'${part.key}': McVec3(${_n(p[0] - b[0])}, ${_n(p[1] - b[1])}, ${_n(p[2] - b[2])})");
      }
      if (part.value.hidden && !was.hidden) hides.add("'${part.key}'");
      if (!part.value.hidden && was.hidden) shows.add("'${part.key}'");
    }
    out.writeln("  '$key': McVanillaLayerPose(");
    if (rotations.isNotEmpty) out.writeln('    rotationsDeg: <String, McVec3>{${rotations.join(', ')}},');
    if (offsets.isNotEmpty) out.writeln('    offsetsPx: <String, McVec3>{${offsets.join(', ')}},');
    if (hides.isNotEmpty) out.writeln('    hides: <String>{${hides.join(', ')}},');
    if (shows.isNotEmpty) out.writeln('    shows: <String>{${shows.join(', ')}},');
    out.writeln('  ),');
  }
  out.writeln('};');
  return out.toString();
}
