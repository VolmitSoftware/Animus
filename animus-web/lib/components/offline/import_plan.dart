/// The Import wizard's plain rules: the sources, what each needs before
/// Convert runs, and what a conversion makes, in words. No browser code, so
/// the rules are unit tested.
library;

import '../../logic/data_folder.dart';
import '../../logic/plural.dart';
import '../../model/document_kind.dart';
import '../../services/server/server_imports.dart';

/// Where the files come from. Citizens, Shopkeepers and Denizen convert in
/// this browser; dtlTraders and "Already on my server" convert on a paired
/// server.
enum ImportSource {
  citizens('Citizens', 'NPCs from saves.yml.'),
  shopkeepers('Shopkeepers', 'Shops from save.yml.'),
  dtlTraders('dtlTraders', 'Trader shops. Your server converts them.'),
  denizen('Denizen', 'Scripts as behaviors.'),
  server('Already on my server', 'Your server converts the saves it has.');

  const ImportSource(this.label, this.line);

  final String label;

  /// One line under the label.
  final String line;

  /// Converted by the paired server instead of this browser.
  bool get onServer => this == dtlTraders || this == server;
}

/// The wizard's steps, in order.
const List<String> importSteps = <String>['Source', 'Files', 'Review', 'Result'];

/// Why [source] cannot be picked, or null when it can.
String? sourceBlocked(ImportSource source, {required bool paired, required bool canImport}) {
  if (!source.onServer) return null;
  if (!paired) return 'Pair a server first.';
  if (!canImport) return 'This pairing cannot import. Pair again.';
  return null;
}

/// Why Convert cannot run yet, or null when it can. [traders] counts the
/// dtlTraders files chosen; [problems] are files the server would refuse.
String? convertBlocked(
  ImportSource source, {
  bool citizens = false,
  bool shopSave = false,
  int traders = 0,
  bool denizen = false,
  bool online = false,
  List<String> problems = const <String>[],
}) {
  switch (source) {
    case ImportSource.citizens:
      return citizens ? null : 'Choose saves.yml first.';
    case ImportSource.shopkeepers:
      return shopSave ? null : 'Choose save.yml first.';
    case ImportSource.denizen:
      return denizen ? null : 'Choose the Denizen folder first.';
    case ImportSource.dtlTraders:
      if (traders == 0) return 'Choose the dtlTraders files first.';
      if (!online) return 'Waiting for the server.';
      return problems.isEmpty ? null : problems.first;
    case ImportSource.server:
      if (!online) return 'Waiting for the server.';
      return problems.isEmpty ? null : problems.first;
  }
}

/// How many documents of each kind [paths] hold, in kind order; files that
/// are not documents (reports, source copies) are not counted.
Map<DocumentKind, int> kindCounts(Iterable<String> paths) {
  final Map<DocumentKind, int> found = <DocumentKind, int>{};
  for (final String path in paths) {
    final DocumentLocation? location = locateDocument(path);
    if (location != null) found[location.kind] = (found[location.kind] ?? 0) + 1;
  }
  return <DocumentKind, int>{
    for (final DocumentKind kind in DocumentKind.values)
      if ((found[kind] ?? 0) > 0) kind: found[kind]!,
  };
}

/// One document of [kind] in a sentence.
String importNoun(DocumentKind kind) => switch (kind) {
      DocumentKind.npc => 'NPC',
      DocumentKind.group => 'trait group',
      DocumentKind.inventory => 'chest GUI',
      DocumentKind.textAnimation => 'text animation',
      DocumentKind.presets => 'preset list',
      DocumentKind.serverBehaviors => 'server behavior list',
      _ => kind.label.substring(0, kind.label.length - 1).toLowerCase(),
    };

/// "a", "a and b", "a, b and c".
String joinWords(List<String> parts) {
  if (parts.isEmpty) return '';
  if (parts.length == 1) return parts.first;
  return '${parts.sublist(0, parts.length - 1).join(', ')} and ${parts.last}';
}

/// What a conversion makes: "6 NPCs, 4 behaviors and 6 skins".
String createdLine(Map<DocumentKind, int> counts) {
  if (counts.isEmpty) return 'Nothing to open.';
  return joinWords(<String>[for (final MapEntry<DocumentKind, int> count in counts.entries) countOf(count.value, importNoun(count.key))]);
}

/// A "not converted" line for Simple: the record it is about, without the
/// field paths and schema wording that follow it.
String simpleIssue(String line) {
  final int path = line.indexOf(r': $.');
  return path < 0 ? line : line.substring(0, path);
}

/// A server import's state in words.
String importStateLabel(String state) => switch (state) {
      'none' => 'Nothing converted',
      'staged' => 'Ready to install',
      'applied' => 'Installed',
      'rolled-back' => 'Rolled back',
      _ => state,
    };

int? _number(Object? value) => value is num ? value.toInt() : null;

/// What a server import holds, in words: "6 NPCs and 6 skins", "3 shops",
/// "6 NPCs installed".
String serverCountsLine(ImportReview review) {
  final Map<String, Object?> citizens = review.reports['citizens'] ?? const <String, Object?>{};
  final Map<String, Object?> shops = review.reports['shopkeepers'] ?? const <String, Object?>{};
  final int? installed = review.npcs;
  if (review.state == 'applied' && installed != null) return '${countOf(installed, 'NPC')} installed';
  final int? records = _number(citizens['records']);
  final int? valid = _number(citizens['valid']);
  final int? skins = _number(citizens['skins']);
  final int? shopCount = _number(shops['shops']);
  final List<String> parts = <String>[
    if (records != null) countOf(records, 'NPC'),
    if (skins != null && skins > 0) countOf(skins, 'skin'),
    if (shopCount != null) countOf(shopCount, 'shop'),
  ];
  final String held = parts.isEmpty ? 'Nothing converted yet' : joinWords(parts);
  if (records != null && valid != null && valid < records) return '$held. ${records - valid} of the NPCs cannot install';
  return held;
}

/// One issue from a server report: a line of text, or an object with a
/// message and the record it is about.
String importIssueLine(Object? issue) {
  if (issue is String) return issue;
  if (issue is Map<String, Object?>) {
    final Object? message = issue['message'] ?? issue['reason'] ?? issue['code'];
    final Object? subject = issue['id'] ?? issue['record'] ?? issue['shop'] ?? issue['npc'] ?? issue['file'];
    final String text = message is String ? message : 'An issue with no description';
    return subject == null ? text : '$subject: $text';
  }
  return '$issue';
}

/// Every issue of a server import's reports, as lines.
List<String> serverIssues(ImportReview review) => <String>[
      for (final String report in const <String>['citizens', 'shopkeepers'])
        for (final Object? issue in (review.reports[report]?['issues'] as List<Object?>?) ?? const <Object?>[]) importIssueLine(issue),
    ];
