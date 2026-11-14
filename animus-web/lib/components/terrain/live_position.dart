/// Follows where a spawned NPC stands on the paired server, so the stage
/// can load terrain around it as it walks. One question at a time, paced
/// by [livePollDelay]: only while the stage is on screen ([seen]) and the
/// tab has focus, less often the longer the NPC stands still. The
/// server is not asked while a bulk load runs or the tab is hidden
/// (`TerrainClient.position` throws `PollPaused`); the last answer stands.
library;

import 'dart:async';

import 'package:web/web.dart' as web;

import '../../logic/terrain/live_polling.dart';
import '../../services/terrain/terrain_client.dart';
import '../../services/terrain/terrain_link.dart';

final class LivePosition {
  LivePosition(this._changed);

  final void Function() _changed;
  String? _npc;
  Timer? _timer;
  bool _asking = false;
  DateTime _changedAt = DateTime.now();

  /// False while the stage is scrolled out of view; the stage sets it.
  bool seen = true;

  /// Where the followed NPC was at the last answer, or null.
  TerrainLivePosition? at;

  /// Follows [npc] (its id, such as town/guard), or stops when null.
  void follow(String? npc) {
    if (npc == _npc) return;
    _npc = npc;
    _timer?.cancel();
    _timer = null;
    _changedAt = DateTime.now();
    if (at != null) {
      at = null;
      _changed();
    }
    if (npc == null) return;
    _ask();
  }

  /// The stage came back into view: ask now rather than at the next turn.
  void wake() {
    if (_npc == null || _asking) return;
    _timer?.cancel();
    _ask();
  }

  bool get _focused => web.document.visibilityState == 'visible' && web.document.hasFocus();

  void _next() {
    _timer?.cancel();
    if (_npc == null) return;
    final Duration delay = livePollDelay(seen: true, sinceChange: DateTime.now().difference(_changedAt))!;
    _timer = Timer(delay, _ask);
  }

  void _ask() {
    final String? npc = _npc;
    final TerrainClient? client = TerrainLink.client;
    if (npc == null) return;
    if (client == null || _asking || livePollDelay(seen: seen && _focused, sinceChange: Duration.zero) == null) {
      _next();
      return;
    }
    _asking = true;
    unawaited(client.position(npc).then((TerrainLivePosition? answer) {
      if (npc != _npc || answer == at) return;
      at = answer;
      _changedAt = DateTime.now();
      _changed();
    }, onError: (Object _) {}).whenComplete(() {
      _asking = false;
      _next();
    }));
  }

  void dispose() {
    _timer?.cancel();
    _timer = null;
    _npc = null;
  }
}
