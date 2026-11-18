import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/inspect/behavior_edit.dart';
import 'package:animus_editor/logic/inspect/behavior_outline.dart';
import 'package:animus_editor/logic/inspect/argument_choices.dart';
import 'package:animus_editor/logic/inspect/behavior_steps.dart';
import 'package:animus_editor/logic/inspect/behavior_summary.dart';
import 'package:animus_editor/logic/inspect/behavior_words.dart';
import 'package:animus_editor/logic/inspect/completion_catalog.dart';
import 'package:animus_editor/logic/inspect/inspect_schemas.dart';
import 'package:animus_editor/logic/offline/new_documents.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

final CompletionCatalog catalog = InspectSchemas.embedded.catalog;

JsonMap _action(String capability, [JsonMap arguments = const <String, Object?>{}]) =>
    <String, Object?>{'type': 'action', 'capability': capability, 'arguments': arguments};

JsonMap _say(String text) => _action('animus:say', <String, Object?>{'text': text});

JsonMap _behavior(JsonMap routines, {JsonMap variables = const <String, Object?>{}}) => <String, Object?>{
      'schemaVersion': 1,
      'revision': 1,
      'id': 'town:guard',
      'entry': 'main',
      if (variables.isNotEmpty) 'variables': variables,
      'routines': routines,
    };

List<String> _errors(JsonMap behavior) => <String>[
      for (final Diagnostic d in SharedSchemas.embedded.validate(DocumentKind.behavior, behavior))
        if (d.severity == DiagnosticSeverity.error) '${d.path}: ${d.message}',
    ];

/// A summary that reads as if a word or value were missing.
final RegExp _gap = RegExp(r'(,\s*,)|(\s,)|(:\s*$)|(\s$)|(^\s)|(\s\s)|((?<!looks )\b(at|to|by|of|in|into|from|with|for)$)|(/$)|(\(\s*\))|(""|\{\})');

