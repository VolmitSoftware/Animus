/// Skin documents (`skins/<sha256>.json`, read by `SkinDocuments`): the
/// signed Mojang `textures` property, kept opaque by the server. The preview
/// reads only the skin model (classic or slim) and the texture URL from it.
/// The PNG itself never comes from `textures.minecraft.net` in the browser
/// (WebGL needs CORS headers it does not send); it comes from the server's
/// `GET /v1/skins/{hash}/texture.png` once connected, or from a PNG the user
/// loads.
library;

import 'dart:convert';

final class NpcSkinInfo {
  const NpcSkinInfo({required this.slim, this.textureUrl, this.signed = false});

  /// Null when [document] is not a readable skin document.
  static NpcSkinInfo? fromDocument(Object? document) {
    if (document is! Map<String, Object?>) return null;
    final Object? raw = document['textureRaw'];
    if (raw is! String || raw.isEmpty) return null;
    Object? decoded;
    try {
      decoded = jsonDecode(utf8.decode(base64.decode(base64.normalize(raw))));
    } on FormatException {
      return null;
    }
    if (decoded is! Map<String, Object?>) return null;
    final Object? textures = decoded['textures'];
    final Object? skin = textures is Map<String, Object?> ? textures['SKIN'] : null;
    if (skin is! Map<String, Object?>) return NpcSkinInfo(slim: false, signed: document['signature'] is String);
    final Object? metadata = skin['metadata'];
    final bool slim = metadata is Map<String, Object?> && metadata['model'] == 'slim';
    return NpcSkinInfo(
      slim: slim,
      textureUrl: skin['url'] is String ? skin['url']! as String : null,
      signed: document['signature'] is String,
    );
  }

  final bool slim;
  final String? textureUrl;
  final bool signed;
}
