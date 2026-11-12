import 'dart:convert';
import 'dart:js_interop';
import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../logic/data_folder.dart';
import '../logic/gui/gloss_bundle.dart';
import '../logic/gui/gui_json.dart';
import '../logic/gui/gui_text.dart';
import '../logic/npc/npc_integrations.dart';
import '../logic/offline/new_documents.dart';
import '../logic/npc/skin_edits.dart';
import '../logic/recording/rename_references.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../model/server_binding.dart';
import '../services/browser.dart';
import '../state/catalog_entry.dart';
import '../state/editor_store.dart';
import '../state/live_session.dart';
import '../state/server_hub.dart';
import '../state/workspace_repository.dart';
import '../state/workspace_route.dart';
import 'citizens_screen.dart';
import '../logic/command_search.dart';
import 'command_palette.dart';
import 'document_view.dart';
import 'gui/gui_fields.dart';
import 'npc/npc_browser.dart';
import 'offline/home_screen.dart';
import 'offline/kind_list_page.dart';
import 'offline/new_document_page.dart';
import 'offline/offline_fields.dart';
import 'offline/sync_page.dart';
import 'recording/recording_list.dart';
import 'select/picker_scope.dart';
import 'rig/rig_actions.dart';
import 'rig/rig_editor.dart';
import 'server/live_bar.dart';
import 'server/pair_page.dart';
import 'server/server_overview.dart';
import 'shell_sidebar.dart';
import 'shortcut_sheet.dart';
import 'shortcuts.dart';
import 'shell_topbar.dart';
import 'workspace_overview.dart';
import '../logic/plural.dart';

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

  /// Why the last folder or zip opened nothing, shown on home.
  String? _openProblem;
  bool _compact = false;
  bool _drawerOpen = false;
  bool _paletteOpen = false;
  bool _sheetOpen = false;

  /// The sidebar hidden on wide screens (Cmd or Ctrl+B); phones use the
  /// drawer instead.
  bool _sidebarHidden = false;

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
    // The live bar's last notice was news on the page it came from.
    if (oldComponent.route != component.route) oldComponent.session?.markLogSeen();
  }

  /// The page-wide shortcuts ([shortcutGroups]). Keys typed into a text
  /// box stay with the box; Escape is handled by whatever has focus.
  void _shortcut(web.KeyboardEvent event) {
    if (_paletteOpen || _sheetOpen || isTypingTarget(event.target)) return;
    final ShortcutAction? action = matchShortcut(key: event.key, mod: event.ctrlKey || event.metaKey, shift: event.shiftKey, alt: event.altKey);
    if (action == null) return;
    final WorkspaceSnapshot? workspace = component.store.workspace;
    if (workspace == null && action != ShortcutAction.palette && action != ShortcutAction.sheet) return;
    event.preventDefault();
    _run(action, workspace);
  }

  void _run(ShortcutAction action, WorkspaceSnapshot? workspace) {
    switch (action) {
      case ShortcutAction.palette:
        _openPalette();
      case ShortcutAction.sheet:
        _openSheet();
      case ShortcutAction.search:
        _focusSearch();
      case ShortcutAction.undo:
        _undo();
      case ShortcutAction.redo:
        _redo();
      case ShortcutAction.save:
        _save(workspace!);
      case ShortcutAction.sidebar:
        _toggleSidebar();
      case ShortcutAction.newDocument:
        component.onNavigate(NewDocumentRoute(workspace!.id));
      case ShortcutAction.browseNpcs:
        component.onNavigate(NpcListRoute(workspace!.id));
    }
  }

  /// Every edit is saved as it lands, so Save retries one the browser
  /// refused; in a live workspace it presses Apply.
  void _save(WorkspaceSnapshot workspace) {
    if (workspace.server != null && component.session != null) {
      final web.HTMLButtonElement? apply = web.document.getElementById('animus-live-apply') as web.HTMLButtonElement?;
      if (apply != null && !apply.disabled) {
        apply.click();
      } else {
        ArcaneSonner.info('Nothing to apply. Edits are kept as drafts until Apply.');
      }
    } else if (component.store.hasUnsavedEdit) {
      component.store.saveAgain();
    } else {
      ArcaneSonner.info('Saved. Every edit is kept in this browser as you make it.');
    }
  }

  void _toggleSidebar() {
    if (_compact) {
      _drawerOpen ? _closeDrawer() : _openDrawer();
    } else {
      setState(() => _sidebarHidden = !_sidebarHidden);
    }
  }

  void _focusSearch() {
    if (_compact && !_drawerOpen) setState(() => _setDrawer(true));
    if (!_compact && _sidebarHidden) setState(() => _sidebarHidden = false);
    focusWhenShown(documentSearchId);
  }

  void _setDrawer(bool open) {
    _drawerOpen = open;
    setScrollLocked(open || _paletteOpen || _sheetOpen);
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
    setScrollLocked(_drawerOpen || _sheetOpen);
  }

  void _openSheet() {
    setState(() => _sheetOpen = true);
    setScrollLocked(true);
  }

  void _closeSheet() {
    setState(() => _sheetOpen = false);
    setScrollLocked(_drawerOpen || _paletteOpen);
  }

  Future<void> _undo() async {
    final String? label = await component.store.undo();
    if (label != null) ArcaneSonner.info('Undid: $label');
  }

  Future<void> _redo() async {
    final String? label = await component.store.redo();
    if (label != null) ArcaneSonner.info('Redid: $label');
  }

  Future<void> _open(Future<PickedFiles?> Function() pick, {required bool zip}) async {
    setState(() {
      _busy = true;
      _openProblem = null;
    });
    try {
      final PickedFiles? picked = await pick();
      if (picked == null) return;
      final WorkspaceSnapshot snapshot = await component.store.openFiles(picked.name, picked.files, requireDocuments: true, zip: zip);
      final int invalid = snapshot.documents.where((AnimusDocument document) => !document.valid).length;
      final int upgraded = component.store.upgraded.length;
      final String upgrades = upgraded == 0 ? '' : ' Upgraded ${countOf(upgraded, 'file')} to schemaVersion 2.';
      if (invalid > 0) {
        ArcaneSonner.warning('Opened ${countOf(snapshot.documents.length, 'document')}; $invalid ${invalid == 1 ? 'needs' : 'need'} repair.$upgrades');
      } else {
        ArcaneSonner.success('Opened ${countOf(snapshot.documents.length, 'document')}.$upgrades');
      }
      component.onNavigate(WorkspaceRoute(snapshot.id));
      focusMainHeading();
    } on NoDocumentsFound catch (refused) {
      // Nothing was saved; home says why beside the open buttons.
      if (mounted) setState(() => _openProblem = refused.message);
      if (component.route is! HomeRoute) ArcaneSonner.error(refused.message);
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
    ArcaneSonner.success('Exported ${countOf(exported.exported, 'document')} as a Gloss bundle.'
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
      ArcaneSonner.warning('Imported ${countOf(imported.documents.length, 'document')} from $file; $invalid ${invalid == 1 ? 'uses' : 'use'} features Animus refuses.$skipped$notes');
    } else {
      ArcaneSonner.success('Imported ${countOf(imported.documents.length, 'document')} from $file.$skipped$notes');
    }
  }

  Widget _glossConfirm(({GlossImport imported, String file, List<DocumentLocation> clashes}) pending) {
    final List<DocumentLocation> clashes = pending.clashes;
    final String named = clashes.take(5).map((DocumentLocation l) => '${l.kind.label} ${l.id}').join(', ');
    final String more = clashes.length > 5 ? ' and ${clashes.length - 5} more' : '';
    return ConfirmRow(
      key: const ValueKey<String>('gloss-confirm'),
      question: 'Replace ${countOf(clashes.length, 'document')} with the copies in ${pending.file}?',
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
      onOpenFolder: idle ? () => _open(pickDirectory, zip: false) : null,
      onOpenZip: idle ? () => _open(pickZip, zip: true) : null,
      onUndo: workspace != null && store.canUndo ? _undo : null,
      onRedo: workspace != null && store.canRedo ? _redo : null,
      onImportBbmodel: workspace != null && idle ? () => _importBbmodel(workspace) : null,
      onImportGloss: workspace != null ? _importGloss : null,
      onExportZip: workspace != null ? () => exportWorkspace(workspace) : null,
      onExportGloss: workspace != null ? () => _exportGloss(workspace) : null,
      onDocuments: workspace != null ? (_drawerOpen ? _closeDrawer : _openDrawer) : null,
      onShowSidebar: workspace != null && _sidebarHidden && !_compact ? _toggleSidebar : null,
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
        CitizensScreen(key: const ValueKey<String>('citizens'), hub: component.hub, store: component.store, onNavigate: component.onNavigate)
      else if (workspace == null)
        _home(store)
      else
        _workspace(workspace),
      if (_paletteOpen) CommandPalette(key: const ValueKey<String>('palette'), commands: _commands(workspace), onClose: _closePalette),
      if (_sheetOpen) ShortcutSheet(key: const ValueKey<String>('shortcuts'), onClose: _closeSheet),
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
        if (store.canUndo) PaletteCommand('Edit', 'Undo ${store.undoLabel}', _undo, hint: platformKeys('Mod+Z'), keywords: const <String>['undo']),
        if (store.canRedo) PaletteCommand('Edit', 'Redo ${store.redoLabel}', _redo, hint: platformKeys('Mod+Shift+Z'), keywords: const <String>['redo']),
        PaletteCommand('Edit', workspace.server == null ? 'Save' : 'Apply to the server', () => _save(workspace), hint: platformKeys('Mod+S'), keywords: const <String>['save', 'apply']),
        PaletteCommand('Workspace', 'Search documents', _focusSearch, hint: platformKeys('Mod+F'), keywords: const <String>['find', 'filter']),
        PaletteCommand('Workspace', 'New document', () => component.onNavigate(NewDocumentRoute(workspace.id)), hint: 'N', keywords: const <String>['create', 'add']),
        PaletteCommand('Workspace', 'New conversation', () => component.onNavigate(NewDocumentRoute.conversation(workspace.id)), keywords: const <String>['dialogue', 'talk', 'create']),
        PaletteCommand('Workspace', 'Export the data folder zip', () => exportWorkspace(workspace), keywords: const <String>['download', 'save']),
        PaletteCommand('Workspace', 'Export a Gloss bundle', () => _exportGloss(workspace), keywords: const <String>['download']),
        PaletteCommand('Workspace', 'Import a Blockbench model', () => _importBbmodel(workspace), keywords: const <String>['bbmodel', 'rig']),
        PaletteCommand('Workspace', 'Import a Gloss bundle', _importGloss),
        if (workspace.server == null) PaletteCommand('Workspace', 'Send to a server', () => component.onNavigate(SyncRoute(workspace.id)), keywords: const <String>['push', 'pull', 'sync']),
        PaletteCommand('Workspace', 'Browse NPCs', () => component.onNavigate(NpcListRoute(workspace.id)), hint: 'B', keywords: const <String>['search', 'filter']),
        PaletteCommand('Workspace', _compact || !_sidebarHidden ? 'Show or hide the sidebar' : 'Show the sidebar', _toggleSidebar, hint: platformKeys('Mod+B'), keywords: const <String>['documents', 'panel']),
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
            hint: countOf(summary.documentCount, 'document'),
          ),
      PaletteCommand('Workspaces', 'All workspaces', () => component.onNavigate(const HomeRoute()), keywords: const <String>['home']),
      PaletteCommand('App', 'Keyboard shortcuts', _openSheet, hint: '?', keywords: const <String>['keys', 'help', 'hotkeys']),
      PaletteCommand('App', dark ? 'Use light theme' : 'Use dark theme', component.onToggleTheme, keywords: const <String>['theme', 'dark', 'light']),
      PaletteCommand('App', 'Open data folder', () => _open(pickDirectory, zip: false)),
      PaletteCommand('App', 'Open zip', () => _open(pickZip, zip: true)),
      PaletteCommand('App', converterTitle, () => component.onNavigate(const CitizensRoute()), keywords: const <String>['citizens', 'shopkeepers', 'denizen', 'convert']),
    ];
  }

  Widget _home(EditorStore store) => HomeScreen(
        store: store,
        hub: component.hub,
        busy: _busy,
        openProblem: _openProblem,
        onOpenFolder: () => _open(pickDirectory, zip: false),
        onOpenZip: () => _open(pickZip, zip: true),
        onNavigate: component.onNavigate,
      );

  /// Kinds whose id the user picks (a skin's is its content hash, presets
  /// have one fixed id).
  bool _movable(DocumentKind kind) => kind != DocumentKind.skin && kind != DocumentKind.presets && kind != DocumentKind.serverBehaviors;

  Widget _workspace(WorkspaceSnapshot workspace) {
    final EditorRoute route = component.route;
    final DocumentRoute? selected = route is DocumentRoute ? route : null;
    final AnimusDocument? document = selected == null ? null : component.store.shown(selected.kind, selected.documentId);
    final LiveSession? session = workspace.server == null ? null : component.session;
    return PickerScope(
      workspaceId: workspace.id,
      route: route,
      onNavigate: component.onNavigate,
      child: dom.div(key: ValueKey<String>('workspace:${workspace.id}'), classes: 'animus-workspace${_sidebarHidden && !_compact ? ' is-sidebar-hidden' : ''}', <Widget>[
      ShellSidebar(
        workspace: workspace,
        route: route,
        compact: _compact,
        open: _drawerOpen,
        onClose: _closeDrawer,
        session: component.session,
        hasCodeDraft: component.store.hasCodeDraft,
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
            codeDraft: component.store.codeDraft(document.kind, document.id),
            onCodeDraft: (String? text) => component.store.setCodeDraft(document.kind, document.id, text),
            onJson: (JsonUpdate update, {String? coalesce}) => component.store.commit(
                  documents: <DocumentChange>[DocumentChange.update(document.kind, document.id, update)],
                  coalesce: coalesce == null ? null : '${document.kind.wireName}:${document.id}:$coalesce',
                ),
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
            liveLookups: session?.lookups,
            workspaceDocuments: workspace.documents,
            onOpen: (DocumentKind kind, String id) => component.onNavigate(DocumentRoute(workspace.id, kind, id)),
            onCommit: (List<DocumentChange> changes, String label) => component.store.commit(documents: changes, label: label),
            onServerPreview: session == null ? null : () => session.startPreview(),
            newConversationHref: NewDocumentRoute.conversation(workspace.id).format(),
            serverBehaviors: <String>[
              for (final CatalogEntry entry in session?.catalog ?? const <CatalogEntry>[])
                if (entry.kind == DocumentKind.behavior) entry.id,
            ],
            serverPlugins: reportedPlugins(session?.connection.capabilities),
            skinEditing: SkinEditing(
              resources: workspace.resources,
              commit: (SkinChange change) => component.store.commit(documents: change.documents, resources: change.resources, label: change.label),
            ),
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
        else if (route is KindListRoute)
          KindListPage(key: ValueKey<String>('list:${workspace.id}:${route.kind.wireName}'), workspace: workspace, kind: route.kind)
        else if (route is NewDocumentRoute)
          NewDocumentPage(
            key: ValueKey<String>('new:${workspace.id}:${route.folder ?? ''}:${route.name ?? ''}:${route.conversation}'),
            store: component.store,
            workspace: workspace,
            kind: route.kind,
            folder: route.folder,
            name: route.name,
            conversation: route.conversation,
            owner: session?.connection.token?.admin,
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
          WorkspaceOverview(
            workspace: workspace,
            ignored: component.store.ignored,
            upgraded: component.store.upgraded,
            onNavigate: component.onNavigate,
          ),
      ]),
    ]),
    );
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
