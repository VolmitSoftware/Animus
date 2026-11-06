import 'dart:convert';
import 'dart:js_interop';
import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../logic/data_folder.dart';
import '../logic/gui/gloss_bundle.dart';
import '../logic/gui/gui_catalog.dart';
import '../logic/gui/gui_json.dart';
import '../logic/gui/gui_text.dart';
import '../logic/inspect/server_behaviors.dart';
import '../logic/offline/new_documents.dart';
import '../logic/recording/rename_references.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../model/server_binding.dart';
import '../services/browser.dart';
import '../state/editor_store.dart';
import '../state/live_session.dart';
import '../state/server_hub.dart';
import '../state/workspace_repository.dart';
import '../state/workspace_route.dart';
import 'brand_mark.dart';
import 'citizens_screen.dart';
import '../logic/command_search.dart';
import 'command_palette.dart';
import 'document_view.dart';
import 'gui/gui_fields.dart';
import 'npc/npc_browser.dart';
import 'offline/home_screen.dart';
import 'offline/new_document_page.dart';
import 'offline/offline_fields.dart';
import 'offline/sync_page.dart';
import 'recording/recording_list.dart';
import 'rig/rig_actions.dart';
import 'rig/rig_editor.dart';
import 'server/live_bar.dart';
import 'server/pair_page.dart';
import 'server/server_overview.dart';
import 'shell_sidebar.dart';
import 'shell_topbar.dart';

/// Kinds whose Visual view edits the parsed JSON (GUI editors, the
/// schema-driven NPC and behavior inspectors and the route editor).
const Set<DocumentKind> jsonEditedKinds = <DocumentKind>{...guiKinds, DocumentKind.npc, DocumentKind.group, DocumentKind.behavior, DocumentKind.route, DocumentKind.pack, DocumentKind.serverBehaviors};

/// The id of every screen's `<main>`, the target of the skip link.
const String mainId = 'animus-main';

