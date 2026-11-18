import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/inspect/command_scope.dart';
import 'package:animus_editor/logic/inspect/inspect_schemas.dart';
import 'package:test/test.dart';

JsonMap _step(String capability, [JsonMap arguments = const <String, Object?>{}]) => <String, Object?>{'type': 'action', 'capability': capability, 'arguments': arguments};

JsonMap _behavior(JsonMap step) => <String, Object?>{
      'id': 'shop:guard',
      'entry': 'main',
      'routines': <String, Object?>{
        'main': <String, Object?>{'type': 'sequence', 'steps': <Object?>[_step('animus:say', <String, Object?>{'text': 'Hi'}), step]},
      },
    };

void main() {
  final Set<String> privileged = privilegedCapabilities(InspectSchemas.embedded.catalog);

  test('should name the steps that need Run commands to apply, as the server decides', () {
    expect(privileged, contains('animus:command'));
    expect(stepNeedsCommands(_step('animus:command', <String, Object?>{'command': 'say hi'}), privileged), 'runs a command');
    expect(stepNeedsCommands(_step('animus:give-item', <String, Object?>{'item': 'minecraft:bread'}), privileged), 'gives items');
    expect(stepNeedsCommands(_step('animus:advance-quest', <String, Object?>{'quest': 'gold'}), privileged), "can pay a quest's rewards");
    expect(stepNeedsCommands(_step('animus:complete-quest', <String, Object?>{'quest': 'gold', 'rewards': false}), privileged), isNull);
    expect(stepNeedsCommands(_step('animus:start-quest', <String, Object?>{'quest': 'gold'}), privileged), isNull, reason: 'a start without force pays nothing');
    expect(stepNeedsCommands(_step('animus:start-quest', <String, Object?>{'quest': 'gold', 'force': true}), privileged), "can pay a quest's rewards");
    expect(stepNeedsCommands(_step('animus:say', <String, Object?>{'text': 'Hi'}), privileged), isNull);
  });

  test('should find the reason anywhere in a behavior', () {
    expect(behaviorNeedsCommands(_behavior(_step('animus:give-item', <String, Object?>{'item': 'minecraft:bread'})), privileged), 'gives items');
    expect(behaviorNeedsCommands(_behavior(_step('animus:say', <String, Object?>{'text': 'Bye'})), privileged), isNull);
  });
}
