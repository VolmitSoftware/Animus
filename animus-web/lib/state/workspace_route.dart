/// Hash routes. Hashes keep pairing secrets out of server logs and work with
/// the single /index.html rewrite.
///
/// ```text
/// #/                                         home
/// #/workspace/<id>                           a workspace
/// #/workspace/<id>/npcs                      the NPC browser
/// #/workspace/<id>/recordings                the recordings list
/// #/workspace/<id>/new[/<kind>][?folder=&name=]  create a document, prefilled
/// #/workspace/<id>/list/<kind>               every document of one kind
/// #/workspace/<id>/sync                      push to or pull from a server
/// #/workspace/<id>/<kind>/<docId>?view=code  one document (docId URI-encoded)
/// #/citizens                                 convert a Citizens save locally
/// #/pair/<code>                              a pairing link
/// #/server/<serverId>/<rest>                 a connected server
/// ```
library;

import '../model/document_kind.dart';

enum ViewMode { visual, preview, code, split }

sealed class EditorRoute {
  const EditorRoute();

  static EditorRoute parse(String hash) {
    String text = hash.startsWith('#') ? hash.substring(1) : hash;
    if (text.isEmpty || text == '/') return const HomeRoute();
    if (!text.startsWith('/')) return const HomeRoute();
    String query = '';
    final int question = text.indexOf('?');
    if (question >= 0) {
      query = text.substring(question + 1);
      text = text.substring(0, question);
    }
    final List<String> parts = text.substring(1).split('/').where((String part) => part.isNotEmpty).toList();
    if (parts.isEmpty) return const HomeRoute();
    String decode(String part) {
      try {
        return Uri.decodeComponent(part);
      } on ArgumentError {
        return part;
      }
    }

    switch (parts.first) {
      case 'workspace':
        if (parts.length == 2) return WorkspaceRoute(decode(parts[1]));
        if (parts.length == 3 && parts[2] == 'npcs') return NpcListRoute(decode(parts[1]));
        if (parts.length == 3 && parts[2] == 'recordings') return RecordingListRoute(decode(parts[1]));
        if (parts.length == 3 && parts[2] == 'new') return NewDocumentRoute(decode(parts[1]));
        if (parts.length == 3 && parts[2] == 'sync') return SyncRoute(decode(parts[1]));
        if (parts.length == 4 && parts[2] == 'new') {
          String? folder;
          String? name;
          for (final String pair in query.split('&')) {
            if (pair.startsWith('folder=')) folder = decode(pair.substring(7));
            if (pair.startsWith('name=')) name = decode(pair.substring(5));
          }
          final DocumentKind? kind = DocumentKind.parse(parts[3]);
          return kind == null ? NewDocumentRoute(decode(parts[1])) : NewDocumentRoute.prefilled(decode(parts[1]), kind, folder: folder, name: name);
        }
        if (parts.length == 4 && parts[2] == 'list') {
          final DocumentKind? kind = DocumentKind.parse(parts[3]);
          return kind == null ? WorkspaceRoute(decode(parts[1])) : KindListRoute(decode(parts[1]), kind);
        }
        if (parts.length == 4) {
          final DocumentKind? kind = DocumentKind.parse(parts[2]);
          if (kind == null) return WorkspaceRoute(decode(parts[1]));
          ViewMode view = ViewMode.visual;
          for (final String pair in query.split('&')) {
            if (pair.startsWith('view=')) {
              view = ViewMode.values.asNameMap()[pair.substring(5)] ?? ViewMode.visual;
            }
          }
          return DocumentRoute(decode(parts[1]), kind, decode(parts[3]), view);
        }
        return const HomeRoute();
      case 'citizens':
        return parts.length == 1 ? const CitizensRoute() : const HomeRoute();
      case 'pair':
        return parts.length == 2 ? PairRoute(parts[1]) : const HomeRoute();
      case 'server':
        return parts.length >= 2 ? ServerRoute(decode(parts[1]), parts.skip(2).map(decode).toList()) : const HomeRoute();
      default:
        return const HomeRoute();
    }
  }

  String format();
}

class HomeRoute extends EditorRoute {
  const HomeRoute();

  @override
  String format() => '#/';

  @override
  bool operator ==(Object other) => other is HomeRoute;

  @override
  int get hashCode => 0;
}

/// The local Citizens save converter.
class CitizensRoute extends EditorRoute {
  const CitizensRoute();

  @override
  String format() => '#/citizens';

  @override
  bool operator ==(Object other) => other is CitizensRoute;

  @override
  int get hashCode => 1;
}

class WorkspaceRoute extends EditorRoute {
  const WorkspaceRoute(this.workspaceId);

  final String workspaceId;

  @override
  String format() => '#/workspace/${Uri.encodeComponent(workspaceId)}';

  @override
  bool operator ==(Object other) => other is WorkspaceRoute && other.workspaceId == workspaceId;

  @override
  int get hashCode => workspaceId.hashCode;
}

/// The NPC browser of a workspace.
class NpcListRoute extends EditorRoute {
  const NpcListRoute(this.workspaceId);

