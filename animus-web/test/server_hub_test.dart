import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/services/server/server_connection.dart';
import 'package:animus_editor/services/server/server_profiles.dart';
import 'package:animus_editor/services/server/transport.dart';
import 'package:animus_editor/state/server_hub.dart';
import 'package:test/test.dart';

import 'server_connection_test.dart' show profile, settle, token;
import 'support/fake_transport.dart';

void main() {
  ServerHub hub(ServerProfileStore profiles) => ServerHub(
        profiles: profiles,
        open: (ConnectionPath path, String url, String fingerprint) => FakeNetwork(serverHandler()).open(path, url),
        schemas: SharedSchemas.embedded,
      );

  test('should connect a paired server nobody has opened yet', () async {
    final ServerProfileStore profiles = ServerProfileStore(local: MemoryKeyValueStore(), session: MemoryKeyValueStore());
    profiles.save(<ServerProfile>[profile]);
    profiles.saveToken(profile, token);
    final ServerHub servers = hub(profiles);
    expect(servers.status(profile.id).phase, ConnectionPhase.offline);
    servers.connectIdle();
    await settle();
    expect(servers.status(profile.id).phase, ConnectionPhase.online);
  });

  test('should leave a server without a token alone', () async {
    final ServerProfileStore profiles = ServerProfileStore(local: MemoryKeyValueStore(), session: MemoryKeyValueStore());
    profiles.save(<ServerProfile>[profile]);
    final ServerHub servers = hub(profiles);
    servers.connectIdle();
    await settle();
    expect(servers.status(profile.id).phase, ConnectionPhase.offline);
  });
}
