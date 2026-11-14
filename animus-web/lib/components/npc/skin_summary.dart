/// A skin document's form: the face once its PNG is loaded, the label, the
/// hash, the arm model (switching it writes a new document and moves every
/// NPC and group wearing it there, see `skin_edits.dart`), the signature,
/// loading the PNG into the preview from a file or a CORS-enabled URL, and
/// the NPCs and groups that wear it.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_text.dart';
import '../../logic/npc/skin_edits.dart';
import '../../logic/suggest/field_profiles.dart';
import '../../logic/offline/new_documents.dart' show skinImagePath;
import '../../mc/npc/npc_skin.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../services/skin_images.dart';
import '../../services/skin_textures.dart' show SkinTextureStatus;
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/field.dart';
import '../../ui/section.dart';
import '../suggest/autocomplete_input.dart';
import 'npc_layout.dart';
import 'skin_face.dart';
import '../../ui/stack.dart';

class SkinSummary extends StatefulWidget {
  const SkinSummary({required this.document, required this.workspaceDocuments, this.onOpen, this.onLoaded, this.editing, super.key});

  final AnimusDocument document;
  final List<AnimusDocument> workspaceDocuments;
  final void Function(DocumentKind kind, String id)? onOpen;

  /// Called after a PNG was loaded.
  final void Function()? onLoaded;

  /// Writes a model change; null shows the model read-only.
  final SkinEditing? editing;

  @override
  State<SkinSummary> createState() => _SkinSummaryState();
}

class _SkinSummaryState extends State<SkinSummary> {
  String _url = '';
  bool _loading = false;
  String? _failure;

