import 'dart:convert';
import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../logic/gui/gloss_bundle.dart';
import '../logic/gui/gui_catalog.dart';
import '../logic/gui/gui_json.dart';
import '../logic/gui/gui_text.dart';
import '../logic/offline/new_documents.dart';
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
import 'gui/gui_fields.dart';
import 'npc/npc_browser.dart';
import 'offline/home_screen.dart';
import 'offline/new_document_page.dart';
import 'offline/sync_page.dart';
import 'server/connection_pill.dart';
import 'server/live_bar.dart';
import 'server/pair_page.dart';
import 'server/server_overview.dart';
import 'rig/rig_actions.dart';
import 'rig/rig_editor.dart';

/// Kinds whose Visual view edits the parsed JSON (GUI editors, the
/// schema-driven NPC and behavior inspectors and the route editor).
const Set<DocumentKind> jsonEditedKinds = <DocumentKind>{...guiKinds, DocumentKind.npc, DocumentKind.behavior, DocumentKind.route};

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

  /// Where [document] goes under [id]: a behavior inside a pack stays in its
  /// pack's folder.
  String _pathFor(AnimusDocument document, String id) {
    final int behaviors = document.path.indexOf('/behaviors/');
    if (document.kind == DocumentKind.behavior && document.path.startsWith('packs/') && behaviors > 0) {
      final int colon = id.indexOf(':');
      return '${document.path.substring(0, behaviors)}/behaviors/${id.substring(0, colon)}/${id.substring(colon + 1)}.json';
    }
    return newDocumentPath(document.kind, id);
  }

  /// Moves (or with [copy], copies) a document to [id] in one undo step. A
  /// move takes the references along: NPCs listing a renamed behavior, and
  /// the animations and NPCs of a renamed rig.
  Future<String?> _relocate(WorkspaceSnapshot workspace, AnimusDocument document, String id, {required bool copy}) async {
    final DocumentKind kind = document.kind;
    if (id == document.id) return copy ? 'Pick an id that is not taken.' : null;
    if (!validDocumentId(kind, id)) return 'Use ${idHint(kind)}.';
    if (component.store.document(kind, id) != null) return '${kind.label}: $id already exists.';
    final Object? json = document.json;
    final String source = json is Map<String, Object?> ? prettyJson(rewriteIdentity(kind, json, id, fresh: copy)) : document.source;
    final List<DocumentChange> changes = <DocumentChange>[
      if (!copy) DocumentChange(kind, document.id, null),
      DocumentChange(kind, id, source, path: _pathFor(document, id)),
    ];
    if (!copy) {
      for (final AnimusDocument other in workspace.documents) {
        final Object? value = other.json;
        if (value is! Map<String, Object?>) continue;
        if (kind == DocumentKind.behavior && other.kind == DocumentKind.npc && stringList(value['behaviors']).contains(document.id)) {
          changes.add(DocumentChange(other.kind, other.id, prettyJson(<String, Object?>{
            ...value,
            'behaviors': <String>[for (final String behavior in stringList(value['behaviors'])) behavior == document.id ? id : behavior],
          })));
        } else if (kind == DocumentKind.rig && other.kind == DocumentKind.animation && value['rig'] == document.id) {
          changes.add(DocumentChange(other.kind, other.id, prettyJson(<String, Object?>{...value, 'rig': id})));
        } else if (kind == DocumentKind.rig && other.kind == DocumentKind.npc && asMap(value['rig'])['id'] == document.id) {
          changes.add(DocumentChange(other.kind, other.id, prettyJson(<String, Object?>{...value, 'rig': <String, Object?>{...asMap(value['rig']), 'id': id}})));
        }
      }
    }
    await component.store.commit(documents: changes);
    final int references = changes.length - (copy ? 1 : 2);
    ArcaneSonner.success(copy
        ? 'Copied ${document.id} as $id.'
        : 'Moved ${document.id} to $id${references > 0 ? ' and updated $references reference${references == 1 ? '' : 's'}' : ''}.');
    component.onNavigate(DocumentRoute(workspace.id, kind, id));
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
        CitizensScreen(key: const ValueKey<String>('citizens'), hub: component.hub)
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

  Widget _home(EditorStore store) => HomeScreen(
        store: store,
        hub: component.hub,
        busy: _busy,
        onOpenFolder: () => _open(pickDirectory),
        onOpenZip: () => _open(pickZip),
        onNavigate: component.onNavigate,
      );

  /// Kinds whose id the user picks (a skin's is its content hash, presets
  /// have one fixed id).
  bool _movable(DocumentKind kind) => kind != DocumentKind.skin && kind != DocumentKind.presets;

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
            onRename: _movable(document.kind) ? (String id) => _relocate(workspace, document, id, copy: false) : null,
            onDuplicate: _movable(document.kind) ? (String id) => _relocate(workspace, document, id, copy: true) : null,
            onDelete: () async {
              await component.store.commit(documents: <DocumentChange>[DocumentChange(document.kind, document.id, null)]);
              component.onNavigate(WorkspaceRoute(workspace.id));
              ArcaneSonner.info('Deleted ${document.id}. Undo brings it back.');
            },
            skinDocument: _skinOf(document),
            resources: workspace.resources,
            editor: _rigEditor(workspace, document),
            serverDiagnostics: session?.serverDiagnostics[documentKey(document.kind, document.id)] ?? const <Diagnostic>[],
            liveSchemas: session?.schemas,
            workspaceDocuments: workspace.documents,
            onOpen: (DocumentKind kind, String id) => component.onNavigate(DocumentRoute(workspace.id, kind, id)),
          )
        else if (selected != null)
          dom.section(classes: 'animus-panel', <Widget>[
            Component.text('${selected.kind.label}: ${selected.documentId} is not in this workspace.'),
          ])
        else if (route is NpcListRoute)
          NpcBrowser(key: ValueKey<String>('npc-browser:${workspace.id}'), store: component.store, workspace: workspace, session: session)
        else if (route is NewDocumentRoute)
          NewDocumentPage(
            key: ValueKey<String>('new:${workspace.id}'),
            store: component.store,
            workspace: workspace,
            kind: route.kind,
            onNavigate: component.onNavigate,
          )
        else if (route is SyncRoute && workspace.server == null)
          SyncPage(key: ValueKey<String>('sync:${workspace.id}'), store: component.store, hub: component.hub, workspace: workspace, onNavigate: component.onNavigate)
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
      dom.nav(classes: 'animus-new', attributes: const <String, String>{'aria-label': 'Workspace'}, <Widget>[
        dom.a(
          href: NewDocumentRoute(workspace.id).format(),
          classes: 'animus-side-link is-primary${component.route is NewDocumentRoute ? ' is-selected' : ''}',
          const <Widget>[Component.text('New document')],
        ),
        if (workspace.server == null)
          dom.a(
            href: SyncRoute(workspace.id).format(),
            classes: 'animus-side-link${component.route is SyncRoute ? ' is-selected' : ''}',
            const <Widget>[Component.text('Send to a server')],
          ),
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
              if (kind == DocumentKind.npc)
                dom.a(
                  href: NpcListRoute(workspace.id).format(),
                  classes: 'animus-tree-browse${component.route is NpcListRoute ? ' is-selected' : ''}',
                  const <Widget>[Component.text('Browse')],
                ),
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
        if (workspace.documents.isEmpty) ...<Widget>[
          const dom.p(<Widget>[Component.text('Nothing here yet. An NPC is a good first document: pick its body, name and skin, then give it a behavior.')]),
          dom.div(classes: 'animus-row', <Widget>[
            Button.primary(label: 'Create an NPC', size: ButtonSize.small, onPressed: () => component.onNavigate(NewDocumentRoute(workspace.id, DocumentKind.npc))),
            Button.ghost(label: 'Every kind of document', size: ButtonSize.small, onPressed: () => component.onNavigate(NewDocumentRoute(workspace.id))),
          ]),
        ],
      ]),
      if (workspace.documents.isNotEmpty) _starters(workspace),
      if (workspace.documents.isNotEmpty)
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

  /// Ways into the playground: NPC previews and behavior dry runs.
  Widget _starters(WorkspaceSnapshot workspace) {
    final List<AnimusDocument> npcs = <AnimusDocument>[for (final AnimusDocument document in workspace.documents) if (document.kind == DocumentKind.npc) document];
    final List<AnimusDocument> behaviors = <AnimusDocument>[for (final AnimusDocument document in workspace.documents) if (document.kind == DocumentKind.behavior) document];
    Widget links(String title, List<AnimusDocument> documents, String verb) => dom.div(classes: 'animus-try-group', <Widget>[
          dom.h3(<Widget>[Component.text(title)]),
          dom.ul(classes: 'animus-try-list', <Widget>[
            for (final AnimusDocument document in documents.take(8))
              dom.li(<Widget>[
                dom.a(href: DocumentRoute(workspace.id, document.kind, document.id, ViewMode.preview).format(), <Widget>[
                  Component.text('$verb ${document.kind == DocumentKind.npc ? guiPlainText(document.title) : document.id}'),
                ]),
              ]),
          ]),
        ]);
    return dom.section(classes: 'animus-panel animus-try', attributes: const <String, String>{'aria-labelledby': 'animus-try-title'}, <Widget>[
      const dom.h2(id: 'animus-try-title', <Widget>[Component.text('Try it in the browser')]),
      const dom.p(classes: 'animus-muted', <Widget>[
        Component.text('Previews draw NPCs with their equipment, pose, text and rig. A dry run steps through a behavior and says what would happen on a server.'),
      ]),
      dom.div(classes: 'animus-try-groups', <Widget>[
        if (npcs.isNotEmpty) links('NPC previews', npcs, 'Preview'),
        if (behaviors.isNotEmpty) links('Behavior dry runs', behaviors, 'Dry-run'),
        if (npcs.isEmpty && behaviors.isEmpty)
          dom.p(<Widget>[
            dom.a(href: NewDocumentRoute(workspace.id, DocumentKind.npc).format(), const <Widget>[Component.text('Create an NPC')]),
            const Component.text(' to preview it here.'),
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
    final String hash = json['skin']! as String;
    final AnimusDocument? skin = component.store.document(DocumentKind.skin, hash);
    if (skin == null && component.store.workspace?.server != null) component.session?.openSkin(hash);
    return skin;
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
