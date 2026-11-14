/// Which NPCs run the conversation: the ones whose `behaviors` list names
/// it, each with Remove, and one searchable picker over the workspace's
/// whole NPC library (trait groups too in Advanced) that adds another. An
/// NPC of a server workspace not read yet is read when picked. Every pick or
/// removal is its own undoable change, saved at once like the rest of the
/// form.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/gui/gui_json.dart';
import '../../logic/select/select_model.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../state/editor_store.dart';
import '../../state/library.dart';
import '../../state/live_session.dart' show DocumentRef;
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/icon_button.dart';
import '../gui/gui_fields.dart' show stringList;
import '../select/animus_select.dart';
import 'conversation_fields.dart';
import '../../ui/stack.dart';
import '../../ui/problem_text.dart';

/// Writes [changes] as one undoable change named [label].
typedef ConversationCommit = Future<void> Function(List<DocumentChange> changes, String label);

/// The `behaviors` list of an NPC, or of a group's `values`.
List<String> listedBehaviors(AnimusDocument document) {
  final JsonMap json = asMap(document.json);
  return stringList(document.kind == DocumentKind.group ? asMap(json['values'])['behaviors'] : json['behaviors']);
}

/// [json] of an NPC or group with [behavior] added to or removed from its
/// `behaviors` list. An emptied list is removed.
JsonMap withBehavior(DocumentKind kind, JsonMap json, String behavior, {required bool listed}) {
  JsonMap put(JsonMap holder) {
    final List<String> current = stringList(holder['behaviors']);
    final List<String> next = listed ? <String>{...current, behavior}.toList() : <String>[for (final String id in current) if (id != behavior) id];
    final JsonMap out = <String, Object?>{...holder, 'behaviors': next};
    if (next.isEmpty) out.remove('behaviors');
    return out;
  }

  if (kind == DocumentKind.group) return <String, Object?>{...json, 'values': put(asMap(json['values']))};
  return put(json);
}

/// The entries of one document kind in the workspace library.
typedef LibraryOf = List<LibraryEntry> Function(DocumentKind kind);

/// Reads documents of a server workspace that are not here yet.
typedef LoadDocuments = Future<void> Function(List<DocumentRef> refs);

class ConversationAttach extends StatelessWidget {
  const ConversationAttach({
    required this.behavior,
    required this.documents,
    required this.library,
    required this.onCommit,
    required this.advanced,
    this.load,
    super.key,
  });

  final String behavior;

  /// The documents read so far; an NPC's `behaviors` list comes from here.
  final List<AnimusDocument> documents;

  /// Every NPC (and in Advanced every trait group), read or not.
  final LibraryOf library;
  final ConversationCommit onCommit;
  final bool advanced;

  /// Reads a picked NPC first when it is not here; null in a local workspace.
  final LoadDocuments? load;

  static String _key(DocumentKind kind, String id) => '${kind.wireName}:$id';

  Future<void> _set(DocumentKind kind, String id, {required bool listed}) => onCommit(
        <DocumentChange>[
          DocumentChange.update(kind, id, (Object? latest) => withBehavior(kind, asMap(latest), behavior, listed: listed)),
        ],
        '${listed ? 'attach' : 'detach'} $behavior ${listed ? 'to' : 'from'} $id',
      );

  Future<void> _add(LibraryEntry entry) async {
    if (!entry.loaded && load != null) await load!(<DocumentRef>[DocumentRef(entry.kind, entry.id)]);
    await _set(entry.kind, entry.id, listed: true);
  }

  @override
  Widget build(BuildContext context) {
    final Map<String, AnimusDocument> read = <String, AnimusDocument>{
      for (final AnimusDocument document in documents)
        if (document.kind == DocumentKind.npc || document.kind == DocumentKind.group) _key(document.kind, document.id): document,
    };
    final List<(LibraryEntry, AnimusDocument)> attached = <(LibraryEntry, AnimusDocument)>[];
    final List<LibraryEntry> free = <LibraryEntry>[];
    for (final DocumentKind kind in <DocumentKind>[DocumentKind.npc, if (advanced) DocumentKind.group]) {
      for (final LibraryEntry entry in library(kind)) {
        final AnimusDocument? document = read[entry.key];
        if (document != null && document.json is Map<String, Object?> && listedBehaviors(document).contains(behavior)) {
          attached.add((entry, document));
        } else {
          free.add(entry);
        }
      }
    }
    final Map<String, LibraryEntry> byKey = <String, LibraryEntry>{for (final LibraryEntry entry in free) entry.key: entry};
    return Field(
      label: 'NPCs',
      forId: 'conv-attach',
      tip: 'The NPCs that run this conversation. A pick or a removal saves at once.',
      docs: conversationDocs,
      child: dom.div(attributes: const <String, String>{'data-testid': 'conv-attach'}, <Widget>[
        stack(<Widget>[
          if (attached.isNotEmpty)
            DenseBox(attributes: const <String, String>{'role': 'list', 'aria-label': 'NPCs that run it'}, children: <Widget>[
              for (final (LibraryEntry entry, AnimusDocument document) in attached)
                dom.div(attributes: <String, String>{'role': 'listitem', 'data-target': entry.key}, <Widget>[
                  Row(gapSize: Gap.sm, children: <Widget>[
                    Expanded(
                      child: Text('${entry.kind == DocumentKind.group ? 'Trait group ' : ''}${_name(entry)}', maxLines: 1, overflow: TextOverflow.ellipsis),
                    ),
                    AppIconButton(icon: ArcaneIcon.x(), label: 'Remove ${_name(entry)}', tipAtEnd: true, onPressed: () => _set(document.kind, document.id, listed: false)),
                  ]),
                ]),
            ]),
          if (free.isEmpty && attached.isEmpty)
            noteLine('No NPCs yet.')
          else if (free.isNotEmpty)
            AnimusSelect(
              id: 'conv-attach',
              value: null,
              hiddenLabel: 'Add NPC',
              placeholder: attached.isEmpty ? 'Pick an NPC' : 'Add NPC',
              options: <SelectOption>[
                for (final LibraryEntry entry in free)
                  SelectOption(
                    value: entry.key,
                    label: _name(entry),
                    group: advanced ? (entry.kind == DocumentKind.group ? 'Trait groups' : 'NPCs') : null,
                    description: advanced ? entry.id : null,
                    keywords: <String>[entry.id],
                  ),
              ],
              onChanged: (String key) {
                final LibraryEntry? picked = byKey[key];
                if (picked != null) _add(picked);
              },
            ),
        ]),
      ]),
    );
  }

  /// The name a person knows an NPC or group by.
  static String _name(LibraryEntry entry) => entry.name.trim().isEmpty ? entry.id : entry.name;
}
