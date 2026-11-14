/// Send to server (`#/workspace/<id>/sync`): a local workspace goes to a
/// paired server in three steps on one page. The server is picked (by
/// itself when there is one), the differences are worked out as soon as it
/// is online, and Apply, always at the bottom of the screen, checks the
/// documents on the server and sends them as one change set. A document the
/// server holds differently is only replaced when the user says so.
/// Advanced adds Pull (server documents into the workspace), picking
/// documents one by one, Validate alone and a change note.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_text.dart';
import '../../logic/offline/sync_plan.dart';
import '../../model/animus_document.dart';
import '../../services/server/connection_status.dart';
import '../../services/server/server_profiles.dart';
import '../../state/catalog_entry.dart';
import '../../state/editor_store.dart';
import '../../state/server_hub.dart';
import '../../state/workspace_repository.dart';
import '../../state/workspace_route.dart';
import '../../state/workspace_sync.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/badge.dart';
import '../../ui/confirm_dialog.dart';
import '../../ui/editor_mode.dart';
import '../../ui/empty_state.dart';
import '../../ui/field.dart';
import '../../ui/library_list.dart';
import '../../ui/library_query.dart';
import '../../ui/mode.dart';
import '../../ui/page_header.dart';
import '../../ui/toggle_choice.dart';
import '../../ui/tone.dart';
import '../list_page.dart' show listNoun;
import 'send_plan.dart';
import '../select/animus_select.dart';
import '../../ui/stack.dart';
import '../../ui/tabs.dart';

enum _Side { send, pull }

class SyncPage extends StatefulWidget {
  const SyncPage({required this.store, required this.hub, required this.workspace, required this.onNavigate, super.key});

  final EditorStore store;
  final ServerHub hub;
  final WorkspaceSnapshot workspace;
  final void Function(EditorRoute route) onNavigate;

  @override
  State<SyncPage> createState() => _SyncPageState();
}

class _SyncPageState extends State<SyncPage> {
  String? _serverId;
  _Side _side = _Side.send;

  /// What runs now ("Comparing", "Applying"); every action waits for it.
  String? _busy;
  String? _failure;
  String? _result;

  /// The compare, and the server it was made against.
  PushPlan? _plan;
  String? _planFor;
  bool _replaceChanged = false;

  /// Advanced: documents picked or left out one by one, by key.
  final Map<String, bool> _picked = <String, bool>{};

  /// The server's problems from the last check, by key.
  Map<String, List<Diagnostic>> _problems = const <String, List<Diagnostic>>{};
  String _note = '';

  /// The earlier apply was checked once since the server came online.
  String? _pendingCheckedFor;

  List<CatalogEntry>? _serverList;
  String? _serverListFor;
  bool _takeServer = false;

  @override
  void initState() {
    super.initState();
    final ServerProfile? server = _server;
    if (server != null) _connectIfIdle(server.id);
  }

  /// A server this browser holds a token for connects as soon as it is
  /// picked.
  void _connectIfIdle(String serverId) {
    if (component.hub.hasToken(serverId) && _link(serverId).phase == ConnectionPhase.offline) {
      component.hub.connect(serverId);
    }
  }

  /// The live connection's state; the start page's reachability check does
  /// not count, since sending needs the connection itself.
  ConnectionStatus _link(String serverId) => component.hub.connection(serverId).status;

  ServerProfile? get _server {
    final List<ServerProfile> servers = component.hub.servers;
    if (servers.isEmpty) return null;
    return servers.firstWhere((ServerProfile profile) => profile.id == _serverId, orElse: () => servers.first);
  }

  WorkspaceSync _sync(ServerProfile server) =>
      WorkspaceSync(store: component.store, connection: component.hub.connection(server.id), serverId: server.id, records: component.hub.profiles.local);

  Future<bool> _run(String label, Future<void> Function() task) async {
    setState(() {
      _busy = label;
      _failure = null;
    });
    try {
      await task();
      return true;
    } on SyncFailure catch (failure) {
      _failure = plainSyncProblem(failure.message);
      return false;
    } on Object {
      _failure = 'Something went wrong. Check the connection, then try again.';
      return false;
    } finally {
      if (mounted) setState(() => _busy = null);
    }
  }

