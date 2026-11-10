import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/offline/new_documents.dart';
import 'package:animus_editor/logic/quest/quest_model.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

List<Diagnostic> schemaProblems(Object? json) => SharedSchemas.embedded.validate(DocumentKind.quest, json);

JsonMap twoStages() => <String, Object?>{
      'schemaVersion': 1,
      'revision': 1,
      'id': 'town:bread',
      'title': 'Bread for the baker',
      'givers': <Object?>['baker'],
      'stages': <Object?>[
        <String, Object?>{
          'description': 'Find wheat',
          'objectives': <Object?>[
            <String, Object?>{'type': 'collect', 'item': <String, Object?>{'id': 'minecraft:wheat'}, 'amount': 3},
            <String, Object?>{'type': 'kill', 'entity': 'minecraft:zombie', 'amount': 2},
          ],
        },
        <String, Object?>{
          'objectives': <Object?>[
            <String, Object?>{'type': 'bring', 'npc': 'baker', 'item': <String, Object?>{'id': 'minecraft:bread'}, 'amount': 2},
          ],
        },
      ],
      'rewards': <String, Object?>{
        'items': <Object?>[
          <String, Object?>{'item': <String, Object?>{'id': 'minecraft:emerald'}, 'amount': 4},
        ],
        'money': 25,
        'experience': 50,
      },
    };

