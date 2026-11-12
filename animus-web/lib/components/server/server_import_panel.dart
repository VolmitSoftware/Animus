/// Import on a paired server: upload the saves chosen on the Citizens screen,
/// stage them, read the server's review report, apply and roll back, all
/// through `/v1/imports`. The server runs the
/// same steps as `/animus import citizens`.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../services/server/error_copy.dart';
import '../../services/server/server_connection.dart';
import '../../services/server/server_imports.dart';
import '../../services/server/server_profiles.dart';
import '../../services/server/transport.dart';
import '../../state/server_hub.dart';
import '../../state/workspace_route.dart';
import '../gui/gui_fields.dart';
import '../../config/docs_links.dart';
import '../fold.dart';
import '../info_tip.dart';
import 'connection_pill.dart';
import 'live_confirm.dart';

/// An import state in words.
String importStateLabel(String state) => switch (state) {
      'none' => 'Nothing staged',
      'staged' => 'Staged, not installed',
      'applied' => 'Installed and running',
      'rolled-back' => 'Rolled back',
      _ => state,
    };

/// One issue from a staged report: a line of text, or an object with a
/// message and the record it is about.
/// The pair command that grants every scope an import needs.
const String importPairCommand = '/animus editor pair scopes=read,import,apply:npcs,apply:behaviors,apply:gui';

String importIssueLine(Object? issue) {
  if (issue is String) return issue;
  if (issue is Map<String, Object?>) {
    final Object? message = issue['message'] ?? issue['reason'] ?? issue['code'];
    final Object? subject = issue['id'] ?? issue['record'] ?? issue['shop'] ?? issue['npc'] ?? issue['file'];
    final String text = message is String ? message : 'An issue with no description';
    return subject == null ? text : '$subject: $text';
  }
  return '$issue';
}

class ServerImportPanel extends StatefulWidget {
  const ServerImportPanel({required this.hub, required this.uploads, required this.worlds, this.onState, super.key});

  final ServerHub hub;

  /// Called with the import state after each server answer, so the screen
  /// can step back while Apply is the next step.
  final void Function(String state)? onState;

  /// The files chosen on the screen, in upload order.
  final List<ImportUpload> uploads;

  /// World mappings for staging (old name to new name).
  final Map<String, String> worlds;

  @override
  State<ServerImportPanel> createState() => _ServerImportPanelState();
}

class _ServerImportPanelState extends State<ServerImportPanel> {
  String? _serverId;
  String _mode = 'reject';
  String _label = '';
  ImportReview? _review;
  String? _error;
  String? _busy;
  /// The write waiting for its inline confirm: apply or rollback.
  String? _confirm;

  static String _actionId(String action) => 'animus-import-$action';

  @override
  void initState() {
    super.initState();
    component.hub.addListener(_changed);
    final List<ServerProfile> servers = component.hub.servers;
    if (servers.isNotEmpty) _serverId = servers.first.id;
  }

  @override
  void dispose() {
    component.hub.removeListener(_changed);
    super.dispose();
  }

  void _changed() {
    if (mounted) setState(() {});
  }

  ServerConnection? get _connection => _serverId == null || component.hub.server(_serverId!) == null ? null : component.hub.connection(_serverId!);

  ServerImports? get _imports {
    final ServerConnection? connection = _connection;
    if (connection == null) return null;
    return ServerImports(send: connection.request, path: () => connection.status.path);
  }

  Future<void> _run(String what, Future<ImportReview> Function(ServerImports imports) step) async {
    final ServerImports? imports = _imports;
    if (imports == null) return;
    setState(() {
      _busy = what;
      _error = null;
      _confirm = null;
    });
    try {
      final ImportReview review = await step(imports);
      if (!mounted) return;
      setState(() => _review = review);
      component.onState?.call(review.state);
    } on ImportRefused catch (refused) {
      if (mounted) setState(() => _error = importRefusalText(refused));
    } on NotConnected catch (offline) {
      if (mounted) setState(() => _error = offline.status.message ?? 'Not connected. Use Connect, then try again.');
    } on TransportFailure catch (failure) {
      if (mounted) setState(() => _error = failure.message);
    } on Object {
      if (mounted) setState(() => _error = transportCopy('dropped').text);
    } finally {
      if (mounted) setState(() => _busy = null);
    }
  }

  Future<ImportReview> _uploadAndStage(ServerImports imports) async {
    ImportReview? last;
    for (final ImportUpload upload in component.uploads) {
      last = await imports.upload(upload);
    }
    if (last == null) throw const ImportRefused('invalid-request', 'No file chosen. Choose a save above.');
    return imports.stage(mode: _mode, label: _mode == 'duplicate' ? _label.trim() : null, worlds: component.worlds);
  }

