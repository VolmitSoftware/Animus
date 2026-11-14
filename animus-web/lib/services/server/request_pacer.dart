/// Keeps one connection's requests within the server's rate limit
/// (`[editor] requestsPerSecond`, 20 by default, bursting to twice that, per
/// token), so a burst of terrain tiles or batch reads queues here instead of
/// drawing `429 rate-limited` answers; it stays a little under both. After a
/// 429 every request waits out the server's hint. Background requests
/// (terrain) take the whole rate while nothing else waits, but never the
/// last tokens of the burst; while other requests wait, those go first and
/// background requests keep to half the rate. [RequestSlots] keeps it within
/// the server's in-flight limit per token (`[editor] concurrentRequestsPerToken`).
library;

import 'dart:async';
import 'dart:math' as math;

class RequestPacer {
  RequestPacer({this.perSecond = 20}) : _tokens = perSecond * 2.0 - _headroom;

  /// The server's requests per second; it bursts to twice that.
  final int perSecond;

  /// Requests leave a little under the server's rate and burst: on the way
  /// they bunch up (a busy relay, a slow tick), and at the exact limit a
  /// bunch arriving together drew an occasional 429.
  static const double _margin = 0.9;
  static const double _headroom = 2;

  /// Tokens background requests leave, so a burst of reads or an Apply goes
  /// at once while terrain loads.
  static const double _reserve = 12;

  double get _rate => perSecond * _margin;
  double get _burst => perSecond * 2.0 - _headroom;
  double _tokens;
  final Stopwatch _clock = Stopwatch()..start();
  int _last = 0;
  int _heldUntil = 0;
  Future<void> _turn = Future<void>.value();
  Future<void> _backgroundTurn = Future<void>.value();

  /// Requests that are not background, asked and not yet let go.
  int _waiting = 0;

  /// When the last background request went, in clock microseconds.
  int _lastBackground = -1000000;

  /// Completes when the next request may go, in the order asked; a
  /// [background] request also lets every other request go first.
  Future<void> take({bool background = false}) {
    if (background) {
      final Future<void> turn = _backgroundTurn.then((void _) => _waitBackground());
      _backgroundTurn = turn;
      return turn;
    }
    _waiting++;
    final Future<void> turn = _turn.then((void _) => _wait()).whenComplete(() => _waiting--);
    _turn = turn;
    return turn;
  }

  /// The server refused a request for [wait]: nothing goes before it passes.
  void hold(Duration wait) {
    _heldUntil = math.max(_heldUntil, _clock.elapsedMicroseconds + wait.inMicroseconds);
    _tokens = 0;
  }

  int _refill() {
    final int now = _clock.elapsedMicroseconds;
    _tokens = math.min(_burst, _tokens + (now - _last) / 1e6 * _rate);
    _last = now;
    return now;
  }

  /// Microseconds until the bucket holds [count] tokens.
  int _until(double count) => ((count - _tokens) / _rate * 1e6).ceil();

  Future<void> _wait() async {
    while (true) {
      final int held = _heldUntil - _refill();
      if (held <= 0 && _tokens >= 1) {
        _tokens -= 1;
        return;
      }
      await Future<void>.delayed(Duration(microseconds: math.max(held, _until(1))));
    }
  }

  Future<void> _waitBackground() async {
    final int gap = (2e6 / _rate).ceil();
    while (true) {
      final int now = _refill();
      final int held = _heldUntil - now;
      final int wait;
      if (_waiting == 0) {
        // Nothing else waits: the whole rate, above the reserve.
        if (held <= 0 && _tokens >= 1 + _reserve) break;
        wait = math.max(held, _until(1 + _reserve));
      } else {
        // Others wait and go first; this keeps to half the rate.
        final int since = now - _lastBackground;
        if (held <= 0 && _tokens >= 1 && since >= gap) break;
        wait = math.max(held, math.max(_until(1), gap - since));
      }
      await Future<void>.delayed(Duration(microseconds: wait));
    }
    _tokens -= 1;
    _lastBackground = _clock.elapsedMicroseconds;
  }
}

/// At most [size] requests in flight; the rest wait in the order they asked.
class RequestSlots {
  RequestSlots(this.size);

  final int size;
  int _taken = 0;
  final List<Completer<void>> _waiting = <Completer<void>>[];

  /// Completes when a slot is free; [release] it once the answer is in.
  Future<void> take() {
    if (_taken < size) {
      _taken++;
      return Future<void>.value();
    }
    final Completer<void> slot = Completer<void>();
    _waiting.add(slot);
    return slot.future;
  }

  /// Hands the slot to the oldest waiting request, else frees it.
  void release() {
    if (_waiting.isNotEmpty) {
      _waiting.removeAt(0).complete();
    } else {
      _taken--;
    }
  }
}
