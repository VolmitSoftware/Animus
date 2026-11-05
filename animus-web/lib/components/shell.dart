import 'dart:convert';
import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../logic/data_folder.dart';
import '../logic/gui/gloss_bundle.dart';
import '../logic/gui/gui_catalog.dart';
import '../logic/gui/gui_json.dart';
import '../logic/gui/gui_templates.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../model/server_binding.dart';
import '../services/browser.dart';
import '../state/editor_store.dart';
import '../state/live_session.dart';
import '../state/server_hub.dart';
import '../state/workspace_repository.dart';
import '../state/workspace_route.dart';
import 'citizens_screen.dart';
import 'document_view.dart';
import 'server/connection_pill.dart';
import 'server/live_bar.dart';
import 'server/pair_page.dart';
import 'server/server_overview.dart';
import 'rig/rig_actions.dart';
import 'rig/rig_editor.dart';

/// Kinds whose Visual view edits the parsed JSON (GUI editors and the
/// schema-driven NPC and behavior inspectors).
const Set<DocumentKind> jsonEditedKinds = <DocumentKind>{...guiKinds, DocumentKind.npc, DocumentKind.behavior};

/// Kinds listed in the sidebar, in this order.
const List<DocumentKind> listedKinds = <DocumentKind>[
  DocumentKind.npc,
  DocumentKind.behavior,
  DocumentKind.pack,
  DocumentKind.shop,
  DocumentKind.inventory,
  DocumentKind.menu,
  DocumentKind.hologram,
  DocumentKind.rig,
  DocumentKind.animation,
  DocumentKind.route,
  DocumentKind.skin,
  DocumentKind.textAnimation,
  DocumentKind.presets,
];

class EditorShell extends StatefulWidget {
  const EditorShell({
    required this.store,
    required this.hub,
    required this.route,
    required this.brightness,
    required this.onToggleTheme,
    required this.onNavigate,
    this.session,
    super.key,
  });

  final EditorStore store;
  final ServerHub hub;

  /// Live editing for the open workspace when it belongs to a paired server.
  final LiveSession? session;
  final EditorRoute route;
  final Brightness brightness;
  final void Function() onToggleTheme;
  final void Function(EditorRoute route) onNavigate;

  @override
  State<EditorShell> createState() => _EditorShellState();
}

class _EditorShellState extends State<EditorShell> {
  bool _busy = false;
  String _filter = '';