  @override
  Widget build(BuildContext context) {
    final List<ServerProfile> servers = component.hub.servers;
    final ServerConnection? connection = _connection;
    final ConnectionStatus? status = connection?.status;
    final bool online = status?.online ?? false;
    final bool scoped = connection?.token?.scopes.contains('import') ?? false;
    final ImportReview? review = _review;
    final ConnectionPath? path = status?.path;
    final List<String> problems = <String>[
      for (final ImportUpload upload in component.uploads)
        if (uploadProblem(upload, path) != null) uploadProblem(upload, path)!,
    ];
    return dom.section(classes: 'animus-panel animus-server-import', attributes: const <String, String>{'aria-label': 'Import on a server'}, <Widget>[
      const dom.h2(<Widget>[
        InfoTip(
          tip: 'Nothing installs until you apply. Apply spawns the NPCs Citizens had spawned and turns on everything that converted.',
          docs: Docs.liveImports,
          child: Component.text('Import on a server'),
        ),
      ]),
      if (servers.isEmpty)
        const dom.p(classes: 'animus-empty-line', <Widget>[
          InfoTip(tip: 'Run $importPairCommand and open its link.', docs: Docs.pairing, child: Component.text('No servers paired. Pair one with the import scope.')),
        ])
      else ...<Widget>[
        dom.div(classes: 'animus-row', <Widget>[
          GuiSelect(
            value: _serverId,
            options: <String>[for (final ServerProfile server in servers) server.id],
            labels: <String, String>{for (final ServerProfile server in servers) server.id: server.name},
            onChanged: (String? id) => setState(() {
              _serverId = id;
              _review = null;
              _error = null;
            }),
          ),
          dom.span(attributes: const <String, String>{'data-testid': 'import-connection'}, <Widget>[
            if (status != null) ConnectionPill(status: status, hasToken: connection?.token != null),
            if (status != null && phaseHint(status, hasToken: connection?.token != null).isNotEmpty)
              dom.span(classes: 'animus-muted', <Widget>[Component.text(' ${phaseHint(status, hasToken: connection?.token != null)}')]),
          ]),
          if (!online && connection != null)
            Button.secondary(label: 'Connect', size: ButtonSize.small, onPressed: () => component.hub.connect(connection.profile.id)),
          if (online) Button.ghost(label: 'Load review', size: ButtonSize.small, disabled: _busy != null || !scoped, onPressed: () => _run('Loading', (ServerImports i) => i.status())),
        ]),
        if (online && !scoped)
          const dom.p(classes: 'animus-citizens-error', attributes: <String, String>{'role': 'alert'}, <Widget>[
            InfoTip(tip: 'Run $importPairCommand and open the new link.', docs: Docs.tokens, child: Component.text('This pairing cannot import. Pair again with the import scope.')),
          ]),
        if (path == ConnectionPath.relay)
          dom.p(classes: 'animus-muted', attributes: const <String, String>{'data-testid': 'import-relay-note'}, <Widget>[
            Component.text('Relay limit: ${formatSize(relayUploadBytes)} per file. Use the direct path for larger saves.'),
          ]),
        dom.div(classes: 'animus-row', <Widget>[
          const dom.span(classes: 'animus-muted', <Widget>[
            InfoTip(
              tip: 'What staging does when an NPC from this save is already on the server: refuse, replace the earlier import, or install a labeled second copy.',
              docs: Docs.liveImports,
              child: Component.text('On collision'),
            ),
          ]),
          GuiSelect(
            value: _mode,
            options: const <String>['reject', 'replace', 'duplicate'],
            labels: const <String, String>{
              'reject': 'Refuse on collision',
              'replace': 'Replace an earlier import',
              'duplicate': 'Install a second copy',
            },
            onChanged: (String? mode) => setState(() => _mode = mode ?? 'reject'),
          ),
          if (_mode == 'duplicate')
            TextInput(
              placeholder: 'Copy label (town-copy)',
              value: _label,
              size: ComponentSize.sm,
              onChanged: (String value) => setState(() => _label = value),
            ),
          Button.secondary(
            label: _busy == 'Uploading' ? 'Uploading' : 'Upload and stage',
            icon: ArcaneIcon.upload(),
            size: ButtonSize.small,
            disabled: !online || !scoped || _busy != null || component.uploads.isEmpty || problems.isNotEmpty || review?.state == 'applied',
            onPressed: () => _run('Uploading', _uploadAndStage),
          ),
        ]),
        dom.p(classes: 'animus-muted', attributes: const <String, String>{'data-testid': 'import-files'}, <Widget>[
          Component.text(component.uploads.isEmpty
              ? 'No files chosen.'
              : 'Uploads: ${component.uploads.map((ImportUpload upload) => '${upload.label} (${formatSize(upload.bytes.length)})').join(', ')}'),
        ]),
        for (final String problem in problems)
          dom.p(classes: 'animus-citizens-error', attributes: const <String, String>{'role': 'alert', 'data-testid': 'import-problem'}, <Widget>[Component.text(problem)]),
        if (_error != null)
          dom.div(classes: 'animus-citizens-error', attributes: const <String, String>{'role': 'alert', 'data-testid': 'import-error'}, <Widget>[
            const dom.h3(<Widget>[Component.text('Not imported')]),
            dom.p(<Widget>[Component.text(_error!)]),
          ]),
        if (review != null) ..._reviewView(review),
      ],
    ]);
  }