  /// Starts what an online server needs without a click: the check of an
  /// earlier unanswered apply, the compare, and the server's list for Pull.
  void _follow(ServerProfile server, bool advanced) {
    if (!_link(server.id).online || _busy != null) return;
    final WorkspaceSync sync = _sync(server);
    if (_pendingCheckedFor != server.id && sync.pending != null) {
      _pendingCheckedFor = server.id;
      _later(() => _checkPending(server));
      return;
    }
    if (_planFor != server.id && _failure == null) {
      _planFor = server.id;
      _later(() => _compare(server));
      return;
    }
    if (advanced && _side == _Side.pull && _serverListFor != server.id && _failure == null) {
      _serverListFor = server.id;
      _later(() => _loadServerList(server));
    }
  }

  void _later(void Function() run) => context.binding.addPostFrameCallback(() {
        if (mounted) run();
      });

  Future<void> _compare(ServerProfile server) => _run('Comparing', () async {
        final PushPlan plan = await _sync(server).comparePush();
        _plan = plan;
        _planFor = server.id;
        _problems = const <String, List<Diagnostic>>{};
      });

  List<PushEntry> get _selection => _plan == null ? const <PushEntry>[] : sendSelection(_plan!, replaceChanged: _replaceChanged, picked: _picked);

  /// Asks the server about every picked document; true when it accepts them.
  Future<bool> _check(ServerProfile server, List<PushEntry> selection) async {
    bool accepted = false;
    final bool ran = await _run('Checking', () async {
      final Map<String, List<Diagnostic>> found = await _sync(server).validate(selection);
      _problems = found;
      final int errors = errorCount(found);
      accepted = errors == 0;
      if (!accepted) {
        _failure = 'The server found ${errors == 1 ? '1 problem' : '$errors problems'} in ${documentCount(found.length)}. Fix them, then apply again.';
      }
    });
    return ran && accepted;
  }

  Future<void> _validateOnly(ServerProfile server) async {
    final List<PushEntry> selection = _selection;
    if (await _check(server, selection) && mounted) {
      setState(() => _result = 'The server accepts ${documentCount(selection.length)}.');
    }
  }

  Future<void> _apply(ServerProfile server) async {
    final List<PushEntry> selection = _selection;
    if (selection.isEmpty) return;
    setState(() => _result = null);
    if (!await _check(server, selection)) return;
    final List<PushEntry> replacing = <PushEntry>[for (final PushEntry entry in selection) if (entry.state == SyncState.conflict) entry];
    if (replacing.isNotEmpty) {
      final bool yes = await askToConfirm(
        title: 'Replace ${documentCount(replacing.length)} on the server?',
        message: '${replacing.take(4).map(_nameOf).join(', ')}${replacing.length > 4 ? ' and ${replacing.length - 4} more' : ''} '
            'get your version. The server can undo it.',
        confirmLabel: 'Replace and apply',
        destructive: true,
      );
      if (!yes || !mounted) return;
    }
    await _run('Applying', () async {
      await _settle(server, await _sync(server).push(selection, message: _note));
    });
  }

  Future<void> _checkPending(ServerProfile server) => _run('Checking', () async {
        final PushResult? result = await _sync(server).checkPending();
        if (result != null) await _settle(server, result);
      });

  Future<void> _settle(ServerProfile server, PushResult result) async {
    switch (result) {
      case PushApplied(:final String changeId, :final int count):
        _result = 'Applied ${documentCount(count)}.';
        if (ModeScope.advanced(context)) _result = 'Applied ${documentCount(count)} as change $changeId.';
        ArcaneSonner.success('Applied ${documentCount(count)} to ${server.name}.');
        _picked.clear();
        _note = '';
        _plan = await _sync(server).comparePush();
        _problems = const <String, List<Diagnostic>>{};
      case PushConflicted(:final List<String> keys):
        _failure = '${documentCount(keys.length)} changed on the server meanwhile, so nothing was written. The list is up to date again.';
        _plan = await _sync(server).comparePush();
      case PushRefused(:final Map<String, List<Diagnostic>> diagnostics):
        _problems = diagnostics;
        _failure = 'The server refused ${documentCount(diagnostics.length)}. Fix the marked ones, then apply again.';
      case PushLost():
        _failure = 'The earlier apply never arrived, so nothing was written.';
        _plan = await _sync(server).comparePush();
      case PushWaiting():
        _failure = 'The server is still writing the earlier apply. Check again soon.';
    }
  }

