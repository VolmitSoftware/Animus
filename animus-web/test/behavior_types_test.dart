import 'package:animus_editor/logic/inspect/behavior_language.dart';
import 'package:animus_editor/logic/inspect/behavior_types.dart';
import 'package:animus_editor/logic/inspect/completion_catalog.dart';
import 'package:animus_editor/logic/inspect/inspect_schemas.dart';
import 'package:animus_editor/logic/suggest/completion_item.dart';
import 'package:animus_editor/logic/suggest/field_profile.dart';
import 'package:animus_editor/logic/suggest/text_completion.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:test/test.dart';

void main() {
  final CompletionCatalog catalog = InspectSchemas.embedded.catalog;
  final ExpressionScope scope = ExpressionScope(
    language: catalog.expressions,
    variables: const <String, String>{'deliveries': 'integer', 'names': 'list', 'seen': 'map', 'label': 'string', 'ready': 'boolean', 'held': 'any'},
    contextKeys: const <String>['entity', 'x'],
    contextTypes: const <String, String>{'entity': 'text', 'x': 'number'},
    contextKnown: true,
  );
  String? first(String source, {ValueType expected = ValueType.any}) => expressionNotes(source, scope, expected: expected).firstOrNull?.message;
  DiagnosticSeverity? severity(String source, {ValueType expected = ValueType.any}) => expressionNotes(source, scope, expected: expected).firstOrNull?.severity;

  group('result types', () {
    test('should know what literals, variables, facts and operators give', () {
      expect(expressionType('deliveries + 1', scope), ValueType.number);
      expect(expressionType("label + '!'", scope), ValueType.text);
      expect(expressionType('deliveries > 3 && ready', scope), ValueType.boolean);
      expect(expressionType('player.name', scope), ValueType.text);
      expect(expressionType('player.health', scope), ValueType.number);
      expect(expressionType('player.online', scope), ValueType.boolean);
      expect(expressionType('context.x', scope), ValueType.number);
      expect(expressionType('ready ? 1 : 2', scope), ValueType.number);
      expect(expressionType('[1, 2]', scope), ValueType.list);
      expect(expressionType('held', scope), ValueType.any);
      expect(expressionType('seen.block', scope), ValueType.any, reason: 'a key of a map variable can hold anything');
      expect(expressionType('now()', scope), ValueType.number);
      expect(expressionType("player_named('Alex')", scope), ValueType.text);
      expect(expressionType('count <', scope), ValueType.any, reason: 'a source that does not parse has no type');
    });
  });

  group('type problems', () {
    test('should refuse a condition that does not give true or false', () {
      expect(first('deliveries + 1', expected: ValueType.boolean), 'deliveries + 1 gives a number, not true or false.');
      expect(severity('deliveries + 1', expected: ValueType.boolean), DiagnosticSeverity.error);
      expect(first('deliveries > 1', expected: ValueType.boolean), isNull);
      expect(first('held', expected: ValueType.boolean), isNull, reason: 'a variable of any type may hold true or false');
    });

    test('should refuse ticks that are not a number', () {
      expect(first('player.name', expected: ValueType.number), 'player.name gives text, not a number.');
      expect(first('random(20, 60)', expected: ValueType.number), isNull);
    });

    test('should refuse operators on the wrong types, as the server fails the run there', () {
      expect(first('deliveries == label'), '== compares a number with text, so the run fails here. Convert one side with num() or text().');
      expect(first('ready && deliveries'), '&& needs true or false on both sides; deliveries gives a number.');
      expect(first('label < 3'), '< needs numbers; label gives text.');
      expect(first('names * 2'), '* needs numbers; names gives a list.');
      expect(first('!label'), '! needs true or false; label gives text.');
      expect(first("label + 'x' == 'ax'"), isNull, reason: '+ joins text when either side is text');
      expect(first('deliveries == 3'), isNull);
    });

    test('should warn when an item id is compared without its namespace', () {
      expect(first("player.item_in_hand == 'wheat'"), "player.item_in_hand is written with its namespace, such as minecraft:wheat, so 'wheat' never matches.");
      expect(severity("player.item_in_hand == 'wheat'"), DiagnosticSeverity.warning);
      expect(first("player.item_in_hand == 'minecraft:wheat'"), isNull);
    });

    test('should warn about a context key the starting triggers do not give', () {
      expect(first('context.message'), 'The triggers that start this routine do not give context.message. They give: entity, x.');
      expect(first('context.entity'), isNull);
      final ExpressionScope open = ExpressionScope(language: catalog.expressions, contextKeys: const <String>['entity']);
      expect(expressionNotes('context.message', open), isEmpty, reason: 'unknown keys pass when a module trigger may give them');
    });
  });

  group('messages and completion', () {
    test('should explain = and bare words in plain words', () {
      expect(checkBehaviorExpression("player.item_in_hand = 'minecraft:wheat'", scope), 'Use == to compare; a single = is not an operator.');
      expect(checkBehaviorExpression('deliveries >= 3', scope), isNull);
      expect(checkBehaviorExpression("player.item_in_hand == wheat", scope), "No variable named wheat. For text, put it in quotes: 'wheat'.");
    });

    test('should suggest the context keys of the starting triggers and show function signatures', () {
      final CompletionEnv env = CompletionEnv(behavior: BehaviorNames(scope: scope));
      final List<CompletionItem> items = completeText('context.', 8, FieldProfile.behaviorExpression, env).items;
      expect(items.map((CompletionItem item) => item.label), unorderedEquals(<String>['context.entity', 'context.x']));
      final CompletionItem named = completeText('player_na', 9, FieldProfile.behaviorExpression, env).items.firstWhere((CompletionItem item) => item.label.startsWith('player_named'));
      expect(named.label, 'player_named(name)');
      expect(named.detail, isNot('Function'));
      expect(named.detail, isNotEmpty);
    });
  });
}
