/// The app frame: a fixed-height grid where only the page body scrolls. The
/// top bar, the nav (an icon rail at 1100 px and below, a drawer on phones)
/// and the save bar stay on screen. Owns the page-wide shortcuts, the Mode
/// switch, search, Review and the confirmations.
library;

import 'dart:async';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../logic/command_search.dart';
import '../logic/gui/gui_json.dart';
import '../logic/npc/npc_integrations.dart';
import '../logic/npc/skin_edits.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../model/display_name.dart' show needsPath;
import '../model/document_summary.dart' show DocumentSummary;
import '../model/server_binding.dart' show documentKey;
import '../services/browser.dart';
import '../services/server/connection_status.dart';
import '../state/catalog_entry.dart';
import '../state/editor_store.dart';
import '../state/library.dart';
import '../state/live_session.dart';
import '../state/server_hub.dart';
import '../state/workspace_repository.dart';
import '../state/workspace_route.dart';
import '../ui/alert.dart';
import '../ui/confirm_dialog.dart';
import '../ui/edit_scope.dart';
import '../ui/empty_state.dart';
import '../ui/editor_mode.dart';
import '../ui/mode.dart';
import '../ui/page_header.dart';
import '../ui/library_rows.dart' show npcLine;
import '../ui/recent.dart';
import '../ui/save_state.dart' show ServerLink;
import '../ui/sheet.dart';
import 'advanced_notice.dart';
import 'citizens_screen.dart';
import 'document_view.dart';
import 'document_wait.dart';
import 'list_page.dart';
import 'npc/npc_browser.dart';
import 'offline/home_screen.dart';
import 'offline/new_document_page.dart';
import 'offline/sync_page.dart';
import 'recording/movement_page.dart';
import 'rig/rig_actions.dart' show exportWorkspace;
import 'select/picker_scope.dart';
import 'server/live_bar.dart';
import 'server/pair_page.dart';
import 'server/server_overview.dart';
import 'shell_actions.dart';
import 'shell_save_bar.dart';
import 'shell_sidebar.dart';
import 'shell_topbar.dart';
import 'shortcut_sheet.dart';
import 'shortcuts.dart';
import 'workspace_overview.dart';

/// The id of every workspace page's `<main>`, the target of the skip link.
const String mainId = 'animus-main';

/// Widths at which the nav becomes a drawer and the top bar a short row.
const String compactQuery = '(max-width: 760px)';

/// The most Search lists at once.
const int paletteLimit = 60;

/// Widths at which the nav is an icon rail.
const String railQuery = '(max-width: 1100px)';

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

class _EditorShellState extends State<EditorShell> with WorkspaceActions {
  bool _compact = false;
  bool _rail = false;
  bool _navOpen = false;
  bool _paletteOpen = false;

  /// What Search holds; a new opening starts empty.
  String _paletteQuery = '';
  int _paletteOpens = 0;
  bool _sheetOpen = false;
  bool _reviewOpen = false;
  EditorMode _mode = loadMode();
  List<String> _recent = const <String>[];
  String? _recentFor;
  late final SaveFlow _flow = SaveFlow(() {
    if (mounted) setState(() {});
  });
  final List<void Function()> _stops = <void Function()>[];
  final ArrivalNotices _arrivals = ArrivalNotices(window: const Duration(seconds: 1));
  String? _arrivalToast;
  StreamSubscription<List<DocumentRef>>? _serverArrivals;

  @override
  void initState() {
    super.initState();
    final (bool compact, void Function() stopCompact) = watchMedia(compactQuery, (bool compact) {
      if (!mounted) return;
      setState(() {
        _compact = compact;
        if (!compact) _navOpen = false;
      });
    });
    final (bool rail, void Function() stopRail) = watchMedia(railQuery, (bool rail) {
      if (mounted) setState(() => _rail = rail);
    });
    _compact = compact;
    _rail = rail;
    _stops.addAll(<void Function()>[stopCompact, stopRail, onKeyDown(_shortcut)]);
    component.store.addListener(_storeChanged);
    _listenForArrivals();
    _remember();
  }