  Future<void> _loadServerList(ServerProfile server) => _run('Reading', () async {
        _serverList = await _sync(server).catalog();
        _serverListFor = server.id;
      });

  Future<void> _pull(ServerProfile server, List<LibraryRow> rows) => _run('Pulling', () async {
        final Set<String> keys = <String>{for (final LibraryRow row in rows) row.key};
        final List<CatalogEntry> picked = <CatalogEntry>[for (final CatalogEntry entry in _serverList ?? const <CatalogEntry>[]) if (keys.contains(entry.key)) entry];
        final WorkspaceSync sync = _sync(server);
        final PullPlan plan = await sync.comparePull(picked);
        final Map<String, PullChoice> choices = <String, PullChoice>{
          for (final PullEntry entry in plan.where(SyncState.conflict)) entry.key: _takeServer ? PullChoice.take : PullChoice.keep,
        };
        final int written = await sync.pull(plan, choices);
        final int kept = _takeServer ? 0 : plan.where(SyncState.conflict).length;
        ArcaneSonner.success('Pulled ${documentCount(written)} into ${component.workspace.name}.${kept == 0 ? '' : ' Kept ${documentCount(kept)} that differ here.'}');
        _plan = await sync.comparePush();
      });

  String _nameOf(PushEntry entry) {
    final String name = component.store.libraryEntry(entry.document.kind, entry.document.id)?.name ?? guiPlainText(entry.document.title);
    return name.trim().isEmpty ? entry.document.id : name;
  }

  @override
  Widget build(BuildContext context) {
    final ServerProfile? server = _server;
    final bool advanced = ModeScope.of(context) == EditorMode.advanced;
    if (!advanced) _side = _Side.send;
    if (server != null) _follow(server, advanced);
    final WorkspaceSnapshot workspace = component.workspace;
    final bool sending = server != null && _side == _Side.send;
    final PushPlan? plan = _plan;
    final List<PushEntry> selection = sending ? _selection : const <PushEntry>[];
    final String? why = sending ? _blocked(_link(server.id).online, plan, selection, _sync(server).pending != null) : null;
    return stack(spacing: 16, <Widget>[
      PageHeader(
        title: 'Send to server',
        crumbs: <Crumb>[Crumb(workspace.name, href: WorkspaceRoute(workspace.id).format()), const Crumb('Send to server')],
        secondary: <RowAction>[
          if (sending && advanced) RowAction('Validate', why == null && _busy == null ? () => _validateOnly(server) : null, loading: _busy == 'Checking' && _result == null),
        ],
        primary: sending
            ? RowAction(
                selection.isEmpty ? 'Apply' : 'Apply ${selection.length}',
                why == null && _busy == null ? () => _apply(server) : null,
                loading: _busy == 'Applying',
                id: 'send-apply',
              )
            : null,
      ),
      // Advanced only: Pull from the server is the other tab.
      if (advanced && server != null)
        AppTabs(
          id: 'send-tabs',
          label: 'Direction',
          tabs: const <String>['Send', 'Pull'],
          selected: _side == _Side.send ? 0 : 1,
          onSelect: (int index) => setState(() {
            _side = index == 0 ? _Side.send : _Side.pull;
            _failure = null;
            _result = null;
          }),
        ),
      if (sending && plan != null)
        dom.div(attributes: const <String, String>{'role': 'status', 'data-testid': 'send-summary'}, <Widget>[mutedText(why ?? '${compareLine(plan)}.')]),
      if (server == null)
        EmptyState(
          message: 'No server paired.',
          action: Button.primary(label: 'Pair a server', onPressed: () => component.onNavigate(const HomeRoute())),
        )
      else ...<Widget>[
        _serverRow(server),
        if (_side == _Side.send) ..._sendStep(server, advanced, selection) else ..._pullStep(server),
      ],
    ]);
  }

