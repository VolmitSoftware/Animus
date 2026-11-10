/// Blockbench `.bbmodel` import as resource pack models: every group becomes a bone, the group's cubes become one item
/// model drawn by an item display on that bone, and every Blockbench
/// animation becomes an animation document.
///
/// Spaces: Blockbench models face -Z (north) like the client's entity
/// models; Animus rigs face +Z at yaw 0. The import turns everything 180
/// degrees about Y: pivots `(-x, y, -z) / 16`, rotations `(-x, y, -z)`. An
/// item display draws its model turned 180 degrees about Y, which cancels
/// that turn for the cubes, so element coordinates are the Blockbench ones
/// relative to the group origin, plus 8. Cube rotations in 22.5-degree steps
/// on one axis stay on the element; any other cube rotation becomes a bone of
/// its own, where a display transform allows any angle.
///
/// Parsing is bounded: [BbmodelLimits] caps the file, elements, textures,
/// animations and keyframes, and anything over a cap is refused, not cut.
library;

import 'dart:convert';
import 'dart:math' as math;
import 'dart:typed_data';

import 'rig_edit.dart';
import 'rig_validation.dart';

class BbmodelLimits {
  const BbmodelLimits();

  static const int fileBytes = 16 * 1024 * 1024;
  static const int elements = 4096;
  static const int textures = 16;
  static const int textureBytes = 4 * 1024 * 1024;
  static const int textureSide = 1024;
  static const int animations = 64;
  static const int keyframesPerChannel = 256;
}

class BbmodelImportException implements Exception {
  const BbmodelImportException(this.message);

  final String message;

  @override
  String toString() => message;
}

/// What an import writes into the workspace.
class BbmodelImport {
  const BbmodelImport({required this.rig, required this.animations, required this.resources, required this.warnings});

  final JsonMap rig;
  final List<JsonMap> animations;

  /// Data-folder paths (`pack/assets/...`) to file bytes: item model
  /// definitions, item models and textures.
  final Map<String, Uint8List> resources;

  /// What the import changed or could not carry over.
  final List<String> warnings;
}

/// Where a rig's generated resource pack sources live in the data folder.
const String packSourceRoot = 'pack/assets';

/// The namespace of generated item models.
const String modelNamespace = 'animus';

String itemModelId(String rigId, String bone) => '$modelNamespace:${rigId.substring(rigId.indexOf(':') + 1)}/$bone';

String itemDefinitionPath(String itemModel) {
  final int colon = itemModel.indexOf(':');
  return '$packSourceRoot/${itemModel.substring(0, colon)}/items/${itemModel.substring(colon + 1)}.json';
}

String modelPath(String modelId) {
  final int colon = modelId.indexOf(':');
  final String namespace = colon < 0 ? 'minecraft' : modelId.substring(0, colon);
  return '$packSourceRoot/$namespace/models/${modelId.substring(colon + 1)}.json';
}

String texturePath(String textureId) {
  final int colon = textureId.indexOf(':');
  final String namespace = colon < 0 ? 'minecraft' : textureId.substring(0, colon);
  return '$packSourceRoot/$namespace/textures/${textureId.substring(colon + 1)}.png';
}

final RegExp _unsafe = RegExp(r'[^a-z0-9_-]+');

/// A Blockbench name as a bone id or path segment.
String sanitizeName(String name, String fallback) {
  String out = name.trim().toLowerCase().replaceAll(_unsafe, '_');
  out = out.replaceAll(RegExp(r'^[_-]+'), '');
  if (out.length > 48) out = out.substring(0, 48);
  return out.isEmpty ? fallback : out;
}

class _Unit {
  _Unit(this.id, this.parent, this.pivotPx, this.rotation);

  final String id;
  final String? parent;

  /// Blockbench pixels, Blockbench axes.
  final List<double> pivotPx;
  final List<double> rotation;
  final List<JsonMap> cubes = <JsonMap>[];
}

class _Texture {
  _Texture(this.id, this.uvWidth, this.uvHeight);

  final String? id;
  final double uvWidth;
  final double uvHeight;
}