  /// Documents the paired server gains while Simple hides their kind.
  void _listenForArrivals() {
    _serverArrivals?.cancel();
    _serverArrivals = component.session?.arrivals.listen((List<DocumentRef> refs) {
      final String? workspaceId = component.store.workspace?.id;
      if (workspaceId != null) announce(Arrival.server, <ArrivedDocument>[for (final DocumentRef ref in refs) (kind: ref.kind, id: ref.id)], workspaceId);
    });
  }

  @override
  bool announce(Arrival arrival, List<ArrivedDocument> documents, String workspaceId) {
    if (_mode != EditorMode.simple) return false;
    final AdvancedNotice? notice = _arrivals.add(arrival, documents);
    if (notice == null) return false;
    final String? shown = _arrivalToast;
    if (shown != null) ArcaneSonner.dismiss(shown);
    _arrivalToast = ArcaneSonner.info(
      notice.message,
      duration: 8000,
      action: ToastAction(label: 'Switch to Advanced', onPressed: () => _showInAdvanced(notice.landing(workspaceId))),
    );
    return true;
  }

  /// Switch to Advanced from an arrival notice, landing on what arrived.
  void _showInAdvanced(EditorRoute landing) {
    final String? shown = _arrivalToast;
    if (shown != null) ArcaneSonner.dismiss(shown);
    _arrivalToast = null;
    _setMode(EditorMode.advanced);
    component.onNavigate(landing);
  }

  @override
  void dispose() {
    for (final void Function() stop in _stops) {
      stop();
    }
    component.store.removeListener(_storeChanged);
    _serverArrivals?.cancel();
    _flow.dispose();
    setScrollLocked(false);
    super.dispose();
  }

  @override
  void didUpdateWidget(EditorShell oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (oldComponent.route != component.route) {
      _navOpen = false;
      oldComponent.session?.markLogSeen();
    }
    // Review belongs to one live session; a new pairing or another server
    // starts without it.
    if (!identical(oldComponent.session, component.session)) {
      _reviewOpen = false;
      _listenForArrivals();
    }
    _remember();
  }

  void _storeChanged() => _flow.watch(component.store);

  /// Keeps the open document at the top of the workspace's Recent.
  void _remember() {
    final WorkspaceSnapshot? workspace = component.store.workspace;
    if (workspace == null) return;
    if (_recentFor != workspace.id) {
      _recentFor = workspace.id;
      _recent = _loadRecent(workspace.id);
    }
    final EditorRoute route = component.route;
    if (route is DocumentRoute && route.workspaceId == workspace.id) {
      final String key = '${route.kind.wireName}:${route.documentId}';
      if (_recent.isEmpty || _recent.first != key) {
        _recent = pushRecent(_recent, key);
        _saveRecent(workspace.id, _recent);
      }
    }
  }

  static List<String> _loadRecent(String workspaceId) {
    try {
      return decodeRecent(web.window.localStorage.getItem(recentStorageKey(workspaceId)));
    } on Object {
      return const <String>[];
    }
  }

  static void _saveRecent(String workspaceId, List<String> keys) {
    try {
      web.window.localStorage.setItem(recentStorageKey(workspaceId), encodeRecent(keys));
    } on Object {
      // Storage blocked: Recent lasts for this page.
    }
  }

  void _setMode(EditorMode mode) {
    saveMode(mode);
    setState(() => _mode = mode);
  }

