import 'package:animus_editor/logic/rig_sampler.dart';
import 'package:animus_editor/logic/rig_validation.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

void expectClose(List<double> actual, List<Object?> expected, double tolerance, String reason) {
  expect(actual, hasLength(expected.length), reason: reason);
  for (int index = 0; index < actual.length; index++) {
    final double want = (expected[index]! as num).toDouble();
    expect((actual[index] - want).abs(), lessThanOrEqualTo(tolerance), reason: '$reason[$index]: ${actual[index]} vs $want');
  }
}

void main() {
  final List<Object?> samples = fixtureIndex()['samples']! as List<Object?>;

  for (final Object? path in samples) {
    test('should match the golden samples in $path', () {
      final Map<String, Object?> golden = readFixture(path! as String)! as Map<String, Object?>;
      final Rig rig = Rig.fromJson(readFixture('animation/${golden['rig']}')! as Map<String, Object?>);
      final List<Layer> layers = <Layer>[
        for (final Object? raw in golden['layers']! as List<Object?>)
          Layer(
            Clip.fromJson(readFixture('animation/${(raw! as Map<String, Object?>)['animation']}')! as Map<String, Object?>),
            speed: ((raw as Map<String, Object?>)['speed']! as num).toDouble(),
          ),
      ];
      final Map<String, Object?> tolerance = golden['tolerance']! as Map<String, Object?>;
      final double blocks = (tolerance['blocks']! as num).toDouble();
      final double degrees = (tolerance['degrees']! as num).toDouble();
      final double yaw = (golden['yaw']! as num).toDouble();
      int checked = 0;
      for (final Object? rawSample in golden['samples']! as List<Object?>) {
        final Map<String, Object?> sample = rawSample! as Map<String, Object?>;
        final double tick = (sample['tick']! as num).toDouble();
        final Map<String, BonePose> poses = sampleRig(rig, layers, yaw, tick);
        final Map<String, Object?> bones = sample['bones']! as Map<String, Object?>;
        expect(poses.keys.toSet(), bones.keys.toSet());
        for (final MapEntry<String, Object?> bone in bones.entries) {
          final Map<String, Object?> want = bone.value! as Map<String, Object?>;
          final BonePose pose = poses[bone.key]!;
          final String at = '$path tick $tick ${bone.key}';
          expectClose(pose.rotation, want['rotation']! as List<Object?>, degrees, '$at rotation');
          expectClose(pose.translation, want['translation']! as List<Object?>, blocks, '$at translation');
          expectClose(pose.scale, want['scale']! as List<Object?>, blocks, '$at scale');
          expect(pose.visible, want['visible'], reason: '$at visible');
          expectClose(pose.model.m, want['model']! as List<Object?>, blocks, '$at model');
          if (want.containsKey('visual')) {
            expectClose(pose.visual!.m, want['visual']! as List<Object?>, blocks, '$at visual');
          } else {
            expect(pose.visual, isNull, reason: '$at visual');
          }
          checked++;
        }
      }
      expect(checked, greaterThan(0));
    });
  }

  test('should parse every easing the schema allows and refuse bad bezier controls', () {
    for (final String name in <String>['linear', 'hold', 'ease_in', 'ease_out', 'ease_in_out', 'back_out', 'bezier(0.42,0,0.58,1)']) {
      final Easing easing = Easing.parse(name);
      expect(easing.apply(0), closeTo(0, 1e-9), reason: name);
      expect(easing.apply(1), closeTo(name == 'hold' ? 0 : 1, 1e-9), reason: name);
    }
    expect(() => Easing.parse('bezier(1.5,0,0.5,1)'), throwsFormatException);
    expect(() => Easing.parse('wobble'), throwsFormatException);
  });

  test('should report rig and animation rules the schema cannot express', () {
    final Map<String, Object?> rig = readFixture('animation/rigs/golem.json')! as Map<String, Object?>;
    expect(validateRigStructure(rig), isEmpty);
    final Map<String, Object?> broken = <String, Object?>{
      ...rig,
      'bones': <Object?>[
        <String, Object?>{'id': 'a', 'pivot': <Object?>[0, 0, 0]},
        <String, Object?>{'id': 'b', 'parent': 'c', 'pivot': <Object?>[0, 0, 0]},
        <String, Object?>{'id': 'a', 'pivot': <Object?>[0, 0, 0]},
      ],
      'sockets': <String, Object?>{'main_hand': 'missing'},
    };
    expect(validateRigStructure(broken).map((Diagnostic d) => d.code), <String>['rig-parent-order', 'rig-duplicate-bone', 'rig-socket']);
    final Map<String, Object?> clip = readFixture('animation/clips/golem-walk.json')! as Map<String, Object?>;
    expect(validateAnimationStructure(clip, rig), isEmpty);
    final Map<String, Object?> badClip = <String, Object?>{
      ...clip,
      'durationTicks': 5,
      'tracks': <Object?>[
        <String, Object?>{
          'bone': 'tail',
          'target': 'rotation',
          'keyframes': <Object?>[
            <String, Object?>{'tick': 4, 'value': <Object?>[0, 0, 0]},
            <String, Object?>{'tick': 2, 'value': <Object?>[0, 0, 0]},
            <String, Object?>{'tick': 9, 'value': <Object?>[0, 0, 0]},
          ],
        },
      ],
    };
    expect(validateAnimationStructure(badClip, rig).map((Diagnostic d) => d.code), <String>['animation-bone', 'animation-order', 'animation-range']);
    expect(SharedSchemas.embedded.validate(DocumentKind.animation, badClip).where((Diagnostic d) => d.severity == DiagnosticSeverity.error), isEmpty);
  });
}
