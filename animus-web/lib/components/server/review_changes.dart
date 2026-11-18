/// The Review sheet's plain-Dart parts: what changed in a file in a few
/// words, its fields old to new, what Compare's merge takes from each side,
/// and the edit that discards a change.
library;

import 'dart:convert';

import '../../logic/change_sets.dart';
import '../../logic/gui/gui_text.dart';
import '../../logic/json_merge.dart' show MergeConflict, absentValue, isAbsent, jsonPathOf;
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

final RegExp _vanillaId = RegExp(r'^minecraft:[a-z0-9_./]+$');

/// A value as Simple shows it: text as it reads in game (no quotes, no
/// colour codes), a vanilla id as words (`minecraft:iron_golem` reads "iron
/// golem"), numbers and switches as they are, a list of plain values joined,
/// any other list or object by its size.
String plainValue(Object? value) {
  if (isAbsent(value)) return 'not set';
  if (value is String) return _vanillaId.hasMatch(value) ? value.substring('minecraft:'.length).replaceAll('_', ' ') : guiPlainText(value);
  if (value is num || value is bool) return '$value';
  if (value is List<Object?>) {
    if (value.isEmpty) return 'none';
    if (value.every((Object? item) => item is String || item is num || item is bool)) {
      final String joined = value.map(plainValue).join(', ');
      return joined.length > 120 ? '${joined.substring(0, 117)}...' : joined;
    }
    return value.length == 1 ? '1 item' : '${value.length} items';
  }
  if (value is Map<String, Object?>) return value.length == 1 ? '1 setting' : '${value.length} settings';
  return shortJson(value);
}

/// One field that differs between two copies of a file.
class FieldChange {
  const FieldChange(this.segments, this.before, this.after);

  /// Object keys from the file's root.
  final List<String> segments;

  /// Each copy's value, or [absentValue] where it is not set.
  final Object? before;
  final Object? after;

  String get path => jsonPathOf(segments);
}

/// Keys Review never lists: the server raises `revision` on every apply.
const Set<String> _bookkeeping = <String>{'revision'};

/// Keys a new file always has, left out of its list.
const Set<String> _newFileBookkeeping = <String>{'revision', 'schemaVersion'};

/// Every field that differs between [before] and [after]: objects are walked
/// key by key, lists and other values compare whole, as the merge does. A
/// side that is not set ([absentValue] or null) counts as an empty object, so
/// a new file lists each field it sets.
List<FieldChange> fieldChanges(Object? before, Object? after) {
  final bool created = isAbsent(before) || before == null;
  final Map<String, Object?> from = before is Map<String, Object?> ? before : const <String, Object?>{};
  final Map<String, Object?> to = after is Map<String, Object?> ? after : const <String, Object?>{};
  final List<FieldChange> changes = <FieldChange>[];
  for (final String key in <String>[...to.keys, for (final String key in from.keys) if (!to.containsKey(key)) key]) {
    if ((created ? _newFileBookkeeping : _bookkeeping).contains(key)) continue;
    _walk(<String>[key], from.containsKey(key) ? from[key] : absentValue, to.containsKey(key) ? to[key] : absentValue, changes);
  }
  return changes;
}

void _walk(List<String> at, Object? before, Object? after, List<FieldChange> changes) {
  if (!isAbsent(before) && !isAbsent(after) && jsonEquals(before, after)) return;
  final bool walkBefore = before is Map<String, Object?> || isAbsent(before);
  final bool walkAfter = after is Map<String, Object?> || isAbsent(after);
  if (walkBefore && walkAfter && (before is Map<String, Object?> || after is Map<String, Object?>)) {
    final Map<String, Object?> from = before is Map<String, Object?> ? before : const <String, Object?>{};
    final Map<String, Object?> to = after is Map<String, Object?> ? after : const <String, Object?>{};
    for (final String key in <String>[...to.keys, for (final String key in from.keys) if (!to.containsKey(key)) key]) {
      _walk(<String>[...at, key], from.containsKey(key) ? from[key] : absentValue, to.containsKey(key) ? to[key] : absentValue, changes);
    }
    return;
  }
  changes.add(FieldChange(List<String>.unmodifiable(at), before, after));
}

