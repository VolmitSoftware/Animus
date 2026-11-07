/// "Make a copy for this NPC": a reusable route copied into a new route
/// document that only this NPC (or group) uses.
library;

import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../state/editor_store.dart';
import '../gui/gui_json.dart';
import '../offline/new_documents.dart';
import 'npc_paths.dart';
import 'route_model.dart';

class RouteCopy {
  const RouteCopy(this.id, this.changes);

  /// The new route's id.
  final String id;

  /// The new route and the owner's navigation pointed at it, for one commit.
  final List<DocumentChange> changes;
}

/// Copies [route] to a free id derived from [owner] (an NPC or a group) and
/// points the owner's `animus:navigation` at the copy.
RouteCopy copyRouteFor(AnimusDocument owner, AnimusDocument route, Iterable<AnimusDocument> documents) {
  final Set<String> taken = <String>{
    for (final AnimusDocument document in documents)
      if (document.kind == DocumentKind.route) document.id,
  };
  final String base = owner.kind == DocumentKind.npc ? npcLocalIdOf(owner.id) : owner.id;
  final String id = freeId(slug('$base-route', fallback: 'route'), taken);
  final List<JsonStep> at = <JsonStep>[if (owner.kind == DocumentKind.group) 'values', 'modules', navigationModule, 'route'];
  return RouteCopy(id, <DocumentChange>[
    DocumentChange(DocumentKind.route, id, prettyJson(route.json), path: newDocumentPath(DocumentKind.route, id)),
    DocumentChange.update(owner.kind, owner.id, (Object? latest) => writeIn(latest, at, id)),
  ]);
}
