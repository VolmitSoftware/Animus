import 'package:animus_editor/logic/terrain/terrain_access.dart';
import 'package:test/test.dart';

const Map<String, Object?> sharing = <String, Object?>{
  'features': <String, Object?>{
    'terrain': <String, Object?>{'available': true},
  },
};

const List<String> defaults = <String>['read', 'validate', 'preview', 'apply:npcs', 'apply:behaviors', 'apply:gui', 'apply:rigs', 'terrain'];

void main() {
  group('terrain access', () {
    test('should allow terrain when the server shares it and the token has the scope', () {
      expect(terrainAbsence(online: true, capabilities: sharing, scopes: defaults), isNull);
    });

    test('should name the missing scope and how to fix it for a token paired without it', () {
      final String? absence = terrainAbsence(online: true, capabilities: sharing, scopes: defaults.where((String scope) => scope != 'terrain').toList());
      expect(absence, contains('no terrain scope'));
      expect(absence, contains('/animus editor pair'));
    });

    test('should pass on the server reason when it does not share terrain', () {
      final String? absence = terrainAbsence(
        online: true,
        capabilities: const <String, Object?>{
          'features': <String, Object?>{
            'terrain': <String, Object?>{'available': false, 'reason': '[editor] terrainRadius is 0'},
          },
        },
        scopes: defaults,
      );
      expect(absence, contains('[editor] terrainRadius is 0'));
    });

    test('should ask for an update when the server predates terrain', () {
      expect(terrainAbsence(online: true, capabilities: const <String, Object?>{'features': <String, Object?>{}}, scopes: const <String>['read']), contains('Update Animus'));
    });

    test('should wait for the connection while offline', () {
      expect(terrainAbsence(online: false, capabilities: null, scopes: defaults), contains('connected'));
    });

    test('should send a second tab to the tab that holds the connection', () {
      final String? absence = terrainAbsence(online: false, capabilities: null, scopes: defaults, elsewhere: true);
      expect(absence, 'Live editing runs in another tab; terrain shows there.');
      expect(terrainLine(TerrainFacts(absence: absence!))?.line, 'In another tab');
    });
  });
  terrainLines();
}

void terrainLines() {
  group('terrain line', () {
    test('should say nothing without a paired server or with the terrain off', () {
      expect(terrainLine(const TerrainFacts(absence: '')), isNull);
    });

    test('should shorten the absence and keep its sentence for the tip', () {
      final TerrainLine? line = terrainLine(const TerrainFacts(absence: 'This pairing has no terrain scope, so the stage cannot show the server\'s blocks.'));
      expect(line?.line, 'Terrain needs a new pairing');
      expect(line?.detail, contains('no terrain scope'));
    });

    test('should name the connection in the words the badge and save bar use', () {
      const String waiting = 'Terrain shows once the server is connected.';
      final TerrainLine? restart = terrainLine(const TerrainFacts(absence: waiting, connection: 'Reconnecting'));
      expect(restart?.line, 'Reconnecting');
      expect(restart?.detail, waiting);
      expect(terrainLine(const TerrainFacts(absence: waiting, connection: 'Offline'))?.line, 'Offline');
      expect(terrainLine(const TerrainFacts(absence: waiting))?.line, isNot('Server offline'));
    });

    test('should tell an unloaded world from chunks nobody is near', () {
      expect(terrainLine(const TerrainFacts(worldUnloaded: true, chunks: 9, unloaded: 9))?.line, 'This world is not loaded on the server.');
      expect(terrainLine(const TerrainFacts(chunks: 9, unloaded: 9))?.line, 'Stand near this NPC in game to load its terrain.');
      expect(terrainLine(const TerrainFacts(chunks: 9, unloaded: 3), near: 'this route')?.line, 'Stand near this route in game to load its terrain.');
    });

    test('should report a failure once, loading, and the rest only in the tip', () {
      expect(terrainLine(const TerrainFacts(failure: 'GET pack.json: HTTP 404'))?.line, 'Terrain did not load');
      expect(terrainLine(const TerrainFacts(loading: true))?.line, 'Loading terrain');
      final TerrainLine? quiet = terrainLine(const TerrainFacts(chunks: 9, truncated: true, fullStates: false));
      expect(quiet?.line, isNull);
      expect(quiet?.detail, contains('first 9 chunks'));
      expect(terrainLine(const TerrainFacts(chunks: 9, forbidden: 2))?.line, 'Part of the terrain is out of reach');
    });
  });
}