  Widget _serverRow(ServerProfile server) {
    final ConnectionStatus status = _link(server.id);
    final bool token = component.hub.hasToken(server.id);
    final List<ServerProfile> servers = component.hub.servers;
    final (String label, BadgeTone tone) = switch (status.phase) {
      ConnectionPhase.online => ('Online', BadgeTone.ok),
      ConnectionPhase.connecting => ('Connecting', BadgeTone.info),
      ConnectionPhase.reconnecting => ('Reconnecting', BadgeTone.warning),
      ConnectionPhase.needsPairing => ('Pairing ended', BadgeTone.error),
      ConnectionPhase.refused => ('Refused', BadgeTone.error),
      ConnectionPhase.offline => ('Not connected', BadgeTone.neutral),
    };
    final bool idle = status.phase == ConnectionPhase.offline || status.phase == ConnectionPhase.refused || status.phase == ConnectionPhase.reconnecting;
    return Field(
      label: 'Server',
      forId: servers.length > 1 ? 'send-server' : null,
      child: ActionRow(
        leading: <Widget>[
          if (servers.length > 1)
            AnimusSelect.values(
              id: 'send-server',
              value: server.id,
              values: <String>[for (final ServerProfile profile in servers) profile.id],
              labels: <String, String>{for (final ServerProfile profile in servers) profile.id: profile.name},
              onChanged: (String? value) {
                setState(() {
                  _serverId = value;
                  _plan = null;
                  _planFor = null;
                  _serverList = null;
                  _serverListFor = null;
                  _picked.clear();
                  _failure = null;
                  _result = null;
                });
                if (value != null) _connectIfIdle(value);
              },
            )
          else
            dom.span(attributes: <String, String>{'title': server.name}, <Widget>[Text.truncated(server.name, weight: FontWeight.w500)]),
          StatusBadge(label, tone: tone, dot: true, attributes: const <String, String>{'data-testid': 'send-server-state'}),
        ],
        secondary: <RowAction>[
          if (status.phase == ConnectionPhase.needsPairing || !token)
            RowAction('Pair again', () => component.onNavigate(const HomeRoute()))
          else if (idle)
            RowAction('Connect', () {
              if (status.phase == ConnectionPhase.reconnecting) {
                component.hub.connection(server.id).retryNow();
              } else {
                component.hub.connect(server.id);
              }
            }),
        ],
      ),
    );
  }

  List<Widget> _sendStep(ServerProfile server, bool advanced, List<PushEntry> selection) {
    final bool online = _link(server.id).online;
    final PushPlan? plan = _plan;
    final bool waiting = _sync(server).pending != null;
    final int changed = plan?.where(SyncState.conflict).length ?? 0;
    final List<LibraryRow> rows = _rows(plan, selection, advanced);
    return <Widget>[
      if (_failure != null && plan != null) AppAlert(key: const ValueKey<String>('send-failure'), tone: AlertTone.error, message: _failure!, live: true, action: _retry(server)),
      if (waiting && _busy == null)
        AppAlert(
          key: const ValueKey<String>('send-pending'),
          tone: AlertTone.warning,
          message: 'An earlier apply has no answer yet.',
          action: Button.outline(label: 'Check again', size: ButtonSize.sm, onPressed: online ? () => _checkPending(server) : null),
        ),
      if (_result != null) AppAlert(key: const ValueKey<String>('send-result'), tone: AlertTone.ok, message: _result!, live: true),
      if (plan != null && changed > 0)
        Field(
          label: 'Changed',
          tip: 'Documents that differ here and on the server. Keep server leaves its copy; Use mine replaces it when you apply.',
          docs: Docs.sending,
          child: ToggleChoice<bool>(
            label: 'Changed documents',
            value: _replaceChanged,
            options: const <ChoiceOption<bool>>[ChoiceOption<bool>(false, 'Keep server'), ChoiceOption<bool>(true, 'Use mine')],
            onChanged: (bool value) => setState(() {
              _replaceChanged = value;
              _picked.removeWhere((String key, bool _) => plan.where(SyncState.conflict).any((PushEntry entry) => entry.key == key));
            }),
          ),
        ),
      if (advanced && plan != null)
        Field(
          label: 'Change note',
          forId: 'send-note',
          child: TextInput(id: 'send-note', value: _note, placeholder: 'Optional', onChanged: (String value) => setState(() => _note = value)),
        ),
      if (!online && plan == null)
        EmptyState(message: _busy == null ? 'Connect to compare with ${server.name}.' : 'Connecting to ${server.name}.')
      else
        LibraryList(
          key: ValueKey<String>('send-list:${server.id}'),
          id: 'send-list',
          label: 'Differences',
          noun: 'document',
          rows: rows,
          fill: false,
          loading: plan == null && _failure == null,
          error: plan == null ? _failure : null,
          onRetry: online && _busy == null ? () => _compare(server) : null,
          filters: const <LibraryFilter>[
            LibraryFilter('new', 'New'),
            LibraryFilter('changed', 'Changed'),
            LibraryFilter('same', 'Same'),
            LibraryFilter('problems', 'Has problems'),
          ],
          sorts: const <LibrarySort>[LibrarySort('status', 'Status'), LibrarySort.name],
          emptyMessage: 'This workspace has no documents yet.',
        ),
    ];
  }

