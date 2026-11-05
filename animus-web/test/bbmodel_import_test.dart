import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:animus_editor/logic/bbmodel_import.dart';
import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/logic/data_folder_export.dart';
import 'package:animus_editor/logic/rig_edit.dart';
import 'package:animus_editor/logic/rig_sampler.dart';
import 'package:animus_editor/logic/rig_validation.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/mc/rig/rig_scene.dart';
import 'package:animus_editor/mc/scene/mc_scene.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/state/workspace_repository.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

Uint8List robotBytes() => File('test/fixtures/bbmodel/robot.bbmodel').readAsBytesSync();

JsonMap robotJson() => jsonDecode(utf8.decode(robotBytes())) as JsonMap;

Uint8List bytesOf(Object json) => Uint8List.fromList(utf8.encode(jsonEncode(json)));

JsonMap resourceJson(BbmodelImport imported, String path) => jsonDecode(utf8.decode(imported.resources[path]!)) as JsonMap;

void main() {
  group('bbmodel import', () {
    final BbmodelImport imported = importBbmodel(robotBytes(), rigId: 'town:robot');

    test('should turn every group into a bone, loose cubes into root, free rotations into bones', () {
      expect(<String>[for (final JsonMap bone in bonesOf(imported.rig)) '${bone['id']}'], <String>['root', 'body', 'head', 'arm', 'arm-blade']);
      expect(boneById(imported.rig, 'head')!['parent'], 'body');
      expect(boneById(imported.rig, 'arm-blade')!['parent'], 'arm');
      expect(boneById(imported.rig, 'arm-blade')!['rotation'], <Object?>[0, 0, -30]);
      expect(boneById(imported.rig, 'arm')!['rotation'], <Object?>[0, 0, 10]);
    });

    test('should flip pivots to face +Z and convert pixels to blocks', () {
      expect(boneById(imported.rig, 'body')!['pivot'], <Object?>[0, 0.5, 0]);
      expect(boneById(imported.rig, 'arm')!['pivot'], <Object?>[-0.3125, 1.25, 0]);
    });

    test('should pass the rig and animation schemas and structure rules', () {
      expect(SharedSchemas.embedded.validate(DocumentKind.rig, imported.rig), isEmpty);
      expect(validateRigStructure(imported.rig), isEmpty);
      for (final JsonMap clip in imported.animations) {
        expect(SharedSchemas.embedded.validate(DocumentKind.animation, clip), isEmpty, reason: '${clip['id']}');
        expect(validateAnimationStructure(clip, imported.rig), isEmpty, reason: '${clip['id']}');
      }
      expect(imported.rig['animations'], <String, Object?>{'walk': 'town:robot/walk', 'idle': 'town:robot/idle'});
    });

    test('should write an item definition and model per bone with cubes', () {
      final JsonMap visual = boneById(imported.rig, 'head')!['visual']! as JsonMap;
      expect(visual['type'], 'item');
      expect(itemModelOf(visual['item']), 'animus:robot/head');
      expect(resourceJson(imported, 'pack/assets/animus/items/robot/head.json'), <String, Object?>{
        'model': <String, Object?>{'type': 'minecraft:model', 'model': 'animus:item/robot/head'},
      });
      final JsonMap model = resourceJson(imported, 'pack/assets/animus/models/item/robot/head.json');
      final List<Object?> elements = model['elements']! as List<Object?>;
      expect(elements, hasLength(2));
      final JsonMap skull = elements.first! as JsonMap;
      expect(skull['from'], <Object?>[4, 8, 4]);
      expect(skull['to'], <Object?>[12, 16, 12]);
      expect(((skull['faces']! as JsonMap)['north']! as JsonMap)['uv'], <Object?>[0, 0, 4, 4]);
      final JsonMap visor = elements.last! as JsonMap;
      expect(visor['rotation'], <String, Object?>{'angle': 22.5, 'axis': 'y', 'origin': <Object?>[8, 11, 4]});
      expect(model['textures'], <String, Object?>{'0': 'animus:item/robot/robot', 'particle': 'animus:item/robot/robot'});
      expect(boneById(imported.rig, 'arm')!.containsKey('visual'), isFalse, reason: 'its only cube moved to arm-blade');
    });

    test('should skip faces without a texture and keep the embedded PNG', () {
      final JsonMap base = (resourceJson(imported, 'pack/assets/animus/models/item/robot/root.json')['elements']! as List<Object?>).single! as JsonMap;
      expect((base['faces']! as JsonMap).keys, <String>['up']);
      final Uint8List png = imported.resources['pack/assets/animus/textures/item/robot/robot.png']!;
      expect(pngSize(png), (32, 32));
      expect(imported.resources.keys.every(isResourcePath), isTrue);
    });

    test('should convert animations to ticks, Animus axes and easings', () {
      final JsonMap walk = imported.animations.first;
      expect(walk['id'], 'town:robot/walk');
      expect(walk['durationTicks'], 20);
      expect(walk['loop'], 'loop');
      final JsonMap rotation = tracksOf(walk).firstWhere((JsonMap track) => track['bone'] == 'head');
      expect(rotation['target'], 'rotation');
      expect(keyframesOf(rotation)[1], <String, Object?>{'tick': 10, 'value': <Object?>[10, -20, -5], 'easing': 'ease_in_out'});
      final JsonMap position = tracksOf(walk).firstWhere((JsonMap track) => track['target'] == 'translation');
      expect(keyframesOf(position).first['easing'], 'hold');
      expect(keyframesOf(position)[1]['value'], <Object?>[0.0625, 0.125, -0.1875]);
      final JsonMap idle = imported.animations.last;
      expect(idle['durationTicks'], 10);
      expect(idle['loop'], 'once');
      expect(tracksOf(idle), isEmpty);
    });

    test('should report what it could not carry over', () {
      expect(imported.warnings.join('\n'), allOf(contains('Molang'), contains('not cubes'), contains('catmullrom')));
    });

    test('should sample and draw the imported rig from its own resources', () {
      final Rig rig = Rig.fromJson(imported.rig);
      final Map<String, BonePose> poses = sampleRig(rig, <Layer>[Layer(Clip.fromJson(imported.animations.first))], 0, 5);
      final RigScene scene = rigScene(imported.rig, poses, resources: imported.resources, textureId: (String path) => 'id:$path');
      expect(scene.nodes.whereType<McModelNode>().map((McModelNode node) => node.key).toSet(), <String>{
        'bone:root:0',
        'bone:body:0',
        'bone:head:0',
        'bone:arm-blade:0',
      });
      expect(scene.nodes.whereType<McModelNode>().first.textureId, 'id:pack/assets/animus/textures/item/robot/robot.png');
      expect(scene.notes, isEmpty);
    });

    test('should scale a group too large for an item model and undo it on the display', () {
      final JsonMap model = robotJson();
      ((model['elements']! as List<Object?>).first! as JsonMap)['to'] = <Object?>[60, 20, 2];
      final BbmodelImport big = importBbmodel(bytesOf(model), rigId: 'town:robot');
      final JsonMap visual = boneById(big.rig, 'body')!['visual']! as JsonMap;
      expect(visual['scale'], isA<List<Object?>>());
      expect(((visual['scale']! as List<Object?>).first! as num).toDouble(), greaterThan(1));
      for (final Object? element in resourceJson(big, 'pack/assets/animus/models/item/robot/body.json')['elements']! as List<Object?>) {
        for (final String corner in <String>['from', 'to']) {
          for (final Object? value in (element! as JsonMap)[corner]! as List<Object?>) {
            expect((value! as num).toDouble(), inInclusiveRange(-16, 32));
          }
        }
      }
      expect(big.warnings.join(), contains('scaled'));
    });

    test('should refuse files that break the bounds', () {
      expect(() => importBbmodel(Uint8List.fromList(utf8.encode('not json')), rigId: 'town:robot'), throwsA(isA<BbmodelImportException>()));
      expect(() => importBbmodel(bytesOf(<String, Object?>{'elements': <Object?>[]}), rigId: 'town:robot'), throwsA(isA<BbmodelImportException>()));
      expect(() => importBbmodel(robotBytes(), rigId: 'Not An Id'), throwsA(isA<BbmodelImportException>()));
      expect(() => importBbmodel(Uint8List(BbmodelLimits.fileBytes + 1), rigId: 'town:robot'), throwsA(isA<BbmodelImportException>()));
      final JsonMap textures = robotJson()..['textures'] = <Object?>[for (int index = 0; index < 17; index++) <String, Object?>{}];
      expect(() => importBbmodel(bytesOf(textures), rigId: 'town:robot'), throwsA(isA<BbmodelImportException>()));
      final JsonMap notPng = robotJson();
      ((notPng['textures']! as List<Object?>).first! as JsonMap)['source'] = 'data:image/png;base64,${base64Encode(utf8.encode('plain text, not an image'))}';
      expect(() => importBbmodel(bytesOf(notPng), rigId: 'town:robot'), throwsA(isA<BbmodelImportException>()));
      final JsonMap groups = robotJson()
        ..['outliner'] = <Object?>[
          for (int index = 0; index < 65; index++) <String, Object?>{'name': 'g$index', 'origin': <Object?>[0, 0, 0], 'children': <Object?>[]},
        ];
      expect(() => importBbmodel(bytesOf(groups), rigId: 'town:robot'), throwsA(isA<BbmodelImportException>()));
    });

    test('should read Blockbench 5 group lists by uuid', () {
      final JsonMap model = robotJson()
        ..['groups'] = <Object?>[
          <String, Object?>{'uuid': 'g-solo', 'name': 'Solo', 'origin': <Object?>[0, 16, 0], 'rotation': <Object?>[0, 0, 0]},
        ]
        ..['outliner'] = <Object?>[
          <String, Object?>{'uuid': 'g-solo', 'children': <Object?>['c-head']},
        ];
      final BbmodelImport solo = importBbmodel(bytesOf(model), rigId: 'town:solo');
      expect(boneById(solo.rig, 'solo')!['pivot'], <Object?>[0, 1, 0]);
    });
  });

  group('rig scene', () {
    final JsonMap golem = readFixture('animation/rigs/golem.json')! as JsonMap;

    test('should draw item, block and text bones as the client draws displays', () {
      final Map<String, BonePose> poses = sampleRig(Rig.fromJson(golem), const <Layer>[], 0, 0);
      final RigScene scene = rigScene(golem, poses);
      expect(scene.nodes.whereType<McBlockNode>().single.blockKey, 'carved_pumpkin');
      expect(scene.nodes.whereType<McItemNode>().map((McItemNode node) => node.itemId), <String>['paper', 'stick']);
      expect(scene.texts.single.lines, <String>['\u00a76Golem']);
      expect(scene.texts.single.anchor.y, closeTo(poses['label']!.visual!.m[7], 1e-9));
      expect(scene.notes.single, contains('animus:golem/torso'));
    });

    test('should leave out hidden bones', () {
      final Map<String, BonePose> poses = sampleRig(
        Rig.fromJson(golem),
        <Layer>[Layer(Clip.fromJson(readFixture('animation/clips/golem-wave.json')! as JsonMap))],
        0,
        7,
      );
      final RigScene scene = rigScene(golem, poses);
      expect(scene.nodes.whereType<McBlockNode>(), isEmpty);
      expect(scene.texts, isEmpty);
    });

    test('should turn item displays 180 degrees about Y', () {
      final Map<String, BonePose> poses = sampleRig(Rig.fromJson(golem), const <Layer>[], 0, 0);
      final McItemNode stick = rigScene(golem, poses).nodes.whereType<McItemNode>().last;
      final List<double> visual = poses['arm']!.visual!.m;
      expect(stick.transform.m[0], closeTo(-visual[0], 1e-9));
      expect(stick.transform.m[12], closeTo(visual[3], 1e-9));
    });
  });

  group('export', () {
    final BbmodelImport imported = importBbmodel(robotBytes(), rigId: 'town:robot');
    final WorkspaceSnapshot workspace = WorkspaceSnapshot(
      id: 'ws',
      name: 'Robots',
      generation: 1,
      documents: <AnimusDocument>[
        validateSource(const DocumentLocation(DocumentKind.rig, 'town:robot'), 'rigs/town/robot.json', encodeDocument(imported.rig), SharedSchemas.embedded),
        for (final JsonMap clip in imported.animations)
          validateSource(
            DocumentLocation(DocumentKind.animation, clip['id']! as String),
            'animations/town/${(clip['id']! as String).substring(5)}.json',
            encodeDocument(clip),
            SharedSchemas.embedded,
          ),
      ],
      resources: <String, Uint8List>{...imported.resources, 'pack/assets/animus/items/other.json': Uint8List.fromList(utf8.encode('{}'))},
    );

    test('should zip a workspace reproducibly and open it again', () {
      final Uint8List first = zipFiles(workspaceFiles(workspace));
      final Uint8List second = zipFiles(workspaceFiles(workspace));
      expect(first, second);
      final DataFolderResult opened = openDataFolder(filesFromZip(first), SharedSchemas.embedded);
      expect(opened.documents.map((AnimusDocument document) => document.id), containsAll(<String>['town:robot', 'town:robot/walk']));
      expect(opened.documents.every((AnimusDocument document) => document.valid), isTrue);
      expect(opened.resources.keys.toSet(), workspace.resources.keys.toSet());
    });

    test('should bundle a rig with its animations and only the pack files it uses', () {
      final RigBundle bundle = rigBundle(workspace, 'town:robot');
      expect(bundle.missing, isEmpty);
      expect(bundle.files.keys, containsAll(<String>[
        'rigs/town/robot.json',
        'animations/town/robot/walk.json',
        'pack/assets/animus/items/robot/head.json',
        'pack/assets/animus/models/item/robot/head.json',
        'pack/assets/animus/textures/item/robot/robot.png',
      ]));
      expect(bundle.files.keys, isNot(contains('pack/assets/animus/items/other.json')));
    });

    test('should list item models the workspace does not hold', () {
      final JsonMap golem = readFixture('animation/rigs/golem.json')! as JsonMap;
      final WorkspaceSnapshot only = WorkspaceSnapshot(id: 'g', name: 'g', generation: 1, documents: <AnimusDocument>[
        validateSource(const DocumentLocation(DocumentKind.rig, 'town:golem'), 'rigs/town/golem.json', encodeDocument(golem), SharedSchemas.embedded),
      ]);
      expect(rigBundle(only, 'town:golem').missing, <String>['animus:golem/torso']);
    });
  });
}
