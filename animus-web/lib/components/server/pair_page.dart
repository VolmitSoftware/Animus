import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../services/server/pairing.dart';
import '../../services/server/server_connection.dart';
import '../../services/server/server_profiles.dart';
import '../../state/server_hub.dart';
import '../../state/workspace_route.dart';
import 'connection_pill.dart';

/// `#/pair/<code>`: shows the server the code names and exchanges its
/// one-time secret for a token.
class PairPage extends StatefulWidget {
  const PairPage({required this.code, required this.hub, required this.onPaired, super.key});

  final String code;
  final ServerHub hub;
  final void Function(String serverId) onPaired;

  @override
  State<PairPage> createState() => _PairPageState();
}

class _PairPageState extends State<PairPage> {
  PairingPayload? _payload;
  String? _problem;
  bool _remember = false;
  bool _busy = false;
  String _client = 'Animus Editor';

  @override
  void initState() {
    super.initState();
    try {
      _payload = decodePairingCode(component.code, component.hub.schemas);
    } on PairingCodeError catch (invalid) {
      _problem = invalid.message;
    }
  }

  Future<void> _pair() async {
    setState(() {
      _busy = true;
      _problem = null;
    });
    try {
      final String id = await component.hub.pair(component.code, remember: _remember, client: _client.trim());
      ArcaneSonner.success('Paired with ${component.hub.server(id)?.name ?? id}.');
      component.onPaired(id);
    } on PairingRefused catch (refused) {
      if (mounted) setState(() => _problem = refused.toString());
    } on PairingCodeError catch (invalid) {
      if (mounted) setState(() => _problem = invalid.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final PairingPayload? payload = _payload;
    return dom.main_(classes: 'animus-home', <Widget>[
      dom.section(classes: 'animus-panel animus-pair', attributes: const <String, String>{'aria-label': 'Pair with a server'}, <Widget>[
        dom.h1(<Widget>[Component.text(payload == null ? 'This pairing link is not valid' : 'Pair with ${payload.serverName ?? 'this server'}')]),
        if (payload != null) ...<Widget>[
          dom.dl(classes: 'animus-facts', <Widget>[
            const dom.dt(<Widget>[Component.text('Fingerprint')]),
            dom.dd(<Widget>[dom.code(<Widget>[Component.text(payload.fingerprint)])]),
            const dom.dt(<Widget>[Component.text('Paths')]),
            dom.dd(<Widget>[
              Component.text(<String>[
                if (payload.directUrl != null) 'direct ${payload.directUrl}',
                if (payload.relayUrl != null) 'relay ${payload.relayUrl}',
              ].join(', ')),
            ]),
            const dom.dt(<Widget>[Component.text('Code expires')]),
            dom.dd(<Widget>[Component.text(payload.expiresAt.toLocal().toString().substring(0, 16))]),
            if (payload.label != null) ...<Widget>[
              const dom.dt(<Widget>[Component.text('Label')]),
              dom.dd(<Widget>[Component.text(payload.label!)]),
            ],
          ]),
          const dom.p(classes: 'animus-muted', <Widget>[
            Component.text('The browser checks that the server answers with this fingerprint on every connection. '
                'The pairing secret works once and stays in this page address only.'),
          ]),
          TextInput(
            label: 'This browser',
            value: _client,
            size: ComponentSize.sm,
            onChanged: (String value) => setState(() => _client = value),
          ),
          ArcaneCheckbox(
            checked: _remember,
            label: 'Remember this browser',
            description: 'Keeps the token after the tab closes. Leave off on a shared computer.',
            size: ComponentSize.sm,
            onChanged: (bool value) => setState(() => _remember = value),
          ),
          dom.div(classes: 'animus-row', <Widget>[
            Button.primary(label: 'Pair', icon: ArcaneIcon.link(), loading: _busy, disabled: _busy, onPressed: _pair),
            const Button.ghost(label: 'Cancel', href: '#/'),
          ]),
        ],
        if (_problem != null) dom.p(classes: 'animus-error', attributes: const <String, String>{'role': 'alert'}, <Widget>[Component.text(_problem!)]),
      ]),
    ]);
  }
}

/// Paired servers on the home page, and a field to paste a pairing code.
class ServersPanel extends StatefulWidget {
  const ServersPanel({required this.hub, required this.onNavigate, super.key});

  final ServerHub hub;
  final void Function(EditorRoute route) onNavigate;

  @override
  State<ServersPanel> createState() => _ServersPanelState();
}

class _ServersPanelState extends State<ServersPanel> {
  String _code = '';

  void _paste() {
    final String code = extractPairingCode(_code);
    if (!code.startsWith(pairingPrefix)) {
      ArcaneSonner.warning('Paste the link or ANM1. code from /animus editor pair.');
      return;
    }
    component.onNavigate(PairRoute(code));
  }

  @override
  Widget build(BuildContext context) {
    final ServerHub hub = component.hub;
    return dom.section(classes: 'animus-panel', attributes: const <String, String>{'aria-label': 'Servers'}, <Widget>[
      const dom.h2(<Widget>[Component.text('Servers')]),
      if (hub.servers.isEmpty)
        const dom.p(classes: 'animus-muted', <Widget>[
          Component.text('Run /animus editor pair on a server and open the link, or paste it here, to edit that server live.'),
        ])
      else
        dom.ul(classes: 'animus-workspace-list animus-server-list', <Widget>[
          for (final ServerProfile server in hub.servers)
            dom.li(<Widget>[
              dom.a(href: ServerRoute(server.id, const <String>[]).format(), <Widget>[Component.text(server.name)]),
              ConnectionPill(status: hub.status(server.id), hasToken: hub.hasToken(server.id)),
              Button.ghost(label: 'Forget', size: ButtonSize.small, onPressed: () => hub.forget(server.id)),
            ]),
        ]),
      dom.div(classes: 'animus-row animus-pair-paste', <Widget>[
        TextInput(
          placeholder: 'Pairing link or ANM1. code',
          value: _code,
          size: ComponentSize.sm,
          attributes: const <String, String>{'aria-label': 'Pairing code'},
          onChanged: (String value) => setState(() => _code = value),
          onSubmitted: (String _) => _paste(),
        ),
        Button.secondary(label: 'Pair', size: ButtonSize.small, onPressed: _paste),
      ]),
    ]);
  }
}

/// Text for a phase, used by the pill and the server overview.
String phaseHint(ConnectionStatus status, {required bool hasToken}) {
  if (!hasToken && status.phase != ConnectionPhase.online) return 'Pair again with /animus editor pair.';
  return switch (status.phase) {
    ConnectionPhase.reconnecting when status.retryAt != null => 'Next try at ${status.retryAt!.toLocal().toString().substring(11, 19)}',
    _ => status.message ?? '',
  };
}
