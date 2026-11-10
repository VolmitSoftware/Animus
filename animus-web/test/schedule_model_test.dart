import 'package:animus_editor/logic/npc/npc_catalog.dart';
import 'package:animus_editor/logic/npc/schedule_model.dart';
import 'package:test/test.dart';

void main() {
  group('schedule module', () {
    test('should start with one location at the block the NPC stands on', () {
      expect(scheduleStarter((10.5, 64.0, -3.5)), <String, Object?>{
        'locations': <Object?>[
          <String, Object?>{'time': 1000, 'x': 10, 'y': 64, 'z': -4},
        ],
      });
    });

    test('should start at the origin when the NPC has no position', () {
      expect(scheduleStarter(null)['locations'], <Object?>[
        <String, Object?>{'time': 1000, 'x': 0, 'y': 64, 'z': 0},
      ]);
    });

    test('should be switchable from the NPC browser', () {
      expect(toggleableModules, contains(scheduleModule));
    });
  });
}
