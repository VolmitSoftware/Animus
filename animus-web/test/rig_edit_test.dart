import 'dart:convert';
import 'dart:typed_data';

import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/logic/rig_edit.dart';
import 'package:animus_editor/logic/rig_sampler.dart';
import 'package:animus_editor/logic/rig_validation.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/state/editor_store.dart';
import 'package:animus_editor/state/workspace_repository.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

JsonMap golem() => cloneJson(readFixture('animation/rigs/golem.json')! as JsonMap);

JsonMap walk() => cloneJson(readFixture('animation/clips/golem-walk.json')! as JsonMap);

List<String> ids(JsonMap rig) => <String>[for (final JsonMap bone in bonesOf(rig)) '${bone['id']}'];

void expectValidRig(JsonMap rig) {
  expect(SharedSchemas.embedded.validate(DocumentKind.rig, rig), isEmpty);
  expect(validateRigStructure(rig), isEmpty);
}

void main() {
  group('bone tree', () {
    test('should add a child after its parent subtree with the parent pivot', () {
      final JsonMap rig = addBone(golem(), 'hat', parent: 'head');
      expect(ids(rig), <String>['root', 'torso', 'head', 'arm', 'label', 'hat']);
      expect(boneById(rig, 'hat')!['pivot'], <Object?>[0, 1.6, 0]);
      expect(boneById(rig, 'hat')!['parent'], 'head');
      expectValidRig(rig);
    });

    test('should add a bone at the rig origin when no parent is given', () {
      final JsonMap rig = addBone(golem(), 'tail');
      expect(ids(rig).last, 'tail');
      expect(boneById(rig, 'tail')!.containsKey('parent'), isFalse);
    });

    test('should refuse duplicate ids, bad ids and the 64 bone limit', () {
      expect(() => addBone(golem(), 'head'), throwsA(isA<RigEditException>()));
      expect(() => addBone(golem(), 'Bad Id'), throwsA(isA<RigEditException>()));
      JsonMap rig = golem();
      for (int index = bonesOf(rig).length; index < maximumBones; index++) {
        rig = addBone(rig, 'extra-$index');
      }
      expect(() => addBone(rig, 'one-more'), throwsA(isA<RigEditException>()));
    });

    test('should refuse nesting deeper than 16', () {
      JsonMap rig = <String, Object?>{'schemaVersion': 1, 'revision': 1, 'id': 'a:b', 'bones': <Object?>[<String, Object?>{'id': 'b1', 'pivot': <Object?>[0, 0, 0]}]};
      for (int depth = 2; depth <= maximumBoneDepth; depth++) {
        rig = addBone(rig, 'b$depth', parent: 'b${depth - 1}');
      }
      expectValidRig(rig);
      expect(() => addBone(rig, 'b17', parent: 'b16'), throwsA(isA<RigEditException>()));
    });

    test('should rename a bone with its children, sockets and animation tracks', () {
      final JsonMap rig = renameBone(golem(), 'torso', 'chest');
      expect(ids(rig), contains('chest'));
      expect(boneById(rig, 'head')!['parent'], 'chest');
      expect(boneById(rig, 'arm')!['parent'], 'chest');
      final JsonMap renamed = renameBone(rig, 'arm', 'right-arm');
      expect((renamed['sockets']! as JsonMap)['main_hand'], 'right-arm');
      final JsonMap clip = renameTrackBone(walk(), 'torso', 'chest');
      expect(tracksOf(clip).map((JsonMap track) => track['bone']), isNot(contains('torso')));
      expect(() => renameBone(golem(), 'torso', 'head'), throwsA(isA<RigEditException>()));
      expectValidRig(renamed);
    });

    test('should reparent a subtree and keep parents before children', () {
      final JsonMap rig = reparentBone(golem(), 'head', 'arm');
      expect(boneById(rig, 'head')!['parent'], 'arm');
      expect(ids(rig).indexOf('arm'), lessThan(ids(rig).indexOf('head')));
      expect(ids(rig).indexOf('head'), lessThan(ids(rig).indexOf('label')));
      expectValidRig(rig);
      final JsonMap root = reparentBone(golem(), 'arm', null);
      expect(boneById(root, 'arm')!.containsKey('parent'), isFalse);
      expectValidRig(root);
    });

    test('should refuse to hang a bone under itself or its subtree', () {
      expect(() => reparentBone(golem(), 'torso', 'label'), throwsA(isA<RigEditException>()));
      expect(() => reparentBone(golem(), 'torso', 'torso'), throwsA(isA<RigEditException>()));
    });

    test('should keep the rest pose when reparenting, since pivots are rig space', () {
      final JsonMap before = golem()..['bones'] = <Object?>[
        for (final JsonMap bone in bonesOf(golem())) <String, Object?>{...bone}..remove('rotation'),
      ];
      final JsonMap after = reparentBone(before, 'arm', 'head');
      final Map<String, BonePose> a = sampleRig(Rig.fromJson(before), const <Layer>[], 0, 0);
      final Map<String, BonePose> b = sampleRig(Rig.fromJson(after), const <Layer>[], 0, 0);
      for (int index = 0; index < 12; index++) {
        expect(b['arm']!.model.m[index], closeTo(a['arm']!.model.m[index], 1e-9));
      }
    });

    test('should delete a bone, rehanging children and dropping its sockets', () {
      final JsonMap rig = deleteBone(golem(), 'torso');
      expect(ids(rig), isNot(contains('torso')));
      expect(boneById(rig, 'head')!['parent'], 'root');
      final JsonMap noArm = deleteBone(golem(), 'arm');
      expect(noArm.containsKey('sockets'), isFalse);
      expectValidRig(rig);
      final JsonMap clip = dropTrackBone(walk(), 'torso');
      expect(tracksOf(clip).where((JsonMap track) => track['bone'] == 'torso'), isEmpty);
      final JsonMap single = <String, Object?>{'bones': <Object?>[<String, Object?>{'id': 'only', 'pivot': <Object?>[0, 0, 0]}]};
      expect(() => deleteBone(single, 'only'), throwsA(isA<RigEditException>()));
    });

    test('should switch visual types keeping the transform fields', () {
      final JsonMap item = boneById(golem(), 'arm')!['visual']! as JsonMap;
      final JsonMap block = visualOfType('block', item);
      expect(block['type'], 'block');
      expect(block['block'], 'minecraft:stone');
      expect(block['scale'], item['scale']);
      expect(block.containsKey('item'), isFalse);
      expect(visualOfType('none', item), <String, Object?>{'type': 'none'});
      final JsonMap rig = updateBone(golem(), 'arm', (JsonMap bone) => bone['visual'] = visualOfType('text', item));
      expectValidRig(rig);
    });
  });

  group('keyframes', () {
    test('should insert keyframes in tick order and replace one at the same tick', () {
      JsonMap clip = setKeyframe(walk(), 'torso', TrackTarget.rotation, 5, <Object?>[0, 0, 1], easing: 'ease_out');
      List<JsonMap> keys = keyframesOf(tracksOf(clip).first);
      expect(keys.map((JsonMap key) => key['tick']), <Object?>[0, 5, 10, 20]);
      expect(keys[1]['easing'], 'ease_out');
      clip = setKeyframe(clip, 'torso', TrackTarget.rotation, 5, <Object?>[0, 0, 2]);
      keys = keyframesOf(tracksOf(clip).first);
      expect(keys[1]['value'], <Object?>[0, 0, 2]);
      expect(keys[1]['easing'], 'ease_out');
      expect(SharedSchemas.embedded.validate(DocumentKind.animation, clip), isEmpty);
      expect(validateAnimationStructure(clip, golem()), isEmpty);
    });

    test('should create a replace track for a new bone and target', () {
      final JsonMap clip = setKeyframe(walk(), 'arm', TrackTarget.visible, 3.5, false);
      final JsonMap track = tracksOf(clip).last;
      expect(track['bone'], 'arm');
      expect(track['target'], 'visible');
      expect(track['blend'], 'replace');
      expect(keyframesOf(track).single, <String, Object?>{'tick': 3.5, 'value': false});
      expect(SharedSchemas.embedded.validate(DocumentKind.animation, clip), isEmpty);
    });

    test('should refuse ticks outside the duration and values of the wrong shape', () {
      expect(() => setKeyframe(walk(), 'torso', TrackTarget.rotation, 21, <Object?>[0, 0, 0]), throwsA(isA<RigEditException>()));
      expect(() => setKeyframe(walk(), 'torso', TrackTarget.visible, 2, <Object?>[0, 0, 0]), throwsA(isA<RigEditException>()));
      expect(() => setKeyframe(walk(), 'torso', TrackTarget.rotation, 2, true), throwsA(isA<RigEditException>()));
    });

    test('should set, clear and refuse easings', () {
      JsonMap clip = setKeyframeEasing(walk(), 0, 0, 'bezier(0.25,0.1,0.25,1)');
      expect(keyframesOf(tracksOf(clip).first).first['easing'], 'bezier(0.25,0.1,0.25,1)');
      clip = setKeyframeEasing(clip, 0, 0, 'linear');
      expect(keyframesOf(tracksOf(clip).first).first.containsKey('easing'), isFalse);
      expect(() => setKeyframeEasing(walk(), 0, 0, 'wobbly'), throwsFormatException);
      expect(SharedSchemas.embedded.validate(DocumentKind.animation, clip), isEmpty);
    });

    test('should move and remove keyframes, dropping an emptied track', () {
      JsonMap clip = moveKeyframe(walk(), 0, 1, 15);
      expect(keyframesOf(tracksOf(clip).first).map((JsonMap key) => key['tick']), <Object?>[0, 15, 20]);
      expect(() => moveKeyframe(walk(), 0, 1, 20), throwsA(isA<RigEditException>()));
      final int tracks = tracksOf(walk()).length;
      clip = walk();
      while (keyframesOf(tracksOf(clip).first).isNotEmpty && tracksOf(clip).length == tracks) {
        clip = removeKeyframe(clip, 0, 0);
      }
      expect(tracksOf(clip).length, tracks - 1);
    });

    test('should read a track value with the shared sampler', () {
      final JsonMap track = tracksOf(walk()).first;
      final List<double> at5 = trackValueAt(track, 5)! as List<double>;
      expect(at5[2], closeTo(-4 + 8 * Easing.parse('ease_in_out').apply(0.5), 1e-9));
      expect(trackValueAt(<String, Object?>{'target': 'rotation', 'keyframes': <Object?>[]}, 0), isNull);
    });

    test('should make a new animation that passes the schema', () {
      final JsonMap clip = newAnimation('town:golem/spin', 'town:golem');
      expect(SharedSchemas.embedded.validate(DocumentKind.animation, clip), isEmpty);
      expect(documentIdPattern.hasMatch('town:golem/spin'), isTrue);
    });
  });

  group('store commits', () {
    late EditorStore store;

    setUp(() async {
      store = EditorStore(repository: MemoryWorkspaceRepository(), schemas: SharedSchemas.embedded, newId: () => 'ws-rig');
      await store.openFiles('rig', <String, Uint8List>{
        'rigs/town/golem.json': Uint8List.fromList(utf8.encode(encodeDocument(golem()))),
        'animations/town/golem/walk.json': Uint8List.fromList(utf8.encode(encodeDocument(walk()))),
        'pack/assets/animus/items/golem/torso.json': Uint8List.fromList(utf8.encode('{"model":{"type":"minecraft:model","model":"animus:item/golem/torso"}}')),
      });
    });

    test('should keep pack sources as workspace resources', () {
      expect(store.workspace!.resources.keys, <String>['pack/assets/animus/items/golem/torso.json']);
    });

    test('should write a rig and its animations as one undo step', () async {
      final JsonMap rig = renameBone(golem(), 'torso', 'chest');
      await store.commit(documents: <DocumentChange>[
        DocumentChange(DocumentKind.rig, 'town:golem', encodeDocument(rig)),
        DocumentChange(DocumentKind.animation, 'town:golem/walk', encodeDocument(renameTrackBone(walk(), 'torso', 'chest'))),
      ]);
      expect(store.document(DocumentKind.rig, 'town:golem')!.valid, isTrue);
      expect(store.document(DocumentKind.animation, 'town:golem/walk')!.valid, isTrue);
      await store.undo();
      expect(store.document(DocumentKind.rig, 'town:golem')!.source, contains('"torso"'));
      expect(store.canUndo, isFalse);
    });

    test('should flag animations whose bones a rig edit removed', () async {
      await store.commit(documents: <DocumentChange>[
        DocumentChange(DocumentKind.rig, 'town:golem', encodeDocument(deleteBone(golem(), 'torso'))),
      ]);
      final AnimusDocument clip = store.document(DocumentKind.animation, 'town:golem/walk')!;
      expect(clip.diagnostics.map((Diagnostic diagnostic) => diagnostic.code), contains('animation-bone'));
    });

    test('should add new documents at their data-folder path and remove them', () async {
      await store.commit(documents: <DocumentChange>[
        DocumentChange(DocumentKind.animation, 'town:golem/spin', encodeDocument(newAnimation('town:golem/spin', 'town:golem'))),
      ], resources: <String, Uint8List?>{'pack/assets/animus/items/golem/torso.json': null});
      final AnimusDocument created = store.document(DocumentKind.animation, 'town:golem/spin')!;
      expect(created.path, 'animations/town/golem/spin.json');
      expect(created.valid, isTrue);
      expect(store.workspace!.resources, isEmpty);
      await store.undo();
      expect(store.document(DocumentKind.animation, 'town:golem/spin'), isNull);
      expect(store.workspace!.resources, hasLength(1));
      expect(documentPath(DocumentKind.rig, 'town:golem'), 'rigs/town/golem.json');
    });

    test('should share one undo step across commits with the same coalesce key', () async {
      for (final double y in <double>[1.2, 1.3, 1.4]) {
        await store.commit(
          documents: <DocumentChange>[
            DocumentChange(DocumentKind.rig, 'town:golem', encodeDocument(updateBone(golem(), 'torso', (JsonMap bone) => bone['pivot'] = <Object?>[0, y, 0]))),
          ],
          coalesce: 'pivot:torso',
        );
      }
      await store.undo();
      expect(store.canUndo, isFalse);
      expect(store.document(DocumentKind.rig, 'town:golem')!.json, golem());
    });

    test('should run concurrent commits in order without a conflict', () async {
      await Future.wait(<Future<void>>[
        for (final double y in <double>[1.2, 1.3, 1.4])
          store.commit(documents: <DocumentChange>[
            DocumentChange(DocumentKind.rig, 'town:golem', encodeDocument(updateBone(golem(), 'torso', (JsonMap bone) => bone['pivot'] = <Object?>[0, y, 0]))),
          ]),
      ]);
      expect(store.problem, isNull);
      expect(boneById(store.document(DocumentKind.rig, 'town:golem')!.json! as JsonMap, 'torso')!['pivot'], <Object?>[0, 1.4, 0]);
    });

    test('should keep resources through a stored round trip', () async {
      final WorkspaceSnapshot decoded = (await store.repository.load(store.workspace!.id))!;
      expect(decoded.resources.keys, store.workspace!.resources.keys);
      expect(isResourcePath('pack/assets/animus/textures/item/a.png'), isTrue);
      expect(isResourcePath('pack/out/pack.zip'), isFalse);
    });
  });
}
