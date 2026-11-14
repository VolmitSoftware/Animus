/// Diagnostics in an admin's words: the field's label in place of its JSON
/// path, and each problem said once.
library;

import '../model/animus_document.dart';
import 'gui/gui_json.dart';
import 'offline/document_fixes.dart' show parseJsonPath;

/// The field a diagnostic path names, as a label: `$.uuid` reads "UUID",
/// `$.lines[2]` "Lines item 3"; null for the document itself.
String? diagnosticField(String path) {
  final List<JsonStep>? steps = parseJsonPath(path);
  if (steps == null || steps.isEmpty) return null;
  final JsonStep last = steps.last;
  if (last is String) return humanizeKey(last);
  final int index = last as int;
  for (int at = steps.length - 2; at >= 0; at--) {
    final JsonStep step = steps[at];
    if (step is String) return '${humanizeKey(step)} item ${index + 1}';
  }
  return 'Item ${index + 1}';
}

/// One line for [diagnostic]: "UUID is required", "Step height: expected
/// at most 1.5", or the message alone for the whole document.
String diagnosticSentence(Diagnostic diagnostic) {
  final String? field = diagnosticField(diagnostic.path);
  final String message = diagnostic.message;
  if (field == null) return message.isEmpty ? message : '${message[0].toUpperCase()}${message.substring(1)}';
  return message.startsWith('is ') ? '$field $message' : '$field: $message';
}

/// [diagnostic] as the mode shows it: Simple keeps a config allow-list
/// note to what it means, since its fix names ids and config keys.
String diagnosticSentenceIn(Diagnostic diagnostic, {required bool advanced}) {
  final String sentence = diagnosticSentence(diagnostic);
  if (advanced || diagnostic.code != 'command-allow-list') return sentence;
  final int fix = sentence.lastIndexOf(': ');
  return fix < 0 ? sentence : '${sentence.substring(0, fix)}. Edit in Advanced to see how.';
}

bool _same(Diagnostic a, Diagnostic b) => a.path == b.path && a.message == b.message;

/// The server's diagnostics the browser does not already show in [local].
List<Diagnostic> serverOnly(List<Diagnostic> local, List<Diagnostic> server) => <Diagnostic>[
      for (final Diagnostic diagnostic in server)
        if (!local.any((Diagnostic mine) => _same(mine, diagnostic))) diagnostic,
    ];

/// [all] with each path and message kept once, first one wins.
List<Diagnostic> withoutRepeats(List<Diagnostic> all) {
  final List<Diagnostic> kept = <Diagnostic>[];
  for (final Diagnostic diagnostic in all) {
    if (!kept.any((Diagnostic seen) => _same(seen, diagnostic))) kept.add(diagnostic);
  }
  return kept;
}

/// [all] with one problem at the same field of several list items
/// (`$.events[3].item`, `$.events[7].item`) said once, with how many items
/// have it: "Item is required (5 events)". The first one's path stays, so a
/// fix or a jump still finds it.
List<Diagnostic> groupRepeats(List<Diagnostic> all) {
  final Map<String, List<Diagnostic>> groups = <String, List<Diagnostic>>{};
  for (final Diagnostic diagnostic in all) {
    final String key = '${diagnostic.severity.name}|${diagnostic.message}|${diagnostic.path.replaceAll(RegExp(r'\[\d+\]'), '[*]')}';
    (groups[key] ??= <Diagnostic>[]).add(diagnostic);
  }
  return <Diagnostic>[
    for (final List<Diagnostic> group in groups.values)
      if (group.length == 1)
        group.single
      else
        Diagnostic(group.first.severity, group.first.path, '${group.first.message} (${group.length} ${_itemsOf(group.first.path)})', code: group.first.code),
  ];
}

/// What the list items of [path] are called: "events" for `$.events[3].item`.
String _itemsOf(String path) {
  final List<JsonStep>? steps = parseJsonPath(path);
  if (steps != null) {
    for (int at = steps.length - 1; at > 0; at--) {
      final JsonStep step = steps[at - 1];
      if (steps[at] is int && step is String) return humanizeKey(step).toLowerCase();
    }
  }
  return 'places';
}

/// What a document's status badge says.
enum DocumentCheck { valid, checking, problems }

/// [problems] are the ones shown; [shown] is [all] less the problems held
/// back while a field is being filled in. Notes that are not problems (a kind
/// without a schema) leave a document valid.
DocumentCheck documentCheck({required List<Diagnostic> all, required List<Diagnostic> shown, required List<Diagnostic> problems}) {
  if (problems.isNotEmpty) return DocumentCheck.problems;
  return shown.length < all.length ? DocumentCheck.checking : DocumentCheck.valid;
}
