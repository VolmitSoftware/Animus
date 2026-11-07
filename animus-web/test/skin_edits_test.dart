import 'dart:convert';
import 'dart:typed_data';

import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/npc/skin_edits.dart';
import 'package:animus_editor/logic/offline/new_documents.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/mc/npc/npc_skin.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/state/editor_store.dart';
import 'package:animus_editor/state/workspace_repository.dart';
import 'package:crypto/crypto.dart';
import 'package:test/test.dart';

AnimusDocument _doc(DocumentKind kind, String id, Object? json, {String? source}) =>
    AnimusDocument(kind: kind, id: id, path: newDocumentPath(kind, id), source: source ?? prettyJson(json), json: json, diagnostics: const <Diagnostic>[]);

AnimusDocument _skin(({String id, String source}) made) => _doc(DocumentKind.skin, made.id, jsonDecode(made.source), source: made.source);

AnimusDocument _npc(String id, {String? skin, String type = 'minecraft:player', List<String>? groups}) => _doc(DocumentKind.npc, id, <String, Object?>{
      'id': id.split('/').last,
      'type': type,
      'skin': ?skin,
      'groups': ?groups,
    });

AnimusDocument _group(String id, JsonMap values) => _doc(DocumentKind.group, id, <String, Object?>{'schemaVersion': 1, 'values': values});

/// Applies [change]'s documents to [documents] the way the store does.
Map<String, Object?> _apply(List<AnimusDocument> documents, SkinChange change) {
  final Map<String, Object?> out = <String, Object?>{for (final AnimusDocument d in documents) '${d.kind.wireName}:${d.id}': d.json};
  for (final DocumentChange c in change.documents) {
    final String key = '${c.kind.wireName}:${c.id}';
    if (c.update != null) {
      if (out.containsKey(key)) out[key] = c.update!(out[key]);
    } else if (c.source == null) {
      out.remove(key);
    } else {
      out[key] = jsonDecode(c.source!);
    }
  }
  return out;
}

String _sha(String text) => sha256.convert(utf8.encode(text)).toString();

