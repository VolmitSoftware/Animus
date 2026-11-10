import 'package:animus_editor/logic/npc/npc_bulk.dart';
import 'package:animus_editor/logic/npc/npc_integrations.dart';
import 'package:test/test.dart';

void main() {
  group('plugin integrations', () {
    test('should read the plugins the server reports running', () {
      final Map<String, Object?> capabilities = <String, Object?>{
        'features': <String, Object?>{
          'modelengine': <String, Object?>{'available': true},
          'libsdisguises': <String, Object?>{'available': false, 'reason': 'LibsDisguises is not installed'},
          'combat': <String, Object?>{'available': true},
        },
      };
      expect(reportedPlugins(capabilities), <String>{'modelengine'});
      expect(reportedPlugins(null), isEmpty);
    });

    test('should offer integration modules and fields only with their plugin', () {
      expect(offersModule('animus:modelengine', const <String>{}), isFalse);
      expect(offersModule('animus:modelengine', const <String>{'modelengine'}), isTrue);
      expect(offersModule('mythic:mob', const <String>{'modelengine'}), isFalse);
      expect(offersModule('animus:combat', const <String>{}), isTrue);
      expect(offersField('disguise', const <String>{}), isFalse);
      expect(offersField('disguise', const <String>{'libsdisguises'}), isTrue);
      expect(offersField('name', const <String>{}), isTrue);
    });

    test('should list a plugin module for bulk toggling only with its plugin', () {
      expect(moduleKeys(const [], plugins: const <String>{}), isNot(contains('animus:modelengine')));
      expect(moduleKeys(const [], plugins: const <String>{'modelengine'}), contains('animus:modelengine'));
    });
  });
}
