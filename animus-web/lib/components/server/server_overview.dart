/// Home of a paired server: a card per job with its count from the server's
/// list, the documents that need attention and the first steps. While the
/// list loads it shows skeletons (the save bar says how far it got), never
/// an empty library. Advanced adds the Server section: the connection, what
/// the pairing allows (in words and ids), Sync, Connect or Disconnect,
/// Import on the server and Forget.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../model/document_kind.dart';
import '../../model/server_binding.dart';
import '../../services/browser.dart';
import '../../services/server/connection_status.dart';
import '../../services/server/error_copy.dart';
import '../../services/server/server_connection.dart';
import '../../services/server/server_profiles.dart';
import '../../state/editor_store.dart';
import '../../state/library.dart';
import '../../state/live_session.dart';
import '../../state/server_hub.dart';
import '../../state/workspace_route.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/confirm_dialog.dart';
import '../../ui/info_tip.dart';
import '../../ui/menu.dart';
import '../../ui/mode.dart';
import '../../ui/page_header.dart';
import '../../ui/section.dart';
import '../../ui/skeleton.dart';
import '../advanced_notice.dart';
import '../in_advanced_state.dart';
import '../offline/home_parts.dart';
import '../offline/page_parts.dart';
import '../offline/start_rows.dart';
import 'connection_pill.dart';
import 'connection_words.dart';
import '../../services/server/scope_words.dart';
import '../../ui/stack.dart';
import '../../model/display_name.dart' show closing;

class ServerOverview extends StatelessWidget {
  const ServerOverview({required this.hub, required this.session, required this.onOpened, super.key});

  final ServerHub hub;
  final LiveSession session;

  /// Opens a document of this server's workspace.
  final void Function(DocumentKind kind, String id) onOpened;

  @override
  Widget build(BuildContext context) {
    final LiveSession session = this.session;
    final EditorStore store = session.store;
    final String workspaceId = store.workspace?.id ?? ServerHub.workspaceIdOf(session.serverId);
    final ServerConnection connection = session.connection;
    final ConnectionStatus status = connection.status;
    final ErrorCopy? problem = session.catalogProblem;
    final bool listed = session.catalogLoaded || store.libraryCounts().values.any((int count) => count > 0);
    final ServerBinding? binding = session.binding;
    final int hidden = ModeScope.advanced(context) ? 0 : hiddenOnly(store.libraryCounts());
    return dom.div(classes: 'ui-home', <Widget>[
      stack(spacing: 24, <Widget>[
        // The top bar already says whether it is online; Advanced shows the details below.
        PageHeader(title: connection.profile.name),
        if (session.waiting)
          AppAlert(
            tone: AlertTone.info,
            title: 'Open in another tab',
            message: 'This tab waits while another one edits this server.',
            action: Button.outline(label: 'Take over', size: ButtonSize.md, onPressed: session.takeOver),
          )
        else if (problem != null)
          AppAlert(
            tone: AlertTone.error,
            title: 'Could not list the server\'s files',
            message: problem.text,
            live: true,
            action: status.online ? Button.outline(label: 'Retry', size: ButtonSize.md, onPressed: session.loadCatalog) : null,
          )
        else if (!listed && !status.online && status.phase != ConnectionPhase.connecting)
          AppAlert(
            tone: AlertTone.warning,
            title: 'Not connected',
            message: phaseHint(status, hasToken: connection.token != null),
          ),
        if (!listed)
          const Skeleton(rows: 4, label: 'Loading the server\'s files')
        else if (hidden > 0)
          InAdvancedState(count: hidden, landing: hiddenLanding(workspaceId, store.libraryCounts()))
        else ...<Widget>[
          FirstSteps(
            steps: firstSteps(openedNpc: openedAnNpc(workspaceId), changed: session.drafts.isNotEmpty || (binding?.history.isNotEmpty ?? false), applied: binding?.history.isNotEmpty ?? false),
          ),
          HomeCards(workspaceId: workspaceId, counts: store.libraryCounts(), ownRoutes: store.libraryOwnRoutes(), server: true),
          NeedsAttention(workspaceId: workspaceId, rows: _attention(store)),
        ],
        if (ModeScope.advanced(context)) ServerDetails(hub: hub, session: session),
      ]),
    ]);
  }

  static List<AttentionRow> _attention(EditorStore store) => <AttentionRow>[
    for (final LibraryEntry entry in store.libraryAttention()) AttentionRow(kind: entry.kind, id: entry.id, name: entry.name, problems: entry.problems, stale: entry.stale),
  ];
}

/// Advanced: the connection, the pairing and what it allows, and the
/// server's own switches.
class ServerDetails extends StatelessWidget {
  const ServerDetails({required this.hub, required this.session, super.key});

  final ServerHub hub;
  final LiveSession session;

  static String? _serverLine(Map<String, Object?>? hello) {
    if (hello == null) return null;
    final List<String> parts = <String>[
      if (hello['animus'] is String) 'Animus ${hello['animus']}',
      if (hello['platform'] case final String platform) 'on ${platformName(platform)}',
      if (hello['minecraft'] is String) '${hello['minecraft']}',
    ];
    return parts.isEmpty ? null : parts.join(' ');
  }

  Future<void> _forget(ServerProfile profile) async {
    final bool yes = await askToConfirm(
      title: 'Forget ${closing(profile.name, '?')}',
      message: hub.forgetPrompt(profile.id),
      confirmLabel: 'Forget server',
      destructive: true,
    );
    if (!yes) return;
    final String? stillValid = await hub.forget(profile.id);
    final String line = ServerHub.forgotLine(profile.name, stillValid);
    stillValid == null ? ArcaneSonner.success(line) : ArcaneSonner.warning(line);
    navigate(const HomeRoute().format());
  }