  /// Why Apply waits, in a few words; null when it can go.
  String? _blocked(bool online, PushPlan? plan, List<PushEntry> selection, bool waiting) {
    if (_busy != null) return '$_busy...';
    if (!online) return 'Not connected.';
    if (waiting) return 'Waiting for the earlier apply.';
    if (plan == null) return 'Comparing...';
    if (selection.isEmpty) {
      final int changed = plan.where(SyncState.conflict).length;
      return changed > 0 && !_replaceChanged ? '${compareLine(plan)}. Nothing new to apply.' : 'Everything here matches the server.';
    }
    return null;
  }

  Widget? _retry(ServerProfile server) {
    if (!_link(server.id).online) return null;
    return Button.outline(
      label: 'Compare again',
      size: ButtonSize.sm,
      onPressed: _busy != null
          ? null
          : () {
              setState(() => _failure = null);
              _compare(server);
            },
    );
  }

  /// The rows, worked out again only when the compare, the picks or the
  /// server's problems change (typing a change note keeps them).
  List<LibraryRow> _rows(PushPlan? plan, List<PushEntry> selection, bool advanced) {
    if (plan == null) return const <LibraryRow>[];
    final Set<String> sending = <String>{for (final PushEntry entry in selection) entry.key};
    final String key = '${identityHashCode(plan)}|${identityHashCode(_problems)}|$advanced|${(sending.toList()..sort()).join(',')}';
    if (key == _rowsKey) return _rowsCache;
    _rowsKey = key;
    return _rowsCache = <LibraryRow>[for (final PushEntry entry in plan.entries) _row(entry, sending.contains(entry.key), advanced)];
  }

  String _rowsKey = '';
  List<LibraryRow> _rowsCache = const <LibraryRow>[];

  LibraryRow _row(PushEntry entry, bool sends, bool advanced) {
    final AnimusDocument document = entry.document;
    final List<Diagnostic> problems = <Diagnostic>[
      for (final Diagnostic diagnostic in _problems[entry.key] ?? const <Diagnostic>[])
        if (diagnostic.severity == DiagnosticSeverity.error) diagnostic,
    ];
    final List<String> fields = entry.state == SyncState.conflict ? differingFields(document.json, entry.server?.source) : const <String>[];
    final String noun = listNoun(document.kind);
    final String meta = switch (entry.state) {
      SyncState.added => '${_upper(noun)}, not on the server',
      SyncState.conflict => '${_upper(noun)}. ${differenceLine(fields)}',
      SyncState.same => _upper(noun),
    };
    final String status = switch (entry.state) {
      SyncState.added => '0',
      SyncState.conflict => '1',
      SyncState.same => '2',
    };
    return LibraryRow(
      key: entry.key,
      title: _nameOf(entry),
      meta: problems.isEmpty ? meta : '$meta. ${problems.first.message}',
      advancedMeta: <String>[
        document.id,
        if (entry.server != null) 'server revision ${entry.server!.revision}',
        if (fields.isNotEmpty) fields.join(', '),
      ].join(' · '),
      badges: <LibraryBadge>[
        switch (entry.state) {
          SyncState.added => const LibraryBadge('New', tone: BadgeTone.info),
          SyncState.conflict => const LibraryBadge('Changed', tone: BadgeTone.warning),
          SyncState.same => const LibraryBadge('Same'),
        },
        if (entry.problem != null) const LibraryBadge('Not JSON', tone: BadgeTone.error),
        if (problems.isNotEmpty) LibraryBadge(problems.length == 1 ? '1 problem' : '${problems.length} problems', tone: BadgeTone.error),
        if (!sends && entry.state != SyncState.same && entry.problem == null) const LibraryBadge('Not sent'),
      ],
      tags: <String>{
        switch (entry.state) {
          SyncState.added => 'new',
          SyncState.conflict => 'changed',
          SyncState.same => 'same',
        },
        if (problems.isNotEmpty || entry.problem != null) 'problems',
      },
      href: DocumentRoute(component.workspace.id, document.kind, document.id).format(),
      keywords: '${document.id} ${document.kind.wireName}',
      sortValues: <String, String>{'status': '$status ${_nameOf(entry)}'},
      trailing: advanced && entry.state != SyncState.same && entry.problem == null ? _pick(entry, sends) : null,
    );
  }

