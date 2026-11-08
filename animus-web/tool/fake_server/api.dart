import 'dart:convert';
import 'dart:typed_data';

import 'package:animus_editor/config/shared_schemas.g.dart';
import 'package:animus_editor/model/document_kind.dart';

import 'changes.dart';
import 'documents.dart';
import 'events.dart';
import 'identity.dart';
import 'imports.dart';
import 'messages.dart';
import 'previews.dart';
import 'protocol.dart';
import 'terrain.dart';
import 'tokens.dart';
import 'validation.dart';

/// Settings of one fake server.
class FakeSettings {
  const FakeSettings({
    this.dataDirectory,
    this.allowedOrigins = const <String>['http://127.0.0.1:8098'],
    this.viewer = '00000000-0000-4000-8000-000000000001',
    this.catalogPageSize = 1000,
    this.serverName = 'Animus fake server',
  });

  final String? dataDirectory;
  final List<String> allowedOrigins;

  /// The admin player who "sees" previews.
  final String viewer;
  final int catalogPageSize;
  final String serverName;
}

/// The answer to opening an event stream: refused with an error response,
/// or open with a handle that removes the stream.
class EventsOpened {
  const EventsOpened(this.response, this.remove);

  final FakeResponse response;
  final void Function()? remove;

  bool get open => remove != null;
}

typedef _Endpoint = FakeResponse Function(FakeRequest request, FakeToken token, List<String> segments);

class _Route {
  const _Route(this.scope, this.anyApply, this.handler);

  final String? scope;
  final bool anyApply;
  final _Endpoint handler;
}

/// The transport-neutral `/animus/v1` API, as EditorApi: origins, bearer
/// tokens, scopes and `X-Animus-Counter` are checked here for both paths.
class FakeApi {
  FakeApi(this.settings) : documents = FakeDocuments(settings.dataDirectory) {
    changes = FakeChanges(documents);
    events = EventHub((String tokenId) => previews.endToken(tokenId));
    previews = FakePreviews((String id, String state) =>
        events.publish(<String, Object?>{'type': 'preview', 'previewId': id, 'state': state}));
    imports = FakeImports(documents, (List<StoredDocument> changed, List<StoredDocument> deleted) => publishCatalog(
          <Map<String, Object?>>[for (final StoredDocument document in changed) document.version],
          <Map<String, Object?>>[for (final StoredDocument document in deleted) <String, Object?>{'kind': document.kind.wireName, 'id': document.id}],
        ));
    documents.load();
  }

  static const Map<String, String> _cors = <String, String>{
    'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
    'Access-Control-Allow-Headers': 'Authorization, Content-Type, X-Animus-Counter, If-None-Match',
    'Access-Control-Expose-Headers': 'ETag',
    'Access-Control-Max-Age': '600',
    'Access-Control-Allow-Private-Network': 'true',
    'Vary': 'Origin',
  };

  static const Map<String, String> _schemaFiles = <String, String>{
    'npc': 'generated/npc.schema.json',
    'behavior': 'generated/behavior.schema.json',
    'pack': 'generated/pack.schema.json',
    'completions': 'generated/completions.json',
    'shop': 'shop-v1.schema.json',
    'inventory': 'gui-inventory-v1.schema.json',
    'rig': 'rig-v1.schema.json',
    'animation': 'animation-v1.schema.json',
    'menu': 'gloss/gloss.schema.json',
    'hologram': 'gloss/gloss-holograms.schema.json',
    'presets': 'gloss/gloss-presets.schema.json',
    'server-behaviors': 'server-behaviors-v1.schema.json',
  };

  final FakeSettings settings;
  final FakeIdentity identity = FakeIdentity();
  final FakeTokens tokens = FakeTokens();
  final FakeDocuments documents;
  late final FakeChanges changes;
  late final EventHub events;
  late final FakePreviews previews;
  late final FakeImports imports;
  Map<String, Object?>? _schemas;