/// Widths at which the sidebar becomes a drawer and the top bar a short row.
const String compactQuery = '(max-width: 760px)';

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
  bool _compact = false;
  bool _drawerOpen = false;
  bool _paletteOpen = false;

  /// A Gloss bundle whose documents would replace ones the workspace has,
  /// waiting for the user to confirm.
  ({GlossImport imported, String file, List<DocumentLocation> clashes})? _glossPending;
  void Function()? _stopKeys;
  void Function()? _stopMedia;

  @override
  void initState() {
    super.initState();
    final (bool compact, void Function() stop) = watchMedia(compactQuery, (bool compact) {
      if (!mounted) return;
      setState(() {
        _compact = compact;
        if (!compact) _setDrawer(false);
      });
    });
    _compact = compact;
    _stopMedia = stop;
    _stopKeys = onKeyDown(_shortcut);
  }

  @override
  void dispose() {
    _stopKeys?.call();
    _stopMedia?.call();
    setScrollLocked(false);
    super.dispose();
  }

  @override
  void didUpdateWidget(EditorShell oldComponent) {
    super.didUpdateWidget(oldComponent);
    // Moving to another screen (or leaving the workspace) closes the drawer.
    if (_drawerOpen && (component.store.workspace == null || oldComponent.route != component.route)) _setDrawer(false);
  }

  /// The page-wide shortcuts. Keys typed into a text box stay with the box;
  /// Escape is handled by whatever has focus.
  void _shortcut(web.KeyboardEvent event) {
    if (_paletteOpen) return;
    final bool modifier = event.ctrlKey || event.metaKey;
    final String key = event.key.toLowerCase();
    if (isTypingTarget(event.target) || event.altKey) return;
    final WorkspaceSnapshot? workspace = component.store.workspace;
    if (modifier && key == 'k') {
      event.preventDefault();
      _openPalette();
    } else if (modifier && key == 'z' && workspace != null) {
      event.preventDefault();
      event.shiftKey ? _redo() : _undo();
    } else if (modifier && key == 'y' && workspace != null) {
      event.preventDefault();
      _redo();
    } else if (((modifier && key == 'f') || (!modifier && key == '/')) && workspace != null) {
      event.preventDefault();
      _focusSearch();
    }
  }

  void _focusSearch() {
    if (_compact && !_drawerOpen) setState(() => _setDrawer(true));
    focusWhenShown(documentSearchId);
  }

  void _setDrawer(bool open) {
    _drawerOpen = open;
    setScrollLocked(open || _paletteOpen);
  }

  void _openDrawer() {
    setState(() => _setDrawer(true));
    // The open document's link, else the search box, takes focus.
    web.window.setTimeout((() {
      final web.HTMLElement? current = web.document.querySelector('#animus-sidebar [aria-current="page"]') as web.HTMLElement?;
      if (current != null) {
        current.scrollIntoView(web.ScrollIntoViewOptions(block: 'center'));
        current.focus();
      } else {
        focusById(documentSearchId);
      }
    }).toJS, 30.toJS);
  }

  void _closeDrawer() {
    setState(() => _setDrawer(false));
    focusWhenShown('animus-documents-button');
  }

  void _openPalette() {
    setState(() => _paletteOpen = true);
    setScrollLocked(true);
  }

  void _closePalette() {
    setState(() => _paletteOpen = false);
    setScrollLocked(_drawerOpen);
  }

  Future<void> _undo() async {
    final String? label = await component.store.undo();
    if (label != null) ArcaneSonner.info('Undid: $label');
  }

  Future<void> _redo() async {
    final String? label = await component.store.redo();
    if (label != null) ArcaneSonner.info('Redid: $label');
  }

  Future<void> _open(Future<PickedFiles?> Function() pick) async {
    setState(() => _busy = true);
    try {
      final PickedFiles? picked = await pick();
      if (picked == null) return;
      final WorkspaceSnapshot snapshot = await component.store.openFiles(picked.name, picked.files);
      final int invalid = snapshot.documents.where((AnimusDocument document) => !document.valid).length;
      if (snapshot.documents.isEmpty) {
        ArcaneSonner.warning('No Animus documents in ${picked.name}. Pick plugins/Animus or a zip of it.');
      } else if (invalid > 0) {
        ArcaneSonner.warning('Opened ${snapshot.documents.length} documents; $invalid need repair.');
      } else {
        ArcaneSonner.success('Opened ${snapshot.documents.length} documents.');
      }
      component.onNavigate(WorkspaceRoute(snapshot.id));
      focusMainHeading();
    } on Object catch (failure) {
      ArcaneSonner.error('Could not open the files ($failure). Pick plugins/Animus or a complete zip of it.');
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
    ArcaneSonner.success('Exported ${exported.exported} documents as a Gloss bundle.'
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
    final List<DocumentLocation> clashes = glossConflicts(imported, component.store.workspace?.documents ?? const <AnimusDocument>[]);
    if (clashes.isNotEmpty) {
      setState(() => _glossPending = (imported: imported, file: picked.$1, clashes: clashes));
      return;
    }
    await _commitGloss(imported, picked.$1);
  }

  Future<void> _commitGloss(GlossImport imported, String file) async {
    if (_glossPending != null) setState(() => _glossPending = null);
    await component.store.commit(
      documents: <DocumentChange>[
        for (final GlossImportedDocument document in imported.documents) DocumentChange(document.location.kind, document.location.id, document.source),
      ],
      label: 'import $file',
    );
    final int invalid = imported.documents
        .where((GlossImportedDocument d) => !(component.store.document(d.location.kind, d.location.id)?.valid ?? false))
        .length;
    final String skipped = imported.skipped.isEmpty ? '' : ' Skipped: ${imported.skipped.join('; ')}.';
    final String notes = imported.notes.isEmpty ? '' : ' ${imported.notes.join(' ')}';
    if (invalid > 0) {
      ArcaneSonner.warning('Imported ${imported.documents.length} documents from $file; $invalid use features Animus refuses.$skipped$notes');
    } else {
      ArcaneSonner.success('Imported ${imported.documents.length} documents from $file.$skipped$notes');
    }
  }

  Widget _glossConfirm(({GlossImport imported, String file, List<DocumentLocation> clashes}) pending) {
    final List<DocumentLocation> clashes = pending.clashes;
    final String named = clashes.take(5).map((DocumentLocation l) => '${l.kind.label} ${l.id}').join(', ');
    final String more = clashes.length > 5 ? ' and ${clashes.length - 5} more' : '';
    return ConfirmRow(
      key: const ValueKey<String>('gloss-confirm'),
      question: 'Replace ${clashes.length} ${clashes.length == 1 ? 'document' : 'documents'} with the copies in ${pending.file}?',
      detail: '$named$more. Undo brings them back.',
      confirmLabel: 'Replace and import',
      onConfirm: () => _commitGloss(pending.imported, pending.file),
      onCancel: () => setState(() => _glossPending = null),
    );
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
  /// move takes the references along: NPCs listing a renamed behavior or
  /// group, the animations and NPCs of a renamed rig, and the NPCs and routes
  /// that play a renamed recording.
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
        } else if (renamedReferences(kind, document.id, id, other.kind, value) case final JsonMap renamed) {
          changes.add(DocumentChange(other.kind, other.id, prettyJson(renamed)));
        }
      }
    }
    await component.store.commit(documents: changes, label: copy ? 'duplicate ${document.id} as $id' : 'rename ${document.id} to $id');
    final int references = changes.length - (copy ? 1 : 2);
    ArcaneSonner.success(copy
        ? 'Copied ${document.id} as $id.'
        : 'Moved ${document.id} to $id${references > 0 ? ' and updated $references reference${references == 1 ? '' : 's'}' : ''}.');
    component.onNavigate(DocumentRoute(workspace.id, kind, id));
    focusMainHeading();
    return null;
  }

  TopbarActions _actions(WorkspaceSnapshot? workspace) {
    final EditorStore store = component.store;
    final bool idle = !_busy;
    return TopbarActions(
      onPalette: _openPalette,
      onToggleTheme: component.onToggleTheme,
      onOpenFolder: idle ? () => _open(pickDirectory) : null,
      onOpenZip: idle ? () => _open(pickZip) : null,
      onUndo: workspace != null && store.canUndo ? _undo : null,
      onRedo: workspace != null && store.canRedo ? _redo : null,
      onImportBbmodel: workspace != null && idle ? () => _importBbmodel(workspace) : null,
      onImportGloss: workspace != null ? _importGloss : null,
      onExportZip: workspace != null ? () => exportWorkspace(workspace) : null,
      onExportGloss: workspace != null ? () => _exportGloss(workspace) : null,
      onDocuments: workspace != null ? (_drawerOpen ? _closeDrawer : _openDrawer) : null,
    );
  }

  @override
  Widget build(BuildContext context) {
    final EditorStore store = component.store;
    final WorkspaceSnapshot? workspace = store.workspace;
    return dom.div(classes: 'animus-shell', <Widget>[
      dom.button(
        classes: 'animus-skip',
        attributes: const <String, String>{'type': 'button'},
        events: <String, void Function(web.Event)>{'click': (web.Event _) => focusById(mainId)},
        <Widget>[Component.text(workspace != null ? 'Skip to document' : 'Skip to content')],
      ),
      ShellTopbar(
        actions: _actions(workspace),
        dark: component.brightness == Brightness.dark,
        compact: _compact,
        workspace: workspace,
        // The live bar shows the connection inside a server workspace, so the
        // top bar shows it only on the screens without one.
        session: _showsLiveBar(workspace) ? null : component.session,
        undoLabel: store.undoLabel,
        redoLabel: store.redoLabel,
        drawerOpen: _drawerOpen,
      ),
      if (!store.persistent)
        const dom.div(key: ValueKey<String>('storage-banner'), classes: 'animus-banner is-warning', attributes: <String, String>{'role': 'status'}, <Widget>[
          Component.text('This browser is not keeping workspaces. Export a zip before closing the tab.'),
        ]),
      if (store.problem != null) _problemBanner(store, workspace),
      if (component.route is PairRoute)
        PairPage(
          key: ValueKey<String>('pair:${(component.route as PairRoute).code}'),
          code: (component.route as PairRoute).code,
          hub: component.hub,
          onPaired: (String id) => replaceHash(ServerRoute(id, const <String>[]).format()),
        )
      else if (component.route is ServerRoute && component.hub.server((component.route as ServerRoute).serverId) == null)
        const dom.main_(id: mainId, classes: 'animus-home', attributes: <String, String>{'tabindex': '-1'}, <Widget>[
          dom.section(classes: 'animus-panel is-error', <Widget>[
            dom.h1(id: mainHeadingId, attributes: <String, String>{'tabindex': '-1'}, <Widget>[Component.text('Server not paired')]),
            dom.p(<Widget>[Component.text('Run /animus editor pair and open the link, or paste it under Servers.')]),
            dom.p(<Widget>[dom.a(href: '#/', <Widget>[Component.text('Back to workspaces')])]),
          ]),
        ])
      else if (workspace == null && component.route is CitizensRoute)
        CitizensScreen(key: const ValueKey<String>('citizens'), hub: component.hub)
      else if (workspace == null)
        _home(store)
      else
        _workspace(workspace),
      if (_paletteOpen) CommandPalette(key: const ValueKey<String>('palette'), commands: _commands(workspace), onClose: _closePalette),
    ]);
  }

  /// True when the route renders the workspace with its live bar.
  bool _showsLiveBar(WorkspaceSnapshot? workspace) {
    final EditorRoute route = component.route;
    if (workspace == null || workspace.server == null || component.session == null || route is PairRoute) return false;
    return !(route is ServerRoute && component.hub.server(route.serverId) == null);
  }

  Widget _problemBanner(EditorStore store, WorkspaceSnapshot? workspace) =>
      dom.div(key: const ValueKey<String>('banner'), classes: 'animus-banner is-error', attributes: const <String, String>{'role': 'alert'}, <Widget>[
        dom.span(<Widget>[Component.text(store.problem!)]),
        dom.div(classes: 'animus-row', <Widget>[
          if (store.hasUnsavedEdit) ...<Widget>[
            Button.secondary(label: 'Save again', size: ButtonSize.small, onPressed: store.saveAgain),
            if (workspace != null) Button.ghost(label: 'Export zip', size: ButtonSize.small, onPressed: () => exportWorkspace(workspace)),
          ] else
            Button.ghost(label: 'Dismiss', size: ButtonSize.small, onPressed: store.dismissProblem),
        ]),
      ]);

  List<PaletteCommand> _commands(WorkspaceSnapshot? workspace) {
    final EditorStore store = component.store;
    final bool dark = component.brightness == Brightness.dark;
    return <PaletteCommand>[
      if (workspace != null) ...<PaletteCommand>[
        if (store.canUndo) PaletteCommand('Edit', 'Undo ${store.undoLabel}', _undo, hint: 'Ctrl+Z', keywords: const <String>['undo']),
        if (store.canRedo) PaletteCommand('Edit', 'Redo ${store.redoLabel}', _redo, hint: 'Ctrl+Shift+Z', keywords: const <String>['redo']),
        PaletteCommand('Workspace', 'New document', () => component.onNavigate(NewDocumentRoute(workspace.id)), keywords: const <String>['create', 'add']),
        PaletteCommand('Workspace', 'New conversation', () => component.onNavigate(NewDocumentRoute.conversation(workspace.id)), keywords: const <String>['dialogue', 'talk', 'create']),
        PaletteCommand('Workspace', 'Export the data folder zip', () => exportWorkspace(workspace), keywords: const <String>['download', 'save']),
        PaletteCommand('Workspace', 'Export a Gloss bundle', () => _exportGloss(workspace), keywords: const <String>['download']),
        PaletteCommand('Workspace', 'Import a Blockbench model', () => _importBbmodel(workspace), keywords: const <String>['bbmodel', 'rig']),
        PaletteCommand('Workspace', 'Import a Gloss bundle', _importGloss),
        if (workspace.server == null) PaletteCommand('Workspace', 'Send to a server', () => component.onNavigate(SyncRoute(workspace.id)), keywords: const <String>['push', 'pull', 'sync']),
        PaletteCommand('Workspace', 'Browse NPCs', () => component.onNavigate(NpcListRoute(workspace.id)), keywords: const <String>['search', 'filter']),
        if (workspace.documents.any((AnimusDocument document) => document.kind == DocumentKind.recording))
          PaletteCommand('Workspace', 'Browse recordings', () => component.onNavigate(RecordingListRoute(workspace.id)), keywords: const <String>['performance', 'playback']),
        PaletteCommand('Workspace', 'Workspace overview', () => component.onNavigate(WorkspaceRoute(workspace.id))),
        for (final AnimusDocument document in workspace.documents)
          PaletteCommand(
            'Documents',
            guiPlainText(document.title),
            () => component.onNavigate(DocumentRoute(workspace.id, document.kind, document.id)),
            hint: document.title == document.id ? document.kind.label : '${document.kind.label}, ${document.id}',
            keywords: <String>[document.id, document.kind.wireName, 'open'],
          ),
      ],
      for (final WorkspaceSummary summary in store.workspaces)
        if (summary.id != workspace?.id)
          PaletteCommand(
            'Workspaces',
            'Switch to ${summary.name}',
            () => component.onNavigate(summary.serverId != null && component.hub.server(summary.serverId!) != null
                ? ServerRoute(summary.serverId!, const <String>[])
                : WorkspaceRoute(summary.id)),
            hint: '${summary.documentCount} document${summary.documentCount == 1 ? '' : 's'}',
          ),
      PaletteCommand('Workspaces', 'All workspaces', () => component.onNavigate(const HomeRoute()), keywords: const <String>['home']),
      PaletteCommand('App', dark ? 'Use light theme' : 'Use dark theme', component.onToggleTheme, keywords: const <String>['theme', 'dark', 'light']),
      PaletteCommand('App', 'Open data folder', () => _open(pickDirectory)),
      PaletteCommand('App', 'Open zip', () => _open(pickZip)),
      PaletteCommand('App', 'Convert Citizens and others', () => component.onNavigate(const CitizensRoute())),
    ];
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
  bool _movable(DocumentKind kind) => kind != DocumentKind.skin && kind != DocumentKind.presets && kind != DocumentKind.serverBehaviors;

  Widget _workspace(WorkspaceSnapshot workspace) {
    final EditorRoute route = component.route;
    final DocumentRoute? selected = route is DocumentRoute ? route : null;
    final AnimusDocument? document = selected == null ? null : component.store.document(selected.kind, selected.documentId);
    final LiveSession? session = workspace.server == null ? null : component.session;
    return dom.div(key: ValueKey<String>('workspace:${workspace.id}'), classes: 'animus-workspace', <Widget>[
      ShellSidebar(
        workspace: workspace,
        route: route,
        compact: _compact,
        open: _drawerOpen,
        onClose: _closeDrawer,
        session: component.session,
      ),
      dom.main_(id: mainId, classes: 'animus-main', attributes: const <String, String>{'tabindex': '-1'}, <Widget>[
        if (_glossPending != null) _glossConfirm(_glossPending!),
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
                ? (JsonUpdate update, {String? coalesce}) => component.store.commit(
                      documents: <DocumentChange>[DocumentChange.update(document.kind, document.id, update)],
                      coalesce: coalesce == null ? null : '${document.kind.wireName}:${document.id}:$coalesce',
                    )
                : null,
            onRename: _movable(document.kind) ? (String id) => _relocate(workspace, document, id, copy: false) : null,
            onDuplicate: _movable(document.kind) && document.kind != DocumentKind.recording ? (String id) => _relocate(workspace, document, id, copy: true) : null,
            onDelete: () async {
              await component.store.commit(documents: <DocumentChange>[DocumentChange(document.kind, document.id, null)], label: 'delete ${document.id}');
              component.onNavigate(WorkspaceRoute(workspace.id));
              focusMainHeading();
              ArcaneSonner.info('Deleted ${document.id}. Undo brings it back.');
            },
            skinDocument: _skinOf(document),
            resources: workspace.resources,
            editor: _rigEditor(workspace, document),
            serverDiagnostics: session?.serverDiagnostics[documentKey(document.kind, document.id)] ?? const <Diagnostic>[],
            liveSchemas: session?.schemas,
            workspaceDocuments: workspace.documents,
            onOpen: (DocumentKind kind, String id) => component.onNavigate(DocumentRoute(workspace.id, kind, id)),
            onCommit: (List<DocumentChange> changes, String label) => component.store.commit(documents: changes, label: label),
            onServerPreview: session == null ? null : () => session.startPreview(),
            newConversationHref: NewDocumentRoute.conversation(workspace.id).format(),
          )
        else if (selected != null)
          dom.section(classes: 'animus-panel is-error', <Widget>[
            dom.h1(id: mainHeadingId, attributes: const <String, String>{'tabindex': '-1'}, <Widget>[
              Component.text('${selected.kind.label}: ${selected.documentId} is not in this workspace'),
            ]),
            const dom.p(<Widget>[Component.text('It was renamed or deleted, or the link is from another workspace.')]),
            dom.p(<Widget>[
              dom.a(href: WorkspaceRoute(workspace.id).format(), const <Widget>[Component.text('Open the workspace overview')]),
            ]),
          ])
        else if (route is RecordingListRoute)
          RecordingList(key: ValueKey<String>('recording-list:${workspace.id}'), workspace: workspace)
        else if (route is NpcListRoute)
          NpcBrowser(key: ValueKey<String>('npc-browser:${workspace.id}'), store: component.store, workspace: workspace, session: session)
        else if (route is NewDocumentRoute)
          NewDocumentPage(
            key: ValueKey<String>('new:${workspace.id}:${route.folder ?? ''}:${route.conversation}'),
            store: component.store,
            workspace: workspace,
            kind: route.kind,
            folder: route.folder,
            conversation: route.conversation,
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

  Widget _overview(WorkspaceSnapshot workspace) {
    final List<AnimusDocument> invalid = workspace.documents.where((AnimusDocument document) => !document.valid).toList();
    return dom.div(classes: 'animus-overview', <Widget>[
      dom.section(classes: 'animus-panel', <Widget>[
        dom.h1(id: mainHeadingId, attributes: const <String, String>{'tabindex': '-1'}, <Widget>[Component.text(workspace.name)]),
        if (workspace.documents.isNotEmpty)
          dom.div(classes: 'animus-stats', <Widget>[
            for (final DocumentKind kind in listedKinds)
              if (workspace.documents.any((AnimusDocument document) => document.kind == kind))
                if (kind == DocumentKind.serverBehaviors)
                  dom.div(classes: 'animus-stat', <Widget>[
                    dom.strong(<Widget>[Component.text('${listedServerBehaviors(workspace.documents).length}')]),
                    const dom.span(<Widget>[Component.text('Server behaviors listed to run')]),
                  ])
                else
                dom.div(classes: 'animus-stat', <Widget>[
                  dom.strong(<Widget>[Component.text('${workspace.documents.where((AnimusDocument document) => document.kind == kind).length}')]),
                  dom.span(<Widget>[Component.text(kind.label)]),
                ]),
          ])
        else
          dom.div(classes: 'animus-empty', <Widget>[
            const BrandMark(size: 56, classes: 'animus-empty-mark'),
            dom.div(<Widget>[
              const dom.p(<Widget>[
                Component.text('No documents yet.'),
              ]),
              dom.div(classes: 'animus-row', <Widget>[
                Button.primary(label: 'Create an NPC', size: ButtonSize.small, onPressed: () => component.onNavigate(NewDocumentRoute(workspace.id, DocumentKind.npc))),
                Button.ghost(label: 'All kinds', size: ButtonSize.small, onPressed: () => component.onNavigate(NewDocumentRoute(workspace.id))),
              ]),
            ]),
          ]),
      ]),
      if (workspace.documents.isNotEmpty) _starters(workspace),
      if (workspace.documents.isNotEmpty)
        dom.section(classes: 'animus-panel${invalid.isEmpty ? '' : ' is-error'}', <Widget>[
          dom.h2(<Widget>[
            Component.text(invalid.isEmpty
                ? 'Every document passes the shared schemas'
                : '${invalid.length} ${invalid.length == 1 ? 'document needs' : 'documents need'} repair'),
          ]),
          if (invalid.isNotEmpty)
            dom.ul(classes: 'animus-problem-list', <Widget>[
              for (final AnimusDocument document in invalid)
                dom.li(<Widget>[
                  dom.a(href: DocumentRoute(workspace.id, document.kind, document.id).format(), <Widget>[
                    Component.text('${document.kind.label}: ${document.id}'),
                  ]),
                  dom.span(classes: 'animus-muted', <Widget>[Component.text(_firstError(document))]),
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

  String _firstError(AnimusDocument document) {
    for (final Diagnostic diagnostic in document.diagnostics) {
      if (diagnostic.severity == DiagnosticSeverity.error) return '${diagnostic.path}: ${diagnostic.message}';
    }
    return '';
  }
}
