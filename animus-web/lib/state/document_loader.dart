/// Reads server documents in batches (`POST /v1/documents/read`, up to 200
/// refs, a few calls in flight), retrying `rate-limited` and `busy` answers
/// after the server's hint, and hands each answer over as it lands. Asked-for
/// documents go first; the background fill takes what is left and waits while
/// the user types or the tab is hidden.
library;

import 'dart:async';

import '../logic/change_sets.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../model/document_summary.dart';
import '../model/server_binding.dart';
import '../services/server/connection_status.dart';
import '../services/server/error_copy.dart';
import '../services/server/transport.dart';

/// One document as the server sent it.
class FetchedDocument {
  const FetchedDocument(this.kind, this.id, this.base, this.summary, this.diagnostics);

  final DocumentKind kind;
  final String id;
  final ServerBase base;
  final DocumentSummary summary;
  final List<Diagnostic> diagnostics;

  String get key => documentKey(kind, id);

  static FetchedDocument? fromJson(Object? json) {
    if (json is! Map<String, Object?>) return null;
    final DocumentKind? kind = DocumentKind.parse('${json['kind']}');
    if (kind == null || json['id'] is! String || json['revision'] is! num) return null;
    return FetchedDocument(
      kind,
      json['id']! as String,
      ServerBase(revision: (json['revision']! as num).toInt(), hash: '${json['hash']}', path: '${json['path']}', source: json['source']),
      DocumentSummary.fromJson(json['summary']),
      diagnosticsOf(json['diagnostics']),
    );
  }
}

/// How a multi-document load is going: [done] of [total] read, [failed]
/// that could not be.
class LoadProgress {
  const LoadProgress(this.done, this.total, this.failed);

  final int done;
  final int total;
  final int failed;

  bool get running => done + failed < total;
}

/// How a load ended.
class LoadResult {
  const LoadResult({this.loaded = const <String>[], this.missing = const <String>[], this.failed = const <String>[], this.reason});

  /// Keys read and handed over.
  final List<String> loaded;

  /// Keys the server no longer has.
  final List<String> missing;

  /// Keys that could not be read.
  final List<String> failed;

  /// Why the first failure happened.
  final ErrorCopy? reason;

  bool get complete => failed.isEmpty;
}

/// Thrown into a load's keys when the loader stops (the session ended).
class LoaderStopped implements Exception {
  const LoaderStopped();
}

class _Waiter {
  _Waiter(this.keys);

  final Set<String> keys;
  final List<String> loaded = <String>[];
  final List<String> missing = <String>[];
  final List<String> failed = <String>[];
  ErrorCopy? reason;
  final Completer<LoadResult> done = Completer<LoadResult>();

  void settle(String key, {required String outcome, ErrorCopy? why}) {
    if (!keys.remove(key)) return;
    switch (outcome) {
      case 'loaded':
        loaded.add(key);
      case 'missing':
        missing.add(key);
      default:
        failed.add(key);
        reason ??= why;
    }
    if (keys.isEmpty && !done.isCompleted) done.complete(LoadResult(loaded: loaded, missing: missing, failed: failed, reason: reason));
  }
}

class DocumentLoader {
  DocumentLoader({
    required this.send,
    required this.deliver,
    required this.quiet,
    this.parallel = 4,
    this.batch = 200,
    this.onChange,
  });

  /// Sends one request, retrying the server's rate-limit hint.
  final Future<ApiResponse> Function(ApiRequest request) send;

  /// Takes one answer's documents; the returned future completes once they
  /// are stored.
  final Future<void> Function(List<FetchedDocument> documents) deliver;

  /// True while background reads should wait (typing, a hidden tab).
  final bool Function() quiet;
  final int parallel;
  final int batch;

  /// Called whenever [progress] or [loading] moved.
  final void Function()? onChange;

  final List<String> _urgent = <String>[];
  final List<String> _background = <String>[];

  /// Every key in [_urgent] or [_background].
  final Set<String> _queued = <String>{};
  final Set<String> _inFlight = <String>{};
  final List<_Waiter> _waiters = <_Waiter>[];
  int _running = 0;
  bool _stopped = false;
  Timer? _resume;

  int _total = 0;
  int _done = 0;
  int _failed = 0;

  /// Keys queued or being read.
  bool isLoading(String key) => _inFlight.contains(key) || _queued.contains(key);

  /// The load in progress across every request since the loader was last
  /// idle; null when idle.
  LoadProgress? get progress => _total == 0 ? null : LoadProgress(_done, _total, _failed);

  bool get idle => _running == 0 && _urgent.isEmpty && _background.isEmpty;

  /// Reads [keys] ahead of the background fill. Keys already queued move up.
  Future<LoadResult> load(Iterable<String> keys) {
    final _Waiter waiter = _Waiter(<String>{...keys});
    if (waiter.keys.isEmpty) return Future<LoadResult>.value(const LoadResult());
    if (_stopped) return Future<LoadResult>.value(LoadResult(failed: waiter.keys.toList(), reason: transportCopy('closed')));
    _waiters.add(waiter);
    for (final String key in waiter.keys) {
      if (_inFlight.contains(key) || _urgent.contains(key)) continue;
      if (_queued.contains(key)) {
        _background.remove(key);
      } else {
        _total++;
        _queued.add(key);
      }
      _urgent.add(key);
    }
    _pump();
    return waiter.done.future;
  }