  /// `/__fake/fail`: the next [_failTimes] requests (on paths starting with
  /// [_failRoute], when set) answer [_failCode].
  String? _failCode;
  int _failTimes = 0;
  String? _failRoute;

  /// `/__fake/drop-answer`: the next change set is written, but its answer
  /// and its change event are lost, so the browser must ask for its status.
  bool dropNextApply = false;

  /// `/__fake/player`: whether the viewer is on the server for previews.
  bool playerOnline = true;

  /// Makes the next [times] requests answer [code].
  void failNext(String code, int times, {String? route}) {
    errorStatus(code);
    _failCode = code;
    _failTimes = times;
    _failRoute = route;
  }

  Map<String, Object?> get testControls => <String, Object?>{
        if (_failTimes > 0) 'fail': <String, Object?>{'code': _failCode, 'times': _failTimes, 'route': ?_failRoute},
        'dropNextApply': dropNextApply,
        'playerOnline': playerOnline,
      };

  /// Back to the data folder as loaded, with no tokens, previews or records.
  void reset() {
    events.closeAll('server-stopping');
    previews.clear();
    tokens.clear();
    changes.clear();
    imports.reset();
    documents.load();
    _failTimes = 0;
    dropNextApply = false;
    playerOnline = true;
  }

  /// Once a second: expired tokens close their streams; expired previews end.
  void housekeeping() {
    for (final String expired in tokens.expire()) {
      events.close(expired, 'token-expired');
      previews.endToken(expired);
    }
    previews.expire();
  }

  /// Revokes tokens, closes their streams and ends their previews.
  List<String> revoke(String idOrAll) {
    final List<String> ids = tokens.revoke(idOrAll);
    for (final String id in ids) {
      events.close(id, 'token-revoked');
      previews.endToken(id);
    }
    return ids;
  }

  /// CORS headers for an allowed browser origin on the direct path.
  Map<String, String> cors(FakeRequest request) {
    final String? origin = request.header('origin');
    if (request.transport != Transport.direct || origin == null || !settings.allowedOrigins.contains(origin)) {
      return const <String, String>{};
    }
    return <String, String>{..._cors, 'Access-Control-Allow-Origin': origin};
  }

  FakeResponse handle(FakeRequest request) {
    final Map<String, String> headers = cors(request);
    FakeResponse response;
    try {
      _injectedFailure(request);
      response = _route(request);
    } on ApiError catch (refused) {
      response = refused.toResponse();
    }
    return headers.isEmpty ? response : response.withHeaders(headers);
  }

  void _injectedFailure(FakeRequest request) {
    if (_failTimes <= 0 || request.method == 'OPTIONS' || request.route == '/v1/hello') return;
    if (_failRoute != null && !request.route.startsWith(_failRoute!)) return;
    _failTimes--;
    throw ApiError(_failCode!, 'Failed on purpose by /__fake/fail.');
  }

  void _origin(FakeRequest request) {
    if (request.transport != Transport.direct) return;
    final String? origin = request.header('origin');
    if (origin != null && !settings.allowedOrigins.contains(origin)) {
      throw ApiError('origin-not-allowed', 'Origin $origin is not in [editor] allowedOrigins.');
    }
  }

  FakeResponse _route(FakeRequest request) {
    _origin(request);
    final String method = request.method;
    final String path = request.route;
    if (method == 'OPTIONS') return const FakeResponse(204);
    if (!path.startsWith('/v1/')) {
      if (RegExp(r'^/v[0-9]+/').hasMatch(path)) throw ApiError('unsupported-protocol', 'This server speaks protocol 1 at /v1/.');
      throw ApiError('not-found', 'No endpoint $path.');
    }
    final List<String> segments = path.substring(4).split('/');
    if (method == 'GET' && path == '/v1/hello') return FakeResponse(200, body: hello());
    if (method == 'POST' && path == '/v1/pair') return _pair(request);
    final FakeToken token = tokens.authenticate(_bearer(request));
    final _Route route = _match(method, segments) ?? (throw ApiError('not-found', 'No endpoint $method $path.'));
    if (route.scope != null && !token.has(route.scope!)) throw ApiError('scope-missing', 'This token lacks ${route.scope}.');
    if (route.anyApply && !token.scopes.any((String scope) => scope.startsWith('apply:'))) {
      throw ApiError('scope-missing', 'This token has no apply scope.');
    }
    if (method == 'POST' || method == 'DELETE') _counter(request, token);
    return route.handler(request, token, segments);
  }

