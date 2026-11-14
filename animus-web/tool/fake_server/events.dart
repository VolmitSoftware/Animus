import 'dart:async';
import 'dart:convert';

import 'protocol.dart';

/// Where an event stream runs: a direct WebSocket or a relay stream id.
enum StreamPath { direct, relay }

/// The most bytes of entries one `catalog` frame carries.
const int catalogFrameBytes = 48 * 1024;

/// A catalog entry waiting for its frame, with its size as JSON.
class _Queued {
  const _Queued(this.entry, this.bytes);

  final Map<String, Object?> entry;
  final int bytes;
}

/// One authenticated event stream. [send] writes one `event` frame; [close]
/// ends the stream on its transport; [visible] says whether its token may
/// see an NPC id.
class EventStream {
  EventStream(this.tokenId, this.path, this.send, this.close, this.visible);

  final String tokenId;
  final StreamPath path;
  final void Function(Map<String, Object?> event) send;
  final void Function() close;
  final bool Function(String npc) visible;

  /// Every entry but an NPC its token may not see, as EditorEvents.allowed.
  bool _allowed(Map<String, Object?> entry) => entry['kind'] != 'npc' || visible(entry['id']! as String);

  /// Catalog versions and deletes not sent yet, by kind and id, oldest
  /// first; a document is in at most one of them.
  final Map<String, _Queued> _versions = <String, _Queued>{};
  final Map<String, _Queued> _deletes = <String, _Queued>{};
  Timer? _flush;
  DateTime? _lastCatalog;
}

/// Event streams of `/v1/events`. Change, preview and closing frames go out
/// at once. Catalog entries queue per stream as EditorEvents queues them: a
/// newer version or a delete of a document replaces the queued one and goes
/// to the back. They leave versions first, in frames of at most
/// [catalogFrameBytes] of entries, `eventFramesPerSecond` frames a second.
class EventHub {
  EventHub(this.lastClosed);

  /// Called when a token's last stream closes (its previews end).
  final void Function(String tokenId) lastClosed;
  final List<EventStream> _streams = <EventStream>[];

  int get open => _streams.length;

  int openFor(StreamPath path) => _streams.where((EventStream stream) => stream.path == path).length;

  static Duration get _spacing => Duration(microseconds: 1000000 ~/ limit('eventFramesPerSecond'));

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
    stream._flush?.cancel();
    stream._flush = null;
    stream._versions.clear();
    stream._deletes.clear();
    if (!_streams.any((EventStream other) => other.tokenId == stream.tokenId)) lastClosed(stream.tokenId);
  }

  /// Sends [event] to every stream; a `change` reaches only streams that
  /// may see one of its documents (or any, when it names none).
  void publish(Map<String, Object?> event) {
    final Object? result = event['result'];
    final List<Map<String, Object?>> named = event['type'] == 'change' && result is Map<String, Object?> && result['documents'] is List<Object?>
        ? (result['documents']! as List<Object?>).whereType<Map<String, Object?>>().toList()
        : const <Map<String, Object?>>[];
    for (final EventStream stream in _streams.toList()) {
      if (named.isNotEmpty && !named.any(stream._allowed)) continue;
      stream.send(event);
    }
  }

  /// Queues catalog entries: [documents] are versions with summary and
  /// source, [deleted] are `{kind, id, source}`.
  void publishCatalog(List<Map<String, Object?>> documents, List<Map<String, Object?>> deleted) {
    if (documents.isEmpty && deleted.isEmpty) return;
    final List<(String, _Queued)> versions = <(String, _Queued)>[for (final Map<String, Object?> entry in documents) _keyed(entry)];
    final List<(String, _Queued)> deletes = <(String, _Queued)>[for (final Map<String, Object?> entry in deleted) _keyed(entry)];
    for (final EventStream stream in _streams) {
      for (final (String key, _Queued queued) in versions) {
        if (!stream._allowed(queued.entry)) continue;
        stream._deletes.remove(key);
        stream._versions.remove(key);
        stream._versions[key] = queued;
      }
      for (final (String key, _Queued queued) in deletes) {
        if (!stream._allowed(queued.entry)) continue;
        stream._versions.remove(key);
        stream._deletes.remove(key);
        stream._deletes[key] = queued;
      }
      _schedule(stream);
    }
  }

  static (String, _Queued) _keyed(Map<String, Object?> entry) =>
      ('${entry['kind']}\n${entry['id']}', _Queued(entry, utf8.encode(jsonEncode(entry)).length));

  void _schedule(EventStream stream) {
    if (stream._flush != null || (stream._versions.isEmpty && stream._deletes.isEmpty)) return;
    final DateTime? last = stream._lastCatalog;
    final Duration wait = last == null ? Duration.zero : _spacing - now().difference(last);
    stream._flush = Timer(wait.isNegative ? Duration.zero : wait, () => _sendCatalog(stream));
  }

  void _sendCatalog(EventStream stream) {
    stream._flush = null;
    final List<Map<String, Object?>> documents = <Map<String, Object?>>[];
    final List<Map<String, Object?>> deleted = <Map<String, Object?>>[];
    int bytes = 0;
    for (final (Map<String, _Queued> waiting, List<Map<String, Object?>> into) in <(Map<String, _Queued>, List<Map<String, Object?>>)>[
      (stream._versions, documents),
      (stream._deletes, deleted),
    ]) {
      final List<String> sent = <String>[];
      for (final MapEntry<String, _Queued> queued in waiting.entries) {
        if (bytes > 0 && bytes + queued.value.bytes > catalogFrameBytes) break;
        sent.add(queued.key);
        bytes += queued.value.bytes;
        into.add(queued.value.entry);
      }
      sent.forEach(waiting.remove);
      if (waiting.isNotEmpty) break;
    }
    stream._lastCatalog = now();
    stream.send(<String, Object?>{'type': 'catalog', 'documents': documents, if (deleted.isNotEmpty) 'deleted': deleted});
    _schedule(stream);
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
