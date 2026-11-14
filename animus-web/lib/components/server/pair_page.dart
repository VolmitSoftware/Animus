/// `#/pair/<code>`: one card that names the server the link is for, what the
/// editor may do there, this browser's name and Pair. The code moves into
/// memory and the address becomes `#/pair`, so the secret stays out of
/// history. A link that cannot work says why in one sentence, gives the
/// next step and, when a new link is needed, the field to paste it.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:arcane_jaspr/component/view/check_list.dart' show ArcaneCheckList;
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../config/docs_links.dart';
import '../../services/browser.dart';
import '../../services/server/error_copy.dart';
import '../../services/server/pairing.dart';
import '../../services/server/server_pairing.dart';
import '../../state/server_hub.dart';
import '../../state/workspace_route.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/field.dart';
import '../../ui/info_tip.dart';
import '../../ui/mode.dart';
import '../../ui/section.dart';
import '../offline/page_parts.dart';
import 'pair_copy.dart';
import 'pair_link_form.dart';
import '../../services/server/scope_words.dart';
import '../../ui/stack.dart';
import '../../model/display_name.dart' show closing;

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

class _PairPageState extends State<PairPage> {
  static const String _switchId = 'pair-remember';
  static const String _nameId = 'pair-browser-name';

  String _code = '';
  PairingPayload? _payload;
  PairProblem? _problem;

  /// On by default: the pairing is revocable on the server and ends after
  /// tokenDays without use; a tab-only token makes every new tab ask again.
  bool _remember = true;
  bool _busy = false;
  late String _client = browserName(web.window.navigator.userAgent);

  DateTime get _now => (component.now ?? DateTime.now)().toUtc();

  @override
  void initState() {
    super.initState();
    _read(component.code);
    if (component.code.isNotEmpty) replaceHistoryHash(const PairRoute().format());
  }

  /// Reads [code]; a code that cannot work is a problem before anything is
  /// sent.
  void _read(String code) {
    _code = code;
    _payload = null;
    _problem = null;
    if (code.isEmpty) {
      _problem = noLink;
      return;
    }
    try {
      final PairingPayload payload = decodePairingCode(code, component.hub.schemas);
      _payload = payload;
      if (!payload.expiresAt.isAfter(_now)) _problem = expiredLink;
    } on PairingCodeError {
      _problem = notALink;
    }
  }

