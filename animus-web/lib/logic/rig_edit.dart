/// Edits to rig and animation documents as JSON values. Every function takes
/// a document and returns a changed copy, keeping keys it does not know, and
/// keeps the rules of schema/rig-v1.schema.json: parents before children,
/// at most 64 bones, depth at most 16, unique ids.
library;

import 'dart:convert';

import 'rig_sampler.dart';
import 'rig_validation.dart';

typedef JsonMap = Map<String, Object?>;

const int maximumBones = 64;
const int maximumKeyframes = 256;
const int maximumTracks = 64;
const int maximumDurationTicks = 1200;

final RegExp boneIdPattern = RegExp(r'^[a-z0-9][a-z0-9_-]{0,63}$');
final RegExp documentIdPattern = RegExp(r'^[a-z0-9_-]{1,64}:[a-z0-9_-]{1,64}(/[a-z0-9_-]{1,64}){0,14}$');

/// The easings in schema/animation-v1.schema.json, bezier aside.
const List<String> namedEasings = <String>['linear', 'hold', 'ease_in', 'ease_out', 'ease_in_out', 'back_out'];

class RigEditException implements Exception {
  const RigEditException(this.message);

  final String message;

  @override
  String toString() => message;
}

JsonMap cloneJson(JsonMap json) => jsonDecode(jsonEncode(json)) as JsonMap;

/// The rig's text as the editor writes it: two-space indented JSON.
String encodeDocument(JsonMap json) => '${const JsonEncoder.withIndent('  ').convert(json)}\n';

List<JsonMap> bonesOf(JsonMap rig) {
  final Object? bones = rig['bones'];
  return <JsonMap>[
    if (bones is List<Object?>)
      for (final Object? bone in bones)
        if (bone is JsonMap) bone,
  ];
}

String? parentOf(JsonMap bone) => bone['parent'] is String ? bone['parent']! as String : null;

JsonMap? boneById(JsonMap rig, String id) {
  for (final JsonMap bone in bonesOf(rig)) {
    if (bone['id'] == id) return bone;
  }
  return null;
}

/// Depth of [id], 1 for a bone without a parent.
int boneDepth(JsonMap rig, String id) {
  int depth = 0;
  String? at = id;
  final Set<String> seen = <String>{};
  while (at != null && seen.add(at)) {
    final JsonMap? bone = boneById(rig, at);
    if (bone == null) break;
    depth++;
    at = parentOf(bone);
  }
  return depth;
}

/// [id] and every bone under it, in document order.
List<String> subtree(JsonMap rig, String id) {
  final Set<String> inside = <String>{id};
  for (final JsonMap bone in bonesOf(rig)) {
    final String? parent = parentOf(bone);
    if (parent != null && inside.contains(parent)) inside.add(bone['id']! as String);
  }
  return <String>[
    for (final JsonMap bone in bonesOf(rig))
      if (inside.contains(bone['id'])) bone['id']! as String,
  ];
}

int _subtreeHeight(JsonMap rig, String id) {
  int height = 0;
  final int base = boneDepth(rig, id);
  for (final String inside in subtree(rig, id)) {
    final int depth = boneDepth(rig, inside) - base + 1;
    if (depth > height) height = depth;
  }
  return height;
}

void _checkBoneId(String id) {
  if (!boneIdPattern.hasMatch(id)) {
    throw RigEditException('Bone ids are lower case letters, digits, - and _, starting with a letter or digit: $id');
  }
}

/// A free id from [base]: `base`, `base-2`, `base-3`, ...
String freeBoneId(JsonMap rig, String base) {
  final Set<String> used = <String>{for (final JsonMap bone in bonesOf(rig)) '${bone['id']}'};
  if (!used.contains(base)) return base;
  for (int suffix = 2;; suffix++) {
    final String candidate = '$base-$suffix';
    if (!used.contains(candidate)) return candidate;
  }
}