BbmodelImport importBbmodel(Uint8List bytes, {required String rigId}) {
  if (!documentIdPattern.hasMatch(rigId)) throw BbmodelImportException('Rig id $rigId is not namespace:path.');
  if (bytes.length > BbmodelLimits.fileBytes) {
    throw BbmodelImportException('The file is ${bytes.length} bytes; the limit is ${BbmodelLimits.fileBytes}.');
  }
  final Object? decoded;
  try {
    decoded = jsonDecode(utf8.decode(bytes));
  } on FormatException catch (invalid) {
    throw BbmodelImportException('Not a .bbmodel: ${invalid.message}');
  }
  if (decoded is! JsonMap || decoded['elements'] is! List<Object?> || decoded['outliner'] is! List<Object?>) {
    throw const BbmodelImportException('Not a .bbmodel: it needs elements and outliner lists.');
  }
  return _Importer(decoded, rigId).run();
}

class _Importer {
  _Importer(this.model, this.rigId);

  final JsonMap model;
  final String rigId;
  final List<String> warnings = <String>[];
  final List<_Unit> units = <_Unit>[];
  final Map<String, String> boneByGroup = <String, String>{};
  final Map<String, JsonMap> elements = <String, JsonMap>{};
  final Map<String, JsonMap> groups = <String, JsonMap>{};
  final Map<String, Uint8List> resources = <String, Uint8List>{};
  final List<_Texture> textures = <_Texture>[];
  late final String rigPath = rigId.substring(rigId.indexOf(':') + 1);
  late final bool javaBlock = model['meta'] is JsonMap && (model['meta']! as JsonMap)['model_format'] == 'java_block';

  BbmodelImport run() {
    final List<Object?> rawElements = model['elements']! as List<Object?>;
    if (rawElements.length > BbmodelLimits.elements) {
      throw BbmodelImportException('${rawElements.length} elements; the limit is ${BbmodelLimits.elements}.');
    }
    int skipped = 0;
    for (final Object? element in rawElements) {
      if (element is! JsonMap || element['uuid'] is! String) continue;
      final Object? type = element['type'];
      if (type != null && type != 'cube') {
        skipped++;
        continue;
      }
      elements[element['uuid']! as String] = element;
    }
    if (skipped > 0) warnings.add('$skipped mesh or locator elements are not cubes and were skipped.');
    if (model['groups'] is List<Object?>) {
      for (final Object? group in model['groups']! as List<Object?>) {
        if (group is JsonMap && group['uuid'] is String) groups[group['uuid']! as String] = group;
      }
    }
    _textures();
    final _Unit loose = _Unit('root', null, const <double>[0, 0, 0], const <double>[0, 0, 0]);
    for (final Object? node in model['outliner']! as List<Object?>) {
      if (node is String) {
        final JsonMap? cube = elements[node];
        if (cube != null) loose.cubes.add(cube);
      } else if (node is JsonMap) {
        _group(node, null, 1);
      }
    }
    if (loose.cubes.isNotEmpty || units.isEmpty) {
      units.insert(0, _Unit(_free('root'), null, loose.pivotPx, loose.rotation)..cubes.addAll(loose.cubes));
    }
    final List<_Unit> all = <_Unit>[];
    for (final _Unit unit in List<_Unit>.of(units)) {
      all.add(unit);
      all.addAll(_freeRotations(unit));
    }
    if (all.length > maximumBones) {
      throw BbmodelImportException('The model needs ${all.length} bones (groups and freely rotated cubes); a rig holds $maximumBones.');
    }
    final JsonMap rig = <String, Object?>{
      'schemaVersion': 1,
      'revision': 1,
      'id': rigId,
      'units': 'blocks',
      'body': 'hidden',
      'bones': <Object?>[for (final _Unit unit in all) _bone(unit)],
    };
    final List<JsonMap> clips = _animations();
    if (clips.isNotEmpty) {
      rig['animations'] = <String, Object?>{
        for (final JsonMap clip in clips) (clip['id']! as String).substring(rigId.length + 1): clip['id'],
      };
    }
    return BbmodelImport(rig: rig, animations: clips, resources: resources, warnings: warnings);
  }

  String _free(String base) {
    final Set<String> used = <String>{for (final _Unit unit in units) unit.id};
    if (!used.contains(base)) return base;
    for (int suffix = 2;; suffix++) {
      if (!used.contains('$base-$suffix')) return '$base-$suffix';
    }
  }