  /// Queues [keys] behind every asked-for read.
  void fill(Iterable<String> keys) {
    for (final String key in keys) {
      if (isLoading(key)) continue;
      _background.add(key);
      _queued.add(key);
      _total++;
    }
    _pump();
  }

  /// Drops queued background reads (the catalog changed under them).
  void clearBackground() {
    _total -= _background.length;
    _queued.removeAll(_background);
    _background.clear();
    _settleIdle();
  }

  void stop() {
    _stopped = true;
    _resume?.cancel();
    for (final _Waiter waiter in List<_Waiter>.of(_waiters)) {
      for (final String key in List<String>.of(waiter.keys)) {
        waiter.settle(key, outcome: 'failed', why: transportCopy('closed'));
      }
    }
    _waiters.clear();
    _urgent.clear();
    _background.clear();
    _queued.clear();
  }

  void _settle(String key, String outcome, {ErrorCopy? why}) {
    _inFlight.remove(key);
    if (outcome == 'failed') {
      _failed++;
    } else {
      _done++;
    }
    for (final _Waiter waiter in List<_Waiter>.of(_waiters)) {
      waiter.settle(key, outcome: outcome, why: why);
      if (waiter.done.isCompleted) _waiters.remove(waiter);
    }
  }

  void _settleIdle() {
    if (idle) {
      _total = 0;
      _done = 0;
      _failed = 0;
    }
    onChange?.call();
  }

  void _pump() {
    if (_stopped) return;
    while (_running < parallel && (_urgent.isNotEmpty || _background.isNotEmpty)) {
      final bool urgent = _urgent.isNotEmpty;
      if (!urgent && quiet()) {
        _resume ??= Timer(const Duration(milliseconds: 500), () {
          _resume = null;
          _pump();
        });
        break;
      }
      final List<String> queue = urgent ? _urgent : _background;
      final List<String> keys = queue.sublist(0, queue.length < batch ? queue.length : batch);
      queue.removeRange(0, keys.length);
      _queued.removeAll(keys);
      _inFlight.addAll(keys);
      _running++;
      unawaited(_read(keys).whenComplete(() {
        _running--;
        _pump();
        _settleIdle();
      }));
    }
    onChange?.call();
  }

  Future<void> _read(List<String> keys) async {
    ApiResponse response;
    try {
      response = await send(ApiRequest(
        'POST',
        '/v1/documents/read',
        body: <String, Object?>{
          'refs': <Object?>[
            for (final String key in keys)
              if (parseDocumentKey(key) case (final DocumentKind kind, final String id)) <String, Object?>{'kind': kind.wireName, 'id': id},
          ],
        },
        counted: false,
      ));
    } on NotConnected catch (offline) {
      for (final String key in keys) {
        _settle(key, 'failed', why: offline.status.problem ?? transportCopy('dropped'));
      }
      return;
    } on TransportFailure catch (failure) {
      for (final String key in keys) {
        _settle(key, 'failed', why: failure.copy);
      }
      return;
    }
    final Object? body = response.body;
    if (!response.ok || body is! Map<String, Object?>) {
      for (final String key in keys) {
        _settle(key, 'failed', why: response.problem);
      }
      return;
    }
    final List<FetchedDocument> documents = <FetchedDocument>[
      for (final Object? raw in (body['documents'] as List<Object?>?) ?? const <Object?>[])
        if (FetchedDocument.fromJson(raw) case final FetchedDocument document) document,
    ];
    if (documents.isNotEmpty) await deliver(documents);
    final Set<String> answered = <String>{};
    for (final FetchedDocument document in documents) {
      answered.add(document.key);
      _settle(document.key, 'loaded');
    }
    for (final Object? raw in (body['errors'] as List<Object?>?) ?? const <Object?>[]) {
      if (raw is! Map<String, Object?>) continue;
      final String key = '${raw['kind']}:${raw['id']}';
      answered.add(key);
      if (raw['code'] == 'not-found') {
        _settle(key, 'missing');
      } else {
        _settle(key, 'failed', why: serverCopy('${raw['code']}'));
      }
    }
    final List<String> remaining = <String>[
      for (final Object? raw in (body['remaining'] as List<Object?>?) ?? const <Object?>[])
        if (raw is Map<String, Object?>) '${raw['kind']}:${raw['id']}',
    ];
    final Set<String> left = remaining.toSet();
    final List<String> urgent = <String>[];
    final List<String> later = <String>[];
    for (final String key in keys) {
      if (answered.contains(key)) continue;
      if (!left.contains(key)) {
        _settle(key, 'failed', why: serverCopy('internal'));
        continue;
      }
      // Did not fit this answer: ahead of everything else in its queue.
      _inFlight.remove(key);
      _queued.add(key);
      (_waiters.any((_Waiter waiter) => waiter.keys.contains(key)) ? urgent : later).add(key);
    }
    _urgent.insertAll(0, urgent);
    _background.insertAll(0, later);
  }
}
