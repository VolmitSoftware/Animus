@TestOn('vm')
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:animus_editor/logic/json_schema.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/logic/suggest/completion_sources.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/services/server/server_proof.dart';
import 'package:cryptography/cryptography.dart';
import 'package:test/test.dart';

import '../tool/fake_server/documents.dart';
import '../tool/fake_server/identity.dart';
import '../tool/fake_server/scale.dart';
import '../tool/fake_server/server.dart';

const String origin = 'http://127.0.0.1:8098';
const String fingerprint = fakeFingerprint;

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

const FakeSettings fixtureSettings = FakeSettings(dataDirectory: '../schema/fixtures/export', catalogPageSize: 3);

/// What setUp starts; the scale group swaps in a generated data folder.
FakeSettings settings = fixtureSettings;
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

/// Mints a pairing code (with the `/__fake/pair` [query]) and exchanges it.
Future<Reply> pair([String query = '']) async {
  final Map<String, Object?> minted = await control('POST', '/pair$query');
  final Map<String, Object?> payload = payloadOf(minted['code']! as String);
  final Reply reply = await call('POST', '/animus/v1/pair', body: <String, Object?>{'tokenId': payload['tokenId'], 'secret': payload['secret']});
  expect(reply.status, 200);
  expectMessage('pair-response', reply.body);
  return reply;
}

Future<String> pairToken([String query = '']) async => (await pair(query)).json['token']! as String;

String tokenIdOf(String token) => token.substring(4, token.indexOf('.'));

/// Every page of the catalog, with the extra [query] such as `kind=npc`.
Future<List<List<Map<String, Object?>>>> catalogPages(String token, [String query = '']) async {
  final List<List<Map<String, Object?>>> pages = <List<Map<String, Object?>>>[];
  String? after;
  do {
    final String parameters = <String>[if (query.isNotEmpty) query, if (after != null) 'after=$after'].join('&');
    final Reply reply = await call('GET', '/animus/v1/catalog${parameters.isEmpty ? '' : '?$parameters'}', token: token);
    expect(reply.status, 200, reason: jsonEncode(reply.body));
    pages.add((reply.json['documents']! as List<Object?>).cast<Map<String, Object?>>());
    after = reply.json['next'] as String?;
  } while (after != null);
  return pages;
}

/// The bytes [entries] take as JSON, as the server counts its answer budgets.
int encodedBytes(Iterable<Object?> entries) => entries.fold(0, (int sum, Object? entry) => sum + utf8.encode(jsonEncode(entry)).length);

/// `kind id` of each document or ref.
List<String> refsOf(Object? entries) => <String>[
      for (final Map<String, Object?> entry in (entries! as List<Object?>).cast<Map<String, Object?>>()) '${entry['kind']} ${entry['id']}',
    ];