  _Route? _match(String method, List<String> s) {
    final int n = s.length;
    switch (method) {
      case 'GET':
        return switch (s.first) {
          'capabilities' when n == 1 => _Route('read', false, _capabilities),
          'schemas' when n == 1 => _Route('read', false, _schemasEndpoint),
          'lookups' when n == 1 => _Route('read', false, _lookups),
          'catalog' when n == 1 => _Route('read', false, _catalog),
          'documents' when n >= 3 => _Route('read', false, _document),
          'skins' when n == 3 && s[2] == 'texture.png' => _Route('read', false, _skin),
          'changes' when n == 2 => _Route('read', false, _changeStatus),
          'imports' when n == 1 => _Route('import', false, _importStatus),
          'terrain' when n == 2 && s[1] == 'surface' => _Route('terrain', false, (FakeRequest request, FakeToken token, List<String> _) => fakeTerrainSurface(request, _anchors())),
          'terrain' when n == 2 && s[1] == 'blocks' => _Route('terrain', false, (FakeRequest request, FakeToken token, List<String> _) => fakeTerrainBlocks(request, _anchors())),
          _ => null,
        };
      case 'POST':
        return switch (s.first) {
          'validate' when n == 1 => _Route('validate', false, _validate),
          'previews' when n == 1 => _Route('preview', false, _preview),
          'changes' when n == 1 => _Route(null, true, _apply),
          'changes' when n == 3 && s[2] == 'undo' => _Route(null, true, _undo),
          'imports' when n == 1 => _Route('import', false, _upload),
          'imports' when n == 2 && s[1] == 'stage' => _Route('import', false, _importStage),
          'imports' when n == 3 && const <String>{'apply', 'accept', 'rollback'}.contains(s[2]) => _Route('import', false, _importStep),
          'npcs' when n == 3 && (s[2] == 'spawn' || s[2] == 'despawn') => _Route('spawn', false, _spawn),
          _ => null,
        };
      case 'DELETE':
        return s.first == 'previews' && n == 2 ? _Route('preview', false, _endPreview) : null;
      default:
        return null;
    }
  }

  /// Where the NPCs stand: terrain is readable within the radius of these.
  List<FakeAnchor> _anchors() => <FakeAnchor>[
        for (final StoredDocument document in documents.list(DocumentKind.npc))
          if (_position(document) case final Map<String, Object?> position
              when position['world'] is String && position['x'] is num && position['z'] is num)
            (world: position['world']! as String, x: (position['x']! as num).toDouble(), z: (position['z']! as num).toDouble()),
      ];

  static Map<String, Object?>? _position(StoredDocument document) {
    try {
      final Object? json = jsonDecode(document.content);
      final Object? position = json is Map<String, Object?> ? json['position'] : null;
      return position is Map<String, Object?> ? position : null;
    } on FormatException {
      return null;
    }
  }

  static String _bearer(FakeRequest request) {
    final String? header = request.header('authorization');
    if (header == null || header.length < 7 || header.substring(0, 7).toLowerCase() != 'bearer ') {
      throw ApiError('unauthorized', 'Authorization: Bearer <token> is required.');
    }
    return header.substring(7).trim();
  }

  void _counter(FakeRequest request, FakeToken token) {
    final String? header = request.header('x-animus-counter');
    if (header == null) throw invalid('X-Animus-Counter is required on POST and DELETE.');
    final int? value = int.tryParse(header.trim());
    if (value == null) throw invalid('X-Animus-Counter must be an integer.');
    tokens.advance(token, value);
  }

