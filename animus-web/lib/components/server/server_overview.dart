import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../model/document_kind.dart';
import '../../services/server/error_copy.dart';
import '../../services/server/server_connection.dart';
import '../../services/server/server_profiles.dart';
import '../../state/catalog_entry.dart';
import '../../state/live_session.dart';
import '../../state/server_hub.dart';
import '../fold.dart';
import '../info_tip.dart';
import 'connection_pill.dart';

/// What a token scope lets this browser do, in words.
String scopeLabel(String scope) => switch (scope) {
      'read' => 'Read documents',
      'validate' => 'Validate',
      'preview' => 'Preview',
      'apply:npcs' => 'Apply NPCs',
      'apply:behaviors' => 'Apply behaviors',
      'apply:gui' => 'Apply GUIs',
      'apply:rigs' => 'Apply rigs',
      'import' => 'Import saves',
      'spawn' => 'Spawn NPCs',
      'apply:commands' => 'Apply commands',
      _ => scope,
    };

/// A server workspace's overview: the connection, what the server offers,
/// and its catalog to open documents from, grouped by kind.
class ServerOverview extends StatefulWidget {
  const ServerOverview({required this.hub, required this.session, required this.onOpened, super.key});

  final ServerHub hub;
  final LiveSession session;

  /// Called with the first opened document, to show it.
  final void Function(DocumentKind kind, String id) onOpened;

  @override
  State<ServerOverview> createState() => _ServerOverviewState();
}

class _ServerOverviewState extends State<ServerOverview> {
  final Set<String> _selected = <String>{};
  String _filter = '';

  Future<void> _open(List<CatalogEntry> entries) async {
    if (entries.isEmpty) return;
    final List<CatalogEntry> closed = <CatalogEntry>[for (final CatalogEntry entry in entries) if (component.session.store.document(entry.kind, entry.id) == null) entry];
    if (closed.isNotEmpty) await component.session.openDocuments(closed);
    _selected.clear();
    if (mounted) setState(() {});
    final CatalogEntry first = entries.first;
    if (component.session.store.document(first.kind, first.id) != null) component.onOpened(first.kind, first.id);
  }