/// Adds a pure pivot bone under [parent] (or at the rig origin), placed after
/// the parent's subtree so parents stay before children. The pivot defaults
/// to the parent's.
JsonMap addBone(JsonMap rig, String id, {String? parent, List<double>? pivot}) {
  _checkBoneId(id);
  final List<JsonMap> bones = bonesOf(rig);
  if (bones.length >= maximumBones) throw const RigEditException('A rig holds at most $maximumBones bones.');
  if (boneById(rig, id) != null) throw RigEditException('Bone $id already exists.');
  final JsonMap out = cloneJson(rig);
  final List<Object?> list = out['bones'] is List<Object?> ? out['bones']! as List<Object?> : <Object?>[];
  out['bones'] = list;
  int insertAt = list.length;
  List<Object?> at = const <Object?>[0, 0, 0];
  if (parent != null) {
    final JsonMap? parentBone = boneById(rig, parent);
    if (parentBone == null) throw RigEditException('No bone named $parent.');
    if (boneDepth(rig, parent) + 1 > maximumBoneDepth) throw const RigEditException('Bones nest at most $maximumBoneDepth deep.');
    final List<String> under = subtree(rig, parent);
    insertAt = list.indexWhere((Object? bone) => bone is JsonMap && bone['id'] == under.last) + 1;
    if (parentBone['pivot'] is List<Object?>) at = parentBone['pivot']! as List<Object?>;
  }
  list.insert(insertAt, <String, Object?>{
    'id': id,
    'parent': ?parent,
    'pivot': pivot ?? List<Object?>.of(at),
  });
  return out;
}

/// Renames a bone and every reference inside the rig (parents, sockets).
/// Animations are renamed with [renameTrackBone].
JsonMap renameBone(JsonMap rig, String from, String to) {
  if (from == to) return rig;
  _checkBoneId(to);
  if (boneById(rig, from) == null) throw RigEditException('No bone named $from.');
  if (boneById(rig, to) != null) throw RigEditException('Bone $to already exists.');
  final JsonMap out = cloneJson(rig);
  for (final JsonMap bone in bonesOf(out)) {
    if (bone['id'] == from) bone['id'] = to;
    if (bone['parent'] == from) bone['parent'] = to;
  }
  final Object? sockets = out['sockets'];
  if (sockets is JsonMap) {
    for (final String slot in sockets.keys.toList()) {
      if (sockets[slot] == from) sockets[slot] = to;
    }
  }
  return out;
}

/// Hangs [id] (with its subtree) from [parent], or from the rig origin when
/// null. Pivots are rig-space, so the rest pose does not move.
JsonMap reparentBone(JsonMap rig, String id, String? parent) {
  if (boneById(rig, id) == null) throw RigEditException('No bone named $id.');
  final List<String> moving = subtree(rig, id);
  if (parent != null) {
    if (boneById(rig, parent) == null) throw RigEditException('No bone named $parent.');
    if (moving.contains(parent)) throw RigEditException('$parent is $id or under it.');
    if (boneDepth(rig, parent) + _subtreeHeight(rig, id) > maximumBoneDepth) {
      throw const RigEditException('Bones nest at most $maximumBoneDepth deep.');
    }
  }
  final JsonMap out = cloneJson(rig);
  final List<Object?> list = out['bones']! as List<Object?>;
  final List<Object?> taken = <Object?>[
    for (final Object? bone in list)
      if (bone is JsonMap && moving.contains(bone['id'])) bone,
  ];
  list.removeWhere(taken.contains);
  final JsonMap moved = taken.first! as JsonMap;
  if (parent == null) {
    moved.remove('parent');
    list.addAll(taken);
  } else {
    moved['parent'] = parent;
    final List<String> under = subtree(out, parent);
    final int at = list.indexWhere((Object? bone) => bone is JsonMap && bone['id'] == under.last) + 1;
    list.insertAll(at, taken);
  }
  return out;
}

/// Removes a bone. Its children hang from its parent instead; sockets that
/// named it are dropped. A rig keeps at least one bone.
JsonMap deleteBone(JsonMap rig, String id) {
  final JsonMap? bone = boneById(rig, id);
  if (bone == null) throw RigEditException('No bone named $id.');
  if (bonesOf(rig).length <= 1) throw const RigEditException('A rig needs at least one bone.');
  final String? parent = parentOf(bone);
  final JsonMap out = cloneJson(rig);
  final List<Object?> list = out['bones']! as List<Object?>;
  list.removeWhere((Object? candidate) => candidate is JsonMap && candidate['id'] == id);
  for (final JsonMap child in bonesOf(out)) {
    if (child['parent'] != id) continue;
    if (parent == null) {
      child.remove('parent');
    } else {
      child['parent'] = parent;
    }
  }
  final Object? sockets = out['sockets'];
  if (sockets is JsonMap) {
    sockets.removeWhere((String _, Object? value) => value == id);
    if (sockets.isEmpty) out.remove('sockets');
  }
  return out;
}

