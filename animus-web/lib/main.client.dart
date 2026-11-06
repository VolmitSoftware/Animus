/// The entrypoint for the Animus editor client app.
library;

import 'package:jaspr/client.dart';
import 'package:web/web.dart' as web;

import 'app.dart';
import 'logic/shared_schemas.dart';
import 'main.client.options.dart';
import 'services/browser.dart';
import 'services/server/browser_storage.dart';
import 'services/server/browser_transports.dart';
import 'services/server/server_profiles.dart';
import 'state/editor_store.dart';
import 'state/server_hub.dart';
import 'state/indexed_db_workspace_repository.dart';
import 'state/workspace_repository.dart';

Future<void> main() async {
  Jaspr.initializeApp(options: defaultClientOptions);
  final IndexedDbWorkspaceRepository? stored = await IndexedDbWorkspaceRepository.open();
  final WorkspaceRepository repository = stored ?? MemoryWorkspaceRepository();
  final EditorStore store = EditorStore(
    repository: repository,
    schemas: SharedSchemas.embedded,
    events: BrowserWorkspaceEvents.open(),
    locks: BrowserWorkspaceLocks.open(),
    persistent: stored != null,
    onWorkspaceCreated: requestPersistentStorage,
  );
  guardUnload(() => store.hasPendingWork);
  final ServerHub hub = ServerHub(
    profiles: ServerProfileStore(local: BrowserKeyValueStore.local(), session: BrowserKeyValueStore.session()),
    open: openBrowserTransport,
    schemas: SharedSchemas.embedded,
    onForget: (String serverId) => store.deleteWorkspace(ServerHub.workspaceIdOf(serverId)),
  );
  onBrowserWake(hub.probeDirect);
  runApp(AnimusApp(store: store, hub: hub));
  web.document.getElementById('loading')?.remove();
}