  @override
  Widget build(BuildContext context) {
    final ServerConnection connection = session.connection;
    final ConnectionStatus status = connection.status;
    final ServerProfile profile = connection.profile;
    final ServerToken? token = connection.token;
    final bool owned = session.owned;
    final String hint = phaseHint(status, hasToken: token != null, connectHere: true);
    final Map<String, Object?> features = (connection.capabilities?['features'] as Map<String, Object?>?) ?? const <String, Object?>{};
    final String? server = _serverLine(connection.hello);
    final bool connectable = status.phase == ConnectionPhase.offline || status.phase == ConnectionPhase.refused;
    return dom.section(
      attributes: const <String, String>{'aria-labelledby': 'home-server', 'data-testid': 'server-details'},
      <Widget>[
        stack(spacing: 12, <Widget>[
          sectionHeading('Server', id: 'home-server', tip: 'The connection, the pairing and what it allows.', docs: Docs.connecting),
          dom.dl(classes: 'ui-facts', <Widget>[
            // The top bar already says Online; online, this row says only
            // which path carries the connection.
            const dom.dt(<Widget>[Component.text('Connection')]),
            dom.dd(
              attributes: const <String, String>{'data-testid': 'server-status'},
              <Widget>[
                if (status.online && status.path != null && !session.waiting)
                  Text('${status.path!.label} path')
                else
                  Wrap(
                    spacing: 8,
                    runSpacing: 4,
                    crossAxisAlignment: WrapCrossAlignment.center,
                    children: <Widget>[
                      ConnectionPill(status: status, hasToken: token != null, elsewhere: session.waiting, detailed: true),
                      if (hint.isNotEmpty && !session.waiting) Text(hint, size: FontSize.sm, color: TextColor.muted),
                    ],
                  ),
              ],
            ),
            if (server != null) ...<Widget>[
              const dom.dt(<Widget>[Component.text('Version')]),
              dom.dd(<Widget>[Component.text(server)]),
            ],
            if (profile.directUrl != null) ...<Widget>[
              const dom.dt(<Widget>[Component.text('Direct')]),
              dom.dd(<Widget>[
                dom.code(<Widget>[Component.text(profile.directUrl!)]),
              ]),
            ],
            if (profile.relayUrl != null) ...<Widget>[
              const dom.dt(<Widget>[Component.text('Relay')]),
              dom.dd(<Widget>[
                dom.code(<Widget>[Component.text(profile.relayUrl!)]),
              ]),
            ],
            const dom.dt(<Widget>[TipLabel(label: Component.text('Fingerprint'), tip: 'Matches what /animus editor status prints on the server.', docs: Docs.pairing)]),
            dom.dd(<Widget>[
              dom.code(<Widget>[Component.text(profile.fingerprint)]),
            ]),
            if (token != null) ...<Widget>[
              const dom.dt(<Widget>[Component.text('Pairing')]),
              dom.dd(<Widget>[
                Component.text(
                  'Ends ${token.expiresAt.toLocal().toString().substring(0, 16)} unless used, '
                  '${profile.remember ? 'remembered' : 'this tab only'}',
                ),
              ]),
              const dom.dt(<Widget>[TipLabel(label: Component.text('Allows'), tip: 'What this pairing may do. Pair again with scopes= to change it.', docs: Docs.tokens)]),
              dom.dd(<Widget>[
                rowList(
                  attributes: const <String, String>{'data-testid': 'server-scopes'},
                  <Widget>[
                    for (final String scope in token.scopes)
                      listItem(
                        Wrap(
                          spacing: 8,
                          crossAxisAlignment: WrapCrossAlignment.center,
                          children: <Widget>[
                            // The scope key is for the pair command's scopes=; it stays in the tip.
                            dom.span(attributes: <String, String>{'title': scope}, <Widget>[Text(scopeWord(scope))]),
                          ],
                        ),
                      ),
                    if (token.groups != null) listItem(Text('Only the folders ${token.groups!.join(', ')}')),
                  ],
                ),
              ]),
            ],
          ]),
          if (features.isNotEmpty)
            AppSection(
              title: 'Server features',
              tip: 'What this server offers the editor, and why a part is off.',
              docs: Docs.editorConfig,
              open: false,
              children: <Widget>[
                rowList(<Widget>[
                  for (final MapEntry<String, Object?> feature in features.entries)
                    if (feature.value case final Map<String, Object?> about)
                      listItem(
                        dom.span(<Widget>[
                          dom.code(<Widget>[Component.text(feature.key)]),
                          Component.text(about['available'] == true ? ' available' : ' off${about['reason'] is String ? ': ${about['reason']}' : ''}'),
                        ]),
                      ),
                ]),
              ],
            ),
          ActionRow(
            label: 'Server',
            moreId: 'server-more',
            more: <AppMenuEntry>[
              AppMenuEntry('Import on the server', () => navigate(CitizensRoute(workspaceId: ServerHub.workspaceIdOf(profile.id)).format())),
              const AppMenuEntry.separator(),
              AppMenuEntry('Forget server', () => _forget(profile), destructive: true),
            ],
            secondary: <RowAction>[
              if (status.phase == ConnectionPhase.reconnecting && owned) RowAction('Retry now', connection.retryNow),
              if (connectable && token != null && !session.waiting) RowAction('Connect', () => hub.connect(profile.id)),
              if (!connectable && status.phase != ConnectionPhase.needsPairing && owned) RowAction('Disconnect', () => hub.disconnect(profile.id)),
              if (status.online && owned)
                RowAction('Sync', session.busy == null ? session.loadCatalog : null, loading: session.busy == 'Reloading', attributes: const <String, String>{'data-testid': 'server-sync'}),
            ],
          ),
        ]),
      ],
    );
  }
}
