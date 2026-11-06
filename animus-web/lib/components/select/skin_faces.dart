/// The faces of the skins in a dropdown, for those whose texture is loaded.
library;

import '../../services/skin_images.dart';

/// Skin id to texture address, for the skins of [ids] loaded this session.
Map<String, String> skinFacesOf(List<String> ids) => <String, String>{
      for (final String id in ids)
        if (SkinImages.forKey(id) case final LoadedSkin skin) id: skin.dataUrl,
    };
