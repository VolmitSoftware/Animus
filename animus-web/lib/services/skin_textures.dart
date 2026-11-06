/// Signed skin textures for the preview: the PNG a skin document's texture
/// URL names, fetched once per hash for the page session. Mojang's texture
/// host answers cross-origin reads, so the browser reads it directly, offline
/// workspaces included; the Animus server keeps no decoded textures (its
/// `skins.texture` capability says so).
library;

import 'dart:async';

/// The https address of a texture URL from a skin document, or null when it
/// does not point at Mojang's texture host. Skin documents store `http://`
/// URLs, which an https page may not load.
Uri? skinTextureAddress(String? url) {
  if (url == null) return null;
  final Uri? uri = Uri.tryParse(url.trim());
  if (uri == null || (uri.scheme != 'http' && uri.scheme != 'https') || uri.host != 'textures.minecraft.net') return null;
  if (!uri.path.startsWith('/texture/') || uri.path.length <= '/texture/'.length) return null;
  return uri.replace(scheme: 'https', port: 443);
}

enum SkinTextureStatus { loading, ready, failed }

final class SkinTextureState {
  const SkinTextureState(this.status, [this.message]);

  final SkinTextureStatus status;

  /// Why it failed, for the line under the stage.
  final String? message;
}

/// An HTTP answer that was not a PNG.
final class SkinTextureRefused implements Exception {
  const SkinTextureRefused(this.status);

  final int status;
}

/// Fetches the PNG at the address and keeps it under the hash (the browser
/// side registers it with the stage).
typedef SkinTextureFetch = Future<void> Function(String hash, Uri address);

final class SkinTextures {
  SkinTextures(this._fetch);

  final SkinTextureFetch _fetch;
  final Map<String, SkinTextureState> _states = <String, SkinTextureState>{};
  final Map<String, Future<void>> _running = <String, Future<void>>{};

  SkinTextureState? stateOf(String hash) => _states[hash];

  /// Starts loading [hash] from [textureUrl] unless it is loading, loaded or
  /// failed (see [retry]). Completes when it settles; never throws.
  Future<void> load(String hash, String? textureUrl) {
    final Future<void>? running = _running[hash];
    if (running != null) return running;
    if (_states.containsKey(hash)) return Future<void>.value();
    final Uri? address = skinTextureAddress(textureUrl);
    if (address == null) {
      _states[hash] = const SkinTextureState(SkinTextureStatus.failed, 'the skin document has no textures.minecraft.net texture URL');
      return Future<void>.value();
    }
    _states[hash] = const SkinTextureState(SkinTextureStatus.loading);
    final Future<void> task = _run(hash, address);
    _running[hash] = task;
    return task;
  }

  /// Forgets a failure and loads again.
  Future<void> retry(String hash, String? textureUrl) {
    if (_states[hash]?.status == SkinTextureStatus.failed) _states.remove(hash);
    return load(hash, textureUrl);
  }

  Future<void> _run(String hash, Uri address) async {
    try {
      await _fetch(hash, address);
      _states[hash] = const SkinTextureState(SkinTextureStatus.ready);
    } on SkinTextureRefused catch (refused) {
      _states[hash] = SkinTextureState(SkinTextureStatus.failed, 'textures.minecraft.net answered HTTP ${refused.status}');
    } on FormatException catch (bad) {
      _states[hash] = SkinTextureState(SkinTextureStatus.failed, 'the texture is not a usable skin: ${bad.message}');
    } on Object {
      _states[hash] = const SkinTextureState(SkinTextureStatus.failed, 'textures.minecraft.net could not be reached from this browser (offline or blocked)');
    } finally {
      _running.remove(hash);
    }
  }
}
