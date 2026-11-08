/// "+ New group" from an NPC's group picker: a group named from the search
/// text, written with the NPC's `groups` entry in one undoable change.
library;

import '../../model/document_kind.dart';
import '../../state/editor_store.dart';
import '../gui/gui_json.dart';
import '../offline/new_documents.dart';
import 'group_model.dart';

class GroupCreation {
  const GroupCreation(this.id);

  /// The new group's id.
  final String id;

  /// The group document and NPC [npcId] listing it last, for one commit.
  List<DocumentChange> changes(String npcId) => <DocumentChange>[
        DocumentChange(DocumentKind.group, id, prettyJson(newGroup(id)), path: newDocumentPath(DocumentKind.group, id)),
        DocumentChange.update(DocumentKind.npc, npcId, (Object? latest) {
          final JsonMap npc = asMap(latest);
          return <String, Object?>{
            ...npc,
            'groups': <String>[...npcGroups(npc).where((String group) => group != id), id],
          };
        }),
      ];
}

/// A group named after [search], at an id no group in [taken] has.
GroupCreation groupFromSearch(String search, Set<String> taken) => GroupCreation(freeId(slug(search, fallback: 'group'), taken));