  void _group(JsonMap node, String? parent, int depth) {
    final String? uuid = node['uuid'] is String ? node['uuid']! as String : null;
    final JsonMap group = <String, Object?>{...?groups[uuid], ...node};
    final _Unit unit;
    if (depth > maximumBoneDepth && parent != null) {
      warnings.add('Group ${group['name']} nests deeper than $maximumBoneDepth; its cubes joined $parent.');
      unit = units.firstWhere((_Unit candidate) => candidate.id == parent);
    } else {
      final List<double> origin = _vector(group['origin'], const <double>[0, 0, 0]);
      unit = _Unit(
        _free(sanitizeName('${group['name'] ?? ''}', 'bone')),
        parent,
        javaBlock ? <double>[origin[0] - 8, origin[1], origin[2] - 8] : origin,
        _vector(group['rotation'], const <double>[0, 0, 0]),
      );
      units.add(unit);
    }
    if (uuid != null) boneByGroup[uuid] = unit.id;
    final Object? children = group['children'];
    if (children is! List<Object?>) return;
    for (final Object? child in children) {
      if (child is String) {
        final JsonMap? cube = elements[child];
        if (cube != null) {
          unit.cubes.add(cube);
        } else if (groups.containsKey(child)) {
          _group(<String, Object?>{'uuid': child}, unit.id, depth + 1);
        }
      } else if (child is JsonMap) {
        _group(child, unit.id, depth + 1);
      }
    }
  }

  /// Cubes whose rotation an element cannot hold move to bones of their own.
  List<_Unit> _freeRotations(_Unit unit) {
    final List<_Unit> extra = <_Unit>[];
    for (final JsonMap cube in List<JsonMap>.of(unit.cubes)) {
      final List<double> rotation = _vector(cube['rotation'], const <double>[0, 0, 0]);
      if (_elementRotation(rotation) != null || rotation.every((double angle) => angle == 0)) continue;
      unit.cubes.remove(cube);
      final List<double> origin = _vector(cube['origin'], unit.pivotPx);
      final _Unit own = _Unit(
        _free(sanitizeName('${unit.id}-${cube['name'] ?? 'cube'}', '${unit.id}-cube')),
        unit.id,
        javaBlock ? <double>[origin[0] - 8, origin[1], origin[2] - 8] : origin,
        rotation,
      )..cubes.add(<String, Object?>{...cube, 'rotation': <Object?>[0, 0, 0]});
      units.add(own);
      extra.add(own);
    }
    return extra;
  }

  /// One axis in 22.5-degree steps within 45: `(axis, angle)`.
  (String, double)? _elementRotation(List<double> rotation) {
    final List<int> turned = <int>[
      for (int axis = 0; axis < 3; axis++)
        if (rotation[axis] != 0) axis,
    ];
    if (turned.length != 1) return null;
    final double angle = rotation[turned.single];
    if (angle.abs() > 45 || (angle / 22.5) != (angle / 22.5).roundToDouble()) return null;
    return (const <String>['x', 'y', 'z'][turned.single], angle);
  }

  void _textures() {
    final Object? raw = model['textures'];
    if (raw is! List<Object?>) return;
    if (raw.length > BbmodelLimits.textures) {
      throw BbmodelImportException('${raw.length} textures; the limit is ${BbmodelLimits.textures}.');
    }
    final JsonMap resolution = model['resolution'] is JsonMap ? model['resolution']! as JsonMap : const <String, Object?>{};
    final Set<String> names = <String>{};
    for (int index = 0; index < raw.length; index++) {
      final Object? texture = raw[index];
      final JsonMap json = texture is JsonMap ? texture : const <String, Object?>{};
      final double uvWidth = _number(json['uv_width'] ?? resolution['width'], 16);
      final double uvHeight = _number(json['uv_height'] ?? resolution['height'], 16);
      final Uint8List? png = _png(json['source'], '${json['name'] ?? 'texture $index'}');
      if (png == null) {
        textures.add(_Texture(null, uvWidth, uvHeight));
        continue;
      }
      String name = sanitizeName('${json['name'] ?? ''}'.replaceAll(RegExp(r'\.png$', caseSensitive: false), ''), 'texture');
      for (int suffix = 2; !names.add(name); suffix++) {
        name = '${sanitizeName('${json['name'] ?? ''}', 'texture')}-$suffix';
      }
      final String id = '$modelNamespace:item/$rigPath/$name';
      resources[texturePath(id)] = png;
      textures.add(_Texture(id, uvWidth, uvHeight));
    }
  }