  Future<void> _load(Future<LoadedSkin?> Function(String key) load) async {
    setState(() {
      _loading = true;
      _failure = null;
    });
    try {
      final LoadedSkin? skin = await load(component.document.id);
      if (skin != null) ArcaneSonner.success('Skin loaded from ${skin.source}.');
      component.onLoaded?.call();
    } on Object catch (failure) {
      if (mounted) setState(() => _failure = 'Could not load the PNG: $failure. Pick a 64x64 skin PNG.');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _model(bool slim) async {
    final SkinEditing? editing = component.editing;
    if (editing == null) return;
    final SkinChange? change = setSkinModel(documents: component.workspaceDocuments, resources: editing.resources, hash: component.document.id, slim: slim);
    if (change == null) return;
    final LoadedSkin? loaded = SkinImages.forKey(component.document.id);
    if (loaded != null) SkinImages.alias(change.skin!, loaded);
    await editing.commit(change);
    ArcaneSonner.info('Saved as skin ${change.skin!.substring(0, 12)}; its wearers moved with it.');
    component.onOpen?.call(DocumentKind.skin, change.skin!);
  }

  String _wearerName(AnimusDocument wearer) =>
      wearer.kind == DocumentKind.npc ? '${guiPlainText(asString(asMap(wearer.json)['name']) ?? wearer.id)} (${wearer.id})' : 'Group ${wearer.id}';

  @override
  Widget build(BuildContext context) {
    final String hash = component.document.id;
    final NpcSkinInfo? info = NpcSkinInfo.fromDocument(component.document.json);
    final LoadedSkin? loaded = SkinImages.forKeyOrWorkspace(hash, component.editing?.resources[skinImagePath(hash)]);
    final List<AnimusDocument> wearers = skinWearers(component.workspaceDocuments, hash);
    final String? block = skinEditBlock(component.document.json);
    final bool modelEditable = component.editing != null && block == null && info != null;
    final SkinTextureStatus? texture = SkinImages.textures.stateOf(hash)?.status;
    if (loaded == null && info?.textureUrl != null && texture == null) {
      SkinImages.textures.load(hash, info!.textureUrl).then((_) {
        if (mounted) setState(() {});
      });
    }
    final SkinTextureStatus? status = loaded == null ? SkinImages.textures.stateOf(hash)?.status : null;
    final bool loading = status == SkinTextureStatus.loading;
    return dom.section(
      styles: stackStyles(gap: Gap.md),
      attributes: const <String, String>{'aria-label': 'Skin'},
      <Widget>[
        // The face from the texture the document names, a skeleton while it
        // loads. The hash is the page's id, so it is not repeated here.
        SkinFace(skinId: hash, texture: info?.textureUrl, png: component.editing?.resources[skinImagePath(hash)], size: SkinFaceSize.large, label: 'Skin face'),
        Field(label: 'Label', child: dom.span(<Widget>[Component.text(skinDocumentLabel(component.document.json) ?? 'None')])),
        Field(
          label: 'Arms',
          tip: modelEditable ? 'Writes a new skin document, since the id is the hash of its text. Every NPC and group wearing this one moves to it.' : block,
          child: modelEditable
              ? ArcaneToggleSwitch(value: info.slim, label: 'Slim arms (Alex)', onChanged: _model, size: ComponentSize.sm)
              : dom.span(<Widget>[Component.text(info == null ? 'Unreadable texture data' : (info.slim ? 'Slim arms (Alex)' : 'Classic arms (Steve)'))]),
        ),
        Field(label: 'Signature', child: dom.span(<Widget>[Component.text(info?.signed == true ? 'Signed by Mojang' : 'Unsigned; the server signs it')])),
        AppSection(
          title: 'Preview PNG',
          // Opens once the texture failed to load, closed while it loads.
          key: ValueKey<String>('skin-png:${loaded != null}:$status'),
          open: loaded == null && !loading,
          tip: 'Load the skin image to see it in the preview. The skin document holds only its hash, so the PNG stays in this browser.',
          docs: Docs.npcAppearance,
          children: <Widget>[
            Field(
              label: 'Image',
              child: dom.span(<Widget>[
                Component.text(switch (status) {
                  _ when loaded != null => 'From ${loaded.source}',
                  SkinTextureStatus.loading => 'Loading',
                  SkinTextureStatus.failed => 'Did not load from textures.minecraft.net. Load the PNG here.',
                  _ => 'Not loaded. Load the PNG here.',
                }),
              ]),
            ),
            ActionRow(
              label: 'Load the PNG',
              leading: <Widget>[
                wrapItem('1 1 16rem', AutocompleteInput(value: _url, label: 'Skin PNG URL', profile: skinAddressProfile, placeholder: 'https://.../skin.png', onChanged: (String text) => setState(() => _url = text))),
              ],
              secondary: <RowAction>[
                RowAction('Load URL', _loading || _url.trim().isEmpty ? null : () => _load((String key) => SkinImages.fetchUrl(key, _url.trim()))),
                RowAction('Load file', _loading ? null : () => _load(SkinImages.pickFile), icon: ArcaneIcon.upload()),
              ],
            ),
            if (_loading) dom.div(attributes: const <String, String>{'role': 'status'}, <Widget>[mutedText('Loading the PNG...')]),
            if (_failure != null) AppAlert(tone: AlertTone.error, message: _failure!, live: true),
          ],
        ),
        AppSection(
          title: 'Worn by',
          tip: 'NPCs and trait groups whose skin or skin pool uses this skin.',
          docs: Docs.npcAppearance,
          count: wearers.length,
          children: <Widget>[
            if (wearers.isEmpty)
              mutedLine('No NPC or trait group wears it.')
            else
              for (final AnimusDocument wearer in wearers)
                if (component.onOpen != null)
                  ActionRow(
                    key: ValueKey<String>('wearer:${wearer.kind.name}:${wearer.id}'),
                    label: _wearerName(wearer),
                    leading: <Widget>[dom.span(<Widget>[Component.text(_wearerName(wearer))])],
                    secondary: <RowAction>[RowAction('Open', () => component.onOpen!(wearer.kind, wearer.id), attributes: <String, String>{'aria-label': _wearerName(wearer)})],
                  )
                else
                  dom.p(<Widget>[Component.text(_wearerName(wearer))]),
          ],
        ),
      ],
    );
  }
}
