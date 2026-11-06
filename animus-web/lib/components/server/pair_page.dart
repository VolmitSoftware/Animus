import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../services/browser.dart';
import '../../services/server/error_copy.dart';
import '../../services/server/pairing.dart';
import '../../services/server/server_connection.dart';
import '../../services/server/server_profiles.dart';
import '../../state/server_hub.dart';
import '../../state/workspace_route.dart';
import 'connection_pill.dart';
import 'live_confirm.dart';
import '../brand_mark.dart';

/// `#/pair/<code>`: shows the server the code names and exchanges its
/// one-time secret for a token. One column: the mark, the server's name, its
/// fingerprint as the thing to check, the paths, the remember switch, Pair.
class PairPage extends StatefulWidget {
  const PairPage({required this.code, required this.hub, required this.onPaired, super.key, this.now});

  final String code;
  final ServerHub hub;
  final void Function(String serverId) onPaired;

  /// The clock the expiry is checked against.
  final DateTime Function()? now;

  @override
  State<PairPage> createState() => _PairPageState();
}

/// Why a pasted code cannot be used, with the next step.
const String newCodeStep = 'Run /animus editor pair on the server again and open the new link, or paste it below.';

class _PairPageState extends State<PairPage> {
  PairingPayload? _payload;
  String? _invalid;
  String? _problem;
  bool _remember = false;
  bool _busy = false;
  String _client = 'Animus Editor';

  DateTime get _now => (component.now ?? DateTime.now)().toUtc();

  @override
  void initState() {
    super.initState();
    try {
      _payload = decodePairingCode(component.code, component.hub.schemas);
    } on PairingCodeError catch (invalid) {
      _invalid = invalid.message;
    }
  }

  Future<void> _pair() async {
    setState(() {
      _busy = true;
      _problem = null;
    });
    try {
      final String id = await component.hub.pair(component.code, remember: _remember, client: _client.trim());
      final String name = component.hub.server(id)?.name ?? id;
      if (component.hub.storageRefused) {
        ArcaneSonner.warning('Paired with $name, but this browser refused to store it, so the pairing lasts until this tab closes.');
      } else {
        ArcaneSonner.success('Paired with $name.');
      }
      component.onPaired(id);
    } on PairingRefused catch (refused) {
      if (mounted) setState(() => _problem = refused.message);
    } on PairingCodeError catch (invalid) {
      if (mounted) setState(() => _invalid = invalid.message);
    } on Object {
      if (mounted) setState(() => _problem = transportCopy('dropped').text);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final PairingPayload? payload = _payload;
    final bool expired = payload != null && !payload.expiresAt.isAfter(_now);
    return dom.main_(classes: 'animus-home', <Widget>[
      dom.section(classes: 'animus-panel animus-pair', attributes: const <String, String>{'aria-labelledby': 'animus-pair-title'}, <Widget>[
        const BrandMark(size: 48, classes: 'animus-server-mark'),
        if (payload == null || _invalid != null) ...<Widget>[
          const dom.h1(id: 'animus-pair-title', <Widget>[Component.text('This pairing link cannot be used')]),
          dom.p(classes: 'animus-error', attributes: const <String, String>{'role': 'alert'}, <Widget>[
            Component.text('${_invalid ?? 'The link holds no pairing code.'} $newCodeStep'),
          ]),
          PairCodeField(onCode: (String code) => _goTo(code)),
        ] else ...<Widget>[
          dom.p(classes: 'animus-pair-kicker', <Widget>[Component.text(expired ? 'Pairing code expired' : 'Pair this browser with')]),
          dom.h1(id: 'animus-pair-title', <Widget>[Component.text(payload.serverName ?? payload.label ?? 'Server ${payload.serverId.substring(0, 8)}')]),
          dom.div(classes: 'animus-pair-fingerprint', <Widget>[
            const dom.span(classes: 'animus-muted', <Widget>[Component.text('Fingerprint. Check it matches /animus editor status on the server.')]),
            dom.code(attributes: const <String, String>{'data-testid': 'pair-fingerprint'}, <Widget>[Component.text(payload.fingerprint)]),
          ]),
          dom.ul(classes: 'animus-pair-paths', attributes: const <String, String>{'aria-label': 'Ways to reach the server'}, <Widget>[
            if (payload.directUrl != null) dom.li(<Widget>[const dom.span(<Widget>[Component.text('Direct ')]), dom.code(<Widget>[Component.text(payload.directUrl!)])]),
            if (payload.relayUrl != null) dom.li(<Widget>[const dom.span(<Widget>[Component.text('Relay ')]), dom.code(<Widget>[Component.text(payload.relayUrl!)])]),
            dom.li(<Widget>[
              Component.text('${expired ? 'Expired' : 'Code expires'} ${payload.expiresAt.toLocal().toString().substring(0, 16)}'),
            ]),
          ]),
          if (expired) ...<Widget>[
            dom.p(classes: 'animus-error', attributes: const <String, String>{'role': 'alert', 'data-testid': 'pair-expired'}, <Widget>[
              Component.text('${serverCopy('pairing-expired').cause} $newCodeStep'),
            ]),
            PairCodeField(onCode: (String code) => _goTo(code)),
          ] else ...<Widget>[
            TextInput(
              label: 'Name for this browser on the server',
              value: _client,
              size: ComponentSize.sm,
              onChanged: (String value) => setState(() => _client = value),
            ),
            dom.div(classes: 'animus-pair-remember', <Widget>[
              ArcaneToggleSwitch(
                value: _remember,
                label: 'Remember this browser',
                size: ComponentSize.sm,
                onChanged: (bool value) => setState(() => _remember = value),
              ),
              const dom.span(classes: 'animus-muted', <Widget>[
                Component.text('On: the token stays after this tab closes. Off: it lasts for this tab only. Leave it off on a shared computer.'),
              ]),
            ]),
            const dom.p(classes: 'animus-muted', <Widget>[
              Component.text('Every later connection checks that the server answers with this fingerprint. The pairing secret works once.'),
            ]),
            dom.div(classes: 'animus-row', <Widget>[
              Button.primary(label: _busy ? 'Pairing' : 'Pair', loading: _busy, disabled: _busy, onPressed: _pair),
              const dom.a(href: '#/', classes: 'animus-pair-cancel', <Widget>[Component.text('Cancel')]),
            ]),
          ],
        ],
        if (_problem != null)
          dom.p(classes: 'animus-error', attributes: const <String, String>{'role': 'alert', 'data-testid': 'pair-problem'}, <Widget>[Component.text(_problem!)]),
      ]),
    ]);
  }

  void _goTo(String code) {
    setState(() {
      _problem = null;
      _invalid = null;
      try {
        _payload = decodePairingCode(code, component.hub.schemas);
      } on PairingCodeError catch (invalid) {
        _payload = null;
        _invalid = invalid.message;
      }
    });
    if (_payload != null) {
      // The address keeps the code, so a reload shows the same server.
      navigate(PairRoute(code).format());
    }
  }
}

/// A field to paste a pairing link or code into, with a Pair button.
class PairCodeField extends StatefulWidget {
  const PairCodeField({required this.onCode, super.key});