Map<String, Object?> errorOf(Object? body) => (body! as Map<String, Object?>)['error']! as Map<String, Object?>;

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
Future<Map<String, Object?>> relayCall(Frames frames, int id, String method, String path,
    {String? token, Object? body, int? count, Map<String, String> headers = const <String, String>{}}) async {
  frames.send(<String, Object?>{
    'type': 'request',
    'id': id,
    'method': method,
    'path': path,
    'headers': <String, String>{
      'Origin': 'http://ignored.example',
      if (token != null) 'Authorization': 'Bearer $token',
      if (count != null) 'X-Animus-Counter': '$count',
      ...headers,
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
    server = await FakeServer.start(settings, port: 0);
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
    expect(first.json['scopes'], <String>['read', 'validate', 'preview', 'apply:npcs', 'apply:behaviors', 'apply:gui', 'apply:rigs', 'import', 'spawn', 'terrain']);
    expect(first.json['serverName'], 'Animus fake server');
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

  test('should report the integration plugins the plugins control names as running', () async {
    final String token = await pairToken();
    Future<Map<String, Object?>> features() async {
      final Reply reply = await call('GET', '/animus/v1/capabilities', token: token);
      expectMessage('capabilities-response', reply.body);
      return reply.json['features']! as Map<String, Object?>;
    }

    expect(await features(), isNot(contains('modelengine')));
    await control('POST', '/plugins?running=modelengine');
    expect(await features(), containsPair('modelengine', <String, Object?>{'available': true}));
    expect(await features(), isNot(contains('mythicmobs')));
    expect((await control('GET', '/state'))['controls'], containsPair('runningPlugins', <String>['modelengine']));
    await control('POST', '/reset');
    expect((await control('GET', '/state'))['controls'], containsPair('runningPlugins', <String>[]));
  });

  test('should list registries, commands and PlaceholderAPI identifiers for completion', () async {
    final Reply reply = await call('GET', '/animus/v1/lookups', token: await pairToken());
    expectMessage('lookups-response', reply.body);
    final ServerLookups lookups = ServerLookups.fromJson(reply.json);
    expect(lookups.registries[RegistryKind.item], contains('diamond_sword'));
    expect(lookups.commands, contains('spawn'));
    expect(lookups.papi, contains('player'));
  });

  test('terrain in a world the server has not loaded answers world-unloaded', () async {
    final String token = await pairToken();
    for (final String path in <String>['surface?world=world_the_end&tx=0&tz=0', 'blocks?world=world_the_end&cx=0&cz=0&y=60&height=4']) {
      final Reply refused = await call('GET', '/animus/v1/terrain/$path', token: token);
      expect(refused.status, 404, reason: path);
      expectMessage('error-response', refused.body);
      expect(refused.errorCode, 'world-unloaded', reason: path);
    }
  });

  test('a spawned NPC answers where it is now, and the ground there opens while it stands there', () async {
    final String token = await pairToken();
    final Reply idle = await call('GET', '/animus/v1/terrain/position?npc=examples/greeter', token: token);
    expectMessage('terrain-position-response', idle.body);
    expect(idle.json['active'], isFalse);
    Future<String> chunk() async => (await call('GET', '/animus/v1/terrain/blocks?world=world&cx=-38&cz=0&y=60&height=4', token: token)).json['status']! as String;
    expect(await chunk(), 'forbidden');
    await control('POST', '/live', <String, Object?>{'npc': 'examples/greeter', 'world': 'world', 'x': -600.5, 'y': 64, 'z': 0.5});
    final Reply live = await call('GET', '/animus/v1/terrain/position?npc=examples/greeter', token: token);
    expectMessage('terrain-position-response', live.body);
    expect(live.json, <String, Object?>{'npc': 'examples/greeter', 'active': true, 'world': 'world', 'x': -600.5, 'y': 64.0, 'z': 0.5});
    expect(await chunk(), 'ok');
    await control('POST', '/live', <String, Object?>{'npc': 'examples/greeter'});
    expect(await chunk(), 'forbidden');
    expect((await call('GET', '/animus/v1/terrain/position?npc=examples/nobody', token: token)).errorCode, 'not-found');
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

  Map<String, Object?> withoutRevision(Object? source) => Map<String, Object?>.of(source! as Map<String, Object?>)..remove('revision');

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
    final Map<String, Object?> back = await document(token, 'npc', 'examples/greeter');
    expect(withoutRevision(back['source']), withoutRevision(original['source']), reason: 'the document is back as it was');
    expect(back['revision'], (original['revision']! as int) + 2, reason: 'an undo is a new write: the revision goes up');
    expect(((undone.json['documents']! as List<Object?>).single! as Map<String, Object?>)['hash'], back['hash']);
    final Reply second = await call('POST', '/animus/v1/changes', token: token, body: rename(back, 'Again', key('undo3')));
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
    expect(catalog['deleted'], <Object?>[<String, Object?>{'kind': 'npc', 'id': 'examples/greeter', 'source': 'editor'}]);
    final Reply undone = await call('POST', '/animus/v1/changes/${applied.json['changeId']}/undo', token: token, body: <String, Object?>{'idempotencyKey': key('move2')});
    expect(undone.status, 200, reason: jsonEncode(undone.body));
    expectMessage('change-applied', undone.body);
    final Map<String, Object?> back = await document(token, 'npc', 'examples/greeter');
    expect(undone.json['documents'], <Object?>[
      <String, Object?>{'kind': 'npc', 'id': 'examples/greeter', 'revision': (greeter['revision']! as int) + 2, 'hash': back['hash'], 'from': <String, Object?>{'id': 'town/market/greeter'}},
    ], reason: 'moving back is a write after the move');
    expect(withoutRevision(back['source']), withoutRevision(greeter['source']));
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

  test('the fake key pair derives the pinned key and fingerprint', () async {
    final SimplePublicKey key = await (await Ed25519().newKeyPairFromSeed(fakeSeed)).extractPublicKey();
    expect(base64UrlNoPadding(key.bytes), fakeServerKey);
    expect(server.identity.fingerprint, fakeFingerprint);
  });

  test('the relay emulation signs a challenged hello with the server key', () async {
    final Frames frames = await relay();
    final String challenge = newChallenge();
    final Map<String, Object?> hello = await relayCall(frames, 1, 'GET', '/v1/hello', headers: <String, String>{challengeHeader: challenge});
    final Map<String, String> headers = (hello['headers']! as Map<String, Object?>).cast<String, String>();
    expect(await verifyServerProof(headers, challenge: challenge, fingerprint: fakeFingerprint), isTrue);
    expect(await verifyServerProof(headers, challenge: newChallenge(), fingerprint: fakeFingerprint), isFalse);
    await frames.socket.close();
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
      expect((refused.json['error']! as Map<String, Object?>).containsKey('scope'), isFalse, reason: 'no scope named unless asked');
      expect((await call('POST', '/animus/v1/changes', token: token, body: rename(await document(token, 'npc', 'examples/greeter'), 'X', key('fail2')))).status, 200);
      await control('POST', '/fail?code=scope-missing&times=1&route=/v1/changes&scope=apply:commands');
      final Reply named = await call('POST', '/animus/v1/changes', token: token, body: rename(await document(token, 'npc', 'examples/greeter'), 'Y', key('fail3')));
      expectMessage('error-response', named.body);
      expect((named.json['error']! as Map<String, Object?>)['scope'], 'apply:commands');
      expect((await call('POST', '/__fake/fail?code=busy&scope=read', withOrigin: null)).status, 400, reason: 'only scope-missing names a scope');
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

    test('upload, stage, apply and roll back a Citizens save with catalog events', () async {
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
      expect((applied['lines']! as List<Object?>).first, contains('NPCs spawn with their patrols'));
      expect(applied.containsKey('pending'), isFalse, reason: 'nothing waits once applied');
      expect((await call('POST', '/animus/v1/imports/$importId/accept', token: token, body: <String, Object?>{'categories': <String>['all']})).errorCode, 'not-found');
      final Map<String, Object?> rolled = review(await call('POST', '/animus/v1/imports/$importId/rollback', token: token));
      expect(rolled['state'], 'rolled-back');
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
      final Reply unscoped = await call('GET', '/animus/v1/imports', token: await pairToken('?scopes=read'));
      expect(unscoped.errorCode, 'scope-missing');
      expectMessage('error-response', unscoped.body);
      expect((unscoped.json['error']! as Map<String, Object?>)['scope'], 'import', reason: 'the refusal names the scope it lacks');
    });
  });

  group('summaries and batch reads', () {
    const Map<String, Object?> greeterSummary = <String, Object?>{
      'name': 'Greeter',
      'type': 'minecraft:player',
      'world': 'world',
      'x': -7.5,
      'y': 64,
      'z': 0.5,
      'spawned': true,
      'folder': 'examples',
      'features': <String>['behavior'],
    };

    Future<Reply> read(String token, Object body) => call('POST', '/animus/v1/documents/read', token: token, counted: false, body: body);

    test('the catalog, a single read and the batch read carry the same summary', () async {
      final String token = await pairToken();
      final List<Map<String, Object?>> npcs = (await catalogPages(token, 'kind=npc')).expand((List<Map<String, Object?>> page) => page).toList();
      expect(npcs.firstWhere((Map<String, Object?> entry) => entry['id'] == 'examples/greeter')['summary'], greeterSummary);
      final Map<String, Object?> single = await document(token, 'npc', 'examples/greeter');
      expect(single['summary'], greeterSummary);
      final Reply batch = await read(token, <String, Object?>{
        'refs': <Object?>[
          <String, Object?>{'kind': 'npc', 'id': 'examples/greeter'},
          <String, Object?>{'kind': 'shop', 'id': 'shop:bakery'},
        ],
      });
      expect(batch.status, 200);
      expectMessage('documents-read-response', batch.body);
      final List<Object?> documents = batch.json['documents']! as List<Object?>;
      expect(documents.first, single, reason: 'a batch entry is the single read answer');
      expect((documents.last! as Map<String, Object?>)['summary'], <String, Object?>{'name': 'Bakery', 'offers': 6, 'pages': 1});
    });

    test('a stored file that is not JSON is listed with the summary of an empty document', () async {
      server.api.documents.write(const Located(DocumentKind.npc, 'examples/garbled', 'npcs/examples/garbled.json'), '{"name": ');
      server.api.documents.write(const Located(DocumentKind.shop, 'shop:garbled', 'shops/shop/garbled.json'), '{"title": ');
      final String token = await pairToken();
      // As EditorSummaries.of over an empty object: an NPC still has its folder and features.
      const Map<String, Object?> npcSummary = <String, Object?>{'folder': 'examples', 'features': <String>[]};
      final List<Map<String, Object?>> entries = (await catalogPages(token)).expand((List<Map<String, Object?>> page) => page).toList();
      expect(entries.firstWhere((Map<String, Object?> entry) => entry['id'] == 'examples/garbled')['summary'], npcSummary);
      expect(entries.firstWhere((Map<String, Object?> entry) => entry['id'] == 'shop:garbled')['summary'], <String, Object?>{});
      final Reply single = await call('GET', '/animus/v1/documents/npc/examples/garbled', token: token);
      expectMessage('document-response', single.body);
      expect(single.json['summary'], npcSummary);
      expect(single.json['source'], <String, Object?>{});
      expect(jsonEncode(single.json['diagnostics']), contains('"code":"json"'));
    });

    test('the batch read answers in request order, reports each bad ref and takes no counter', () async {
      final String token = await pairToken();
      final Reply reply = await call('POST', '/animus/v1/documents/read', token: token, count: 50, body: <String, Object?>{
        'refs': <Object?>[
          <String, Object?>{'kind': 'shop', 'id': 'shop:bakery'},
          <String, Object?>{'kind': 'npc', 'id': 'examples/nobody'},
          <String, Object?>{'kind': 'thing', 'id': 'x'},
          <String, Object?>{'kind': 'npc', 'id': 'Not An Id'},
          <String, Object?>{'kind': 'npc', 'id': 'examples/broken'},
        ],
      });
      expect(reply.status, 200, reason: jsonEncode(reply.body));
      expectMessage('documents-read-response', reply.body);
      expect(refsOf(reply.json['documents']), <String>['shop shop:bakery', 'npc examples/broken']);
      expect((reply.json['documents']! as List<Object?>).last, containsPair('diagnostics', isNotEmpty));
      expect(reply.json['errors'], <Object?>[
        <String, Object?>{'kind': 'npc', 'id': 'examples/nobody', 'code': 'not-found', 'message': 'No npc examples/nobody.'},
        <String, Object?>{'kind': 'thing', 'id': 'x', 'code': 'invalid-request', 'message': 'Unknown kind thing.'},
        <String, Object?>{'kind': 'npc', 'id': 'Not An Id', 'code': 'invalid-request', 'message': "'Not An Id' is not a document id."},
      ]);
      expect(reply.json['remaining'], isEmpty);
      expect(((await control('GET', '/state'))['tokens']! as List<Object?>).single, containsPair('counter', 0));
      final Map<String, Object?> validate = <String, Object?>{
        'documents': <Object?>[
          <String, Object?>{'kind': 'npc', 'id': 'examples/new', 'baseRevision': null, 'baseHash': null, 'source': <String, Object?>{}},
        ],
      };
      expect((await call('POST', '/animus/v1/validate', token: token, body: validate, count: 1)).status, 200);
    });

    test('the batch read refuses a malformed request whole', () async {
      final String token = await pairToken();
      Map<String, Object?> ref(String id) => <String, Object?>{'kind': 'npc', 'id': id};
      final List<Object?> bodies = <Object?>[
        <String, Object?>{'refs': <Object?>[for (int index = 0; index < 201; index++) ref('examples/n$index')]},
        <String, Object?>{'refs': <Object?>[ref('examples/greeter'), ref('examples/greeter')]},
        <String, Object?>{'refs': <Object?>[ref('examples/greeter')], 'limit': 2},
        <String, Object?>{'refs': <Object?>[]},
        <String, Object?>{'refs': <Object?>[<String, Object?>{'kind': 'npc', 'id': ''}]},
        <String, Object?>{'refs': <Object?>[<String, Object?>{...ref('examples/greeter'), 'path': 'npcs/examples/greeter.json'}]},
        <String, Object?>{},
        <String, Object?>{'refs': 'npc'},
      ];
      for (final Object? body in bodies) {
        final Reply refused = await read(token, body!);
        expect(refused.status, 400, reason: jsonEncode(body));
        expectMessage('error-response', refused.body);
        expect(refused.errorCode, 'invalid-request');
      }
      final Reply most = await read(token, <String, Object?>{'refs': <Object?>[for (int index = 0; index < 200; index++) ref('examples/n$index')]});
      expect(most.status, 200);
      expect(most.json['errors'], hasLength(200));
    });

    test('a batch read stops at 512 KiB, a larger document comes alone, and the relay refuses one bigger than a frame', () async {
      final String token = await pairToken();
      final Map<String, Object?> greeter = await document(token, 'npc', 'examples/greeter');
      Map<String, Object?> padded(String id, int bytes) => <String, Object?>{
            ...greeter['source']! as Map<String, Object?>,
            'id': id,
            'modules': <String, Object?>{'citizens:review': <String, Object?>{'notes': 'x' * bytes}},
          };
      await control('POST', '/edit', <String, Object?>{'kind': 'npc', 'id': 'examples/large', 'source': padded('large', 600 * 1024)});
      await control('POST', '/edit', <String, Object?>{'kind': 'npc', 'id': 'examples/huge', 'source': padded('huge', 1100 * 1024)});
      final List<Object?> refs = <Object?>[
        <String, Object?>{'kind': 'npc', 'id': 'examples/greeter'},
        <String, Object?>{'kind': 'npc', 'id': 'examples/large'},
        <String, Object?>{'kind': 'npc', 'id': 'examples/shopkeeper'},
      ];
      final Reply first = await read(token, <String, Object?>{'refs': refs});
      expect(refsOf(first.json['documents']), <String>['npc examples/greeter']);
      expect(first.json['remaining'], refs.sublist(1));
      final Reply second = await read(token, <String, Object?>{'refs': refs.sublist(1)});
      expect(refsOf(second.json['documents']), <String>['npc examples/large']);
      expect(second.json['remaining'], refs.sublist(2));
      final Frames frames = await relay();
      final Map<String, Object?> relayed = await relayCall(frames, 1, 'POST', '/v1/documents/read', token: token, body: <String, Object?>{
        'refs': <Object?>[<String, Object?>{'kind': 'npc', 'id': 'examples/huge'}, refs.first],
      });
      expect(relayed['status'], 200);
      final Map<String, Object?> body = relayed['body']! as Map<String, Object?>;
      expect(refsOf(body['documents']), <String>['npc examples/greeter']);
      expect(body['errors'], <Object?>[containsPair('code', 'too-large')]);
      await frames.socket.close();
    });
  });

  group('protocol additions', () {
    test('DELETE /v1/token revokes the bearer with no scope, counter or rate limit, and answers 204 again once revoked', () async {
      final String token = await pairToken('?scopes=read');
      final Frames events = await directEvents(token);
      await Future<void>.delayed(const Duration(milliseconds: 100));
      final List<Reply> burst = await Future.wait(<Future<Reply>>[
        for (int index = 0; index < 50; index++) call('GET', '/animus/v1/capabilities', token: token),
      ]);
      expect(burst.any((Reply reply) => reply.status == 429), isTrue, reason: 'the request budget is spent');
      final Reply forgot = await call('DELETE', '/animus/v1/token', token: token, counted: false);
      expect(forgot.status, 204);
      expect(forgot.body, isNull);
      final Map<String, Object?> closing = await events.nextOf('closing');
      expect(closing['reason'], 'token-revoked');
      expect((await call('GET', '/animus/v1/catalog', token: token)).errorCode, 'token-revoked');
      expect((await call('DELETE', '/animus/v1/token', token: token, counted: false)).status, 204);
      expect((await call('DELETE', '/animus/v1/token', counted: false)).errorCode, 'unauthorized');
    });

    test('a token past its request budget gets 429 with a retry hint on both paths; other tokens are unaffected', () async {
      final String token = await pairToken();
      final String other = await pairToken();
      final List<Reply> burst = await Future.wait(<Future<Reply>>[
        for (int index = 0; index < 50; index++) call('GET', '/animus/v1/capabilities', token: token),
      ]);
      expect(burst.where((Reply reply) => reply.status == 200).length, greaterThanOrEqualTo(40), reason: 'a burst of twice requestsPerSecond passes');
      final Reply refused = burst.lastWhere((Reply reply) => reply.status == 429);
      expectMessage('error-response', refused.body);
      expect(refused.errorCode, 'rate-limited');
      expect(errorOf(refused.body)['message'], 'Too many requests for this token.');
      final int wait = errorOf(refused.body)['retryAfterMillis']! as int;
      expect(wait, inInclusiveRange(1, 50));
      expect(refused.headers.value('retry-after'), '1');
      expect((await call('GET', '/animus/v1/capabilities', token: other)).status, 200);
      await Future<void>.delayed(Duration(milliseconds: wait + 5));
      expect((await call('GET', '/animus/v1/capabilities', token: token)).status, 200);
      final Frames frames = await relay();
      for (int id = 1; id <= 45; id++) {
        frames.send(<String, Object?>{'type': 'request', 'id': id, 'method': 'GET', 'path': '/v1/capabilities', 'headers': <String, String>{'Authorization': 'Bearer $token'}});
      }
      final List<Map<String, Object?>> responses = <Map<String, Object?>>[for (int index = 0; index < 45; index++) await frames.nextOf('response')];
      final Map<String, Object?> relayed = responses.lastWhere((Map<String, Object?> response) => response['status'] == 429);
      expectMessage('relay-response', relayed);
      expect(relayed['headers'], <String, Object?>{'Retry-After': '1'});
      expect(errorOf(relayed['body'])['retryAfterMillis'], isA<int>());
      await frames.socket.close();
      final Map<String, Object?> state = await control('GET', '/state');
      final Map<String, Object?> limited = state['rateLimited']! as Map<String, Object?>;
      expect(limited.keys, <String>[tokenIdOf(token)]);
      expect(state['rateLimitedTotal'], limited[tokenIdOf(token)]);
      expect(state['rateLimitedTotal'], greaterThanOrEqualTo(1 + responses.where((Map<String, Object?> response) => response['status'] == 429).length));
    });

    test('catalog events carry the summary and what changed each document', () async {
      final String token = await pairToken();
      final Frames events = await directEvents(token);
      await Future<void>.delayed(const Duration(milliseconds: 100));
      final Map<String, Object?> greeter = await document(token, 'npc', 'examples/greeter');
      Map<String, Object?> renamed(String name) => <String, Object?>{...greeter['source']! as Map<String, Object?>, 'name': name};
      Future<Map<String, Object?>> entry() async => ((await events.nextOf('catalog'))['documents']! as List<Object?>).single! as Map<String, Object?>;
      await control('POST', '/edit', <String, Object?>{'kind': 'npc', 'id': 'examples/greeter', 'source': renamed('&6Server Greeter')});
      final Map<String, Object?> byServer = await entry();
      expect(byServer['source'], 'server');
      expect(byServer['summary'], containsPair('name', 'Server Greeter'));
      await control('POST', '/edit', <String, Object?>{'kind': 'npc', 'id': 'examples/greeter', 'source': renamed('Hand edit'), 'origin': 'file'});
      expect(await entry(), containsPair('source', 'file'));
      final Reply applied = await call('POST', '/animus/v1/changes', token: token, body: rename(await document(token, 'npc', 'examples/greeter'), 'Applied', key('sources')));
      expect(applied.status, 200);
      final Map<String, Object?> byEditor = await entry();
      expect(byEditor['source'], 'editor');
      expect(byEditor['hash'], ((applied.json['documents']! as List<Object?>).single! as Map<String, Object?>)['hash']);
      await control('POST', '/delete', <String, Object?>{'kind': 'npc', 'id': 'examples/greeter'});
      final Map<String, Object?> deleted = await events.nextOf('catalog');
      expectMessage('event', deleted);
      expect(deleted['documents'], isEmpty);
      expect(deleted['deleted'], <Object?>[<String, Object?>{'kind': 'npc', 'id': 'examples/greeter', 'source': 'server'}]);
      expect((await call('GET', '/animus/v1/documents/npc/examples/greeter', token: token)).status, 404);
      expect((await call('POST', '/__fake/delete', body: <String, Object?>{'kind': 'npc', 'id': 'examples/greeter'}, withOrigin: null)).status, 404);
      final Reply badOrigin = await call('POST', '/__fake/edit', withOrigin: null, body: <String, Object?>{'kind': 'npc', 'id': 'examples/greeter', 'source': <String, Object?>{}, 'origin': 'disk'});
      expect(badOrigin.status, 400);
      await events.socket.close();
    });

    test('queued catalog entries coalesce to the latest version of each document', () async {
      final String token = await pairToken();
      final Frames events = await directEvents(token);
      await Future<void>.delayed(const Duration(milliseconds: 100));
      server.api.touch(DocumentKind.npc, null, 'server');
      server.api.touch(DocumentKind.npc, null, 'file');
      server.api.serverDelete(DocumentKind.npc, 'examples/broken', 'server');
      server.api.touch(DocumentKind.npc, 1, 'server');
      final Map<String, Object?> frame = await events.nextOf('catalog');
      expect(<Object?>[
        for (final Map<String, Object?> entry in (frame['documents']! as List<Object?>).cast<Map<String, Object?>>())
          <Object?>[entry['id'], entry['revision'], entry['source']],
      ], <Object?>[
        <Object?>['examples/shopkeeper', 4, 'file'],
        <Object?>['examples/greeter', 4, 'server'],
      ], reason: 'a newer version goes to the back of the queue');
      expect(frame['deleted'], <Object?>[<String, Object?>{'kind': 'npc', 'id': 'examples/broken', 'source': 'server'}]);
      await events.socket.close();
    });

    test('pair scopes= with read narrows the grant; without read it adds to the defaults', () async {
      Future<Object?> scopes(String query) async => (await pair(query)).json['scopes'];
      expect(await scopes('?scopes=read,terrain'), <String>['read', 'terrain']);
      expect(await scopes('?scopes=apply:commands'), <String>['read', 'validate', 'preview', 'apply:npcs', 'apply:behaviors', 'apply:gui', 'apply:rigs', 'apply:commands', 'import', 'spawn', 'terrain']);
      expect(await scopes('?scopes=import'), await scopes(''));
      expect((await call('POST', '/__fake/pair?scopes=admin', withOrigin: null)).status, 400);
    });

    test('a token expires a full lifetime after its last use', () async {
      final Reply paired = await pair('?days=1');
      final String token = paired.json['token']! as String;
      int minutesLeft(Object? instant) => DateTime.parse(instant! as String).difference(DateTime.now()).inMinutes;
      Future<int> left() async => minutesLeft((((await control('GET', '/state'))['tokens']! as List<Object?>).single! as Map<String, Object?>)['expiresAt']);
      expect(minutesLeft(paired.json['expiresAt']), closeTo(1440, 1));
      await control('POST', '/expire', <String, Object?>{'inDays': 0.5});
      expect(await left(), closeTo(720, 1));
      expect((await call('GET', '/animus/v1/capabilities', token: token)).status, 200);
      expect(await left(), closeTo(1440, 1));
    });

    test('offline is a restart: the direct path is gone and the relay reports the server offline until it returns', () async {
      final String token = await pairToken();
      final Frames events = await directEvents(token);
      final Frames frames = await relay();
      await Future<void>.delayed(const Duration(milliseconds: 100));
      await control('POST', '/offline', <String, Object?>{'seconds': 1});
      await events.closed;
      await expectLater(call('GET', '/animus/v1/hello'), throwsA(anyOf(isA<HttpException>(), isA<SocketException>())));
      expect((await frames.nextOf('status'))['online'], isFalse);
      final Map<String, Object?> down = await relayCall(frames, 1, 'GET', '/v1/hello');
      expect(down['status'], 503);
      expect((await control('GET', '/state'))['paths'], <String, Object?>{'direct': 'down', 'relay': 'up', 'server': 'offline'});
      final Map<String, Object?> back = await frames.nextOf('status');
      expectMessage('relay-status', back);
      expect(back['online'], isTrue);
      expect((await call('GET', '/animus/v1/hello')).status, 200);
      expect((await relayCall(frames, 2, 'GET', '/v1/hello'))['status'], 200);
      await frames.socket.close();
    });
  });

  group('as the Java server', () {
    /// Polls `/__fake/state` until [token] has [count] requests in flight.
    Future<void> inFlight(String token, int count) async {
      final DateTime deadline = DateTime.now().add(const Duration(seconds: 5));
      while (true) {
        final Map<String, Object?> running = (await control('GET', '/state'))['inFlight']! as Map<String, Object?>;
        if ((running[tokenIdOf(token)] ?? 0) == count) return;
        if (DateTime.now().isAfter(deadline)) fail('$count requests never were in flight: $running');
        await Future<void>.delayed(const Duration(milliseconds: 10));
      }
    }

    String retryAfter(int millis) => '${(millis + 999) ~/ 1000}';

    test('despawn and spawn write spawned, raise the revision and send an editor catalog event', () async {
      final String token = await pairToken();
      final Frames events = await directEvents(token);
      await Future<void>.delayed(const Duration(milliseconds: 100));
      int revision = (await document(token, 'npc', 'examples/greeter'))['revision']! as int;
      for (final bool spawned in <bool>[false, true]) {
        final Reply reply = await call('POST', '/animus/v1/npcs/examples%2Fgreeter/${spawned ? 'spawn' : 'despawn'}', token: token);
        expect(reply.status, 200, reason: jsonEncode(reply.body));
        expectMessage('spawn-response', reply.body);
        expect(reply.json, <String, Object?>{'id': 'examples/greeter', 'spawned': spawned});
        final Map<String, Object?> after = await document(token, 'npc', 'examples/greeter');
        expect(after['revision'], ++revision);
        expect((after['source']! as Map<String, Object?>)['spawned'], spawned);
        expect((after['summary']! as Map<String, Object?>)['spawned'], spawned);
        final Map<String, Object?> catalog = await events.nextOf('catalog');
        expectMessage('event', catalog);
        expect((catalog['documents']! as List<Object?>).single, <String, Object?>{
          'kind': 'npc',
          'id': 'examples/greeter',
          'path': after['path'],
          'revision': revision,
          'hash': after['hash'],
          'summary': after['summary'],
          'source': 'editor',
        });
      }
      await events.socket.close();
    });

    test('fail with rate-limited or busy carries a retry hint, the given one or what Java sends', () async {
      final String token = await pairToken();
      for (final (String query, String code, int status, int wait) in <(String, String, int, int)>[
        ('code=rate-limited', 'rate-limited', 429, 50),
        ('code=busy', 'busy', 503, 500),
        ('code=busy&retryAfterMillis=2500', 'busy', 503, 2500),
      ]) {
        await control('POST', '/fail?$query');
        final Reply refused = await call('GET', '/animus/v1/catalog', token: token);
        expect(refused.status, status, reason: query);
        expectMessage('error-response', refused.body);
        expect(refused.errorCode, code, reason: query);
        expect(errorOf(refused.body)['retryAfterMillis'], wait, reason: query);
        expect(refused.headers.value('retry-after'), retryAfter(wait), reason: query);
      }
      await control('POST', '/fail?code=rate-limited&retryAfterMillis=1200');
      final Frames frames = await relay();
      final Map<String, Object?> relayed = await relayCall(frames, 1, 'GET', '/v1/catalog', token: token);
      expect(relayed['status'], 429);
      expect(relayed['headers'], <String, Object?>{'Retry-After': '2'});
      expect(errorOf(relayed['body'])['retryAfterMillis'], 1200);
      await frames.socket.close();
      for (final String query in <String>['code=busy&retryAfterMillis=-1', 'code=busy&retryAfterMillis=soon', 'code=conflict&retryAfterMillis=100']) {
        expect((await call('POST', '/__fake/fail?$query', withOrigin: null)).status, 400, reason: query);
      }
    });

    test('a token with 8 requests in flight gets 429 rate-limited with a 250 ms hint on both paths', () async {
      final String token = await pairToken();
      final String other = await pairToken();
      await control('POST', '/slow?ms=400');
      expect((await control('GET', '/state'))['controls'], containsPair('slowMillis', 400));
      final List<Future<Reply>> slow = <Future<Reply>>[
        for (int index = 0; index < 8; index++) call('GET', '/animus/v1/capabilities', token: token),
      ];
      await inFlight(token, 8);
      final Reply refused = await call('GET', '/animus/v1/capabilities', token: token);
      expect(refused.status, 429);
      expectMessage('error-response', refused.body);
      expect(errorOf(refused.body), <String, Object?>{'code': 'rate-limited', 'message': 'At most 8 requests run at once per token.', 'retryAfterMillis': 250});
      expect(refused.headers.value('retry-after'), '1');
      expect((await call('GET', '/animus/v1/capabilities', token: other)).status, 200, reason: 'another token has its own slots');
      expect((await Future.wait(slow)).map((Reply reply) => reply.status), everyElement(200));
      await inFlight(token, 0);
      final Frames frames = await relay();
      for (int id = 1; id <= 8; id++) {
        frames.send(<String, Object?>{'type': 'request', 'id': id, 'method': 'GET', 'path': '/v1/capabilities', 'headers': <String, String>{'Authorization': 'Bearer $token'}});
      }
      await inFlight(token, 8);
      final Map<String, Object?> relayed = await relayCall(frames, 9, 'GET', '/v1/capabilities', token: token);
      expect(relayed['status'], 429);
      expect(relayed['headers'], <String, Object?>{'Retry-After': '1'});
      expect(errorOf(relayed['body'])['retryAfterMillis'], 250);
      for (int index = 0; index < 8; index++) {
        expect((await frames.nextOf('response'))['status'], 200);
      }
      await frames.socket.close();
      await control('POST', '/slow?ms=0');
      expect((await call('POST', '/__fake/slow?ms=soon', withOrigin: null)).status, 400);
    });

    test('an id malformed for its kind is invalid-request on the single and the batch read', () async {
      final String token = await pairToken();
      final Reply single = await call('GET', '/animus/v1/documents/skin/abc', token: token);
      expect(single.status, 400);
      expectMessage('error-response', single.body);
      expect(errorOf(single.body), <String, Object?>{'code': 'invalid-request', 'message': "'abc' is not a valid skin id."});
      final Reply batch = await call('POST', '/animus/v1/documents/read', token: token, counted: false, body: <String, Object?>{
        'refs': <Object?>[
          <String, Object?>{'kind': 'skin', 'id': 'abc'},
          <String, Object?>{'kind': 'shop', 'id': 'bakery'},
          <String, Object?>{'kind': 'shop', 'id': 'shop:bakery'},
        ],
      });
      expect(batch.status, 200);
      expectMessage('documents-read-response', batch.body);
      expect(refsOf(batch.json['documents']), <String>['shop shop:bakery']);
      expect(batch.json['errors'], <Object?>[
        <String, Object?>{'kind': 'skin', 'id': 'abc', 'code': 'invalid-request', 'message': "'abc' is not a valid skin id."},
        <String, Object?>{'kind': 'shop', 'id': 'bakery', 'code': 'invalid-request', 'message': "'bakery' is not a valid shop id."},
      ]);
    });

    test('group, quest and recording documents carry a revision the server raises', () async {
      final String token = await pairToken();
      Map<String, Object?> fixture(String path) => (jsonDecode(File(path).readAsStringSync()) as Map<String, Object?>)..remove('revision');
      for (final (String kind, String id, Map<String, Object?> source) in <(String, String, Map<String, Object?>)>[
        ('group', 'harbor', fixture('../schema/fixtures/groups/harbor.json')),
        ('recording', 'market-loop', fixture('../schema/fixtures/recordings/market-loop.json')),
      ]) {
        final Reply created = await call('POST', '/animus/v1/changes', token: token, body: <String, Object?>{
          'idempotencyKey': key('new-$kind'),
          'documents': <Object?>[<String, Object?>{'kind': kind, 'id': id, 'baseRevision': null, 'baseHash': null, 'source': source}],
        });
        expect(created.status, 200, reason: jsonEncode(created.body));
        expect(((created.json['documents']! as List<Object?>).single! as Map<String, Object?>)['revision'], 1, reason: kind);
        final Map<String, Object?> stored = await document(token, kind, id);
        expect(stored['revision'], 1, reason: kind);
        final Reply edited = await call('POST', '/animus/v1/changes', token: token, body: <String, Object?>{
          'idempotencyKey': key('edit-$kind'),
          'documents': <Object?>[<String, Object?>{'kind': kind, 'id': id, 'baseRevision': 1, 'baseHash': stored['hash'], 'source': stored['source']}],
        });
        expect(edited.status, 200, reason: jsonEncode(edited.body));
        expect(((edited.json['documents']! as List<Object?>).single! as Map<String, Object?>)['revision'], 2, reason: kind);
      }
      final Map<String, Object?> quest = <String, Object?>{'kind': 'quest', 'id': 'town:intro', 'source': <String, Object?>{'title': 'Intro'}};
      expect((await control('POST', '/edit', quest))['revision'], 1);
      expect((await control('POST', '/edit', quest))['revision'], 2);
    });

    test('more than 10 pair attempts a minute from one address get 429 with the wait to the next minute', () async {
      final String token = await pairToken();
      final Map<String, Object?> unknown = <String, Object?>{'tokenId': 'tok_000000000000', 'secret': 'A' * 43};
      int waitOf(Reply reply) => errorOf(reply.body)['retryAfterMillis']! as int;
      // Windows are whole clock minutes, so at most 21 attempts reach one refusal.
      Future<Reply> flood() async {
        for (int attempt = 0; attempt < 21; attempt++) {
          final Reply reply = await call('POST', '/animus/v1/pair', body: unknown);
          if (reply.status == 429) return reply;
          expect(reply.errorCode, 'pairing-invalid');
        }
        fail('no attempt was refused');
      }

      Reply refused = await flood();
      if (waitOf(refused) < 3000) {
        // The minute is nearly over: start in the next one so the checks below share it.
        await Future<void>.delayed(Duration(milliseconds: waitOf(refused) + 50));
        refused = await flood();
      }
      expectMessage('error-response', refused.body);
      expect(errorOf(refused.body)['code'], 'rate-limited');
      expect(errorOf(refused.body)['message'], 'Too many pairing attempts from this address.');
      expect(waitOf(refused), inInclusiveRange(1, 60000));
      expect(refused.headers.value('retry-after'), retryAfter(waitOf(refused)));
      final Map<String, Object?> payload = payloadOf((await control('POST', '/pair'))['code']! as String);
      final Reply valid = await call('POST', '/animus/v1/pair', body: <String, Object?>{'tokenId': payload['tokenId'], 'secret': payload['secret']});
      expect(valid.status, 429, reason: 'the window counts attempts, not failures');
      final Frames frames = await relay();
      final Map<String, Object?> relayed = await relayCall(frames, 1, 'POST', '/v1/pair', body: unknown);
      expect(relayed['status'], 429);
      expect((relayed['headers']! as Map<String, Object?>)['Retry-After'], isNotNull);
      expect(errorOf(relayed['body'])['retryAfterMillis'], isA<int>());
      await frames.socket.close();
      final Frames events = await directEvents(token);
      await Future<void>.delayed(const Duration(milliseconds: 100));
      await control('POST', '/touch', <String, Object?>{'kind': 'npc', 'count': 1});
      expectMessage('event', await events.nextOf('catalog'));
      await events.socket.close();
      await control('POST', '/reset');
      expect((await pair()).status, 200, reason: 'reset forgets the windows');
    });

    test('a token paired with folders= reaches only NPCs in those folders', () async {
      final Map<String, Object?> greeter = (await control('GET', '/document?kind=npc&id=examples/greeter'))['source']! as Map<String, Object?>;
      await control('POST', '/edit', <String, Object?>{'kind': 'npc', 'id': 'town/guard', 'source': <String, Object?>{...greeter, 'id': 'guard', 'group': 'town', 'name': 'Guard'}});
      expect((await pair()).json.containsKey('groups'), isFalse, reason: 'an unlimited token has no groups');
      final Reply paired = await pair('?folders=town');
      expect(paired.json['groups'], <String>['town']);
      final String token = paired.json['token']! as String;
      final List<Map<String, Object?>> entries = (await catalogPages(token)).expand((List<Map<String, Object?>> page) => page).toList();
      expect(<Object?>[for (final Map<String, Object?> entry in entries) if (entry['kind'] == 'npc') entry['id']], <Object?>['town/guard']);
      expect(entries.where((Map<String, Object?> entry) => entry['kind'] == 'shop'), isNotEmpty, reason: 'other kinds are not limited');
      final Reply denied = await call('GET', '/animus/v1/documents/npc/examples%2Fgreeter', token: token);
      expect(denied.status, 403);
      expectMessage('error-response', denied.body);
      expect(errorOf(denied.body), <String, Object?>{'code': 'group-denied', 'message': 'This token cannot reach NPC folder npcs/examples.'});
      expect((await document(token, 'npc', 'town/guard'))['id'], 'town/guard');
      final Reply batch = await call('POST', '/animus/v1/documents/read', token: token, counted: false, body: <String, Object?>{
        'refs': <Object?>[
          <String, Object?>{'kind': 'npc', 'id': 'examples/greeter'},
          <String, Object?>{'kind': 'npc', 'id': 'town/guard'},
          <String, Object?>{'kind': 'shop', 'id': 'shop:bakery'},
        ],
      });
      expectMessage('documents-read-response', batch.body);
      expect(refsOf(batch.json['documents']), <String>['npc town/guard', 'shop shop:bakery']);
      expect(batch.json['errors'], <Object?>[
        <String, Object?>{'kind': 'npc', 'id': 'examples/greeter', 'code': 'group-denied', 'message': 'This token cannot reach NPC folder npcs/examples.'},
      ]);
      expect((await call('POST', '/animus/v1/npcs/examples%2Fgreeter/despawn', token: token)).errorCode, 'group-denied');
      final Frames events = await directEvents(token);
      await Future<void>.delayed(const Duration(milliseconds: 100));
      await control('POST', '/edit', <String, Object?>{'kind': 'npc', 'id': 'examples/greeter', 'source': <String, Object?>{...greeter, 'name': 'Hidden'}});
      await control('POST', '/edit', <String, Object?>{'kind': 'npc', 'id': 'town/guard', 'source': <String, Object?>{...greeter, 'id': 'guard', 'group': 'town', 'name': 'Seen'}});
      final Map<String, Object?> catalog = await events.nextOf('catalog');
      expectMessage('event', catalog);
      expect(refsOf(catalog['documents']), <String>['npc town/guard']);
      await events.socket.close();
      expect((await call('POST', '/__fake/pair?folders=Town', withOrigin: null)).status, 400);
      final Reply nested = await pair('?folders=town/market');
      expectMessage('pair-response', nested.body);
      expect(nested.json['groups'], <String>['town/market']);
    });
  });

  group('scale', () {
    setUpAll(() => settings = const FakeSettings(scale: 1200));
    tearDownAll(() => settings = fixtureSettings);

    test('--scale=1200 is the imported mix in catalog pages under the caps, and every document validates', () async {
      final String token = await pairToken();
      final List<List<Map<String, Object?>>> pages = await catalogPages(token);
      for (final List<Map<String, Object?>> page in pages) {
        expect(page.length, lessThanOrEqualTo(1000));
        expect(encodedBytes(page), lessThanOrEqualTo(512 * 1024));
      }
      final List<Map<String, Object?>> all = pages.expand((List<Map<String, Object?>> page) => page).toList();
      final Map<String, int> counts = <String, int>{};
      for (final Map<String, Object?> entry in all) {
        counts.update(entry['kind']! as String, (int count) => count + 1, ifAbsent: () => 1);
      }
      expect(counts, <String, int>{
        'npc': 509, 'behavior': 143, 'server-behaviors': 1, 'shop': 110, 'inventory': 3, 'menu': 1,
        'hologram': 7, 'text-animation': 12, 'recording': 1, 'skin': 413,
      });
      expect(all, hasLength(1200));
      final List<Map<String, Object?>> npcs = <Map<String, Object?>>[
        for (final Map<String, Object?> entry in all)
          if (entry['kind'] == 'npc') entry['summary']! as Map<String, Object?>,
      ];
      expect(npcs.where((Map<String, Object?> summary) => !summary.containsKey('name')), hasLength(blankNamedNpcs));
      final Set<String> skins = <String>{for (final Map<String, Object?> entry in all) if (entry['kind'] == 'skin') entry['id']! as String};
      final List<Object?> worn = <Object?>[for (final Map<String, Object?> summary in npcs) if (summary['skin'] != null) summary['skin']];
      expect(worn.length, greaterThan(200));
      expect(skins.containsAll(worn), isTrue, reason: 'every worn skin is a skin document');
      expect(<Object?>{for (final Map<String, Object?> summary in npcs) summary['folder']}, containsAll(<String>['world', 'town/market', 'harbor', '']));
      expect(<Object?>{for (final Map<String, Object?> summary in npcs) summary['world']}.length, greaterThanOrEqualTo(3));
      expect(<Object?>{for (final Map<String, Object?> summary in npcs) ...summary['features']! as List<Object?>}, containsAll(<String>['route', 'combat', 'shop', 'behavior', 'hologram']));
      final List<StoredDocument> stored = server.api.documents.list();
      expect(stored.fold(0, (int sum, StoredDocument document) => sum + document.content.length), inInclusiveRange(6000000, 9000000));
      for (final StoredDocument document in stored) {
        expect(document.diagnostics.where((Map<String, Object?> found) => found['severity'] == 'error'), isEmpty, reason: document.path);
      }
    });

    test('a catalog page also ends at 512 KiB of entries', () async {
      final FakeServer big = await FakeServer.start(const FakeSettings(scale: 2000, catalogPageSize: 5000), port: 0);
      try {
        final Map<String, Object?> payload = payloadOf(big.pair()['code']! as String);
        final String bigBase = 'http://127.0.0.1:${big.port}/animus/v1';
        final String token = (await call('POST', '$bigBase/pair', body: <String, Object?>{'tokenId': payload['tokenId'], 'secret': payload['secret']})).json['token']! as String;
        final Reply first = await call('GET', '$bigBase/catalog', token: token);
        final List<Object?> page = first.json['documents']! as List<Object?>;
        expect(page.length, lessThan(2000));
        expect(encodedBytes(page), allOf(lessThanOrEqualTo(512 * 1024), greaterThan(500 * 1024)));
        final Reply second = await call('GET', '$bigBase/catalog?after=${first.json['next']}', token: token);
        expect((second.json['documents']! as List<Object?>).length + page.length, 2000);
        expect(second.json['next'], isNull);
      } finally {
        await big.close();
      }
    });

    test('reset regenerates the same set', () async {
      List<String> hashes() => <String>[for (final StoredDocument document in server.api.documents.list()) document.hash];
      final List<String> before = hashes();
      server.api.touch(null, 20, 'server');
      expect(hashes(), isNot(before));
      await control('POST', '/reset');
      expect(hashes(), before);
      expect(scaleDataFolder(1200), scaleDataFolder(1200));
    });

    test('batch reads of 200 NPCs split at 512 KiB, the rest kept in remaining in order', () async {
      final String token = await pairToken();
      final List<Map<String, Object?>> npcs = (await catalogPages(token, 'kind=npc')).expand((List<Map<String, Object?>> page) => page).toList();
      final List<Object?> refs = <Object?>[for (final Map<String, Object?> entry in npcs.take(200)) <String, Object?>{'kind': 'npc', 'id': entry['id']}];
      List<Object?> queue = refs;
      final List<String> read = <String>[];
      int calls = 0;
      while (queue.isNotEmpty) {
        final Reply reply = await call('POST', '/animus/v1/documents/read', token: token, counted: false, body: <String, Object?>{'refs': queue});
        calls++;
        expect(reply.status, 200);
        final List<Object?> documents = reply.json['documents']! as List<Object?>;
        expect(encodedBytes(documents), lessThanOrEqualTo(512 * 1024));
        expect(reply.json['errors'], isEmpty);
        expect(<String>[...refsOf(documents), ...refsOf(reply.json['remaining'])], refsOf(queue));
        read.addAll(refsOf(documents));
        queue = reply.json['remaining']! as List<Object?>;
      }
      expect(read, refsOf(refs));
      expect(calls, greaterThan(1));
    });

    test('touch sends a reload of every document as paced catalog frames of at most 48 KiB', () async {
      final String token = await pairToken();
      final Frames events = await directEvents(token);
      await Future<void>.delayed(const Duration(milliseconds: 100));
      expect((await control('POST', '/touch', <String, Object?>{}))['touched'], 1200);
      final Set<String> seen = <String>{};
      final List<DateTime> arrivals = <DateTime>[];
      while (seen.length < 1200) {
        final Map<String, Object?> frame = await events.nextOf('catalog');
        arrivals.add(DateTime.now());
        final List<Object?> entries = frame['documents']! as List<Object?>;
        expect(encodedBytes(entries), lessThanOrEqualTo(48 * 1024));
        for (final Object? entry in entries) {
          expect(entry, allOf(containsPair('source', 'server'), contains('summary')));
        }
        seen.addAll(refsOf(entries));
      }
      expect(arrivals, hasLength(inInclusiveRange(8, 14)));
      expect(arrivals.last.difference(arrivals.first).inMilliseconds, greaterThanOrEqualTo((arrivals.length - 1) * 40), reason: 'at most 20 frames a second');
      await events.socket.close();
    });
  });
}
