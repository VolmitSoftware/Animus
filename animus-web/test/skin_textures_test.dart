import 'dart:async';
import 'dart:convert';

import 'package:animus_editor/mc/npc/npc_skin.dart';
import 'package:animus_editor/services/skin_textures.dart';
import 'package:test/test.dart';

/// A skin document of the on-disk shape: exactly `signature`, `skinName` and
/// `textureRaw` (synthetic values, never a real signed skin).
Map<String, Object?> skinDocument({bool slim = false}) {
  final Map<String, Object?> textures = <String, Object?>{
    'timestamp': 0,
    'profileName': 'Synthetic',
    'textures': <String, Object?>{
      'SKIN': <String, Object?>{
        'url': 'http://textures.minecraft.net/texture/${'0f' * 32}',
        if (slim) 'metadata': <String, Object?>{'model': 'slim'},
      },
    },
  };
  return <String, Object?>{'signature': 's' * 684, 'skinName': 'n' * 36, 'textureRaw': base64.encode(utf8.encode(jsonEncode(textures)))};
}

void main() {
  group('skin documents', () {
    test('should read the classic model and texture URL from a three-key skin document', () {
      final NpcSkinInfo info = NpcSkinInfo.fromDocument(skinDocument())!;
      expect(info.slim, isFalse);
      expect(info.signed, isTrue);
      expect(info.textureUrl, 'http://textures.minecraft.net/texture/${'0f' * 32}');
    });

    test('should read the slim model from metadata.model', () {
      expect(NpcSkinInfo.fromDocument(skinDocument(slim: true))!.slim, isTrue);
    });
  });

  group('skinTextureAddress', () {
    test('should upgrade a Mojang texture URL to https', () {
      expect(skinTextureAddress('http://textures.minecraft.net/texture/abc').toString(), 'https://textures.minecraft.net/texture/abc');
      expect(skinTextureAddress('https://textures.minecraft.net/texture/abc').toString(), 'https://textures.minecraft.net/texture/abc');
    });

    test('should refuse other hosts and empty texture paths', () {
      expect(skinTextureAddress('https://example.com/texture/abc'), isNull);
      expect(skinTextureAddress('https://textures.minecraft.net/texture/'), isNull);
      expect(skinTextureAddress('file:///texture/abc'), isNull);
      expect(skinTextureAddress(null), isNull);
    });
  });

  group('SkinTextures', () {
    const String url = 'http://textures.minecraft.net/texture/abc';

    test('should fetch a texture once per hash and report it ready', () async {
      final List<(String, Uri)> fetched = <(String, Uri)>[];
      final Completer<void> gate = Completer<void>();
      final SkinTextures textures = SkinTextures((String hash, Uri address) async {
        fetched.add((hash, address));
        await gate.future;
      });
      final Future<void> first = textures.load('h1', url);
      final Future<void> second = textures.load('h1', url);
      expect(textures.stateOf('h1')!.status, SkinTextureStatus.loading);
      gate.complete();
      await Future.wait(<Future<void>>[first, second]);
      await textures.load('h1', url);
      expect(textures.stateOf('h1')!.status, SkinTextureStatus.ready);
      expect(fetched, <(String, Uri)>[('h1', Uri.parse('https://textures.minecraft.net/texture/abc'))]);
    });

    test('should say why a texture failed and load it again on retry', () async {
      int calls = 0;
      final SkinTextures textures = SkinTextures((String hash, Uri address) async {
        calls++;
        if (calls == 1) throw const SkinTextureRefused(404);
        if (calls == 2) throw StateError('network');
      });
      await textures.load('h1', url);
      expect(textures.stateOf('h1')!.message, 'textures.minecraft.net answered HTTP 404');
      await textures.load('h1', url);
      expect(calls, 1, reason: 'a failure waits for retry');
      await textures.retry('h1', url);
      expect(textures.stateOf('h1')!.message, contains('could not be reached'));
      await textures.retry('h1', url);
      expect(textures.stateOf('h1')!.status, SkinTextureStatus.ready);
    });

    test('should fail without fetching when the document names no Mojang texture', () async {
      bool called = false;
      final SkinTextures textures = SkinTextures((String hash, Uri address) async => called = true);
      await textures.load('h1', null);
      expect(called, isFalse);
      expect(textures.stateOf('h1')!.status, SkinTextureStatus.failed);
      expect(textures.stateOf('h1')!.message, contains('no textures.minecraft.net texture URL'));
    });

    test('should report a texture that is not a skin image', () async {
      final SkinTextures textures = SkinTextures((String hash, Uri address) async => throw const FormatException('this image is 32x32'));
      await textures.load('h1', url);
      expect(textures.stateOf('h1')!.message, 'the texture is not a usable skin: this image is 32x32');
    });
  });
}
