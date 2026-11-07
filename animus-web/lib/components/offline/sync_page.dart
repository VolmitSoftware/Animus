/// Sending a local workspace to a paired server, and pulling server
/// documents into it. A push is compared with the server first, validated
/// by the server's own checks, and sent as one change set; a document the
/// server already holds differently is only replaced when the user says so.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../logic/offline/sync_plan.dart';
import '../../logic/offline/sync_step.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../services/server/error_copy.dart';
import '../../services/server/server_connection.dart';
import '../../services/server/server_profiles.dart';
import '../../state/editor_store.dart';
import '../../state/catalog_entry.dart';
import '../../state/server_hub.dart';
import '../../state/workspace_repository.dart';
import '../../state/workspace_route.dart';
import '../../state/workspace_sync.dart';
import '../gui/gui_fields.dart';
import '../server/connection_pill.dart';
import '../server/live_confirm.dart';
import '../brand_mark.dart';
import '../info_tip.dart';
import 'offline_fields.dart';

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
  String? _busy;
  String? _failure;

  PushPlan? _push;
  final Map<String, PushChoice> _pushChoices = <String, PushChoice>{};
  Map<String, List<Diagnostic>>? _validated;
  String? _validatedFor;
  String _message = '';
  String? _pushResult;

  List<CatalogEntry>? _catalog;
  final Set<String> _pullSelected = <String>{};
  PullPlan? _pull;
  final Map<String, PullChoice> _pullChoices = <String, PullChoice>{};
  String _filter = '';

  /// A push that replaces server copies waits for this confirm.
  bool _confirmReplace = false;

  static const String _pushId = 'animus-sync-push';

  ServerProfile? get _server {
    final List<ServerProfile> servers = component.hub.servers;
    if (servers.isEmpty) return null;
    return servers.firstWhere((ServerProfile profile) => profile.id == _serverId, orElse: () => servers.first);
  }

  WorkspaceSync _sync(ServerProfile server) =>
      WorkspaceSync(store: component.store, connection: component.hub.connection(server.id), serverId: server.id, records: component.hub.profiles.local);

  Future<void> _run(String label, Future<void> Function() task) async {
    setState(() {
      _busy = label;
      _failure = null;
    });
    try {
      await task();
    } on SyncFailure catch (failure) {
      _failure = failure.message;
    } on Object {
      _failure = 'Something went wrong. ${transportCopy('dropped').next} Then try again.';
    } finally {
      if (mounted) setState(() => _busy = null);
    }
  }

  int get _serverErrors => (_validated ?? const <String, List<Diagnostic>>{}).values.fold<int>(
    0,
    (int total, List<Diagnostic> list) => total + list.where((Diagnostic d) => d.severity == DiagnosticSeverity.error).length,
  );

  SyncStep get _step {
    final List<PushEntry> selection = _selection;
    return syncNextStep(
      pushCompared: _push != null,
      pushSelected: selection.length,
      validated: _validatedFor != null && _validatedFor == _selectionKey(selection) && _serverErrors == 0,
      pullSelected: _pullSelected.length,
      pullCompared: _pull != null,
      pullWrites: _pull == null ? 0 : pullWrites(_pull!, _pullChoices).length,
    );
  }

  ButtonVariant _variant(SyncStep step) => _step == step ? ButtonVariant.primary : ButtonVariant.secondary;

  List<PushEntry> get _selection => _push == null ? const <PushEntry>[] : pushSelection(_push!, _pushChoices);

  String _selectionKey(List<PushEntry> selection) =>
      <String>[for (final PushEntry entry in selection) '${entry.key}@${entry.document.source.hashCode}'].join(',');

  Future<void> _compare(ServerProfile server) => _run('Comparing', () async {
    final PushPlan plan = await _sync(server).comparePush();
    _push = plan;
    _pushChoices.clear();
    _validated = null;
    _validatedFor = null;
    _pushResult = null;
  });

  Future<void> _validate(ServerProfile server) => _run('Validating', () async {
    final List<PushEntry> selection = _selection;
    _validated = await _sync(server).validate(selection);
    _validatedFor = _selectionKey(selection);
  });

  Future<void> _send(ServerProfile server) => _run('Pushing', () async {
    final List<PushEntry> selection = _selection;
    await _settle(server, await _sync(server).push(selection, message: _message));
  });

  Future<void> _checkPending(ServerProfile server) => _run('Checking', () async {
    final PushResult? result = await _sync(server).checkPending();
    if (result != null) await _settle(server, result);
  });

  Future<void> _settle(ServerProfile server, PushResult result) async {
    switch (result) {
      case PushApplied(:final String changeId, :final int count):
        _pushResult = 'Applied $count document${count == 1 ? '' : 's'} as change $changeId.';
        ArcaneSonner.success(_pushResult!);
        _push = await _sync(server).comparePush();
        _pushChoices.clear();
        _validated = null;
        _validatedFor = null;
      case PushConflicted(:final List<String> keys):
        _failure = '${keys.join(', ')} changed on the server, so nothing was written. Review the new compare and push again.';
        _push = await _sync(server).comparePush();
        _validated = null;
        _validatedFor = null;
      case PushRefused(:final Map<String, List<Diagnostic>> diagnostics):
        _validated = diagnostics;
        _validatedFor = null;
        _failure =
            'The server refused ${diagnostics.length} document${diagnostics.length == 1 ? '' : 's'}. Fix the marked fields and validate again.';
      case PushLost():
        _failure = 'The earlier push never arrived. Compare again, then push.';
        _push = null;
      case PushWaiting():
        _failure = 'The server is still writing the earlier push. Check again soon.';
    }
  }

  Future<void> _loadCatalog(ServerProfile server) => _run('Loading', () async {
    _catalog = await _sync(server).catalog();
    _pullSelected.clear();
    _pull = null;
  });

  Future<void> _comparePull(ServerProfile server) => _run('Comparing', () async {
    final List<CatalogEntry> picked = <CatalogEntry>[
      for (final CatalogEntry entry in _catalog ?? const <CatalogEntry>[])
        if (_pullSelected.contains(entry.key)) entry,
    ];
    _pull = await _sync(server).comparePull(picked);
    _pullChoices.clear();
  });

  Future<void> _takePull(ServerProfile server) => _run('Pulling', () async {
    final int written = await _sync(server).pull(_pull!, _pullChoices);
    ArcaneSonner.success('Pulled $written document${written == 1 ? '' : 's'} into ${component.workspace.name}.');
    _pull = null;
    _pullSelected.clear();
  });

  @override
  Widget build(BuildContext context) {
    final ServerProfile? server = _server;
    return dom.div(classes: 'animus-sync', <Widget>[
      dom.section(
        classes: 'animus-panel',
        attributes: const <String, String>{'aria-labelledby': 'animus-sync-title'},
        <Widget>[
          const dom.h2(id: 'animus-sync-title', <Widget>[
            InfoTip(
              tip: 'A push is checked by the server and applied as one change set. A pull copies server documents in as one undo step.',
              docs: Docs.sending,
              child: Component.text('Send to a server'),
            ),
          ]),
          if (server == null)
            dom.div(
              classes: 'animus-sync-empty',
              attributes: const <String, String>{'data-testid': 'sync-no-server'},
              <Widget>[
                const BrandMark(size: 48, classes: 'animus-server-mark'),
                const dom.p(<Widget>[
                  Component.text(
                    'No server paired. Run /animus editor pair, then paste the link under Servers on the home page.',
                  ),
                ]),
                Button.secondary(label: 'Pair a server', size: ButtonSize.small, onPressed: () => component.onNavigate(const HomeRoute())),
              ],
            )
          else
            _connection(server),
          if (_failure != null) dom.p(classes: 'animus-error', attributes: const <String, String>{'role': 'alert'}, <Widget>[Component.text(_failure!)]),
          if (_busy != null) dom.p(classes: 'animus-muted', attributes: const <String, String>{'role': 'status'}, <Widget>[Component.text('$_busy...')]),
        ],
      ),
      if (server != null) ...<Widget>[_pushPanel(server), _pullPanel(server)],
    ]);
  }

  Widget _connection(ServerProfile server) {
    final ConnectionStatus status = component.hub.status(server.id);
    final bool token = component.hub.hasToken(server.id);
    return dom.div(classes: 'animus-row', <Widget>[
      if (component.hub.servers.length > 1)
        FieldRow(
          label: 'Server',
          child: GuiSelect(
            value: server.id,
            options: <String>[for (final ServerProfile profile in component.hub.servers) profile.id],
            labels: <String, String>{for (final ServerProfile profile in component.hub.servers) profile.id: profile.name},
            onChanged: (String? value) => setState(() {
              _serverId = value;
              _push = null;
              _pull = null;
              _catalog = null;
            }),
          ),
        )
      else
        dom.strong(<Widget>[Component.text(server.name)]),
      ConnectionPill(status: status, hasToken: token),
      if (phaseHint(status, hasToken: token).isNotEmpty) dom.span(classes: 'animus-muted', <Widget>[Component.text(phaseHint(status, hasToken: token))]),
      if (!status.online && token && status.phase != ConnectionPhase.connecting)
        Button.secondary(label: 'Connect', size: ButtonSize.small, onPressed: () => component.hub.connect(server.id)),
    ]);
  }

  Widget _pushPanel(ServerProfile server) {
    final bool online = component.hub.status(server.id).online;
    final PushPlan? plan = _push;
    final List<PushEntry> selection = _selection;
    final bool validatedNow = _validatedFor != null && _validatedFor == _selectionKey(selection);
    final int serverErrors = _serverErrors;
    final bool waiting = _sync(server).pending != null;
    final List<PushEntry> replacing = <PushEntry>[
      for (final PushEntry entry in selection)
        if (entry.state == SyncState.conflict) entry,
    ];
    return dom.section(
      classes: 'animus-panel',
      attributes: const <String, String>{'aria-labelledby': 'animus-push-title'},
      <Widget>[
        const dom.h2(id: 'animus-push-title', <Widget>[
          InfoTip(
            tip: 'Compare lists what is new or different on the server, Validate runs its checks, and Push applies the picked documents as one change set.',
            docs: Docs.sending,
            child: Component.text('Push this workspace'),
          ),
        ]),
        dom.div(classes: 'animus-row', <Widget>[
          Button(
            variant: _variant(SyncStep.comparePush),
            label: plan == null ? 'Compare with ${server.name}' : 'Compare again',
            size: ButtonSize.small,
            disabled: !online || _busy != null,
            onPressed: () => _compare(server),
          ),
          if (!online) const dom.span(classes: 'animus-muted', <Widget>[Component.text('Connect to compare.')]),
        ]),
        if (waiting)
          dom.div(
            classes: 'animus-row',
            attributes: const <String, String>{'role': 'status', 'data-testid': 'sync-pending'},
            <Widget>[
              const dom.span(<Widget>[Component.text('An earlier push has no answer yet. Check it before pushing again.')]),
              Button.secondary(
                label: 'Check the earlier push',
                size: ButtonSize.small,
                disabled: !online || _busy != null,
                onPressed: () => _checkPending(server),
              ),
            ],
          ),
        // After a push the new compare has nothing left to send, so the result
        // shows above that line rather than inside the push form.
        if (_pushResult != null) dom.p(classes: 'animus-ok', attributes: const <String, String>{'role': 'status'}, <Widget>[Component.text(_pushResult!)]),
        if (plan != null && plan.where(SyncState.added).isEmpty && plan.where(SyncState.conflict).isEmpty)
          dom.p(
            classes: 'animus-muted',
            attributes: const <String, String>{'data-testid': 'sync-nothing'},
            <Widget>[
              Component.text(
                plan.where(SyncState.same).isEmpty
                    ? '${component.workspace.name} has no documents to send.'
                    : 'All ${plan.where(SyncState.same).length} documents already match the server.',
              ),
            ],
          ),
        if (plan != null && (plan.where(SyncState.added).isNotEmpty || plan.where(SyncState.conflict).isNotEmpty)) ...<Widget>[
          dom.p(<Widget>[
            Component.text(
              '${plan.where(SyncState.added).length} new, ${plan.where(SyncState.conflict).length} different, '
              '${plan.where(SyncState.same).length} the same.',
            ),
          ]),
          if (plan.where(SyncState.added).isNotEmpty)
            dom.fieldset(classes: 'animus-sync-group', <Widget>[
              const dom.legend(<Widget>[Component.text('New on the server')]),
              for (final PushEntry entry in plan.where(SyncState.added))
                dom.div(classes: 'animus-sync-entry', <Widget>[
                  ArcaneCheckbox(
                    checked: (_pushChoices[entry.key] ?? defaultPushChoice(entry)) == PushChoice.send,
                    disabled: entry.problem != null,
                    label: '${entry.document.kind.wireName} ${entry.document.id}',
                    size: ComponentSize.sm,
                    onChanged: (bool value) => setState(() => _pushChoices[entry.key] = value ? PushChoice.send : PushChoice.skip),
                  ),
                  if (entry.problem != null) dom.span(classes: 'animus-error', <Widget>[Component.text(entry.problem!)]),
                  if (entry.browserErrors > 0)
                    dom.span(classes: 'animus-muted', <Widget>[Component.text('${entry.browserErrors} browser error${entry.browserErrors == 1 ? '' : 's'}')]),
                  ..._serverDiagnostics(entry.key),
                ]),
            ]),
          if (plan.where(SyncState.conflict).isNotEmpty)
            dom.fieldset(classes: 'animus-sync-group', <Widget>[
              const dom.legend(<Widget>[Component.text('Different on the server')]),
              for (final PushEntry entry in plan.where(SyncState.conflict))
                dom.div(classes: 'animus-sync-entry', <Widget>[
                  dom.span(<Widget>[
                    dom.code(<Widget>[Component.text('${entry.document.kind.wireName} ${entry.document.id}')]),
                    dom.span(classes: 'animus-muted', <Widget>[
                      Component.text(
                        ' server revision ${entry.server!.revision}; ${differingFields(entry.document.json, entry.server!.source).join(', ')} differ',
                      ),
                    ]),
                  ]),
                  _choice<PushChoice>('For ${entry.document.id}', _pushChoices[entry.key] ?? defaultPushChoice(entry), const <(PushChoice, String)>[
                    (PushChoice.skip, 'Keep the server copy'),
                    (PushChoice.send, 'Replace it with mine'),
                  ], (PushChoice value) => setState(() => _pushChoices[entry.key] = value)),
                  ..._serverDiagnostics(entry.key),
                ]),
            ]),
          OfflineTextField(
            label: 'Change message',
            value: _message,
            placeholder: 'Optional',
            onChanged: (String value) => setState(() => _message = value),
          ),
          dom.div(classes: 'animus-row', <Widget>[
            Button(
              variant: _variant(SyncStep.validate),
              label: 'Validate ${selection.length} on the server',
              size: ButtonSize.small,
              disabled: !online || selection.isEmpty || _busy != null,
              onPressed: () => _validate(server),
            ),
            Button(
              variant: _variant(SyncStep.push),
              label: 'Push ${selection.length} document${selection.length == 1 ? '' : 's'}',
              size: ButtonSize.small,
              disabled: !online || selection.isEmpty || !validatedNow || serverErrors > 0 || _busy != null || waiting || _confirmReplace,
              attributes: const <String, String>{'id': _pushId},
              onPressed: () => replacing.isEmpty ? _send(server) : setState(() => _confirmReplace = true),
            ),
            dom.span(
              classes: 'animus-muted',
              attributes: const <String, String>{'aria-live': 'polite'},
              <Widget>[
                Component.text(
                  selection.isEmpty
                      ? 'Nothing selected.'
                      : !validatedNow
                      ? 'Validate before pushing.'
                      : serverErrors > 0
                      ? '$serverErrors server error${serverErrors == 1 ? '' : 's'}. Fix and validate again.'
                      : 'The server accepts this selection.',
                ),
              ],
            ),
          ]),
          if (_confirmReplace && replacing.isNotEmpty)
            LiveConfirm(
              question: 'Replace ${replacing.length} server cop${replacing.length == 1 ? 'y' : 'ies'} with yours?',
              detail:
                  '${replacing.map((PushEntry entry) => entry.document.id).take(6).join(', ')}${replacing.length > 6 ? ' and more' : ''} '
                  'get your version on the server. The server can undo the push.',
              confirmLabel: 'Replace and push',
              destructive: true,
              returnFocus: _pushId,
              onConfirm: () {
                setState(() => _confirmReplace = false);
                _send(server);
              },
              onCancel: () => setState(() => _confirmReplace = false),
            ),
        ],
      ],
    );
  }

  List<Widget> _serverDiagnostics(String key) => <Widget>[
    for (final Diagnostic diagnostic in (_validated ?? const <String, List<Diagnostic>>{})[key] ?? const <Diagnostic>[])
      dom.span(classes: 'animus-sync-diagnostic is-${diagnostic.severity.name}', <Widget>[
        const dom.span(classes: 'animus-tag is-server', <Widget>[Component.text('server')]),
        dom.code(<Widget>[Component.text(diagnostic.path)]),
        Component.text(' ${diagnostic.message}'),
      ]),
  ];

  Widget _choice<T>(String label, T value, List<(T, String)> options, void Function(T value) onChanged) => dom.div(
    classes: 'animus-segmented',
    attributes: <String, String>{'role': 'radiogroup', 'aria-label': label},
    <Widget>[
      for (final (T option, String text) in options)
        Button(
          label: text,
          size: ButtonSize.small,
          variant: ButtonVariant.ghost,
          attributes: <String, String>{'role': 'radio', 'aria-checked': '${option == value}'},
          onPressed: () => onChanged(option),
        ),
    ],
  );

  Widget _pullPanel(ServerProfile server) {
    final bool online = component.hub.status(server.id).online;
    final List<CatalogEntry>? catalog = _catalog;
    final String filter = _filter.trim().toLowerCase();
    final List<CatalogEntry> shown = <CatalogEntry>[
      for (final CatalogEntry entry in catalog ?? const <CatalogEntry>[])
        if (filter.isEmpty || entry.key.toLowerCase().contains(filter)) entry,
    ];
    final PullPlan? plan = _pull;
    final int writes = plan == null ? 0 : pullWrites(plan, _pullChoices).length;
    return dom.section(
      classes: 'animus-panel',
      attributes: const <String, String>{'aria-labelledby': 'animus-pull-title'},
      <Widget>[
        const dom.h2(id: 'animus-pull-title', <Widget>[
          InfoTip(
            tip: 'Copies picked server documents into this workspace as one undo step. A document you also changed is only replaced when you choose so.',
            docs: Docs.sending,
            child: Component.text('Pull from the server'),
          ),
        ]),
        dom.div(classes: 'animus-row', <Widget>[
          Button.secondary(
            label: catalog == null ? 'Load the server catalog' : 'Reload the catalog',
            size: ButtonSize.small,
            disabled: !online || _busy != null,
            onPressed: () => _loadCatalog(server),
          ),
        ]),
        if (catalog != null && catalog.isEmpty)
          const dom.p(classes: 'animus-muted', <Widget>[Component.text('The server has no documents yet.')]),
        if (catalog != null && catalog.isNotEmpty) ...<Widget>[
          dom.div(classes: 'animus-row', <Widget>[
            TextInput(
              placeholder: 'Filter',
              value: _filter,
              size: ComponentSize.sm,
              attributes: const <String, String>{'aria-label': 'Filter the server catalog'},
              onChanged: (String value) => setState(() => _filter = value),
            ),
            Button.ghost(
              label: 'Select all shown',
              size: ButtonSize.small,
              onPressed: () => setState(() => _pullSelected.addAll(<String>[for (final CatalogEntry entry in shown) entry.key])),
            ),
            Button(
              variant: _variant(SyncStep.comparePull),
              label: 'Compare ${_pullSelected.length} selected',
              size: ButtonSize.small,
              disabled: _pullSelected.isEmpty || !online || _busy != null,
              onPressed: () => _comparePull(server),
            ),
          ]),
          dom.ul(classes: 'animus-catalog', <Widget>[
            for (final CatalogEntry entry in shown)
              dom.li(<Widget>[
                ArcaneCheckbox(
                  checked: _pullSelected.contains(entry.key),
                  label: '${entry.kind.wireName} ${entry.id}${component.store.document(entry.kind, entry.id) == null ? '' : ' (also here)'}',
                  size: ComponentSize.sm,
                  onChanged: (bool value) => setState(() => value ? _pullSelected.add(entry.key) : _pullSelected.remove(entry.key)),
                ),
              ]),
          ]),
        ],
        if (plan != null) ...<Widget>[
          dom.p(<Widget>[
            Component.text(
              '${plan.where(SyncState.added).length} new, ${plan.where(SyncState.conflict).length} different, '
              '${plan.where(SyncState.same).length} the same.',
            ),
          ]),
          for (final PullEntry entry in plan.where(SyncState.conflict))
            dom.div(classes: 'animus-sync-entry', <Widget>[
              dom.span(<Widget>[
                dom.code(<Widget>[Component.text('${entry.kind.wireName} ${entry.id}')]),
                dom.span(classes: 'animus-muted', <Widget>[Component.text(' ${differingFields(entry.local?.json, entry.server.source).join(', ')} differ')]),
              ]),
              _choice<PullChoice>('For ${entry.id}', _pullChoices[entry.key] ?? defaultPullChoice(entry), const <(PullChoice, String)>[
                (PullChoice.keep, 'Keep mine'),
                (PullChoice.take, 'Take the server copy'),
              ], (PullChoice value) => setState(() => _pullChoices[entry.key] = value)),
            ]),
          dom.div(classes: 'animus-row', <Widget>[
            Button(
              variant: _variant(SyncStep.pull),
              label: 'Pull $writes document${writes == 1 ? '' : 's'}',
              size: ButtonSize.small,
              disabled: writes == 0 || _busy != null,
              onPressed: () => _takePull(server),
            ),
            if (writes > 0)
              dom.span(classes: 'animus-muted', <Widget>[
                Component.text(
                  <String>[for (final ({DocumentKind kind, String id, String source, String path}) write in pullWrites(plan, _pullChoices).take(6)) write.id]
                      .join(', '),
                ),
              ]),
          ]),
        ],
      ],
    );
  }
}