  Map<String, Object?> hello() => <String, Object?>{
        'protocol': 1,
        'protocols': <int>[1],
        'fingerprint': identity.fingerprint,
        'animus': '0.0.0-fake',
        'platform': 'paper',
        'minecraft': '26.1.2',
        'serverName': settings.serverName,
      };

  FakeResponse _pair(FakeRequest request) {
    final Map<String, Object?> body = bodyObject(request);
    fields(body, r'$', <String>{'tokenId', 'secret', 'client'});
    final String tokenId = stringField(body, 'tokenId', tokenIdPattern, r'$.tokenId');
    final String secret = stringField(body, 'secret', secretPattern, r'$.secret');
    final String? client = optionalString(body, 'client', 128);
    final (String text, FakeToken token) = tokens.exchange(tokenId, secret, client);
    return FakeResponse(200, body: <String, Object?>{
      'token': text,
      'tokenId': token.id,
      'scopes': token.scopes,
      'expiresAt': instant(token.expires),
      'fingerprint': identity.fingerprint,
      'counter': token.counter,
      'admin': settings.viewer,
    });
  }

  /// A small Paper server with PlaceholderAPI: a few ids of each registry.
  FakeResponse _lookups(FakeRequest request, FakeToken token, List<String> segments) => const FakeResponse(200, body: <String, Object?>{
        'registries': <String, Object?>{
          'items': <String>['minecraft:bread', 'minecraft:diamond', 'minecraft:diamond_sword', 'minecraft:emerald'],
          'sounds': <String>['minecraft:entity.villager.ambient', 'minecraft:entity.villager.yes'],
          'particles': <String>['minecraft:happy_villager', 'minecraft:heart'],
          'worlds': <String>['world', 'world_nether'],
        },
        'commands': <String>['animus', 'give', 'spawn', 'tp'],
        'papi': <String>['player', 'server'],
      });

  FakeResponse _capabilities(FakeRequest request, FakeToken token, List<String> segments) {
    Map<String, Object?> no(String reason) => <String, Object?>{'available': false, 'reason': reason};
    const Map<String, Object?> yes = <String, Object?>{'available': true};
    return FakeResponse(200, body: <String, Object?>{
      'features': <String, Object?>{
        'editor.listener': yes,
        'editor.relay': yes,
        'previews': yes,
        'imports': yes,
        'spawn': yes,
        'terrain': yes,
        'skins.texture': no('the server does not cache decoded skin textures; the editor loads them from the skin\'s texture URL'),
      },
      'modules': <Object?>[],
    });
  }

  FakeResponse _schemasEndpoint(FakeRequest request, FakeToken token, List<String> segments) {
    final Map<String, Object?> schemas = _schemas ??= <String, Object?>{
      for (final MapEntry<String, String> entry in _schemaFiles.entries)
        if (sharedSchemaSources.containsKey(entry.value)) entry.key: jsonDecode(sharedSchemaSources[entry.value]!),
    };
    return FakeResponse(200, body: <String, Object?>{'schemas': schemas});
  }

  FakeResponse _catalog(FakeRequest request, FakeToken token, List<String> segments) {
    final Map<String, String> query = request.query;
    for (final String key in query.keys) {
      if (key != 'kind' && key != 'after') throw invalid('\$query.$key: unknown field');
    }
    DocumentKind? kind;
    final String? kindName = query['kind'];
    if (kindName != null && kindName.isNotEmpty) {
      kind = DocumentKind.parse(kindName) ?? (throw invalid('kind: unknown kind $kindName'));
    }
    final String? rawAfter = query['after'];
    final (int, String)? after = rawAfter == null || rawAfter.isEmpty ? null : _cursor(rawAfter);
    final List<Map<String, Object?>> page = <Map<String, Object?>>[];
    String? next;
    for (final StoredDocument document in documents.list(kind)) {
      if (after != null && _compare(document.kind.index, document.path, after) <= 0) continue;
      if (page.length == settings.catalogPageSize) {
        final Map<String, Object?> last = page.last;
        next = base64UrlNoPadding(utf8.encode('${DocumentKind.parse(last['kind']! as String)!.index}\n${last['path']}'));
        break;
      }
      page.add(document.version);
    }
    return FakeResponse(200, body: <String, Object?>{'documents': page, 'next': next});
  }

