import 'dart:math' as math;

import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/inspect/behavior_edit.dart';
import 'package:animus_editor/logic/inspect/behavior_eval.dart';
import 'package:animus_editor/logic/inspect/behavior_language.dart';
import 'package:animus_editor/logic/inspect/behavior_operands.dart';
import 'package:animus_editor/logic/inspect/behavior_summary.dart';
import 'package:animus_editor/logic/inspect/completion_catalog.dart';
import 'package:animus_editor/logic/inspect/inspect_schemas.dart';
import 'package:animus_editor/logic/inspect/server_behaviors.dart';
import 'package:animus_editor/logic/offline/dry_run.dart';
import 'package:animus_editor/logic/offline/new_documents.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/logic/suggest/completion_item.dart';
import 'package:animus_editor/logic/suggest/field_profile.dart';
import 'package:animus_editor/logic/suggest/text_completion.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

JsonMap _fixture(String path) => asMap(readFixture(path));

void main() {
  final CompletionCatalog catalog = InspectSchemas.embedded.catalog;
  final ExpressionScope scope = ExpressionScope(language: catalog.expressions, variables: const <String, String>{'count': 'integer', 'names': 'list', 'flag-2': 'boolean'});

  group('expressions', () {
    test('should accept what the server compiles', () {
      expect(checkBehaviorExpression('count < 3 && player.health > 5', scope), isNull);
      expect(checkBehaviorExpression('len(names) + sum(range(1, 3))', scope), isNull);
      expect(checkBehaviorExpression('context.message', scope), isNull);
      expect(checkBehaviorExpression("has_flag('player', 'met')", scope), isNull);
    });

    test('should name the problem in words when it does not compile', () {
      expect(checkBehaviorExpression('count <', scope), isNotNull);
      expect(checkBehaviorExpression('total + 1', scope), contains('No variable named total'));
      expect(checkBehaviorExpression('player.mana', scope), contains('player facts'));
      expect(checkBehaviorExpression('context', scope), contains('context needs a key'));
      expect(checkBehaviorExpression('explode(1)', scope), contains('no function explode'));
      expect(checkBehaviorExpression('flag-2', scope), isNotNull, reason: 'a dash reads as minus, so flag cannot be found');
      expect(checkBehaviorExpression('', scope), isNotNull);
    });

    test('should find template holes the way the server does, quotes included', () {
      final (List<BehaviorHole> holes, int? open) = behaviorHoles("Hi {{ player.name }}, {{ '}}' }} and {{ count }}");
      expect(open, isNull);
      expect(holes.map((BehaviorHole hole) => hole.source.trim()), <String>['player.name', "'}}'", 'count']);
      expect(checkBehaviorTemplate('Hello {{ player.name', scope), contains('not closed'));
      expect(checkBehaviorTemplate('Hello {{ nobody }}', scope), contains('nobody'));
      expect(checkBehaviorTemplate('Plain text', scope), isNull);
    });

    test('should complete variables, roots, facts and functions', () {
      final CompletionEnv env = CompletionEnv(behavior: BehaviorNames(scope: scope));
      List<String> labels(String text, {bool template = false}) => <String>[
            for (final CompletionItem item in completeText(text, text.length, template ? FieldProfile.behaviorTemplate : FieldProfile.behaviorExpression, env).items) item.label,
          ];
      expect(labels('co'), contains('count'));
      expect(labels('player.he'), contains('player.health'));
      expect(labels('su'), contains('sum()'));
      expect(labels('Hi {{ pl', template: true), contains('player'));
      expect(labels('Hi pl', template: true), isEmpty, reason: 'outside a hole a template is plain text');
    });
  });

  group('evaluator', () {
    test('should compute with variables, functions and seeded randomness', () {
      final Map<String, Object?> values = <String, Object?>{'count': 2, 'names': <Object?>['Alex', 'Sam'], 'prices': <String, Object?>{'bread': 2}};
      BehaviorEvalScope scope() => BehaviorEvalScope(read: (String name) => values[name], flag: (String scope, String name) => null, quest: (String scope, String name) => 0, random: math.Random(1));
      expect(evaluateBehaviorExpression('count + 1', scope()), 3.0);
      expect(evaluateBehaviorExpression("join(append(names, 'Kai'), ', ')", scope()), 'Alex, Sam, Kai');
      expect(evaluateBehaviorExpression("get(prices, 'bread') * 2", scope()), 4.0);
      expect(evaluateBehaviorExpression("same('3', 3)", scope()), isTrue);
      expect(renderBehaviorTemplate('{{ count }} of {{ len(names) }}', scope()), '2 of 2');
      final BehaviorEvalScope facts = scope();
      expect(evaluateBehaviorExpression('player.health >= 20', facts), isTrue);
      expect(facts.factsRead.keys, <String>['player.health']);
      expect(() => evaluateBehaviorExpression('missing + 1', scope()), throwsA(isA<BehaviorEvalFailure>()));
      expect(convertForType(3.0, 'integer'), (3, null));
      expect(convertForType(3.5, 'integer').$2, isNotNull);
      expect(convertForType(3.0, 'string'), ('3', null));
    });

    test('should match a world by Bukkit name or dimension key when the sample server resolves both', () {
      final BehaviorEvalScope scope = BehaviorEvalScope(read: (String name) => null, flag: (String scope, String name) => null, quest: (String scope, String name) => 0, random: math.Random(1));
      expect(evaluateBehaviorExpression("same_world(player.world, 'minecraft:overworld')", scope), isTrue);
      expect(evaluateBehaviorExpression("same_world('world_nether', 'minecraft:the_nether')", scope), isTrue);
      expect(evaluateBehaviorExpression("same_world('Skylands', 'skylands')", scope), isTrue);
      expect(evaluateBehaviorExpression("same_world(player.world, 'minecraft:the_end')", scope), isFalse);
    });
  });

  group('operands and placeholders', () {
    test('should read and switch operand modes without corrupting the value', () {
      expect(operandMode(<String, Object?>{'expression': 'count + 1'}, type: 'integer'), OperandMode.expression);
      expect(operandMode(<String, Object?>{'variable': 'x', 'other': 1}, type: 'map'), OperandMode.literal, reason: 'a map literal with other keys stays a literal');
      final Object? expression = switchOperand(0, OperandMode.expression, type: 'integer');
      expect(expression, <String, Object?>{'expression': '0'});
      final Object? template = switchOperand(<String, Object?>{'expression': 'count'}, OperandMode.template, type: 'string');
      expect(template, <String, Object?>{'template': 'count'}, reason: 'the text carries across');
      expect(switchOperand(template, OperandMode.variable, type: 'string', variables: <String>['label']), <String, Object?>{'variable': 'label'});
      expect(switchOperand(template, OperandMode.literal, type: 'list'), <Object?>[]);
      expect(operandModes('integer'), isNot(contains(OperandMode.template)), reason: 'templates make text');
      expect(compatibleVariables(<String, Object?>{'a': <String, Object?>{'type': 'integer'}, 'b': <String, Object?>{'type': 'any'}, 'c': <String, Object?>{'type': 'string'}}, 'integer'), <String>['a', 'b']);
    });

    test('should write a null placeholder and its pointer, and undo both', () {
      final JsonMap say = <String, Object?>{'type': 'action', 'capability': 'animus:say', 'arguments': <String, Object?>{'text': 'Hi'}};
      final JsonMap filled = setArgumentSource(say, 'text', ArgumentSource.template, text: 'Hi {{ player.name }}');
      expect(asMap(filled['arguments'])['text'], isNull);
      expect(asMap(filled['arguments']).containsKey('text'), isTrue);
      expect(filled['templates'], <String, Object?>{'/text': 'Hi {{ player.name }}'});
      expect(argumentSource(filled, 'text'), ArgumentSource.template);
      final JsonMap moved = setArgumentSource(filled, 'text', ArgumentSource.expression, text: 'player.name');
      expect(moved.containsKey('templates'), isFalse);
      expect(moved['expressions'], <String, Object?>{'/text': 'player.name'});
      final JsonMap back = setArgumentSource(moved, 'text', ArgumentSource.literal, literal: '');
      expect(back.containsKey('expressions'), isFalse);
      expect(asMap(back['arguments'])['text'], '');
      expect(argumentPointer('a/b~c'), '/a~1b~0c');
      expect(strayPointers(<String, Object?>{'arguments': <String, Object?>{'text': 'x'}, 'expressions': <String, Object?>{'/text': '1'}}), <String>['/text']);
    });
  });

  group('edits', () {
    test('should add loops that compile as added', () {
      final JsonMap variables = <String, Object?>{'names': <String, Object?>{'type': 'list', 'initial': <Object?>[]}};
      final JsonMap foreach = defaultNode('foreach', catalog, variables: variables);
      expect(foreach['list'], <String, Object?>{'variable': 'names'});
      expect(foreach['as'], 'item');
      expect(variablesForNewNode('foreach', variables).keys, <String>['item']);
      expect(defaultNode('while', catalog)['expression'], 'false');
      expect(asMap(defaultNode('while', catalog)['body'])['type'], 'sequence');
      expect(defaultNode('action', catalog, server: true)['capability'], 'animus:message', reason: 'a server behavior gets no NPC-only capability');
    });

    test('should rename a variable everywhere it is named', () {
      final JsonMap document = _fixture('documents/behavior/loops.json');
      final JsonMap renamed = renameVariable(document, 'count', 'rounds', catalog);
      expect(asMap(renamed['variables']).keys, contains('rounds'));
      final JsonMap main = asMap(readIn(renamed, <JsonStep>['routines', 'main']));
      expect(readIn(main, <JsonStep>['steps', 1, 'expression']), 'rounds < 3');
      expect(readIn(main, <JsonStep>['steps', 1, 'body', 'variable']), 'rounds');
      expect(readIn(main, <JsonStep>['steps', 5, 'value', 'template']), '{{ rounds }} rounds');
      expect(renameInExpression("count + 'count' + x.count", 'count', 'n'), "n + 'count' + x.count");
      expect(variableNameProblem('player', const <String>[]), contains('reserved'));
      expect(variableNameProblem('Bad', const <String>[]), isNotNull);
      expect(variableNameNote('flag-2'), isNotNull);
      expect(variableNameNote('flag_2'), isNull);
    });

    test('should turn a behavior into a server behavior with the timer minimum', () {
      final JsonMap document = <String, Object?>{
        'triggers': <Object?>[<String, Object?>{'id': 't', 'event': 'timer', 'intervalTicks': 5}],
      };
      final JsonMap server = setServerBehavior(document, true);
      expect(server['server'], isTrue);
      expect(readIn(server, <JsonStep>['triggers', 0, 'intervalTicks']), serverTimerMinimum);
      expect(setServerBehavior(server, false).containsKey('server'), isFalse);
      expect(catalog.eventsFor(server: true), containsAll(<String>['player_join', 'command', 'timer']));
      expect(catalog.eventsFor(server: true), isNot(contains('interaction')));
      expect(catalog.eventsFor(server: false), isNot(contains('player_chat')));
      expect(scopesFor(<String>['execution', 'npc', 'player', 'relationship', 'global'], server: true), <String>['execution', 'player', 'global']);
      expect(catalog.capabilitiesFor('action', server: true).map((CapabilitySpec spec) => spec.id), isNot(contains('animus:say')));
      expect(defaultTrigger('timer', catalog, const <String>[], server: true)['intervalTicks'], serverTimerMinimum);
    });

    test('should summarize nodes in plain words', () {
      expect(nodeSummary(<String, Object?>{'type': 'action', 'capability': 'animus:say', 'arguments': <String, Object?>{'text': 'Welcome'}}), 'Say: Welcome');
      expect(nodeSummary(<String, Object?>{'type': 'condition', 'capability': 'animus:flag-state', 'arguments': <String, Object?>{'name': 'met_guard', 'value': true}}), 'If flag met_guard is true');
      expect(nodeSummary(<String, Object?>{'type': 'while', 'expression': 'count < 3'}), 'While count < 3');
      expect(nodeSummary(<String, Object?>{'type': 'action', 'capability': 'animus:say', 'arguments': <String, Object?>{'text': 'Welcome, &etraveller&f! <red>{player_name}'}}), 'Say: Welcome, traveller! {player_name}');
      expect(nodeSummary(<String, Object?>{'type': 'action', 'capability': 'animus:choices', 'arguments': <String, Object?>{'prompt': '&6Where to?'}}), 'Ask: Where to?');
      expect(nodeSummary(<String, Object?>{'type': 'set', 'variable': 'count', 'value': <String, Object?>{'expression': 'count + 1'}}), 'Set count to count + 1');
    });
  });

  group('dry run with Denizen v2 nodes', () {
    test('should run loops, expressions, templates and calls of the loop fixture', () {
      final JsonMap behavior = _fixture('documents/behavior/loops.json');
      final DryRunSession session = dryRun(behavior, DryRunInputs.start('click'));
      final List<DryRunStep> steps = session.runs.single.steps;
      expect(steps.where((DryRunStep step) => step.outcome == DryRunOutcome.problem), isEmpty, reason: steps.map((DryRunStep s) => s.text).join('\n'));
      expect(steps.where((DryRunStep step) => step.text.startsWith('Pass ')), hasLength(3));
      expect(steps.where((DryRunStep step) => step.speech != null).map((DryRunStep step) => step.speech), <String>['Hello Alex, visit 1.'], reason: 'continue skips Sam');
      expect(steps.any((DryRunStep step) => step.text == 'Sets label to "3 rounds".'), isTrue);
      expect(steps.any((DryRunStep step) => step.text.startsWith('Stores the result in total: 3')), isTrue);
      expect(session.state['variable visits'], 1);
    });

    test('should fail at a loop maximum and stop runaway loops', () {
      final JsonMap behavior = <String, Object?>{
        'entry': 'main',
        'variables': <String, Object?>{'n': <String, Object?>{'type': 'integer', 'initial': 0}},
        'routines': <String, Object?>{
          'main': <String, Object?>{'type': 'while', 'expression': 'true', 'maximum': 4, 'body': <String, Object?>{'type': 'set', 'variable': 'n', 'value': <String, Object?>{'expression': 'n + 1'}}},
        },
      };
      final List<DryRunStep> steps = dryRun(behavior, DryRunInputs.start(null)).runs.single.steps;
      expect(steps.last.outcome, DryRunOutcome.problem);
      expect(steps.last.text, contains('more than 4 times'));
    });

    test('should assume facts only a server knows and let the user flip them', () {
      final JsonMap behavior = <String, Object?>{
        'entry': 'main',
        'routines': <String, Object?>{
          'main': <String, Object?>{
            'type': 'condition',
            'expression': 'player.health < 10',
            'then': <String, Object?>{'type': 'action', 'capability': 'animus:say', 'arguments': <String, Object?>{'text': 'You look hurt.'}},
            'else': <String, Object?>{'type': 'action', 'capability': 'animus:say', 'arguments': <String, Object?>{'text': 'Hello.'}},
          },
        },
      };
      final DryRunSession healthy = dryRun(behavior, DryRunInputs.start(null));
      final DryRunStep assumed = healthy.runs.single.steps.first;
      expect(assumed.outcome, DryRunOutcome.assumed);
      expect(assumed.assumption, isFalse);
      final DryRunSession hurt = dryRun(behavior, DryRunInputs.start(null).withAssumption(assumed.path, true));
      expect(hurt.runs.single.steps.last.speech, 'You look hurt.');
    });

    test('should run a server behavior with its event context', () {
      final JsonMap behavior = _fixture('documents/behavior/server-welcome.json');
      final DryRunSession session = dryRun(behavior, DryRunInputs.start('join'));
      expect(dryRunStarts(behavior).first.label, 'join: a player joins');
      expect(session.runs.single.steps.where((DryRunStep step) => step.speech != null).single.speech, 'Welcome, Player. You are visitor 1.');
      expect(session.state['variable joins'], 1);
    });
  });

  group('server-behaviors kind', () {
    test('should locate, create and validate the root list', () {
      expect(locateDocument('server-behaviors.json'), const DocumentLocation(DocumentKind.serverBehaviors, 'server-behaviors'));
      expect(newDocumentPath(DocumentKind.serverBehaviors, 'server-behaviors'), 'server-behaviors.json');
      expect(DocumentKind.parse('server-behaviors'), DocumentKind.serverBehaviors);
      final SharedSchemas schemas = SharedSchemas.embedded;
      expect(schemas.validate(DocumentKind.serverBehaviors, newServerBehaviors(<String>['town:welcome'])), isEmpty);
      expect(schemas.validate(DocumentKind.serverBehaviors, <String, Object?>{'schemaVersion': 1, 'behaviors': <Object?>['Bad Id']}), isNotEmpty);
      expect(schemas.validate(DocumentKind.serverBehaviors, <String, Object?>{'schemaVersion': 1, 'behaviors': <Object?>[for (int i = 0; i < 257; i++) 'a:b$i']}), isNotEmpty);
    });

    test('should warn about listed behaviors that are not server behaviors', () {
      AnimusDocument behavior(String id, {required bool server}) => AnimusDocument(
            kind: DocumentKind.behavior,
            id: id,
            path: 'behaviors/${id.replaceAll(':', '/')}.json',
            source: '{}',
            json: <String, Object?>{'id': id, if (server) 'server': true},
            diagnostics: const <Diagnostic>[],
          );
      final List<AnimusDocument> documents = <AnimusDocument>[behavior('town:welcome', server: true), behavior('town:greeter', server: false)];
      final List<Diagnostic> found = serverBehaviorsDiagnostics(newServerBehaviors(<String>['town:welcome', 'town:greeter', 'elsewhere:x']), documents);
      expect(found.single.path, r'$.behaviors[1]');
      expect(serverBehaviorNote('elsewhere:x', workspaceBehaviors(documents)), contains('Not in this workspace'));
      expect(serverBehaviorNote('town:welcome', workspaceBehaviors(documents)), isNull, reason: 'a listed server behavior needs no note');
    });
  });
}
