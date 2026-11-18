/// What the paired server's lists say about an NPC's ids: a body, world,
/// mob type or ridden entity type the server does not have is an error at
/// its field, so "Valid" means the server can run the NPC. A list the
/// server did not send, or one the editor only holds in part, checks
/// nothing. Pure Dart.
library;

import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../gui/gui_json.dart';
import '../suggest/completion_sources.dart';
import 'mount_seat.dart';
import 'npc_appearance.dart' show bodyLabel;

const String _combat = 'animus:combat';

/// The combat rule sets whose `entityTypes` name mob types.
const List<String> _ruleSets = <String>['targets', 'ignores', 'avoids'];

/// The lookup errors of [document]: an NPC's ids, nothing for other kinds.
List<Diagnostic> lookupDiagnostics(AnimusDocument document, CompletionSources sources) =>
    document.kind == DocumentKind.npc ? npcLookupDiagnostics(document.json, sources) : const <Diagnostic>[];

/// Errors for the ids in NPC [json] that a complete list in [sources] lacks.
List<Diagnostic> npcLookupDiagnostics(Object? json, CompletionSources sources) {
  if (json is! Map<String, Object?>) return const <Diagnostic>[];
  final bool entities = sources.complete.contains(RegistryKind.entity);
  final bool worlds = sources.complete.contains(RegistryKind.world);
  if (!entities && !worlds) return const <Diagnostic>[];
  final Set<String> entityIds = <String>{for (final String id in sources.registry(RegistryKind.entity)) stripMinecraft(id)};
  final Set<String> worldNames = <String>{for (final String name in sources.registry(RegistryKind.world)) stripMinecraft(name)};
  bool entity(String id) => entityIds.contains(stripMinecraft(id.trim()));
  bool world(String name) => worldNames.contains(stripMinecraft(name.trim()));
  Diagnostic error(List<JsonStep> steps, String message) => Diagnostic(DiagnosticSeverity.error, formatPath(steps), message, code: 'lookup');
  final JsonMap modules = asMap(json['modules']);
  final List<Diagnostic> found = <Diagnostic>[];
  if (entities) {
    final String? type = asString(json['type']);
    if (type != null && type.trim().isNotEmpty && !entity(type)) found.add(error(const <JsonStep>['type'], '${bodyLabel(type)} is not a body on this server.'));
  }
  if (worlds) {
    final String? at = asString(asMap(json['position'])['world']);
    if (at != null && at.trim().isNotEmpty && !world(at)) found.add(error(const <JsonStep>['position', 'world'], 'World $at is not loaded on the server.'));
    for (final MapEntry<String, Object?> anchor in asMap(json['anchors']).entries) {
      final String? other = asString(asMap(anchor.value)['world']);
      if (other != null && other.trim().isNotEmpty && !world(other)) {
        found.add(error(<JsonStep>['anchors', anchor.key, 'world'], 'World $other is not loaded on the server.'));
      }
    }
  }
  if (entities) {
    final JsonMap combat = asMap(modules[_combat]);
    for (final String rules in _ruleSets) {
      final List<Object?> types = asList(asMap(combat[rules])['entityTypes']);
      for (int index = 0; index < types.length; index++) {
        final Object? type = types[index];
        if (type is String && type.trim().isNotEmpty && !entity(type)) {
          found.add(error(<JsonStep>['modules', _combat, rules, 'entityTypes', index], '$type is not a mob type on this server.'));
        }
      }
    }
    final String? ridden = asString(asMap(modules[mountModuleKey])['entity']);
    if (ridden != null && ridden.trim().isNotEmpty && !isEntityUuid(ridden) && !entity(ridden)) {
      found.add(error(const <JsonStep>['modules', mountModuleKey, 'entity'], '$ridden is not an entity type on this server.'));
    }
  }
  return found;
}