  /// The page-wide shortcuts ([pageGroups]). Keys typed into a text box
  /// stay with the box, except Mod+S, which always saves.
  void _shortcut(web.KeyboardEvent event) {
    final ShortcutAction? action = matchShortcut(key: event.key, mod: event.ctrlKey || event.metaKey, shift: event.shiftKey, alt: event.altKey);
    if (action == null) return;
    if (isTypingTarget(event.target) && !firesWhileTyping(action)) return;
    if ((_paletteOpen || _sheetOpen) && action != ShortcutAction.save) return;
    final WorkspaceSnapshot? workspace = component.store.workspace;
    if (workspace == null && action != ShortcutAction.palette && action != ShortcutAction.sheet) {
      // Never the browser's own Save page.
      if (action == ShortcutAction.save) event.preventDefault();
      return;
    }
    event.preventDefault();
    _run(action, workspace);
  }

  void _run(ShortcutAction action, WorkspaceSnapshot? workspace) {
    switch (action) {
      case ShortcutAction.palette:
        _setPalette(true);
      case ShortcutAction.sheet:
        setState(() => _sheetOpen = true);
      case ShortcutAction.undo:
        _undo();
      case ShortcutAction.redo:
        _redo();
      case ShortcutAction.save:
        _save(workspace!);
      case ShortcutAction.newDocument:
        component.onNavigate(NewDocumentRoute(workspace!.id));
      case ShortcutAction.browseNpcs:
        component.onNavigate(NpcListRoute(workspace!.id));
    }
  }

  /// Mod+S: Apply on a server; in this browser every edit is kept as it
  /// lands, so it retries a refused save or says Saved.
  void _save(WorkspaceSnapshot workspace) {
    final LiveSession? session = component.session;
    if (workspace.server != null && session != null) {
      _flow.apply(session, simple: _mode == EditorMode.simple, onBlocked: _openReview);
    } else if (component.store.hasUnsavedEdit) {
      component.store.saveAgain();
    } else {
      _flow.flash();
    }
  }

  void _openReview() {
    if (mounted) setState(() => _reviewOpen = true);
  }

  void _setPalette(bool open) {
    setState(() {
      _paletteOpen = open;
      if (open) {
        _paletteQuery = '';
        _paletteOpens++;
      }
    });
    setScrollLocked(open);
  }

  static const Set<String> _libraryGroups = <String>{'Recent', 'Library'};

  /// Search on shadcn's command palette: documents first, then commands;
  /// with nothing typed, the recent documents and the commands. The editor
  /// ranks and caps the matches ([paletteLimit]) and hands over only those.
  Widget _palette(WorkspaceSnapshot? workspace) {
    final List<PaletteCommand> all = _paletteOpen ? _commands(workspace) : const <PaletteCommand>[];
    final List<PaletteCommand> pool = _paletteQuery.trim().isEmpty ? <PaletteCommand>[for (final PaletteCommand command in all) if (command.group != 'Library') command] : all;
    final bool phone = web.window.matchMedia('(max-width: 760px)').matches;
    final List<PaletteCommand> shown = filterCommands(pool, _paletteQuery).take(paletteLimit).toList();
    final int hidden = shown.isEmpty && workspace != null && _mode == EditorMode.simple && _paletteQuery.trim().isNotEmpty ? _hiddenMatches(_paletteQuery) : 0;
    // One group per heading, in the order of each group's best match.
    final Map<String, List<CommandItem>> grouped = <String, List<CommandItem>>{};
    for (final PaletteCommand command in shown) {
      (grouped[command.group] ??= <CommandItem>[]).add(CommandItem(
        label: command.label,
        // Phones have no keys to press: a command's shortcut is left out.
        shortcut: phone && !_libraryGroups.contains(command.group) ? null : command.hint,
        keywords: <String>[...command.keywords, ?command.hint],
        onSelect: command.run,
      ));
    }
    final List<CommandGroup> groups = <CommandGroup>[
      for (final MapEntry<String, List<CommandItem>> group in grouped.entries) CommandGroup(heading: group.key, items: group.value),
    ];
    return ArcaneCommand(
      key: ValueKey<int>(_paletteOpens),
      id: 'ui-search',
      isOpen: _paletteOpen,
      externalFilter: true,
      animate: false,
      // Phones have no keys: the key hints under the list go too.
      keyHints: !phone,
      placeholder: 'Search NPCs, shops, commands',
      emptyMessage: hidden > 0 ? hiddenMatchesText(hidden) : 'Nothing matches.',
      groups: groups,
      onSearch: (String query) => setState(() => _paletteQuery = query),
      onClose: () => _setPalette(false),
    );
  }