final RegExp _uuid = RegExp(r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$');

bool _hiddenValue(Object? value) => value is String && _uuid.hasMatch(value);

/// Keys Simple leaves out of the list: they hold ids, not settings.
const Set<String> _simpleSkips = <String>{'uuid', 'id'};

/// Whether Simple lists [change] at all.
bool shownInSimple(FieldChange change) => !_simpleSkips.contains(change.segments.first);

/// One line of the list: "Name: Baker → Head baker" in Simple; the JSON path
/// and values in Advanced. Simple never shows a UUID: such a field reads
/// "Owner: changed" (or "set", "removed").
String fieldChangeText(FieldChange change, {required bool advanced}) {
  if (advanced) return '${change.path}: ${shortJson(change.before)} → ${shortJson(change.after)}';
  final String label = fieldLabel(change.segments);
  if (_hiddenValue(change.before) || _hiddenValue(change.after)) {
    if (isAbsent(change.before)) return '$label: set';
    if (isAbsent(change.after)) return '$label: removed';
    return '$label: changed';
  }
  return '$label: ${plainValue(change.before)} → ${plainValue(change.after)}';
}

/// The most field lines a file shows in Review.
const int reviewFieldLimit = 6;

/// The lines Review shows for [changes] in the mode, at most
/// [reviewFieldLimit], and how many more there are.
({List<String> rows, int more}) fieldRows(List<FieldChange> changes, {required bool advanced}) {
  final List<FieldChange> shown = <FieldChange>[for (final FieldChange change in changes) if (advanced || shownInSimple(change)) change];
  return (
    rows: <String>[for (final FieldChange change in shown.take(reviewFieldLimit)) fieldChangeText(change, advanced: advanced)],
    more: shown.length > reviewFieldLimit ? shown.length - reviewFieldLimit : 0,
  );
}

/// The line under a capped list.
String moreFieldsLine(int more) => 'and $more more';

/// The server's copy a change is compared with: [absentValue] for a new
/// file, the copy at the old id for a move, null when the server's copy is
/// not stored here.
Object? reviewBase(DraftChange draft, ServerBinding binding) => switch (draft.state) {
      DraftState.created => absentValue,
      DraftState.moved => draft.from == null ? null : binding.bases[documentKey(draft.kind, draft.from!)]?.source,
      _ => binding.bases[draft.key]?.source,
    };

bool _related(List<String> a, List<String> b) {
  final int shared = a.length < b.length ? a.length : b.length;
  for (int at = 0; at < shared; at++) {
    if (a[at] != b[at]) return false;
  }
  return true;
}

/// What Compare's merge takes on its own: the fields only the server changed
/// (merged in) and those only you changed (kept). Fields both sides changed
/// are the [conflicts], in neither list. Nothing when a side was deleted.
({List<FieldChange> server, List<FieldChange> mine}) mergedFields(Object? base, Object? mine, Object? server, List<MergeConflict> conflicts) {
  if (server == null || isAbsent(mine) || mine == null) return (server: const <FieldChange>[], mine: const <FieldChange>[]);
  final List<FieldChange> theirs = fieldChanges(base, server);
  final List<FieldChange> yours = fieldChanges(base, mine);
  bool free(FieldChange change) => !conflicts.any((MergeConflict conflict) => _related(conflict.segments, change.segments));
  bool both(FieldChange change, List<FieldChange> other) => other.any((FieldChange each) => _related(each.segments, change.segments));
  return (
    server: <FieldChange>[for (final FieldChange change in theirs) if (free(change) && !both(change, yours)) change],
    mine: <FieldChange>[for (final FieldChange change in yours) if (free(change) && !both(change, theirs)) change],
  );
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