  Uint8List? _png(Object? source, String name) {
    const String prefix = 'data:image/png;base64,';
    if (source is! String || !source.startsWith(prefix)) {
      warnings.add('Texture $name is not embedded in the file; its faces show the missing texture.');
      return null;
    }
    if ((source.length - prefix.length) * 3 ~/ 4 > BbmodelLimits.textureBytes) {
      throw BbmodelImportException('Texture $name is over ${BbmodelLimits.textureBytes} bytes.');
    }
    final Uint8List bytes;
    try {
      bytes = base64Decode(source.substring(prefix.length));
    } on FormatException {
      throw BbmodelImportException('Texture $name is not valid base64.');
    }
    final (int, int)? size = pngSize(bytes);
    if (size == null) throw BbmodelImportException('Texture $name is not a PNG.');
    if (size.$1 > BbmodelLimits.textureSide || size.$2 > BbmodelLimits.textureSide * 32) {
      throw BbmodelImportException('Texture $name is ${size.$1}x${size.$2}; the limit is ${BbmodelLimits.textureSide} wide.');
    }
    return bytes;
  }

  JsonMap _bone(_Unit unit) {
    final List<double> pivot = <double>[-unit.pivotPx[0] / 16, unit.pivotPx[1] / 16, -unit.pivotPx[2] / 16];
    final JsonMap bone = <String, Object?>{
      'id': unit.id,
      'parent': ?unit.parent,
      'pivot': _round(pivot),
      if (unit.rotation.any((double angle) => angle != 0)) 'rotation': _round(<double>[-unit.rotation[0], unit.rotation[1], -unit.rotation[2]]),
    };
    if (unit.cubes.isEmpty) return bone;
    final double scale = _writeModel(unit);
    final String item = itemModelId(rigId, unit.id);
    bone['visual'] = <String, Object?>{
      'type': 'item',
      'item': <String, Object?>{'id': 'minecraft:paper', 'components': '[item_model="$item"]'},
      if (scale != 1) 'scale': _round(<double>[1 / scale, 1 / scale, 1 / scale]),
    };
    return bone;
  }

