import 'dart:convert';

import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/inspect/behavior_outline.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

JsonMap _json(String source) => jsonDecode(source) as JsonMap;

/// "depth:text" per line of [part].
List<String> _lines(OutlinePart part) => <String>[for (final OutlineLine line in part.lines) '${line.depth}:${line.text}'];

void main() {
  group('behavior outline', () {
    test('should say what starts each part and its steps in words, branches as Yes and No', () {
      final List<OutlinePart> parts = behaviorOutline(_json('''
{"id": "town:guard", "entry": "main", "routines": {
  "main": {"type": "sequence", "steps": [
    {"type": "action", "capability": "animus:look", "arguments": {"target": "player"}},
    {"type": "condition", "capability": "animus:flag-state", "arguments": {"name": "met"},
     "then": {"type": "action", "capability": "animus:say", "arguments": {"text": "&eAgain?"}},
     "else": {"type": "sequence", "steps": [
       {"type": "action", "capability": "animus:say", "arguments": {"text": "Halt."}},
       {"type": "call", "routine": "warn-off"}]}}]},
  "warn-off": {"type": "repeat", "count": 2, "body": {"type": "wait", "ticks": 20}}},
 "triggers": [
  {"id": "click", "event": "interaction"},
  {"id": "near", "event": "proximity", "phase": "enter", "radius": 4},
  {"id": "tick", "event": "timer", "intervalTicks": 200, "routine": "warn-off"}]}'''));
      expect(parts, hasLength(2));
      expect(parts.first.title, 'What it does');
      expect(parts.first.runs, 'On click, Player nearby');
      expect(_lines(parts.first), <String>[
        '0:Look at the player',
        '0:If flag met is true',
        '1:Yes:',
        '2:Say: Again?',
        '1:No:',
        '2:Say: Halt.',
        '2:Run Warn off',
      ]);
      expect(parts.last.title, 'Warn off');
      expect(parts.last.runs, 'Every 10 seconds');
      expect(_lines(parts.last), <String>['0:Repeat 2 times', '1:Wait 20 ticks (1s)']);
    });

    test('should say a part an answer runs follows an answer, and an empty part has no steps', () {
      final List<OutlinePart> parts = behaviorOutline(_json('''
{"entry": "main", "routines": {
  "main": {"type": "action", "capability": "animus:choices", "arguments": {"id": "q", "prompt": "Room?", "options": [{"id": "yes", "text": "Yes"}]}},
  "room": {"type": "sequence", "steps": []}},
 "triggers": [{"id": "yes", "event": "dialogue_choice", "dialogue": "q", "choice": "yes", "routine": "room"}]}'''));
      expect(parts.first.runs, 'Runs when called');
      expect(_lines(parts.first), <String>['0:Ask: Room?']);
      expect(parts.last.runs, 'After an answer');
      expect(parts.last.lines, isEmpty);
    });

    test('should put a condition without No straight under it and stay in words for unknown nodes', () {
      final List<OutlinePart> parts = behaviorOutline(_json('''
{"entry": "main", "routines": {"main": {"type": "sequence", "steps": [
  {"type": "condition", "expression": "player.level > 3", "then": {"type": "action", "capability": "animus:say", "arguments": {"text": "Hi"}}},
  {"type": "parallel", "branches": [{"type": "wait", "ticks": 1}, {"type": "glow"}]}]}}}'''));
      expect(_lines(parts.single), <String>[
        '0:If player.level > 3',
        '1:Say: Hi',
        '0:At the same time:',
        '1:Wait 1 ticks (0.1s)',
        '1:Unknown step glow, kept as written',
      ]);
    });

    test('should say a server behavior runs with no NPC', () {
      final List<OutlinePart> parts = behaviorOutline(_json('''
{"server": true, "entry": "main", "routines": {"main": {"type": "action", "capability": "animus:message", "arguments": {"text": "Welcome"}}},
 "triggers": [{"id": "join", "event": "player_join"}]}'''));
      expect(parts.single.runs, 'Player joins');
      expect(_lines(parts.single), <String>['0:Message: Welcome']);
    });

    test('should title a part named after its event by what starts it, and say it once', () {
      final List<OutlinePart> parts = behaviorOutline(_json('''
{"server": true, "entry": "on-delta-time-secondly", "routines": {
  "on-delta-time-secondly": {"type": "sequence", "steps": []},
  "on-delta-time-secondly-2": {"type": "sequence", "steps": []},
  "on-delta-time-minutely": {"type": "sequence", "steps": []}},
 "triggers": [
  {"id": "on-delta-time-secondly", "event": "timer", "intervalTicks": 20, "routine": "on-delta-time-secondly"},
  {"id": "on-delta-time-secondly-2", "event": "timer", "intervalTicks": 40, "routine": "on-delta-time-secondly-2"},
  {"id": "on-delta-time-minutely", "event": "timer", "intervalTicks": 1200, "routine": "on-delta-time-minutely"}]}'''));
      expect(<String>[for (final OutlinePart part in parts) part.title], <String>['Every second', 'Every 2 seconds', 'Every minute'],
          reason: 'parts started by events read alike, the first one too');
      expect(<String?>[for (final OutlinePart part in parts) part.runs], <String?>[null, null, null]);
    });

    test('should name whose flag it is and read Minecraft ids without their namespace', () {
      final List<OutlinePart> parts = behaviorOutline(_json('''
{"entry": "main", "routines": {"main": {"type": "sequence", "steps": [
  {"type": "condition", "capability": "animus:flag-state", "arguments": {"name": "met", "scope": "relationship"},
   "then": {"type": "action", "capability": "animus:play-sound", "arguments": {"sound": "minecraft:entity.villager.ambient"}}},
  {"type": "action", "capability": "animus:spawn-particle", "arguments": {"particle": "minecraft:happy_villager"}},
  {"type": "action", "capability": "animus:give-item", "arguments": {"item": "minecraft:golden_apple", "amount": 2}},
  {"type": "action", "capability": "animus:set-flag", "arguments": {"name": "open", "scope": "global"}}]}}}'''));
      expect(_lines(parts.single), <String>[
        '0:If flag met with this NPC is true',
        '1:Play sound villager ambient',
        '0:Show particle happy villager',
        '0:Give 2 golden apple',
        '0:Set flag open for everyone to true',
      ]);
    });

    test('should name the documents a step opens or plays, and modded ids, never by id', () {
      const String steps = '''
{"entry": "main", "routines": {"main": {"type": "sequence", "steps": [
  {"type": "action", "capability": "animus:open-shop", "arguments": {"shop": "town:bakery"}},
  {"type": "action", "capability": "animus:open-gui", "arguments": {"gui": "shop:counter"}},
  {"type": "action", "capability": "animus:navigate", "arguments": {"route": "town:loop"}},
  {"type": "action", "capability": "animus:play-animation", "arguments": {"animation": "rigs:wave"}},
  {"type": "action", "capability": "animus:give-item", "arguments": {"item": "mymod:ruby"}},
  {"type": "action", "capability": "animus:spawn-particle", "arguments": {"particle": "mymod:sparks"}}]}}}''';
      expect(_lines(behaviorOutline(_json(steps)).single), <String>[
        '0:Open shop Bakery',
        '0:Open GUI Counter',
        '0:Walk route Loop',
        '0:Play animation Wave',
        '0:Give ruby',
        '0:Show particle sparks (mymod)',
      ]);
      String named(DocumentKind kind, String id) => '${kind.wireName} named for $id';
      expect(_lines(behaviorOutline(_json(steps), names: named).single).take(2), <String>['0:Open shop shop named for town:bakery', '0:Open GUI inventory named for shop:counter']);
    });
  });
}
