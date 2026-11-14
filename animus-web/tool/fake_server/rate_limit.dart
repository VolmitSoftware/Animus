import 'dart:math';

import 'protocol.dart';

class _Bucket {
  _Bucket(this.tokens, this.micros);

  double tokens;
  int micros;
}

/// `[editor] pairAttemptsPerMinute`: pair attempts one address may make in a
/// clock minute.
const int pairAttemptsPerMinute = 10;

/// The per-token limits of EditorApi: a request budget refilling
/// [perSecond] requests a second and holding at most twice that, and at most
/// `concurrentRequestsPerToken` requests in flight. Also the per-address
/// pairing window of EditorPairingRoutes.rate.
class RateLimiter {
  RateLimiter(this.perSecond);

  final int perSecond;
  final Stopwatch _clock = Stopwatch()..start();
  final Map<String, _Bucket> _buckets = <String, _Bucket>{};
  final Map<String, int> _refused = <String, int>{};
  final Map<String, int> _inFlight = <String, int>{};

  /// Pair attempts per address in the clock minute [_pairMinute].
  final Map<String, int> _pairAttempts = <String, int>{};
  int _pairMinute = -1;

  /// Requests refused per token id since the start or the last reset.
  Map<String, int> get refused => Map<String, int>.unmodifiable(_refused);

  int get refusedTotal => _refused.values.fold(0, (int sum, int count) => sum + count);

  /// Requests running per token id; a token with none is left out.
  Map<String, int> get inFlight => Map<String, int>.unmodifiable(_inFlight);

  void clear() {
    _buckets.clear();
    _refused.clear();
    _inFlight.clear();
    _pairAttempts.clear();
  }

  /// Takes one of [tokenId]'s [slots] until the returned release runs, or
  /// refuses the request as EditorApi does when they are all taken.
  void Function() occupy(String tokenId, int slots) {
    final int running = _inFlight[tokenId] ?? 0;
    if (running >= slots) {
      _refused[tokenId] = (_refused[tokenId] ?? 0) + 1;
      throw ApiError('rate-limited', 'At most $slots requests run at once per token.', retryAfterMillis: 250);
    }
    _inFlight[tokenId] = running + 1;
    bool released = false;
    return () {
      if (released) return;
      released = true;
      final int left = (_inFlight[tokenId] ?? 1) - 1;
      if (left > 0) {
        _inFlight[tokenId] = left;
      } else {
        _inFlight.remove(tokenId);
      }
    };
  }

  /// Counts a pair attempt from [address] in this clock minute, refusing
  /// past [pairAttemptsPerMinute] with the wait until the next minute.
  void pairAttempt(String address) {
    final int millis = now().millisecondsSinceEpoch;
    final int minute = millis ~/ 60000;
    if (minute != _pairMinute) {
      _pairMinute = minute;
      _pairAttempts.clear();
    }
    final int attempts = (_pairAttempts[address] ?? 0) + 1;
    _pairAttempts[address] = attempts;
    if (attempts > pairAttemptsPerMinute) {
      throw ApiError('rate-limited', 'Too many pairing attempts from this address.', retryAfterMillis: 60000 - millis % 60000);
    }
  }

  /// Takes one request from [tokenId]'s bucket, or refuses it with the wait
  /// until the next one is there.
  void charge(String tokenId) {
    final double capacity = perSecond * 2.0;
    final int time = _clock.elapsedMicroseconds;
    final _Bucket bucket = _buckets.putIfAbsent(tokenId, () => _Bucket(capacity, time));
    bucket.tokens = min(capacity, bucket.tokens + (time - bucket.micros) / 1e6 * perSecond);
    bucket.micros = time;
    if (bucket.tokens < 1) {
      _refused[tokenId] = (_refused[tokenId] ?? 0) + 1;
      throw ApiError('rate-limited', 'Too many requests for this token.', retryAfterMillis: ((1 - bucket.tokens) / perSecond * 1000).ceil());
    }
    bucket.tokens -= 1;
  }
}
