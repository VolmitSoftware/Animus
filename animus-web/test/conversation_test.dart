import 'dart:convert';

import 'package:animus_editor/logic/conversation/conversation_check.dart';
import 'package:animus_editor/logic/conversation/conversation_edit.dart';
import 'package:animus_editor/logic/conversation/conversation_json.dart';
import 'package:animus_editor/logic/conversation/conversation_model.dart';
import 'package:animus_editor/logic/conversation/conversation_play.dart';
import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/offline/new_documents.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

JsonMap _json(String source) => jsonDecode(source) as JsonMap;

Conversation _read(JsonMap behavior) => switch (readConversation(behavior)) {
      ConversationFits(:final Conversation conversation) => conversation,
      ConversationMisfit(:final String path, :final String reason) => throw StateError('$path: $reason'),
    };

/// Reads, edits and writes back, as the Conversation tab does.
JsonMap _edit(JsonMap behavior, void Function(Conversation conversation) edit) {
  final Conversation conversation = _read(behavior);
  edit(conversation);
  return writeConversation(conversation, behavior);
}

List<Diagnostic> _schemaErrors(String id, JsonMap behavior) => validateSource(
      DocumentLocation(DocumentKind.behavior, id),
      newDocumentPath(DocumentKind.behavior, id),
      prettyJson(behavior),
      SharedSchemas.embedded,
    ).diagnostics.where((Diagnostic diagnostic) => diagnostic.severity == DiagnosticSeverity.error).toList();

/// The ferry example of 07-behaviors.md, as written by hand.
final JsonMap _ferry = _json('''
{
  "schemaVersion": 1,
  "revision": 1,
  "id": "harbor:ferryman",
  "entry": "main",
  "routines": {
    "main": {"type": "sequence", "steps": [
      {"type": "action", "capability": "animus:look", "arguments": {"target": "player"}},
      {"type": "action", "capability": "animus:choices", "arguments": {
        "id": "ferry", "prompt": "Need a crossing?",
        "options": [{"id": "yes", "text": "Yes, please"}, {"id": "no", "text": "No, thanks"}]
      }}
    ]},
    "cross": {"type": "action", "capability": "animus:say", "arguments": {"text": "Climb aboard. We leave at dusk."}},
    "decline": {"type": "action", "capability": "animus:say", "arguments": {"text": "Suit yourself."}}
  },
  "triggers": [
    {"id": "click", "event": "interaction", "cooldownTicks": 40},
    {"id": "yes", "event": "dialogue_choice", "dialogue": "ferry", "choice": "yes", "routine": "cross"},
    {"id": "no", "event": "dialogue_choice", "dialogue": "ferry", "choice": "no", "routine": "decline"}
  ]
}
''');