void main() {
  group('quest documents', () {
    test('should locate quests under quests/<namespace>/<name>.json', () {
      expect(locateDocument('quests/town/bread.json'), const DocumentLocation(DocumentKind.quest, 'town:bread'));
      expect(newDocumentPath(DocumentKind.quest, 'town:bread'), 'quests/town/bread.json');
      expect(validDocumentId(DocumentKind.quest, 'town:bread'), isTrue);
    });

    test('should start a new quest that the shared schema accepts', () {
      final JsonMap quest = newQuest('town:first', title: 'First steps');
      expect(schemaProblems(quest), isEmpty);
      expect(quest['id'], 'town:first');
      expect(quest['title'], 'First steps');
      expect(validDocumentId(DocumentKind.quest, 'town:a/b'), isFalse);
    });

    test('should accept the two stage example and refuse a talk objective with an item', () {
      expect(schemaProblems(twoStages()), isEmpty);
      final Object? bad = writeIn(twoStages(), <JsonStep>['stages', 0, 'objectives', 0], <String, Object?>{'type': 'talk', 'npc': 'baker', 'item': <String, Object?>{'id': 'minecraft:stone'}});
      expect(schemaProblems(bad), isNotEmpty);
    });
  });

  group('objective types', () {
    test('should give every objective type a template the schema accepts', () {
      for (final String type in questObjectiveTypes) {
        final JsonMap quest = <String, Object?>{
          ...newQuest('town:t'),
          'stages': <Object?>[
            <String, Object?>{'objectives': <Object?>[newObjective(type, npc: 'baker')]},
          ],
        };
        expect(schemaProblems(quest), isEmpty, reason: type);
      }
    });

    test('should keep id, description and amount when the type changes where allowed', () {
      final JsonMap kill = <String, Object?>{'type': 'kill', 'id': 'wolves', 'description': 'Thin the pack', 'entity': 'minecraft:wolf', 'amount': 5};
      final JsonMap collect = retypeObjective(kill, 'collect');
      expect(collect['type'], 'collect');
      expect(collect['id'], 'wolves');
      expect(collect['description'], 'Thin the pack');
      expect(collect['amount'], 5);
      expect(collect.containsKey('entity'), isFalse);
      expect(asMap(collect['item'])['id'], isNotNull);
      final JsonMap reach = retypeObjective(kill, 'reach');
      expect(reach.containsKey('amount'), isFalse);
      expect(reach['world'], isNotNull);
      final JsonMap talk = retypeObjective(<String, Object?>{'type': 'bring', 'npc': 'smith', 'item': <String, Object?>{'id': 'minecraft:iron_ingot'}}, 'talk');
      expect(talk, <String, Object?>{'type': 'talk', 'npc': 'smith'});
    });

    test('should write a default journal line for each objective', () {
      expect(objectiveLine(<String, Object?>{'type': 'talk', 'npc': 'baker'}), 'Talk to baker');
      expect(objectiveLine(<String, Object?>{'type': 'talk', 'npc': 'baker'}, npcName: (String id) => 'Bob'), 'Talk to Bob');
      expect(objectiveLine(<String, Object?>{'type': 'bring', 'npc': 'baker', 'item': <String, Object?>{'id': 'minecraft:bread'}, 'amount': 2}), 'Bring 2 bread to baker');
      expect(objectiveLine(<String, Object?>{'type': 'kill', 'entity': 'minecraft:zombie', 'amount': 3}), 'Defeat 3 zombie');
      expect(objectiveLine(<String, Object?>{'type': 'kill', 'npc': 'bandit'}), 'Defeat bandit');
      expect(objectiveLine(<String, Object?>{'type': 'reach', 'world': 'world', 'x': 10, 'y': 64, 'z': -3}), 'Go to 10, 64, -3');
      expect(objectiveLine(<String, Object?>{'type': 'collect', 'item': <String, Object?>{'id': 'minecraft:oak_log'}}), 'Collect 1 oak log');
      expect(objectiveLine(<String, Object?>{'type': 'custom', 'id': 'ring_the-bell'}), 'ring the bell');
      expect(objectiveLine(<String, Object?>{'type': 'kill', 'entity': 'minecraft:zombie', 'description': 'Clear the crypt'}), 'Clear the crypt');
    });
  });

  group('quest checks', () {
    test('should warn about NPCs and quests the workspace does not have', () {
      final JsonMap quest = <String, Object?>{
        ...twoStages(),
        'turnIn': 'mayor',
        'requirements': <String, Object?>{'quests': <Object?>['town:intro']},
      };
      final List<Diagnostic> problems = questChecks(quest, npcs: <String>{'baker'}, quests: <String>{'town:bread'}).where((Diagnostic d) => d.severity != DiagnosticSeverity.info).toList();
      expect(problems.map((Diagnostic d) => d.path), containsAll(<String>[r'$.turnIn', r'$.requirements.quests[0]']));
      expect(problems.every((Diagnostic d) => d.severity == DiagnosticSeverity.warning), isTrue);
      expect(questChecks(<String, Object?>{...twoStages(), 'rewards': <String, Object?>{'experience': 5}}, npcs: <String>{'baker'}, quests: <String>{'town:bread'}), isEmpty);
    });

    test('should refuse repeated objective ids within a stage', () {
      final Object? quest = writeIn(twoStages(), <JsonStep>['stages', 0, 'objectives'], <Object?>[
        <String, Object?>{'type': 'custom', 'id': 'bell'},
        <String, Object?>{'type': 'custom', 'id': 'bell'},
      ]);
      final List<Diagnostic> problems = questChecks(asMap(quest)).where((Diagnostic d) => d.severity != DiagnosticSeverity.info).toList();
      expect(problems.single.path, r'$.stages[0].objectives[1].id');
      expect(problems.single.severity, DiagnosticSeverity.error);
    });

    test('should warn about unknown items only when the registry is known', () {
      final Object? quest = writeIn(twoStages(), <JsonStep>['stages', 0, 'objectives', 0, 'item', 'id'], 'minecraft:not_an_item');
      expect(questChecks(asMap(quest)).where((Diagnostic d) => d.severity != DiagnosticSeverity.info), isEmpty);
      final List<Diagnostic> problems = questChecks(asMap(quest), knownItem: (String id) => id != 'minecraft:not_an_item').where((Diagnostic d) => d.severity != DiagnosticSeverity.info).toList();
      expect(problems.single.path, r'$.stages[0].objectives[0].item.id');
    });

    test('should note that items, money and commands need the apply:commands scope', () {
      final List<Diagnostic> notes = questChecks(twoStages());
      expect(notes.single.path, r'$.rewards');
      expect(notes.single.message, contains('apply:commands'));
      expect(questChecks(<String, Object?>{...twoStages(), 'rewards': <String, Object?>{'experience': 5}}), isEmpty);
    });

    test('should note that reward commands need the allow-list', () {
      final Object? quest = writeIn(twoStages(), <JsonStep>['rewards', 'commands'], <Object?>[
        <String, Object?>{'command': 'say {player} helped'},
      ]);
      final Diagnostic note = questChecks(asMap(quest)).firstWhere((Diagnostic d) => d.path == r'$.rewards.commands');
      expect(note.severity, DiagnosticSeverity.info);
    });
  });

  group('journal entry', () {
    test('should show the active stage as the server journal does', () {
      final QuestJournalEntry entry = journalEntry(twoStages(), stage: 0, npcName: (String id) => id == 'baker' ? 'Baker Bob' : id);
      expect(entry.title, 'Bread for the baker');
      expect(entry.lore, <String>[
        '&6Stage 1/2',
        '&fFind wheat',
        '&e• &fCollect 3 wheat&7 0/3',
        '&e• &fDefeat 2 zombie&7 0/2',
      ]);
      expect(journalEntry(twoStages(), stage: 1, npcName: (String id) => 'Baker Bob').lore.last, '&e• &fBring 2 bread to Baker Bob&7 0/2');
    });

    test('should wrap the description above a blank line', () {
      final QuestJournalEntry entry = journalEntry(<String, Object?>{...twoStages(), 'description': 'The baker ran out of flour and needs wheat before the morning rush.'});
      expect(entry.lore.take(4), <String>['&7The baker ran out of flour and needs', '&7wheat before the morning rush.', '', '&6Stage 1/2']);
    });

    test('should leave talk and reach objectives without a count', () {
      final Object? quest = writeIn(twoStages(), <JsonStep>['stages', 0, 'objectives'], <Object?>[
        <String, Object?>{'type': 'talk', 'npc': 'baker'},
        <String, Object?>{'type': 'reach', 'world': 'world', 'x': 1.4, 'y': 64, 'z': 2.6},
      ]);
      expect(journalEntry(asMap(quest)).lore.skip(2), <String>['&e• &fTalk to baker', '&e• &fGo to 1, 64, 3']);
    });

    test('should show a finished entry without objectives', () {
      final QuestJournalEntry entry = journalEntry(<String, Object?>{...twoStages(), 'description': 'Help the baker.'}, finished: true);
      expect(entry.lore, <String>['&7Help the baker.', '', '&aFinished']);
      expect(entry.icon, 'minecraft:book');
      expect(journalEntry(twoStages()).icon, 'minecraft:writable_book');
    });

    test('should say the offer as the server does', () {
      expect(offerLine(twoStages()), 'I have a task for you.');
      expect(offerLine(<String, Object?>{...twoStages(), 'description': 'Help me.'}), 'Help me.');
      expect(offerLine(<String, Object?>{...twoStages(), 'messages': <String, Object?>{'offer': 'Hi {player}, about {quest}'}}, player: 'Steve'), 'Hi Steve, about Bread for the baker');
    });

    test('should use the quest state name behaviors read', () {
      expect(questStateName('town:bread'), 'bread');
      expect(questStageExpression('town:bread'), "quest('player', 'bread')");
    });
  });

  test('should read flag values as JSON, else as text', () {
    expect(flagValue(''), isNull);
    expect(flagValue('true'), isTrue);
    expect(flagValue('3'), 3);
    expect(flagValue('"x"'), 'x');
    expect(flagValue('helped'), 'helped');
  });
}
