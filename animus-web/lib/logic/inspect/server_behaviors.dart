/// The `server-behaviors.json` list against the workspace: which listed ids
/// are server behaviors here, which are missing, and the overview count.
library;

import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../gui/gui_json.dart';

/// The most ids the list may hold, as the server's `ServerBehaviorList`.
const int serverBehaviorsMaximum = 256;

/// Behavior ids in [documents], each with whether it has `"server": true`.
Map<String, bool> workspaceBehaviors(List<AnimusDocument> documents) => <String, bool>{
      for (final AnimusDocument document in documents)
        if (document.kind == DocumentKind.behavior) document.id: asMap(document.json)['server'] == true,
    };

/// Why a listed [id] may not run, in words; null when it is a server
/// behavior here.
String? serverBehaviorNote(String id, Map<String, bool> behaviors) => switch (behaviors[id]) {
      true => null,
      false => 'Server behavior is off, so it will not run.',
      null => 'Not in this workspace. Runs if the server has it.',
    };

/// Warnings for the list [json] against [documents]: listed NPC behaviors.
/// Ids missing from the workspace are not flagged: the server may have
/// them.
List<Diagnostic> serverBehaviorsDiagnostics(Object? json, List<AnimusDocument> documents) {
  final Map<String, bool> behaviors = workspaceBehaviors(documents);
  final List<Object?> listed = asList(asMap(json)['behaviors']);
  return <Diagnostic>[
    for (int index = 0; index < listed.length; index++)
      if (listed[index] is String && behaviors[listed[index]] == false)
        Diagnostic(DiagnosticSeverity.warning, indexPath(r'$.behaviors', index), '${listed[index]} does not have "server": true, so the server will not run it.', code: 'not-server-behavior'),
  ];
}

/// The ids the workspace's list holds, or empty when there is no list.
List<String> listedServerBehaviors(List<AnimusDocument> documents) {
  for (final AnimusDocument document in documents) {
    if (document.kind == DocumentKind.serverBehaviors) {
      return <String>[for (final Object? id in asList(asMap(document.json)['behaviors'])) if (id is String) id];
    }
  }
  return const <String>[];
}