  final String workspaceId;

  @override
  String format() => '#/workspace/${Uri.encodeComponent(workspaceId)}/npcs';

  @override
  bool operator ==(Object other) => other is NpcListRoute && other.workspaceId == workspaceId;

  @override
  int get hashCode => Object.hash('npcs', workspaceId);
}

/// The recordings list of a workspace.
class RecordingListRoute extends EditorRoute {
  const RecordingListRoute(this.workspaceId);

  final String workspaceId;

  @override
  String format() => '#/workspace/${Uri.encodeComponent(workspaceId)}/recordings';

  @override
  bool operator ==(Object other) => other is RecordingListRoute && other.workspaceId == workspaceId;

  @override
  int get hashCode => Object.hash('recordings', workspaceId);
}

/// The New page of a workspace, optionally on one kind's guided step.
class NewDocumentRoute extends EditorRoute {
  const NewDocumentRoute(this.workspaceId, [this.kind])
      : folder = null,
        name = null;

  const NewDocumentRoute.inFolder(this.workspaceId, DocumentKind this.kind, String this.folder) : name = null;

  /// A guided step with its folder and name typed in, as a dropdown's
  /// "+ New" row opens it.
  const NewDocumentRoute.prefilled(this.workspaceId, DocumentKind this.kind, {this.folder, this.name});

  final String workspaceId;
  final DocumentKind? kind;

  /// Where the new document goes: the folder under npcs/ for an NPC, the
  /// namespace for namespaced kinds, the rig for an animation.
  final String? folder;

  /// The name the id is made from.
  final String? name;

  @override
  String format() {
    final String base = '#/workspace/${Uri.encodeComponent(workspaceId)}/new${kind == null ? '' : '/${kind!.wireName}'}';
    if (kind == null) return base;
    final List<String> query = <String>[
      if (folder != null) 'folder=${Uri.encodeComponent(folder!)}',
      if (name != null && name!.isNotEmpty) 'name=${Uri.encodeComponent(name!)}',
    ];
    return query.isEmpty ? base : '$base?${query.join('&')}';
  }

  @override
  bool operator ==(Object other) =>
      other is NewDocumentRoute && other.workspaceId == workspaceId && other.kind == kind && other.folder == folder && other.name == name;

  @override
  int get hashCode => Object.hash('new', workspaceId, kind, folder, name);
}

/// Every document of one kind, with New and the problems of each.
class KindListRoute extends EditorRoute {
  const KindListRoute(this.workspaceId, this.kind);

  final String workspaceId;
  final DocumentKind kind;

  @override
  String format() => '#/workspace/${Uri.encodeComponent(workspaceId)}/list/${kind.wireName}';

  @override
  bool operator ==(Object other) => other is KindListRoute && other.workspaceId == workspaceId && other.kind == kind;

  @override
  int get hashCode => Object.hash('list', workspaceId, kind);
}

/// Pushing a local workspace to a paired server, or pulling from one.
class SyncRoute extends EditorRoute {
  const SyncRoute(this.workspaceId);

  final String workspaceId;

  @override
  String format() => '#/workspace/${Uri.encodeComponent(workspaceId)}/sync';

  @override
  bool operator ==(Object other) => other is SyncRoute && other.workspaceId == workspaceId;

  @override
  int get hashCode => Object.hash('sync', workspaceId);
}

class DocumentRoute extends EditorRoute {
  const DocumentRoute(this.workspaceId, this.kind, this.documentId, [this.view = ViewMode.visual]);

  final String workspaceId;
  final DocumentKind kind;
  final String documentId;
  final ViewMode view;

  DocumentRoute withView(ViewMode next) => DocumentRoute(workspaceId, kind, documentId, next);

  @override
  String format() {
    final String base = '#/workspace/${Uri.encodeComponent(workspaceId)}/${kind.wireName}/${Uri.encodeComponent(documentId)}';
    return view == ViewMode.visual ? base : '$base?view=${view.name}';
  }

  @override
  bool operator ==(Object other) =>
      other is DocumentRoute && other.workspaceId == workspaceId && other.kind == kind && other.documentId == documentId && other.view == view;

  @override
  int get hashCode => Object.hash(workspaceId, kind, documentId, view);
}

class PairRoute extends EditorRoute {
  const PairRoute(this.code);

  /// The ANM1. pairing code, kept only in the fragment.
  final String code;

  @override
  String format() => '#/pair/$code';

  @override
  bool operator ==(Object other) => other is PairRoute && other.code == code;

  @override
  int get hashCode => code.hashCode;
}

class ServerRoute extends EditorRoute {
  const ServerRoute(this.serverId, this.rest);

  final String serverId;
  final List<String> rest;

  @override
  String format() => <String>['#/server', Uri.encodeComponent(serverId), ...rest.map(Uri.encodeComponent)].join('/');

  @override
  bool operator ==(Object other) => other is ServerRoute && other.serverId == serverId && other.rest.join('/') == rest.join('/');

  @override
  int get hashCode => Object.hash(serverId, rest.join('/'));
}
