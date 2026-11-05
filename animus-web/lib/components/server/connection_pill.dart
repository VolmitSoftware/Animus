import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../services/server/server_connection.dart';

/// A small status label for one server connection.
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
    final String label = status.phase == ConnectionPhase.offline && !hasToken ? 'Not paired' : status.label;
    return dom.span(
      classes: 'animus-pill $tone',
      attributes: <String, String>{'role': 'status', 'data-phase': status.phase.name, if (status.message != null) 'title': status.message!},
      <Widget>[
        const dom.span(classes: 'animus-pill-dot', attributes: <String, String>{'aria-hidden': 'true'}, <Widget>[]),
        Component.text(label),
      ],
    );
  }
}
