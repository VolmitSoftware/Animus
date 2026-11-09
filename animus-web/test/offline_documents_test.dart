import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/offline/document_fixes.dart';
import 'package:animus_editor/logic/offline/new_documents.dart';
import 'package:animus_editor/logic/offline/starter_workspace.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/mc/rigs/mc_rigs.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:crypto/crypto.dart';
import 'package:test/test.dart';

import '../tool/embed_starter_packs.dart';

AnimusDocument validate(DocumentKind kind, String id, Object? json) =>
    validateSource(DocumentLocation(kind, id), newDocumentPath(kind, id), prettyJson(json), SharedSchemas.embedded);

List<Diagnostic> errors(AnimusDocument document) =>
    <Diagnostic>[for (final Diagnostic diagnostic in document.diagnostics) if (diagnostic.severity == DiagnosticSeverity.error) diagnostic];

void main() {
  final math.Random random = math.Random(7);

  group('ids', () {
    test('should slug a display name with colour codes into a file name', () {
      expect(slug('&aOld Tom, the Baker!'), 'old-tom-the-baker');
      expect(slug('***'), 'npc');
      expect(slug('x' * 80).length, 64);
    });

    test('should number an id that is taken', () {
      expect(freeId('baker', <String>{}), 'baker');
      expect(freeId('baker', <String>{'baker', 'baker-2'}), 'baker-3');
    });

    test('should make canonical version 4 UUIDs', () {
      final String uuid = newUuid(random);
      expect(uuid, matches(RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')));
    });

    test('should check ids against their data-folder location', () {
      expect(validDocumentId(DocumentKind.npc, 'town/baker'), isTrue);
      expect(validDocumentId(DocumentKind.npc, 'baker'), isTrue);
      expect(validDocumentId(DocumentKind.npc, 'world/town/baker'), isTrue);
      expect(validDocumentId(DocumentKind.npc, 'a/b/c/d/e/f/g/h/i/baker'), isFalse);
      expect(validDocumentId(DocumentKind.npc, 'Town/baker'), isFalse);
      expect(validDocumentId(DocumentKind.behavior, 'town:greeter'), isTrue);
      expect(validDocumentId(DocumentKind.behavior, 'Town:Greeter'), isFalse);
      expect(validDocumentId(DocumentKind.pack, 'town'), isTrue);
      expect(newDocumentPath(DocumentKind.pack, 'town'), 'packs/town/pack.json');
      expect(newDocumentPath(DocumentKind.presets, 'presets'), 'gui/presets.json');
    });
  });

  group('defaults', () {
    test('should make a valid NPC for every body type the stage draws', () {
      for (final String type in mcBodyTypes) {
        final NpcSeed seed = NpcSeed(name: '&6Test ${type.split(':').last}', type: type, group: 'town', x: 4, y: 70, z: -2, yaw: 90);
        final AnimusDocument document = validate(DocumentKind.npc, seed.documentId, newNpc(seed, uuid: newUuid(random)));
        expect(errors(document), isEmpty, reason: type);
      }
    });

    test('should keep a skin only on player bodies', () {
      final String hash = newSkin(slim: false).id;
      expect(newNpc(NpcSeed(name: 'A', skin: hash), uuid: newUuid(random))['skin'], hash);
      expect(newNpc(NpcSeed(name: 'A', type: 'minecraft:horse', skin: hash), uuid: newUuid(random)).containsKey('skin'), isFalse);
    });

    test('should make the paired player the owner of a new NPC', () {
      const String player = '0000000a-0000-4000-8000-0000000000b2';
      const NpcSeed seed = NpcSeed(name: 'Owned', group: 'town', owner: player);
      final JsonMap npc = newNpc(seed, uuid: newUuid(random));
      expect(npc['owner'], player);
      expect(errors(validate(DocumentKind.npc, seed.documentId, npc)), isEmpty);
      expect(newNpc(const NpcSeed(name: 'Offline'), uuid: newUuid(random)).containsKey('owner'), isFalse, reason: 'offline NPCs have no owner');
    });

    test('should make a valid behavior from every template', () {
      for (final BehaviorTemplate template in BehaviorTemplate.values) {
        final JsonMap behavior = behaviorFromTemplate(template, 'town:${template.name}', shop: 'town:market', origin: (10, 70, -4));
        expect(errors(validate(DocumentKind.behavior, 'town:${template.name}', behavior)), isEmpty, reason: template.name);
        expect(behavior['id'], 'town:${template.name}');
      }
    });

    test('should point the patrol template around its post and the shop template at its shop', () {
      final String patrol = jsonEncode(behaviorFromTemplate(BehaviorTemplate.patrol, 'a:p', origin: (100, 70, 200)));
      expect(patrol, contains('"x":108.0'));
      expect(patrol, contains('"z":208.0'));
      expect(jsonEncode(behaviorFromTemplate(BehaviorTemplate.shop, 'a:s', shop: 'town:market')), contains('"shop":"town:market"'));
    });

    test('should make valid documents of every other creatable kind', () {
      final JsonMap rig = newRig('town:golem');
      final Map<DocumentKind, (String, Object?)> made = <DocumentKind, (String, Object?)>{
        DocumentKind.pack: ('town', newPack('town', name: 'Town', description: 'Town behaviors')),
        DocumentKind.route: ('town-loop', newRoute(origin: (5, 64, 5))),
        DocumentKind.shop: ('town:market', newGuiDocument(DocumentKind.shop, 'town:market', title: '&6Market')),
        DocumentKind.inventory: ('town:board', newGuiDocument(DocumentKind.inventory, 'town:board', title: 'Board')),
        DocumentKind.menu: ('town:hub', newGuiDocument(DocumentKind.menu, 'town:hub', title: 'Hub')),
        DocumentKind.hologram: ('town:sign', newGuiDocument(DocumentKind.hologram, 'town:sign', title: 'Welcome')),
        DocumentKind.rig: ('town:golem', rig),
        DocumentKind.animation: ('town:golem/bob', newRigAnimation('town:golem/bob', 'town:golem', rig)),
        DocumentKind.textAnimation: ('town:blink', newTextAnimation()),
        DocumentKind.presets: ('presets', newPresets()),
      };
      for (final MapEntry<DocumentKind, (String, Object?)> entry in made.entries) {
        expect(errors(validate(entry.key, entry.value.$1, entry.value.$2)), isEmpty, reason: entry.key.wireName);
        expect(locateDocument(newDocumentPath(entry.key, entry.value.$1)), DocumentLocation(entry.key, entry.value.$1), reason: entry.key.wireName);
      }
      for (final RigStarter starter in RigStarter.values) {
        final JsonMap made = newRig('town:r', starter: starter, bodyShown: true);
        expect(errors(validate(DocumentKind.rig, 'town:r', made)), isEmpty, reason: starter.name);
        for (final AnimationStarter motion in AnimationStarter.values) {
          final AnimusDocument clip = validate(DocumentKind.animation, 'town:r/m', newRigAnimation('town:r/m', 'town:r', made, starter: motion));
          expect(errors(clip), isEmpty, reason: '${starter.name} ${motion.name}');
        }
      }
    });

    test('should name a skin by the SHA-256 of its file text and carry the model', () {
      final ({String id, String source}) slim = newSkin(slim: true, url: 'https://textures.minecraft.net/texture/abc');
      expect(slim.id, sha256.convert(utf8.encode(slim.source)).toString());
      expect(locateDocument('skins/${slim.id}.json'), DocumentLocation(DocumentKind.skin, slim.id));
      final JsonMap json = asMap(jsonDecode(slim.source));
      final String textures = utf8.decode(base64Decode(json['textureRaw']! as String));
      expect(textures, contains('"model":"slim"'));
      expect(textures, contains('textures.minecraft.net'));
      expect(json['signature'], isNull);
      expect(newSkin(slim: false).id, isNot(slim.id));
      expect(newSkin(slim: false, image: <int>[1, 2]).id, isNot(newSkin(slim: false, image: <int>[3]).id));
      expect(isResourcePath(skinImagePath(slim.id)), isTrue);
      expect(isResourcePath('editor/skins/x.png'), isFalse);
      expect(isMojangSkinUrl('https://textures.minecraft.net/texture/abc'), isTrue);
      expect(isMojangSkinUrl('https://example.com/skin.png'), isFalse);
    });

    test('should make a valid hologram from the template with its title as the first line', () {
      final JsonMap hologram = newGuiDocument(DocumentKind.hologram, 'town:sign', title: 'Hello');
      expect(asList(hologram['lines']).first, 'Hello');
    });
  });

  group('copies', () {
    test('should give a copied NPC its new location and a new UUID', () {
      final JsonMap npc = newNpc(const NpcSeed(name: 'Baker', group: 'town'), uuid: newUuid(random));
      final JsonMap copy = asMap(rewriteIdentity(DocumentKind.npc, npc, 'market/baker-copy', fresh: true, random: random));
      expect(copy['group'], 'market');
      expect(copy['id'], 'baker-copy');
      expect(copy['uuid'], isNot(npc['uuid']));
      expect(errors(validate(DocumentKind.npc, 'market/baker-copy', copy)), isEmpty);
      final JsonMap moved = asMap(rewriteIdentity(DocumentKind.npc, npc, 'market/baker', fresh: false));
      expect(moved['uuid'], npc['uuid']);
    });

    test('should rewrite the id field of namespaced kinds', () {
      for (final DocumentKind kind in <DocumentKind>[DocumentKind.behavior, DocumentKind.shop, DocumentKind.rig]) {
        final Object json = switch (kind) {
          DocumentKind.behavior => behaviorFromTemplate(BehaviorTemplate.greeter, 'a:b'),
          DocumentKind.shop => newGuiDocument(DocumentKind.shop, 'a:b'),
          _ => newRig('a:b'),
        };
        final Object? copy = rewriteIdentity(kind, json, 'a:c', fresh: true);
        expect(errors(validate(kind, 'a:c', copy)), isEmpty, reason: kind.wireName);
      }
      expect(copyId(DocumentKind.behavior, 'a:b', <String>{'a:b-copy'}), 'a:b-copy-2');
    });
  });

  group('fixes', () {
    test('should repair a broken NPC with the offered fixes', () {
      final JsonMap npc = newNpc(const NpcSeed(name: 'Baker', group: 'town'), uuid: newUuid(random));
      final JsonMap broken = <String, Object?>{...npc, 'uuid': 'not-a-uuid', 'id': 'wrong'}..remove('revision');
      final AnimusDocument document = validate(DocumentKind.npc, 'town/baker', broken);
      expect(errors(document), isNotEmpty);
      final List<DocumentFix> fixes = fixesFor(document, random: random);
      expect(fixes.map((DocumentFix fix) => fix.path), containsAll(<String>[r'$.uuid', r'$.id', r'$.revision']));
      final AnimusDocument repaired = validate(DocumentKind.npc, 'town/baker', applyFixes(document.json, fixes));
      expect(errors(repaired), isEmpty);
    });

    test('should offer a missing UUID with a short label', () {
      final JsonMap npc = newNpc(const NpcSeed(name: 'Baker', group: 'town'), uuid: newUuid(random))..remove('uuid');
      final List<DocumentFix> fixes = fixesFor(validate(DocumentKind.npc, 'town/baker', npc), random: random);
      expect(fixes.single.label, 'Add a new UUID');
    });

    test('should clamp a number outside its bounds and set a constant', () {
      final JsonMap rig = <String, Object?>{...newRig('a:b'), 'schemaVersion': 2};
      final JsonMap animation = <String, Object?>{...newRigAnimation('a:b/c', 'a:b', rig), 'durationTicks': 5000};
      final AnimusDocument document = validate(DocumentKind.animation, 'a:b/c', animation);
      final List<DocumentFix> fixes = fixesFor(document);
      expect(fixes.single.label, 'Use 1200');
      expect(errors(validate(DocumentKind.animation, 'a:b/c', applyFixes(document.json, fixes))), isEmpty);
      final AnimusDocument rigDocument = validate(DocumentKind.rig, 'a:b', <String, Object?>{...rig, 'colour': 'red'});
      expect(fixesFor(rigDocument).map((DocumentFix fix) => fix.label), <String>['Set schemaVersion to 1', 'Remove colour']);
      expect(errors(validate(DocumentKind.rig, 'a:b', applyFixes(rigDocument.json, fixesFor(rigDocument)))), isEmpty);
    });

    test('should parse diagnostic paths', () {
      expect(parseJsonPath(r'$.keys["#"].icon[2]'), <Object>['keys', '#', 'icon', 2]);
      expect(parseJsonPath(r'$'), isEmpty);
      expect(parseJsonPath('keys'), isNull);
    });
  });

  group('starter', () {
    test('should embed the current example packs', () {
      expect(renderStarterPacks(readStarterPacks(Directory('../examples/packs'))), File(starterPacksPath).readAsStringSync(),
          reason: 'run dart run tool/embed_starter_packs.dart');
    });

    test('should open the starter packs as a valid workspace with every pack', () {
      final DataFolderResult opened = openDataFolder(starterWorkspaceFiles(), SharedSchemas.embedded);
      expect(opened.ignored, isEmpty);
      expect(<AnimusDocument>[for (final AnimusDocument document in opened.documents) if (!document.valid) document], isEmpty);
      final Set<DocumentKind> kinds = <DocumentKind>{for (final AnimusDocument document in opened.documents) document.kind};
      expect(kinds, containsAll(<DocumentKind>[DocumentKind.npc, DocumentKind.behavior, DocumentKind.pack, DocumentKind.shop, DocumentKind.inventory]));
      expect(starterPacks().map(((String, String) pack) => pack.$1), contains('Greeter'));
      expect(opened.documents.where((AnimusDocument document) => document.kind == DocumentKind.pack).length, starterPacks().length);
    });
  });

  group('defaultNamespace', () {
    test('should use the namespace most workspace ids use, not the server name', () {
      expect(defaultNamespace(const <String>['town:greeting', 'harbor:goods', 'town:crier', 'town/baker'], 'Animus fake server', server: true), 'town');
    });

    test('should fall back to the NPC folder, the workspace name, or local for a server', () {
      expect(defaultNamespace(const <String>['examples/greeter'], 'Animus fake server', server: true), 'examples');
      expect(defaultNamespace(const <String>['3f9a1c'], 'Harbor Pack'), 'harbor-pack');
      expect(defaultNamespace(const <String>[], 'Animus fake server', server: true), 'local');
    });
  });
}
