@TestOn('vm')
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:animus_editor/logic/json_schema.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/logic/suggest/completion_sources.dart';
import 'package:test/test.dart';

import '../tool/fake_server/server.dart';

const String origin = 'http://127.0.0.1:8098';
const String fingerprint = 'sha256:630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd';

/// Fails unless [body] is valid for the editor-v1 `$defs` entry [definition].
void expectMessage(String definition, Object? body) {
  final List<SchemaError> errors = SharedSchemas.embedded.validateMessage(definition, body);
  expect(errors, isEmpty, reason: '$definition: ${jsonEncode(body)}');
}

class Reply {
  const Reply(this.status, this.headers, this.body);

  final int status;
  final HttpHeaders headers;
  final Object? body;

  Map<String, Object?> get json => body! as Map<String, Object?>;

  String get errorCode => (json['error']! as Map<String, Object?>)['code']! as String;
}

/// Decodes a pairing code's payload.
Map<String, Object?> payloadOf(String code) =>
    jsonDecode(utf8.decode(base64Url.decode(base64Url.normalize(code.substring(5))))) as Map<String, Object?>;

/// Frames of one WebSocket. [next] takes the oldest frame; [nextOf] takes
/// the oldest frame of a type and leaves the others queued.
class Frames {
  Frames(this.socket) {
    socket.listen((Object? data) {
      _queued.add(jsonDecode(data! as String) as Map<String, Object?>);
      _arrived?.complete();
      _arrived = null;
    }, onDone: () => _closed.complete(socket.closeCode));
  }

  final WebSocket socket;
  final List<Map<String, Object?>> _queued = <Map<String, Object?>>[];
  Completer<void>? _arrived;
  final Completer<int?> _closed = Completer<int?>();

  Future<Map<String, Object?>> _take(bool Function(Map<String, Object?> frame) which) async {
    final DateTime deadline = DateTime.now().add(const Duration(seconds: 5));
    while (true) {
      final int index = _queued.indexWhere(which);
      if (index >= 0) return _queued.removeAt(index);
      final Duration left = deadline.difference(DateTime.now());
      if (left.isNegative) throw TimeoutException('no matching frame; queued: $_queued');
      await (_arrived ??= Completer<void>()).future.timeout(left, onTimeout: () {});
    }
  }

  Future<Map<String, Object?>> next() => _take((Map<String, Object?> frame) => true);

  Future<Map<String, Object?>> nextOf(String type) => _take((Map<String, Object?> frame) => frame['type'] == type);

  Future<int?> get closed => _closed.future.timeout(const Duration(seconds: 5));

  void send(Map<String, Object?> frame) => socket.add(jsonEncode(frame));
}

late FakeServer server;
late HttpClient client;
int counter = 0;

String get base => 'http://127.0.0.1:${server.port}';

Future<Reply> call(String method, String path, {Object? body, String? token, String? withOrigin = origin, int? count, bool counted = true}) async {
  final HttpClientRequest request = await client.openUrl(method, Uri.parse(path.startsWith('http') ? path : '$base$path'));
  if (withOrigin != null) request.headers.set('Origin', withOrigin);
  if (token != null) request.headers.set('Authorization', 'Bearer $token');
  if (counted && (method == 'POST' || method == 'DELETE') && token != null) {
    request.headers.set('X-Animus-Counter', '${count ?? ++counter}');
  }
  if (body != null) {
    request.headers.contentType = ContentType.json;
    request.write(jsonEncode(body));
  }
  final HttpClientResponse response = await request.close();
  final String text = await utf8.decoder.bind(response).join();
  return Reply(response.statusCode, response.headers, text.isEmpty ? null : jsonDecode(text));
}

Future<Map<String, Object?>> control(String method, String path, [Object? body]) async {
  final Reply reply = await call(method, '/__fake$path', body: body, withOrigin: null);
  expect(reply.status, 200, reason: jsonEncode(reply.body));
  return reply.json;
}

/// Mints a pairing code and exchanges it for a token.
Future<String> pairToken() async {
  final Map<String, Object?> minted = await control('POST', '/pair');
  final Map<String, Object?> payload = payloadOf(minted['code']! as String);
  final Reply reply = await call('POST', '/animus/v1/pair', body: <String, Object?>{'tokenId': payload['tokenId'], 'secret': payload['secret']});
  expect(reply.status, 200);
  expectMessage('pair-response', reply.body);
  return reply.json['token']! as String;
}

Future<Map<String, Object?>> document(String token, String kind, String id) async {
  final Reply reply = await call('GET', '/animus/v1/documents/$kind/${Uri.encodeComponent(id)}', token: token);
  expect(reply.status, 200);
  expectMessage('document-response', reply.body);
  return reply.json;
}

String key(String suffix) => 'test-key-$suffix-0000000000'.substring(0, 24);

/// A change set editing [current] (a document-response) with a new name.
Map<String, Object?> rename(Map<String, Object?> current, String name, String idempotencyKey) {
  final Map<String, Object?> source = Map<String, Object?>.of(current['source']! as Map<String, Object?>)..['name'] = name;
  return <String, Object?>{
    'idempotencyKey': idempotencyKey,
    'documents': <Object?>[
      <String, Object?>{
        'kind': current['kind'],
        'id': current['id'],
        'baseRevision': current['revision'],
        'baseHash': current['hash'],
        'source': source,
      },
    ],
  };
}