void main() {
  group('target choice', () {
    test('the catalog keeps exclusive groups apart from groups that may be combined', () {
      expect(catalog.capability('animus:look')!.oneOfRequired, <List<String>>[<String>['target'], <String>['x', 'y', 'z'], <String>['location']]);
      expect(catalog.capability('animus:title')!.oneOfRequired, isEmpty);
      expect(catalog.capability('animus:title')!.anyOfRequired, <List<String>>[<String>['title'], <String>['subtitle']]);
    });

    test('look and navigate offer their targets in words', () {
      expect(argumentChoices(catalog.capability('animus:look')!).map((ArgumentChoice choice) => choice.label), <String>['The player', 'Coordinates']);
      expect(argumentChoices(catalog.capability('animus:navigate')!).map((ArgumentChoice choice) => choice.label), <String>['A route', 'An anchor', 'Coordinates']);
      expect(argumentChoiceField(catalog.capability('animus:look')!), 'Looks at');
      expect(argumentChoices(catalog.capability('animus:title')!), isEmpty, reason: 'title and subtitle are not a choice');
    });

    test('a location joins x, y and z as one Coordinates choice', () {
      final CapabilitySpec look = catalog.capability('animus:look')!;
      final List<ArgumentChoice> choices = argumentChoices(look);
      expect(choices.map((ArgumentChoice choice) => choice.group), <int>[0, 1]);
      expect(choices[1].stands(2), isTrue, reason: 'a step written with location shows Coordinates picked');
      expect(argumentChoices(catalog.capability('animus:teleport')!).map((ArgumentChoice choice) => choice.label), <String>['Coordinates', 'The NPC or player', 'An anchor']);
      final JsonMap located = _action('animus:look', <String, Object?>{'location': '1,64,3'});
      expect(chosenArgumentGroup(located, look), 2);
      expect(_errors(_behavior(<String, Object?>{'main': located})), isEmpty);
      final JsonMap player = chooseArgumentGroup(located, look, choices[0].group);
      expect(player['arguments'], <String, Object?>{'target': 'player'});
    });

    test('picking the player writes target player and drops the coordinates and their placeholders', () {
      final CapabilitySpec look = catalog.capability('animus:look')!;
      final JsonMap node = <String, Object?>{
        ..._action('animus:look', <String, Object?>{'x': 1, 'y': null, 'z': 3}),
        'expressions': <String, Object?>{'/y': 'height'},
      };
      expect(chosenArgumentGroup(node, look), 1);
      final JsonMap player = chooseArgumentGroup(node, look, 0);
      expect(player['arguments'], <String, Object?>{'target': 'player'});
      expect(player.containsKey('expressions'), isFalse);
      expect(chosenArgumentGroup(player, look), 0);
      expect(_errors(_behavior(<String, Object?>{'main': player})), isEmpty);
      final JsonMap position = chooseArgumentGroup(player, look, 1);
      expect(position['arguments'], <String, Object?>{'x': 0, 'y': 64, 'z': 0});
      expect(_errors(_behavior(<String, Object?>{'main': position})), isEmpty);
    });

    test('a target filled from a template still counts as chosen', () {
      final JsonMap node = <String, Object?>{
        ..._action('animus:look', <String, Object?>{'target': null}),
        'templates': <String, Object?>{'/target': 'player'},
      };
      expect(chosenArgumentGroup(node, catalog.capability('animus:look')!), 0);
      expect(nodeSummary(node), 'Look at the player');
    });

    test('picking Look as what a step does starts it looking at the player', () {
      final JsonMap looked = changeCapability(_say('Hi'), 'animus:look', catalog);
      expect(looked['arguments'], <String, Object?>{'target': 'player'});
      expect(nodeSummary(looked), 'Look at the player');
    });

    test('picking an anchor for navigate keeps the step and starts the anchor empty', () {
      final CapabilitySpec navigate = catalog.capability('animus:navigate')!;
      final JsonMap anchored = chooseArgumentGroup(_action('animus:navigate', <String, Object?>{'route': 'town:loop', 'wait': true}), navigate, 1);
      expect(anchored['arguments'], <String, Object?>{'wait': true, 'anchor': ''});
    });
  });

  group('step headers', () {
    test('look, navigate and teleport say where to', () {
      expect(nodeSummary(_action('animus:look', <String, Object?>{'target': 'player'})), 'Look at the player');
      expect(nodeSummary(_action('animus:look', <String, Object?>{'x': 1, 'y': 64, 'z': 3})), 'Look at 1, 64, 3');
      expect(nodeSummary(_action('animus:look')), 'Look');
      expect(nodeSummary(_action('animus:look', <String, Object?>{'location': '1,64,3'})), 'Look at 1, 64, 3');
      expect(nodeSummary(_action('animus:navigate', <String, Object?>{'location': '4, 64, 2, world_nether'})), 'Walk to 4, 64, 2 in world_nether');
      expect(nodeSummary(_action('animus:teleport', <String, Object?>{'location': '1,64,3'})), 'Teleport the player to 1, 64, 3');
      expect(nodeSummary(_action('animus:navigate', <String, Object?>{'anchor': 'gate'})), 'Walk to anchor gate');
      expect(nodeSummary(_action('animus:navigate', <String, Object?>{'x': 4, 'y': 64, 'z': 2})), 'Walk to 4, 64, 2');
      expect(nodeSummary(_action('animus:navigate')), 'Walk');
      expect(nodeSummary(_action('animus:teleport', <String, Object?>{'anchor': 'gate'})), 'Teleport the player to anchor gate');
      expect(nodeSummary(_action('animus:teleport', <String, Object?>{'target': 'npc', 'to': 'player'})), 'Teleport the NPC to the player');
      expect(nodeSummary(_action('animus:set-quest-state', <String, Object?>{'name': 'errand', 'random': 5})), 'Set quest errand to a random stage up to 5');
    });

    test('no header reads as if a value were missing', () {
      final List<String> gaps = <String>[];
      for (final CapabilitySpec spec in catalog.capabilities) {
        final List<JsonMap> nodes = <JsonMap>[
          <String, Object?>{'type': spec.kind, 'capability': spec.id},
          <String, Object?>{'type': spec.kind, 'capability': spec.id, 'arguments': defaultArguments(spec)},
          <String, Object?>{'type': spec.kind, 'capability': spec.id, 'arguments': <String, Object?>{for (final argument in spec.arguments) argument.key: ''}},
        ];
        for (final JsonMap node in nodes) {
          final String line = nodeSummary(node);
          if (_gap.hasMatch(line)) gaps.add('${spec.id} ${node['arguments']}: "$line"');
        }
      }
      expect(gaps, isEmpty);
    });
  });

  group('type changes', () {
    test('a single step becomes the first step of a new list or loop', () {
      final JsonMap say = _say('Hello');
      expect(changeNodeType(say, 'sequence', catalog)['steps'], <Object?>[say]);
      expect(changeNodeType(say, 'parallel', catalog)['branches'], <Object?>[say]);
      expect(changeNodeType(say, 'repeat', catalog)['body'], say);
      expect(changeNodeType(say, 'condition', catalog)['then'], say);
      expect(typeChangeLoss(say, changeNodeType(say, 'sequence', catalog), catalog), isNull);
    });

    test('a change that drops content names what goes', () {
      final JsonMap say = _say('Hello');
      expect(typeChangeLoss(say, changeNodeType(say, 'wait', catalog), catalog), 'Say: Hello');
      final JsonMap list = <String, Object?>{'type': 'sequence', 'steps': <Object?>[say, _say('Bye')]};
      expect(typeChangeLoss(list, changeNodeType(list, 'wait', catalog), catalog), '2 steps');
      expect(typeChangeLoss(list, changeNodeType(list, 'parallel', catalog), catalog), isNull);
    });

    test('a step left as it was added loses nothing worth a question', () {
      final JsonMap wait = defaultNode('wait', catalog);
      expect(typeChangeLoss(wait, changeNodeType(wait, 'action', catalog), catalog), isNull);
      final JsonMap action = defaultNode('action', catalog);
      expect(typeChangeLoss(action, changeNodeType(action, 'wait', catalog), catalog), isNull);
    });

    test('a step under a single-step branch is wrapped with the new one in order', () {
      final JsonMap wrapped = wrapWithStep(_say('Halt'), _say('Who goes there?'));
      expect(wrapped, <String, Object?>{
        'type': 'sequence',
        'steps': <Object?>[_say('Halt'), _say('Who goes there?')],
      });
      final JsonMap list = <String, Object?>{'type': 'sequence', 'steps': <Object?>[_say('Halt')]};
      expect(asList(wrapWithStep(list, _say('Go'))['steps']), hasLength(2), reason: 'a list takes the step at its end');
    });

    test('a new If checks a flag', () {
      final JsonMap condition = defaultNode('condition', catalog);
      expect(condition['capability'], 'animus:flag-state');
      expect(_errors(_behavior(<String, Object?>{'main': condition})), isEmpty);
    });
  });

  group('moving, copying and pasting steps', () {
    final JsonMap routines = <String, Object?>{
      'main': <String, Object?>{
        'type': 'sequence',
        'steps': <Object?>[_say('one'), _say('two'), _say('three')],
      },
      'other': <String, Object?>{'type': 'sequence', 'steps': <Object?>[_say('four')]},
    };
    List<String> texts(JsonMap document, String routine) => <String>[
          for (final Object? step in asList(readIn(document, <JsonStep>['routines', routine, 'steps']))) argumentWords(asMap(step), 'text'),
        ];

    test('moves a step up, down and within its list', () {
      final JsonMap document = _behavior(routines);
      const List<JsonStep> main = <JsonStep>['routines', 'main', 'steps'];
      expect(texts(moveStep(document, main, 2, main, 0)!, 'main'), <String>['three', 'one', 'two']);
      expect(texts(moveStep(document, main, 0, main, 2)!, 'main'), <String>['two', 'one', 'three'], reason: 'to is the place before the move');
      expect(texts(moveStep(document, main, 0, main, 3)!, 'main'), <String>['two', 'three', 'one']);
    });

    test('moves a step to another routine and refuses to move a step into itself', () {
      final JsonMap document = _behavior(routines);
      final JsonMap moved = moveStep(document, const <JsonStep>['routines', 'main', 'steps'], 1, const <JsonStep>['routines', 'other', 'steps'], 0)!;
      expect(texts(moved, 'main'), <String>['one', 'three']);
      expect(texts(moved, 'other'), <String>['two', 'four']);
      final JsonMap nested = _behavior(<String, Object?>{
        'main': <String, Object?>{
          'type': 'sequence',
          'steps': <Object?>[
            _say('a'),
            <String, Object?>{'type': 'sequence', 'steps': <Object?>[_say('b')]},
          ],
        },
      });
      expect(moveStep(nested, const <JsonStep>['routines', 'main', 'steps'], 1, const <JsonStep>['routines', 'main', 'steps', 1, 'steps'], 0), isNull);
      final JsonMap outOf = moveStep(nested, const <JsonStep>['routines', 'main', 'steps', 1, 'steps'], 0, const <JsonStep>['routines', 'main', 'steps'], 0)!;
      expect(asList(readIn(outOf, const <JsonStep>['routines', 'main', 'steps'])), hasLength(3));
    });

    test('a later list of the same parent follows a removal before it', () {
      final JsonMap document = _behavior(<String, Object?>{
        'main': <String, Object?>{
          'type': 'sequence',
          'steps': <Object?>[
            _say('a'),
            <String, Object?>{'type': 'sequence', 'steps': <Object?>[_say('b')]},
          ],
        },
      });
      final JsonMap moved = moveStep(document, const <JsonStep>['routines', 'main', 'steps'], 0, const <JsonStep>['routines', 'main', 'steps', 1, 'steps'], 1)!;
      expect(readIn(moved, const <JsonStep>['routines', 'main', 'steps', 0, 'steps', 1, 'arguments', 'text']), 'a');
    });

    test('a pasted step brings the variables it names into another behavior', () {
      final JsonMap source = _behavior(<String, Object?>{
        'main': <String, Object?>{
          'type': 'sequence',
          'steps': <Object?>[
            <String, Object?>{'type': 'set', 'variable': 'visits', 'value': <String, Object?>{'expression': 'visits + bonus'}},
          ],
        },
      }, variables: <String, Object?>{
        'visits': <String, Object?>{'type': 'integer', 'initial': 0},
        'bonus': <String, Object?>{'type': 'integer', 'initial': 1},
        'unused': <String, Object?>{'type': 'string', 'initial': ''},
      });
      final CopiedStep copied = copyStep(source, readIn(source, const <JsonStep>['routines', 'main', 'steps', 0]), catalog);
      expect(copied.variables.keys, unorderedEquals(<String>['visits', 'bonus']));
      expect(copied.name, 'Set variable');
      final JsonMap target = _behavior(<String, Object?>{'main': <String, Object?>{'type': 'sequence', 'steps': <Object?>[]}},
          variables: <String, Object?>{'bonus': <String, Object?>{'type': 'integer', 'initial': 5}});
      final JsonMap pasted = pasteStep(target, const <JsonStep>['routines', 'main', 'steps'], 0, copied);
      expect(asList(readIn(pasted, const <JsonStep>['routines', 'main', 'steps'])), hasLength(1));
      expect(asMap(pasted['variables']).keys, unorderedEquals(<String>['bonus', 'visits']));
      expect(readIn(pasted, const <JsonStep>['variables', 'bonus', 'initial']), 5, reason: 'a variable already there is kept');
      expect(_errors(pasted), isEmpty);
    });

    test('pasting into an empty branch puts the step there and into a full one wraps it', () {
      final JsonMap document = _behavior(<String, Object?>{
        'main': <String, Object?>{'type': 'condition', 'expression': 'true', 'then': _say('yes')},
      });
      final CopiedStep copied = copyStep(document, _say('again'), catalog);
      final JsonMap filled = pasteInto(document, const <JsonStep>['routines', 'main', 'else'], copied);
      expect(readIn(filled, const <JsonStep>['routines', 'main', 'else', 'arguments', 'text']), 'again');
      final JsonMap wrapped = pasteInto(document, const <JsonStep>['routines', 'main', 'then'], copied);
      expect(readIn(wrapped, const <JsonStep>['routines', 'main', 'then', 'type']), 'sequence');
      expect(asList(readIn(wrapped, const <JsonStep>['routines', 'main', 'then', 'steps'])), hasLength(2));
    });

    test('names a step as its header does, for undo notices', () {
      expect(stepName(_say('hi')), 'Say');
      expect(stepName(_action('animus:play-sound', <String, Object?>{'sound': 'x'})), 'Play sound');
      expect(stepName(<String, Object?>{'type': 'condition', 'expression': 'true'}), 'If');
      expect(stepName(<String, Object?>{'type': 'wait', 'ticks': 1}), 'Wait');
      expect(stepName(<String, Object?>{'type': 'call', 'routine': 'main'}), 'Call routine');
    });
  });

  group('wording', () {
    test('ticks can be typed as a time', () {
      expect(parseTicks('40'), 40);
      expect(parseTicks('40 ticks'), 40);
      expect(parseTicks('2 seconds'), 40);
      expect(parseTicks('2s'), 40);
      expect(parseTicks('1.5 sec'), 30);
      expect(parseTicks('3 minutes'), 3600);
      expect(parseTicks('1 hour'), 72000);
      expect(parseTicks('soon'), isNull);
      expect(parseTicks(''), isNull);
      expect(durationWords(40), '2 seconds');
      expect(durationWords(30), '1.5 seconds');
      expect(durationWords(20), '1 second');
      expect(durationWords(3600), '3 minutes');
      expect(durationWords(72000), '1 hour');
    });

    test('a value held to its range says so', () {
      expect(fitNumber(100000, 1, 72000, unit: 'ticks'), (72000, 'Set to 72000 ticks, the most it takes.'));
      expect(fitNumber(0, 1, 72000, unit: 'ticks'), (1, 'Set to 1 tick, the least it takes.'));
      expect(fitNumber(200, 0, 128), (128, 'Set to 128, the most it takes.'));
      expect(fitNumber(40, 1, 72000, unit: 'ticks'), (40, null));
    });

    test('a refused routine name says why', () {
      expect(routineNameProblem('my part', <String>['main']), 'Use lowercase letters, digits, _ and -, such as my-part.');
      expect(routineNameProblem('main', <String>['main']), 'Another routine is already called main.');
      expect(routineNameProblem('', <String>['main']), 'A routine needs a name.');
      expect(routineNameProblem('hostile', <String>['main']), isNull);
    });

    test('flag scopes read as variable scopes do', () {
      for (final String scope in <String>['global', 'npc', 'player', 'relationship']) {
        expect(flagScopeWords[scope], variableScopeWords[scope]);
      }
    });

    test('routines and triggers use one word each', () {
      expect(nodeLabel('call'), 'Call routine');
      expect(triggerSentence(<String, Object?>{'event': 'interaction'}), 'Starts when a player clicks');
      expect(triggerSentence(<String, Object?>{'event': 'proximity', 'phase': 'enter'}), 'Starts when a player comes near');
      expect(triggerSentence(<String, Object?>{'event': 'timer', 'intervalTicks': 200}), 'Starts every 10 seconds');
      expect(triggerSentence(<String, Object?>{'event': 'command', 'name': 'bell'}), 'Starts when a player runs /bell');
      for (final String event in catalog.triggers.keys) {
        expect(triggerSentence(<String, Object?>{'event': event}), startsWith('Starts '), reason: event);
      }
      final List<OutlinePart> parts = behaviorOutline(_behavior(<String, Object?>{
        'main': <String, Object?>{'type': 'call', 'routine': 'warn-off'},
        'warn-off': <String, Object?>{'type': 'wait', 'ticks': 20},
      })
        ..['triggers'] = <Object?>[
          <String, Object?>{'id': 'click', 'event': 'interaction'},
          <String, Object?>{'id': 'tick', 'event': 'timer', 'intervalTicks': 200},
        ]);
      expect(parts.first.runs, 'Starts when a player clicks, or every 10 seconds');
      expect(parts.first.lines.single.text, 'Call routine Warn off');
    });
  });

  group('new behaviors', () {
    test('every template, blank and server behavior is valid as made', () {
      for (final BehaviorTemplate template in BehaviorTemplate.values) {
        final JsonMap behavior = behaviorFromTemplate(template, 'town:${template.name}');
        expect(_errors(behavior), isEmpty, reason: template.name);
        expect(behavior['server'] == true, template == BehaviorTemplate.server, reason: template.name);
      }
      expect(BehaviorTemplate.values.map((BehaviorTemplate template) => template.label), isNot(contains('Dialogue')));
    });

    test('the id of a new document is the folder and the name', () {
      expect(newIdIn('town', 'Gate greeter', <String>{}), 'town:gate-greeter');
      expect(newIdIn('town', 'Gate greeter', <String>{'town:gate-greeter'}), 'town:gate-greeter-2');
      expect(newIdIn('', 'Gate greeter', <String>{}), 'local:gate-greeter');
      expect(workspaceNamespaces(<String>['shop:keeper', 'town:guard', 'town/baker', 'shop:sign']), <String>['shop', 'town']);
    });
  });
}
