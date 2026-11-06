/// Rig and animation rules that a JSON Schema cannot express. The Java rig
/// validator applies the same rules (dev/editor-contracts.md).
library;

import '../model/animus_document.dart';

const int maximumBoneDepth = 16;

List<Diagnostic> validateRigStructure(Object? json) {
  if (json is! Map<String, Object?> || json['bones'] is! List<Object?>) return const <Diagnostic>[];
  final List<Diagnostic> problems = <Diagnostic>[];
  final Map<String, int> depth = <String, int>{};
  final List<Object?> bones = json['bones']! as List<Object?>;
  for (int index = 0; index < bones.length; index++) {
    final Object? bone = bones[index];
    if (bone is! Map<String, Object?> || bone['id'] is! String) continue;
    final String id = bone['id']! as String;
    final String path = '\$.bones[$index]';
    if (depth.containsKey(id)) {
      problems.add(Diagnostic(DiagnosticSeverity.error, '$path.id', 'bone id $id is used twice', code: 'rig-duplicate-bone'));
      continue;
    }
    final Object? parent = bone['parent'];
    if (parent is String) {
      final int? parentDepth = depth[parent];
      if (parentDepth == null) {
        problems.add(Diagnostic(DiagnosticSeverity.error, '$path.parent', 'parent $parent must be an earlier bone', code: 'rig-parent-order'));
        depth[id] = 1;
        continue;
      }
      if (parentDepth + 1 > maximumBoneDepth) {
        problems.add(Diagnostic(DiagnosticSeverity.error, '$path.parent', 'bones nest at most $maximumBoneDepth deep', code: 'rig-depth'));
      }
      depth[id] = parentDepth + 1;
    } else {
      depth[id] = 1;
    }
  }
  final Object? sockets = json['sockets'];
  if (sockets is Map<String, Object?>) {
    for (final MapEntry<String, Object?> socket in sockets.entries) {
      if (socket.value is String && !depth.containsKey(socket.value)) {
        problems.add(Diagnostic(DiagnosticSeverity.error, '\$.sockets.${socket.key}', 'no bone named ${socket.value}', code: 'rig-socket'));
      }
    }
  }
  return problems;
}

/// [rig] is the parsed rig the animation names, when the workspace has it.
List<Diagnostic> validateAnimationStructure(Object? json, Object? rig) {
  if (json is! Map<String, Object?> || json['tracks'] is! List<Object?>) return const <Diagnostic>[];
  final List<Diagnostic> problems = <Diagnostic>[];
  final num duration = json['durationTicks'] is num ? json['durationTicks']! as num : double.infinity;
  final Set<String>? bones = rig is Map<String, Object?> && rig['bones'] is List<Object?>
      ? <String>{
          for (final Object? bone in rig['bones']! as List<Object?>)
            if (bone is Map<String, Object?> && bone['id'] is String) bone['id']! as String,
        }
      : null;
  final List<Object?> tracks = json['tracks']! as List<Object?>;
  for (int index = 0; index < tracks.length; index++) {
    final Object? track = tracks[index];
    if (track is! Map<String, Object?>) continue;
    final String path = '\$.tracks[$index]';
    final Object? bone = track['bone'];
    if (bones != null && bone is String && !bones.contains(bone)) {
      problems.add(Diagnostic(DiagnosticSeverity.error, '$path.bone', 'rig ${json['rig']} has no bone $bone', code: 'animation-bone'));
    }
    final Object? keyframes = track['keyframes'];
    if (keyframes is! List<Object?>) continue;
    num previous = -1;
    for (int key = 0; key < keyframes.length; key++) {
      final Object? keyframe = keyframes[key];
      if (keyframe is! Map<String, Object?> || keyframe['tick'] is! num) continue;
      final num tick = keyframe['tick']! as num;
      if (tick <= previous) {
        problems.add(Diagnostic(DiagnosticSeverity.error, '$path.keyframes[$key].tick', 'keyframe ticks must strictly increase', code: 'animation-order'));
      }
      if (tick > duration) {
        problems.add(Diagnostic(DiagnosticSeverity.error, '$path.keyframes[$key].tick', 'tick $tick is after durationTicks $duration', code: 'animation-range'));
      }
      previous = tick;
    }
  }
  return problems;
}