/// Replaces one bone with [change] applied to a copy of it.
JsonMap updateBone(JsonMap rig, String id, void Function(JsonMap bone) change) {
  final JsonMap out = cloneJson(rig);
  final JsonMap? bone = boneById(out, id);
  if (bone == null) throw RigEditException('No bone named $id.');
  change(bone);
  return out;
}

/// A visual of [type] for a bone, keeping the old transform fields.
JsonMap visualOfType(String type, JsonMap? previous) {
  final JsonMap visual = <String, Object?>{'type': type};
  if (type == 'none') return visual;
  for (final String field in const <String>['translation', 'rotation', 'scale']) {
    if (previous != null && previous[field] != null) visual[field] = previous[field];
  }
  switch (type) {
    case 'item':
      visual['item'] = previous?['item'] ?? <String, Object?>{'id': 'minecraft:stick'};
    case 'block':
      visual['block'] = previous?['block'] ?? 'minecraft:stone';
    case 'text':
      visual['text'] = previous?['text'] ?? 'Text';
      visual['billboard'] = previous?['billboard'] ?? 'center';
  }
  return visual;
}

// --- animations --------------------------------------------------------------

List<JsonMap> tracksOf(JsonMap animation) {
  final Object? tracks = animation['tracks'];
  return <JsonMap>[
    if (tracks is List<Object?>)
      for (final Object? track in tracks)
        if (track is JsonMap) track,
  ];
}

List<JsonMap> keyframesOf(JsonMap track) {
  final Object? keys = track['keyframes'];
  return <JsonMap>[
    if (keys is List<Object?>)
      for (final Object? key in keys)
        if (key is JsonMap) key,
  ];
}

JsonMap renameTrackBone(JsonMap animation, String from, String to) {
  final JsonMap out = cloneJson(animation);
  for (final JsonMap track in tracksOf(out)) {
    if (track['bone'] == from) track['bone'] = to;
  }
  return out;
}

JsonMap dropTrackBone(JsonMap animation, String bone) {
  final JsonMap out = cloneJson(animation);
  (out['tracks']! as List<Object?>).removeWhere((Object? track) => track is JsonMap && track['bone'] == bone);
  return out;
}

double _number(Object? value) => value is num ? value.toDouble() : 0;

/// Keyframe ticks keep up to three decimals so text round-trips.
num tickValue(double tick) {
  final double rounded = (tick * 1000).roundToDouble() / 1000;
  return rounded == rounded.roundToDouble() ? rounded.round() : rounded;
}

/// Sets the value at [tick] on the first track for [bone] and [target],
/// creating the track (blend replace) when there is none. A keyframe already
/// at that tick keeps its easing unless [easing] is given.
JsonMap setKeyframe(JsonMap animation, String bone, TrackTarget target, double tick, Object value, {String? easing}) {
  final double duration = _number(animation['durationTicks']);
  if (tick < 0 || tick > duration) throw RigEditException('Tick $tick is outside 0..${tickValue(duration)}.');
  if (target == TrackTarget.visible ? value is! bool : value is! List<Object?>) {
    throw RigEditException('${target.name} keyframes take ${target == TrackTarget.visible ? 'true or false' : 'three numbers'}.');
  }
  final JsonMap out = cloneJson(animation);
  final List<Object?> tracks = out['tracks'] is List<Object?> ? out['tracks']! as List<Object?> : <Object?>[];
  out['tracks'] = tracks;
  JsonMap? track;
  for (final Object? candidate in tracks) {
    if (candidate is JsonMap && candidate['bone'] == bone && candidate['target'] == target.name) {
      track = candidate;
      break;
    }
  }
  if (track == null) {
    if (tracks.length >= maximumTracks) throw const RigEditException('An animation holds at most $maximumTracks tracks.');
    track = <String, Object?>{'bone': bone, 'target': target.name, 'blend': 'replace', 'keyframes': <Object?>[]};
    tracks.add(track);
  }
  final List<Object?> keys = track['keyframes']! as List<Object?>;
  final num at = tickValue(tick);
  int index = 0;
  while (index < keys.length && _number((keys[index]! as JsonMap)['tick']) < at) {
    index++;
  }
  final bool replacing = index < keys.length && _number((keys[index]! as JsonMap)['tick']) == at;
  if (!replacing && keys.length >= maximumKeyframes) throw const RigEditException('A track holds at most $maximumKeyframes keyframes.');
  final JsonMap key = replacing ? keys[index]! as JsonMap : <String, Object?>{'tick': at};
  key['value'] = value is List<Object?> ? <Object?>[for (final Object? component in value) _clean(_number(component))] : value;
  if (easing != null) _setEasing(key, easing);
  if (!replacing) keys.insert(index, key);
  return out;
}