  /// Advanced: takes one document into Apply or leaves it out. The box is
  /// named after the document; the row says what it is.
  Component _pick(PushEntry entry, bool sends) => dom.span(
        events: <String, void Function(web.Event)>{'click': (web.Event event) => event.stopPropagation()},
        <Widget>[
          dom.label(htmlFor: 'send-pick-${entry.key}', classes: 'animus-sr-only', <Widget>[Component.text('Include ${_nameOf(entry)}')]),
          ArcaneCheckbox(
            id: 'send-pick-${entry.key}',
            checked: sends,
            size: ComponentSize.sm,
            onChanged: (bool value) => setState(() => _picked[entry.key] = value),
          ),
        ],
      );

  List<Widget> _pullStep(ServerProfile server) {
    final bool online = _link(server.id).online;
    final List<CatalogEntry>? listed = _serverList;
    return <Widget>[
      if (_failure != null) AppAlert(key: const ValueKey<String>('pull-failure'), tone: AlertTone.error, message: _failure!, live: true),
      Field(
        label: 'When different',
        tip: 'For documents this workspace also has. Keep mine leaves them; Take server writes the server copy here. Undo takes a pull back.',
        docs: Docs.sending,
        child: ToggleChoice<bool>(
          label: 'When different',
          value: _takeServer,
          options: const <ChoiceOption<bool>>[ChoiceOption<bool>(false, 'Keep mine'), ChoiceOption<bool>(true, 'Take server')],
          onChanged: (bool value) => setState(() => _takeServer = value),
        ),
      ),
      if (!online && listed == null)
        EmptyState(message: 'Connect to read ${server.name}.')
      else
        LibraryList(
          key: ValueKey<String>('pull-list:${server.id}'),
          id: 'pull-list',
          label: 'On the server',
          noun: 'document',
          fill: false,
          selectable: true,
          loading: listed == null,
          rows: <LibraryRow>[
            for (final CatalogEntry entry in listed ?? const <CatalogEntry>[])
              LibraryRow(
                key: entry.key,
                title: entry.summary.name ?? entry.id,
                meta: _upper(listNoun(entry.kind)),
                advancedMeta: '${entry.id} · revision ${entry.revision}',
                badges: <LibraryBadge>[if (component.store.document(entry.kind, entry.id) != null) const LibraryBadge('Also here')],
                tags: <String>{if (component.store.document(entry.kind, entry.id) != null) 'here' else 'missing'},
                keywords: '${entry.id} ${entry.kind.wireName}',
              ),
          ],
          filters: const <LibraryFilter>[LibraryFilter('missing', 'Not here'), LibraryFilter('here', 'Also here')],
          bulkActions: <LibraryBulkAction>[LibraryBulkAction('Pull', (List<LibraryRow> rows) => _pull(server, rows))],
          onOpen: (LibraryRow _) {},
          emptyMessage: 'The server has no documents yet.',
        ),
    ];
  }
}

String _upper(String text) => text.isEmpty ? text : '${text[0].toUpperCase()}${text.substring(1)}';

