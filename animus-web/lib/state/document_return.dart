/// The trip a dropdown's "+ New" row starts: from the dropdown to the kind's
/// create step and, once the document exists, back to the same screen with
/// the new id chosen in that dropdown.
///
/// 1. [DocumentReturn.begin] records where the user was and which dropdown
///    asked, then the caller navigates to [newRoute].
/// 2. The create step calls [DocumentReturn.complete] with the id it made;
///    the answer is the route to go back to.
/// 3. The dropdown, built again on that route, calls [DocumentReturn.claim]
///    and applies the id as if the user had picked it.
///
/// Leaving that create step without creating ([DocumentReturn.abandon])
/// forgets the trip.
library;

import '../model/document_kind.dart';
import 'workspace_route.dart';

class DocumentReturn {
  _Trip? _trip;
  _Made? _made;

  /// The trip in progress, for the create step to show where it returns.
  EditorRoute? get origin => _trip?.origin;

  /// Whether a create step of [kind] in [workspaceId] returns to a dropdown.
  bool returnsFor(String workspaceId, DocumentKind kind) {
    final _Trip? trip = _trip;
    return trip != null && trip.kind == kind && trip.workspaceId == workspaceId;
  }

  /// Starts a trip from [origin] for the dropdown [field] and returns the
  /// create step to open, prefilled with [name] and [folder].
  NewDocumentRoute begin({
    required String workspaceId,
    required EditorRoute origin,
    required String field,
    required DocumentKind kind,
    String name = '',
    String? folder,
  }) {
    final NewDocumentRoute create = NewDocumentRoute.prefilled(workspaceId, kind, folder: folder, name: name.trim().isEmpty ? null : name.trim());
    _trip = _Trip(workspaceId, origin, field, kind, create);
    _made = null;
    return create;
  }

  /// A document of [kind] was created as [id]: the route to return to, or
  /// null when no dropdown waits for one (the caller opens the document).
  EditorRoute? complete(String workspaceId, DocumentKind kind, String id) {
    final _Trip? trip = _trip;
    if (trip == null || trip.kind != kind || trip.workspaceId != workspaceId) return null;
    _trip = null;
    _made = _Made(trip.origin, trip.field, kind, id);
    return trip.origin;
  }

  /// The id made for the dropdown [field] on [route], once; null for any
  /// other dropdown or screen.
  String? claim(EditorRoute route, String field, DocumentKind kind) {
    final _Made? made = _made;
    if (made == null || made.origin != route || made.field != field || made.kind != kind) return null;
    _made = null;
    return made.id;
  }

  /// The create step [create] closed without creating. Another create step
  /// closing (the one a trip started from) leaves the trip alone.
  void abandon(NewDocumentRoute create) {
    if (_trip?.create == create) _trip = null;
  }

  /// Forgets everything (a workspace closed).
  void clear() {
    _trip = null;
    _made = null;
  }
}

class _Trip {
  const _Trip(this.workspaceId, this.origin, this.field, this.kind, this.create);

  final String workspaceId;
  final EditorRoute origin;
  final String field;
  final DocumentKind kind;
  final NewDocumentRoute create;
}

class _Made {
  const _Made(this.origin, this.field, this.kind, this.id);

  final EditorRoute origin;
  final String field;
  final DocumentKind kind;
  final String id;
}

/// The page's one trip; dropdowns and the create step share it.
final DocumentReturn documentReturn = DocumentReturn();

/// The folder a new document made from [kind]/[id]'s screen goes in, in
/// the form [target] expects: the NPC folder for an NPC, the namespace for
/// a namespaced kind, the rig for an animation made from a rig. Null when
/// the kinds share no folder.
String? folderFor(DocumentKind kind, String id, DocumentKind target) {
  if (target == DocumentKind.animation) {
    if (kind == DocumentKind.rig) return id;
    if (kind == DocumentKind.animation) {
      final int slash = id.lastIndexOf('/');
      return slash > 0 ? id.substring(0, slash) : null;
    }
    return null;
  }
  final String? folder = switch (kind) {
    DocumentKind.npc => id.contains('/') ? id.substring(0, id.lastIndexOf('/')) : null,
    DocumentKind.route || DocumentKind.recording || DocumentKind.pack || DocumentKind.group || DocumentKind.presets || DocumentKind.serverBehaviors || DocumentKind.skin => null,
    _ => id.contains(':') ? id.substring(0, id.indexOf(':')) : null,
  };
  if (folder == null || folder.isEmpty) return null;
  return switch (target) {
    DocumentKind.npc => folder.replaceAll(':', '/'),
    DocumentKind.route || DocumentKind.recording || DocumentKind.pack || DocumentKind.group || DocumentKind.presets || DocumentKind.serverBehaviors || DocumentKind.skin => null,
    // A namespace is one path segment: the NPC folder's first part.
    _ => folder.split('/').first,
  };
}
