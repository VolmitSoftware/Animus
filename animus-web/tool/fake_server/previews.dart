import 'protocol.dart';

class Preview {
  const Preview(this.id, this.tokenId, this.viewer, this.expires, this.npcs);

  final String id;
  final String tokenId;
  final String viewer;
  final DateTime expires;
  final List<String> npcs;

  Map<String, Object?> get state => <String, Object?>{
        'previewId': id,
        'tokenId': tokenId,
        'viewer': viewer,
        'expiresAt': instant(expires),
        'npcs': npcs,
      };
}

/// Running previews, as EditorPreviews. [ended] receives the preview id and
/// its end state (ended, expired or player-left) for the event stream.
class FakePreviews {
  FakePreviews(this.ended);

  final void Function(String id, String state) ended;
  final Map<String, Preview> _running = <String, Preview>{};

  List<Preview> get running => List<Preview>.unmodifiable(_running.values);

  /// Reserves a slot, or replaces the token's own preview.
  Preview start(String? replace, String tokenId, String viewer, List<String> npcs) {
    expire();
    final Duration lifetime = Duration(minutes: limit('previewMinutes'));
    if (replace != null) {
      final Preview? previous = _running[replace];
      if (previous == null || previous.tokenId != tokenId) {
        throw ApiError('not-found', 'No running preview $replace for this token.');
      }
      final Preview next = Preview(previous.id, tokenId, viewer, now().add(lifetime), npcs);
      _running[next.id] = next;
      return next;
    }
    final int maximum = limit('previewsPerServer');
    if (_running.length >= maximum) {
      throw ApiError('busy', 'This server runs at most $maximum previews at once.', retryAfterMillis: 30000);
    }
    final Preview preview = Preview('prev_${randomText(12)}', tokenId, viewer, now().add(lifetime), npcs);
    _running[preview.id] = preview;
    return preview;
  }

  Preview? find(String id) => _running[id];

  void end(String id, String state) {
    if (_running.remove(id) != null) ended(id, state);
  }

  void endToken(String tokenId) {
    for (final Preview preview in _running.values.toList()) {
      if (preview.tokenId == tokenId) end(preview.id, 'ended');
    }
  }

  void endAll() {
    for (final String id in _running.keys.toList()) {
      end(id, 'ended');
    }
  }

  /// Ends previews past their lifetime.
  void expire() {
    final DateTime time = now();
    for (final Preview preview in _running.values.toList()) {
      if (!time.isBefore(preview.expires)) end(preview.id, 'expired');
    }
  }

  /// Forgets every preview without events (reset).
  void clear() => _running.clear();
}
