/// Import on a paired server: upload the saves chosen on the Citizens screen,
/// stage them, read the server's review report, apply, accept reviewed
/// categories and roll back, all through `/v1/imports`. The server runs the
/// same steps as `/animus import citizens`.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../services/server/server_connection.dart';
import '../../services/server/server_imports.dart';
import '../../services/server/server_profiles.dart';
import '../../services/server/transport.dart';
import '../../state/server_hub.dart';
import '../gui/gui_fields.dart';

class ServerImportPanel extends StatefulWidget {
  const ServerImportPanel({required this.hub, required this.uploads, required this.worlds, super.key});

  final ServerHub hub;

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
  bool _confirmRollback = false;
  final Set<String> _accept = <String>{};

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
      _confirmRollback = false;
    });
    try {
      final ImportReview review = await step(imports);
      if (!mounted) return;
      setState(() {
        _review = review;
        _accept.retainAll(<String>{for (final String category in importCategories) if ((review.pending[category] ?? 0) > 0) category});
      });
    } on ImportRefused catch (refused) {
      if (mounted) setState(() => _error = refused.message);
    } on NotConnected {
      if (mounted) setState(() => _error = 'The server is not connected.');
    } on TransportFailure catch (failure) {
      if (mounted) setState(() => _error = 'The ${failure.path.label.toLowerCase()} path failed: ${failure.message}');
    } finally {
      if (mounted) setState(() => _busy = null);
    }
  }

  Future<ImportReview> _uploadAndStage(ServerImports imports) async {
    ImportReview? last;
    for (final ImportUpload upload in component.uploads) {
      last = await imports.upload(upload);
    }
    if (last == null) throw const ImportRefused('invalid-request', 'Choose a Citizens or Shopkeepers save above first.');
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
      const dom.h2(<Widget>[Component.text('Import on a server')]),
      const dom.p(classes: 'animus-muted', <Widget>[
        Component.text('Upload the files chosen above to a paired server. The server converts and stages them, shows its review, '
            'and installs nothing until you apply. Imported NPCs stay despawned and their patrols, combat, riding, shops and builds stay off until accepted.'),
      ]),
      if (servers.isEmpty)
        const dom.p(<Widget>[Component.text('No server is paired in this browser. Run /animus editor pair scopes=read,import,apply:npcs,apply:behaviors,apply:gui on the server and open its link.')])
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
          dom.span(classes: 'animus-muted', attributes: const <String, String>{'data-testid': 'import-connection'}, <Widget>[
            Component.text(status?.label ?? 'Offline'),
          ]),
          if (!online && connection != null)
            Button.secondary(label: 'Connect', size: ButtonSize.small, onPressed: () => component.hub.connect(connection.profile.id)),
          if (online) Button.ghost(label: 'Load review', size: ButtonSize.small, disabled: _busy != null || !scoped, onPressed: () => _run('Loading', (ServerImports i) => i.status())),
        ]),
        if (online && !scoped)
          const dom.p(classes: 'animus-citizens-error', attributes: <String, String>{'role': 'alert'}, <Widget>[
            Component.text('This token has no import scope. Pair again with /animus editor pair scopes=read,import,apply:npcs,apply:behaviors,apply:gui.'),
          ]),
        if (path == ConnectionPath.relay)
          dom.p(classes: 'animus-muted', attributes: const <String, String>{'data-testid': 'import-relay-note'}, <Widget>[
            Component.text('Connected over the relay: files up to ${formatSize(relayUploadBytes)} fit a relay frame. '
                'Larger saves need the direct path (the server\'s listener).'),
          ]),
        dom.div(classes: 'animus-row', <Widget>[
          GuiSelect(
            value: _mode,
            options: const <String>['reject', 'replace', 'duplicate'],
            labels: const <String, String>{
              'reject': 'Refuse NPCs that already exist',
              'replace': 'Replace an earlier import',
              'duplicate': 'Install a second copy',
            },
            onChanged: (String? mode) => setState(() => _mode = mode ?? 'reject'),
          ),
          if (_mode == 'duplicate')
            TextInput(
              placeholder: 'Copy label, such as town-copy',
              value: _label,
              size: ComponentSize.sm,
              onChanged: (String value) => setState(() => _label = value),
            ),
          Button.primary(
            label: _busy == 'Uploading' ? 'Uploading' : 'Upload and stage',
            icon: ArcaneIcon.upload(),
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
            const dom.h3(<Widget>[Component.text('The server refused')]),
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
      dom.div(classes: 'animus-stats', attributes: const <String, String>{'data-testid': 'import-review'}, <Widget>[
        _stat(review.state, 'state'),
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
        dom.details(<Widget>[
          dom.summary(<Widget>[Component.text('Issues (${issues.length})')]),
          dom.ul(classes: 'animus-citizens-lines', <Widget>[for (final Object? issue in issues) dom.li(<Widget>[Component.text('$issue')])]),
        ]),
      dom.div(classes: 'animus-row', <Widget>[
        if (importId != null && (review.state == 'staged' || review.state == 'rolled-back'))
          Button.primary(
            label: _busy == 'Applying' ? 'Applying' : 'Apply',
            icon: ArcaneIcon.check(),
            disabled: busy,
            onPressed: () => _run('Applying', (ServerImports i) => i.apply(importId)),
          ),
        if (importId != null && review.state == 'applied')
          Button(
            label: _confirmRollback ? 'Confirm roll back' : 'Roll back',
            icon: ArcaneIcon.undo(),
            variant: _confirmRollback ? ButtonVariant.destructive : ButtonVariant.secondary,
            disabled: busy,
            onPressed: () {
              if (!_confirmRollback) {
                setState(() => _confirmRollback = true);
                return;
              }
              _run('Rolling back', (ServerImports i) => i.rollback(importId));
            },
          ),
      ]),
      if (_confirmRollback)
        const dom.p(classes: 'animus-muted', <Widget>[
          Component.text('Rolling back undoes every acceptance, the shops, the generated behaviors and the NPC definitions of this import. '
              'The staged preview stays, so it can be applied again.'),
        ]),
      if (importId != null && review.state == 'applied') ..._acceptView(review, importId),
    ];
  }

  List<Widget> _acceptView(ImportReview review, String importId) => <Widget>[
        const dom.h3(<Widget>[Component.text('Review categories')]),
        dom.div(classes: 'animus-import-categories', attributes: const <String, String>{'data-testid': 'import-categories'}, <Widget>[
          for (final String category in importCategories)
            ArcaneCheckbox(
              checked: _accept.contains(category),
              label: '$category: ${review.pending[category] ?? 0} waiting',
              size: ComponentSize.sm,
              disabled: (review.pending[category] ?? 0) == 0,
              onChanged: (bool on) => setState(() => on ? _accept.add(category) : _accept.remove(category)),
            ),
        ]),
        dom.div(classes: 'animus-row', <Widget>[
          Button.secondary(
            label: 'Accept selected',
            size: ButtonSize.small,
            disabled: _busy != null || _accept.isEmpty,
            onPressed: () => _run('Accepting', (ServerImports i) => i.accept(importId, _accept.toList())),
          ),
          Button.secondary(
            label: 'Accept all',
            size: ButtonSize.small,
            disabled: _busy != null || review.waiting == 0,
            onPressed: () => _run('Accepting', (ServerImports i) => i.accept(importId, const <String>['all'])),
          ),
        ]),
      ];

  Widget _stat(String value, String label) => dom.div(classes: 'animus-stat', <Widget>[
        dom.strong(<Widget>[Component.text(value)]),
        dom.span(<Widget>[Component.text(label)]),
      ]);
}