  /// Writes the unit's item definition and model; returns the factor the
  /// cubes were shrunk by to fit the model bounds (-16..32).
  double _writeModel(_Unit unit) {
    final List<double> origin = unit.pivotPx;
    double extent = 0;
    for (final JsonMap cube in unit.cubes) {
      final double inflate = _number(cube['inflate'], 0);
      for (final String corner in const <String>['from', 'to']) {
        final List<double> point = _vector(cube[corner], const <double>[0, 0, 0]);
        for (int axis = 0; axis < 3; axis++) {
          final double offset = javaBlock && axis != 1 ? -8 : 0;
          extent = math.max(extent, (point[axis] + offset - origin[axis]).abs() + inflate);
        }
      }
    }
    final double scale = extent > 24 ? 24 / extent : 1;
    if (scale != 1) warnings.add('Bone ${unit.id} is larger than an item model allows; its model is scaled by ${_round(<double>[scale]).first} and its display by the inverse.');
    List<double> place(List<double> point) => <double>[
      for (int axis = 0; axis < 3; axis++) 8 + (point[axis] + (javaBlock && axis != 1 ? -8 : 0) - origin[axis]) * scale,
    ];
    final List<Object?> out = <Object?>[];
    final Set<int> used = <int>{};
    for (final JsonMap cube in unit.cubes) {
      final double inflate = _number(cube['inflate'], 0);
      final List<double> from = place(_vector(cube['from'], const <double>[0, 0, 0]));
      final List<double> to = place(_vector(cube['to'], const <double>[0, 0, 0]));
      final List<double> low = <double>[for (int axis = 0; axis < 3; axis++) math.min(from[axis], to[axis]) - inflate * scale];
      final List<double> high = <double>[for (int axis = 0; axis < 3; axis++) math.max(from[axis], to[axis]) + inflate * scale];
      final JsonMap faces = <String, Object?>{};
      final Object? rawFaces = cube['faces'];
      if (rawFaces is JsonMap) {
        for (final String face in const <String>['north', 'east', 'south', 'west', 'up', 'down']) {
          final Object? raw = rawFaces[face];
          if (raw is! JsonMap || raw['texture'] is! int) continue;
          final int index = raw['texture']! as int;
          if (index < 0 || index >= textures.length) continue;
          used.add(index);
          final _Texture texture = textures[index];
          final List<double> uv = _vector4(raw['uv']);
          faces[face] = <String, Object?>{
            'uv': _round(<double>[
              uv[0] * 16 / texture.uvWidth,
              uv[1] * 16 / texture.uvHeight,
              uv[2] * 16 / texture.uvWidth,
              uv[3] * 16 / texture.uvHeight,
            ]),
            'texture': '#$index',
            if (raw['rotation'] is num && (raw['rotation']! as num) != 0) 'rotation': (raw['rotation']! as num).round(),
            if (raw['tint'] is num && (raw['tint']! as num) >= 0) 'tintindex': (raw['tint']! as num).round(),
          };
        }
      }
      if (faces.isEmpty) continue;
      final JsonMap element = <String, Object?>{'from': _round(low), 'to': _round(high), 'faces': faces};
      if (cube['shade'] == false) element['shade'] = false;
      final (String, double)? rotation = _elementRotation(_vector(cube['rotation'], const <double>[0, 0, 0]));
      if (rotation != null && rotation.$2 != 0) {
        element['rotation'] = <String, Object?>{
          'angle': rotation.$2,
          'axis': rotation.$1,
          'origin': _round(place(_vector(cube['origin'], origin))),
        };
      }
      out.add(element);
    }
    final JsonMap textureMap = <String, Object?>{
      for (final int index in used.toList()..sort()) '$index': textures[index].id ?? 'minecraft:missingno',
    };
    if (textureMap.isNotEmpty) textureMap['particle'] = textureMap.values.first;
    final String item = itemModelId(rigId, unit.id);
    final String model = '$modelNamespace:item/$rigPath/${unit.id}';
    resources[itemDefinitionPath(item)] = _json(<String, Object?>{
      'model': <String, Object?>{'type': 'minecraft:model', 'model': model},
    });
    resources[modelPath(model)] = _json(<String, Object?>{'textures': textureMap, 'elements': out});
    return scale;
  }