void main() {
  final Uint8List png = Uint8List.fromList(List<int>.generate(64, (int i) => i));
  final ({String id, String source}) classic = newSkin(slim: false, image: png);

  group('skin labels', () {
    test('a label is read trimmed and a blank one is no label', () {
      expect(skinDocumentLabel(<String, Object?>{'textureRaw': 'e30=', 'label': ' Notch '}), 'Notch');
      expect(skinDocumentLabel(<String, Object?>{'textureRaw': 'e30=', 'label': '  '}), isNull);
      expect(skinDocumentLabel(<String, Object?>{'textureRaw': 'e30='}), isNull);
    });

    test('a model change keeps the label in the new document', () {
      final JsonMap labelled = <String, Object?>{...asMap(jsonDecode(classic.source)), 'label': 'Town guard'};
      final ({String id, String source})? slim = skinWithModel(labelled, slim: true);
      expect(skinDocumentLabel(jsonDecode(slim!.source)), 'Town guard');
      expect(slim.id, _sha(slim.source));
    });
  });

  group('skinWithModel', () {
    test('a model change makes a new document whose id is the hash of its text', () {
      final ({String id, String source})? slim = skinWithModel(jsonDecode(classic.source), slim: true);
      expect(slim, isNotNull);
      expect(slim!.id, isNot(classic.id));
      expect(slim.id, _sha(slim.source));
      expect(NpcSkinInfo.fromDocument(jsonDecode(slim.source))!.slim, isTrue);
      final ({String id, String source})? back = skinWithModel(jsonDecode(slim.source), slim: false);
      expect(NpcSkinInfo.fromDocument(jsonDecode(back!.source))!.slim, isFalse);
    });

    test('keeps the texture url and profile', () {
      final ({String id, String source}) hosted = newSkin(slim: false, url: 'https://textures.minecraft.net/texture/abc', image: png);
      final ({String id, String source})? slim = skinWithModel(jsonDecode(hosted.source), slim: true);
      expect(NpcSkinInfo.fromDocument(jsonDecode(slim!.source))!.textureUrl, 'https://textures.minecraft.net/texture/abc');
    });

    test('refuses a signed skin and an unreadable one', () {
      final JsonMap signed = <String, Object?>{...asMap(jsonDecode(classic.source)), 'signature': 'abc'};
      expect(skinWithModel(signed, slim: true), isNull);
      expect(skinWithModel(<String, Object?>{'textureRaw': '%%%'}, slim: true), isNull);
      expect(skinEditBlock(signed), contains('Signed'));
      expect(skinEditBlock(jsonDecode(classic.source)), isNull);
    });
  });

  group('skinWearers', () {
    test('lists NPCs and groups that name the hash, by skin or pool', () {
      final List<AnimusDocument> documents = <AnimusDocument>[
        _skin(classic),
        _npc('town/a', skin: classic.id),
        _npc('town/b'),
        _group('guards', <String, Object?>{'skin': classic.id}),
        _group('crowd', <String, Object?>{
          'skins': <Object?>[classic.id, 'f' * 64],
        }),
        _group('other', <String, Object?>{'skin': 'f' * 64}),
      ];
      expect(<String>[for (final AnimusDocument d in skinWearers(documents, classic.id)) '${d.kind.wireName}:${d.id}'], <String>['npc:town/a', 'group:guards', 'group:crowd']);
    });
  });

  group('setSkinModel', () {
    test('from the skin document moves every wearer and drops the old document', () {
      final List<AnimusDocument> documents = <AnimusDocument>[
        _skin(classic),
        _npc('town/a', skin: classic.id),
        _npc('town/b', skin: classic.id),
        _group('crowd', <String, Object?>{
          'skins': <Object?>[classic.id, 'f' * 64],
        }),
      ];
      final SkinChange change = setSkinModel(documents: documents, resources: <String, Uint8List>{skinImagePath(classic.id): png}, hash: classic.id, slim: true)!;
      final String next = change.skin!;
      final Map<String, Object?> after = _apply(documents, change);
      expect(after.containsKey('skin:${classic.id}'), isFalse);
      expect(NpcSkinInfo.fromDocument(after['skin:$next'])!.slim, isTrue);
      expect(asMap(after['npc:town/a'])['skin'], next);
      expect(asMap(after['npc:town/b'])['skin'], next);
      expect(asMap(asMap(after['group:crowd'])['values'])['skins'], <Object?>[next, 'f' * 64]);
      expect(change.resources[skinImagePath(next)], png);
      expect(change.resources.containsKey(skinImagePath(classic.id)), isTrue);
      expect(change.resources[skinImagePath(classic.id)], isNull);
    });

    test('from one NPC moves only that NPC and keeps the document others wear', () {
      final List<AnimusDocument> documents = <AnimusDocument>[
        _skin(classic),
        _npc('town/a', skin: classic.id),
        _npc('town/b', skin: classic.id),
      ];
      final SkinChange change = setSkinModel(
        documents: documents,
        resources: <String, Uint8List>{skinImagePath(classic.id): png},
        hash: classic.id,
        slim: true,
        only: const SkinTarget(DocumentKind.npc, 'town/a'),
      )!;
      final Map<String, Object?> after = _apply(documents, change);
      expect(asMap(after['npc:town/a'])['skin'], change.skin);
      expect(asMap(after['npc:town/b'])['skin'], classic.id);
      expect(after.containsKey('skin:${classic.id}'), isTrue);
      expect(change.resources[skinImagePath(classic.id)], isNull, reason: 'the old PNG stays: no entry');
      expect(change.resources.containsKey(skinImagePath(classic.id)), isFalse);
      expect(change.resources[skinImagePath(change.skin!)], png);
    });

    test('from the only wearer drops the old document', () {
      final List<AnimusDocument> documents = <AnimusDocument>[_skin(classic), _npc('town/a', skin: classic.id)];
      final SkinChange change = setSkinModel(documents: documents, resources: const <String, Uint8List>{}, hash: classic.id, slim: true, only: const SkinTarget(DocumentKind.npc, 'town/a'))!;
      final Map<String, Object?> after = _apply(documents, change);
      expect(after.containsKey('skin:${classic.id}'), isFalse);
      expect(asMap(after['npc:town/a'])['skin'], change.skin);
    });

    test('toggling back reuses an existing document instead of writing it again', () {
      final ({String id, String source}) slim = skinWithModel(jsonDecode(classic.source), slim: true)!;
      final List<AnimusDocument> documents = <AnimusDocument>[_skin(classic), _skin(slim), _npc('town/a', skin: slim.id), _npc('town/b', skin: classic.id)];
      final SkinChange change = setSkinModel(documents: documents, resources: const <String, Uint8List>{}, hash: slim.id, slim: false, only: const SkinTarget(DocumentKind.npc, 'town/a'))!;
      expect(change.skin, classic.id);
      expect(change.documents.where((DocumentChange c) => c.kind == DocumentKind.skin && c.source != null), isEmpty);
    });

    test('a group value follows when edited from the group', () {
      final List<AnimusDocument> documents = <AnimusDocument>[_skin(classic), _group('guards', <String, Object?>{'skin': classic.id})];
      final SkinChange change = setSkinModel(documents: documents, resources: const <String, Uint8List>{}, hash: classic.id, slim: true, only: const SkinTarget(DocumentKind.group, 'guards'))!;
      final Map<String, Object?> after = _apply(documents, change);
      expect(asMap(asMap(after['group:guards'])['values'])['skin'], change.skin);
    });

    test('no change for the same model, a signed skin or a skin not in the workspace', () {
      final List<AnimusDocument> documents = <AnimusDocument>[_skin(classic)];
      expect(setSkinModel(documents: documents, resources: const <String, Uint8List>{}, hash: classic.id, slim: false), isNull);
      expect(setSkinModel(documents: documents, resources: const <String, Uint8List>{}, hash: 'e' * 64, slim: true), isNull);
      final AnimusDocument signed = _doc(DocumentKind.skin, 'a' * 64, <String, Object?>{...asMap(jsonDecode(classic.source)), 'signature': 'sig'});
      expect(setSkinModel(documents: <AnimusDocument>[signed], resources: const <String, Uint8List>{}, hash: 'a' * 64, slim: true), isNull);
    });
  });

  group('skinFromPng', () {
    test('makes the document, keeps the PNG and points the target at it in one change', () {
      final List<AnimusDocument> documents = <AnimusDocument>[_npc('town/a')];
      final SkinChange change = skinFromPng(documents: documents, png: png, slim: true, target: const SkinTarget(DocumentKind.npc, 'town/a'));
      final Map<String, Object?> after = _apply(documents, change);
      expect(change.skin, newSkin(slim: true, image: png).id);
      expect(NpcSkinInfo.fromDocument(after['skin:${change.skin}'])!.slim, isTrue);
      expect(asMap(after['npc:town/a'])['skin'], change.skin);
      expect(change.resources[skinImagePath(change.skin!)], png);
    });

    test('a Mojang address goes into the texture; another address does not', () {
      final SkinChange hosted = skinFromPng(documents: const <AnimusDocument>[], png: png, slim: false, url: 'https://textures.minecraft.net/texture/abc', target: const SkinTarget(DocumentKind.group, 'g'));
      final DocumentChange made = hosted.documents.firstWhere((DocumentChange c) => c.kind == DocumentKind.skin);
      expect(NpcSkinInfo.fromDocument(jsonDecode(made.source!))!.textureUrl, 'https://textures.minecraft.net/texture/abc');
      final SkinChange other = skinFromPng(documents: const <AnimusDocument>[], png: png, slim: false, url: 'https://example.com/skin.png', target: const SkinTarget(DocumentKind.group, 'g'));
      expect(NpcSkinInfo.fromDocument(jsonDecode(other.documents.firstWhere((DocumentChange c) => c.kind == DocumentKind.skin).source!))!.textureUrl, isNull);
    });

    test('the same PNG twice reuses the document', () {
      final List<AnimusDocument> documents = <AnimusDocument>[_skin(classic), _npc('town/a')];
      final SkinChange change = skinFromPng(documents: documents, png: png, slim: false, target: const SkinTarget(DocumentKind.npc, 'town/a'));
      expect(change.skin, classic.id);
      expect(change.documents.where((DocumentChange c) => c.kind == DocumentKind.skin), isEmpty);
    });

    test('a group target sets values.skin', () {
      final List<AnimusDocument> documents = <AnimusDocument>[_group('guards', const <String, Object?>{})];
      final SkinChange change = skinFromPng(documents: documents, png: png, slim: false, target: const SkinTarget(DocumentKind.group, 'guards'));
      expect(asMap(asMap(_apply(documents, change)['group:guards'])['values'])['skin'], change.skin);
    });
  });

  group('skinDeleteWarning', () {
    test('names the wearers, or says none', () {
      final List<AnimusDocument> documents = <AnimusDocument>[
        _skin(classic),
        _doc(DocumentKind.npc, 'town/a', <String, Object?>{'name': 'Anna', 'skin': classic.id}),
        _group('guards', <String, Object?>{'skin': classic.id}),
      ];
      expect(skinDeleteWarning(documents, classic.id), 'Worn by Anna (town/a) and group guards; they would name a missing skin. Undo brings it back.');
      expect(skinDeleteWarning(<AnimusDocument>[_skin(classic)], classic.id), 'No NPC or group wears it. Undo brings it back.');
    });
  });

  test('a model change commits as one undo step in the store', () async {
    final EditorStore store = EditorStore(repository: MemoryWorkspaceRepository(), schemas: SharedSchemas.embedded, newId: () => 'ws-skin');
    final String npc = prettyJson(<String, Object?>{
      'schemaVersion': 1,
      'revision': 1,
      'uuid': '00000000-0000-4000-8000-000000000001',
      'id': 'a',
      'group': 'town',
      'name': 'Anna',
      'type': 'minecraft:player',
      'spawned': false,
      'position': <String, Object?>{'world': 'world', 'x': 0, 'y': 64, 'z': 0, 'yaw': 0, 'pitch': 0},
      'skin': classic.id,
    });
    await store.openFiles('Skins', <String, Uint8List>{
      'skins/${classic.id}.json': Uint8List.fromList(utf8.encode(classic.source)),
      'npcs/town/a.json': Uint8List.fromList(utf8.encode(npc)),
      skinImagePath(classic.id): png,
    });
    final SkinChange change = setSkinModel(documents: store.workspace!.documents, resources: store.workspace!.resources, hash: classic.id, slim: true)!;
    await store.commit(documents: change.documents, resources: change.resources, label: change.label);
    expect(store.document(DocumentKind.skin, classic.id), isNull);
    final AnimusDocument made = store.document(DocumentKind.skin, change.skin!)!;
    expect(made.id, _sha(made.source), reason: 'the server checks the hash of the stored text');
    expect(made.path, 'skins/${change.skin}.json');
    expect(asMap(store.document(DocumentKind.npc, 'town/a')!.json)['skin'], change.skin);
    expect(store.workspace!.resources[skinImagePath(change.skin!)], png);
    expect(store.workspace!.resources.containsKey(skinImagePath(classic.id)), isFalse);
    await store.undo();
    expect(store.document(DocumentKind.skin, classic.id), isNotNull);
    expect(store.document(DocumentKind.skin, change.skin!), isNull);
    expect(asMap(store.document(DocumentKind.npc, 'town/a')!.json)['skin'], classic.id);
    expect(store.workspace!.resources[skinImagePath(classic.id)], png);
  });

  group('typeWithoutSkin', () {
    final List<AnimusDocument> documents = <AnimusDocument>[
      _npc('town/a', skin: 'a' * 64, groups: <String>['guards', 'plain', 'crowd']),
      _npc('town/b', groups: <String>['guards']),
      _group('guards', <String, Object?>{'skin': 'b' * 64, 'tags': <Object?>['x']}),
      _group('plain', <String, Object?>{'glowing': true}),
      _group('crowd', <String, Object?>{
        'skins': <Object?>['c' * 64],
      }),
    ];

    test('names the groups that give a skin and their other members', () {
      expect(skinGroupsOf(documents, asMap(documents.first.json)), <String>['guards', 'crowd']);
      expect(otherGroupMembers(documents, 'town/a', <String>['guards', 'crowd']), 1);
    });

    test('changes the type and removes the skin from the NPC and its groups in one change', () {
      final SkinChange change = typeWithoutSkin(documents: documents, npcId: 'town/a', type: 'minecraft:llama');
      final Map<String, Object?> after = _apply(documents, change);
      final JsonMap npc = asMap(after['npc:town/a']);
      expect(npc['type'], 'minecraft:llama');
      expect(npc.containsKey('skin'), isFalse);
      expect(asMap(asMap(after['group:guards'])['values']), <String, Object?>{
        'tags': <Object?>['x'],
      });
      expect(asMap(asMap(after['group:crowd'])['values']).containsKey('skins'), isFalse);
      expect(asMap(asMap(after['group:plain'])['values']), <String, Object?>{'glowing': true});
      expect(change.documents.where((DocumentChange c) => c.id == 'plain'), isEmpty);
    });
  });
}
