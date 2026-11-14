import 'package:animus_editor/logic/stage/stage_status.dart';
import 'package:test/test.dart';

void main() {
  const String keys = 'Drag to orbit. Focused: arrows orbit.';

  test('should say how to turn the view when nothing else needs saying', () {
    const StageStatus status = StageStatus(<StageNote>[]);
    expect(status.line, 'Drag to turn');
    expect(status.tip(keys), keys);
  });

  test('should show at most two phrases and explain them first in the tip', () {
    const StageStatus status = StageStatus(<StageNote>[
      StageNote('Stand near this NPC in game to load its terrain.', 'Chunks are empty until a player is near.'),
      StageNote('Showing 60 of 140 nearby', 'The 60 nearest are drawn.'),
      StageNote('Not shown in game', 'A body no server shows.'),
    ], extra: <String>['Skin: the Miner stand-in, classic arms.']);
    expect(status.line, 'Stand near this NPC in game to load its terrain. · Showing 60 of 140 nearby');
    expect(status.tip(keys), startsWith('Chunks are empty until a player is near. The 60 nearest are drawn.'));
  });

  test('should count what the renderer could not draw and list it in the tip', () {
    const StageStatus status = StageStatus(<StageNote>[], unrendered: <String>['The shield is not drawn.', 'The trident is not drawn.']);
    expect(status.line, '2 parts not drawn');
    expect(status.tip(keys), startsWith('The shield is not drawn. The trident is not drawn.'));
  });

  test('should skip empty phrases and keep extra details after the explanations', () {
    const StageStatus status = StageStatus(<StageNote>[StageNote(''), StageNote('Loading terrain')], extra: <String>['Skin: loading.']);
    expect(status.line, 'Loading terrain');
    expect(status.tip(keys), 'Skin: loading. $keys');
  });
}