  /// How many documents only Advanced lists match [query].
  int _hiddenMatches(String query) => filterCommands(<PaletteCommand>[
        for (final DocumentKind kind in DocumentKind.values)
          if (!kindShown(kind, EditorMode.simple))
            for (final LibraryEntry entry in component.store.library(kind)) PaletteCommand('Library', entry.name, () {}, hint: kindLabel(kind), keywords: <String>[entry.id, kind.wireName]),
      ], query).length;

  Future<void> _undo() async => _toldHistory(await component.store.undo(), 'Undid');

  Future<void> _redo() async => _toldHistory(await component.store.redo(), 'Redid');

  void _toldHistory(HistoryMove? move, String done) {
    if (move == null) return;
    final String? refused = move.refused;
    refused == null ? ArcaneSonner.info('$done: ${move.label}') : ArcaneSonner.warning(refused);
  }

  void _reconnect() {
    final LiveSession? session = component.session;
    if (session == null) return;
    if (session.connection.status.phase == ConnectionPhase.reconnecting) {
      session.connection.retryNow();
    } else {
      component.hub.connect(session.serverId);
    }
  }

  TopbarActions _actions(WorkspaceSnapshot? workspace) {
    final EditorStore store = component.store;
    return TopbarActions(
      onPalette: () => _setPalette(true),
      onToggleTheme: component.onToggleTheme,
      onShortcuts: () => setState(() => _sheetOpen = true),
      onMode: _setMode,
      onNavigate: component.onNavigate,
      onOpenFolder: busy ? null : () => openFiles(pickDirectory, zip: false),
      onOpenZip: busy ? null : () => openFiles(pickZip, zip: true),
      onUndo: workspace != null && store.canUndo ? _undo : null,
      onRedo: workspace != null && store.canRedo ? _redo : null,
      onImportBbmodel: workspace != null && !busy ? () => importBbmodel(workspace) : null,
      onImportGloss: workspace != null ? importGloss : null,
      onExportZip: workspace != null ? () => exportWorkspace(workspace) : null,
      onExportGloss: workspace != null ? () => exportGloss(workspace) : null,
      onNav: workspace != null ? () => setState(() => _navOpen = !_navOpen) : null,
    );
  }