  static int _compare(int kind, String path, (int, String) after) =>
      kind != after.$1 ? kind.compareTo(after.$1) : path.compareTo(after.$2);

  static (int, String) _cursor(String value) {
    try {
      final String key = utf8.decode(base64Url.decode(base64Url.normalize(value)));
      final RegExpMatch? match = RegExp(r'^([0-9]{1,2})\n([a-z0-9_./-]{1,1024})$').firstMatch(key);
      if (match != null) return (int.parse(match.group(1)!), match.group(2)!);
    } on FormatException {
      // Refused below.
    }
    throw invalid('after: not a catalog cursor');
  }

  static String _decode(String segment) {
    try {
      return Uri.decodeComponent(segment);
    } on ArgumentError {
      throw invalid('The path is not URL encoded.');
    } on FormatException {
      throw invalid('The path is not URL encoded.');
    }
  }

  FakeResponse _document(FakeRequest request, FakeToken token, List<String> segments) {
    final DocumentKind kind = DocumentKind.parse(segments[1]) ?? (throw ApiError('not-found', 'Unknown kind ${segments[1]}.'));
    final String id = <String>[for (final String segment in segments.sublist(2)) _decode(segment)].join('/');
    if (!documentIdPattern.hasMatch(id)) throw invalid("'$id' is not a document id.");
    final StoredDocument stored = documents.find(kind, id) ?? (throw ApiError('not-found', 'No ${kind.wireName} $id.'));
    final Map<String, Object?> body = stored.version;
    Map<String, Object?> source;
    List<Map<String, Object?>> diagnostics;
    try {
      final Object? json = jsonDecode(stored.content);
      if (json is! Map<String, Object?>) throw const FormatException('not an object');
      source = json;
      diagnostics = validateDocument(kind, id, stored.path, source);
    } on FormatException catch (unreadable) {
      source = <String, Object?>{};
      diagnostics = <Map<String, Object?>>[
        diagnostic('error', r'$', 'The stored file is not one JSON object: ${unreadable.message}', 'json'),
      ];
    }
    body['source'] = source;
    if (diagnostics.isNotEmpty) body['diagnostics'] = diagnostics;
    return FakeResponse(200, body: body);
  }

  FakeResponse _skin(FakeRequest request, FakeToken token, List<String> segments) =>
      throw ApiError('not-found', 'This server keeps no decoded skin textures; read the skin document and load its texture URL.');

  FakeResponse _validate(FakeRequest request, FakeToken token, List<String> segments) {
    final Map<String, Object?> body = bodyObject(request);
    fields(body, r'$', <String>{'documents'});
    final List<Change> list = parseChanges(body);
    final Set<String> behaviors = <String>{for (final StoredDocument document in documents.list(DocumentKind.behavior)) document.id};
    for (final Change change in list) {
      if (change.kind != DocumentKind.behavior) continue;
      if (change.delete) {
        behaviors.remove(change.id);
      } else {
        behaviors.add(change.id);
      }
    }
    bool valid = true;
    final List<Map<String, Object?>> results = <Map<String, Object?>>[];
    for (final Change change in list) {
      final Located location = documents.locate(change.kind, change.id);
      List<Map<String, Object?>> diagnostics = <Map<String, Object?>>[];
      if (!change.delete) {
        final Map<String, Object?> source = copyJson(change.source)! as Map<String, Object?>;
        final String base = change.from == null ? location.path : documents.locate(change.kind, change.from!).path;
        if (revisioned.contains(change.kind)) source['revision'] = (documents.at(base)?.revision ?? 0) + 1;
        diagnostics = validateDocument(change.kind, change.id, location.path, source);
        final Object? references = source['behaviors'];
        if (change.kind == DocumentKind.npc && references is List<Object?>) {
          for (int index = 0; index < references.length; index++) {
            final Object? reference = references[index];
            if (reference is String && !behaviors.contains(reference)) {
              diagnostics.add(diagnostic('warning', '\$.behaviors[$index]', 'behavior $reference is not in the catalog or this change set', 'behavior-missing'));
            }
          }
        }
      }
      valid &= !hasErrors(diagnostics);
      results.add(documentDiagnostics(change.kind, change.id, diagnostics));
    }
    return FakeResponse(200, body: <String, Object?>{'valid': valid, 'documents': results});
  }