  List<JsonMap> _animations() {
    final Object? raw = model['animations'];
    if (raw is! List<Object?>) return const <JsonMap>[];
    if (raw.length > BbmodelLimits.animations) {
      throw BbmodelImportException('${raw.length} animations; the limit is ${BbmodelLimits.animations}.');
    }
    final List<JsonMap> clips = <JsonMap>[];
    final Set<String> names = <String>{};
    final Map<String, int> approximated = <String, int>{};
    final Map<String, int> dropped = <String, int>{};
    int split = 0;
    int splitLost = 0;
    int sounds = 0;
    for (final Object? entry in raw) {
      if (entry is! JsonMap) continue;
      String name = sanitizeName('${entry['name'] ?? ''}'.replaceAll(RegExp(r'^animation\.[^.]*\.'), ''), 'animation');
      for (int suffix = 2; !names.add(name); suffix++) {
        name = '${sanitizeName('${entry['name'] ?? ''}', 'animation')}-$suffix';
      }
      final double seconds = _number(entry['length'], 1);
      final int duration = (seconds * 20).ceil().clamp(1, maximumDurationTicks);
      final String loop = switch (entry['loop']) {
        'loop' => 'loop',
        'hold' => 'hold',
        _ => 'once',
      };
      final List<Object?> tracks = <Object?>[];
      final List<Object?> events = <Object?>[];
      final Object? animators = entry['animators'];
      if (animators is JsonMap) {
        for (final MapEntry<String, Object?> animator in animators.entries) {
          final String? bone = boneByGroup[animator.key];
          final Object? body = animator.value;
          if (body is JsonMap && body['keyframes'] is List<Object?> && (animator.key == 'effects' || body['type'] == 'effect')) {
            for (final Object? key in body['keyframes']! as List<Object?>) {
              if (key is! JsonMap) continue;
              final String channel = '${key['channel'] ?? 'timeline'}';
              final String? sound = channel == 'sound' ? _vanillaSound(key) : null;
              if (sound == null || events.length >= maximumEvents) {
                dropped[channel] = (dropped[channel] ?? 0) + 1;
                continue;
              }
              events.add(<String, Object?>{
                'tick': (_number(key['time'], 0) * 20).round().clamp(0, duration),
                'capability': 'animus:play-sound',
                'arguments': <String, Object?>{'sound': sound},
              });
              sounds++;
            }
            continue;
          }
          if (bone == null || body is! JsonMap || body['keyframes'] is! List<Object?>) continue;
          for (final String channel in const <String>['rotation', 'position', 'scale']) {
            final List<JsonMap> keys = <JsonMap>[
              for (final Object? key in body['keyframes']! as List<Object?>)
                if (key is JsonMap && key['channel'] == channel) key,
            ];
            if (keys.isEmpty) continue;
            if (keys.length > BbmodelLimits.keyframesPerChannel) {
              throw BbmodelImportException('Animation $name has ${keys.length} $channel keyframes on $bone; the limit is ${BbmodelLimits.keyframesPerChannel}.');
            }
            keys.sort((JsonMap a, JsonMap b) => _number(a['time'], 0).compareTo(_number(b['time'], 0)));
            final List<Object?> frames = <Object?>[];
            num previous = -1;
            List<num> converted(List<double> value) => _round(switch (channel) {
              'rotation' => <double>[value[0], -value[1], -value[2]],
              'position' => <double>[value[0] / 16, value[1] / 16, -value[2] / 16],
              _ => value,
            });
            for (final JsonMap key in keys) {
              final num tick = tickValue(math.min(_number(key['time'], 0) * 20, duration.toDouble()));
              if (tick <= previous) continue;
              final String interpolation = '${key['interpolation'] ?? 'linear'}';
              final String easing = switch (interpolation) {
                'step' => 'hold',
                'catmullrom' || 'bezier' => 'ease_in_out',
                _ => 'linear',
              };
              if (easing == 'ease_in_out') approximated[interpolation] = (approximated[interpolation] ?? 0) + 1;
              final List<num> before = converted(_point(key, name, 0));
              final Object? points = key['data_points'];
              final List<num> after = points is List<Object?> && points.length > 1 ? converted(_point(key, name, 1)) : before;
              // A split key jumps from its first value to its second: the
              // first lands a thousandth of a tick before the second.
              if (!_same(before, after)) {
                split++;
                final num early = tickValue(tick - 0.001), late = tickValue(tick + 0.001);
                if (late <= duration) {
                  frames.add(<String, Object?>{'tick': tick, 'value': before});
                  previous = late;
                  frames.add(<String, Object?>{'tick': late, 'value': after, if (easing != 'linear') 'easing': easing});
                  continue;
                }
                if (early > previous) {
                  frames.add(<String, Object?>{'tick': early, 'value': before});
                } else {
                  splitLost++;
                }
              }
              previous = tick;
              frames.add(<String, Object?>{'tick': tick, 'value': after, if (easing != 'linear') 'easing': easing});
            }
            if (frames.length > maximumKeyframes) {
              throw BbmodelImportException('Animation $name has ${frames.length} $channel keyframes on $bone once split keys are kept; the limit is $maximumKeyframes.');
            }
            tracks.add(<String, Object?>{
              'bone': bone,
              'target': channel == 'position' ? 'translation' : channel,
              'blend': 'replace',
              'keyframes': frames,
            });
          }
        }
      }
      if (tracks.length > maximumTracks) {
        throw BbmodelImportException('Animation $name has ${tracks.length} tracks; the limit is $maximumTracks.');
      }
      clips.add(<String, Object?>{
        'schemaVersion': 1,
        'revision': 1,
        'id': '$rigId/$name',
        'rig': rigId,
        'durationTicks': duration,
        'loop': loop,
        'tracks': tracks,
        if (events.isNotEmpty) 'events': events,
      });
    }
    String keys(int count) => '$count keyframe${count == 1 ? '' : 's'}';
    for (final MapEntry<String, int> entry in approximated.entries) {
      warnings.add('${keys(entry.value)} use Blockbench ${entry.key} curves; they play as ease_in_out.');
    }
    if (split > 0) {
      warnings.add('${keys(split)} jump between two values; each became two keyframes a thousandth of a tick apart${splitLost > 0 ? ' ($splitLost kept only the second value)' : ''}.');
    }
    if (sounds > 0) warnings.add('${keys(sounds)} with vanilla sounds became play-sound events.');
    final int otherSounds = dropped['sound'] ?? 0;
    if (otherSounds > 0) warnings.add('${keys(otherSounds)} play sound files that are not vanilla sound ids; they were left out.');
    final int particles = dropped['particle'] ?? 0;
    if (particles > 0) warnings.add('${keys(particles)} spawn Bedrock particles, which Java has no match for; they were left out.');
    final int scripts = dropped['timeline'] ?? 0;
    if (scripts > 0) warnings.add('${keys(scripts)} run timeline scripts; they were left out.');
    return clips;
  }

