import 'package:animus_editor/mc/scene/mc_camera.dart';
import 'package:animus_editor/mc/scene/mc_math.dart';
import 'package:animus_editor/mc/scene/mc_occlusion.dart';
import 'package:test/test.dart';

void main() {
  // Looking along +z at a body standing at the origin, from 8 blocks away.
  const McCamera camera = McCamera(pivot: McVec3(0, 1, 0), yawDeg: 0, pitchDeg: 0, distance: 8);
  const McBounds subject = McBounds(McVec3(-0.4, 0, -0.4), McVec3(0.4, 1.8, 0.4));

  Set<McOccluder> culled(List<McOccluder> occluders) => mcCulled(camera, subject, occluders, widthPx: 400, heightPx: 300);

  test('a body between the eye and the subject is left out', () {
    const McOccluder front = McOccluder(McVec3(0.3, 0, -3));
    expect(culled(<McOccluder>[front]), <McOccluder>{front});
  });

  test('a body behind the subject or off to the side is kept', () {
    const McOccluder behind = McOccluder(McVec3(0, 0, 3));
    const McOccluder aside = McOccluder(McVec3(5, 0, -3));
    expect(culled(<McOccluder>[behind, aside]), isEmpty);
  });

  test('a body round the eye is left out, one behind it does not count', () {
    const McOccluder atEye = McOccluder(McVec3(0, 0, -8));
    const McOccluder behind = McOccluder(McVec3(0, 0, -10));
    expect(culled(<McOccluder>[atEye, behind]), <McOccluder>{atEye});
  });

  test('a body close to the eye is left out even clear of the subject', () {
    const McOccluder close = McOccluder(McVec3(1.2, 0, -7));
    expect(culled(<McOccluder>[close]), <McOccluder>{close});
  });

  test('a body whose head leaves the top of the view is left out', () {
    const McOccluder high = McOccluder(McVec3(2, 5, 6));
    const McOccluder seen = McOccluder(McVec3(2, 0, 6));
    expect(culled(<McOccluder>[high, seen]), <McOccluder>{high});
  });

  test('a nearer body cut by the side is left out, a farther one is kept', () {
    const McOccluder nearer = McOccluder(McVec3(2.6, 0, -3));
    const McOccluder farther = McOccluder(McVec3(6.8, 0, 6));
    expect(culled(<McOccluder>[nearer, farther]), <McOccluder>{nearer});
  });

  test('a body behind the subject that shows through its name plates is left out', () {
    // The subject's hologram hangs from 2.0 to 2.6 blocks up.
    const McBounds plates = McBounds(McVec3(-0.9, 2, -0.05), McVec3(0.9, 2.6, 0.05));
    const McOccluder behindPlates = McOccluder(McVec3(0.2, 1.5, 6));
    const McOccluder behindLow = McOccluder(McVec3(3.5, -1, 6));
    expect(mcCulled(camera, subject, <McOccluder>[behindPlates, behindLow], widthPx: 400, heightPx: 300, plates: plates), <McOccluder>{behindPlates});
    expect(culled(<McOccluder>[behindPlates]), isEmpty, reason: 'without plates the body behind is kept');
  });

  test('with nothing to leave out no work is done', () {
    expect(culled(const <McOccluder>[]), isEmpty);
  });
}
