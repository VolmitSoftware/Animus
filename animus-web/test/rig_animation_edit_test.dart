import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:animus_editor/logic/bbmodel_import.dart';
import 'package:animus_editor/logic/rig_edit.dart';
import 'package:animus_editor/logic/rig_sampler.dart';
import 'package:animus_editor/logic/rig_validation.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

JsonMap golem() => cloneJson(readFixture('animation/rigs/golem.json')! as JsonMap);

JsonMap walk() => cloneJson(readFixture('animation/clips/golem-walk.json')! as JsonMap);

void expectValidClip(JsonMap clip) {
  expect(SharedSchemas.embedded.validate(DocumentKind.animation, clip), isEmpty);
  expect(validateAnimationStructure(clip, golem()), isEmpty);
}

void main() {
  group('setKeyframe', () {
    test('should key the selected track when a bone has two tracks on one channel', () {
      // walk: track 2 is arm rotation replace, track 3 is arm rotation add.
      final JsonMap clip = setKeyframe(walk(), 'arm', TrackTarget.rotation, 10, <Object?>[0, 0, 7], track: 3);
      expect(keyframesOf(tracksOf(clip)[3]).map((JsonMap key) => key['tick']), <Object?>[0, 10, 20]);
      expect(keyframesOf(tracksOf(clip)[3])[1]['value'], <Object?>[0, 0, 7]);
      expect(keyframesOf(tracksOf(clip)[2]), keyframesOf(tracksOf(walk())[2]), reason: 'the replace track is untouched');
      expectValidClip(clip);
    });

    test('should fall back to the first matching track and refuse a track of another channel', () {
      final JsonMap clip = setKeyframe(walk(), 'arm', TrackTarget.rotation, 5, <Object?>[1, 2, 3]);
      expect(keyframesOf(tracksOf(clip)[2]).map((JsonMap key) => key['tick']), <Object?>[0, 5, 10, 20]);
      expect(() => setKeyframe(walk(), 'arm', TrackTarget.rotation, 5, <Object?>[1, 2, 3], track: 0), throwsA(isA<RigEditException>()));
    });

    test('should create a new track with the chosen blend, and visible tracks replace', () {
      final JsonMap clip = setKeyframe(walk(), 'head', TrackTarget.scale, 0, <Object?>[1, 1, 1], blend: Blend.multiply);
      expect(tracksOf(clip).last['blend'], 'multiply');
      final JsonMap hidden = setKeyframe(walk(), 'head', TrackTarget.visible, 0, false, blend: Blend.add);
      expect(tracksOf(hidden).last['blend'], 'replace');
    });

    test('should key the channel-relative value of an add track, not the combined pose', () {
      // At tick 10 the arm's two rotation tracks combine; the add track alone
      // holds its own value, which is what a new key on it should store.
      final JsonMap clip = walk();
      final Object? own = trackValueAt(tracksOf(clip)[3], 10);
      final Map<String, BonePose> pose = sampleRig(
        Rig.fromJson(golem()),
        <Layer>[Layer(Clip.fromJson(clip))],
        0,
        10,
      );
      expect(own, isNot(pose['arm']!.rotation));
      expect(neutralValue(Blend.add, TrackTarget.rotation, <double>[5, 5, 5]), <double>[0, 0, 0]);
      expect(neutralValue(Blend.multiply, TrackTarget.scale, <double>[5, 5, 5]), <double>[1, 1, 1]);
      expect(neutralValue(Blend.replace, TrackTarget.rotation, <double>[5, 5, 5]), <double>[5, 5, 5]);
    });

    test('should keep a bone own visibility apart from a hidden ancestor', () {
      final JsonMap clip = setKeyframe(walk(), 'torso', TrackTarget.visible, 0, false);
      final Map<String, BonePose> pose = sampleRig(Rig.fromJson(golem()), <Layer>[Layer(Clip.fromJson(clip))], 0, 0);
      expect(pose['head']!.visible, isFalse);
      expect(pose['head']!.ownVisible, isTrue);
    });
  });

  group('tracks', () {
    test('should change blend on an existing track and refuse it on visible tracks', () {
      expect(tracksOf(setTrackBlend(walk(), 0, Blend.add))[0]['blend'], 'add');
      final JsonMap hidden = setKeyframe(walk(), 'head', TrackTarget.visible, 0, true);
      expect(() => setTrackBlend(hidden, tracksOf(hidden).length - 1, Blend.add), throwsA(isA<RigEditException>()));
    });

    test('should add, reorder and delete tracks', () {
      final JsonMap added = addTrack(walk(), 'head', TrackTarget.scale, Blend.multiply, 5, <Object?>[1, 1, 1]);
      expect(tracksOf(added).length, 5);
      expect(tracksOf(added).last, <String, Object?>{
        'bone': 'head',
        'target': 'scale',
        'blend': 'multiply',
        'keyframes': <Object?>[
          <String, Object?>{'tick': 5, 'value': <Object?>[1, 1, 1]},
        ],
      });
      final JsonMap moved = moveTrack(added, 4, 0);
      expect(tracksOf(moved).first['target'], 'scale');
      expect(tracksOf(removeTrack(moved, 0)).length, 4);
      expectValidClip(moved);
    });

    test('should copy a keyframe to the playhead on its own track', () {
      final JsonMap clip = copyKeyframe(walk(), 0, 1, 15);
      final List<JsonMap> keys = keyframesOf(tracksOf(clip)[0]);
      expect(keys.map((JsonMap key) => key['tick']), <Object?>[0, 10, 15, 20]);
      expect(keys[2]['value'], keys[1]['value']);
      expect(keys[2]['easing'], keys[1]['easing']);
    });
  });

  group('duration', () {
    test('should count and refuse keys past a shorter end, then trim them', () {
      expect(pastEnd(walk(), 12), (keyframes: 3, events: 0));
      expect(pastEnd(walk(), 8), (keyframes: 6, events: 1));
      expect(() => setDuration(walk(), 12), throwsA(isA<RigEditException>()));
      final JsonMap trimmed = setDuration(walk(), 8, trim: true);
      expect(trimmed['durationTicks'], 8);
      expect(trimmed.containsKey('events'), isFalse);
      for (final JsonMap track in tracksOf(trimmed)) {
        expect(keyframesOf(track).every((JsonMap key) => (key['tick']! as num) <= 8), isTrue);
      }
      expectValidClip(trimmed);
      expect(setDuration(walk(), 40)['durationTicks'], 40);
      expect(() => setDuration(walk(), 0), throwsA(isA<RigEditException>()));
    });
  });

  group('events', () {
    test('should add events in tick order and edit and remove them', () {
      final JsonMap one = addEvent(walk(), 4, 'animus:spawn-particle', <String, Object?>{'particle': 'minecraft:flame', 'count': 3});
      expect(eventsOf(one).map((JsonMap event) => event['tick']), <Object?>[4, 10]);
      expectValidClip(one);
      final JsonMap edited = updateEvent(one, 0, 15, 'animus:play-sound', <String, Object?>{'sound': 'minecraft:block.anvil.land'});
      expect(eventsOf(edited).map((JsonMap event) => event['tick']), <Object?>[10, 15]);
      expect(eventsOf(edited).last['arguments'], <String, Object?>{'sound': 'minecraft:block.anvil.land'});
      final JsonMap none = removeEvent(removeEvent(edited, 0), 0);
      expect(none.containsKey('events'), isFalse);
    });

    test('should refuse ticks past the end and capabilities animations cannot run', () {
      expect(() => addEvent(walk(), 21, 'animus:play-sound', <String, Object?>{'sound': 'a.b'}), throwsA(isA<RigEditException>()));
      expect(() => addEvent(walk(), 1, 'animus:say', <String, Object?>{}), throwsA(isA<RigEditException>()));
      expect(requiredArgument('animus:spawn-particle'), 'particle');
    });
  });

  group('animation names', () {
    test('should add, rename in place and remove names', () {
      JsonMap rig = setAnimationName(golem(), 'wave', 'town:golem/wave');
      expect(animationNames(rig).keys, <String>['idle', 'walk', 'wave']);
      rig = renameAnimationName(rig, 'idle', 'rest');
      expect(animationNames(rig).keys, <String>['rest', 'walk', 'wave']);
      expect(() => renameAnimationName(rig, 'rest', 'walk'), throwsA(isA<RigEditException>()));
      expect(() => setAnimationName(rig, 'Bad Name', 'town:golem/x'), throwsA(isA<RigEditException>()));
      rig = removeAnimationName(removeAnimationName(removeAnimationName(rig, 'rest'), 'walk'), 'wave');
      expect(rig.containsKey('animations'), isFalse);
      expect(freeAnimationName(golem(), 'walk'), 'walk-2');
    });

    test('should warn about names whose animation is missing or animates another rig', () {
      final JsonMap rig = setAnimationName(golem(), 'wave', 'town:other/wave');
      final List<Diagnostic> problems = validateRigAnimations(rig, <String, Object?>{
        'town:golem/walk': walk(),
        'town:other/wave': <String, Object?>{'rig': 'town:other'},
      });
      expect(problems.map((Diagnostic d) => '${d.path} ${d.code}'), <String>[
        r'$.animations.idle rig-animation-missing',
        r'$.animations.wave rig-animation-rig',
      ]);
      expect(problems.every((Diagnostic d) => d.severity == DiagnosticSeverity.warning), isTrue);
    });
  });

  group('bezier', () {
    test('should accept exactly the schema pattern', () {
      for (final String good in <String>['bezier(0.25,0.1,0.25,1)', 'bezier( 0 , -2 , 1.0 , 3.5 )', 'bezier(1,0,0,1)']) {
        expect(Easing.parse(good).name, 'bezier', reason: good);
      }
      for (final String bad in <String>['bezier(.25,0,0.5,1)', 'bezier(0.25,+1,0.5,1)', 'bezier(0.25,1e2,0.5,1)', 'bezier(1.5,0,0.5,1)', 'bezier(0,0,1)', 'bezier(0, 0, 1, 1']) {
        expect(() => Easing.parse(bad), throwsA(predicate((Object? error) => error is FormatException && error.message == Easing.bezierHint)), reason: bad);
      }
    });

    test('should match the schema regex on every case', () {
      final RegExp schema = RegExp(r'^bezier\(\s*(0(\.\d+)?|1(\.0+)?)\s*,\s*-?\d+(\.\d+)?\s*,\s*(0(\.\d+)?|1(\.0+)?)\s*,\s*-?\d+(\.\d+)?\s*\)$');
      for (final String text in <String>['bezier(0.25,0.1,0.25,1)', 'bezier(1.00,0,0,1)', 'bezier(1.01,0,0,1)', 'bezier(0.,0,0,1)', 'bezier(0,-0.5,0,1)']) {
        bool parses;
        try {
          Easing.parse(text);
          parses = true;
        } on FormatException {
          parses = false;
        }
        expect(parses, schema.hasMatch(text), reason: text);
      }
    });
  });

  group('bbmodel warnings', () {
    JsonMap robot() => jsonDecode(File('test/fixtures/bbmodel/robot.bbmodel').readAsStringSync()) as JsonMap;
    Uint8List bytes(Object json) => Uint8List.fromList(utf8.encode(jsonEncode(json)));

    test('should keep vanilla sounds as events and count what it leaves out', () {
      final JsonMap model = robot();
      final JsonMap animators = ((model['animations']! as List<Object?>).first! as JsonMap)['animators']! as JsonMap;
      animators['effects'] = <String, Object?>{
        'name': 'Effects',
        'type': 'effect',
        'keyframes': <Object?>[
          <String, Object?>{'channel': 'sound', 'time': 0.5, 'data_points': <Object?>[<String, Object?>{'effect': 'entity.iron_golem.step', 'file': ''}]},
          <String, Object?>{'channel': 'sound', 'time': 0.25, 'data_points': <Object?>[<String, Object?>{'effect': 'sounds/clank.ogg'}]},
          <String, Object?>{'channel': 'particle', 'time': 0.1, 'data_points': <Object?>[<String, Object?>{'effect': 'minecraft:basic_flame_particle'}]},
          <String, Object?>{'channel': 'particle', 'time': 0.2, 'data_points': <Object?>[<String, Object?>{'effect': 'smoke'}]},
          <String, Object?>{'channel': 'timeline', 'time': 0, 'data_points': <Object?>[<String, Object?>{'script': 'v.x = 1;'}]},
        ],
      };
      final BbmodelImport imported = importBbmodel(bytes(model), rigId: 'town:robot');
      final JsonMap clip = imported.animations.first;
      expect(eventsOf(clip), <JsonMap>[
        <String, Object?>{'tick': 10, 'capability': 'animus:play-sound', 'arguments': <String, Object?>{'sound': 'minecraft:entity.iron_golem.step'}},
      ]);
      final String warnings = imported.warnings.join('\n');
      expect(warnings, contains('1 keyframe with vanilla sounds became play-sound events.'));
      expect(warnings, contains('1 keyframe play sound files'));
      expect(warnings, contains('2 keyframes spawn Bedrock particles'));
      expect(warnings, contains('1 keyframe run timeline scripts'));
      expect(SharedSchemas.embedded.validate(DocumentKind.animation, clip), isEmpty);
    });

    test('should keep both values of a split key and count curve approximations', () {
      final JsonMap model = robot();
      final JsonMap head = (((model['animations']! as List<Object?>).first! as JsonMap)['animators']! as JsonMap)['g-head']! as JsonMap;
      final List<Object?> keys = head['keyframes']! as List<Object?>;
      (keys[1]! as JsonMap)['data_points'] = <Object?>[
        <String, Object?>{'x': 10, 'y': 0, 'z': 0},
        <String, Object?>{'x': 30, 'y': 0, 'z': 0},
      ];
      (keys[2]! as JsonMap)['interpolation'] = 'bezier';
      final BbmodelImport imported = importBbmodel(bytes(model), rigId: 'town:robot');
      final JsonMap rotation = tracksOf(imported.animations.first).firstWhere((JsonMap track) => track['bone'] == 'head');
      final List<JsonMap> frames = keyframesOf(rotation);
      expect(frames.map((JsonMap key) => key['tick']), <Object?>[0, 10, 10.001, 20]);
      expect(frames[1]['value'], <Object?>[10, 0, 0]);
      expect(frames[2]['value'], <Object?>[30, 0, 0]);
      expect(frames[2]['easing'], 'ease_in_out');
      final String warnings = imported.warnings.join('\n');
      expect(warnings, contains('1 keyframe use Blockbench catmullrom curves'));
      expect(warnings, contains('1 keyframe use Blockbench bezier curves'));
      expect(warnings, contains('1 keyframe jump between two values'));
      expect(validateAnimationStructure(imported.animations.first, imported.rig), isEmpty);
    });
  });
}