  @override
  Widget build(BuildContext context) {
    final EditorStore store = component.store;
    final WorkspaceSnapshot? workspace = store.workspace;
    final bool unpaired = component.route is ServerRoute && component.hub.server((component.route as ServerRoute).serverId) == null;
    final EditorRoute here = component.route;
    final bool framed = workspace != null && here is! PairRoute && !unpaired && !(here is CitizensRoute && here.workspaceId == null);
    final LiveSession? session = workspace?.server == null ? null : component.session;
    return ModeScope(
      mode: _mode,
      onChanged: _setMode,
      child: dom.div(
        classes: 'ui-app${framed ? '' : ' is-bare'}${_rail ? ' is-rail' : ''}${_compact ? ' is-compact' : ''}',
        <Widget>[
          dom.div(classes: 'ui-skip', <Widget>[
            Button.outline(label: 'Skip to content', onPressed: () => focusById(framed ? mainId : mainHeadingId)),
          ]),
          ShellTopbar(
            actions: _actions(workspace),
            dark: component.brightness == Brightness.dark,
            compact: _compact,
            mode: _mode,
            hub: component.hub,
            workspaces: store.workspaces,
            workspace: framed ? workspace : null,
            undoLabel: store.undoLabel,
            redoLabel: store.redoLabel,
            navOpen: _navOpen,
            elsewhere: framed && component.session?.waiting == true,
          ),
          if (framed && !_compact)
            dom.div(classes: 'ui-nav', <Widget>[
              ShellNav(
                store: store,
                workspace: workspace,
                route: component.route,
                mode: _mode,
                layout: _rail ? NavLayout.rail : NavLayout.full,
                recent: _recent,
                session: session,
              ),
            ]),
          if (framed)
            dom.main_(id: mainId, classes: 'ui-page', attributes: const <String, String>{'tabindex': '-1'}, <Widget>[
              ..._alerts(store),
              _workspace(workspace),
            ])
          else
            dom.div(classes: 'ui-page', <Widget>[..._alerts(store), _bare(store, unpaired)]),
          if (framed)
            ShellSaveBar(
              store: store,
              workspace: workspace,
              session: session,
              flow: _flow,
              advanced: _mode == EditorMode.advanced,
              onReview: _openReview,
              onReconnect: _reconnect,
              // Send to server is offered everywhere but on its own page.
              onSend: component.route is SyncRoute ? null : () => component.onNavigate(SyncRoute(workspace.id)),
            ),
          if (framed && _compact)
            Sheet(
              id: 'ui-nav-drawer',
              open: _navOpen,
              fromStart: true,
              title: 'Menu',
              onClose: () {
                setState(() => _navOpen = false);
                focusWhenShown('ui-nav-button');
              },
              child: ShellNav(
                store: store,
                workspace: workspace,
                route: component.route,
                mode: _mode,
                layout: NavLayout.drawer,
                recent: _recent,
                session: session,
                header: ModeSwitch(mode: _mode, onChanged: _setMode),
              ),
            ),
          if (framed && session != null)
            Sheet(
              id: 'ui-review',
              open: _reviewOpen,
              title: 'Review',
              onClose: () => setState(() => _reviewOpen = false),
              child: _reviewOpen ? _review(session, workspace) : const Component.fragment(<Widget>[]),
            ),
          _palette(workspace),
          ShortcutSheet(open: _sheetOpen, onClose: () => setState(() => _sheetOpen = false)),
          const ConfirmHost(),
        ],
      ),
    );
  }

  /// Review: the changes, conflicts and history of a server workspace. For
  /// now the live editing panel, which the Review rebuild replaces.
  Widget _review(LiveSession session, WorkspaceSnapshot workspace) {
    final EditorRoute route = component.route;
    final String? focus = route is DocumentRoute ? documentKey(route.kind, route.documentId) : null;
    return LiveBar(
      session: session,
      focus: focus,
      onOpenServer: () {
        setState(() => _reviewOpen = false);
        component.onNavigate(WorkspaceRoute(workspace.id));
      },
    );
  }

  List<Widget> _alerts(EditorStore store) => <Widget>[
        if (!store.persistent)
          const AppAlert(
            key: ValueKey<String>('storage-alert'),
            tone: AlertTone.warning,
            message: 'This browser is not keeping workspaces. Export a zip before closing the tab.',
          ),
        // A refused save is the save bar's; other load and save problems show here.
        if (store.problem != null && !store.hasUnsavedEdit)
          AppAlert(
            key: const ValueKey<String>('problem-alert'),
            tone: AlertTone.error,
            message: store.problem!,
            live: true,
            onDismiss: store.dismissProblem,
          ),
      ];