  List<Widget> _reviewView(ImportReview review) {
    final String? importId = review.importId;
    final Map<String, Object?> citizens = review.reports['citizens'] ?? const <String, Object?>{};
    final Map<String, Object?> shops = review.reports['shopkeepers'] ?? const <String, Object?>{};
    final List<Object?> issues = <Object?>[
      ...((citizens['issues'] as List<Object?>?) ?? const <Object?>[]),
      ...((shops['issues'] as List<Object?>?) ?? const <Object?>[]),
    ];
    final bool busy = _busy != null;
    return <Widget>[
      dom.p(attributes: const <String, String>{'data-testid': 'import-state'}, <Widget>[dom.strong(<Widget>[Component.text(importStateLabel(review.state))])]),
      dom.div(classes: 'animus-stats', attributes: const <String, String>{'data-testid': 'import-review'}, <Widget>[
        if (citizens['records'] != null) _stat('${citizens['records']}', 'Citizens records'),
        if (citizens['valid'] != null) _stat('${citizens['valid']}', 'valid'),
        if (citizens['skins'] != null) _stat('${citizens['skins']}', 'unique skins'),
        if (shops['shops'] != null) _stat('${shops['shops']}', 'Shopkeepers shops'),
        if (review.npcs != null) _stat('${review.npcs}', 'NPCs installed'),
        _stat('${citizens['issuesTotal'] ?? issues.length}', 'issues'),
      ]),
      if (importId != null)
        dom.p(classes: 'animus-muted animus-citizens-id', <Widget>[
          const Component.text('Import '),
          dom.code(attributes: const <String, String>{'data-testid': 'import-id'}, <Widget>[Component.text(importId)]),
        ]),
      if (review.lines.isNotEmpty)
        dom.ul(classes: 'animus-citizens-lines', attributes: const <String, String>{'data-testid': 'import-lines'}, <Widget>[
          for (final String line in review.lines) dom.li(<Widget>[Component.text(line)]),
        ]),
      if (issues.isNotEmpty)
        Fold(summary: <Widget>[Component.text('Issues (${issues.length})')], children: <Widget>[
          dom.ul(classes: 'animus-citizens-lines', <Widget>[for (final Object? issue in issues) dom.li(<Widget>[Component.text(importIssueLine(issue))])]),
        ]),
      if (importId != null && _confirm == 'apply')
        LiveConfirm(
          question: 'Install this import on the server?',
          detail: review.commandNamespaces.isEmpty
              ? 'NPCs spawn as they were, with their patrols, combat, shops and other converted features on.'
              : 'NPCs spawn as they were, with their converted features on. Their commands run as before: ${review.commandNamespaces.join(', ')} joins the command allow-list, which needs apply:commands.',
          confirmLabel: 'Install import',
          returnFocus: _actionId('apply'),
          onConfirm: () => _run('Applying', (ServerImports i) => i.apply(importId)),
          onCancel: () => setState(() => _confirm = null),
        )
      else if (importId != null && _confirm == 'rollback')
        LiveConfirm(
          question: 'Roll back this import on the server?',
          detail: 'Removes its NPCs, shops and behaviors, and the command namespaces it allowed. The staged import stays.',
          confirmLabel: 'Roll back',
          destructive: true,
          returnFocus: _actionId('rollback'),
          onConfirm: () => _run('Rolling back', (ServerImports i) => i.rollback(importId)),
          onCancel: () => setState(() => _confirm = null),
        )
      else
        dom.div(classes: 'animus-row', <Widget>[
          if (importId != null && (review.state == 'staged' || review.state == 'rolled-back'))
            Button.primary(
              label: _busy == 'Applying' ? 'Applying' : 'Apply',
              icon: ArcaneIcon.check(),
              size: ButtonSize.small,
              disabled: busy,
              attributes: <String, String>{'id': _actionId('apply')},
              onPressed: () => setState(() => _confirm = 'apply'),
            ),
          if (importId != null && review.state == 'applied')
            Button.secondary(
              label: 'Roll back',
              icon: ArcaneIcon.undo(),
              size: ButtonSize.small,
              disabled: busy,
              attributes: <String, String>{'id': _actionId('rollback')},
              onPressed: () => setState(() => _confirm = 'rollback'),
            ),
        ]),
      if (_serverId != null && review.state == 'applied')
        dom.p(attributes: const <String, String>{'data-testid': 'import-next'}, <Widget>[
          dom.a(href: ServerRoute(_serverId!, const <String>[]).format(), const <Widget>[Component.text('Open the imported NPCs')]),
          const dom.span(classes: 'animus-muted', <Widget>[Component.text(' in the server catalog; filter by citizens- or shopkeeper-.')]),
        ]),
    ];
  }

  Widget _stat(String value, String label) => dom.div(classes: 'animus-stat', <Widget>[
        dom.strong(<Widget>[Component.text(value)]),
        dom.span(<Widget>[Component.text(label)]),
      ]);
}
