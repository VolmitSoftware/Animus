import 'dart:convert';

import 'package:animus_editor/config/starter_packs.g.dart';
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/offline/dry_run.dart';
import 'package:animus_editor/logic/offline/new_documents.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

List<String> speech(DryRunSession session) => <String>[
      for (final DryRunRun run in session.runs)
        for (final DryRunStep step in run.steps)
          if (step.speech != null) step.speech!,
    ];

void main() {
  test('should list every trigger but answers as a start, then the entry routine', () {
    final JsonMap greeter = behaviorFromTemplate(BehaviorTemplate.greeter, 'a:g');
    expect(dryRunStarts(greeter).map((DryRunStart start) => start.id), <String?>['click', 'near', null]);
    expect(dryRunStarts(greeter).first.label, 'click: a player right-clicks the NPC');
    final JsonMap dialogue = behaviorFromTemplate(BehaviorTemplate.dialogue, 'a:d');
    expect(dryRunStarts(dialogue).map((DryRunStart start) => start.id), <String?>['click', null]);
  });

  test('should greet a stranger, set the flag, and greet them again by the remembered flag', () {
    final JsonMap greeter = behaviorFromTemplate(BehaviorTemplate.greeter, 'a:g');
    final DryRunSession first = dryRun(greeter, DryRunInputs.start('click'));
    expect(speech(first), <String>['Welcome, Player! Nice to meet you.']);
    expect(first.state['flag met (relationship)'], isTrue);
    expect(first.serverOnly, isEmpty);
    final DryRunSession again = dryRun(greeter, DryRunInputs.start('click').withTrigger('click'));
    expect(speech(again), <String>['Welcome, Player! Nice to meet you.', 'Good to see you again, Player!']);
    expect(again.runs, hasLength(2));
    expect(dryRun(greeter, DryRunInputs.start('click').withTrigger('click').withoutLast()).runs, hasLength(1));
  });

  test('should name a sound plainly for Simple and by its key otherwise', () {
    final JsonMap bell = <String, Object?>{
      'entry': 'main',
      'routines': <String, Object?>{
        'main': <String, Object?>{'type': 'action', 'capability': 'animus:play-sound', 'arguments': <String, Object?>{'sound': 'minecraft:block.note_block.chime'}},
      },
    };
    String said({required bool plain}) => dryRun(bell, DryRunInputs.start(null), plain: plain).runs.single.steps.single.text;
    expect(said(plain: true), 'Plays sound note block chime.');
    expect(said(plain: false), 'Plays sound minecraft:block.note_block.chime.');
  });

  test('should word the steps plainly for Simple', () {
    final JsonMap greeter = behaviorFromTemplate(BehaviorTemplate.greeter, 'a:g');
    final List<String> engine = <String>[for (final DryRunStep step in dryRun(greeter, DryRunInputs.start('click')).runs.single.steps) step.text];
    expect(engine.any((String text) => text.contains('so else runs')), isTrue, reason: 'Advanced keeps the engine words');
    final List<String> plain = <String>[for (final DryRunStep step in dryRun(greeter, DryRunInputs.start('click'), plain: true).runs.single.steps) step.text];
    expect(plain, contains('Checks flag met with this NPC is true: it is not set, so the No steps run.'));
    expect(plain.join(' '), isNot(contains('minecraft:')));
    expect(plain.join(' '), isNot(contains('(relationship)')));
    expect(plain.join(' '), isNot(matches(RegExp(r'\b(then|else) runs'))));
  });

  test('should open a prompt, then run the answer trigger the user picks', () {
    final JsonMap dialogue = behaviorFromTemplate(BehaviorTemplate.dialogue, 'a:d');
    final DryRunSession asked = dryRun(dialogue, DryRunInputs.start('click'));
    expect(asked.unanswered?.dialogue, 'topic');
    expect(asked.unanswered?.options.map((DryRunOption option) => option.id), <String>['news', 'directions', 'leave']);
    final DryRunSession answered = dryRun(dialogue, DryRunInputs.start('click').withAnswer('topic', 'news'));
    expect(answered.runs, hasLength(2));
    expect(answered.runs.last.title, 'Answer "Any news?" (topic-news)');
    expect(speech(answered), <String>['The harvest festival starts at &esunset&f.']);
    expect(answered.unanswered, isNull);
  });

  test('should say when no trigger answers a picked option', () {
    final JsonMap dialogue = behaviorFromTemplate(BehaviorTemplate.dialogue, 'a:d');
    dialogue['triggers'] = <Object?>[asList(dialogue['triggers']).first];
    final DryRunSession session = dryRun(dialogue, DryRunInputs.start('click').withAnswer('topic', 'leave'));
    expect(session.runs.last.steps.single.outcome, DryRunOutcome.problem);
  });

  test('should walk the patrol square with waits on a timer', () {
    final JsonMap patrol = behaviorFromTemplate(BehaviorTemplate.patrol, 'a:p', origin: (0, 64, 0));
    final DryRunSession session = dryRun(patrol, DryRunInputs.start('rounds'));
    final List<DryRunStep> steps = session.runs.single.steps;
    final List<(double, double, double)> targets = <(double, double, double)>[for (final DryRunStep step in steps) if (step.target != null) step.target!];
    expect(targets, hasLength(8));
    expect(targets.take(4), <(double, double, double)>[(8, 64, 0), (8, 64, 8), (0, 64, 8), (0, 64, 0)]);
    expect(steps.where((DryRunStep step) => step.text.startsWith('Waits 40 ticks (2s)')), hasLength(8));
    expect(dryRunStarts(patrol).first.carriesPlayer, isFalse);
  });

  test('should mark combat as server only and flip an assumed permission', () {
    final JsonMap guard = behaviorFromTemplate(BehaviorTemplate.guard, 'a:g');
    final DryRunSession hit = dryRun(guard, DryRunInputs.start('attacked'));
    expect(hit.serverOnly.single.text, 'Changes combat: enable. Server only; not run.');
    final DryRunSession member = dryRun(guard, DryRunInputs.start('challenge'));
    final DryRunStep assumed = member.runs.single.steps.firstWhere((DryRunStep step) => step.outcome == DryRunOutcome.assumed);
    expect(assumed.assumption, isTrue);
    expect(speech(member), <String>['Evening, citizen.']);
    final DryRunSession stranger = dryRun(guard, DryRunInputs.start('challenge').withAssumption(assumed.path, false));
    expect(speech(stranger), <String>['Stranger, state your business.']);
  });

  test('should follow quest stages through accept and turn-in', () {
    final JsonMap quest = behaviorFromTemplate(BehaviorTemplate.quest, 'a:q');
    final DryRunSession accepted = dryRun(quest, DryRunInputs.start('click').withAnswer('offer', 'accept'));
    expect(accepted.state['quest errand (player)'], 1);
    expect(speech(accepted).last, 'Wonderful. Come back when you have them.');
    final DryRunSession returned = dryRun(quest, DryRunInputs.start('click').withAnswer('offer', 'accept').withTrigger('click'));
    expect(speech(returned).last, '&aThank you!&f Here is something for your trouble.');
    expect(returned.state['quest errand (player)'], 2);
    final DryRunSession ignored = dryRun(quest, DryRunInputs.start('click').withAnswer('elsewhere', 'accept'));
    expect(ignored.runs, hasLength(1));
  });

  test('should name documents and modded ids plainly for Simple', () {
    final JsonMap steps = <String, Object?>{
      'entry': 'main',
      'routines': <String, Object?>{
        'main': <String, Object?>{'type': 'sequence', 'steps': <Object?>[
          <String, Object?>{'type': 'action', 'capability': 'animus:navigate', 'arguments': <String, Object?>{'route': 'town:loop'}},
          <String, Object?>{'type': 'action', 'capability': 'animus:play-animation', 'arguments': <String, Object?>{'animation': 'rigs:wave'}},
          <String, Object?>{'type': 'action', 'capability': 'animus:spawn-particle', 'arguments': <String, Object?>{'particle': 'mymod:sparks'}},
          <String, Object?>{'type': 'action', 'capability': 'animus:set-equipment', 'arguments': <String, Object?>{'item': 'mymod:ruby', 'slot': 'main_hand'}},
          <String, Object?>{'type': 'action', 'capability': 'animus:open-gui', 'arguments': <String, Object?>{'gui': 'shop:counter'}},
        ]},
      },
    };
    final String plain = <String>[for (final DryRunStep step in dryRun(steps, DryRunInputs.start(null), plain: true, names: (DocumentKind kind, String id) => 'the ${kind.wireName} ${id.split(':').last}').runs.single.steps) step.text].join(' ');
    expect(plain, allOf(contains('Follows route the route loop'), contains('Plays animation the animation wave'), contains('sparks (mymod) particles'), contains('Holds ruby'), contains('GUI the inventory counter')));
    expect(plain, isNot(matches(RegExp(r'\b[a-z0-9_.-]+:[a-z0-9_./-]+'))));
  });

  test('should list a shop as not run and a command with its text', () {
    final JsonMap shop = behaviorFromTemplate(BehaviorTemplate.shop, 'a:s', shop: 'town:market');
    expect(dryRun(shop, DryRunInputs.start('click')).serverOnly.single.text, startsWith('Would open shop town:market.'));
    expect(dryRun(shop, DryRunInputs.start('click'), plain: true).serverOnly.single.text, startsWith('Would open shop Market.'));
    final JsonMap command = <String, Object?>{
      'schemaVersion': 1,
      'revision': 1,
      'id': 'a:c',
      'entry': 'main',
      'routines': <String, Object?>{
        'main': <String, Object?>{'type': 'action', 'capability': 'animus:command', 'arguments': <String, Object?>{'command': 'give {player} bread'}},
      },
    };
    expect(dryRun(command, DryRunInputs.start(null)).serverOnly.single.text, 'Runs a server command: /give {player} bread. Server only; not run.');
  });

  test('should stop a recursive call and cap a long session', () {
    final JsonMap looping = <String, Object?>{
      'entry': 'main',
      'routines': <String, Object?>{
        'main': <String, Object?>{'type': 'call', 'routine': 'main'},
        'long': <String, Object?>{
          'type': 'repeat',
          'count': 128,
          'body': <String, Object?>{'type': 'repeat', 'count': 128, 'body': <String, Object?>{'type': 'wait', 'ticks': 1}},
        },
      },
      'triggers': <Object?>[<String, Object?>{'id': 'long', 'event': 'timer', 'intervalTicks': 20, 'routine': 'long'}],
    };
    expect(dryRun(looping, DryRunInputs.start(null)).runs.single.steps.single.outcome, DryRunOutcome.problem);
    final DryRunSession capped = dryRun(looping, DryRunInputs.start('long'));
    expect(capped.stepCount, dryRunStepLimit + 1);
    expect(capped.runs.single.steps.last.text, 'Stopped after $dryRunStepLimit steps.');
  });

  test('should dry-run every example pack behavior without problems', () {
    for (final MapEntry<String, String> file in starterPackFiles.entries) {
      if (!file.key.contains('/behaviors/')) continue;
      final JsonMap behavior = asMap(jsonDecode(file.value));
      for (final DryRunStart start in dryRunStarts(behavior)) {
        final DryRunSession session = dryRun(behavior, DryRunInputs.start(start.id));
        final List<DryRunStep> problems = <DryRunStep>[
          for (final DryRunRun run in session.runs)
            for (final DryRunStep step in run.steps)
              if (step.outcome == DryRunOutcome.problem) step,
        ];
        expect(problems, isEmpty, reason: '${file.key} ${start.label}');
      }
    }
  });
  test('should print text values without colour codes for Simple', () {
    final JsonMap behavior = <String, Object?>{
      'entry': 'main',
      'routines': <String, Object?>{
        'main': <String, Object?>{
          'type': 'action',
          'capability': 'animus:set-flag',
          'arguments': <String, Object?>{'name': 'hud', 'value': '&#5FD3FFClick an option above'},
        },
      },
    };
    final String plain = dryRun(behavior, DryRunInputs.start(null), plain: true).runs.single.steps.single.text;
    expect(plain, 'Sets flag hud to Click an option above.');
    expect(plain, isNot(contains('&#')));
    expect(dryRun(behavior, DryRunInputs.start(null)).runs.single.steps.single.text, contains('"&#5FD3FFClick an option above"'),
        reason: 'Advanced keeps the value as written');
  });

  test('should note only a server behavior the server list leaves out', () {
    AnimusDocument behavior({required bool server}) => AnimusDocument(
          kind: DocumentKind.behavior,
          id: 'town:bell',
          path: 'behaviors/town/bell.json',
          source: '{}',
          json: <String, Object?>{'id': 'town:bell', if (server) 'server': true},
          diagnostics: const <Diagnostic>[],
        );
    final List<AnimusDocument> listed = <AnimusDocument>[
      const AnimusDocument(
        kind: DocumentKind.serverBehaviors,
        id: DocumentKind.serverBehaviorsId,
        path: DocumentKind.serverBehaviorsPath,
        source: '{}',
        json: <String, Object?>{'schemaVersion': 1, 'behaviors': <Object?>['town:bell']},
        diagnostics: <Diagnostic>[],
      ),
    ];
    expect(testPaneNote(behavior(server: true), const <AnimusDocument>[]), 'Not running on the server yet');
    expect(testPaneNote(behavior(server: true), listed), isNull);
    expect(testPaneNote(behavior(server: false), listed), isNull);
    expect(testPaneNote(behavior(server: false), const <AnimusDocument>[]), isNull);
  });
}
