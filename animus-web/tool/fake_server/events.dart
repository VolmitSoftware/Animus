/// Where an event stream runs: a direct WebSocket or a relay stream id.
enum StreamPath { direct, relay }

/// One authenticated event stream. [send] writes one `event` frame; [close]
/// ends the stream on its transport.
class EventStream {
  EventStream(this.tokenId, this.path, this.send, this.close);

  final String tokenId;
  final StreamPath path;
  final void Function(Map<String, Object?> event) send;
  final void Function() close;
}

/// Event streams of `/v1/events`. Frames go out at once; the fake keeps no
/// queue, so catalog frames are not coalesced.
class EventHub {
  EventHub(this.lastClosed);

  /// Called when a token's last stream closes (its previews end).
  final void Function(String tokenId) lastClosed;
  final List<EventStream> _streams = <EventStream>[];

  int get open => _streams.length;

  int openFor(StreamPath path) => _streams.where((EventStream stream) => stream.path == path).length;

  /// Registers a stream. Returns a handle that removes it.
  void Function() add(EventStream stream) {
    _streams.add(stream);
    bool removed = false;
    return () {
      if (removed) return;
      removed = true;
      _remove(stream);
    };
  }

  void _remove(EventStream stream) {
    if (!_streams.remove(stream)) return;
    if (!_streams.any((EventStream other) => other.tokenId == stream.tokenId)) lastClosed(stream.tokenId);
  }

  void publish(Map<String, Object?> event) {
    for (final EventStream stream in _streams.toList()) {
      stream.send(event);
    }
  }

  /// Sends a `closing` frame to the token's streams and closes them.
  void close(String tokenId, String reason) => closeWhere((EventStream stream) => stream.tokenId == tokenId, reason);

  void closeAll(String reason) => closeWhere((EventStream stream) => true, reason);

  /// Closes streams without a closing frame, as a lost connection does.
  void dropWhere(bool Function(EventStream stream) which) {
    for (final EventStream stream in _streams.where(which).toList()) {
      _remove(stream);
      stream.close();
    }
  }

  void closeWhere(bool Function(EventStream stream) which, String reason) {
    for (final EventStream stream in _streams.where(which).toList()) {
      _remove(stream);
      stream.send(<String, Object?>{'type': 'closing', 'reason': reason});
      stream.close();
    }
  }
}
