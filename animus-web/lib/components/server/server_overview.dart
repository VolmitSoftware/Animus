import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../model/document_kind.dart';
import '../../services/server/server_connection.dart';
import '../../services/server/server_profiles.dart';
import '../../state/live_session.dart';
import '../../state/server_hub.dart';
import 'pair_page.dart';

/// A server workspace's overview: the connection, what the server offers,
/// and its catalog to open documents from.
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
    await component.session.openDocuments(entries);
    _selected.clear();
    if (mounted) setState(() {});
    final CatalogEntry first = entries.first;
    if (component.session.store.document(first.kind, first.id) != null) component.onOpened(first.kind, first.id);
  }

  @override
  Widget build(BuildContext context) {
    final LiveSession session = component.session;
    final ServerHub hub = component.hub;
    final ServerConnection connection = session.connection;
    final ConnectionStatus status = connection.status;
    final ServerProfile profile = connection.profile;
    final Map<String, Object?>? hello = connection.hello;
    final String filter = _filter.trim().toLowerCase();
    final List<CatalogEntry> shown = <CatalogEntry>[
      for (final CatalogEntry entry in session.catalog)
        if (filter.isEmpty || entry.id.contains(filter) || entry.kind.wireName.contains(filter)) entry,
    ];
    final Map<String, Object?> features = (connection.capabilities?['features'] as Map<String, Object?>?) ?? const <String, Object?>{};
    final ServerToken? token = connection.token;
    return dom.div(classes: 'animus-overview', <Widget>[
      dom.section(classes: 'animus-panel', attributes: const <String, String>{'aria-label': 'Connection'}, <Widget>[
        dom.h2(<Widget>[Component.text(profile.name)]),
        dom.dl(classes: 'animus-facts', <Widget>[
          const dom.dt(<Widget>[Component.text('Status')]),
          dom.dd(attributes: const <String, String>{'data-testid': 'server-status'}, <Widget>[
            Component.text(status.label),
            if (phaseHint(status, hasToken: token != null).isNotEmpty)
              dom.span(classes: 'animus-muted', <Widget>[Component.text(' - ${phaseHint(status, hasToken: token != null)}')]),
          ]),
          const dom.dt(<Widget>[Component.text('Fingerprint')]),
          dom.dd(<Widget>[dom.code(<Widget>[Component.text(profile.fingerprint)])]),
          const dom.dt(<Widget>[Component.text('Paths')]),
          dom.dd(<Widget>[
            Component.text(<String>[
              if (profile.directUrl != null) 'direct ${profile.directUrl}',
              if (profile.relayUrl != null) 'relay ${profile.relayUrl}',
            ].join(', ')),
          ]),
          if (hello != null) ...<Widget>[
            const dom.dt(<Widget>[Component.text('Server')]),
            dom.dd(<Widget>[Component.text('Animus ${hello['animus']} on ${hello['platform']} ${hello['minecraft']}')]),
          ],
          if (token != null) ...<Widget>[
            const dom.dt(<Widget>[Component.text('Token')]),
            dom.dd(<Widget>[
              Component.text('${token.tokenId}, ${token.scopes.join(' ')}, expires ${token.expiresAt.toLocal().toString().substring(0, 16)}'
                  '${profile.remember ? ', remembered' : ', this tab only'}'),
            ]),
          ],
        ]),
        dom.div(classes: 'animus-row', <Widget>[
          if (status.phase == ConnectionPhase.offline || status.phase == ConnectionPhase.refused)
            Button.secondary(label: 'Connect', size: ButtonSize.small, disabled: token == null, onPressed: () => hub.connect(profile.id)),
          if (status.phase != ConnectionPhase.offline && status.phase != ConnectionPhase.needsPairing && status.phase != ConnectionPhase.refused)
            Button.ghost(label: 'Disconnect', size: ButtonSize.small, onPressed: () => hub.disconnect(profile.id)),
          if (status.phase == ConnectionPhase.reconnecting) Button.ghost(label: 'Retry now', size: ButtonSize.small, onPressed: connection.retryNow),
          if (status.online) Button.ghost(label: 'Reload catalog', size: ButtonSize.small, onPressed: session.loadCatalog),
        ]),
        if (features.isNotEmpty)
          dom.details(<Widget>[
            const dom.summary(<Widget>[Component.text('Server features')]),
            dom.ul(classes: 'animus-muted', <Widget>[
              for (final MapEntry<String, Object?> feature in features.entries)
                if (feature.value is Map<String, Object?>)
                  dom.li(<Widget>[
                    dom.code(<Widget>[Component.text(feature.key)]),
                    Component.text((feature.value! as Map<String, Object?>)['available'] == true
                        ? ' available'
                        : ' unavailable: ${(feature.value! as Map<String, Object?>)['reason']}'),
                  ]),
            ]),
          ]),
      ]),
      dom.section(classes: 'animus-panel', attributes: const <String, String>{'aria-label': 'Server catalog'}, <Widget>[
        dom.h2(<Widget>[Component.text(session.catalogLoaded ? '${session.catalog.length} documents on the server' : 'Server catalog')]),
        if (!session.catalogLoaded)
          dom.p(classes: 'animus-muted', <Widget>[
            Component.text(status.online ? 'Loading...' : 'Connect to list the server\'s documents. Drafts here stay in this browser.'),
          ])
        else ...<Widget>[
          dom.div(classes: 'animus-row', <Widget>[
            TextInput(
              placeholder: 'Filter',
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
          dom.ul(classes: 'animus-catalog', <Widget>[
            for (final CatalogEntry entry in shown)
              dom.li(<Widget>[
                if (session.store.document(entry.kind, entry.id) != null)
                  Button.ghost(
                    label: '${entry.kind.wireName} ${entry.id}',
                    size: ButtonSize.small,
                    attributes: const <String, String>{'data-open': 'true'},
                    onPressed: () => component.onOpened(entry.kind, entry.id),
                  )
                else
                  ArcaneCheckbox(
                    checked: _selected.contains(entry.key),
                    label: '${entry.kind.wireName} ${entry.id}',
                    size: ComponentSize.sm,
                    onChanged: (bool on) => setState(() => on ? _selected.add(entry.key) : _selected.remove(entry.key)),
                  ),
                dom.span(classes: 'animus-muted', <Widget>[Component.text('r${entry.revision}')]),
              ]),
          ]),
        ],
      ]),
    ]);
  }
}