  /// The sound of an effect key when it names a vanilla sound event such as
  /// `entity.zombie.ambient`, with or without `minecraft:`.
  static String? _vanillaSound(JsonMap key) {
    final Object? points = key['data_points'];
    final Object? first = points is List<Object?> && points.isNotEmpty ? points.first : null;
    final Object? effect = first is JsonMap ? first['effect'] : null;
    if (effect is! String) return null;
    final String id = effect.trim();
    return _soundId.hasMatch(id) ? (id.startsWith('minecraft:') ? id : 'minecraft:$id') : null;
  }

  static final RegExp _soundId = RegExp(r'^(minecraft:)?[a-z0-9_]+(\.[a-z0-9_]+)+$');

  static bool _same(List<num> a, List<num> b) => a.length == b.length && <int>[for (int i = 0; i < a.length; i++) i].every((int i) => a[i] == b[i]);

  /// Data point [index] of a key, falling back to the first: a split key
  /// keeps its value before the key in point 0 and after it in point 1.
  List<double> _point(JsonMap key, String animation, int index) {
    final Object? points = key['data_points'];
    final Object? point = points is List<Object?> && points.isNotEmpty ? (index < points.length ? points[index] : points.first) : null;
    if (point is! JsonMap) return <double>[0, 0, 0];
    return <double>[
      for (final String axis in const <String>['x', 'y', 'z']) _molang(point[axis], animation),
    ];
  }

  double _molang(Object? value, String animation) {
    if (value is num) return value.toDouble();
    if (value is String) {
      final double? parsed = double.tryParse(value.trim().isEmpty ? '0' : value.trim());
      if (parsed != null) return parsed;
      warnings.add('Animation $animation uses a Molang expression ($value); it plays as 0.');
    }
    return 0;
  }
}

double _number(Object? value, double fallback) => value is num ? value.toDouble() : fallback;

List<double> _vector(Object? value, List<double> fallback) {
  if (value is! List<Object?> || value.length < 3) return List<double>.of(fallback);
  return <double>[for (int axis = 0; axis < 3; axis++) _number(value[axis], fallback[axis])];
}

List<double> _vector4(Object? value) {
  if (value is! List<Object?> || value.length < 4) return <double>[0, 0, 16, 16];
  return <double>[for (int index = 0; index < 4; index++) _number(value[index], 0)];
}

/// Four decimals, whole numbers written as integers.
List<num> _round(List<double> values) => <num>[
  for (final double value in values) _round4(value),
];

num _round4(double value) {
  final double rounded = (value * 10000).roundToDouble() / 10000;
  return rounded == rounded.roundToDouble() ? rounded.round() : rounded;
}

Uint8List _json(JsonMap json) => Uint8List.fromList(utf8.encode(encodeDocument(json)));

/// A PNG's width and height from its IHDR chunk, or null for other bytes.
(int, int)? pngSize(Uint8List bytes) {
  const List<int> signature = <int>[0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  if (bytes.length < 24) return null;
  for (int index = 0; index < signature.length; index++) {
    if (bytes[index] != signature[index]) return null;
  }
  if (String.fromCharCodes(bytes.sublist(12, 16)) != 'IHDR') return null;
  final ByteData data = ByteData.sublistView(bytes);
  return (data.getUint32(16), data.getUint32(20));
}