  Future<void> _pair() async {
    final PairingPayload? payload = _payload;
    if (payload == null || _busy) return;
    setState(() {
      _busy = true;
      _problem = null;
    });
    try {
      final String id = await component.hub.pair(_code, remember: _remember, client: _client.trim().isEmpty ? null : _client.trim());
      final String name = component.hub.server(id)?.name ?? id;
      if (component.hub.storageRefused) {
        ArcaneSonner.warning('Paired with $name for this tab only. The browser refused storage.');
      } else {
        ArcaneSonner.success('Paired with ${closing(name)}');
      }
      component.onPaired(id);
    } on PairingRefused catch (refused) {
      if (mounted) setState(() => _problem = pairProblemFor(refused, pageOrigin: web.window.location.origin, directUrl: payload.directUrl));
    } on PairingCodeError {
      if (mounted) setState(() => _problem = notALink);
    } on Object {
      if (mounted) setState(() => _problem = pairProblemOf('dropped', pageOrigin: web.window.location.origin, directUrl: payload.directUrl, copy: transportCopy('dropped')));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _newLink(String code) => setState(() => _read(code));

  static String _time(DateTime at) => at.toLocal().toString().substring(11, 16);

  @override
  Widget build(BuildContext context) {
    final PairingPayload? payload = _payload;
    final PairProblem? problem = _problem;
    final bool spent = problem != null && problem.newLink;
    final String title = payload?.serverName ?? payload?.label ?? (payload == null ? 'Pair a server' : 'Minecraft server');
    return dom.main_(
      classes: 'ui-start',
      attributes: const <String, String>{'aria-labelledby': mainHeadingId},
      <Widget>[
        // What is wrong with the link sits above the card, never boxed inside it.
        if (problem != null)
          dom.div(
            key: ValueKey<String>('pair-problem:${problem.title}'),
            attributes: const <String, String>{'data-testid': 'pair-problem'},
            <Widget>[AppAlert(tone: AlertTone.error, title: problem.title, message: problem.next, live: true)],
          ),
        Card(
          children: <Widget>[
            stack(spacing: 12, <Widget>[
              stack(spacing: 4, <Widget>[
                dom.h1(id: mainHeadingId, classes: 'ui-title', attributes: <String, String>{'tabindex': '-1', 'title': title}, <Widget>[Component.text(title)]),
                if (payload != null)
                  dom.div(attributes: const <String, String>{'data-testid': 'pair-expiry'}, <Widget>[
                    mutedText(payload.expiresAt.isAfter(_now) ? 'Link works until ${_time(payload.expiresAt)}, once.' : 'Link expired at ${_time(payload.expiresAt)}.'),
                  ]),
              ]),
              if (spent || payload == null)
                PairLinkForm(key: ValueKey<String>('new-link:$_code'), id: 'pair-new-link', label: 'New link', onCode: _newLink)
              else ...<Widget>[
                _abilities(),
                Field(
                  label: 'Browser name',
                  forId: _nameId,
                  tip: 'How the server lists this browser in /animus editor tokens.',
                  docs: Docs.tokens,
                  child: TextInput(id: _nameId, value: _client, fullWidth: true, onChanged: (String value) => setState(() => _client = value)),
                ),
                Field(
                  label: 'Stay paired',
                  forId: _switchId,
                  tip: 'Off on a shared computer: the pairing then ends with this tab.',
                  docs: Docs.pairing,
                  child: ArcaneToggleSwitch(id: _switchId, value: _remember, ariaLabel: 'Stay paired', onChanged: (bool value) => setState(() => _remember = value)),
                ),
                AdvancedOnly(child: _details(payload)),
                FormFooter(
                  cancel: RowAction('Cancel', () => navigate(const HomeRoute().format())),
                  primary: RowAction(_busy ? 'Pairing' : 'Pair', _busy ? null : _pair, loading: _busy, attributes: const <String, String>{'data-testid': 'pair-submit'}),
                ),
              ],
            ]),
          ],
        ),
      ],
    );
  }

  /// What the editor may do, in words.
  Widget _abilities() => stack(spacing: 8, <Widget>[
    sectionHeading('The editor can', tip: 'What a link made by an operator allows. A link can be made to allow less.', docs: Docs.tokens),
    // Plain checks in the text colour: pink stays for the Pair button.
    const ArcaneCheckList(items: fullPairing, listGap: '0.375rem', iconColor: 'var(--foreground)'),
  ]);

  /// The fingerprint, the ways in and the link itself, folded.
  Widget _details(PairingPayload payload) => AppSection(
    title: 'Details',
    open: false,
    children: <Widget>[
      dom.dl(classes: 'ui-facts', <Widget>[
        const dom.dt(<Widget>[TipLabel(label: Component.text('Fingerprint'), tip: 'Must match what /animus editor status prints on the server.', docs: Docs.pairing)]),
        dom.dd(<Widget>[
          dom.code(attributes: const <String, String>{'data-testid': 'pair-fingerprint'}, <Widget>[Component.text(payload.fingerprint)]),
        ]),
        if (payload.directUrl != null) ...<Widget>[
          const dom.dt(<Widget>[TipLabel(label: Component.text('Direct'), tip: 'The server\'s own address.', docs: Docs.connecting)]),
          dom.dd(<Widget>[
            dom.code(<Widget>[Component.text(payload.directUrl!)]),
          ]),
        ],
        if (payload.relayUrl != null) ...<Widget>[
          const dom.dt(<Widget>[TipLabel(label: Component.text('Relay'), tip: 'Used when the direct address does not answer.', docs: Docs.connecting)]),
          dom.dd(<Widget>[
            dom.code(<Widget>[Component.text(payload.relayUrl!)]),
          ]),
        ],
        if (payload.label != null && payload.label!.trim().isNotEmpty) ...<Widget>[
          const dom.dt(<Widget>[Component.text('Link name')]),
          dom.dd(<Widget>[Component.text(payload.label!)]),
        ],
      ]),
    ],
  );
}