  /// The pages outside a workspace: start, pairing, the converter.
  Widget _bare(EditorStore store, bool unpaired) {
    final EditorRoute route = component.route;
    if (route is PairRoute) {
      return PairPage(
        key: ValueKey<String>('pair:${route.code}'),
        code: route.code,
        hub: component.hub,
        onPaired: (String id) => replaceHash(ServerRoute(id, const <String>[]).format()),
      );
    }
    if (unpaired) {
      return const dom.main_(id: mainId, classes: 'ui-start', attributes: <String, String>{'tabindex': '-1'}, <Widget>[
        AppAlert(tone: AlertTone.error, title: 'Server not paired', message: 'Run /animus editor pair in game and open the link.'),
        Row(children: <Widget>[Button.link(label: 'All workspaces', href: '#/')]),
      ]);
    }
    if (route is CitizensRoute) {
      return CitizensScreen(key: const ValueKey<String>('citizens'), hub: component.hub, store: store, onNavigate: component.onNavigate, announce: announce);
    }
    return HomeScreen(
      store: store,
      hub: component.hub,
      busy: busy,
      openProblem: openProblem,
      onOpenFolder: () => openFiles(pickDirectory, zip: false),
      onOpenZip: () => openFiles(pickZip, zip: true),
      onNavigate: component.onNavigate,
    );
  }

  /// A document row's muted line: its kind; in Advanced with a short id or
  /// an unnamed NPC's path, in Simple with where an unnamed NPC stands. A
  /// skin's 64-character hash would squeeze the name, so it is found but
  /// not shown.
  static String _hint(DocumentKind kind, String name, String id, bool advanced, DocumentSummary summary) {
    if (advanced && (needsPath(kind, name) || (name != id && id.length <= 24))) return '${kindLabel(kind)}, $id';
    final String? place = needsPath(kind, name) ? npcLine(summary, place: true) : null;
    return place == null ? kindLabel(kind) : '${kindLabel(kind)}, $place';
  }

  List<PaletteCommand> _commands(WorkspaceSnapshot? workspace) {
    final EditorStore store = component.store;
    final bool dark = component.brightness == Brightness.dark;
    final bool advanced = _mode == EditorMode.advanced;
    return <PaletteCommand>[
      if (workspace != null) ...<PaletteCommand>[
        for (final String key in _recent)
          if (_documentOf(key) case final AnimusDocument document when kindShown(document.kind, _mode))
            PaletteCommand('Recent', store.libraryEntry(document.kind, document.id)?.name ?? document.id,
                () => component.onNavigate(DocumentRoute(workspace.id, document.kind, document.id)),
                hint: _hint(document.kind, store.libraryEntry(document.kind, document.id)?.name ?? document.id, document.id, advanced,
                    store.libraryEntry(document.kind, document.id)?.summary ?? DocumentSummary.empty),
                keywords: <String>[document.id]),
        // Every document the workspace lists in this mode, loaded or still on
        // the server.
        for (final DocumentKind kind in DocumentKind.values)
          if (kindShown(kind, _mode))
            for (final LibraryEntry entry in store.library(kind))
            PaletteCommand(
              'Library',
              entry.name,
              () => component.onNavigate(DocumentRoute(workspace.id, kind, entry.id)),
              hint: _hint(kind, entry.name, entry.id, advanced, entry.summary),
              keywords: <String>[entry.id, kind.wireName],
            ),
        for (final NavJob job in visibleJobs(_mode, server: workspace.server != null))
          PaletteCommand('Go to', job.label, () => component.onNavigate(jobRoute(job, workspace.id))),
        if (store.canUndo) PaletteCommand('Edit', 'Undo ${store.undoLabel}', _undo, hint: platformKeys('Mod+Z'), keywords: const <String>['undo']),
        if (store.canRedo) PaletteCommand('Edit', 'Redo ${store.redoLabel}', _redo, hint: platformKeys('Mod+Shift+Z'), keywords: const <String>['redo']),
        PaletteCommand('Create', 'New document', () => component.onNavigate(NewDocumentRoute(workspace.id)), hint: 'N', keywords: const <String>['create', 'add']),
        PaletteCommand('Files', 'Export zip', () => exportWorkspace(workspace), keywords: const <String>['download', 'save']),
        if (advanced) ...<PaletteCommand>[
          PaletteCommand('Files', 'Export Gloss bundle', () => exportGloss(workspace), keywords: const <String>['download']),
          PaletteCommand('Files', 'Import Blockbench model', () => importBbmodel(workspace), keywords: const <String>['bbmodel', 'rig']),
          PaletteCommand('Files', 'Import Gloss bundle', importGloss),
        ],
      ],
      for (final WorkspaceSummary summary in store.workspaces)
        if (summary.id != workspace?.id)
          PaletteCommand(
            'Switch',
            summary.name,
            () => component.onNavigate(summary.serverId != null && component.hub.server(summary.serverId!) != null
                ? ServerRoute(summary.serverId!, const <String>[])
                : WorkspaceRoute(summary.id)),
          ),
      PaletteCommand('Switch', 'All workspaces', () => component.onNavigate(const HomeRoute()), keywords: const <String>['home']),
      PaletteCommand('App', 'Mode: ${_mode == EditorMode.simple ? 'Advanced' : 'Simple'}',
          () => _setMode(_mode == EditorMode.simple ? EditorMode.advanced : EditorMode.simple), keywords: const <String>['mode', 'simple', 'advanced']),
      PaletteCommand('App', 'Keyboard shortcuts', () => setState(() => _sheetOpen = true), hint: '?', keywords: const <String>['keys', 'help']),
      PaletteCommand('App', dark ? 'Light theme' : 'Dark theme', component.onToggleTheme, keywords: const <String>['theme', 'dark', 'light']),
      PaletteCommand('Files', 'Open folder', () => openFiles(pickDirectory, zip: false)),
      PaletteCommand('Files', 'Open zip', () => openFiles(pickZip, zip: true)),
      PaletteCommand('Files', converterTitle, () => component.onNavigate(CitizensRoute(workspaceId: workspace?.id)), keywords: const <String>['citizens', 'shopkeepers', 'denizen']),
    ];
  }

