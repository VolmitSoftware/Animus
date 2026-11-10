/// Follows where a spawned NPC stands on the paired server, so the stage
/// can load terrain around it as it walks. Asks every [livePositionPeriod]
/// while it follows an NPC and the server shares terrain; one question at
/// a time.
library;

import 'dart:async';

import '../../services/terrain/terrain_client.dart';
import '../../services/terrain/terrain_link.dart';

/// How often the stage asks where the NPC is.
const Duration livePositionPeriod = Duration(milliseconds: 1500);

final class LivePosition {
  LivePosition(this._changed);

  final void Function() _changed;
  String? _npc;
  Timer? _timer;
  bool _asking = false;

  /// Where the followed NPC was at the last answer, or null.
  TerrainLivePosition? at;

  /// Follows [npc] (its id, such as town/guard), or stops when null.
  void follow(String? npc) {
    if (npc == _npc) return;
    _npc = npc;
    _timer?.cancel();
    _timer = null;
    if (at != null) {
      at = null;
      _changed();
    }
    if (npc == null) return;
    _ask();
    _timer = Timer.periodic(livePositionPeriod, (Timer _) => _ask());
  }

  void _ask() {
    final String? npc = _npc;
    final TerrainClient? client = TerrainLink.client;
    if (npc == null || client == null || _asking) return;
    _asking = true;
    unawaited(client.position(npc).then((TerrainLivePosition? answer) {
      if (npc != _npc || answer == at) return;
      at = answer;
      _changed();
    }, onError: (Object _) {}).whenComplete(() => _asking = false));
  }

  void dispose() {
    _timer?.cancel();
    _timer = null;
    _npc = null;
  }
}
