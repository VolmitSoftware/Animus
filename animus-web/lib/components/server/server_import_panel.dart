/// Import on a paired server, for the Import wizard's dtlTraders and
/// "Already on my server" sources: upload the chosen files (if any), let the
/// server convert them, read its review, install and roll back, all through
/// `/v1/imports`. The server runs the same steps as `/animus import citizens`.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../services/server/connection_status.dart';
import '../../services/server/error_copy.dart';
import '../../services/server/server_connection.dart';
import '../../services/server/server_imports.dart';
import '../../services/server/server_profiles.dart';
import '../../services/server/transport.dart';
import '../../state/server_hub.dart';
import '../../ui/alert.dart';
import '../../ui/badge.dart';
import '../../ui/field.dart';
import '../../ui/mode.dart';
import '../../ui/section.dart';
import '../../ui/tone.dart';
import '../offline/import_plan.dart';
import '../offline/page_parts.dart';
import '../select/animus_select.dart';
import '../../ui/stack.dart';

/// The state of one server import: the server it runs on, the collision
/// mode, the last review, the step in progress and the last refusal. The
/// wizard keeps it while the steps change.
class ServerImportFlow {
  ServerImportFlow(this.hub, this._changed) {
    final List<ServerProfile> servers = hub.servers;
    if (servers.isNotEmpty) serverId = servers.first.id;
  }

  final ServerHub hub;
  final void Function() _changed;

  String? serverId;

  /// What the server does when an NPC of this save is already installed:
  /// reject, replace or duplicate. Advanced only; Simple always refuses.
  String mode = 'reject';

  /// The label of a second copy (duplicate).
  String label = '';
  ImportReview? review;
  String? error;

  /// The step in progress ("Converting", "Installing"), or null.
  String? busy;

  ServerConnection? get connection => serverId == null || hub.server(serverId!) == null ? null : hub.connection(serverId!);

  ConnectionStatus? get status => serverId == null ? null : hub.status(serverId!);

  bool get online => connection?.status.online ?? false;

  /// The pairing lets this browser import.
  bool get canImport => connection?.token?.scopes.contains('import') ?? false;

  ConnectionPath? get path => connection?.status.path;

  /// Picks the server and connects to it when it is idle.
  void pick(String? id) {
    serverId = id;
    review = null;
    error = null;
    connectIfIdle();
    _changed();
  }

  /// Opens the connection the import needs, as the server's page would.
  void connectIfIdle() {
    final String? id = serverId;
    if (id == null || !hub.hasToken(id)) return;
    if (hub.connection(id).status.phase == ConnectionPhase.offline) hub.connect(id);
  }

  ServerImports? get _imports {
    final ServerConnection? connection = this.connection;
    if (connection == null) return null;
    return ServerImports(send: connection.request, path: () => connection.status.path);
  }

  /// Runs one step; true when the server answered with a review.
  Future<bool> _run(String what, Future<ImportReview> Function(ServerImports imports) step) async {
    final ServerImports? imports = _imports;
    if (imports == null) return false;
    busy = what;
    error = null;
    _changed();
    try {
      review = await step(imports);
      return true;
    } on ImportRefused catch (refused) {
      error = importRefusalText(refused);
    } on NotConnected catch (offline) {
      error = offline.status.message ?? 'Not connected. Wait for the server, then try again.';
    } on TransportFailure catch (failure) {
      error = failure.message;
    } on Object {
      error = transportCopy('dropped').text;
    } finally {
      busy = null;
      _changed();
    }
    return false;
  }

  /// Uploads [uploads] (none when the server already has the saves) and
  /// asks the server to convert them.
  Future<bool> convert(List<ImportUpload> uploads, Map<String, String> worlds, {required bool advanced}) => _run('Converting', (ServerImports imports) async {
        for (final ImportUpload upload in uploads) {
          await imports.upload(upload);
        }
        final String collision = advanced ? mode : 'reject';
        return imports.stage(mode: collision, label: collision == 'duplicate' ? label.trim() : null, worlds: worlds);
      });

  /// Reads the server's current import.
  Future<bool> refresh() => _run('Checking', (ServerImports imports) => imports.status());

  Future<bool> install() async {
    final String? id = review?.importId;
    return id != null && await _run('Installing', (ServerImports imports) => imports.apply(id));
  }

  Future<bool> rollback() async {
    final String? id = review?.importId;
    return id != null && await _run('Rolling back', (ServerImports imports) => imports.rollback(id));
  }
}

/// The connection state in words: the path (direct or relay) only in
/// Advanced.
String linkLabel(ConnectionStatus status, {required bool advanced}) {
  final String label = status.label;
  if (advanced) return label;
  final int bracket = label.indexOf(' (');
  return bracket < 0 ? label : label.substring(0, bracket);
}

BadgeTone _linkTone(ConnectionStatus status) => switch (status.phase) {
      ConnectionPhase.online => BadgeTone.ok,
      ConnectionPhase.connecting || ConnectionPhase.reconnecting => BadgeTone.info,
      ConnectionPhase.needsPairing || ConnectionPhase.refused => BadgeTone.error,
      ConnectionPhase.offline => BadgeTone.neutral,
    };

