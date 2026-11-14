/// The faces of the skins in a dropdown, for those whose texture is loaded.
library;

import 'dart:typed_data';

import '../../logic/offline/new_documents.dart' show skinImagePath;
import '../../mc/npc/npc_skin.dart';
import '../../model/animus_document.dart';
import '../../services/skin_images.dart';

/// Skin id to texture address, for the skins of [ids] loaded this session.
Map<String, String> skinFacesOf(List<String> ids) => <String, String>{
      for (final String id in ids)
        if (SkinImages.forKey(id) case final LoadedSkin skin) id: skin.dataUrl,
    };

/// Each skin document's texture address, read once per document (skins are
/// content-addressed and documents immutable): decoding every skin's
/// base64 texture field on each build cost a quarter second at 400 skins.
final Expando<_Texture> _textures = Expando<_Texture>('skin texture');

class _Texture {
  const _Texture(this.url);

  final String? url;
}

String? _textureOf(AnimusDocument skin) => (_textures[skin] ??= _Texture(NpcSkinInfo.fromDocument(skin.json)?.textureUrl)).url;

/// [skinFacesOf] for workspace [skins], registering the PNGs the workspace
/// keeps first and starting the fetch of signed skins' textures; [onFetched]
/// runs when one lands, so the dropdown redraws with its face.
Map<String, String> workspaceSkinFaces(List<AnimusDocument> skins, Map<String, Uint8List> resources, {void Function()? onFetched}) {
  for (final AnimusDocument skin in skins) {
    if (SkinImages.forKeyOrWorkspace(skin.id, resources[skinImagePath(skin.id)]) != null) continue;
    final String? url = _textureOf(skin);
    if (url != null && SkinImages.textures.stateOf(skin.id) == null) {
      SkinImages.textures.load(skin.id, url).then((_) => onFetched?.call());
    }
  }
  return skinFacesOf(<String>[for (final AnimusDocument skin in skins) skin.id]);
}