  FakeResponse _preview(FakeRequest request, FakeToken token, List<String> segments) {
    final Map<String, Object?> body = bodyObject(request);
    fields(body, r'$', <String>{'previewId', 'documents'});
    final String? replace = body.containsKey('previewId') ? stringField(body, 'previewId', objectIdPattern, r'$.previewId') : null;
    final List<Change> list = parseChanges(body);
    final List<Map<String, Object?>> refusedDocuments = <Map<String, Object?>>[];
    final List<String> npcs = <String>[];
    bool refused = false;
    for (final Change change in list) {
      if (change.delete) continue;
      final Located location = documents.locate(change.kind, change.id);
      final Map<String, Object?> source = copyJson(change.source)! as Map<String, Object?>;
      if (revisioned.contains(change.kind) && !source.containsKey('revision')) source['revision'] = 1;
      final List<Map<String, Object?>> diagnostics = validateDocument(change.kind, change.id, location.path, source);
      final List<Map<String, Object?>> refusing = refusals(change.kind, source);
      refused |= refusing.isNotEmpty;
      diagnostics.addAll(refusing);
      if (hasErrors(diagnostics)) {
        refusedDocuments.add(documentDiagnostics(change.kind, change.id, diagnostics));
      } else if (change.kind == DocumentKind.npc) {
        npcs.add(change.id);
      }
    }
    if (refusedDocuments.isNotEmpty) {
      final String names = refusedDocuments.map((Map<String, Object?> entry) => '${entry['kind']} ${entry['id']}').join(', ');
      throw ApiError(
        refused ? 'preview-refused' : 'invalid-request',
        refused ? 'The preview would run something previews refuse: $names' : 'Some documents are invalid: $names',
        diagnostics: <Map<String, Object?>>[
          for (final Map<String, Object?> entry in refusedDocuments) ...(entry['diagnostics']! as List<Map<String, Object?>>),
        ],
      );
    }
    final int maximum = limit('npcsPerPreview');
    if (npcs.length > maximum) throw ApiError('too-large', 'A preview shows at most $maximum NPCs.');
    if (!playerOnline) throw ApiError('player-offline', 'The viewer ${settings.viewer} is not online.');
    final Preview started = previews.start(replace, token.id, settings.viewer, npcs);
    final List<String> hidden = <String>[
      for (final String id in started.npcs)
        if (documents.find(DocumentKind.npc, id) != null) id,
    ];
    events.publish(<String, Object?>{'type': 'preview', 'previewId': started.id, 'state': 'running'});
    return FakeResponse(200, body: <String, Object?>{
      'previewId': started.id,
      'viewer': started.viewer,
      'expiresAt': instant(started.expires),
      'npcs': started.npcs,
      'hidden': hidden,
    });
  }

  FakeResponse _endPreview(FakeRequest request, FakeToken token, List<String> segments) {
    final String id = segments[1];
    final Preview? preview = previews.find(id);
    if (preview == null || preview.tokenId != token.id) throw ApiError('not-found', 'No running preview $id for this token.');
    previews.end(id, 'ended');
    return const FakeResponse(204);
  }

  void _scopes(FakeToken token, Set<DocumentKind> kinds) {
    for (final DocumentKind kind in kinds) {
      final String scope = applyScope(kind.wireName);
      if (!token.has(scope)) throw ApiError('scope-missing', 'This token lacks $scope.');
    }
  }

