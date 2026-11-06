import 'dart:convert';

import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/inspect/behavior_kept.dart';
import 'package:animus_editor/logic/inspect/completion_catalog.dart';
import 'package:animus_editor/logic/inspect/inspect_schemas.dart';
import 'package:animus_editor/logic/inspect/typed_values.dart';
import 'package:animus_editor/logic/npc/dialogue_prompts.dart';
import 'package:animus_editor/logic/npc/reference_options.dart';
import 'package:animus_editor/logic/npc/route_copy.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/state/editor_store.dart';
import 'package:test/test.dart';

final CompletionCatalog catalog = InspectSchemas.embedded.catalog;

AnimusDocument doc(DocumentKind kind, String id, JsonMap json) =>
    AnimusDocument(kind: kind, id: id, path: '${kind.folder}/$id.json', source: jsonEncode(json), json: json, diagnostics: const <Diagnostic>[]);

JsonMap choices(String id, String prompt, List<(String, String)> options) => <String, Object?>{
      'type': 'action',
      'capability': 'animus:choices',
      'arguments': <String, Object?>{
        'id': id,
        'prompt': prompt,
        'options': <Object?>[
          for (final (String option, String text) in options) <String, Object?>{'id': option, 'text': text},
        ],
      },
    };

final JsonMap ferryman = <String, Object?>{
  'schemaVersion': 1,
  'id': 'harbor:ferryman',
  'entry': 'main',
  'routines': <String, Object?>{
    'main': <String, Object?>{
      'type': 'sequence',
      'steps': <Object?>[
        choices('ferry', 'Need a crossing?', <(String, String)>[('yes', 'Yes'), ('no', 'No')]),
      ],
    },
    'cross': <String, Object?>{
      'type': 'condition',
      'capability': 'animus:has-item',
      'then': choices('fare', 'Pay now or later?', <(String, String)>[('now', 'Now'), ('later', 'Later')]),
    },
  },
  'triggers': <Object?>[
    <String, Object?>{'id': 'talk', 'event': 'interaction'},
    <String, Object?>{'id': 'ferry-yes', 'event': 'dialogue_choice', 'dialogue': 'ferry', 'choice': 'yes', 'routine': 'cross'},
  ],
};