Future<Frames> directEvents(String token) async {
  final WebSocket socket = await WebSocket.connect('ws://127.0.0.1:${server.port}/animus/v1/events', headers: <String, Object>{'Origin': origin});
  final Frames frames = Frames(socket);
  frames.send(<String, Object?>{'type': 'auth', 'token': token});
  return frames;
}

Future<Frames> relay() async {
  final WebSocket socket = await WebSocket.connect('${server.relayUrl}/app', headers: <String, Object>{'Origin': origin});
  final Frames frames = Frames(socket);
  frames.send(<String, Object?>{'type': 'subscribe', 'protocol': 1, 'fingerprint': fingerprint});
  final Map<String, Object?> subscribed = await frames.next();
  expectMessage('relay-subscribed', subscribed);
  expect(subscribed['online'], isTrue);
  return frames;
}

/// Sends a relay request and returns its response frame.
Future<Map<String, Object?>> relayCall(Frames frames, int id, String method, String path, {String? token, Object? body, int? count}) async {
  frames.send(<String, Object?>{
    'type': 'request',
    'id': id,
    'method': method,
    'path': path,
    'headers': <String, String>{
      'Origin': 'http://ignored.example',
      if (token != null) 'Authorization': 'Bearer $token',
      if (count != null) 'X-Animus-Counter': '$count',
    },
    'body': ?body,
  });
  final Map<String, Object?> response = await frames.nextOf('response');
  expectMessage('relay-response', response);
  expect(response['id'], id);
  return response;
}

