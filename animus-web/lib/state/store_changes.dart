part of 'editor_store.dart';

/// One document written by [EditorStore.commit]: new text, removal when
/// [source] is null, or an [update] of an existing document.
class DocumentChange {
  const DocumentChange(this.kind, this.id, this.source, {this.path}) : update = null;

  /// An edit run on the document's JSON when the commit's turn comes, after
  /// every commit queued before it, and written back as formatted JSON. An
  /// edit worked out from what an editor shows goes this way, so it cannot
  /// overwrite a quicker edit that was still being saved. Skipped when the
  /// document is gone by then.
  const DocumentChange.update(this.kind, this.id, JsonUpdate this.update) : source = null, path = null;

  final DocumentKind kind;
  final String id;
  final String? source;
  final JsonUpdate? update;

  /// Where a new document lives; defaults to [documentPath].
  final String? path;
}

/// Where a new document of [kind] with [id] lives in the data folder.
String documentPath(DocumentKind kind, String id) {
  if (kind == DocumentKind.serverBehaviors) return DocumentKind.serverBehaviorsPath;
  final int colon = id.indexOf(':');
  final String relative = colon < 0 ? id : '${id.substring(0, colon)}/${id.substring(colon + 1)}';
  return '${kind.folder}/$relative.json';
}

/// What a commit did, for "Undid: ..." when no label was given, naming each
/// document as the library does: [nameOf] gives a document's name now, or
/// null for one that does not exist yet, which is named from its new text.
/// Keystrokes stored together name their document once.
String describeChanges(List<DocumentChange> changes, Iterable<String> resources, String? Function(DocumentChange change) nameOf) {
  final Set<String> seen = <String>{};
  final List<DocumentChange> documents = <DocumentChange>[
    for (final DocumentChange change in changes)
      if (seen.add(documentKey(change.kind, change.id))) change,
  ];
  if (documents.length == 1) {
    final DocumentChange change = documents.single;
    final String? name = nameOf(change);
    if (change.source == null && change.update == null) return 'delete ${name ?? change.id}';
    return name != null ? 'edit $name' : 'create ${_newName(change)}';
  }
  if (documents.isEmpty) return resources.length == 1 ? 'change ${resources.single}' : 'change ${countOf(resources.length, 'file')}';
  return 'change ${countOf(documents.length, 'document')}';
}

/// A new document's name, as the library will show it.
String _newName(DocumentChange change) {
  Object? json;
  try {
    json = change.source == null ? null : jsonDecode(change.source!);
  } on FormatException {
    json = null;
  }
  if (change.kind == DocumentKind.skin) return skinLabel(change.id, label: skinDocumentLabel(json));
  return displayName(change.kind, change.id, summarizeDocument(change.kind, change.id, json).name);
}

/// A commit in the queue, with what a later keystroke must match to join it.
class _QueuedCommit {
  _QueuedCommit(this.changes, this.coalesce, this.undoable, this.label, this.done);

  final List<DocumentChange> changes;
  final String? coalesce;
  final bool undoable;
  final String? label;
  final Future<void> done;
}