  Future<void> _open(Future<PickedFiles?> Function() pick) async {
    setState(() => _busy = true);
    try {
      final PickedFiles? picked = await pick();
      if (picked == null) return;
      final WorkspaceSnapshot snapshot = await component.store.openFiles(picked.name, picked.files);
      final int invalid = snapshot.documents.where((AnimusDocument document) => !document.valid).length;
      if (snapshot.documents.isEmpty) {
        ArcaneSonner.warning('No Animus documents found. Pick the plugins/Animus folder or a zip of it.');
      } else if (invalid > 0) {
        ArcaneSonner.warning('Opened ${snapshot.documents.length} documents; $invalid need repair.');
      } else {
        ArcaneSonner.success('Opened ${snapshot.documents.length} documents.');
      }
      component.onNavigate(WorkspaceRoute(snapshot.id));
    } on Object catch (failure) {
      ArcaneSonner.error('Could not open the files: $failure');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  String _fileStem(WorkspaceSnapshot workspace) {
    final String stem = workspace.name.toLowerCase().replaceAll(RegExp(r'[^a-z0-9_-]+'), '-').replaceAll(RegExp(r'^-+|-+$'), '');
    return stem.isEmpty ? 'animus' : stem;
  }

  void _exportGloss(WorkspaceSnapshot workspace) {
    final GlossExport exported = writeGlossBundle(workspace.documents);
    if (exported.exported == 0) {
      ArcaneSonner.warning('No chest GUIs, menus, holograms or presets Gloss can run.${exported.skipped.isEmpty ? '' : ' Skipped: ${exported.skipped.join('; ')}'}');
      return;
    }
    downloadBytes('${_fileStem(workspace)}.gloss-workspace.json', utf8.encode(exported.bundle), type: 'application/json');
    ArcaneSonner.success('Exported ${exported.exported} documents as a Gloss workspace bundle.'
        '${exported.skipped.isEmpty ? '' : ' Skipped ${exported.skipped.length}: ${exported.skipped.join('; ')}'}');
  }

  Future<void> _importGloss() async {
    final (String, Uint8List)? picked = await pickFile('.json,application/json');
    if (picked == null) return;
    final GlossImport imported = readGlossBundle(utf8.decode(picked.$2, allowMalformed: true));
    if (imported.error != null) {
      ArcaneSonner.error(imported.error!);
      return;
    }
    await component.store.commit(documents: <DocumentChange>[
      for (final GlossImportedDocument document in imported.documents) DocumentChange(document.location.kind, document.location.id, document.source),
    ]);
    final int invalid = imported.documents
        .where((GlossImportedDocument d) => !(component.store.document(d.location.kind, d.location.id)?.valid ?? false))
        .length;
    final String skipped = imported.skipped.isEmpty ? '' : ' Skipped: ${imported.skipped.join('; ')}.';
    if (invalid > 0) {
      ArcaneSonner.warning('Imported ${imported.documents.length} documents from ${picked.$1}; $invalid use features Animus refuses.$skipped');
    } else {
      ArcaneSonner.success('Imported ${imported.documents.length} documents from ${picked.$1}.$skipped');
    }
  }

  Future<void> _create(WorkspaceSnapshot workspace, DocumentKind kind) async {
    final Set<String> taken = <String>{for (final AnimusDocument document in workspace.documents) if (document.kind == kind) document.id};
    final String id = newDocumentId(kind, taken);
    await component.store.commit(documents: <DocumentChange>[DocumentChange(kind, id, prettyJson(newDocument(kind, id)))]);
    component.onNavigate(DocumentRoute(workspace.id, kind, id));
  }

  Future<String?> _rename(WorkspaceSnapshot workspace, AnimusDocument document, String id) async {
    if (id == document.id) return null;
    if (locateDocument(documentPath(document.kind, id)) != DocumentLocation(document.kind, id)) {
      return document.kind == DocumentKind.inventory || document.kind == DocumentKind.shop
          ? 'Use namespace:path with a-z, 0-9, _ and -, such as town:menu.'
          : 'Use a path with a-z, 0-9, _, - and /, such as town/welcome.';
    }
    if (component.store.document(document.kind, id) != null) return '${document.kind.label}: $id already exists.';
    final Object? json = document.json;
    final String source = document.kind == DocumentKind.shop && json is Map<String, Object?> && json['id'] is String
        ? prettyJson(<String, Object?>{...json, 'id': id})
        : document.source;
    await component.store.commit(documents: <DocumentChange>[
      DocumentChange(document.kind, document.id, null),
      DocumentChange(document.kind, id, source),
    ]);
    component.onNavigate(DocumentRoute(workspace.id, document.kind, id));
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final EditorStore store = component.store;
    final WorkspaceSnapshot? workspace = store.workspace;
    return dom.div(classes: 'animus-shell', <Widget>[
      _topbar(workspace),
      if (store.problem != null)
        dom.div(key: const ValueKey<String>('banner'), classes: 'animus-banner', attributes: const <String, String>{'role': 'alert'}, <Widget>[
          Component.text(store.problem!),
        ]),
      if (component.route is PairRoute)
        PairPage(
          key: ValueKey<String>('pair:${(component.route as PairRoute).code}'),
          code: (component.route as PairRoute).code,
          hub: component.hub,
          onPaired: (String id) => replaceHash(ServerRoute(id, const <String>[]).format()),
        )
      else if (component.route is ServerRoute && component.hub.server((component.route as ServerRoute).serverId) == null)
        const dom.main_(classes: 'animus-home', <Widget>[
          dom.section(classes: 'animus-panel', <Widget>[
            dom.h1(<Widget>[Component.text('This server is not paired in this browser')]),
            dom.p(<Widget>[Component.text('Run /animus editor pair on the server and open the link it prints.')]),
          ]),
        ])
      else if (workspace == null && component.route is CitizensRoute)
        const CitizensScreen(key: ValueKey<String>('citizens'))
      else if (workspace == null)
        _home(store)
      else
        _workspace(workspace),
    ]);
  }

  Widget _topbar(WorkspaceSnapshot? workspace) {
    final bool dark = component.brightness == Brightness.dark;
    return dom.header(classes: 'animus-topbar', <Widget>[
      const dom.a(href: '#/', classes: 'animus-brand', <Widget>[
        dom.span(classes: 'animus-brand-mark', <Widget>[Component.text('A')]),
        dom.span(<Widget>[Component.text('Animus Editor')]),
      ]),
      if (workspace != null)
        dom.span(classes: 'animus-topbar-workspace', <Widget>[Component.text(workspace.name)]),
      if (component.session != null)
        ConnectionPill(status: component.session!.connection.status, hasToken: component.session!.connection.token != null),
      dom.div(classes: 'animus-topbar-actions', <Widget>[
        Button.secondary(
          label: 'Open folder',
          icon: ArcaneIcon.folderOpen(),
          size: ButtonSize.small,
          disabled: _busy,
          onPressed: () => _open(pickDirectory),
        ),
        Button.secondary(
          label: 'Open zip',
          icon: ArcaneIcon.fileArchive(),
          size: ButtonSize.small,
          disabled: _busy,
          onPressed: () => _open(pickZip),
        ),
        if (workspace != null) ...<Widget>[
          Button.secondary(
            label: 'Import .bbmodel',
            icon: ArcaneIcon.upload(),
            size: ButtonSize.small,
            disabled: _busy,
            onPressed: () => _importBbmodel(workspace),
          ),
          Button.secondary(
            label: 'Export zip',
            icon: ArcaneIcon.download(),
            size: ButtonSize.small,
            onPressed: () => exportWorkspace(workspace),
          ),
          Button.ghost(
            label: 'Import Gloss bundle',
            icon: ArcaneIcon.upload(),
            size: ButtonSize.small,
            onPressed: _importGloss,
          ),
          Button.ghost(
            label: 'Export Gloss bundle',
            size: ButtonSize.small,
            onPressed: () => _exportGloss(workspace),
          ),
        ],
        if (workspace != null)
          Button.ghost(
            label: 'Undo',
            size: ButtonSize.small,
            disabled: !component.store.canUndo,
            onPressed: component.store.undo,
          ),
        Button.ghost(
          icon: dark ? ArcaneIcon.sun() : ArcaneIcon.moon(),
          size: ButtonSize.small,
          attributes: <String, String>{'aria-label': dark ? 'Use light theme' : 'Use dark theme'},
          onPressed: component.onToggleTheme,
        ),
      ]),
    ]);
  }

  Widget _home(EditorStore store) {
    return dom.main_(classes: 'animus-home', <Widget>[
      dom.section(classes: 'animus-panel animus-home-intro', <Widget>[
        const dom.h1(<Widget>[Component.text('Open an Animus data folder')]),
        const dom.p(<Widget>[
          Component.text('Pick plugins/Animus (config/animus on mod servers) or a zip of it. '
              'NPCs, behaviors, shops and GUIs are checked against the shared schemas in this browser. '
              'Nothing is uploaded.'),
        ]),
        dom.div(classes: 'animus-row', <Widget>[
          Button.primary(label: 'Open folder', icon: ArcaneIcon.folderOpen(), disabled: _busy, onPressed: () => _open(pickDirectory)),
          Button.secondary(label: 'Open zip', icon: ArcaneIcon.fileArchive(), disabled: _busy, onPressed: () => _open(pickZip)),
          Button.ghost(
            label: 'Convert Citizens save',
            icon: ArcaneIcon.arrowRightLeft(),
            disabled: _busy,
            onPressed: () => component.onNavigate(const CitizensRoute()),
          ),
        ]),
      ]),
      ServersPanel(hub: component.hub, onNavigate: component.onNavigate),
      dom.section(classes: 'animus-panel', <Widget>[
        const dom.h2(<Widget>[Component.text('Workspaces in this browser')]),
        if (store.workspaces.isEmpty)
          const dom.p(classes: 'animus-muted', <Widget>[Component.text('None yet. Opened folders are kept here as local workspaces.')])
        else
          dom.ul(classes: 'animus-workspace-list', <Widget>[
            for (final WorkspaceSummary summary in store.workspaces)
              dom.li(<Widget>[
                dom.a(
                  href: summary.serverId != null && component.hub.server(summary.serverId!) != null
                      ? ServerRoute(summary.serverId!, const <String>[]).format()
                      : WorkspaceRoute(summary.id).format(),
                  <Widget>[Component.text(summary.name)],
                ),
                dom.span(classes: 'animus-muted', <Widget>[
                  Component.text('${summary.documentCount} documents${summary.serverId == null ? '' : ', live on a server'}'),
                ]),
                Button.ghost(label: 'Remove', size: ButtonSize.small, onPressed: () => store.deleteWorkspace(summary.id)),
              ]),
          ]),
      ]),
    ]);
  }

  Widget _workspace(WorkspaceSnapshot workspace) {
    final EditorRoute route = component.route;
    final DocumentRoute? selected = route is DocumentRoute ? route : null;
    final AnimusDocument? document = selected == null ? null : component.store.document(selected.kind, selected.documentId);
    final LiveSession? session = workspace.server == null ? null : component.session;
    return dom.div(key: ValueKey<String>('workspace:${workspace.id}'), classes: 'animus-workspace', <Widget>[
      _sidebar(workspace, selected),
      dom.main_(classes: 'animus-main', <Widget>[
        if (session != null)
          LiveBar(
            session: session,
            focus: document == null ? null : documentKey(document.kind, document.id),
            onOpenServer: () => component.onNavigate(WorkspaceRoute(workspace.id)),
          ),
        if (selected != null && document != null)
          DocumentPane(
            key: ValueKey<String>('${selected.kind.wireName}:${selected.documentId}'),
            document: document,
            view: selected.view,
            onView: (ViewMode view) => component.onNavigate(selected.withView(view)),
            onSource: (String source) => component.store.editSource(document.kind, document.id, source),
            onJson: jsonEditedKinds.contains(document.kind)
                ? (Object? json, {String? coalesce}) => component.store.commit(
                      documents: <DocumentChange>[DocumentChange(document.kind, document.id, prettyJson(json))],
                      coalesce: coalesce == null ? null : '${document.kind.wireName}:${document.id}:$coalesce',
                    )
                : null,
            onRename: guiKinds.contains(document.kind) ? (String id) => _rename(workspace, document, id) : null,
            onDelete: guiKinds.contains(document.kind)
                ? () async {
                    await component.store.commit(documents: <DocumentChange>[DocumentChange(document.kind, document.id, null)]);
                    component.onNavigate(WorkspaceRoute(workspace.id));
                    ArcaneSonner.info('Deleted ${document.id}. Undo brings it back.');
                  }
                : null,
            skinDocument: _skinOf(document),
            editor: _rigEditor(workspace, document),
            serverDiagnostics: session?.serverDiagnostics[documentKey(document.kind, document.id)] ?? const <Diagnostic>[],
            liveSchemas: session?.schemas,
            workspaceDocuments: workspace.documents,
          )
        else if (selected != null)
          dom.section(classes: 'animus-panel', <Widget>[
            Component.text('${selected.kind.label}: ${selected.documentId} is not in this workspace.'),
          ])
        else if (session != null)
          ServerOverview(
            key: ValueKey<String>('server-overview:${session.serverId}'),
            hub: component.hub,
            session: session,
            onOpened: (DocumentKind kind, String id) => component.onNavigate(DocumentRoute(workspace.id, kind, id)),
          )
        else
          _overview(workspace),
      ]),
    ]);
  }

  Widget _sidebar(WorkspaceSnapshot workspace, DocumentRoute? selected) {
    final String filter = _filter.trim().toLowerCase();
    bool matches(AnimusDocument document) =>
        filter.isEmpty || document.id.toLowerCase().contains(filter) || document.title.toLowerCase().contains(filter);
    return dom.aside(classes: 'animus-sidebar', <Widget>[
      dom.div(classes: 'animus-new', attributes: const <String, String>{'role': 'group', 'aria-label': 'New document'}, <Widget>[
        for (final (DocumentKind kind, String label) in const <(DocumentKind, String)>[
          (DocumentKind.inventory, 'New chest GUI'),
          (DocumentKind.shop, 'New shop'),
          (DocumentKind.menu, 'New menu'),
          (DocumentKind.hologram, 'New hologram'),
        ])
          Button.ghost(label: label, icon: ArcaneIcon.plus(size: IconSize.sm), size: ButtonSize.small, onPressed: () => _create(workspace, kind)),
      ]),
      TextInput(
        placeholder: 'Search documents',
        value: _filter,
        onChanged: (String value) => setState(() => _filter = value),
      ),
      for (final DocumentKind kind in listedKinds)
        if (workspace.documents.any((AnimusDocument document) => document.kind == kind))
          dom.section(classes: 'animus-tree-group', <Widget>[
            dom.h3(<Widget>[
              Component.text(kind.label),
              dom.span(classes: 'animus-count', <Widget>[
                Component.text('${workspace.documents.where((AnimusDocument document) => document.kind == kind).length}'),
              ]),
            ]),
            dom.ul(attributes: const <String, String>{'role': 'list'}, <Widget>[
              for (final AnimusDocument document in workspace.documents)
                if (document.kind == kind && matches(document))
                  dom.li(<Widget>[
                    dom.a(
                      href: DocumentRoute(workspace.id, kind, document.id, selected?.view ?? ViewMode.visual).format(),
                      classes: 'animus-tree-item${selected?.kind == kind && selected?.documentId == document.id ? ' is-selected' : ''}',
                      <Widget>[
                        dom.span(classes: 'animus-tree-title', <Widget>[Component.text(document.title)]),
                        if (document.title != document.id)
                          dom.span(classes: 'animus-tree-id', <Widget>[Component.text(document.id)]),
                        if (!document.valid)
                          dom.span(classes: 'animus-badge is-error', <Widget>[Component.text('${errorCount(document)}')]),
                        if (component.session != null && workspace.server != null && draftBadge(component.session!, kind, document.id) != null)
                          dom.span(
                            classes: 'animus-badge is-draft',
                            attributes: <String, String>{'data-draft': draftBadge(component.session!, kind, document.id)!},
                            <Widget>[Component.text(draftBadge(component.session!, kind, document.id)!)],
                          ),
                      ],
                    ),
                  ]),
            ]),
          ]),
    ]);
  }

  Widget _overview(WorkspaceSnapshot workspace) {
    final List<AnimusDocument> invalid = workspace.documents.where((AnimusDocument document) => !document.valid).toList();
    return dom.div(classes: 'animus-overview', <Widget>[
      dom.section(classes: 'animus-panel', <Widget>[
        dom.h2(<Widget>[Component.text(workspace.name)]),
        dom.div(classes: 'animus-stats', <Widget>[
          for (final DocumentKind kind in listedKinds)
            if (workspace.documents.any((AnimusDocument document) => document.kind == kind))
              dom.div(classes: 'animus-stat', <Widget>[
                dom.strong(<Widget>[Component.text('${workspace.documents.where((AnimusDocument document) => document.kind == kind).length}')]),
                dom.span(<Widget>[Component.text(kind.label)]),
              ]),
        ]),
        if (workspace.documents.isEmpty)
          const dom.p(classes: 'animus-muted', <Widget>[Component.text('This workspace has no documents.')]),
      ]),
      dom.section(classes: 'animus-panel', <Widget>[
        dom.h2(<Widget>[Component.text(invalid.isEmpty ? 'Every document passes the shared schemas' : '${invalid.length} documents need repair')]),
        if (invalid.isNotEmpty)
          dom.ul(classes: 'animus-problem-list', <Widget>[
            for (final AnimusDocument document in invalid)
              dom.li(<Widget>[
                dom.a(href: DocumentRoute(workspace.id, document.kind, document.id).format(), <Widget>[
                  Component.text('${document.kind.label}: ${document.id}'),
                ]),
                dom.span(classes: 'animus-muted', <Widget>[Component.text(firstError(document))]),
              ]),
          ]),
      ]),
      if (component.store.ignored.isNotEmpty)
        dom.section(classes: 'animus-panel', <Widget>[
          dom.details(<Widget>[
            dom.summary(<Widget>[Component.text('${component.store.ignored.length} files are not editor documents')]),
            dom.ul(classes: 'animus-muted', <Widget>[
              for (final String path in component.store.ignored.take(200)) dom.li(<Widget>[dom.code(<Widget>[Component.text(path)])]),
            ]),
          ]),
        ]),
    ]);
  }

  Future<void> _importBbmodel(WorkspaceSnapshot workspace) async {
    setState(() => _busy = true);
    try {
      String namespace = 'animus';
      for (final AnimusDocument document in workspace.documents) {
        if (document.kind == DocumentKind.rig && document.id.contains(':')) {
          namespace = document.id.substring(0, document.id.indexOf(':'));
          break;
        }
      }
      final String? id = await importBbmodelFile(component.store, namespace);
      if (id != null) component.onNavigate(DocumentRoute(workspace.id, DocumentKind.rig, id));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// The rig editor for a rig, or for an animation whose rig is here.
  Widget? _rigEditor(WorkspaceSnapshot workspace, AnimusDocument document) {
    final Object? json = document.json;
    AnimusDocument? rig;
    String? animation;
    if (document.kind == DocumentKind.rig) {
      rig = document;
    } else if (document.kind == DocumentKind.animation && json is Map<String, Object?> && json['rig'] is String) {
      rig = component.store.document(DocumentKind.rig, json['rig']! as String);
      animation = document.id;
    }
    if (rig == null || rig.json is! Map<String, Object?>) return null;
    final DocumentRoute? current = component.route is DocumentRoute ? component.route as DocumentRoute : null;
    return RigEditor(
      key: ValueKey<String>('rig-editor:${rig.id}'),
      store: component.store,
      rig: rig,
      animationId: animation,
      onOpen: (DocumentKind kind, String id) =>
          component.onNavigate(DocumentRoute(workspace.id, kind, id, current?.view ?? ViewMode.visual)),
    );
  }

  AnimusDocument? _skinOf(AnimusDocument document) {
    final Object? json = document.json;
    if (document.kind != DocumentKind.npc || json is! Map<String, Object?> || json['skin'] is! String) return null;
    return component.store.document(DocumentKind.skin, json['skin']! as String);
  }
}

int errorCount(AnimusDocument document) =>
    document.diagnostics.where((Diagnostic diagnostic) => diagnostic.severity == DiagnosticSeverity.error).length;

String firstError(AnimusDocument document) {
  for (final Diagnostic diagnostic in document.diagnostics) {
    if (diagnostic.severity == DiagnosticSeverity.error) return '${diagnostic.path}: ${diagnostic.message}';
  }
  return '';
}
