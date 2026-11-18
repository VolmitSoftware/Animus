import 'package:animus_editor/logic/npc/skin_finds.dart';
import 'package:animus_editor/mc/npc/npc_skin_failure.dart';
import 'package:test/test.dart';

void main() {
  test('a skin by name or MineSkin link needs a paired server that can look one up', () {
    expect(skinFindAbsence(paired: false), 'Player names and MineSkin links need a paired server.');
    expect(skinFindAbsence(paired: true), isNull, reason: 'capabilities not read yet: the request says why if it fails');
    expect(skinFindAbsence(paired: true, capabilities: <String, Object?>{'features': <String, Object?>{'skins.find': <String, Object?>{'available': true}}}), isNull);
    expect(skinFindAbsence(paired: true, capabilities: <String, Object?>{'features': <String, Object?>{}}),
        "This server's Animus is too old to look up skins. Update it, then pair again.");
  });

  test('a skin that did not load says why without an exception name and names the real button', () {
    expect(skinFailureText(const FormatException('Use an http or https address.')), 'Use an http or https address. Paste the full address of the PNG.');
    expect(skinFailureText(const FormatException('A skin is 64x64 pixels.')), 'A skin is 64x64 pixels. Pick a 64x64 Minecraft skin PNG.');
    expect(skinFailureText(Exception('HTTP 404')), 'Nothing is at that address (HTTP 404). Check the address or use Upload PNG.');
    expect(skinFailureText(StateError('opaque')), 'Could not read the skin: not a PNG, or the site blocks cross-origin reads. Use Upload PNG.');
    for (final Object failure in <Object>[const FormatException('x'), Exception('HTTP 500'), StateError('y')]) {
      expect(skinFailureText(failure), isNot(contains('Exception')));
      expect(skinFailureText(failure), isNot(contains('From PNG file')));
    }
  });
}