  FakeResponse _apply(FakeRequest request, FakeToken token, List<String> segments) {
    final Map<String, Object?> body = bodyObject(request);
    final ChangeSet set = parseChangeSet(body);
    _scopes(token, <DocumentKind>{for (final Change change in set.documents) change.kind});
    final Outcome outcome = changes.apply(set, sha256Text(jsonEncode(body)));
    if (dropNextApply && outcome.committed) {
      dropNextApply = false;
      // Written, but the answer and the change event are lost: only
      // GET /v1/changes/{key} can tell the browser what happened.
      publishCatalog(outcome.catalog, outcome.deleted);
      return FakeResponse.dropped;
    }
    _published(outcome);
    return FakeResponse(outcome.status, body: outcome.body);
  }

  FakeResponse _undo(FakeRequest request, FakeToken token, List<String> segments) {
    final String changeId = segments[1];
    if (!uuidPattern.hasMatch(changeId)) throw ApiError('not-found', 'No applied change $changeId.');
    final Map<String, Object?> body = bodyObject(request);
    fields(body, r'$', <String>{'idempotencyKey'});
    final String key = stringField(body, 'idempotencyKey', keyPattern, r'$.idempotencyKey');
    final Outcome outcome = changes.undo(changeId, key, (Set<DocumentKind> kinds) => _scopes(token, kinds));
    _published(outcome);
    return FakeResponse(outcome.status, body: outcome.body);
  }

  void _published(Outcome outcome) {
    if (!outcome.committed) return;
    events.publish(<String, Object?>{'type': 'change', 'result': outcome.body});
    publishCatalog(outcome.catalog, outcome.deleted);
  }

  void publishCatalog(List<Map<String, Object?>> versions, List<Map<String, Object?>> deleted) {
    events.publish(<String, Object?>{
      'type': 'catalog',
      'documents': versions,
      if (deleted.isNotEmpty) 'deleted': deleted,
    });
  }

  FakeResponse _changeStatus(FakeRequest request, FakeToken token, List<String> segments) {
    final String key = segments[1];
    if (!keyPattern.hasMatch(key)) throw invalid('Not an idempotency key: $key');
    return FakeResponse(200, body: changes.status(key));
  }

  FakeResponse _importStatus(FakeRequest request, FakeToken token, List<String> segments) => FakeResponse(200, body: imports.status());

  /// The file's bytes on the direct path, a base64 JSON string over the relay.
  FakeResponse _upload(FakeRequest request, FakeToken token, List<String> segments) {
    final Map<String, String> query = request.query;
    for (final String key in query.keys) {
      if (key != 'file' && key != 'name') throw invalid('Unknown query parameter $key.');
    }
    final Object? body = request.body;
    if (body is! String) throw invalid('The upload is the file\'s bytes, or over the relay a base64 JSON string.');
    final Uint8List bytes;
    try {
      bytes = base64Decode(body);
    } on FormatException {
      throw invalid('The upload is not base64.');
    }
    if (bytes.isEmpty) throw invalid('The uploaded file is empty.');
    if (bytes.length > limit('importBodyBytes')) throw ApiError('too-large', 'Imports are limited to ${limit('importBodyBytes')} bytes.');
    return FakeResponse(200, body: imports.upload(query['file'] ?? 'citizens', query['name'], bytes));
  }

  FakeResponse _importStage(FakeRequest request, FakeToken token, List<String> segments) {
    final Map<String, Object?> body = request.body == null ? <String, Object?>{} : bodyObject(request);
    fields(body, r'$', <String>{'mode', 'label', 'worlds'});
    final String mode = body.containsKey('mode') ? stringField(body, 'mode', RegExp(r'^(?:reject|replace|duplicate)$'), r'$.mode') : 'reject';
    final String? label = body.containsKey('label') ? stringField(body, 'label', RegExp(r'^[a-z0-9_-]{1,32}$'), r'$.label') : null;
    if ((mode == 'duplicate') != (label != null)) throw invalid(r'$.label: a duplicate import needs a copy label, and only a duplicate takes one.');
    final Object? worlds = body['worlds'];
    if (worlds != null && worlds is! Map<String, Object?>) throw invalid(r'$.worlds: expected an object of old world names to new ones.');
    final Map<String, String> mapped = <String, String>{
      for (final MapEntry<String, Object?> entry in ((worlds as Map<String, Object?>?) ?? const <String, Object?>{}).entries) entry.key: '${entry.value}',
    };
    return FakeResponse(200, body: imports.stage(mode, label, mapped));
  }

