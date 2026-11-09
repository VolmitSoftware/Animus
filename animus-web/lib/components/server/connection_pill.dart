import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../services/server/error_copy.dart';
import '../../services/server/server_connection.dart';

/// A small status label for one server connection: a dot and the state in
/// words, so the colour is never the only signal.
class ConnectionPill extends StatelessWidget {
  const ConnectionPill({required this.status, required this.hasToken, super.key});

  final ConnectionStatus status;
  final bool hasToken;

  @override
  Widget build(BuildContext context) {
    final String tone = switch (status.phase) {
      ConnectionPhase.online => 'is-online',
      ConnectionPhase.connecting || ConnectionPhase.reconnecting => 'is-waiting',
      ConnectionPhase.needsPairing || ConnectionPhase.refused => 'is-error',
      ConnectionPhase.offline => hasToken ? 'is-idle' : 'is-error',
    };
    final String label = phaseLabel(status, hasToken: hasToken);
    return dom.span(
      classes: 'animus-pill $tone',
      attributes: <String, String>{'role': 'status', 'data-phase': status.phase.name, 'title': label},
      <Widget>[
        const dom.span(classes: 'animus-pill-dot', attributes: <String, String>{'aria-hidden': 'true'}, <Widget>[]),
        dom.span(classes: 'animus-pill-text', <Widget>[Component.text(label)]),
      ],
    );
  }
}

/// The state in two or three words.
String phaseLabel(ConnectionStatus status, {required bool hasToken}) {
  if (status.phase == ConnectionPhase.offline && !hasToken) return 'Not paired';
  return switch (status.phase) {
    ConnectionPhase.needsPairing => 'Pairing ended',
    ConnectionPhase.refused => 'Connection refused',
    _ => status.label,
  };
}

/// Why the connection is not online and what to do, for the live bar, the
/// server page and the sync page. Empty while online. [connectHere] says a
/// Connect button sits beside the hint, so it need not point elsewhere.
String phaseHint(ConnectionStatus status, {required bool hasToken, bool connectHere = false}) {
  if (status.online) return '';
  if (!hasToken && status.phase != ConnectionPhase.needsPairing) return transportCopy('no-token').text;
  final String? message = status.message;
  return switch (status.phase) {
    ConnectionPhase.reconnecting when status.retryAt != null =>
      '${message ?? transportCopy('dropped').cause} Next try at ${status.retryAt!.toLocal().toString().substring(11, 19)}.',
    ConnectionPhase.offline => connectHere ? 'Not connected.' : 'Not connected. Use Connect on the server page.',
    _ => message ?? '',
  };
}
