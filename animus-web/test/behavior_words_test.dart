import 'package:animus_editor/logic/inspect/behavior_summary.dart';
import 'package:animus_editor/logic/inspect/behavior_words.dart';
import 'package:test/test.dart';

void main() {
  test('should read a behavior id as a name with its acronyms upper case', () {
    expect(behaviorTitle('denizen:npc_dialogue_intercept'), 'NPC dialogue intercept');
    expect(behaviorTitle('town:open-gui'), 'Open GUI');
    expect(behaviorTitle('town:guard-talk'), 'Guard talk');
  });

  test('should name a capability in plain words', () {
    expect(capabilityWords('animus:actionbar'), 'Action bar');
    expect(capabilityWords('animus:play-sound'), 'Play sound');
  });

  group('timer words', () {
    test('should say how often a timer runs in the largest whole unit', () {
      expect(triggerWords(<String, Object?>{'event': 'timer', 'intervalTicks': 20}), 'Every second');
      expect(triggerWords(<String, Object?>{'event': 'timer', 'intervalTicks': 40}), 'Every 2 seconds');
      expect(triggerWords(<String, Object?>{'event': 'timer', 'intervalTicks': 30}), 'Every 1.5 seconds');
      expect(triggerWords(<String, Object?>{'event': 'timer', 'intervalTicks': 1200}), 'Every minute');
      expect(triggerWords(<String, Object?>{'event': 'timer', 'intervalTicks': 6000}), 'Every 5 minutes');
      expect(triggerWords(<String, Object?>{'event': 'timer', 'intervalTicks': 72000}), 'Every hour');
      expect(triggerWords(<String, Object?>{'event': 'timer', 'intervalTicks': 7}), 'Every 7 ticks');
    });

    test('should fall back to the event words without an interval', () {
      expect(triggerWords(<String, Object?>{'event': 'timer'}), 'On a timer');
    });
  });

  group('sound names', () {
    test('should name a sound in plain words without its namespace or registry folder', () {
      expect(soundName('minecraft:block.note_block.chime'), 'Note block chime');
      expect(soundName('entity.villager.ambient'), 'Villager ambient');
      expect(soundName('minecraft:item.armor.equip_iron'), 'Armor equip iron');
      expect(soundName('ui.button.click'), 'Button click');
      expect(soundName('ambient.cave'), 'Cave');
      expect(soundName('town:bell'), 'Bell');
    });

    test('should group sounds by what makes them', () {
      expect(soundGroup('block.note_block.chime'), 'Blocks');
      expect(soundGroup('minecraft:entity.villager.ambient'), 'Creatures');
      expect(soundGroup('item.armor.equip_iron'), 'Items');
      expect(soundGroup('ambient.cave'), 'Other');
    });
  });
}
