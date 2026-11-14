import 'dart:async';

import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/services/server/connection_status.dart';
import 'package:animus_editor/services/server/page_activity.dart';
import 'package:animus_editor/services/server/server_pairing.dart';
import 'package:animus_editor/services/server/server_profiles.dart';
import 'package:animus_editor/services/server/transport.dart';
import 'package:animus_editor/state/server_hub.dart';
import 'package:test/test.dart';

import 'server_connection_test.dart' show Waits, profile, settle, token;
import 'support/fake_transport.dart';

void main() {
  late FakeNetwork network;
  late Waits waits;
  late DateTime clock;

  ServerHub hub(ServerProfileStore profiles, {List<String>? forgotten}) => ServerHub(
        profiles: profiles,
        open: (ConnectionPath path, String url, String fingerprint) => network.open(path, url),
        schemas: SharedSchemas.embedded,
        onForget: (String id) async => forgotten?.add(id),
        delay: waits.call,
        now: () => clock,
      );

  ServerProfileStore saved({bool withToken = true}) {
    final ServerProfileStore profiles = ServerProfileStore(local: MemoryKeyValueStore(), session: MemoryKeyValueStore());
    profiles.save(<ServerProfile>[profile]);
    if (withToken) profiles.saveToken(profile, token);
    return profiles;
  }

  setUp(() {
    network = FakeNetwork(serverHandler());
    waits = Waits();
    clock = DateTime.utc(2026, 11, 4, 12);
  });

  tearDown(() => PageActivity.hidden = false);

  test('should check a saved server with one hello and no connection or stream', () async {
    final ServerHub servers = hub(saved());
    servers.probeServers();
    expect(servers.status(profile.id).label, 'Checking');
    await settle();
    expect(servers.status(profile.id).phase, ConnectionPhase.online);
    expect(servers.status(profile.id).label, 'Online');
    expect(network.sent.map(((ConnectionPath, ApiRequest) sent) => sent.$2.path), <String>['/v1/hello']);
    expect(network.streams, isEmpty);
    expect(network.transports.every((FakeTransport transport) => transport.closed), isTrue);
  });

  test('should say a saved server is not reachable when no path answers', () async {
    network.down.addAll(ConnectionPath.values);
    final ServerHub servers = hub(saved(withToken: false));
    servers.probeServers();
    await settle();
    expect(servers.status(profile.id).phase, ConnectionPhase.offline);
    expect(servers.status(profile.id).label, 'Not reachable');
  });

  test('should not check a server whose live connection is up, and show that instead', () async {
    final ServerHub servers = hub(saved());
    await servers.connect(profile.id);
    final int sent = network.sent.length;
    servers.probeServers();
    await settle();
    expect(network.sent.length, sent);
    expect(servers.status(profile.id).label, 'Online (direct)');
  });

  test('should take the name the server reports', () async {
    network.handler = (ApiRequest request) async {
      final ApiResponse answer = await serverHandler()(request);
      if (request.path != '/v1/hello') return answer;
      return ApiResponse(answer.status, <String, Object?>{...answer.body! as Map<String, Object?>, 'serverName': 'Harbor Town'}, headers: answer.headers);
    };
    final ServerHub servers = hub(saved());
    servers.probeServers();
    await settle();
    expect(servers.server(profile.id)!.name, 'Harbor Town');
  });

  test('should give up on a path that never opens and say not reachable', () async {
    final Stopwatch watch = Stopwatch()..start();
    final (ConnectionPath, String?)? found = await probeServer(
      profile,
      (ConnectionPath path, String url) => Completer<ServerTransport>().future,
      timeout: const Duration(milliseconds: 50),
    );
    expect(found, isNull);
    expect(watch.elapsed, lessThan(const Duration(seconds: 2)), reason: 'each path waits only for the open timeout');
  });

  test('should reconnect at once on a browser wake instead of waiting out the long backoff', () async {
    PageActivity.hidden = true;
    network.down.addAll(ConnectionPath.values);
    final ServerHub servers = hub(saved());
    await servers.connect(profile.id);
    clock = clock.add(const Duration(minutes: 2));
    expect(waits.release(const Duration(seconds: 1)), 1);
    await settle();
    expect(servers.status(profile.id).phase, ConnectionPhase.reconnecting);
    expect(waits.durations, contains(const Duration(seconds: 30)), reason: 'two minutes down in a hidden tab');
    network.down.clear();
    PageActivity.hidden = false;
    servers.wake();
    await settle();
    expect(servers.status(profile.id).phase, ConnectionPhase.online);
    expect(waits.durations, contains(const Duration(seconds: 30)), reason: 'online without that wait');
  });

  group('forgetting a server', () {
    List<ApiRequest> deletes() => <ApiRequest>[
          for (final (ConnectionPath _, ApiRequest request) in network.sent)
            if (request.method == 'DELETE') request,
        ];

    test('should ask an online server to revoke the pairing once and forget the token here', () async {
      network.handler = serverHandler(rest: (ApiRequest request) {
        if (request.method != 'DELETE' || request.path != '/v1/token') return const ApiResponse(404, <String, Object?>{});
        unawaited(network.lastStream.end('token-revoked'));
        return const ApiResponse(204, null);
      });
      final ServerProfileStore profiles = saved();
      final List<String> forgotten = <String>[];
      final ServerHub servers = hub(profiles, forgotten: forgotten);
      await servers.connect(profile.id);
      final List<ConnectionPhase> heard = <ConnectionPhase>[];
      servers.connection(profile.id).statuses.listen((ConnectionStatus status) => heard.add(status.phase));
      expect(servers.forgetPrompt(profile.id), 'Ends the pairing on the server. Changes not applied are lost.');
      expect(await servers.forget(profile.id), isNull, reason: 'nothing left to revoke');
      expect(deletes(), hasLength(1));
      expect(deletes().single.path, '/v1/token');
      expect(deletes().single.headers['Authorization'], 'Bearer anm_tok.secret');
      expect(heard, isNot(contains(ConnectionPhase.needsPairing)), reason: 'no pairing-revoked notice while forgetting');
      expect(profiles.token(profile), isNull);
      expect(servers.servers, isEmpty);
      expect(forgotten, <String>[profile.id]);
    });

    test('should still forget when the server does not know the request', () async {
      final ServerProfileStore profiles = saved();
      final ServerHub servers = hub(profiles);
      await servers.connect(profile.id);
      expect(await servers.forget(profile.id), 'tok');
      expect(deletes(), hasLength(1));
      expect(profiles.token(profile), isNull);
      expect(servers.servers, isEmpty);
    });

    test('should still forget when the server does not answer in time', () async {
      final ServerProfileStore profiles = saved();
      final ServerHub servers = hub(profiles);
      await servers.connect(profile.id);
      network.gate = (ConnectionPath path, ApiRequest request) => request.path == '/v1/token' ? Completer<void>().future : Future<void>.value();
      final Future<String?> forgetting = servers.forget(profile.id);
      await settle();
      expect(waits.release(const Duration(seconds: 3)), 1);
      expect(await forgetting, 'tok');
      expect(profiles.token(profile), isNull);
      expect(servers.servers, isEmpty);
    });

    test('should connect to a server it only checked and end the pairing there', () async {
      network.handler = serverHandler(rest: (ApiRequest request) {
        if (request.method != 'DELETE' || request.path != '/v1/token') return const ApiResponse(404, <String, Object?>{});
        unawaited(network.lastStream.end('token-revoked'));
        return const ApiResponse(204, null);
      });
      final ServerProfileStore profiles = saved();
      final ServerHub servers = hub(profiles);
      servers.probeServers();
      await settle();
      expect(servers.forgetPrompt(profile.id), 'Ends the pairing on the server. Changes not applied are lost.', reason: 'the start page row says Online');
      expect(await servers.forget(profile.id), isNull);
      expect(deletes(), hasLength(1));
      expect(profiles.token(profile), isNull);
      expect(ServerHub.forgotLine('Town', null), 'Forgot Town.');
    });

    test('should forget a server that cannot be reached and say the pairing stays valid, with no token or command', () async {
      network.down.addAll(ConnectionPath.values);
      final ServerProfileStore profiles = saved();
      final ServerHub servers = hub(profiles);
      servers.probeServers();
      await settle();
      expect(servers.forgetPrompt(profile.id), 'Not reachable now, so the pairing stays valid until it expires. Changes not applied are lost.');
      final Future<String?> forgetting = servers.forget(profile.id);
      await settle();
      waits.release(const Duration(seconds: 4));
      expect(await forgetting, 'tok');
      expect(deletes(), isEmpty);
      expect(profiles.token(profile), isNull);
      expect(ServerHub.forgotLine('Town', 'tok'), 'Forgot Town. Its pairing ends when it expires.');
      expect(ServerHub.forgotLine('Town', 'tok'), isNot(contains('tok')));
    });
  });
}
