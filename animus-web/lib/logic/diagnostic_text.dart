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