void main() {
  setUp(() async {
    server = await FakeServer.start(const FakeSettings(dataDirectory: '../schema/fixtures/export', catalogPageSize: 3), port: 0);
    client = HttpClient();
    counter = 0;
  });

  tearDown(() async {
    client.close(force: true);
    await server.close();
  });

  test('hello answers without a token', () async {
    final Reply reply = await call('GET', '/animus/v1/hello');
    expect(reply.status, 200);
    expectMessage('hello-response', reply.body);
    expect(reply.json['fingerprint'], fingerprint);
  });

  test('a pairing code carries both paths and pairs once', () async {
    final Map<String, Object?> minted = await control('POST', '/pair');
    final String code = minted['code']! as String;
    expect(minted['link'], '$origin/#/pair/$code');
    final Map<String, Object?> payload = payloadOf(code);
    expect(payload.keys.toList(), <String>['v', 'directUrl', 'relayUrl', 'fingerprint', 'serverKey', 'tokenId', 'secret', 'expiresAt', 'label', 'serverName']);
    expect(payload['directUrl'], base);
    expect(payload['relayUrl'], 'ws://127.0.0.1:${server.port}');
    expect(payload['fingerprint'], fingerprint);
    expect(payloadOf((await control('POST', '/pair?paths=relay'))['code']! as String).containsKey('directUrl'), isFalse);
    final Map<String, Object?> request = <String, Object?>{'tokenId': payload['tokenId'], 'secret': payload['secret'], 'client': 'test'};
    expectMessage('pair-request', request);
    final Reply first = await call('POST', '/animus/v1/pair', body: request);
    expect(first.status, 200);
    expectMessage('pair-response', first.body);
    expect(first.json['scopes'], <String>['read', 'validate', 'preview', 'apply:npcs', 'apply:behaviors', 'apply:gui', 'apply:rigs', 'terrain']);
    final Reply second = await call('POST', '/animus/v1/pair', body: request);
    expect(second.status, 401);
    expectMessage('error-response', second.body);
    expect(second.errorCode, 'pairing-invalid');
  });

  test('requests need a valid token', () async {
    final Reply missing = await call('GET', '/animus/v1/catalog');
    expect(missing.status, 401);
    expectMessage('error-response', missing.body);
    final Reply capabilities = await call('GET', '/animus/v1/capabilities', token: await pairToken());
    expectMessage('capabilities-response', capabilities.body);
  });

  test('should list registries, commands and PlaceholderAPI identifiers for completion', () async {
    final Reply reply = await call('GET', '/animus/v1/lookups', token: await pairToken());
    expectMessage('lookups-response', reply.body);
    final ServerLookups lookups = ServerLookups.fromJson(reply.json);
    expect(lookups.registries[RegistryKind.item], contains('diamond_sword'));
    expect(lookups.commands, contains('spawn'));
    expect(lookups.papi, contains('player'));
  });

  test('schemas are keyed by kind', () async {
    final Reply reply = await call('GET', '/animus/v1/schemas', token: await pairToken());
    expectMessage('schemas-response', reply.body);
    expect((reply.json['schemas']! as Map<String, Object?>).keys.toSet(),
        <String>{'npc', 'behavior', 'pack', 'completions', 'shop', 'inventory', 'rig', 'animation', 'menu', 'hologram', 'presets', 'server-behaviors'});
  });

  test('the catalog pages with after and filters by kind', () async {
    final String token = await pairToken();
    final List<String> seen = <String>[];
    String? after;
    int pages = 0;
    do {
      final Reply reply = await call('GET', '/animus/v1/catalog${after == null ? '' : '?after=$after'}', token: token);
      expect(reply.status, 200);
      expectMessage('catalog-response', reply.body);
      final List<Object?> documents = reply.json['documents']! as List<Object?>;
      expect(documents.length, lessThanOrEqualTo(3));
      for (final Object? entry in documents) {
        final Map<String, Object?> version = entry! as Map<String, Object?>;
        seen.add('${version['kind']} ${version['id']}');
      }
      after = reply.json['next'] as String?;
      pages++;
    } while (after != null);
    expect(pages, 4);
    expect(seen.length, 10);
    expect(seen.toSet().length, 10);
    final Reply npcs = await call('GET', '/animus/v1/catalog?kind=npc', token: token);
    expect((npcs.json['documents']! as List<Object?>).length, 3);
    expect(npcs.json['next'], isNull);
    final Reply unknown = await call('GET', '/animus/v1/catalog?limit=2', token: token);
    expect(unknown.status, 400);
  });

  test('documents are fetched by encoded or plain id', () async {
    final String token = await pairToken();
    final Map<String, Object?> greeter = await document(token, 'npc', 'examples/greeter');
    expect(greeter['path'], 'npcs/examples/greeter.json');
    expect(greeter['revision'], 1);
    final Reply plain = await call('GET', '/animus/v1/documents/npc/examples/greeter', token: token);
    expect(plain.json['hash'], greeter['hash']);
    final Map<String, Object?> keeper = await document(token, 'behavior', 'shop:keeper');
    expect(keeper['path'], 'packs/shop/behaviors/shop/keeper.json');
    final Map<String, Object?> broken = await document(token, 'npc', 'examples/broken');
    expect(broken['diagnostics'], isNotEmpty);
    final Reply missing = await call('GET', '/animus/v1/documents/npc/examples/nobody', token: token);
    expect(missing.status, 404);
    expectMessage('error-response', missing.body);
    final Reply skin = await call('GET', '/animus/v1/skins/${'a' * 64}/texture.png', token: token);
    expect(skin.status, 404);
  });

  test('validate reports diagnostics and writes nothing', () async {
    final String token = await pairToken();
    final Map<String, Object?> broken = await document(token, 'npc', 'examples/broken');
    final Map<String, Object?> greeter = await document(token, 'npc', 'examples/greeter');
    final Map<String, Object?> request = <String, Object?>{
      'documents': <Object?>[
        <String, Object?>{'kind': 'npc', 'id': 'examples/broken', 'baseRevision': 1, 'baseHash': broken['hash'], 'source': broken['source']},
        <String, Object?>{'kind': 'npc', 'id': 'examples/greeter', 'baseRevision': 1, 'baseHash': greeter['hash'], 'source': greeter['source']},
      ],
    };
    expectMessage('validate-request', request);
    final Reply reply = await call('POST', '/animus/v1/validate', token: token, body: request);
    expect(reply.status, 200);
    expectMessage('validate-response', reply.body);
    expect(reply.json['valid'], isFalse);
    final List<Object?> documents = reply.json['documents']! as List<Object?>;
    expect(((documents[0]! as Map<String, Object?>)['diagnostics']! as List<Object?>), isNotEmpty);
    expect(((documents[1]! as Map<String, Object?>)['diagnostics']! as List<Object?>), isEmpty);
    final Map<String, Object?> moved = Map<String, Object?>.of(greeter['source']! as Map<String, Object?>)..['id'] = 'elsewhere';
    final Reply mismatch = await call('POST', '/animus/v1/validate', token: token, body: <String, Object?>{
      'documents': <Object?>[
        <String, Object?>{'kind': 'npc', 'id': 'examples/greeter', 'baseRevision': 1, 'baseHash': greeter['hash'], 'source': moved},
      ],
    });
    expect(jsonEncode(mismatch.body), contains('id-mismatch'));
    expect((await document(token, 'npc', 'examples/greeter'))['hash'], greeter['hash']);
  });

  test('previews refuse shops and run NPCs with events', () async {
    final String token = await pairToken();
    final Frames events = await directEvents(token);
    final Map<String, Object?> bakery = await document(token, 'shop', 'shop:bakery');
    final Reply refused = await call('POST', '/animus/v1/previews', token: token, body: <String, Object?>{
      'documents': <Object?>[
        <String, Object?>{'kind': 'shop', 'id': 'shop:bakery', 'baseRevision': 1, 'baseHash': bakery['hash'], 'source': bakery['source']},
      ],
    });
    expect(refused.status, 422);
    expectMessage('error-response', refused.body);
    expect(refused.errorCode, 'preview-refused');
    expect(jsonEncode(refused.body), contains('"code":"preview-refused"'));
    final Map<String, Object?> greeter = await document(token, 'npc', 'examples/greeter');
    final Map<String, Object?> request = <String, Object?>{
      'documents': <Object?>[
        <String, Object?>{'kind': 'npc', 'id': 'examples/greeter', 'baseRevision': 1, 'baseHash': greeter['hash'], 'source': greeter['source']},
      ],
    };
    expectMessage('preview-request', request);
    final Reply started = await call('POST', '/animus/v1/previews', token: token, body: request);
    expect(started.status, 200);
    expectMessage('preview-response', started.body);
    expect(started.json['npcs'], <String>['examples/greeter']);
    expect(started.json['hidden'], <String>['examples/greeter'], reason: 'the stored greeter is the live NPC the candidate hides');
    final String previewId = started.json['previewId']! as String;
    final Map<String, Object?> running = await events.nextOf('preview');
    expectMessage('event', running);
    expect(running, <String, Object?>{'type': 'preview', 'previewId': previewId, 'state': 'running'});
    final Reply replaced = await call('POST', '/animus/v1/previews', token: token, body: <String, Object?>{...request, 'previewId': previewId});
    expect(replaced.json['previewId'], previewId);
    final Reply ended = await call('DELETE', '/animus/v1/previews/$previewId', token: token);
    expect(ended.status, 204);
    while (true) {
      final Map<String, Object?> event = await events.nextOf('preview');
      if (event['state'] == 'ended') break;
    }
    final Reply again = await call('DELETE', '/animus/v1/previews/$previewId', token: token);
    expect(again.status, 404);
    await events.socket.close();
  });

  test('apply bumps the revision, then a stale base conflicts', () async {
    final String token = await pairToken();
    final Map<String, Object?> greeter = await document(token, 'npc', 'examples/greeter');
    final Map<String, Object?> set = rename(greeter, 'Greeter Two', key('apply'));
    expectMessage('change-set', set);
    final Reply applied = await call('POST', '/animus/v1/changes', token: token, body: set);
    expect(applied.status, 200);
    expectMessage('change-applied', applied.body);
    final Map<String, Object?> version = (applied.json['documents']! as List<Object?>).single! as Map<String, Object?>;
    expect(version['revision'], 2);
    final Map<String, Object?> now = await document(token, 'npc', 'examples/greeter');
    expect(now['hash'], version['hash']);
    expect((now['source']! as Map<String, Object?>)['revision'], 2);
    final Reply conflict = await call('POST', '/animus/v1/changes', token: token, body: rename(greeter, 'Stale', key('stale')));
    expect(conflict.status, 409);
    expectMessage('change-conflict', conflict.body);
    final Map<String, Object?> current = ((conflict.json['conflicts']! as List<Object?>).single! as Map<String, Object?>)['current']! as Map<String, Object?>;
    expect(current['revision'], 2);
    expect(current['hash'], version['hash']);
    final Reply invalid = await call('POST', '/animus/v1/changes', token: token, body: <String, Object?>{
      'idempotencyKey': key('invalid'),
      'documents': <Object?>[
        <String, Object?>{'kind': 'npc', 'id': 'examples/greeter', 'baseRevision': 2, 'baseHash': version['hash'], 'source': <String, Object?>{'name': 'x'}},
      ],
    });
    expect(invalid.status, 422);
    expectMessage('change-invalid', invalid.body);
  });

  test('an idempotent retry returns the same result and status lookups follow', () async {
    final String token = await pairToken();
    final Reply unknown = await call('GET', '/animus/v1/changes/${key('retry')}', token: token);
    expectMessage('change-status-response', unknown.body);
    expect(unknown.json['status'], 'unknown');
    final Map<String, Object?> set = rename(await document(token, 'npc', 'examples/greeter'), 'Retry', key('retry'));
    final Reply first = await call('POST', '/animus/v1/changes', token: token, body: set);
    final Reply retry = await call('POST', '/animus/v1/changes', token: token, body: set);
    expect(retry.status, 200);
    expect(retry.body, first.body);
    final Reply status = await call('GET', '/animus/v1/changes/${key('retry')}', token: token);
    expectMessage('change-status-response', status.body);
    expect(status.json['status'], 'applied');
    expect(status.json['result'], first.body);
    final Reply reused = await call('POST', '/animus/v1/changes', token: token, body: rename(await document(token, 'npc', 'examples/greeter'), 'Other', key('retry')));
    expect(reused.status, 400);
    expect(reused.errorCode, 'invalid-request');
  });

  test('undo restores, and a later edit refuses the undo', () async {
    final String token = await pairToken();
    final Map<String, Object?> original = await document(token, 'npc', 'examples/greeter');
    final Reply applied = await call('POST', '/animus/v1/changes', token: token, body: rename(original, 'Undo me', key('undo1')));
    final String changeId = applied.json['changeId']! as String;
    final Map<String, Object?> undoRequest = <String, Object?>{'idempotencyKey': key('undo2')};
    expectMessage('undo-request', undoRequest);
    final Reply undone = await call('POST', '/animus/v1/changes/$changeId/undo', token: token, body: undoRequest);
    expect(undone.status, 200);
    expectMessage('change-applied', undone.body);
    expect((await document(token, 'npc', 'examples/greeter'))['hash'], original['hash']);
    final Reply second = await call('POST', '/animus/v1/changes', token: token, body: rename(original, 'Again', key('undo3')));
    expect(second.status, 200);
    final Map<String, Object?> edited = Map<String, Object?>.of(original['source']! as Map<String, Object?>)..['name'] = 'Server side';
    await control('POST', '/edit', <String, Object?>{'kind': 'npc', 'id': 'examples/greeter', 'source': edited});
    final Reply refused = await call('POST', '/animus/v1/changes/${second.json['changeId']}/undo', token: token, body: <String, Object?>{'idempotencyKey': key('undo4')});
    expect(refused.status, 409);
    expectMessage('change-conflict', refused.body);
    final Reply missing = await call('POST', '/animus/v1/changes/00000000-0000-4000-8000-000000000000/undo', token: token, body: <String, Object?>{'idempotencyKey': key('undo5')});
    expect(missing.status, 404);
  });

  /// A change set moving [current] (a document-response) to npc [id].
  Map<String, Object?> moveTo(Map<String, Object?> current, String id, String idempotencyKey) {
    final int slash = id.lastIndexOf('/');
    final Map<String, Object?> source = Map<String, Object?>.of(current['source']! as Map<String, Object?>)..['group'] = slash < 0 ? '' : id.substring(0, slash);
    return <String, Object?>{
      'idempotencyKey': idempotencyKey,
      'documents': <Object?>[
        <String, Object?>{
          'kind': 'npc',
          'id': id,
          'from': <String, Object?>{'id': current['id']},
          'baseRevision': current['revision'],
          'baseHash': current['hash'],
          'source': source,
        },
      ],
    };
  }

  test('a move writes the new path, drops the old one and keeps the revision chain', () async {
    final String token = await pairToken();
    final Frames events = await directEvents(token);
    await Future<void>.delayed(const Duration(milliseconds: 100));
    final Map<String, Object?> greeter = await document(token, 'npc', 'examples/greeter');
    final Map<String, Object?> set = moveTo(greeter, 'town/market/greeter', key('move1'));
    expectMessage('change-set', set);
    final Reply applied = await call('POST', '/animus/v1/changes', token: token, body: set);
    expect(applied.status, 200, reason: jsonEncode(applied.body));
    expectMessage('change-applied', applied.body);
    final Map<String, Object?> version = (applied.json['documents']! as List<Object?>).single! as Map<String, Object?>;
    expect(version['id'], 'town/market/greeter');
    expect(version['from'], <String, Object?>{'id': 'examples/greeter'});
    expect(version['revision'], (greeter['revision']! as int) + 1);
    final Map<String, Object?> moved = await document(token, 'npc', 'town/market/greeter');
    expect(moved['path'], 'npcs/town/market/greeter.json');
    expect((await call('GET', '/animus/v1/documents/npc/examples%2Fgreeter', token: token)).status, 404);
    final Map<String, Object?> catalog = await events.nextOf('catalog');
    expectMessage('event', catalog);
    expect(((catalog['documents']! as List<Object?>).single! as Map<String, Object?>)['id'], 'town/market/greeter');
    expect(catalog['deleted'], <Object?>[<String, Object?>{'kind': 'npc', 'id': 'examples/greeter'}]);
    final Reply undone = await call('POST', '/animus/v1/changes/${applied.json['changeId']}/undo', token: token, body: <String, Object?>{'idempotencyKey': key('move2')});
    expect(undone.status, 200, reason: jsonEncode(undone.body));
    expectMessage('change-applied', undone.body);
    expect(undone.json['documents'], <Object?>[
      <String, Object?>{'kind': 'npc', 'id': 'examples/greeter', 'revision': greeter['revision'], 'hash': greeter['hash'], 'from': <String, Object?>{'id': 'town/market/greeter'}},
    ]);
    expect((await document(token, 'npc', 'examples/greeter'))['hash'], greeter['hash']);
    expect((await call('GET', '/animus/v1/documents/npc/town%2Fmarket%2Fgreeter', token: token)).status, 404);
  });

  test('a move conflicts on a changed base and on an occupied target', () async {
    final String token = await pairToken();
    final Map<String, Object?> greeter = await document(token, 'npc', 'examples/greeter');
    await call('POST', '/animus/v1/changes', token: token, body: rename(greeter, 'Changed', key('movec1')));
    final Reply stale = await call('POST', '/animus/v1/changes', token: token, body: moveTo(greeter, 'town/greeter', key('movec2')));
    expect(stale.status, 409);
    expectMessage('change-conflict', stale.body);
    expect(((stale.json['conflicts']! as List<Object?>).single! as Map<String, Object?>)['id'], 'examples/greeter');
    final Map<String, Object?> current = await document(token, 'npc', 'examples/greeter');
    final Reply occupied = await call('POST', '/animus/v1/changes', token: token, body: moveTo(current, 'examples/shopkeeper', key('movec3')));
    expect(occupied.status, 409);
    expectMessage('change-conflict', occupied.body);
    final Map<String, Object?> conflict = (occupied.json['conflicts']! as List<Object?>).single! as Map<String, Object?>;
    expect(conflict['id'], 'examples/shopkeeper');
    expect(conflict['baseRevision'], isNull);
    final Reply refused = await call('POST', '/animus/v1/changes', token: token, body: <String, Object?>{
      'idempotencyKey': key('movec4'),
      'documents': <Object?>[
        <String, Object?>{'kind': 'npc', 'id': 'town/greeter', 'from': <String, Object?>{'id': 'examples/greeter'}, 'baseRevision': null, 'baseHash': null, 'source': <String, Object?>{}},
      ],
    });
    expect(refused.status, 400);
  });

  test('a replayed counter is refused', () async {
    final String token = await pairToken();
    final Map<String, Object?> body = <String, Object?>{
      'documents': <Object?>[
        <String, Object?>{'kind': 'npc', 'id': 'examples/new', 'baseRevision': null, 'baseHash': null, 'source': <String, Object?>{}},
      ],
    };
    expect((await call('POST', '/animus/v1/validate', token: token, body: body, count: 5)).status, 200);
    final Reply replayed = await call('POST', '/animus/v1/validate', token: token, body: body, count: 5);
    expect(replayed.status, 409);
    expectMessage('error-response', replayed.body);
    expect(replayed.errorCode, 'counter-replayed');
    final Reply missing = await call('POST', '/animus/v1/validate', token: token, body: body, counted: false);
    expect(missing.status, 400);
    expect((await call('POST', '/animus/v1/validate', token: token, body: body, count: 6)).status, 200);
  });

  test('origins are checked exactly, with CORS for allowed ones', () async {
    final Reply refused = await call('GET', '/animus/v1/hello', withOrigin: 'http://127.0.0.1:9999');
    expect(refused.status, 403);
    expectMessage('error-response', refused.body);
    expect(refused.errorCode, 'origin-not-allowed');
    expect(refused.headers.value('access-control-allow-origin'), isNull);
    final Reply allowed = await call('GET', '/animus/v1/hello');
    expect(allowed.headers.value('access-control-allow-origin'), origin);
    final Reply preflight = await call('OPTIONS', '/animus/v1/changes');
    expect(preflight.status, 204);
    expect(preflight.headers.value('access-control-allow-private-network'), 'true');
    expect(preflight.headers.value('access-control-allow-headers'), contains('X-Animus-Counter'));
  });

  test('event streams authenticate and carry catalog, change and closing events', () async {
    final WebSocket refused = await WebSocket.connect('ws://127.0.0.1:${server.port}/animus/v1/events', headers: <String, Object>{'Origin': origin});
    final Frames rejected = Frames(refused);
    rejected.send(<String, Object?>{'type': 'auth', 'token': 'anm_tok_00000000.${'A' * 43}'});
    expect(await rejected.closed, 4401);
    final String token = await pairToken();
    final Frames events = await directEvents(token);
    await Future<void>.delayed(const Duration(milliseconds: 100));
    final Map<String, Object?> greeter = await document(token, 'npc', 'examples/greeter');
    final Map<String, Object?> source = Map<String, Object?>.of(greeter['source']! as Map<String, Object?>)..['name'] = 'Edited';
    final Map<String, Object?> edited = await control('POST', '/edit', <String, Object?>{'kind': 'npc', 'id': 'examples/greeter', 'source': source});
    expect(edited['revision'], 2);
    final Map<String, Object?> catalog = await events.nextOf('catalog');
    expectMessage('event', catalog);
    expect(((catalog['documents']! as List<Object?>).single! as Map<String, Object?>)['revision'], 2);
    await call('POST', '/animus/v1/changes', token: token, body: rename(await document(token, 'npc', 'examples/greeter'), 'Applied', key('events')));
    final Map<String, Object?> change = await events.nextOf('change');
    expectMessage('event', change);
    final Map<String, Object?> state = await control('GET', '/state');
    final String tokenId = ((state['tokens']! as List<Object?>).single! as Map<String, Object?>)['id']! as String;
    await control('POST', '/revoke', <String, Object?>{'tokenId': tokenId});
    final Map<String, Object?> closing = await events.nextOf('closing');
    expectMessage('event', closing);
    expect(closing['reason'], 'token-revoked');
    await events.closed;
    expect((await call('GET', '/animus/v1/catalog', token: token)).errorCode, 'token-revoked');
  });

  test('the relay emulation answers requests and streams events', () async {
    await expectLater(WebSocket.connect('${server.relayUrl}/app', headers: <String, Object>{'Origin': 'http://evil.example'}),
        throwsA(isA<WebSocketException>()));
    final String token = await pairToken();
    final Frames frames = await relay();
    final Map<String, Object?> hello = await relayCall(frames, 1, 'GET', '/v1/hello');
    expectMessage('hello-response', hello['body']);
    final Map<String, Object?> catalog = await relayCall(frames, 2, 'GET', '/v1/catalog?kind=npc', token: token);
    expectMessage('catalog-response', catalog['body']);
    final Map<String, Object?> unauthorized = await relayCall(frames, 3, 'GET', '/v1/catalog');
    expect(unauthorized['status'], 401);
    expectMessage('error-response', unauthorized['body']);
    final Map<String, Object?> opened = await relayCall(frames, 4, 'GET', '/v1/events', token: token);
    expect(opened['status'], 200);
    final Map<String, Object?> greeter = await document(token, 'npc', 'examples/greeter');
    final Map<String, Object?> applied = await relayCall(frames, 5, 'POST', '/v1/changes', token: token, count: 100, body: rename(greeter, 'Relayed', key('relay')));
    expect(applied['status'], 200);
    expectMessage('change-applied', applied['body']);
    final Map<String, Object?> event = await frames.nextOf('event');
    expectMessage('relay-event', event);
    expect(event['id'], 4);
    final Map<String, Object?> replayed = await relayCall(frames, 6, 'POST', '/v1/validate', token: token, count: 100, body: <String, Object?>{'documents': <Object?>[]});
    expect(replayed['status'], 409);
    await control('POST', '/offline', <String, Object?>{'seconds': 30});
    final Map<String, Object?> ended = await frames.nextOf('close');
    expectMessage('relay-close', ended);
    expect(ended['id'], 4);
    final Map<String, Object?> offline = await frames.nextOf('status');
    expectMessage('relay-status', offline);
    expect(offline['online'], isFalse);
    final Map<String, Object?> down = await relayCall(frames, 7, 'GET', '/v1/hello');
    expect(down['status'], 503);
    expect(((down['body']! as Map<String, Object?>)['error']! as Map<String, Object?>)['code'], 'server-offline');
    await control('POST', '/restore');
    final Map<String, Object?> online = await frames.nextOf('status');
    expect(online['online'], isTrue);
    expect((await relayCall(frames, 8, 'GET', '/v1/hello'))['status'], 200);
    await frames.socket.close();
  });

  test('drop makes a path unreachable until restore', () async {
    final String token = await pairToken();
    final Frames events = await directEvents(token);
    final Frames frames = await relay();
    await Future<void>.delayed(const Duration(milliseconds: 100));
    await control('POST', '/drop', <String, Object?>{'path': 'all', 'seconds': 60});
    await events.closed;
    await frames.closed;
    await expectLater(call('GET', '/animus/v1/hello'), throwsA(anyOf(isA<HttpException>(), isA<SocketException>())));
    await expectLater(WebSocket.connect('${server.relayUrl}/app', headers: <String, Object>{'Origin': origin}),
        throwsA(anything));
    final Map<String, Object?> state = await control('GET', '/state');
    expect(state['paths'], <String, Object?>{'direct': 'down', 'relay': 'down', 'server': 'online'});
    await control('POST', '/restore');
    expect((await call('GET', '/animus/v1/hello')).status, 200);
    final Frames back = await relay();
    await back.socket.close();
  });

  test('reset reloads the data folder and forgets tokens', () async {
    final String token = await pairToken();
    await call('POST', '/animus/v1/changes', token: token, body: rename(await document(token, 'npc', 'examples/greeter'), 'Reset', key('reset')));
    final Map<String, Object?> before = await control('GET', '/state');
    expect(before['appliedChanges'], hasLength(1));
    await control('POST', '/reset');
    final Map<String, Object?> after = await control('GET', '/state');
    expect(after['tokens'], isEmpty);
    expect(after['appliedChanges'], isEmpty);
    expect((await call('GET', '/animus/v1/catalog', token: token)).status, 401);
  });

  group('test controls', () {
    test('expire ends tokens now, or moves their expiry', () async {
      final String token = await pairToken();
      final Frames events = await directEvents(token);
      await Future<void>.delayed(const Duration(milliseconds: 100));
      final Map<String, Object?> later = await control('POST', '/expire', <String, Object?>{'inDays': 2});
      expect((later['expired']! as List<Object?>), hasLength(1));
      expect((await call('GET', '/animus/v1/catalog', token: token)).status, 200);
      await control('POST', '/expire');
      final Map<String, Object?> closing = await events.nextOf('closing');
      expect(closing['reason'], 'token-expired');
      expect((await call('GET', '/animus/v1/catalog', token: token)).errorCode, 'token-revoked');
    });

    test('fail answers the next requests with a code, on a route when given', () async {
      final String token = await pairToken();
      await control('POST', '/fail?code=scope-missing&times=1&route=/v1/changes');
      expect((await call('GET', '/animus/v1/catalog', token: token)).status, 200, reason: 'another route');
      expect((await call('GET', '/animus/v1/hello')).status, 200, reason: 'hello never fails');
      final Reply refused = await call('POST', '/animus/v1/changes', token: token, body: rename(await document(token, 'npc', 'examples/greeter'), 'X', key('fail')));
      expect(refused.status, 403);
      expectMessage('error-response', refused.body);
      expect(refused.errorCode, 'scope-missing');
      expect((await call('POST', '/animus/v1/changes', token: token, body: rename(await document(token, 'npc', 'examples/greeter'), 'X', key('fail2')))).status, 200);
      expect((await call('POST', '/__fake/fail?code=nonsense', withOrigin: null)).status, 400);
    });

    test('drop-answer writes the change set but loses the answer and its change event', () async {
      final String token = await pairToken();
      final Frames events = await directEvents(token);
      await Future<void>.delayed(const Duration(milliseconds: 100));
      await control('POST', '/drop-answer');
      await expectLater(
        call('POST', '/animus/v1/changes', token: token, body: rename(await document(token, 'npc', 'examples/greeter'), 'Lost', key('lost'))),
        throwsA(isA<Object>()),
      );
      expect((await events.nextOf('catalog'))['type'], 'catalog');
      final Reply status = await call('GET', '/animus/v1/changes/${key('lost')}', token: token);
      expectMessage('change-status-response', status.body);
      expect(status.json['status'], 'applied');
      // Only one change set was dropped; the next answers as usual.
      expect((await call('POST', '/animus/v1/changes', token: token, body: rename(await document(token, 'npc', 'examples/greeter'), 'Kept', key('kept')))).status, 200);
      await expectLater(events.nextOf('change').then((Map<String, Object?> event) => ((event['result']! as Map<String, Object?>)['idempotencyKey'])), completion(key('kept')));
    });

    test('drop-answer over the relay closes the request instead of answering', () async {
      final String token = await pairToken();
      final Frames frames = await relay();
      await control('POST', '/drop-answer');
      final Map<String, Object?> greeter = await document(token, 'npc', 'examples/greeter');
      frames.send(<String, Object?>{
        'type': 'request',
        'id': 9,
        'method': 'POST',
        'path': '/v1/changes',
        'headers': <String, String>{'Authorization': 'Bearer $token', 'X-Animus-Counter': '500'},
        'body': rename(greeter, 'Lost', key('relay-lost')),
      });
      final Map<String, Object?> closed = await frames.nextOf('close');
      expectMessage('relay-close', closed);
      expect(closed['id'], 9);
    });

    test('relay-error sends the error frame and closes every relay connection', () async {
      final Frames frames = await relay();
      final Map<String, Object?> sent = await control('POST', '/relay-error?code=capacity');
      expect(sent['closed'], 1);
      final Map<String, Object?> error = await frames.nextOf('error');
      expectMessage('relay-error', error);
      expect(error['code'], 'capacity');
      await frames.closed;
      expect((await call('POST', '/__fake/relay-error?code=nonsense', withOrigin: null)).status, 400);
    });

    test('limit closes every event stream with the limit reason', () async {
      final String token = await pairToken();
      final Frames events = await directEvents(token);
      await Future<void>.delayed(const Duration(milliseconds: 100));
      await control('POST', '/limit');
      final Map<String, Object?> closing = await events.nextOf('closing');
      expectMessage('event', closing);
      expect(closing['reason'], 'limit');
      await events.closed;
    });

    test('player online=false refuses previews with player-offline', () async {
      final String token = await pairToken();
      final Map<String, Object?> greeter = await document(token, 'npc', 'examples/greeter');
      Future<Reply> preview() => call('POST', '/animus/v1/previews', token: token, body: <String, Object?>{
            'documents': <Object?>[
              <String, Object?>{'kind': 'npc', 'id': 'examples/greeter', 'baseRevision': greeter['revision'], 'baseHash': greeter['hash'], 'source': greeter['source']},
            ],
          });
      await control('POST', '/player?online=false');
      final Reply refused = await preview();
      expectMessage('error-response', refused.body);
      expect(refused.errorCode, 'player-offline');
      expect((await control('GET', '/state'))['controls'], containsPair('playerOnline', false));
      await control('POST', '/player?online=true');
      expect((await preview()).status, 200);
    });
  });

  group('imports', () {
    Future<String> importToken() async {
      final Map<String, Object?> minted = await control('POST', '/pair?scopes=import');
      final Map<String, Object?> payload = payloadOf(minted['code']! as String);
      final Reply reply = await call('POST', '/animus/v1/pair', body: <String, Object?>{'tokenId': payload['tokenId'], 'secret': payload['secret']});
      expect(reply.json['scopes'], contains('import'));
      return reply.json['token']! as String;
    }

    Future<Reply> uploadRaw(String token, String query, List<int> bytes) async {
      final HttpClientRequest request = await client.openUrl('POST', Uri.parse('$base/animus/v1/imports$query'));
      request.headers
        ..set('Origin', origin)
        ..set('Authorization', 'Bearer $token')
        ..set('X-Animus-Counter', '${++counter}')
        ..contentType = ContentType.binary;
      request.add(bytes);
      final HttpClientResponse response = await request.close();
      final String text = await utf8.decoder.bind(response).join();
      return Reply(response.statusCode, response.headers, text.isEmpty ? null : jsonDecode(text));
    }

    Map<String, Object?> review(Reply reply) {
      expect(reply.status, 200, reason: jsonEncode(reply.body));
      expectMessage('import-review', reply.body);
      return reply.json;
    }

    test('upload, stage, apply, accept and roll back a Citizens save with catalog events', () async {
      final String token = await importToken();
      final Frames events = await directEvents(token);
      expect(review(await call('GET', '/animus/v1/imports', token: token))['state'], 'none');
      final List<int> save = File('../schema/fixtures/citizens/runtime.yml').readAsBytesSync();
      expect((review(await uploadRaw(token, '?file=citizens', save))['uploads']! as Map<String, Object?>)['citizens'], 'imports/saves.yml');
      final Map<String, Object?> staged = review(await call('POST', '/animus/v1/imports/stage', token: token, body: <String, Object?>{}));
      expect(staged['state'], 'staged');
      final String importId = (staged['citizens']! as Map<String, Object?>)['importId']! as String;
      expect(((staged['reports']! as Map<String, Object?>)['citizens']! as Map<String, Object?>)['records'], 6);
      expect((await call('POST', '/animus/v1/imports/${'0' * 64}/apply', token: token)).errorCode, 'conflict');
      final Map<String, Object?> applied = review(await call('POST', '/animus/v1/imports/$importId/apply', token: token));
      expect(applied['state'], 'applied');
      expect(applied['npcs'], 6);
      final Map<String, Object?> catalog = await events.nextOf('catalog');
      expect(catalog['documents'], isNotEmpty);
      final int waiting = (applied['pending']! as Map<String, Object?>).values.fold(0, (int sum, Object? n) => sum + (n! as int));
      expect(waiting, greaterThan(0));
      final Map<String, Object?> accepted = review(await call('POST', '/animus/v1/imports/$importId/accept', token: token, body: <String, Object?>{'categories': <String>['all']}));
      expect((accepted['pending']! as Map<String, Object?>).values.every((Object? n) => n == 0), isTrue);
      final Map<String, Object?> rolled = review(await call('POST', '/animus/v1/imports/$importId/rollback', token: token));
      expect(rolled['state'], 'rolled-back');
      await events.nextOf('catalog');
      final Map<String, Object?> deleted = await events.nextOf('catalog');
      expect(deleted['deleted'], isNotEmpty);
      await events.socket.close();
    });

    test('uploads over the relay as base64 and refuses bad input and missing scope', () async {
      final String token = await importToken();
      final Frames frames = await relay();
      final Map<String, Object?> response = await relayCall(frames, 1, 'POST', '/v1/imports?file=dtltraders&name=guis.yml', token: token, count: ++counter,
          body: base64Encode(utf8.encode('guis: {}\n')));
      expect(response['status'], 200);
      expectMessage('import-review', response['body']);
      expect(((response['body']! as Map<String, Object?>)['uploads']! as Map<String, Object?>)['dtltraders'], 'imports/dtltraders');
      await frames.socket.close();
      expect((await uploadRaw(token, '?file=nope', <int>[1])).errorCode, 'invalid-request');
      expect((await uploadRaw(token, '?file=dtltraders&name=../x.yml', <int>[1])).errorCode, 'invalid-request');
      expect((await call('POST', '/animus/v1/imports/stage', token: token, body: <String, Object?>{'mode': 'duplicate'})).errorCode, 'invalid-request');
      final String plain = await pairToken();
      expect((await call('GET', '/animus/v1/imports', token: plain)).errorCode, 'scope-missing');
    });
  });
}
