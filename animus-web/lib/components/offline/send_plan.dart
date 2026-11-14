/// What Send to server sends and how it reads, as plain data so it is unit
/// tested: the documents Apply sends, the line under a changed document and
/// the summary of a compare.
library;

import '../../logic/offline/sync_plan.dart';
import '../../model/animus_document.dart';

/// What Apply sends from [plan]: every new document, and a changed one when
/// [replaceChanged] says so or its own entry in [picked] does (Advanced
/// picks documents one by one).
List<PushEntry> sendSelection(PushPlan plan, {required bool replaceChanged, Map<String, bool> picked = const <String, bool>{}}) => <PushEntry>[
      for (final PushEntry entry in plan.entries)
        if (entry.state != SyncState.same && entry.problem == null && (picked[entry.key] ?? (entry.state == SyncState.added || replaceChanged))) entry,
    ];

/// Plain words for the top-level fields people know; any other field makes
/// the line a count.
const Map<String, String> fieldWords = <String, String>{
  'name': 'name',
  'title': 'title',
  'type': 'body',
  'skin': 'skin',
  'position': 'position',
  'equipment': 'equipment',
  'behaviors': 'behaviors',
  'hologram': 'hologram',
  'route': 'route',
  'combat': 'combat',
  'spawned': 'spawned',
  'offers': 'offers',
  'pages': 'pages',
  'lines': 'lines',
  'frames': 'frames',
  'stages': 'stages',
  'description': 'description',
};

/// What differs between two copies, in a few words: "Name differs",
/// "Name and position differ", or "4 settings differ".
String differenceLine(List<String> fields) {
  if (fields.isEmpty) return 'Same content';
  final List<String> words = <String>[for (final String field in fields) ?fieldWords[field]];
  if (words.length != fields.length || words.length > 3) return '${fields.length} settings differ';
  final String joined = switch (words.length) {
    1 => words.single,
    2 => '${words[0]} and ${words[1]}',
    _ => '${words[0]}, ${words[1]} and ${words[2]}',
  };
  return '${joined[0].toUpperCase()}${joined.substring(1)} ${words.length == 1 ? 'differs' : 'differ'}';
}

/// "3 new, 2 changed, 40 same".
String compareLine(PushPlan plan) =>
    '${plan.where(SyncState.added).length} new, ${plan.where(SyncState.conflict).length} changed, ${plan.where(SyncState.same).length} same';

/// Errors among the server's diagnostics.
int errorCount(Map<String, List<Diagnostic>> diagnostics) => diagnostics.values.fold<int>(
      0,
      (int total, List<Diagnostic> list) => total + list.where((Diagnostic diagnostic) => diagnostic.severity == DiagnosticSeverity.error).length,
    );

/// "1 document", "3 documents".
String documentCount(int count) => count == 1 ? '1 document' : '$count documents';

/// The sync layer's failure sentences in the page's words (Apply, not push;
/// the server's documents, not its catalog).
String plainSyncProblem(String message) {
  String text = message;
  const List<(String, String)> words = <(String, String)>[
    ('Press Check the earlier push once connected.', 'Check it once connected.'),
    ('Press Check the earlier push soon.', 'Check it soon.'),
    ('Press Check the earlier push.', 'Check it first.'),
    ('Tick documents to push.', 'Pick documents to apply.'),
    ('The push went out but its answer', 'The changes went out but the answer'),
    ('refused the push', 'refused the changes'),
    ('earlier push', 'earlier apply'),
    ('push again', 'apply again'),
    ('load the server catalog', "read the server's documents"),
  ];
  for (final (String from, String to) in words) {
    text = text.replaceAll(from, to);
  }
  return text;
}