  FakeResponse _importStep(FakeRequest request, FakeToken token, List<String> segments) {
    final String id = segments[1];
    if (!RegExp(r'^[a-f0-9]{64}$').hasMatch(id)) throw ApiError('not-found', 'No staged import $id.');
    for (final String scope in const <String>['apply:npcs', 'apply:behaviors', 'apply:gui']) {
      if (!token.has(scope)) throw ApiError('scope-missing', 'This token lacks $scope.');
    }
    switch (segments[2]) {
      case 'apply':
        return FakeResponse(200, body: imports.apply(id));
      case 'accept':
        final Map<String, Object?> body = bodyObject(request);
        fields(body, r'$', <String>{'categories'});
        final Object? categories = body['categories'];
        if (categories is! List<Object?> || categories.isEmpty || categories.any((Object? c) => c is! String)) {
          throw invalid(r'$.categories: expected a non-empty array of review categories.');
        }
        return FakeResponse(200, body: imports.accept(id, categories.cast<String>()));
      default:
        return FakeResponse(200, body: imports.rollback(id));
    }
  }

  FakeResponse _spawn(FakeRequest request, FakeToken token, List<String> segments) {
    final String id = _decode(segments[1]);
    if (!RegExp(r'^(?:[a-z0-9][a-z0-9_-]{0,63}/){0,8}[a-z0-9][a-z0-9_-]{0,63}$').hasMatch(id)) {
      throw ApiError('not-found', 'No NPC $id; NPC ids are their path under npcs/, such as town/guard.');
    }
    if (documents.find(DocumentKind.npc, id) == null) throw ApiError('not-found', 'No NPC $id.');
    return FakeResponse(200, body: <String, Object?>{'id': id, 'spawned': segments[2] == 'spawn'});
  }

  /// A server-side edit outside the API (`POST /__fake/edit`): bumps the
  /// revision of revisioned kinds and publishes a catalog event.
  Map<String, Object?> serverEdit(DocumentKind kind, String id, Map<String, Object?> source) {
    final Located location = documents.locate(kind, id);
    final Map<String, Object?> copy = copyJson(source)! as Map<String, Object?>;
    if (revisioned.contains(kind)) copy['revision'] = (documents.at(location.path)?.revision ?? 0) + 1;
    final StoredDocument stored = documents.write(location, prettyJson(copy));
    publishCatalog(<Map<String, Object?>>[stored.version], const <Map<String, Object?>>[]);
    return stored.version;
  }

  /// `GET /v1/events`: the token arrives in the Authorization header (the
  /// relay forwards the browser's; the direct path copies it from event-auth).
  EventsOpened openEvents(FakeRequest request, StreamPath path, void Function(Map<String, Object?> event) send, void Function() close) {
    try {
      _origin(request);
      final FakeToken token = tokens.authenticate(_bearer(request));
      if (!token.has('read')) throw ApiError('scope-missing', 'This token lacks read.');
      if (events.open >= limit('openConnections')) {
        throw ApiError('busy', 'The server has its maximum of event streams open.', retryAfterMillis: 5000);
      }
      final void Function() remove = events.add(EventStream(token.id, path, send, close));
      final Map<String, String> headers = cors(request);
      return EventsOpened(FakeResponse(200, headers: headers), remove);
    } on ApiError catch (refused) {
      return EventsOpened(refused.toResponse(), null);
    }
  }
}
