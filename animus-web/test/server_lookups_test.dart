import 'package:animus_editor/logic/suggest/completion_sources.dart';
import 'package:test/test.dart';

void main() {
  test('should know the names of the players who own NPCs when the server sends them', () {
    final ServerLookups lookups = ServerLookups.fromJson(<String, Object?>{
      'registries': <String, Object?>{},
      'commands': <Object?>[],
      'papi': <Object?>[],
      'owners': <String, Object?>{'0000000a-0000-4000-8000-0000000000a1': 'Alex', 'not-a-uuid': 'Ghost', '0000000b-0000-4000-8000-0000000000b2': 7},
    });
    expect(lookups.owners, <String, String>{'0000000a-0000-4000-8000-0000000000a1': 'Alex'});
    expect(lookups.over(CompletionSources.empty).owners, lookups.owners);
  });

  test('should know no names from a server that sends none', () {
    expect(ServerLookups.fromJson(<String, Object?>{'registries': <String, Object?>{}, 'commands': <Object?>[], 'papi': <Object?>[]}).owners, isEmpty);
  });
}