  @override
  Widget build(BuildContext context) {
    final LiveSession session = component.session;
    final ServerConnection connection = session.connection;
    final ConnectionStatus status = connection.status;
    final ServerProfile profile = connection.profile;
    final ServerToken? token = connection.token;
    final Map<String, Object?> features = (connection.capabilities?['features'] as Map<String, Object?>?) ?? const <String, Object?>{};
    final String hint = phaseHint(status, hasToken: token != null);
    return dom.div(classes: 'animus-overview', <Widget>[
      dom.section(classes: 'animus-panel', attributes: const <String, String>{'aria-label': 'Connection'}, <Widget>[
        dom.h2(<Widget>[Component.text(profile.name)]),
        dom.dl(classes: 'animus-facts', <Widget>[
          const dom.dt(<Widget>[Component.text('Status')]),
          dom.dd(attributes: const <String, String>{'data-testid': 'server-status'}, <Widget>[
            ConnectionPill(status: status, hasToken: token != null),
            if (hint.isNotEmpty) dom.span(classes: 'animus-muted animus-status-hint', <Widget>[Component.text(hint)]),
          ]),
          const dom.dt(<Widget>[
            InfoTip(tip: 'The server key this browser trusts. It matches what /animus editor status prints.', docs: Docs.pairing, child: Component.text('Fingerprint')),
          ]),
          dom.dd(<Widget>[dom.code(<Widget>[Component.text(profile.fingerprint)])]),
          const dom.dt(<Widget>[
            InfoTip(
              tip: 'Direct talks to the server itself; relay goes through the Animus relay when the server is not reachable.',
              docs: Docs.connecting,
              child: Component.text('Paths'),
            ),
          ]),
          dom.dd(<Widget>[
            dom.ul(classes: 'animus-pair-paths', <Widget>[
              if (profile.directUrl != null) dom.li(<Widget>[const Component.text('Direct '), dom.code(<Widget>[Component.text(profile.directUrl!)])]),
              if (profile.relayUrl != null) dom.li(<Widget>[const Component.text('Relay '), dom.code(<Widget>[Component.text(profile.relayUrl!)])]),
            ]),
          ]),
          if (_serverLine(connection.hello) case final String line) ...<Widget>[
            const dom.dt(<Widget>[Component.text('Server')]),
            dom.dd(<Widget>[Component.text(line)]),
          ],
          if (token != null) ...<Widget>[
            const dom.dt(<Widget>[
              InfoTip(
                tip: 'What this pairing may do on the server. Pair again with scopes= for more.',
                docs: Docs.tokens,
                child: Component.text('Scopes'),
              ),
            ]),
            dom.dd(classes: 'animus-scopes', <Widget>[
              for (final String scope in token.scopes)
                dom.span(classes: 'animus-tag', attributes: <String, String>{'title': scope}, <Widget>[Component.text(scopeLabel(scope))]),
            ]),
            const dom.dt(<Widget>[Component.text('Pairing')]),
            dom.dd(<Widget>[
              Component.text('Expires ${token.expiresAt.toLocal().toString().substring(0, 16)}, '
                  '${profile.remember ? 'remembered' : 'this tab only'}'),
            ]),
          ],
        ]),
        dom.div(classes: 'animus-row', <Widget>[
          if (status.phase == ConnectionPhase.offline || status.phase == ConnectionPhase.refused)
            Button.secondary(label: 'Connect', size: ButtonSize.small, disabled: token == null, onPressed: () => component.hub.connect(profile.id)),
          if (status.phase != ConnectionPhase.offline && status.phase != ConnectionPhase.needsPairing && status.phase != ConnectionPhase.refused)
            Button.ghost(label: 'Disconnect', size: ButtonSize.small, onPressed: () => component.hub.disconnect(profile.id)),
          if (status.phase == ConnectionPhase.reconnecting) Button.ghost(label: 'Retry now', size: ButtonSize.small, onPressed: connection.retryNow),
          if (status.online && session.owned) Button.ghost(label: 'Reload catalog', size: ButtonSize.small, onPressed: session.loadCatalog),
        ]),
        if (features.isNotEmpty)
          Fold(summary: const <Widget>[
            InfoTip(
              tip: 'What this server offers the editor, such as previews, imports and spawning, and why any part is off.',
              docs: Docs.editorConfig,
              child: Component.text('Server features'),
            ),
          ], children: <Widget>[
            dom.ul(classes: 'animus-muted', <Widget>[
              for (final MapEntry<String, Object?> feature in features.entries)
                if (feature.value is Map<String, Object?>)
                  dom.li(<Widget>[
                    dom.code(<Widget>[Component.text(feature.key)]),
                    Component.text((feature.value! as Map<String, Object?>)['available'] == true
                        ? ' available'
                        : ' unavailable${(feature.value! as Map<String, Object?>)['reason'] is String ? ': ${(feature.value! as Map<String, Object?>)['reason']}' : ''}'),
                  ]),
            ]),
          ]),
      ]),
      _catalog(session, status),
    ]);
  }

  static String? _serverLine(Map<String, Object?>? hello) {
    if (hello == null) return null;
    final List<String> parts = <String>[
      if (hello['animus'] is String) 'Animus ${hello['animus']}',
      if (hello['platform'] is String) 'on ${hello['platform']}',
      if (hello['minecraft'] is String) '${hello['minecraft']}',
    ];
    return parts.isEmpty ? null : parts.join(' ');
  }

