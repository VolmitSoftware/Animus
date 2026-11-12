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
  });
}