/// The server field of the Files step: which server, its state, and in
/// Advanced what to do when an NPC is already installed.
class ServerImportFields extends StatelessWidget {
  const ServerImportFields({required this.flow, super.key});

  final ServerImportFlow flow;

  @override
  Widget build(BuildContext context) {
    final bool advanced = ModeScope.advanced(context);
    final List<ServerProfile> servers = flow.hub.servers;
    final ConnectionStatus? status = flow.status;
    return stack(spacing: 16, <Widget>[
      Field(
        label: 'Server',
        forId: servers.length > 1 ? 'animus-import-server' : null,
        child: dom.div(attributes: const <String, String>{'data-testid': 'import-connection'}, <Widget>[
          Wrap(spacing: 8, runSpacing: 4, crossAxisAlignment: WrapCrossAlignment.center, children: <Widget>[
          if (servers.length > 1)
            AnimusSelect.values(
              id: 'animus-import-server',
              value: flow.serverId,
              values: <String>[for (final ServerProfile server in servers) server.id],
              labels: <String, String>{for (final ServerProfile server in servers) server.id: server.name},
              onChanged: flow.pick,
            )
          else if (servers.isNotEmpty)
            Text(servers.first.name, weight: FontWeight.w500),
          if (status != null) StatusBadge(linkLabel(status, advanced: advanced), tone: _linkTone(status), dot: true),
          ]),
        ]),
      ),
      if (advanced) ...<Widget>[
        Field(
          label: 'On collision',
          forId: 'animus-import-collision',
          tip: 'What the server does when an NPC from this save is already installed.',
          docs: Docs.liveImports,
          child: AnimusSelect.values(
            id: 'animus-import-collision',
            value: flow.mode,
            values: const <String>['reject', 'replace', 'duplicate'],
            labels: const <String, String>{
              'reject': 'Refuse',
              'replace': 'Replace the earlier import',
              'duplicate': 'Install a second copy',
            },
            onChanged: (String? mode) {
              flow.mode = mode ?? 'reject';
              flow._changed();
            },
          ),
        ),
        if (flow.mode == 'duplicate')
          Field(
            label: 'Copy label',
            forId: 'animus-import-copy-label',
            child: TextInput(
              id: 'animus-import-copy-label',
              placeholder: 'town-copy',
              value: flow.label,
              onChanged: (String value) => flow.label = value,
            ),
          ),
        if (flow.path == ConnectionPath.relay)
          dom.div(attributes: const <String, String>{'data-testid': 'import-relay-note'}, <Widget>[
            mutedText('On this connection each file can be at most ${formatSize(relayUploadBytes)}.'),
          ]),
      ],
    ]);
  }
}

/// The server's answer: the state, what the import holds in words, the
/// issues, and in Advanced the server's own lines and the import id.
class ServerImportReview extends StatelessWidget {
  const ServerImportReview({required this.flow, super.key});

  final ServerImportFlow flow;

  @override
  Widget build(BuildContext context) {
    final ImportReview? review = flow.review;
    final bool advanced = ModeScope.advanced(context);
    final List<String> issues = review == null ? const <String>[] : serverIssues(review);
    return stack(spacing: 16, <Widget>[
      if (flow.error != null)
        dom.div(attributes: const <String, String>{'data-testid': 'import-error'}, <Widget>[
          AppAlert(tone: AlertTone.error, title: 'Not imported', message: flow.error!, live: true),
        ]),
      if (review != null) ...<Widget>[
        Wrap(spacing: 16, runSpacing: 8, crossAxisAlignment: WrapCrossAlignment.center, children: <Widget>[
          dom.span(attributes: const <String, String>{'data-testid': 'import-state'}, <Widget>[
            StatusBadge(importStateLabel(review.state), tone: review.state == 'applied' ? BadgeTone.ok : BadgeTone.neutral),
          ]),
          dom.p(attributes: const <String, String>{'data-testid': 'import-counts'}, <Widget>[Component.text(serverCountsLine(review))]),
        ]),
        if (issues.isNotEmpty)
          AppSection(
            title: 'Problems',
            count: issues.length,
            open: issues.length <= 20,
            children: <Widget>[
              rowList(<Widget>[for (final String issue in issues) listItem(Text(issue, size: FontSize.sm))]),
            ],
          ),
        if (advanced && review.lines.isNotEmpty)
          AppSection(
            title: 'Server report',
            children: <Widget>[
              rowList(attributes: const <String, String>{'data-testid': 'import-lines'}, <Widget>[
                for (final String line in review.lines) listItem(Text(line, size: FontSize.sm)),
              ]),
              if (review.importId != null)
                dom.p(<Widget>[
                  const Component.text('Import '),
                  dom.code(attributes: const <String, String>{'data-testid': 'import-id'}, <Widget>[Component.text(review.importId!)]),
                ]),
            ],
          ),
      ],
    ]);
  }
}
