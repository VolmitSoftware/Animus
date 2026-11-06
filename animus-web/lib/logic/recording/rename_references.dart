/// References that follow a renamed recording or trait group: the NPC
/// navigation source and recording steps in inline and reusable routes for
/// a recording, and the NPC `groups` lists for a group.
library;

import '../../model/document_kind.dart';
import '../gui/gui_json.dart';

const String _navigation = 'animus:navigation';

/// [other] (a document of [otherKind]) with every reference to [from] of
/// [kind] pointing at [to], or null when it names none.
JsonMap? renamedReferences(DocumentKind kind, String from, String to, DocumentKind otherKind, JsonMap other) {
  if (kind == DocumentKind.group && otherKind == DocumentKind.npc) {
    final List<Object?> groups = asList(other['groups']);
    if (!groups.contains(from)) return null;
    return <String, Object?>{
      ...other,
      'groups': <Object?>[for (final Object? group in groups) group == from ? to : group],
    };
  }
  if (kind != DocumentKind.recording) return null;
  if (otherKind == DocumentKind.route) {
    final List<Object?>? points = _renamedSteps(asList(other['points']), from, to);
    return points == null ? null : <String, Object?>{...other, 'points': points};
  }
  if (otherKind != DocumentKind.npc) return null;
  final JsonMap modules = asMap(other['modules']);
  final Object? raw = modules[_navigation];
  if (raw is! Map<String, Object?>) return null;
  JsonMap navigation = raw;
  bool changed = false;
  if (navigation['recording'] == from) {
    navigation = <String, Object?>{...navigation, 'recording': to};
    changed = true;
  }
  final List<Object?>? points = _renamedSteps(asList(navigation['points']), from, to);
  if (points != null) {
    navigation = <String, Object?>{...navigation, 'points': points};
    changed = true;
  }
  if (!changed) return null;
  return <String, Object?>{
    ...other,
    'modules': <String, Object?>{...modules, _navigation: navigation},
  };
}

bool _names(Object? point, String id) => point is Map<String, Object?> && point['recording'] == id;

List<Object?>? _renamedSteps(List<Object?> points, String from, String to) {
  if (!points.any((Object? point) => _names(point, from))) return null;
  return <Object?>[
    for (final Object? point in points) _names(point, from) ? <String, Object?>{...asMap(point), 'recording': to} : point,
  ];
}