void main() {
  group('reading hand-written behaviors', () {
    test('should read the ferry example as a question with two answers', () {
      final Conversation conversation = _read(_ferry);
      expect(conversation.entry, 'main');
      expect(conversation.start.kind, ConversationStartKind.click);
      final List<ConversationStep> main = conversation.blocks['main']!.steps;
      expect(main.first, isA<ConversationAction>());
      final ConversationChoices question = main[1] as ConversationChoices;
      expect(question.dialogue, 'ferry');
      expect(<String?>[for (final ConversationOption option in question.options) option.next], <String>['cross', 'decline']);
      expect(<String?>[for (final ConversationOption option in question.options) option.trigger], <String>['yes', 'no']);
      expect((conversation.blocks['cross']!.steps.single as ConversationLine).text, 'Climb aboard. We leave at dusk.');
    });

    test('should write a hand-written conversation back unchanged', () {
      expect(writeConversation(_read(_ferry), _ferry), _ferry);
      for (final BehaviorTemplate template in <BehaviorTemplate>[BehaviorTemplate.dialogue, BehaviorTemplate.quest, BehaviorTemplate.conversation]) {
        final JsonMap behavior = behaviorFromTemplate(template, 'town:${template.name}');
        expect(writeConversation(_read(behavior), behavior), behavior, reason: template.name);
      }
    });

    test('should fold a wait, a sound and an animation into the line they surround', () {
      final JsonMap behavior = _json('''
{"schemaVersion": 1, "revision": 1, "id": "a:b", "entry": "main", "routines": {"main": {"type": "sequence", "steps": [
  {"type": "wait", "ticks": 20},
  {"type": "action", "capability": "animus:say", "arguments": {"text": "Boo"}},
  {"type": "action", "capability": "animus:play-sound", "arguments": {"sound": "minecraft:entity.ghast.scream"}},
  {"type": "action", "capability": "animus:play-animation", "arguments": {"animation": "town:ghost/jump"}},
  {"type": "action", "capability": "animus:play-sound", "arguments": {"sound": "minecraft:block.bell.use", "volume": 2}}
]}}}''');
      final List<ConversationStep> steps = _read(behavior).blocks['main']!.steps;
      expect(steps, hasLength(2));
      final ConversationLine line = steps.first as ConversationLine;
      expect((line.delayTicks, line.sound, line.animation), (20, 'minecraft:entity.ghast.scream', 'town:ghost/jump'));
      expect(steps.last, isA<ConversationAction>(), reason: 'a sound with a volume stays its own action');
      expect(writeConversation(_read(behavior), behavior), behavior);
    });

    test('should read a sneak start and a walk-near start', () {
      final JsonMap sneak = _json('''
{"schemaVersion": 1, "revision": 1, "id": "a:b", "entry": "hello", "routines": {
  "sneak-start": {"type": "condition", "expression": "context.sneaking", "then": {"type": "call", "routine": "hello"}},
  "hello": {"type": "action", "capability": "animus:say", "arguments": {"text": "Psst."}}},
 "triggers": [{"id": "start", "event": "interaction", "routine": "sneak-start", "consume": "context.sneaking"}]}''');
      final Conversation read = _read(sneak);
      expect(read.start.kind, ConversationStartKind.sneakClick);
      expect(read.entry, 'hello');
      expect(read.blocks.keys, <String>['hello'], reason: 'the gate is not a block');
      final JsonMap migrated = writeConversation(read, sneak);
      expect(asList(migrated['triggers']).single, <String, Object?>{'id': 'start', 'event': 'interaction', 'action': 'sneak_interact', 'consume': 'true'});
      expect(asMap(migrated['routines']).keys, <String>['hello'], reason: 'the sneak action replaces the gate');
      expect(writeConversation(_read(migrated), migrated), migrated);

      final JsonMap drop = _json('''
{"schemaVersion": 1, "revision": 1, "id": "a:b", "entry": "hello", "routines": {"hello": {"type": "action", "capability": "animus:say", "arguments": {"text": "Dropped?"}}},
 "triggers": [{"id": "start", "event": "interaction", "action": "sneak_drop"}]}''');
      expect(_read(drop).start.kind, ConversationStartKind.sneakDrop);
      expect(writeConversation(_read(drop), drop), drop);

      final JsonMap near = _json('''
{"schemaVersion": 1, "revision": 1, "id": "a:b", "entry": "main", "routines": {"main": {"type": "action", "capability": "animus:say", "arguments": {"text": "Hi"}}},
 "triggers": [{"id": "near", "event": "proximity", "phase": "enter", "radius": 5, "cooldownTicks": 200}]}''');
      expect(_read(near).start.radius, 5);
      expect(writeConversation(_read(near), near)['triggers'], <Object?>[
        <String, Object?>{'id': 'near', 'event': 'proximity', 'cooldownTicks': 200, 'phase': 'enter', 'radius': 5},
      ]);
    });

    test('should name the first field that does not fit', () {
      ConversationMisfit misfit(String source) => readConversation(_json(source)) as ConversationMisfit;
      expect(misfit('{"entry": "main", "routines": {"main": {"type": "repeat", "count": 2, "body": {"type": "sequence", "steps": []}}}}').path, r'$.routines.main');
      expect(misfit('{"entry": "main", "routines": {"main": {"type": "sequence", "steps": []}}, "triggers": [{"id": "t", "event": "timer", "intervalTicks": 20}]}').path,
          r'$.triggers[0].event');
      expect(misfit('{"entry": "main", "routines": {"main": {"type": "sequence", "steps": []}}, "triggers": [{"id": "t", "event": "dialogue_choice", "dialogue": "x"}]}').path,
          r'$.triggers[0].choice');
      expect(misfit('{"server": true, "entry": "main", "routines": {"main": {"type": "sequence", "steps": []}}}').path, r'$.server');
      expect(misfit('{"entry": "main", "routines": {"main": {"type": "sequence", "steps": [{"type": "call", "routine": "main"}, {"type": "return"}]}}}').path,
          r'$.routines.main.steps[0]');
      expect(misfit('{"entry": "main", "routines": {"main": {"type": "action", "capability": "animus:say", "arguments": {"text": null}, "bindings": {"/text": {"variable": "x"}}}}}').path,
          r'$.routines.main.bindings');
      expect(misfit('{"entry": "main", "routines": {"main": {"type": "sequence", "steps": []}}, "triggers": [{"id": "t", "event": "dialogue_choice", "dialogue": "x", "choice": "y"}]}').reason,
          contains('no line asks'));
    });
  });

  group('writing', () {
    test('should keep a written conversation stable through read and write', () {
      final JsonMap made = newConversationBehavior('town:talk');
      final JsonMap once = writeConversation(_read(made), made);
      expect(once, made);
      final JsonMap edited = _edit(made, (Conversation conversation) {
        insertStep(conversation, const StepAt('main', <Object>[1]), ConversationLine('Nice weather.', delayTicks: 10, sound: 'minecraft:entity.villager.yes'));
      });
      expect(writeConversation(_read(edited), edited), edited);
      expect(jsonEncode(edited), contains('"ticks":10'));
    });

    test('should add an action step that is valid as added, with a flag name of its own', () {
      final JsonMap edited = _edit(newConversationBehavior('town:talk'), (Conversation conversation) {
        insertStep(conversation, const StepAt('main', <Object>[1]), newAction(conversation));
        insertStep(conversation, const StepAt('main', <Object>[1]), newAction(conversation));
      });
      expect(_schemaErrors('town:talk', edited), isEmpty);
      final Conversation back = _read(edited);
      expect(checkConversation(back).where((ConversationIssue issue) => issue.error), isEmpty);
      final List<String> names = <String>[
        for (final ConversationStep step in back.blocks['main']!.steps)
          if (step is ConversationAction && step.capability == 'animus:set-flag') asString(step.arguments['name'])!,
      ];
      expect(names.toSet(), hasLength(names.length), reason: 'each new action names a flag no other step sets');
    });

    test('should write placeholders the server does not fill as template holes', () {
      final JsonMap edited = _edit(newConversationBehavior('town:talk'), (Conversation conversation) {
        (conversation.blocks['main']!.steps.first as ConversationLine).text = 'I am {npc.name}, {player_name}.';
        (conversation.blocks['main']!.steps[1] as ConversationChoices).prompt = 'Well, {player_name}?';
      });
      final JsonList steps = asList(asMap(asMap(edited['routines'])['main'])['steps']);
      expect(asMap(steps[0])['templates'], <String, Object?>{'/text': 'I am {{ npc.name }}, {player_name}.'});
      expect(asMap(asMap(steps[0])['arguments'])['text'], isNull);
      expect(asMap(steps[1])['templates'], <String, Object?>{'/prompt': 'Well, {{ player.name }}?'});
      final Conversation back = _read(edited);
      expect((back.blocks['main']!.steps.first as ConversationLine).text, 'I am {npc.name}, {player_name}.');
      expect((back.blocks['main']!.steps[1] as ConversationChoices).prompt, 'Well, {player_name}?');
      expect(_schemaErrors('town:talk', edited), isEmpty);
    });

    test('should make a valid behavior from every start', () {
      for (final ConversationStartKind kind in ConversationStartKind.values) {
        final JsonMap behavior = _edit(newConversationBehavior('town:talk'), (Conversation conversation) => setStart(conversation, kind, radius: 7));
        expect(_schemaErrors('town:talk', behavior), isEmpty, reason: kind.name);
        expect(_read(behavior).start.kind, kind);
        expect(writeConversation(_read(behavior), behavior), behavior, reason: kind.name);
      }
    });

    test('should keep the start trigger id and cooldown when the start changes', () {
      final JsonMap behavior = _edit(newConversationBehavior('town:talk'), (Conversation conversation) => setStart(conversation, ConversationStartKind.sneakClick));
      final JsonMap start = asMap(asList(behavior['triggers']).first);
      expect((start['id'], start['cooldownTicks'], start['action'], start['consume']), ('start', 40, 'sneak_interact', 'true'));
      final JsonMap back = _edit(behavior, (Conversation conversation) => setStart(conversation, ConversationStartKind.click));
      expect(asList(back['triggers']).first, <String, Object?>{'id': 'start', 'event': 'interaction', 'cooldownTicks': 40});
      expect(asMap(back['routines']).containsKey('sneak-start'), isFalse);
    });
  });

  group('ids', () {
    test('should name a follow-up after its answer and keep the ids it made', () {
      final JsonMap behavior = newConversationBehavior('town:talk');
      final JsonList triggers = asList(behavior['triggers']);
      expect(<Object?>[for (final Object? trigger in triggers) asMap(trigger)['id']], <String>['start', 'ask-just-looking', 'ask-goodbye']);
      expect(asMap(behavior['routines']).keys, <String>['main', 'just-looking', 'goodbye']);
      final JsonMap renamed = _edit(behavior, (Conversation conversation) {
        (conversation.blocks['main']!.steps[1] as ConversationChoices).options.first.text = 'Only browsing';
      });
      expect(asMap(renamed['routines']).keys, <String>['main', 'just-looking', 'goodbye'], reason: 'text edits keep ids');
      expect(asMap(asList(renamed['triggers'])[1])['choice'], 'just-looking');
    });

    test('should make fresh ids for duplicates and copy their follow-ups', () {
      final JsonMap duplicated = _edit(newConversationBehavior('town:talk'), (Conversation conversation) {
        duplicateOption(conversation, const StepAt('main', <Object>[1]), 0);
        duplicateStep(conversation, const StepAt('main', <Object>[1]));
      });
      final Conversation read = _read(duplicated);
      final ConversationChoices first = read.blocks['main']!.steps[1] as ConversationChoices;
      final ConversationChoices copy = read.blocks['main']!.steps[2] as ConversationChoices;
      expect(first.options.map((ConversationOption option) => option.id), <String>['just-looking', 'just-looking-2', 'goodbye']);
      expect(first.options[1].next, 'just-looking-2');
      expect(copy.dialogue, 'ask-2');
      expect(copy.options.map((ConversationOption option) => option.next), <String>['just-looking-3', 'just-looking-4', 'goodbye-2']);
      final List<Object?> ids = <Object?>[for (final Object? trigger in asList(duplicated['triggers'])) asMap(trigger)['id']];
      expect(ids.toSet(), hasLength(ids.length), reason: 'trigger ids stay unique');
      expect(_schemaErrors('town:talk', duplicated), isEmpty);
      expect(checkConversation(read).where((ConversationIssue issue) => issue.error), isEmpty);
    });

    test('should turn a line typed with bracketed answers into choices', () {
      final (String prompt, List<String> options) = inlineChoices('Need directions? [Yes] [No thanks]')!;
      expect(prompt, 'Need directions?');
      expect(options, <String>['Yes', 'No thanks']);
      expect(inlineChoices('Press [E] to talk'), isNull);
      final JsonMap behavior = _edit(newConversationBehavior('town:talk'), (Conversation conversation) {
        (conversation.blocks['goodbye']!.steps.first as ConversationLine).text = 'Sure? [Yes] [No] [Maybe]';
        expect(lineToChoices(conversation, const StepAt('goodbye', <Object>[0])), isTrue);
        addFollowUp(conversation, const StepAt('goodbye', <Object>[0]), 0);
      });
      final ConversationChoices choices = _read(behavior).blocks['goodbye']!.steps.single as ConversationChoices;
      expect((choices.dialogue, choices.prompt), ('ask-2', 'Sure?'));
      expect(choices.options.map((ConversationOption option) => option.id), <String>['yes', 'no', 'maybe']);
      expect(choices.options.first.next, 'yes');
    });
  });

  group('checks', () {
    test('should report unreachable blocks, missing targets, too many answers and long lines', () {
      final Conversation conversation = _read(newConversationBehavior('town:talk'));
      final ConversationChoices question = conversation.blocks['main']!.steps[1] as ConversationChoices;
      question.options.first.next = 'gone';
      for (int index = 0; index < 8; index++) {
        question.options.add(newOption(question, 'Option $index'));
      }
      (conversation.blocks['main']!.steps.first as ConversationLine).text = 'x' * 1100;
      conversation.blocks['main']!.steps.add(ConversationBranch(capability: 'animus:flag-state', arguments: <String, Object?>{'name': 'met'}));
      final List<ConversationIssue> issues = checkConversation(conversation, names: knownNames(<Object?>[]));
      List<String> messages(StepAt at, {int? option}) => <String>[for (final ConversationIssue issue in issuesAt(issues, at, option: option)) issue.message];
      expect(issues.where((ConversationIssue issue) => issue.block == 'just-looking').single.message, 'Nothing leads here.');
      expect(messages(const StepAt('main', <Object>[1]), option: 0), contains('Leads to gone, which is gone.'));
      expect(messages(const StepAt('main', <Object>[1])), contains('10 answers. A question takes 8.'));
      expect(messages(const StepAt('main', <Object>[0])), contains('1100 characters. A line takes 1024.'));
      expect(messages(const StepAt('main', <Object>[2])), contains('No behavior sets flag met.'));
      final List<ConversationIssue> known = checkConversation(conversation, names: knownNames(<Object?>[_edit(_ferry, (Conversation c) {
        c.blocks['cross']!.steps.add(ConversationAction('animus:set-flag', arguments: <String, Object?>{'name': 'met'}));
      })]));
      expect(issuesAt(known, const StepAt('main', <Object>[2])), isEmpty);
    });
  });

  group('preview', () {
    test('should play to the question and follow the branch the author picked', () {
      final Conversation conversation = _read(newConversationBehavior('town:talk'));
      conversation.blocks['main']!.steps.insert(1, ConversationBranch(
        capability: 'animus:flag-state',
        arguments: <String, Object?>{'name': 'met'},
        then: StepList(<ConversationStep>[ConversationLine('Back again, {npc.name} remembers.')]),
        otherwise: StepList(<ConversationStep>[ConversationAction('animus:set-flag', arguments: <String, Object?>{'name': 'met'})]),
      ));
      List<PlayEntry> play(bool met) => playFrom(conversation, 'main', branch: (StepAt _) => met, npc: 'Ada');
      final List<PlayEntry> yes = play(true);
      expect((yes[0] as PlayLine).text, 'Hello, Steve.');
      expect((yes[1] as PlayCondition).label, 'flag met');
      expect((yes[2] as PlayLine).text, 'Back again, Ada remembers.');
      expect(yes[3], isA<PlayQuestion>());
      expect((play(false)[2] as PlayEffect).text, 'Sets flag met');
      final List<PlayEntry> answer = playFrom(conversation, 'goodbye', branch: (StepAt _) => true);
      expect((answer.single as PlayLine).text, 'Safe travels.');
    });

    test('should stop a loop with no question', () {
      final Conversation conversation = _read(newConversationBehavior('town:talk'));
      conversation.blocks['goodbye']!.steps.add(ConversationGoto('goodbye'));
      expect(playFrom(conversation, 'goodbye', branch: (StepAt _) => true).last, isA<PlayStop>());
    });
  });
}
