import 'package:animus_editor/logic/gui/gui_expression.dart';
import 'package:animus_editor/logic/inspect/behavior_language.dart';
import 'package:animus_editor/logic/inspect/completion_catalog.dart';
import 'package:animus_editor/logic/inspect/inspect_schemas.dart';
import 'package:animus_editor/logic/inspect/json_completion.dart';
import 'package:animus_editor/logic/suggest/completion_item.dart';
import 'package:animus_editor/logic/suggest/completion_sources.dart';
import 'package:animus_editor/logic/suggest/field_profile.dart';
import 'package:animus_editor/logic/suggest/field_profiles.dart';
import 'package:animus_editor/logic/suggest/fuzzy.dart';
import 'package:animus_editor/logic/suggest/text_completion.dart';
import 'package:animus_editor/logic/suggest/text_hints.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

void main() {
  final CompletionCatalog catalog = InspectSchemas.embedded.catalog;
  final BehaviorNames behavior = BehaviorNames(
    scope: ExpressionScope(
      language: catalog.expressions,
      variables: const <String, String>{'count': 'integer', 'greeting': 'string'},
      contextKeys: const <String>['projectile', 'shooter'],
    ),
    variableScopes: const <String, String>{'count': 'player'},
  );
  final CompletionSources sources = CompletionSources(
    registries: const <RegistryKind, List<String>>{
      RegistryKind.item: <String>['diamond', 'diamond_sword', 'stone', 'iron_sword'],
    },
    complete: const <RegistryKind>{RegistryKind.item},
    documents: const <DocumentKind, List<String>>{
      DocumentKind.route: <String>['town/loop', 'mine/path'],
    },
    flags: const <String>['met_baker'],
    catalog: catalog,
  );
  final CompletionEnv env = CompletionEnv(sources: sources, behavior: behavior);

  List<String> labels(CompletionResult result) => <String>[for (final CompletionItem item in result.items) item.label];

  group('fuzzy ranking', () {
    test('should put a prefix match before a word-start match before scattered letters', () {
      final List<String> ranked = fuzzyRank(<String>['iron_sword', 'swordfish', 'stone_wall_ward'], 'sw', (String s) => s);
      expect(ranked, <String>['swordfish', 'iron_sword', 'stone_wall_ward']);
    });

    test('should drop candidates missing a letter and keep order for an empty query', () {
      expect(fuzzyScore('diamond', 'dz'), isNull);
      expect(fuzzyRank(<String>['b', 'a', 'c'], '', (String s) => s), <String>['b', 'a', 'c']);
    });

    test('should match across word starts', () {
      expect(fuzzyRank(<String>['viewer.health', 'viewer.name', 'player.health'], 'vh', (String s) => s).first, 'viewer.health');
    });
  });

  group('context at the caret', () {
    test('should find a behavior variable in a whole expression', () {
      final CaretSpot spot = caretSpot('cou < 3', 3, FieldProfile.behaviorExpression);
      expect(spot.source, CaretSource.expression);
      expect((spot.query, spot.from, spot.to), ('cou', 0, 3));
    });

    test('should read a hole in a template and a placeholder after one brace', () {
      expect(caretSpot('Hi {{ vie', 9, FieldProfile.guiText).source, CaretSource.expression);
      final CaretSpot placeholder = caretSpot('Hi {pla', 7, argumentProfile('animus:say', 'text'));
      expect((placeholder.source, placeholder.query, placeholder.from), (CaretSource.placeholder, 'pla', 4));
    });

    test('should tell tags, colour values and closing tags apart', () {
      expect(caretSpot('<bo', 3, FieldProfile.richText).source, CaretSource.tag);
      expect(caretSpot('<color:re', 9, FieldProfile.richText).source, CaretSource.tagColor);
      expect(caretSpot('<red>hi</', 9, FieldProfile.richText).source, CaretSource.closingTag);
      expect(caretSpot('a < b', 5, FieldProfile.richText).source, CaretSource.none);
    });

    test('should open a PlaceholderAPI context only after an opening percent', () {
      expect(caretSpot('%vau', 4, FieldProfile.guiText).source, CaretSource.papi);
      expect(caretSpot('%vault_eco% and', 15, FieldProfile.guiText).source, CaretSource.none);
    });

    test('should offer flag names inside a flag call string', () {
      final CaretSpot spot = caretSpot("has_flag('player', 'me", 22, FieldProfile.behaviorExpression);
      expect((spot.source, spot.query), (CaretSource.flagName, 'me'));
    });

    test('should take the whole field for an id and the first word for a command', () {
      final CaretSpot value = caretSpot('  dia', 5, argumentProfile('animus:give-item', 'item'));
      expect((value.source, value.query, value.from, value.to), (CaretSource.value, 'dia', 2, 5));
      expect(caretSpot('/tp', 3, argumentProfile('animus:command', 'command')).source, CaretSource.command);
      expect(caretSpot('/tp @s', 6, argumentProfile('animus:command', 'command')).source, CaretSource.none);
    });
  });

  group('rows', () {
    test('should list declared variables with type and scope before roots', () {
      final CompletionResult result = completeText('', 0, FieldProfile.behaviorExpression, env);
      expect(result.auto, isFalse);
      final CompletionItem count = result.items.firstWhere((CompletionItem item) => item.label == 'count');
      expect((count.kind, count.type, count.detail), (CompletionKind.variable, 'integer', 'Player variable'));
      expect(labels(result), contains('player'));
    });

    test('should offer facts after a root and context keys from the event', () {
      expect(labels(completeText('player.he', 9, FieldProfile.behaviorExpression, env)), contains('player.health'));
      expect(labels(completeText('context.', 8, FieldProfile.behaviorExpression, env)), unorderedEquals(<String>['context.projectile', 'context.shooter']));
    });

    test('should rank an item id fuzzily and open as you type', () {
      final CompletionResult result = completeText('dsw', 3, argumentProfile('animus:give-item', 'item'), env);
      expect(result.auto, isTrue);
      expect(labels(result).first, 'diamond_sword');
    });

    test('should offer document ids of the referenced kind', () {
      expect(labels(completeText('', 0, argumentProfile('animus:navigate', 'route'), env)), <String>['town/loop', 'mine/path']);
    });

    test('should give colour rows a swatch', () {
      final CompletionItem red = completeText('<re', 3, FieldProfile.richText, env).items.first;
      expect((red.label, red.swatch), ('red', '#FF5555'));
    });

    test('should close the most recent open tag first', () {
      expect(labels(completeText('<red><bold>hi</', 15, FieldProfile.richText, env)), <String>['bold', 'red']);
    });

    test('should offer whole holes after one brace in a GUI text', () {
      expect(labels(completeText('{view', 5, FieldProfile.guiText, env)), contains('{{ viewer.name }}'));
    });
  });

  group('insertion', () {
    test('should replace the typed prefix and the rest of the word', () {
      final CompletionResult result = completeText('Hi {pla}!', 7, argumentProfile('animus:say', 'text'), env);
      final AppliedCompletion applied = result.apply('Hi {pla}!', result.items.first);
      expect(applied.text, 'Hi {player_name}!');
      expect(applied.selectionStart, 'Hi {player_name}'.length);
    });

    test('should wrap the caret in a decoration and visit snippet stops in order', () {
      final AppliedCompletion bold = applySnippet('<bo', 1, 3, r'bold>$0</bold>');
      expect((bold.text, bold.selectionStart), ('<bold></bold>', 6));
      final AppliedCompletion color = applySnippet('<', 1, 1, r'color:${1:red}>$0</color>');
      expect(color.text, '<color:red></color>');
      expect((color.selectionStart, color.selectionEnd), (7, 10));
      expect(color.stops, <TabStop>[const TabStop(11, 11)]);
    });

    test('should put the caret inside a function call', () {
      final AppliedCompletion call = applySnippet('le', 0, 2, r'len($1)');
      expect((call.text, call.selectionStart), ('len()', 4));
      expect(call.stops, <TabStop>[const TabStop(5, 5)]);
    });

    test('should move later stops with an edit before them', () {
      expect(shiftStops(<TabStop>[const TabStop(10, 12)], 4, 3), <TabStop>[const TabStop(13, 15)]);
    });
  });

  group('hints', () {
    test('should underline an unknown variable the compiler would refuse', () {
      final List<TextHint> hints = textHints('count + total', FieldProfile.behaviorExpression, env);
      expect(hints.single.start, 8);
      expect(hints.single.message, contains('No variable named total'));
    });

    test('should underline an unknown name inside a GUI hole only', () {
      final List<TextHint> hints = textHints('Hi {{ viewer.nam }} <red>x <wobble>', FieldProfile.guiText, env);
      expect(hints.map((TextHint hint) => hint.message), <String>['Unknown name viewer.nam.', 'Not a supported tag. Shown as text.']);
    });

    test('should underline a placeholder the action does not replace', () {
      final List<TextHint> hints = textHints('{player_name} {npc}', argumentProfile('animus:say', 'text'), env);
      expect((hints.single.start, hints.single.end), (14, 19));
    });

    test('should underline an unknown id only when the list is complete', () {
      expect(textHints('diamond_swrd', argumentProfile('animus:give-item', 'item'), env).single.message, 'Unknown item.');
      expect(textHints('minecraft:diamond', argumentProfile('animus:give-item', 'item'), env), isEmpty);
      expect(textHints('anything', argumentProfile('animus:play-sound', 'sound'), env), isEmpty);
      expect(textHints('new_flag', argumentProfile('animus:set-flag', 'name'), env), isEmpty);
    });
  });

  test('should collect flag and quest names and document ids from the workspace', () {
    final List<AnimusDocument> documents = <AnimusDocument>[
      const AnimusDocument(
        kind: DocumentKind.behavior,
        id: 'town/greeter',
        path: 'behaviors/town/greeter.json',
        source: '',
        diagnostics: <Diagnostic>[],
        json: <String, Object?>{
          'routines': <String, Object?>{
            'main': <String, Object?>{
              'steps': <Object?>[
                <String, Object?>{'type': 'action', 'capability': 'animus:set-flag', 'arguments': <String, Object?>{'name': 'met_baker'}},
                <String, Object?>{'type': 'action', 'capability': 'animus:set-quest-state', 'arguments': <String, Object?>{'name': 'bread'}},
                <String, Object?>{'type': 'condition', 'expression': "has_flag('player', 'gift') && quest('player', 'cake') > 1"},
              ],
            },
          },
        },
      ),
    ];
    final CompletionSources found = workspaceSources(documents);
    expect(found.flags, <String>['gift', 'met_baker']);
    expect(found.quests, <String>['bread', 'cake']);
    expect(found.ids(DocumentKind.behavior), <String>['town/greeter']);
  });

  test('should strip the minecraft namespace from registry lists', () {
    final Map<RegistryKind, List<String>> parsed = parseRegistries(<String, Object?>{
      'registries': <String, Object?>{
        'sounds': <Object?>['minecraft:entity.villager.yes', 'custom:ping'],
      },
    });
    expect(parsed[RegistryKind.sound], <String>['custom:ping', 'entity.villager.yes']);
  });

  test('should keep GUI names in reach of list entries', () {
    final CompletionEnv local = CompletionEnv(gui: ExpressionNames.standard.withLocal('entry'));
    expect(labels(completeText('{{ ent', 6, FieldProfile.guiText, local)), contains('entry'));
  });

  group('code view strings', () {
    const String say = '{"routines": {"main": {"type": "action", "capability": "animus:say", "arguments": {"text": "Hi <re"}}}}';
    const String give = '{"routines": {"main": {"type": "action", "capability": "animus:give-item", "arguments": {"item": "dsw"}}}}';

    test('should complete a tag inside an argument string', () {
      final int caret = say.indexOf('<re') + 3;
      final JsonSuggestion red = suggestJson(say, caret, InspectSchemas.embedded.behavior, catalog: catalog, sources: sources).first;
      expect((red.label, red.swatch), ('red', '#FF5555'));
      expect(red.applyTo(say).$1, contains('"text": "Hi <red>"'));
    });

    test('should complete a registry id from the sources', () {
      final int caret = give.indexOf('dsw') + 3;
      final JsonSuggestion item = suggestJson(give, caret, InspectSchemas.embedded.behavior, catalog: catalog, sources: sources).first;
      expect((item.label, item.kind, item.type), ('diamond_sword', CompletionKind.registry, 'item'));
    });
  });
}
