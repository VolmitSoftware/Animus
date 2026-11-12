import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/offline/new_documents.dart';
import 'package:animus_editor/logic/rig_sampler.dart';
import 'package:animus_editor/mc/npc/npc_look.dart';
import 'package:animus_editor/mc/npc/npc_playground.dart';
import 'package:animus_editor/mc/npc/npc_scene.dart';
import 'package:animus_editor/mc/scene/mc_camera.dart';
import 'package:animus_editor/mc/scene/mc_scene.dart';
import 'package:test/test.dart';

void main() {
  final NpcLook look = NpcLook.fromDefinition(newNpc(const NpcSeed(name: 'Tom'), uuid: newUuid()));

  test('should draw the body alone without a rig', () {
    final NpcScene scene = npcPlaygroundScene(look);
    expect(scene.scene.nodes, isNotEmpty);
    expect(scene.texts.single.lines.single, 'Tom');
  });

  test('should hide the body under a hidden rig and keep it under a shown one', () {
    final List<String> body = <String>[for (final McSceneNode node in npcScene(look).scene.nodes) node.key];
    final JsonMap hidden = newRig('a:r');
    final List<String> hiddenKeys = <String>[for (final McSceneNode node in npcPlaygroundScene(look, rig: hidden).scene.nodes) node.key];
    expect(hiddenKeys.toSet().intersection(body.toSet()), isEmpty);
    expect(hiddenKeys, containsAll(<String>['bone:body', 'bone:head']));
    final List<String> shownKeys = <String>[
      for (final McSceneNode node in npcPlaygroundScene(look, rig: newRig('a:r', bodyShown: true)).scene.nodes) node.key,
    ];
    expect(shownKeys, containsAll(<String>[...body, 'bone:body']));
  });

  test('should move a bone when the animation plays', () {
    final JsonMap rig = newRig('a:r');
    final Clip clip = Clip.fromJson(newRigAnimation('a:r/bob', 'a:r', rig));
    McSceneNode bodyAt(double tick) =>
        npcPlaygroundScene(look, rig: rig, clip: clip, tick: tick).scene.nodes.firstWhere((McSceneNode node) => node.key == 'bone:body');
    expect(bodyAt(10).transform.m, isNot(bodyAt(0).transform.m));
  });

  test('should float speech above the nameplate and frame it', () {
    final NpcScene scene = npcPlaygroundScene(look, speech: '&eHello there');
    final NpcText speech = scene.texts.last;
    expect(speech.lines.single, '§eHello there');
    expect(speech.keepInside, isTrue);
    expect(scene.texts.first.keepInside, isFalse);
    expect(speech.anchor.y, greaterThan(scene.texts.first.anchor.y));
    expect(McBounds.of(scene.scene.frame).max.y, greaterThan(speech.anchor.y));
    expect(npcPlaygroundScene(look, speech: '  ').texts, hasLength(1));
  });
}
