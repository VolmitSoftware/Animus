import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../services/server/connection_status.dart';
import '../../ui/badge.dart';
import '../../ui/tone.dart';
import '../shell_topbar.dart' show serverState;
import 'connection_words.dart';

/// One server connection's state as a badge: a dot and the state in words,
/// so the colour is never the only signal. [detailed] names the path
/// ("Online (direct)"), for Advanced.
class ConnectionPill extends StatelessWidget {
  const ConnectionPill({required this.status, required this.hasToken, this.elsewhere = false, this.detailed = false, super.key});

  final ConnectionStatus status;
  final bool hasToken;

  /// Live editing for this server runs in another tab of this browser, so
  /// this one holds no connection on purpose.
  final bool elsewhere;
  final bool detailed;

  @override
  Widget build(BuildContext context) {
    if (elsewhere) {
      return const StatusBadge('In another tab', dot: true, attributes: <String, String>{'role': 'status', 'data-phase': 'elsewhere'});
    }
    final (String plain, BadgeTone tone) = serverState(status);
    final String label = !hasToken && status.phase == ConnectionPhase.offline ? 'Not paired' : (detailed ? phaseLabel(status, hasToken: hasToken) : plain);
    return StatusBadge(label, tone: hasToken ? tone : BadgeTone.error, dot: true, attributes: <String, String>{'role': 'status', 'data-phase': status.phase.name});
  }
}