void main() {
  group('dialogue_choice pickers', () {
    test('every prompt the behavior asks is offered, nested ones too', () {
      final List<DialoguePrompt> prompts = dialoguePrompts(ferryman);
      expect(prompts.map((DialoguePrompt p) => p.id), <String>['ferry', 'fare']);
      final PickerOptions picked = promptOptions(prompts);
      expect(picked.ids, <String>['ferry', 'fare']);
      expect(picked.labels['ferry'], 'ferry: Need a crossing?');
    });

    test('a prompt asked twice is offered once with every option', () {
      final JsonMap twice = <String, Object?>{
        'routines': <String, Object?>{
          'a': choices('ask', 'Hi?', <(String, String)>[('one', 'One')]),
          'b': choices('ask', '', <(String, String)>[('one', 'One'), ('two', 'Two')]),
        },
      };
      final List<DialoguePrompt> prompts = dialoguePrompts(twice);
      expect(prompts.single.options.map((DialoguePromptOption o) => o.id), <String>['one', 'two']);
      expect(promptOptions(prompts).labels['ask'], 'ask: Hi?');
    });

    test('the choice offers the options of the picked prompt', () {
      final List<DialoguePrompt> prompts = dialoguePrompts(ferryman);
      final PickerOptions options = choiceOptions(prompts, 'fare');
      expect(options.ids, <String>['now', 'later']);
      expect(options.labels['now'], 'now: Now');
      expect(choiceOptions(prompts, 'missing').ids, isEmpty);
      expect(choiceOptions(prompts, null).ids, isEmpty);
    });

    test('picking another prompt drops a choice it does not have', () {
      final List<DialoguePrompt> prompts = dialoguePrompts(ferryman);
      final JsonMap trigger = asMap(asList(ferryman['triggers'])[1]);
      final JsonMap moved = withDialogue(trigger, 'fare', prompts);
      expect(moved['dialogue'], 'fare');
      expect(moved.containsKey('choice'), isFalse);
      expect(moved['routine'], 'cross');
      final JsonMap kept = withDialogue(<String, Object?>{...trigger, 'choice': 'no'}, 'ferry', prompts);
      expect(kept['choice'], 'no');
    });

    test('the suggested trigger id is free', () {
      expect(dialogueTriggerId('ferry', 'yes', <String>['talk', 'ferry-yes']), 'ferry-yes-2');
      expect(dialogueTriggerId('ferry', null, <String>['talk']), 'ferry');
    });

    test('dialogue triggers are found', () {
      expect(hasDialogueTriggers(ferryman), isTrue);
      expect(hasDialogueTriggers(<String, Object?>{'triggers': <Object?>[<String, Object?>{'event': 'interaction'}]}), isFalse);
    });
  });

  group('behavior and shop pickers', () {
    final List<AnimusDocument> documents = <AnimusDocument>[
      doc(DocumentKind.behavior, 'greet', <String, Object?>{'id': 'greet'}),
      doc(DocumentKind.behavior, 'welcome', <String, Object?>{'id': 'welcome', 'server': true}),
      doc(DocumentKind.behavior, 'patrol', <String, Object?>{'id': 'patrol'}),
      doc(DocumentKind.shop, 'town:baker', <String, Object?>{'title': 'Baker'}),
      doc(DocumentKind.shop, 'smith', <String, Object?>{'title': 'Smith'}),
    ];

    test('workspace NPC behaviors only, without the listed ones', () {
      final PickerOptions options = behaviorPickerOptions(documents, listed: <String>['patrol']);
      expect(options.ids, <String>['greet']);
      expect(options.groups, isEmpty);
    });

    test('server behaviors the workspace lacks are added in their own group', () {
      final PickerOptions options = behaviorPickerOptions(documents, server: <String>['greet', 'guard', 'welcome'], listed: const <String>[]);
      expect(options.ids, <String>['greet', 'patrol', 'guard']);
      expect(options.groups, <String, String>{'greet': 'Workspace', 'patrol': 'Workspace', 'guard': 'On the server'});
    });

    test('shops are the workspace shop ids, sorted', () {
      expect(shopPickerOptions(documents), <String>['smith', 'town:baker']);
    });
  });

  group('route copy', () {
    final AnimusDocument route = doc(DocumentKind.route, 'town-patrol', <String, Object?>{
      'schemaVersion': 1,
      'mode': 'walk',
      'points': <Object?>[
        <String, Object?>{'x': 1, 'y': 64, 'z': 2},
      ],
    });
    final AnimusDocument npc = doc(DocumentKind.npc, 'town/baker', <String, Object?>{
      'id': 'baker',
      'modules': <String, Object?>{
        'animus:navigation': <String, Object?>{'enabled': true, 'route': 'town-patrol', 'extra': 1},
        'animus:shop': <String, Object?>{'shop': 'town:baker'},
      },
    });
    final List<AnimusDocument> documents = <AnimusDocument>[route, npc, doc(DocumentKind.route, 'baker-route', const <String, Object?>{})];

    test('creates a route with a free id from the NPC and points the NPC at it, in one change list', () {
      final RouteCopy copy = copyRouteFor(npc, route, documents);
      expect(copy.id, 'baker-route-2');
      expect(copy.changes, hasLength(2));
      final DocumentChange created = copy.changes.first;
      expect(created.kind, DocumentKind.route);
      expect(created.id, 'baker-route-2');
      expect(created.path, 'routes/baker-route-2.json');
      expect(jsonDecode(created.source!), route.json);
      final DocumentChange pointed = copy.changes.last;
      expect(pointed.kind, DocumentKind.npc);
      expect(pointed.id, 'town/baker');
      final JsonMap next = asMap(pointed.update!(npc.json));
      final JsonMap navigation = asMap(asMap(next['modules'])['animus:navigation']);
      expect(navigation, <String, Object?>{'enabled': true, 'route': 'baker-route-2', 'extra': 1});
      expect(asMap(next['modules'])['animus:shop'], <String, Object?>{'shop': 'town:baker'});
    });

    test('a group copies under its values', () {
      final AnimusDocument group = doc(DocumentKind.group, 'guards', <String, Object?>{
        'id': 'guards',
        'values': <String, Object?>{
          'modules': <String, Object?>{
            'animus:navigation': <String, Object?>{'route': 'town-patrol'},
          },
        },
      });
      final RouteCopy copy = copyRouteFor(group, route, documents);
      expect(copy.id, 'guards-route');
      final JsonMap next = asMap(copy.changes.last.update!(group.json));
      expect(asMap(asMap(asMap(next['values'])['modules'])['animus:navigation'])['route'], 'guards-route');
    });
  });

  group('kept fields round trip', () {
    test('a behavior document reads and writes its other fields', () {
      final JsonMap document = <String, Object?>{'schemaVersion': 1, 'id': 'a', 'note': 'x', 'entry': 'main', 'extra': <String, Object?>{'k': 1}};
      final JsonMap other = unknownEntries(document, behaviorDocumentKeys);
      expect(other, <String, Object?>{'note': 'x', 'extra': <String, Object?>{'k': 1}});
      expect(withUnknownEntries(document, behaviorDocumentKeys, other), document);
      final JsonMap written = withUnknownEntries(document, behaviorDocumentKeys, <String, Object?>{'note': 'y', 'added': true});
      expect(written.keys, <String>['schemaVersion', 'id', 'note', 'entry', 'added']);
      expect(written['note'], 'y');
    });

    test('a trigger reads and writes the fields its event does not declare', () {
      final Set<String> known = triggerKnownKeys(catalog.triggers['dialogue_choice']);
      expect(known, containsAll(<String>['id', 'event', 'dialogue', 'choice', 'routine']));
      final JsonMap trigger = <String, Object?>{'id': 't', 'event': 'dialogue_choice', 'dialogue': 'ferry', 'legacy': 3};
      final JsonMap other = unknownEntries(trigger, known);
      expect(other, <String, Object?>{'legacy': 3});
      expect(withUnknownEntries(trigger, known, other), trigger);
      expect(withUnknownEntries(trigger, known, const <String, Object?>{}), <String, Object?>{'id': 't', 'event': 'dialogue_choice', 'dialogue': 'ferry'});
      expect(triggerKnownKeys(null), <String>{'event'});
    });

    test('capability arguments outside the catalog read and write as their own map', () {
      final CapabilitySpec say = catalog.capability('animus:say')!;
      final Set<String> known = argumentKnownKeys(say);
      expect(known, contains('text'));
      final JsonMap arguments = <String, Object?>{'text': 'Hi', 'mood': 'glad'};
      expect(unknownEntries(arguments, known), <String, Object?>{'mood': 'glad'});
      expect(withUnknownEntries(arguments, known, <String, Object?>{'mood': 'sad', 'volume': 2}), <String, Object?>{'text': 'Hi', 'mood': 'sad', 'volume': 2});
      final Set<String> none = argumentKnownKeys(null);
      expect(none, isEmpty);
      final JsonMap module = <String, Object?>{'speed': 1, 'tags': <Object?>['a']};
      expect(withUnknownEntries(module, none, unknownEntries(module, none)), module);
    });

    test('a module without a schema round-trips through the key/value entries', () {
      final JsonMap module = <String, Object?>{'enabled': true, 'radius': 4.5, 'names': <Object?>['a']};
      final JsonMap renamed = renameKey(module, 'radius', 'range');
      expect(renamed.keys, <String>['enabled', 'range', 'names']);
      expect(removeEntry(putEntry(module, 'extra', ''), 'extra'), module);
    });
  });
}
