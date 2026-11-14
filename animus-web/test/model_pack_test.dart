// The preview draws mobs from the model pack the extraction tool writes from
// a client jar with the editor's own texture list. A pack extracted before
// the list named the mob textures drew villagers white.
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:animus_editor/mc/assets/mc_pack_manifest.dart';
import 'package:animus_editor/mc/npc/npc_look.dart';
import 'package:animus_editor/mc/npc/npc_scene.dart';
import 'package:animus_editor/mc/rigs/mc_rigs.dart';
import 'package:animus_editor/mc/scene/mc_scene.dart';
import 'package:archive/archive.dart';
import 'package:image/image.dart' as img;
import 'package:test/test.dart';

import '../tool/mc_extract/jar_assets.dart';
import '../tool/mc_extract/pack_writer.dart';

/// The textures a desert farmer villager at master level draws with.
List<String> villagerTextures() {
  final NpcScene villager = npcScene(NpcLook.fromDefinition(<String, Object?>{
    'type': 'minecraft:villager',
    'name': 'Farmer',
    'villager': <String, Object?>{'type': 'desert', 'profession': 'farmer', 'level': 'master'},
  }));
  return <String>[for (final McRigNode node in villager.scene.nodes.whereType<McRigNode>()) node.textureId];
}

/// The pack under [folder], or null when none was extracted there.
McPackManifest? packAt(String folder) {
  final File file = File('$folder/pack.json');
  if (!file.existsSync()) return null;
  return McPackManifest.fromJson(jsonDecode(file.readAsStringSync()) as Map<String, Object?>);
}

void main() {
  test('a villager finds its body, biome, job and badge textures in an extracted pack', () async {
    final Uint8List png = Uint8List.fromList(img.encodePng(img.Image(width: 16, height: 16)));
    final Archive jar = Archive()
      ..addFile(ArchiveFile.bytes('assets/minecraft/textures/block/stone.png', png))
      ..addFile(ArchiveFile.bytes('assets/minecraft/textures/item/apple.png', png));
    for (final String id in mcEntityTextureIds.toSet()) {
      jar.addFile(ArchiveFile.bytes('assets/minecraft/textures/$id.png', png));
    }
    final Directory out = Directory.systemTemp.createTempSync('animus-pack-');
    addTearDown(() => out.deleteSync(recursive: true));
    await PackWriter(
      jar: JarAssets.fromBytes(Uint8List.fromList(ZipEncoder().encodeBytes(jar))),
      version: '26.3',
      jarSha256: '0',
      generatedAt: '2026-11-13T00:00:00Z',
      entityTextures: mcEntityTextureIds,
    ).write(out);
    final McPackManifest pack = packAt(out.path)!;

    final List<String> textures = villagerTextures();
    expect(textures, containsAll(<String>['entity/villager/villager', 'entity/villager/type/desert', 'entity/villager/profession/farmer', 'entity/villager/profession_level/diamond']));
    for (final String id in textures) {
      final String? path = pack.entityTextures[id];
      expect(path, isNotNull, reason: id);
      expect(File('${out.path}/$path').existsSync(), isTrue, reason: id);
    }
  });

  final McPackManifest? local = packAt('web/assets/mc/26.3');
  test('the pack extracted in this checkout carries every texture the editor draws', () {
    final List<String> missing = <String>[for (final String id in mcEntityTextureIds) if (!local!.entityTextures.containsKey(id)) id];
    expect(missing, isEmpty,
        reason: 'web/assets/mc/26.3 is older than the texture list; extract it again with '
            'dart run tool/extract_minecraft_world_assets.dart --jar=<26.3 client jar> --version=26.3');
  }, skip: local == null ? 'no model pack extracted in web/assets/mc/26.3' : false);
}