  Widget _catalog(LiveSession session, ConnectionStatus status) {
    final String filter = _filter.trim().toLowerCase();
    final List<CatalogEntry> shown = <CatalogEntry>[
      for (final CatalogEntry entry in session.catalog)
        if (filter.isEmpty || entry.id.toLowerCase().contains(filter) || entry.kind.wireName.contains(filter) || entry.kind.label.toLowerCase().contains(filter)) entry,
    ];
    final ErrorCopy? problem = session.catalogProblem;
    return dom.section(classes: 'animus-panel', attributes: const <String, String>{'aria-label': 'Server catalog'}, <Widget>[
      dom.h2(<Widget>[
        withTip(
          Component.text(session.catalogLoaded ? '${session.catalog.length} document${session.catalog.length == 1 ? '' : 's'} on the server' : 'Server catalog'),
          'Every document on the server. Opening one copies it into this workspace as a draft; nothing changes on the server until you apply.',
          docs: Docs.live,
        ),
      ]),
      if (session.waiting)
        const dom.p(classes: 'animus-muted', <Widget>[Component.text(LiveSession.otherTab)])
      else if (problem != null)
        dom.div(classes: 'animus-row', attributes: const <String, String>{'role': 'alert', 'data-testid': 'catalog-problem'}, <Widget>[
          dom.span(classes: 'animus-error', <Widget>[Component.text('Catalog failed. ${problem.text}')]),
          if (status.online) Button.secondary(label: 'Try again', size: ButtonSize.small, onPressed: session.loadCatalog),
        ])
      else if (!session.catalogLoaded)
        dom.p(classes: 'animus-muted', attributes: const <String, String>{'role': 'status'}, <Widget>[
          Component.text(status.online ? 'Loading catalog...' : 'Connect to list documents.'),
        ])
      else if (session.catalog.isEmpty)
        const dom.p(classes: 'animus-muted', attributes: <String, String>{'data-testid': 'catalog-empty'}, <Widget>[
          Component.text('No documents yet. Send some from a workspace\'s Sync page.'),
        ])
      else ...<Widget>[
        dom.div(classes: 'animus-row', <Widget>[
          TextInput(
            placeholder: 'Filter by id or kind',
            value: _filter,
            size: ComponentSize.sm,
            attributes: const <String, String>{'aria-label': 'Filter the catalog'},
            onChanged: (String value) => setState(() => _filter = value),
          ),
          Button.primary(
            label: _selected.isEmpty ? 'Open selected' : 'Open ${_selected.length}',
            size: ButtonSize.small,
            disabled: _selected.isEmpty || !status.online,
            onPressed: () => _open(<CatalogEntry>[for (final CatalogEntry entry in session.catalog) if (_selected.contains(entry.key)) entry]),
          ),
          Button.secondary(
            label: 'Open all shown',
            size: ButtonSize.small,
            disabled: shown.isEmpty || !status.online,
            onPressed: () => _open(shown),
          ),
        ]),
        if (shown.isEmpty)
          dom.div(classes: 'animus-row', attributes: const <String, String>{'data-testid': 'catalog-no-match'}, <Widget>[
            dom.span(classes: 'animus-muted', <Widget>[Component.text('No document matches "${_filter.trim()}".')]),
            Button.ghost(label: 'Clear the filter', size: ButtonSize.small, onPressed: () => setState(() => _filter = '')),
          ])
        else
          dom.div(classes: 'animus-catalog', <Widget>[
            for (final DocumentKind kind in DocumentKind.values)
              if (shown.any((CatalogEntry entry) => entry.kind == kind))
                dom.section(classes: 'animus-catalog-group', attributes: <String, String>{'aria-label': kind.label}, <Widget>[
                  dom.h3(<Widget>[Component.text('${kind.label} (${shown.where((CatalogEntry entry) => entry.kind == kind).length})')]),
                  dom.ul(<Widget>[
                    for (final CatalogEntry entry in shown)
                      if (entry.kind == kind) _row(session, entry),
                  ]),
                ]),
          ]),
      ],
    ]);
  }

  Widget _row(LiveSession session, CatalogEntry entry) {
    final bool open = session.store.document(entry.kind, entry.id) != null;
    return dom.li(<Widget>[
      ArcaneCheckbox(
        checked: _selected.contains(entry.key),
        label: entry.id,
        size: ComponentSize.sm,
        onChanged: (bool on) => setState(() => on ? _selected.add(entry.key) : _selected.remove(entry.key)),
      ),
      dom.span(classes: 'animus-catalog-meta', <Widget>[
        dom.span(classes: 'animus-muted', <Widget>[Component.text('revision ${entry.revision}')]),
        if (open)
          Button.ghost(
            label: 'Show',
            size: ButtonSize.small,
            attributes: <String, String>{'data-open': 'true', 'aria-label': 'Show ${entry.kind.label.toLowerCase()} ${entry.id}, already open'},
            onPressed: () => component.onOpened(entry.kind, entry.id),
          ),
      ]),
    ]);
  }
}
