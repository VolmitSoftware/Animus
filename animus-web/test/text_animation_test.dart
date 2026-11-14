import 'package:animus_editor/logic/gui/gui_expression.dart';
import 'package:animus_editor/logic/gui/gui_validation.dart';
import 'package:animus_editor/logic/gui/gui_text.dart';
import 'package:animus_editor/logic/gui/text_animation.dart';
import 'package:animus_editor/logic/suggest/completion_item.dart';
import 'package:animus_editor/logic/suggest/field_profile.dart';
import 'package:animus_editor/logic/suggest/text_completion.dart';
import 'package:animus_editor/mc/gui/gui_scene.dart';
import 'package:animus_editor/mc/npc/mc_text.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
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
    final CompletionEnv env = CompletionEnv(gui: names);
    List<String> labels(CompletionResult result) => <String>[for (final CompletionItem item in result.items) item.label];
    final CompletionResult offered = completeText('&6|animation.sp', 15, FieldProfile.guiText, env);
    expect(labels(offered), <String>['sparkle']);
    expect(offered.apply('&6|animation.sp', offered.items.single).text, '&6|animation.sparkle|');
    expect(labels(completeText('|animation.', 11, FieldProfile.guiText, env)), unorderedEquals(<String>['fx:rainbow', 'sparkle']));
    final CompletionResult inside = completeText('|animation.sp|x', 13, FieldProfile.guiText, env);
    expect(inside.apply('|animation.sp|x', inside.items.single).text, '|animation.sparkle|x');
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
    expect(scene.notes, contains('Text animation shop:gone is not in this workspace; token shown as written.'));
    expect(animationTickMs('|animation.sparkle|', 'shop:sign', animations), 500);
    expect(animationTickMs('plain', 'shop:sign', animations), isNull);
  });

  test('should show sample text in the colour of a frame that is only a colour code', () {
    final List<McTextRun> runs = guiTextRuns(textAnimationPreviewText('[FF0000]'));
    expect(runs, hasLength(1));
    expect(runs.single.text, 'Sample text');
    expect(runs.single.color, 0xFF0000);
    expect(textAnimationPreviewText('&aHi'), '&aHi');
    expect(textAnimationPreviewText(''), '');
  });

  test('should give a frame of codes alone the colour it sets, and none to a frame that shows text', () {
    expect(textAnimationFrameColour('[FF3300]'), 0xFF3300);
    expect(textAnimationFrameColour('&6'), 0xFFAA00);
    expect(textAnimationFrameColour('&#5FD3FF'), 0x5FD3FF);
    expect(textAnimationFrameColour('&e* '), isNull);
    expect(textAnimationFrameColour(''), isNull);
    expect(colourFramesOnly(<String>['[FF0000]', '&a']), isTrue);
    expect(colourFramesOnly(<String>['[FF0000]', '&aGo']), isFalse);
    expect(colourFramesOnly(const <String>[]), isFalse);
  });

  test('should page long frame lists by thirty, clamping the page', () {
    expect(framePage(60, 0), (start: 0, end: 30, page: 0, pages: 2));
    expect(framePage(60, 1), (start: 30, end: 60, page: 1, pages: 2));
    expect(framePage(61, 5), (start: 60, end: 61, page: 2, pages: 3));
    expect(framePage(12, 3), (start: 0, end: 12, page: 0, pages: 1));
    expect(framePage(0, 0), (start: 0, end: 0, page: 0, pages: 1));
  });

  test('should read the clock names the server reads, so a marquee frame is valid and drawn', () {
    final Map<String, Object?> marquee = readJson('gloss/fixtures/animation-marquee.json')! as Map<String, Object?>;
    final List<Diagnostic> problems = validateGui(DocumentKind.textAnimation, marquee);
    expect(problems.where((Diagnostic d) => d.severity == DiagnosticSeverity.error), isEmpty, reason: '$problems');
    for (final String name in <String>['time.seconds', 'time.ticks', 'time.millis', 'time.ms', 'time.epochMs']) {
      expect(checkExpression('floor($name)'), isNull, reason: name);
    }
    expect(renderTemplate((marquee['frames']! as List<Object?>).single! as String), isNot(contains('?')));
  });

  test('should count a broken frame expression as a document problem at that frame', () {
    final List<Diagnostic> problems = validateGui(DocumentKind.textAnimation, <String, Object?>{
      'schemaVersion': 1,
      'mode': 'ascend',
      'frameIntervalMs': 100,
      'frames': <Object?>['&aok', '{{ nope.thing }}'],
    });
    final List<Diagnostic> errors = <Diagnostic>[for (final Diagnostic d in problems) if (d.severity == DiagnosticSeverity.error) d];
    expect(errors, hasLength(1));
    expect(errors.single.path, r'$.frames[1]');
    expect(errors.single.message, contains("unknown name 'nope.thing'"));
  });
}
