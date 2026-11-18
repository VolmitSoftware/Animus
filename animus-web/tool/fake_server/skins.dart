/// `POST /v1/skins/sign` and `GET /v1/skins/sign/<job>`, as EditorSkinRoutes:
/// a job is signing at its first poll and signed at the next, with a stand-in
/// signature (no MineSkin or Mojang here). A player name is found when it is
/// one of [_accounts] (their real textures, so the preview draws them) and
/// missing otherwise; a MineSkin link or id always answers one real MineSkin
/// skin ([_mineSkinTexture]).
/// While [failing] is set (`/__fake/skin-sign`) every job fails as MineSkin
/// refusing the image would.
library;

import 'dart:convert';

import 'messages.dart';
import 'protocol.dart';

/// Accounts the fake lookup finds, by lowercase name: their spelling and
/// their real texture address.
const Map<String, (String, String)> _accounts = <String, (String, String)>{
  'notch': ('Notch', 'http://textures.minecraft.net/texture/292009a4925b58f02c77dadc3ecef07ea4c7472f64e0fdc32ce5522489362680'),
  'jeb_': ('jeb_', 'http://textures.minecraft.net/texture/7fd9ba42a7c81eeea22f1524271ae85a8e045ce0af5a6ae16c6406ae917e68b5'),
};

/// The texture of MineSkin's public skin e51d0e34, slim arms.
const String _mineSkinTexture = 'http://textures.minecraft.net/texture/46b2961c73ec09f6b83621d44d096f32761dbe628b78776f500f886a6fc66284';

final RegExp _name = RegExp(r'^[A-Za-z0-9_]{1,16}$');
final RegExp _mineSkinLink = RegExp(r'^(?:https?://)?(?:www\.)?(?:mineskin\.org|minesk\.in)/(?:skins/)?([A-Za-z0-9-]{1,40})/?(?:[?#]\S*)?$', caseSensitive: false);
final RegExp _mineSkinId = RegExp(r'^(?:[0-9a-fA-F]{32}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|[A-Za-z0-9]{6,16})$');

class FakeSkinJobs {
  final Map<String, _Job> _jobs = <String, _Job>{};
  int _next = 0;
  bool failing = false;

  FakeResponse sign(FakeRequest request, String tokenId) {
    final Map<String, Object?> body = bodyObject(request);
    fields(body, r'$', <String>{'image', 'url', 'player', 'mineskin', 'slim', 'label'});
    if (<String>['image', 'url', 'player', 'mineskin'].where(body.containsKey).length != 1) throw ApiError('invalid-request', r'$: send exactly one of image, url, player or mineskin');
    final Object? image = body['image'], url = body['url'], player = body['player'], mineskin = body['mineskin'], slim = body['slim'], label = body['label'];
    if (player != null) {
      final String name = player is String ? player.trim() : '';
      if (!_name.hasMatch(name)) throw ApiError('invalid-request', '$name is not a Minecraft name (1-16 letters, digits or _)');
      return _queue(tokenId, 'player:$name', slim: false, label: (label as String?)?.trim() ?? '', player: name);
    }
    if (mineskin != null) {
      final String typed = mineskin is String ? mineskin.trim() : '';
      final String id = _mineSkinLink.firstMatch(typed)?.group(1) ?? typed;
      if (!_mineSkinId.hasMatch(id)) throw ApiError('invalid-request', 'Not a MineSkin link or id');
      final String short = id.replaceAll('-', '');
      return _queue(tokenId, 'mineskin:$id', slim: true, texture: _mineSkinTexture, label: (label as String?)?.trim().isNotEmpty == true ? (label as String).trim() : 'MineSkin ${short.substring(0, short.length < 8 ? short.length : 8)}');
    }
    if (image != null && (image is! String || base64Decode(image).length > 20000)) throw ApiError('invalid-request', r'$.image: expected a base64 PNG of at most 20000 bytes');
    if (url != null && (url is! String || !RegExp(r'^https?://\S{1,248}$').hasMatch(url))) throw ApiError('invalid-request', r'$.url: expected an http or https address');
    if (slim != null && slim is! bool) throw ApiError('invalid-request', r'$.slim: expected a boolean');
    if (label != null && (label is! String || label.length > 64)) throw ApiError('invalid-request', r'$.label: expected a string of at most 64 characters');
    return _queue(tokenId, '${image ?? url}', slim: slim == true, label: (label as String?)?.trim() ?? '');
  }

  FakeResponse _queue(String tokenId, String source, {required bool slim, required String label, String? player, String? texture}) {
    final String id = (++_next).toRadixString(16).padLeft(32, '0');
    _jobs[id] = _Job(tokenId, source, slim, label, failing, player: player, texture: texture);
    return FakeResponse(202, body: <String, Object?>{'job': id, 'state': 'signing'});
  }

  FakeResponse status(String id, String tokenId) {
    final _Job? job = _jobs[id];
    if (job == null || job.tokenId != tokenId) throw ApiError('not-found', 'No skin signing job $id.');
    if (job.polls++ == 0) return FakeResponse(200, body: <String, Object?>{'job': id, 'state': 'signing'});
    if (job.fails) return FakeResponse(200, body: <String, Object?>{'job': id, 'state': 'failed', 'message': 'MineSkin could not read the skin image: Invalid image dimensions'});
    final String? player = job.player;
    final (String, String)? account = player == null ? null : _accounts[player.toLowerCase()];
    if (player != null && account == null) return FakeResponse(200, body: <String, Object?>{'job': id, 'state': 'failed', 'message': 'No Minecraft account is named $player'});
    final String texture = base64Encode(utf8.encode(jsonEncode(<String, Object?>{
      'textures': <String, Object?>{
        'SKIN': <String, Object?>{
          'url': account?.$2 ?? job.texture ?? 'https://textures.minecraft.net/texture/${sha256Text(job.source).substring(0, 40)}',
          if (job.slim) 'metadata': <String, Object?>{'model': 'slim'},
        },
      },
    })));
    final String label = account != null && job.label.isEmpty ? account.$1 : job.label;
    final Map<String, Object?> source = <String, Object?>{
      'textureRaw': texture,
      'signature': base64Encode(utf8.encode('fake signature')),
      if (label.isNotEmpty) 'label': label,
      'player': ?account?.$1,
    };
    return FakeResponse(200, body: <String, Object?>{
      'job': id,
      'state': 'signed',
      'skin': <String, Object?>{'id': sha256Text(prettyJson(source)), 'source': source},
    });
  }
}

class _Job {
  _Job(this.tokenId, this.source, this.slim, this.label, this.fails, {this.player, this.texture});

  final String tokenId;
  final String source;
  final bool slim;
  final String label;
  final bool fails;

  /// The name a player lookup asked for.
  final String? player;

  /// A real texture address the answer carries, for a MineSkin skin.
  final String? texture;
  int polls = 0;
}
