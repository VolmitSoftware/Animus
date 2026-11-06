import 'package:animus_editor/logic/gui/gui_expression.dart';
import 'package:animus_editor/logic/gui/text_animation.dart';
import 'package:animus_editor/mc/gui/gui_scene.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

void main() {
  test('should choose the frame TextAnimation.frame chooses for every shared sample', () {
    final Map<String, Object?> fixture = readFixture('gui/text-animation-frames.json')! as Map<String, Object?>;
    int samples = 0;
    for (final Object? raw in fixture['animations']! as List<Object?>) {
      final Map<String, Object?> entry = raw! as Map<String, Object?>;
      final String id = entry['id']! as String;
      final Map<String, Object?> document = entry['document']! as Map<String, Object?>;
      for (final Object? sample in entry['samples']! as List<Object?>) {
        final List<Object?> pair = sample! as List<Object?>;
        expect(textAnimationFrameIndex(id, document, (pair[0]! as num).toInt()), pair[1], reason: '$id at ${pair[0]}');
        samples++;
      }
    }
    expect(samples, greaterThanOrEqualTo(30));
  });

  test('should replace |animation.<id>| tokens with the current frame, relative ids in the document namespace', () {
    final Map<String, Map<String, Object?>> animations = <String, Map<String, Object?>>{
      'shop:sparkle': <String, Object?>{'mode': 'ascend', 'frameIntervalMs': 500, 'frames': <Object?>['&e* ', '&6+ ']},
      'fx:off': <String, Object?>{'show': false, 'frames': <Object?>['x']},
    };
    expect(renderAnimationTokens('|animation.sparkle|&6Bakery', 'shop:sign', animations, 0), '&e* &6Bakery');
    expect(renderAnimationTokens('|animation.shop:sparkle|Bakery', 'town:sign', animations, 600), '&6+ Bakery');
    expect(renderAnimationTokens('|animation.fx:off|a', 'shop:sign', animations, 0), 'a');
    expect(renderAnimationTokens('|animation.missing|a', 'shop:sign', animations, 0), '|animation.missing|a',
        reason: 'a missing animation stays visible as text so the author sees it');
    expect(animationReferences('|animation.sparkle| and |animation.fx:off|', 'shop:sign'), <String>['shop:sparkle', 'fx:off']);
  });

  test('should complete |animation.| tokens with workspace animation ids, short within the namespace', () {
    final Set<String> ids = animationCompletionIds('shop:sign', <String>['shop:sparkle', 'fx:rainbow']);
    expect(ids, <String>{'sparkle', 'fx:rainbow'});
    final ExpressionNames names = ExpressionNames(animations: ids);
    final List<ExpressionCompletion> offered = completeExpression('&6|animation.sp', 15, template: true, names: names);
    expect(offered.map((ExpressionCompletion c) => c.label), <String>['sparkle']);
    expect(offered.single.applyTo('&6|animation.sp').$1, '&6|animation.sparkle|');
    expect(completeExpression('|animation.', 11, template: true, names: names).map((ExpressionCompletion c) => c.label), <String>['fx:rainbow', 'sparkle']);
    expect(completeExpression('|animation.sp|x', 13, template: true, names: names).single.applyTo('|animation.sp|x').$1, '|animation.sparkle|x');
  });

  test('should play animation tokens in the hologram preview and note missing animations', () {
    final Map<String, Map<String, Object?>> animations = <String, Map<String, Object?>>{
      'shop:sparkle': <String, Object?>{'mode': 'ascend', 'frameIntervalMs': 500, 'frames': <Object?>['&e* ', '&6+ ']},
    };
    final Map<String, Object?> sign = <String, Object?>{
      'schemaVersion': 3,
      'lines': <Object?>['|animation.sparkle|&6Bakery', '|animation.gone|x'],
    };
    String first(int nowMs) => hologramScene(sign, clock: GuiAnimationClock(from: 'shop:sign', animations: animations, nowMs: nowMs))
        .texts
        .single
        .lines
        .first;
    expect(first(0), '&e* &6Bakery');
    expect(first(500), '&6+ &6Bakery');
    final GuiScene scene = hologramScene(sign, clock: GuiAnimationClock(from: 'shop:sign', animations: animations));
    expect(scene.notes, contains('Text animation shop:gone is not in this workspace, so its token shows as written.'));
    expect(animationTickMs('|animation.sparkle|', 'shop:sign', animations), 500);
    expect(animationTickMs('plain', 'shop:sign', animations), isNull);
  });
}
