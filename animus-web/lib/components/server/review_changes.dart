/// The Review sheet's plain-Dart parts: what changed in a document in a few
/// words, and the edit that discards a change.
library;

import 'dart:convert';

import '../../logic/change_sets.dart';
import '../../logic/gui/gui_text.dart';
import '../../logic/json_merge.dart' show isAbsent;
import '../../logic/gui/gui_json.dart';
import '../../logic/offline/sync_plan.dart' show differingFields;
import '../../logic/plural.dart';
import '../../model/server_binding.dart';
import '../../state/editor_store.dart';

/// Top-level document keys in the words the editor shows for them.
const Map<String, String> fieldWords = <String, String>{
  'type': 'body',
  'group': 'folder',
  'groups': 'trait groups',
  'navigation': 'movement',
  'offers': 'offers',
  'lines': 'lines',
  'frames': 'frames',
};

/// A top-level key in words: `skinLayers` reads "skin layers".
String fieldWord(String key) {
  final String? known = fieldWords[key];
  if (known != null) return known;
  return key.replaceAllMapped(RegExp('([a-z0-9])([A-Z])'), (Match match) => '${match[1]} ${match[2]}').replaceAll('_', ' ').toLowerCase();
}

/// What a change does, in a few words: "New", "Deleted", "Moved from
/// town/baker", "Changed name and skin". [base] is the server's copy,
/// [mine] the document here.
String changeLine(DraftChange draft, {Object? base, Object? mine}) {
  switch (draft.state) {
    case DraftState.created:
      return 'New';
    case DraftState.deleted:
      return 'Deleted';
    case DraftState.moved:
      return 'Moved from ${draft.from ?? 'elsewhere'}';
    case DraftState.clean:
      return 'No change';
    case DraftState.modified:
      final List<String> fields = <String>[
        for (final String key in differingFields(base, mine))
          if (key != 'revision') fieldWord(key),
      ];
      if (fields.isEmpty) return 'Changed';
      if (fields.length <= 3) return 'Changed ${_join(fields)}';
      return 'Changed ${fields.take(2).join(', ')} and ${fields.length - 2} more';
  }
}

String _join(List<String> words) => words.length == 1 ? words.first : '${words.sublist(0, words.length - 1).join(', ')} and ${words.last}';

/// The edits that put a document back as the server has it, or null when the
/// server's copy is not stored here (nothing to go back to).
List<DocumentChange>? discardOf(DraftChange draft, ServerBinding binding) {
  final ServerBase? base = binding.bases[draft.key];
  switch (draft.state) {
    case DraftState.clean:
      return const <DocumentChange>[];
    case DraftState.created:
      return <DocumentChange>[DocumentChange(draft.kind, draft.id, null)];
    case DraftState.modified || DraftState.deleted:
      if (base == null || base.source == null) return null;
      return <DocumentChange>[DocumentChange(draft.kind, draft.id, prettyJson(base.source), path: base.path)];
    case DraftState.moved:
      final String? from = draft.from;
      final ServerBase? origin = from == null ? null : binding.bases[documentKey(draft.kind, from)];
      if (from == null || origin == null || origin.source == null) return null;
      return <DocumentChange>[
        DocumentChange(draft.kind, draft.id, null),
        DocumentChange(draft.kind, from, prettyJson(origin.source), path: origin.path),
      ];
  }
}

/// A value as Advanced shows it: the JSON, cut at 120 characters.
String shortJson(Object? value) {
  if (isAbsent(value)) return 'not set';
  final String text = jsonEncode(value);
  return text.length > 120 ? '${text.substring(0, 117)}...' : text;
}

/// A value as Simple shows it: text as it reads in game (no quotes, no
/// colour codes), numbers and switches as they are.
String plainValue(Object? value) {
  if (isAbsent(value)) return 'not set';
  if (value is String) return guiPlainText(value);
  if (value is num || value is bool) return '$value';
  return shortJson(value);
}

/// A field as people read it: `$.position.x` reads "position x".
String fieldPath(List<String> segments) => segments.map(fieldWord).join(' ');

/// [fieldPath] as a label: "Position x", "Name".
String fieldLabel(List<String> segments) {
  final String words = fieldPath(segments);
  return words.isEmpty ? words : '${words[0].toUpperCase()}${words.substring(1)}';
}

/// What Compare says and offers for a document changed here and on the
/// server: its [line], the primary button (Take server is always beside it)
/// and whether each field both sides changed gets its own Mine or Server
/// switch. With one such field the two buttons are the choice.
({String line, String primary, bool pick}) compareChoice({required bool serverDeleted, required int conflicts, String? field}) {
  if (serverDeleted) return (line: 'The server deleted it after you started changing it.', primary: 'Keep mine', pick: false);
  if (conflicts == 0) return (line: 'Your changes and the server\'s touch different fields, so both can be kept.', primary: 'Keep both', pick: false);
  if (conflicts == 1) return (line: '${field ?? 'One field'} was changed on both sides.', primary: 'Keep mine', pick: false);
  return (line: '$conflicts fields were changed on both sides. Pick which to keep.', primary: 'Use combined', pick: true);
}

/// A line of Review's history: what an apply (or an undo of one) wrote,
/// with each document named by [nameOf].
String historyLine(AppliedChange change, String Function(String key) nameOf) {
  final String names = namesLine(<String>[for (final String key in change.documents) nameOf(key)]);
  if (change.undoOf != null) return names.isEmpty ? 'Undid an earlier apply' : 'Undid $names';
  return names.isEmpty ? 'Applied nothing' : 'Applied $names';
}
