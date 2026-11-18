import 'package:animus_editor/logic/gui/gui_json.dart' show JsonMap;
import 'package:animus_editor/logic/inspect/behavior_words.dart';
import 'package:animus_editor/logic/offline/dry_run.dart';
import 'package:animus_editor/logic/suggest/field_profile.dart';
import 'package:animus_editor/logic/suggest/field_profiles.dart';
import 'package:test/test.dart';

const List<String> _sensed = <String>[
  'entity_proximity',
  'entity_collide',
  'hit_by_projectile',
  'item_dropped',
  'vehicle_bump',
  'leash_tie',
  'leash_pass',
  'npc_proximity',
  'npc_speech',
];

void main() {
  test('every sensed event has short words of its own', () {
    final Set<String> words = <String>{};
    for (final String event in _sensed) {
      final String said = eventWords(event);
      expect(said.split(' ').length, lessThanOrEqualTo(3), reason: event);
      words.add(said);
    }
    expect(words, hasLength(_sensed.length));
  });

  test('a test run of a sensed trigger says what happens and carries a player only when one can be involved', () {
    final JsonMap behavior = <String, Object?>{
      'entry': 'main',
      'triggers': <Object?>[
        <String, Object?>{'id': 'shot', 'event': 'hit_by_projectile', 'projectile': 'arrow'},
        <String, Object?>{'id': 'gift', 'event': 'item_dropped', 'radius': 3, 'item': 'minecraft:emerald'},
        <String, Object?>{'id': 'ear', 'event': 'npc_speech', 'radius': 8},
      ],
    };
    final List<DryRunStart> starts = dryRunStarts(behavior);
    expect(starts[0].label, 'shot: a projectile hits the NPC');
    expect(starts[0].carriesPlayer, isTrue);
    expect(starts[1].label, 'gift: an item is dropped within 3 blocks');
    expect(starts[1].context['item'], 'minecraft:emerald');
    expect(starts[2].label, 'ear: another NPC speaks within 8 blocks');
    expect(starts[2].carriesPlayer, isFalse);
    expect(starts[0].context['projectile'], 'minecraft:arrow');
    expect(starts[0].context['entity_type'], 'minecraft:player');
  });

  test('a test run of a damage trigger for player hits carries the player who hurt the NPC', () {
    final JsonMap behavior = <String, Object?>{
      'entry': 'main',
      'triggers': <Object?>[
        <String, Object?>{'id': 'hit', 'event': 'damage', 'players': true},
        <String, Object?>{'id': 'hurt', 'event': 'damage'},
      ],
    };
    final List<DryRunStart> starts = dryRunStarts(behavior);
    expect(starts[0].label, 'hit: a player hurts the NPC');
    expect(starts[0].carriesPlayer, isTrue);
    expect(starts[0].context['attacker_type'], 'player');
    expect(starts[0].context['damage'], isA<double>());
    expect(starts[1].label, 'hurt: the NPC is hurt');
    expect(starts[1].carriesPlayer, isFalse);
    expect(triggerSentence(<String, Object?>{'event': 'damage', 'players': true}), 'Starts when a player hurts the NPC');
    expect(triggerSentence(<String, Object?>{'event': 'damage', 'players': false}), 'Starts when something other than a player hurts the NPC');
    expect(triggerSentence(<String, Object?>{'event': 'damage'}), 'Starts when the NPC is hurt');
  });

  test('the npc filter suggests NPCs and the entity filter entity types', () {
    expect(triggerFieldProfile('npc', type: 'string'), isNot(same(FieldProfile.none)));
    expect(triggerFieldProfile('entity', type: 'string'), isNot(same(FieldProfile.none)));
    expect(triggerFieldLabel('named'), 'Name');
    expect(triggerFieldLabel('entityCooldownTicks'), 'Entity cooldown');
    expect(triggerFieldLabel('radius'), isNull);
  });
}
