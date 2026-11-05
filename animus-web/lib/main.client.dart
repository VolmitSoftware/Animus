/// The entrypoint for the Animus editor client app.
library;

import 'package:jaspr/client.dart';
import 'package:web/web.dart' as web;

import 'app.dart';
import 'logic/shared_schemas.dart';
import 'main.client.options.dart';
import 'state/editor_store.dart';
import 'state/indexed_db_workspace_repository.dart';
import 'state/workspace_repository.dart';

Future<void> main() async {
  Jaspr.initializeApp(options: defaultClientOptions);
  final WorkspaceRepository repository = await IndexedDbWorkspaceRepository.open() ?? MemoryWorkspaceRepository();
  final EditorStore store = EditorStore(repository: repository, schemas: SharedSchemas.embedded);
  runApp(AnimusApp(store: store));
  web.document.getElementById('loading')?.remove();
}
