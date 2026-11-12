/// An NPC as the offline playground shows it: the body from its definition,
/// the rig its `rig` names sampled at a playback tick (hiding the body when
/// the rig says so, as the server does), and a line of speech from a dry run
/// floating over everything.
library;

import 'dart:typed_data';

import '../../logic/gui/gui_json.dart';
import '../../logic/rig_sampler.dart';
import '../rig/rig_scene.dart';
import '../scene/mc_camera.dart';
import '../scene/mc_math.dart';
import '../scene/mc_scene.dart';
import 'npc_look.dart';
import 'npc_scene.dart';
import '../../logic/gui/gui_text.dart';

NpcScene npcPlaygroundScene(
  NpcLook look, {
  NpcSkinChoice skin = NpcSkinChoice.steve,
  JsonMap? rig,
  Clip? clip,
  double tick = 0,
  Map<String, Uint8List> resources = const <String, Uint8List>{},
  RigTextureId? textureId,
  String? speech,
}) {
  final NpcScene body = npcScene(look, skin: skin);
  final List<McSceneNode> nodes = <McSceneNode>[...body.scene.nodes];
  final List<McVec3> frame = <McVec3>[...body.scene.frame];
  final List<NpcText> texts = <NpcText>[...body.texts];
  final List<String> notes = <String>[...body.notes];
  if (rig != null) {
    Rig? parsed;
    try {
      parsed = Rig.fromJson(rig);
    } on Object {
      notes.add('The rig ${rig['id']} is not drawn until it is valid.');
    }
    if (parsed != null) {
      final RigScene drawn = rigScene(
        rig,
        sampleRig(parsed, <Layer>[if (clip != null) Layer(clip)], look.yawDeg, tick),
        resources: resources,
        textureId: textureId,
        // A shown body wears its own equipment; a hidden one leaves it to the sockets.
        equipment: rig['body'] == 'shown'
            ? const <String, String>{}
            : <String, String>{for (final MapEntry<NpcSlot, NpcItem> item in look.equipment.entries) item.key.key: item.value.modelKey},
      );
      if (rig['body'] != 'shown') {
        nodes.clear();
        frame.clear();
      }
      nodes.addAll(drawn.nodes);
      texts.addAll(drawn.texts);
      notes.addAll(drawn.notes);
      frame.addAll(drawn.bounds.corners);
    }
  }
  final String? line = speech?.trim();
  if (line != null && line.isNotEmpty) {
    double top = McBounds.of(frame).max.y;
    for (final NpcText text in texts) {
      final double over = text.anchor.y + text.lines.length * 0.25;
      if (over > top) top = over;
    }
    final McVec3 anchor = McVec3(0, top + 0.35, 0);
    texts.add(NpcText(anchor: anchor, lines: <String>[guiColorize(line)], nameplate: false));
    // The camera frames the speech too, so a long body never pushes it off.
    frame.add(anchor + const McVec3(0, 0.3, 0));
  }
  // A glowing body glows with the rig it shows or wears.
  final McGlow? bodyGlow = body.scene.glow;
  final McGlow? glow = bodyGlow == null ? null : McGlow(bodyGlow.color, <String>{for (final McSceneNode node in nodes) node.key});
  return NpcScene(scene: McScene(nodes, frame: frame, glow: glow), texts: texts, notes: notes, presentable: body.presentable);
}