num _clean(double value) {
  final double rounded = (value * 100000).roundToDouble() / 100000;
  return rounded == rounded.roundToDouble() ? rounded.round() : rounded;
}

void _setEasing(JsonMap key, String easing) {
  Easing.parse(easing);
  if (easing == 'linear') {
    key.remove('easing');
  } else {
    key['easing'] = easing;
  }
}

JsonMap setKeyframeEasing(JsonMap animation, int track, int keyframe, String easing) {
  final JsonMap out = cloneJson(animation);
  _setEasing(keyframesOf(tracksOf(out)[track])[keyframe], easing);
  return out;
}

/// Removes a keyframe; a track left without keyframes is removed too.
JsonMap removeKeyframe(JsonMap animation, int track, int keyframe) {
  final JsonMap out = cloneJson(animation);
  final List<Object?> tracks = out['tracks']! as List<Object?>;
  final List<Object?> keys = (tracks[track]! as JsonMap)['keyframes']! as List<Object?>;
  keys.removeAt(keyframe);
  if (keys.isEmpty) tracks.removeAt(track);
  return out;
}

/// Moves a keyframe to [tick] when no other keyframe of its track is there.
JsonMap moveKeyframe(JsonMap animation, int track, int keyframe, double tick) {
  final JsonMap out = cloneJson(animation);
  final List<Object?> keys = (tracksOf(out)[track])['keyframes']! as List<Object?>;
  final num at = tickValue(tick);
  if (at < 0 || at > _number(out['durationTicks'])) throw RigEditException('Tick $at is outside the animation.');
  final JsonMap key = keys[keyframe]! as JsonMap;
  for (final Object? other in keys) {
    if (!identical(other, key) && _number((other! as JsonMap)['tick']) == at) throw RigEditException('Another keyframe is at tick $at.');
  }
  key['tick'] = at;
  keys.sort((Object? a, Object? b) => _number((a! as JsonMap)['tick']).compareTo(_number((b! as JsonMap)['tick'])));
  return out;
}

/// A new, empty animation for [rigId].
JsonMap newAnimation(String id, String rigId, {int durationTicks = 20, String loop = 'loop'}) => <String, Object?>{
  'schemaVersion': 1,
  'revision': 1,
  'id': id,
  'rig': rigId,
  'durationTicks': durationTicks,
  'loop': loop,
  'tracks': <Object?>[],
};

/// The value of one track at [time] clip ticks, or null without keyframes.
Object? trackValueAt(JsonMap track, double time) {
  final List<JsonMap> keys = keyframesOf(track);
  if (keys.isEmpty) return null;
  final Clip clip = Clip.fromJson(<String, Object?>{
    'id': 'track:preview',
    'durationTicks': 1200,
    'tracks': <Object?>[
      <String, Object?>{...track, 'bone': 'bone', 'blend': 'replace'},
    ],
  });
  final Map<String, BonePose> poses = sampleRig(
    const Rig('track:preview', <Bone>[Bone('bone', null, <double>[0, 0, 0], <double>[0, 0, 0], null)]),
    <Layer>[Layer(clip)],
    0,
    time,
  );
  final BonePose pose = poses['bone']!;
  return switch (TrackTarget.values.byName(track['target']! as String)) {
    TrackTarget.rotation => pose.rotation,
    TrackTarget.translation => pose.translation,
    TrackTarget.scale => pose.scale,
    TrackTarget.visible => pose.visible,
  };
}
