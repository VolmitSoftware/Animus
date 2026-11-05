import 'dart:convert';
import 'dart:io';

import 'package:animus_editor/model/document_kind.dart';

import 'direct.dart';
import 'documents.dart';
import 'messages.dart';
import 'protocol.dart';
import 'server.dart';

/// Test controls under `http://127.0.0.1:<port>/__fake/`: no auth, loopback
/// callers only, CORS-allowed for the configured origins.
class ControlPath {
  ControlPath(this.server);

  final FakeServer server;

  Future<void> serve(HttpRequest request) async {
    final InternetAddress? peer = request.connectionInfo?.remoteAddress;
    final String? origin = request.headers.value('origin');
    if (peer == null || !peer.isLoopback) {
      await request.drain<void>();
      return _reply(request, const FakeResponse(403, body: <String, Object?>{'error': 'loopback only'}), origin);
    }
    if (origin != null && !server.settings.allowedOrigins.contains(origin)) {
      await request.drain<void>();
      return _reply(request, FakeResponse(403, body: <String, Object?>{'error': 'origin $origin is not allowed'}), null);
    }
    FakeResponse result;
    try {
      final Object? body = await _body(request);
      result = _route(request.method, request.uri, body);
    } on ApiError catch (refused) {
      result = FakeResponse(refused.status, body: <String, Object?>{'error': refused.message});
    } on FormatException catch (failure) {
      result = FakeResponse(400, body: <String, Object?>{'error': 'invalid JSON body: ${failure.message}'});
    }
    return _reply(request, result, origin);
  }

  Future<Object?> _body(HttpRequest request) async {
    final String text = await utf8.decoder.bind(request).join();
    return text.trim().isEmpty ? null : jsonDecode(text);
  }

  Future<void> _reply(HttpRequest request, FakeResponse result, String? origin) {
    final Map<String, String> headers = origin == null
        ? const <String, String>{}
        : <String, String>{
            'Access-Control-Allow-Origin': origin,
            'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
            'Access-Control-Allow-Headers': 'Content-Type',
            'Access-Control-Allow-Private-Network': 'true',
            'Vary': 'Origin',
          };
    return writeResponse(request.response, result.withHeaders(headers));
  }

  static Map<String, Object?> _object(Object? body) {
    if (body is! Map<String, Object?>) throw invalid('Send a JSON object body.');
    return body;
  }

  static Duration _seconds(Map<String, Object?> body) {
    final Object? seconds = body['seconds'];
    if (seconds is! num || seconds < 0) throw invalid('seconds: expected a non-negative number');
    return Duration(milliseconds: (seconds * 1000).round());
  }

  FakeResponse _route(String method, Uri uri, Object? body) {
    final String route = uri.path.substring('/__fake'.length);
    if (method == 'OPTIONS') return const FakeResponse(204);
    switch ('$method $route') {
      case 'POST /pair':
        final String paths = uri.queryParameters['paths'] ?? 'both';
        final PairPaths chosen = switch (paths) {
          'both' => PairPaths.both,
          'direct' => PairPaths.direct,
          'relay' => PairPaths.relay,
          _ => throw invalid('paths: both, direct or relay'),
        };
        final List<String> extra = <String>[
          for (final String scope in (uri.queryParameters['scopes'] ?? '').split(','))
            if (scope.isNotEmpty) scope,
        ];
        for (final String scope in extra) {
          if (!const <String>{'import', 'spawn', 'admin'}.contains(scope)) throw invalid('scopes: import, spawn or admin, comma separated');
        }
        return FakeResponse(200, body: server.pair(chosen, extra));
      case 'POST /edit':
        final Map<String, Object?> edit = _object(body);
        fields(edit, r'$', <String>{'kind', 'id', 'source'});
        final String kindName = edit['kind'] is String ? edit['kind']! as String : '';
        final DocumentKind kind = DocumentKind.parse(kindName) ?? (throw invalid('kind: unknown kind $kindName'));
        final String id = stringField(edit, 'id', documentIdPattern, r'$.id');
        final Object? source = edit['source'];
        if (source is! Map<String, Object?>) throw invalid('source: expected an object');
        return FakeResponse(200, body: server.api.serverEdit(kind, id, source));
      case 'POST /drop':
        final Map<String, Object?> drop = _object(body);
        final Object? path = drop['path'];
        if (path != 'direct' && path != 'relay' && path != 'all') throw invalid('path: direct, relay or all');
        final Duration duration = _seconds(drop);
        server.drop(path! as String, duration);
        return FakeResponse(200, body: <String, Object?>{'dropped': path, 'until': instant(now().add(duration))});
      case 'POST /offline':
        final Duration duration = _seconds(_object(body));
        server.goOffline(duration);
        return FakeResponse(200, body: <String, Object?>{'offline': true, 'until': instant(now().add(duration))});
      case 'POST /revoke':
        final Map<String, Object?> revoke = _object(body);
        final Object? tokenId = revoke['tokenId'];
        if (tokenId is! String) throw invalid('tokenId: a token id or all');
        return FakeResponse(200, body: <String, Object?>{'revoked': server.api.revoke(tokenId)});
      case 'POST /restore':
        server.restore();
        return const FakeResponse(200, body: <String, Object?>{'restored': true});
      case 'POST /reset':
        server.reset();
        return const FakeResponse(200, body: <String, Object?>{'reset': true});
      case 'GET /state':
        return FakeResponse(200, body: server.state());
      case 'GET /document':
        final DocumentKind kind = DocumentKind.parse(uri.queryParameters['kind'] ?? '') ?? (throw invalid('kind: unknown kind'));
        final StoredDocument? stored = server.api.documents.find(kind, uri.queryParameters['id'] ?? '');
        if (stored == null) return const FakeResponse(404, body: <String, Object?>{'error': 'no such document'});
        return FakeResponse(200, body: <String, Object?>{...stored.version, 'source': jsonDecode(stored.content)});
      default:
        return FakeResponse(404, body: <String, Object?>{'error': 'no control $method /__fake$route'});
    }
  }
}
