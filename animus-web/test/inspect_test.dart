import 'dart:convert';
import 'dart:io';

import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/inspect/behavior_edit.dart';
import 'package:animus_editor/logic/inspect/completion_catalog.dart';
import 'package:animus_editor/logic/inspect/inspect_schemas.dart';
import 'package:animus_editor/logic/inspect/json_completion.dart';
import 'package:animus_editor/logic/inspect/json_outline.dart';
import 'package:animus_editor/logic/inspect/json_syntax.dart';
import 'package:animus_editor/logic/inspect/schema_fields.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

final InspectSchemas schemas = InspectSchemas.embedded;
final CompletionCatalog catalog = schemas.catalog;

JsonMap _fixture(String path) => jsonDecode(File('../schema/fixtures/export/$path').readAsStringSync()) as JsonMap;

FieldSpec _field(List<FieldSpec> fields, String key) => fields.firstWhere((FieldSpec field) => field.key == key);

/// [text] with `|` removed, and the offset where it was.
(String, int) _caret(String text) => (text.replaceFirst('|', ''), text.indexOf('|'));

void main() {
  group('schema fields', () {
    test('should size a number field to the widest value its range allows', () {
      expect(const FieldSpec(key: 'afterTicks', kind: FieldKind.integer, minimum: 1, maximum: 12000).digits, 5);
      expect(const FieldSpec(key: 'x', kind: FieldKind.number, minimum: -30000000, maximum: 30000000).digits, 12);
      expect(const FieldSpec(key: 'speed', kind: FieldKind.number, minimum: 0, maximum: 1.5).digits, 6);
      expect(const FieldSpec(key: 'open', kind: FieldKind.integer, minimum: 0).digits, isNull);
      expect(const FieldSpec(key: 'long', kind: FieldKind.integer, minimum: 0, maximum: 9007199254740991).digits, isNull);
    });

    test('npc fields carry kinds, required markers, bounds and descriptions', () {
      final JsonMap npc = _fixture('npcs/examples/greeter.json');
      final List<FieldSpec> fields = schemas.npc.fields(schemas.npc.root, npc);
      expect(_field(fields, 'name').required, isTrue);
      expect(_field(fields, 'name').description, 'The displayed name.');
      expect(_field(fields, 'spawned').kind, FieldKind.boolean);
      expect(_field(fields, 'skin').required, isFalse);
      expect(_field(fields, 'revision').kind, FieldKind.integer);
      expect(_field(fields, 'revision').minimum, 1);
      expect(_field(fields, 'schemaVersion').kind, FieldKind.choice);
      final Map<String, Object?> position = _field(fields, 'position').schema;
      final FieldSpec x = _field(schemas.npc.fields(position, npc['position']), 'x');
      expect(x.kind, FieldKind.number);
      expect(x.maximum, 30000000);
      expect(x.check(40000000), contains('at most'));
    });

    test('a node resolves its type branch through \$ref and if/then', () {
      final SchemaView behavior = schemas.behavior;
      final Map<String, Object?> node = behavior.resolve(<String, Object?>{r'$ref': r'#/$defs/node'});
      final List<FieldSpec> wait = behavior.fields(node, <String, Object?>{'type': 'wait'});
      final FieldSpec ticks = _field(wait, 'ticks');
      expect(ticks.kind, FieldKind.integer);
      expect(ticks.required, isTrue);
      expect(ticks.minimum, 1);
      expect(ticks.maximum, 72000);
      final FieldSpec type = _field(wait, 'type');
      expect(type.kind, FieldKind.choice);
      expect(type.options.map((FieldOption option) => option.value), containsAll(<String>['sequence', 'action', 'set']));
      final List<FieldSpec> action = behavior.fields(node, <String, Object?>{'type': 'action'});
      final FieldSpec capability = _field(action, 'capability');
      expect(capability.kind, FieldKind.choice);
      expect(capability.openChoice, isTrue, reason: 'module capabilities are allowed beside the built-in ones');
      expect(capability.options.map((FieldOption option) => option.value), contains('animus:say'));
      expect(capability.options.firstWhere((FieldOption option) => option.value == 'animus:say').description, isNotEmpty);
    });

    test('schema at a path follows the document through conditional branches', () {
      final JsonMap behavior = _fixture('behaviors/greeter/greeter.json');
      final Map<String, Object?>? radius = schemas.behavior.at(<JsonStep>['triggers', 1, 'radius'], behavior);
      expect(radius, isNotNull);
      final FieldSpec spec = schemas.behavior.field('radius', radius);
      expect(spec.exclusiveMinimum, isTrue);
      expect(spec.minimum, 0);
      expect(spec.check(0), contains('above'));
      final Map<String, Object?>? say = schemas.behavior.at(<JsonStep>['routines', 'main', 'steps', 1, 'then', 'arguments'], behavior);
      expect(schemas.behavior.fields(say, null).map((FieldSpec field) => field.key), contains('text'));
    });

    test('catalog reads capabilities, triggers and node types', () {
      final CapabilitySpec say = catalog.capability('animus:say')!;
      expect(_field(say.arguments, 'text').required, isTrue);
      expect(_field(say.arguments, 'audience').kind, FieldKind.choice);
      final CapabilitySpec look = catalog.capability('animus:look')!;
      expect(look.oneOfRequired, <List<String>>[<String>['target'], <String>['x', 'y', 'z']]);
      expect(catalog.capability('animus:command')!.note, contains('privileged'));
      expect(catalog.triggers['proximity']!.fields.map((FieldSpec field) => field.key), containsAll(<String>['phase', 'radius']));
      expect(nodeListFields('sequence', catalog), <String>['steps']);
      expect(nodeSlotFields('condition', catalog), <String>['then', 'else']);
      expect(InspectSchemas.from(null), same(schemas));
    });

    test('live schemas replace the embedded ones they name', () {
      final Map<String, Object?> live = <String, Object?>{
        'completions': <String, Object?>{
          'capabilities': <Object?>[
            <String, Object?>{'id': 'shop:open', 'kind': 'action', 'description': 'Module action.', 'arguments': <String, Object?>{}},
          ],
        },
      };
      final InspectSchemas fromServer = InspectSchemas.from(live);
      expect(fromServer.live, isTrue);
      expect(fromServer.catalog.capability('shop:open'), isNotNull);
      expect(fromServer.npc, same(schemas.npc));
      expect(InspectSchemas.from(live), same(fromServer));
    });
  });

  group('json outline', () {
    const String text = '{\n  "a": 1,\n  "list": [\n    {"b": true},\n    "x"\n  ],\n  "we ird": {"c": null}\n}\n';

    test('maps diagnostic paths to lines, falling back to the nearest ancestor', () {
      final JsonOutline outline = scanJson(text);
      expect(outline.lineForPath(r'$'), 1);
      expect(outline.lineForPath(r'$.a'), 2);
      expect(outline.lineForPath(r'$.list'), 3);
      expect(outline.lineForPath(r'$.list[0].b'), 4);
      expect(outline.lineForPath(r'$.list[1]'), 5);
      expect(outline.lineForPath(r'$["we ird"].c'), 7);
      expect(outline.lineForPath(r'$.list[0].missing.deeper'), 4);
      expect(outline.lineForPath('not a path'), isNull);
      expect(outline.value, jsonDecode(text));
    });

    test('parses paths with quoted keys and indexes', () {
      expect(parseJsonPath(r'$.keys["#"].actions[1].type'), <JsonStep>['keys', '#', 'actions', 1, 'type']);
      expect(parseJsonPath(r"$['a.b'][0]"), <JsonStep>['a.b', 0]);
      expect(parseJsonPath(r'$'), isEmpty);
    });

    test('fixture lines match the server-style paths', () {
      final String source = File('../schema/fixtures/export/behaviors/greeter/greeter.json').readAsStringSync();
      final JsonOutline outline = scanJson(source);
      final int line = outline.lineForPath(r'$.triggers[1].radius')!;
      expect(source.split('\n')[line - 1], contains('"radius"'));
    });

    test('keeps a best-effort value of unfinished text', () {
      final JsonOutline outline = scanJson('{"type": "action", "capability": "animus:say", "arguments": {"te');
      expect(asMap(outline.value)['type'], 'action');
      expect(asMap(outline.value)['capability'], 'animus:say');
    });
  });

  group('json completion', () {
    List<String> labels(List<JsonSuggestion> suggestions) => <String>[for (final JsonSuggestion suggestion in suggestions) suggestion.label];

    test('suggests missing keys of the object at the caret', () {
      final (String text, int caret) = _caret('{"schemaVersion": 1, "na|"}');
      final List<JsonSuggestion> out = suggestJson(text, caret, schemas.npc);
      expect(labels(out), contains('name'));
      expect(labels(out), isNot(contains('schemaVersion')));
      final (String applied, int at) = out.firstWhere((JsonSuggestion s) => s.label == 'name').applyTo(text);
      expect(applied, '{"schemaVersion": 1, "name": }');
      expect(at, applied.indexOf('}'));
    });

    test('suggests keys in an empty spot with the colon added', () {
      final (String text, int caret) = _caret('{"position": {"world": "w", |}}');
      final List<JsonSuggestion> out = suggestJson(text, caret, schemas.npc);
      expect(labels(out), containsAll(<String>['x', 'y', 'z', 'yaw', 'pitch']));
      expect(labels(out), isNot(contains('world')));
      expect(out.first.insert, endsWith(': '));
    });

    test('suggests node types and capability values with availability', () {
      final (String text, int caret) = _caret('{"routines": {"main": {"type": "|"}}}');
      expect(labels(suggestJson(text, caret, schemas.behavior, catalog: catalog)), containsAll(<String>['sequence', 'wait', 'call']));
      final (String text2, int caret2) = _caret('{"routines": {"main": {"type": "condition", "capability": "flag|"}}}');
      final List<JsonSuggestion> capabilities = suggestJson(text2, caret2, schemas.behavior, catalog: catalog);
      expect(labels(capabilities), <String>['animus:flag-state']);
      final (String text3, int caret3) = _caret('{"routines": {"main": {"type": "action", "capability": "animus:sa|"}}}');
      final JsonSuggestion say = suggestJson(text3, caret3, schemas.behavior, catalog: catalog).single;
      expect(say.detail, contains('platform-unavailable'));
      expect(say.applyTo(text3).$1, '{"routines": {"main": {"type": "action", "capability": "animus:say"}}}');
    });

    test('suggests capability argument keys and enum values', () {
      final (String text, int caret) = _caret('{"routines": {"main": {"type": "action", "capability": "animus:say", "arguments": {|}}}}');
      final List<JsonSuggestion> keys = suggestJson(text, caret, schemas.behavior, catalog: catalog);
      expect(labels(keys), containsAll(<String>['text', 'audience', 'radius']));
      expect(keys.firstWhere((JsonSuggestion s) => s.label == 'text').detail, startsWith('required'));
      final (String text2, int caret2) = _caret('{"routines": {"m": {"type": "action", "capability": "animus:say", "arguments": {"audience": |}}}}');
      expect(labels(suggestJson(text2, caret2, schemas.behavior, catalog: catalog)), isNotEmpty);
    });

    test('suggests trigger events, booleans and routine names from hints', () {
      final (String text, int caret) = _caret('{"routines": {"main": {}, "wave": {}}, "triggers": [{"id": "a", "event": "prox|"}]}');
      expect(labels(suggestJson(text, caret, schemas.behavior, catalog: catalog)), <String>['proximity']);
      final (String text2, int caret2) = _caret('{"routines": {"main": {}, "wave": {}}, "triggers": [{"id": "a", "event": "spawn", "routine": "|"}]}');
      expect(labels(suggestJson(text2, caret2, schemas.behavior, catalog: catalog, hints: behaviorHints())), <String>['main', 'wave']);
      final (String text3, int caret3) = _caret('{"spawned": t|}');
      expect(labels(suggestJson(text3, caret3, schemas.npc)), <String>['true']);
    });

    test('should suggest workspace GUI ids of the open-gui kind when editing its gui argument', () {
      final ValueHints hints = behaviorHints(guiIds: <DocumentKind, List<String>>{
        DocumentKind.inventory: <String>['town:board'],
        DocumentKind.menu: <String>['lobby:main', 'town:hub'],
        DocumentKind.shop: <String>['town:market'],
      });
      List<String> at(String source) {
        final (String text, int caret) = _caret(source);
        return labels(suggestJson(text, caret, schemas.behavior, catalog: catalog, hints: hints));
      }

      expect(at('{"routines": {"m": {"type": "action", "capability": "animus:open-gui", "arguments": {"kind": "menu", "gui": "|"}}}}'),
          <String>['lobby:main', 'town:hub']);
      expect(at('{"routines": {"m": {"type": "action", "capability": "animus:open-gui", "arguments": {"gui": "|"}}}}'), <String>['town:board']);
      expect(at('{"routines": {"m": {"type": "action", "capability": "animus:open-gui", "arguments": {"kind": "shop", "gui": "|"}}}}'),
          <String>['town:market']);
      expect(at('{"routines": {"m": {"type": "action", "capability": "animus:open-shop", "arguments": {"shop": "|"}}}}'), <String>['town:market']);
      expect(at('{"routines": {"m": {"type": "action", "capability": "animus:open-gui", "arguments": {"kind": "gloss", "gui": "|"}}}}'), isEmpty);
    });

    test('suggests workspace behavior ids for npc behaviors', () {
      final (String text, int caret) = _caret('{"behaviors": ["greeter:greeter", "|"]}');
      final List<JsonSuggestion> out = suggestJson(
        text,
        caret,
        schemas.npc,
        hints: npcHints(behaviorIds: <String>['greeter:greeter', 'shop:keeper']),
      );
      expect(labels(out), containsAll(<String>['greeter:greeter', 'shop:keeper']));
    });
  });

  group('behavior edits', () {
    test('changing a node type keeps unknown keys and moves children', () {
      final JsonMap sequence = <String, Object?>{
        'type': 'sequence',
        'note': 'kept',
        'steps': <Object?>[
          <String, Object?>{'type': 'wait', 'ticks': 5},
        ],
      };
      final JsonMap parallel = changeNodeType(sequence, 'parallel', catalog);
      expect(parallel['type'], 'parallel');
      expect(parallel['note'], 'kept');
      expect(parallel.containsKey('steps'), isFalse);
      expect(asList(parallel['branches']), hasLength(1));
      final JsonMap repeat = changeNodeType(sequence, 'repeat', catalog);
      expect(repeat['count'], 1, reason: 'a new repeat runs its body once');
      expect(asMap(repeat['body'])['type'], 'wait');
      expect(repeat['note'], 'kept');
      final JsonMap wait = changeNodeType(sequence, 'wait', catalog);
      expect(wait['ticks'], 1);
      expect(wait['note'], 'kept');
      final JsonMap call = changeNodeType(wait, 'call', catalog, routine: 'wave');
      expect(call, <String, Object?>{'type': 'call', 'note': 'kept', 'routine': 'wave'});
    });

    test('a new action has its capability and required arguments', () {
      final JsonMap action = defaultNode('action', catalog);
      expect(action['capability'], 'animus:say');
      expect(asMap(action['arguments'])['text'], '');
      final JsonMap condition = defaultNode('condition', catalog);
      expect(catalog.capability(condition['capability']! as String)!.kind, 'condition');
      expect(asMap(condition['then'])['type'], 'sequence');
    });

    test('changing the capability keeps unknown arguments only', () {
      final JsonMap node = <String, Object?>{
        'type': 'action',
        'capability': 'animus:say',
        'arguments': <String, Object?>{'text': 'hi', 'radius': 4, 'moduleExtra': 1},
        'bindings': <String, Object?>{},
      };
      final JsonMap changed = changeCapability(node, 'animus:play-sound', catalog);
      final JsonMap arguments = asMap(changed['arguments']);
      expect(arguments.containsKey('text'), isFalse);
      expect(arguments['radius'], 4, reason: 'play-sound also takes radius');
      expect(arguments['moduleExtra'], 1);
      expect(arguments['sound'], '');
      expect(changed.containsKey('bindings'), isTrue);
    });

    test('trigger event changes and new triggers', () {
      final JsonMap trigger = <String, Object?>{'id': 'near', 'event': 'proximity', 'phase': 'enter', 'radius': 6, 'routine': 'wave', 'x-extra': true};
      final JsonMap timer = changeTriggerEvent(trigger, 'timer', catalog);
      expect(timer, <String, Object?>{'id': 'near', 'event': 'timer', 'routine': 'wave', 'x-extra': true, 'intervalTicks': 1});
      final JsonMap fresh = defaultTrigger('proximity', catalog, <String>['proximity']);
      expect(fresh['id'], 'proximity_2');
      expect(fresh['phase'], 'enter');
      expect(fresh['radius'], 1);
    });

    test('renaming a routine follows entry, triggers and calls', () {
      final JsonMap document = _fixture('behaviors/greeter/greeter.json');
      final JsonMap withCall = asMap(writeIn(document, <JsonStep>['routines', 'main', 'steps', 2], <String, Object?>{'type': 'call', 'routine': 'wave'}));
      final JsonMap renamed = renameRoutine(withCall, 'wave', 'greet', catalog);
      expect(asMap(renamed['routines']).keys, <String>['main', 'greet']);
      expect(readIn(renamed, <JsonStep>['triggers', 1, 'routine']), 'greet');
      expect(readIn(renamed, <JsonStep>['routines', 'main', 'steps', 2, 'routine']), 'greet');
      expect(readIn(renamed, <JsonStep>['routines', 'main', 'steps', 1, 'else', 'steps', 0, 'arguments', 'text']), startsWith('Welcome'));
      final JsonMap entry = renameRoutine(document, 'main', 'start', catalog);
      expect(entry['entry'], 'start');
    });

    test('list helpers and unknown keys', () {
      expect(insertItem(<Object?>[1, 2], 1, 9), <Object?>[1, 9, 2]);
      expect(removeItem(<Object?>[1, 2], 0), <Object?>[2]);
      expect(unknownKeys(<String, Object?>{'type': 'wait', 'ticks': 1, 'x': 2}, <String>['ticks']), <String>['x']);
      expect(uniqueName('main', <String>['main', 'main_2']), 'main_3');
      expect(uniqueName('flag', <String>['flag']), matches(RegExp(r'^[a-z][a-z0-9_]{0,63}$')), reason: 'expressions must be able to read it');
    });
  });

  group('json syntax', () {
    test('should place the first problem by line and column in words', () {
      expect(jsonSyntaxProblem('{"a": 1}'), isNull);
      final JsonSyntaxProblem missing = jsonSyntaxProblem('{\n  "a": 1\n  "b": 2\n}')!;
      expect((missing.line, missing.column), (3, 3));
      expect(missing.text, 'Line 3, column 3: expected a comma or } after the value, found a quote');
      expect(jsonSyntaxProblem('{"a": [1, 2,]}')!.message, 'remove the comma before ]');
      expect(jsonSyntaxProblem('{"a": 1,}')!.message, 'remove the comma before }');
      expect(jsonSyntaxProblem('{"a": "x')!.message, contains('never closed'));
      expect(jsonSyntaxProblem("{'a': 1}")!.message, contains('double quotes'));
      expect(jsonSyntaxProblem('{"a": 1')!.message, contains('ends early'));
      expect(jsonSyntaxProblem('')!.message, contains('empty'));
    });

    test('should complete names inside an expression string in the code view', () {
      const String text = '{"variables": {"count": {"type": "integer", "initial": 0}}, "routines": {"main": {"type": "while", "expression": "co';
      final List<JsonSuggestion> found = suggestJson(text, text.length, schemas.behavior, catalog: catalog);
      final JsonSuggestion count = found.firstWhere((JsonSuggestion suggestion) => suggestion.label == 'count');
      expect(count.applyTo(text).$1, endsWith('"expression": "count'));
    });
  });
}
