// The hologram and menu previews frame what they draw: the camera stands
// back far enough that the widest text row fits the stage at the size the
// text layer draws it, so a long row never wraps out of its backdrop.
import 'dart:math' as math;

import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/gui/hologram_lines.dart';
import 'package:animus_editor/mc/gui/gui_scene.dart';
import 'package:animus_editor/mc/npc/mc_text.dart';
import 'package:animus_editor/mc/npc/npc_scene.dart';
import 'package:animus_editor/mc/scene/mc_camera.dart';
import 'package:animus_editor/mc/scene/mc_math.dart';
import 'package:test/test.dart';

/// The widest row of [text] in CSS pixels on a [width] x [height] stage
/// framed the way the stage frames a GUI preview, and the room it has.
(double, double) widestRow(GuiScene scene, NpcText text, double width, double height) {
  final McCamera camera = mcCameraFraming(scene.scene.frame, yawDeg: 180, aspect: width / height);
  final McVec3 at = mcProjectToPixels(camera, text.anchor, widthPx: width, heightPx: height)!;
  final double font = mcCrispFontPx(mcTextFontPx(height, at.z) * text.scaleY);
  double widest = 0;
  for (final List<McTextRun> row in text.styled!) {
    double units = 0;
    for (final McTextRun run in row) {
      units += run.text.length * (run.bold ? 7 : 6);
    }
    widest = math.max(widest, units / 8 * font * text.scaleX / text.scaleY);
  }
  return (widest, width - 2 * mcTextInsetPx);
}

const List<(double, double)> stages = <(double, double)>[(680, 240), (650, 488), (520, 390), (1200, 900)];

void main() {
  test('should frame a hologram so its widest row fits the stage without wrapping', () {
    final GuiScene scene = hologramScene(<String, Object?>{
      'lines': <Object?>["&4&l&oWelcome to Lili's house", 'Home of Blocks, Boats & Booty'],
    });
    for (final (double width, double height) in stages) {
      final (double widest, double room) = widestRow(scene, scene.texts.single, width, height);
      expect(widest, lessThanOrEqualTo(room), reason: '${width}x$height: the bold row carried from the first line is $widest px');
    }
  });

  // A row wider than the stage even at the text layer's smallest font
  // (16 px) wraps whatever the camera does; these fit at that size.
  test('should frame a wide plain row and a scaled row too', () {
    for (final JsonMap doc in <JsonMap>[
      <String, Object?>{'lines': <Object?>['A long welcome line for the market square']},
      <String, Object?>{
        'style': <String, Object?>{'scaleX': 2, 'scaleY': 2},
        'lines': <Object?>['Scaled sign'],
      },
    ]) {
      final GuiScene scene = hologramScene(doc);
      for (final (double width, double height) in stages) {
        final (double widest, double room) = widestRow(scene, scene.texts.single, width, height);
        expect(widest, lessThanOrEqualTo(room), reason: '$doc at ${width}x$height');
      }
    }
  });

  test('should not frame pixel art wider than it draws: colour codes take no room', () {
    final GuiScene scene = hologramScene(<String, Object?>{
      'lines': <Object?>[for (int row = 0; row < 16; row++) '[FFFF00]██[BFFF00]██[7FFF00]██[40FF00]██'],
    });
    final Iterable<double> xs = scene.scene.frame.map((McVec3 point) => point.x);
    final double framed = xs.reduce(math.max) - xs.reduce(math.min);
    expect(framed, lessThan(3), reason: 'eight glyphs are about 1.2 blocks wide; the codes are not glyphs');
  });

  test('should keep the default two-block frame for a short sign', () {
    final GuiScene scene = hologramScene(<String, Object?>{'lines': <Object?>['Hi']});
    final Iterable<double> xs = scene.scene.frame.map((McVec3 point) => point.x);
    expect(xs.reduce(math.max) - xs.reduce(math.min), closeTo(2, 1e-9));
  });

  test('should say a hologram that is not see-through in plain words', () {
    final GuiScene hidden = hologramScene(<String, Object?>{'style': <String, Object?>{'seeThrough': false}, 'lines': <Object?>['a']});
    expect(hidden.notes, contains('Not see-through in game.'));
    expect(hidden.notes.join(' '), isNot(contains('seeThrough')));
  });

  test('should float a holographic menu with no ground under it', () {
    final GuiScene scene = menuScene(<String, Object?>{
      'components': <Object?>[
        <String, Object?>{
          'id': 'title',
          'data': <String, Object?>{'type': 'button', 'icon': <String, Object?>{'type': 'text', 'text': '&6Gloss Menu'}},
        },
      ],
    });
    expect(scene.scene.ground, isFalse, reason: 'a menu floats in front of the viewer; a grass patch under it is empty space');
    expect(hologramScene(<String, Object?>{'lines': <Object?>['a']}).scene.ground, isTrue);
  });

  test('should show a coordinate rounded to two places and keep whole numbers whole', () {
    expect(shownCoordinate(-22.411723669167085), -22.41);
    expect(shownCoordinate(64), 64);
    expect(shownCoordinate(0.005), 0.01);
    expect(shownCoordinate(null), isNull);
  });
}