  /// The kind whose list an address opens when the mode does not show it:
  /// Simple offers the switch instead of an Advanced kind's list. A document
  /// a Simple page links to still opens, in its Simple form.
  DocumentKind? _advancedKind(EditorRoute route) =>
      route is KindListRoute && !kindShown(route.kind, _mode) ? route.kind : null;

  AnimusDocument? _documentOf(String key) {
    final int colon = key.indexOf(':');
    if (colon < 0) return null;
    final DocumentKind? kind = DocumentKind.parse(key.substring(0, colon));
    return kind == null ? null : component.store.document(kind, key.substring(colon + 1));
  }

  Widget _workspace(WorkspaceSnapshot workspace) {
    final EditorRoute route = component.route;
    final DocumentRoute? selected = route is DocumentRoute ? route : null;
    final AnimusDocument? document = selected == null ? null : component.store.shown(selected.kind, selected.documentId);
    final LiveSession? session = workspace.server == null ? null : component.session;
    return PickerScope(
      workspaceId: workspace.id,
      route: route,
      onNavigate: component.onNavigate,
      nameOf: (DocumentKind kind, String id) => component.store.libraryEntry(kind, id)?.name,
      child: EditScope(
        editable: session == null || linkOf(session) != ServerLink.needsPairing,
        child: dom.div(key: ValueKey<String>('workspace:${workspace.id}'), classes: 'ui-page-body', <Widget>[
        if (route is CitizensRoute)
          CitizensScreen(key: const ValueKey<String>('citizens'), hub: component.hub, store: component.store, onNavigate: component.onNavigate, announce: announce, landmark: false)
        else if (_advancedKind(route) case final DocumentKind kind)
          dom.div(key: ValueKey<String>('advanced:${kind.wireName}'), classes: 'ui-list-page', <Widget>[
            PageHeader(title: kind.label, crumbs: <Crumb>[Crumb(workspace.name, href: WorkspaceRoute(workspace.id).format()), Crumb(kind.label)]),
            EmptyState(
              message: '${kind.label} are in Advanced.',
              action: Button.primary(label: 'Switch to Advanced', onPressed: () => _setMode(EditorMode.advanced)),
            ),
          ])
        else if (selected != null && document != null)
          DocumentPane(
            key: ValueKey<String>('${selected.kind.wireName}:${selected.documentId}'),
            document: document,
            view: selected.view,
            tab: selected.tab,
            workspaceName: workspace.name,
            workspaceId: workspace.id,
            name: component.store.libraryEntry(selected.kind, selected.documentId)?.name,
            onView: (ViewMode view) => component.onNavigate(selected.withView(view)),
            onSource: (String source) => component.store.editSource(document.kind, document.id, source),
            codeDraft: component.store.codeDraft(document.kind, document.id),
            onCodeDraft: (String? text) => component.store.setCodeDraft(document.kind, document.id, text),
            onJson: (JsonUpdate update, {String? coalesce}) => component.store.commit(
                  documents: <DocumentChange>[DocumentChange.update(document.kind, document.id, update)],
                  coalesce: coalesce == null ? null : '${document.kind.wireName}:${document.id}:$coalesce',
                ),
            onRename: movable(document.kind) ? (String id) => relocate(workspace, document, id, copy: false) : null,
            onDuplicate: movable(document.kind) && document.kind != DocumentKind.recording ? (String id) => relocate(workspace, document, id, copy: true) : null,
            onDelete: () => deleteDocument(workspace, document),
            skinDocument: skinOf(document),
            resources: workspace.resources,
            editor: rigEditor(workspace, document),
            serverDiagnostics: session?.serverDiagnostics[documentKey(document.kind, document.id)] ?? const <Diagnostic>[],
            liveSchemas: session?.schemas,
            liveLookups: session?.lookups,
            workspaceDocuments: workspace.documents,
            onOpen: (DocumentKind kind, String id) => component.onNavigate(DocumentRoute(workspace.id, kind, id)),
            onCommit: (List<DocumentChange> changes, String label) => component.store.commit(documents: changes, label: label),
            library: component.store.library,
            load: session == null ? null : (List<DocumentRef> refs) => session.ensureLoaded(refs),
            serverBehaviors: <String>[
              for (final CatalogEntry entry in session?.catalog ?? const <CatalogEntry>[])
                if (entry.kind == DocumentKind.behavior) entry.id,
            ],
            serverPlugins: reportedPlugins(session?.connection.capabilities),
            npcLibrary: document.kind == DocumentKind.npc && workspace.server != null ? component.store.library(DocumentKind.npc) : null,
            skinTexture: (String id) => component.store.libraryEntry(DocumentKind.skin, id)?.summary.texture,
            skinEditing: SkinEditing(
              resources: workspace.resources,
              commit: (SkinChange change) => component.store.commit(documents: change.documents, resources: change.resources, label: change.label),
            ),
          )
        else if (selected != null)
          DocumentWait(
            key: ValueKey<String>('wait:${selected.kind.wireName}:${selected.documentId}'),
            kind: selected.kind,
            id: selected.documentId,
            entry: component.store.libraryEntry(selected.kind, selected.documentId),
            session: session,
            onHome: () => component.onNavigate(WorkspaceRoute(workspace.id)),
          )
        else if (route is RecordingListRoute)
          MovementPage(key: ValueKey<String>('list:${workspace.id}:recording'), store: component.store, workspace: workspace, kind: DocumentKind.recording, onNavigate: component.onNavigate)
        else if (route is NpcListRoute)
          NpcBrowser(key: ValueKey<String>('npc-browser:${workspace.id}'), store: component.store, workspace: workspace, session: session)
        else if (route is KindListRoute)
          ListPage(key: ValueKey<String>('list:${workspace.id}:${route.kind.wireName}'), store: component.store, workspace: workspace, kind: route.kind, onNavigate: component.onNavigate)
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
            ownRoutes: component.store.libraryOwnRoutes(),
            onNavigate: component.onNavigate,
          ),
        ]),
      ),
    );
  }
}
