/// A skin's face, drawn from its 64x64 texture and loaded the first time
/// the face is built: the PNG the workspace keeps, or the signed texture
/// fetched from the URL the skin's summary carries, with the library's
/// skeleton while it loads and the stage's placeholder when there is none
/// or it fails. Lists draw only the rows in view, so a list of 400 skins
/// fetches only the faces someone looks at. [NpcFace] picks this or a
/// body's icon for an NPC row.
library;

import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/npc/npc_faces.dart';
import '../../logic/npc/placeholder_skins.dart';
import '../../services/skin_images.dart';
import '../../services/skin_textures.dart' show SkinTextureStatus;
import '../mc/mc_item_icons.dart';

/// How big a face is drawn.
enum SkinFaceSize { small, medium, large }

class SkinFace extends StatefulWidget {
  const SkinFace({this.skinId, this.texture, this.png, this.size = SkinFaceSize.small, this.label, this.fallback, super.key});

  /// The skin document id (its hash); null draws the blank head.
  final String? skinId;

  /// The signed texture URL from the skin's summary or document.
  final String? texture;

  /// The PNG a local workspace keeps for the skin, when it has one.
  final Uint8List? png;
  final SkinFaceSize size;

  /// Names the drawn face as an image; null keeps it decorative.
  final String? label;

  /// Drawn when there is no skin or its texture cannot load, as the stage
  /// draws the NPC; null draws the blank head.
  final PlaceholderSkin? fallback;

  @override
  State<SkinFace> createState() => _SkinFaceState();
}

class _SkinFaceState extends State<SkinFace> {
  LoadedSkin? _face() {
    final String? id = component.skinId;
    if (id == null) return null;
    final LoadedSkin? loaded = SkinImages.forKeyOrWorkspace(id, component.png);
    if (loaded != null) return loaded;
    if (component.texture != null && SkinImages.textures.stateOf(id) == null) {
      SkinImages.textures.load(id, component.texture).then((_) {
        if (mounted && SkinImages.forKey(id) != null) setState(() {});
      });
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final LoadedSkin? face = _face();
    final String size = _sizeClass(component.size);
    if (face == null) {
      // A texture on its way reads as loading, not as a missing face.
      final String? id = component.skinId;
      if (id != null && SkinImages.textures.stateOf(id)?.status == SkinTextureStatus.loading) {
        return dom.span(classes: 'animus-skin-face$size', attributes: const <String, String>{'aria-hidden': 'true', 'data-face': 'loading'}, const <Widget>[
          ArcaneSkeleton(width: '100%', height: '100%'),
        ]);
      }
      final PlaceholderSkin? fallback = component.fallback;
      if (fallback != null) {
        return dom.span(
          classes: 'animus-skin-face$size',
          attributes: const <String, String>{'aria-hidden': 'true', 'data-face': 'placeholder'},
          styles: dom.Styles(raw: <String, String>{'background-image': 'url("${fallback.url}")'}),
          const <Widget>[],
        );
      }
      return dom.span(classes: 'animus-skin-face is-blank$size', attributes: const <String, String>{'aria-hidden': 'true'}, const <Widget>[]);
    }
    final String? label = component.label;
    return dom.span(
      classes: 'animus-skin-face$size',
      attributes: <String, String>{if (label == null) 'aria-hidden': 'true' else ...<String, String>{'role': 'img', 'aria-label': label}, 'data-face': 'loaded'},
      styles: dom.Styles(raw: <String, String>{'background-image': 'url("${face.dataUrl}")'}),
      const <Widget>[],
    );
  }
}

String _sizeClass(SkinFaceSize size) => switch (size) {
      SkinFaceSize.small => ' is-small',
      SkinFaceSize.medium => '',
      SkinFaceSize.large => ' is-large',
    };

/// An NPC's face in a list, as the stage draws it ([npcFaceSource]): a
/// player body's skin or placeholder, another body's icon.
class NpcFace extends StatelessWidget {
  const NpcFace({required this.source, this.texture, this.png, this.size = SkinFaceSize.small, super.key});

  final NpcFaceSource source;

  /// The skin's signed texture URL and the PNG a local workspace keeps.
  final String? texture;
  final Uint8List? png;
  final SkinFaceSize size;

  @override
  Widget build(BuildContext context) => switch (source) {
        SkinFaceSource(:final String? skin, :final PlaceholderSkin fallback) => SkinFace(skinId: skin, texture: texture, png: png, size: size, fallback: fallback),
        BodyFaceSource(:final List<String> items) => BodyFace(items: items, size: size),
      };
}

/// A body's icon from the model pack: the first of [items] it draws, the
/// library's skeleton while it draws, a body glyph when it has none.
class BodyFace extends StatefulWidget {
  const BodyFace({required this.items, this.size = SkinFaceSize.small, super.key});

  final List<String> items;
  final SkinFaceSize size;

  @override
  State<BodyFace> createState() => _BodyFaceState();
}

class _BodyFaceState extends State<BodyFace> {
  @override
  void initState() {
    super.initState();
    McItemIcons.addListener(_drawn);
  }

  @override
  void dispose() {
    McItemIcons.removeListener(_drawn);
    super.dispose();
  }

  void _drawn() {
    if (mounted) setState(() {});
  }

  /// The icon to show, or null while the first item not known to be
  /// missing is still being drawn; "" when none can be.
  String? _icon() {
    for (final String key in component.items) {
      final String? url = McItemIcons.url(key);
      if (url != null) return url;
      if (!McItemIcons.missing(key)) return null;
    }
    return '';
  }

  @override
  Widget build(BuildContext context) {
    final String size = _sizeClass(component.size);
    final String? icon = _icon();
    if (icon == null) {
      return dom.span(classes: 'animus-skin-face$size', attributes: const <String, String>{'aria-hidden': 'true', 'data-face': 'loading'}, const <Widget>[
        ArcaneSkeleton(width: '100%', height: '100%'),
      ]);
    }
    if (icon.isEmpty) {
      return dom.span(classes: 'animus-skin-face is-blank$size', attributes: const <String, String>{'aria-hidden': 'true', 'data-face': 'body'}, <Widget>[
        ArcaneIcon.circleUser(size: IconSize.sm),
      ]);
    }
    // The icon is drawn larger than the face and scaled down to it.
    final String px = switch (component.size) { SkinFaceSize.small => '28', SkinFaceSize.medium => '32', SkinFaceSize.large => '96' };
    return dom.span(classes: 'animus-skin-face$size', attributes: const <String, String>{'aria-hidden': 'true', 'data-face': 'body'}, <Widget>[
      dom.img(src: icon, alt: '', attributes: <String, String>{'width': px, 'height': px}),
    ]);
  }
}