  final void Function(String code) onCode;

  @override
  State<PairCodeField> createState() => _PairCodeFieldState();
}

class _PairCodeFieldState extends State<PairCodeField> {
  String _code = '';

  void _submit() {
    final String code = extractPairingCode(_code);
    if (!code.startsWith(pairingPrefix)) {
      ArcaneSonner.warning('Paste the link or the ANM1. code that /animus editor pair prints on the server.');
      return;
    }
    component.onCode(code);
  }

  @override
  Widget build(BuildContext context) => dom.div(classes: 'animus-row animus-pair-paste', <Widget>[
        TextInput(
          placeholder: 'Pairing link or ANM1. code',
          value: _code,
          size: ComponentSize.sm,
          attributes: const <String, String>{'aria-label': 'Pairing link or code'},
          onChanged: (String value) => setState(() => _code = value),
          onSubmitted: (String _) => _submit(),
        ),
        Button.secondary(label: 'Use this code', size: ButtonSize.small, onPressed: _submit),
      ]);
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
  /// The server whose Forget waits for a confirm.
  String? _forgetting;

  static String _forgetId(String id) => 'animus-forget-$id';

  Future<void> _forget(ServerProfile server) async {
    setState(() => _forgetting = null);
    await component.hub.forget(server.id);
    ArcaneSonner.success('Forgot ${server.name}: its token and live workspace left this browser. Pair again to edit it.');
    focusSoon('animus-servers-title');
  }

  @override
  Widget build(BuildContext context) {
    final ServerHub hub = component.hub;
    return dom.section(classes: 'animus-panel', attributes: const <String, String>{'aria-labelledby': 'animus-servers-title'}, <Widget>[
      const dom.h2(id: 'animus-servers-title', attributes: <String, String>{'tabindex': '-1'}, <Widget>[Component.text('Servers')]),
      if (hub.servers.isEmpty)
        const dom.p(classes: 'animus-muted', <Widget>[
          Component.text('No server is paired yet. Run /animus editor pair on a server and open the link it prints, or paste it here, to edit that server live.'),
        ])
      else
        dom.ul(classes: 'animus-workspace-list animus-server-list', <Widget>[
          for (final ServerProfile server in hub.servers)
            dom.li(<Widget>[
              if (_forgetting == server.id)
                LiveConfirm(
                  question: 'Forget ${server.name}?',
                  detail: 'Its token and its live workspace, with any drafts not applied, leave this browser. The server keeps everything already applied.',
                  confirmLabel: 'Forget server',
                  destructive: true,
                  returnFocus: _forgetId(server.id),
                  onConfirm: () => _forget(server),
                  onCancel: () => setState(() => _forgetting = null),
                )
              else ...<Widget>[
                dom.a(href: ServerRoute(server.id, const <String>[]).format(), <Widget>[Component.text(server.name)]),
                ConnectionPill(status: hub.status(server.id), hasToken: hub.hasToken(server.id)),
                Button.ghost(
                  label: 'Forget',
                  size: ButtonSize.small,
                  attributes: <String, String>{'id': _forgetId(server.id), 'aria-label': 'Forget ${server.name}'},
                  onPressed: () => setState(() => _forgetting = server.id),
                ),
              ],
            ]),
        ]),
      PairCodeField(onCode: (String code) => component.onNavigate(PairRoute(code))),
    ]);
  }
}
