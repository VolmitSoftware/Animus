/// A skin document's Visual view: the face once its PNG is loaded, the
/// hash, the arm model (switching it writes a new document and moves every
/// NPC and group wearing it there, see `skin_edits.dart`), the signature, the
/// NPCs and groups that wear it, and loading the PNG into the preview from a
/// file or a CORS-enabled URL.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_text.dart';
import '../../logic/npc/skin_edits.dart';
import '../../logic/offline/new_documents.dart' show skinImagePath;
import '../../mc/npc/npc_skin.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../services/skin_images.dart';
import '../gui/gui_fields.dart';

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
    return dom.section(
      classes: 'animus-panel animus-inspector animus-skin-summary',
      attributes: const <String, String>{'aria-label': 'Skin'},
      <Widget>[
        dom.div(classes: 'animus-skin-summary-head', <Widget>[
          if (loaded != null)
            dom.span(
              classes: 'animus-skin-face is-large',
              attributes: const <String, String>{'role': 'img', 'aria-label': 'Skin face'},
              styles: dom.Styles(raw: <String, String>{'background-image': 'url("${loaded.dataUrl}")'}),
              const <Widget>[],
            )
          else
            const dom.span(classes: 'animus-skin-face is-blank is-large', attributes: <String, String>{'aria-hidden': 'true'}, <Widget>[]),
          dom.dl(classes: 'animus-skin-facts', <Widget>[
            const dom.dt(<Widget>[Component.text('Hash')]),
            dom.dd(<Widget>[
              dom.code(<Widget>[Component.text(hash)]),
            ]),
            const dom.dt(<Widget>[Component.text('Model')]),
            dom.dd(<Widget>[Component.text(info == null ? 'Unreadable texture data' : (info.slim ? 'Slim arms (Alex)' : 'Classic arms (Steve)'))]),
            const dom.dt(<Widget>[Component.text('Signature')]),
            dom.dd(<Widget>[Component.text(info?.signed == true ? 'Signed by Mojang' : 'Unsigned; the server signs it')]),
            const dom.dt(<Widget>[Component.text('Preview')]),
            dom.dd(<Widget>[Component.text(loaded == null ? 'Not loaded' : 'PNG loaded from ${loaded.source}')]),
          ]),
        ]),
        if (component.editing != null && block == null && info != null)
          GuiRow(
            label: 'Arms',
            tip: 'Writes a new skin document, since the id is the hash of its text. Every NPC and group wearing this one moves to it.',
            child: GuiSwitch(value: info.slim, label: 'Slim arms (Alex)', onChanged: _model),
          )
        else if (block != null)
          GuiRow(label: 'Arms', tip: block, child: dom.span(<Widget>[Component.text(info?.slim == true ? 'Slim (Alex)' : 'Classic (Steve)')])),
        GuiSection(
          title: 'Load the PNG',
          children: <Widget>[
            const dom.p(classes: 'animus-gui-help', <Widget>[
              Component.text('Preview only. The document stores no image.'),
            ]),
            dom.div(classes: 'animus-row', <Widget>[
              Button.secondary(label: 'Load skin PNG', size: ButtonSize.small, disabled: _loading, onPressed: () => _load(SkinImages.pickFile)),
            ]),
            dom.div(classes: 'animus-gui-line', <Widget>[
              GuiTextInput(value: _url, label: 'Skin PNG URL', placeholder: 'https://.../skin.png', onChanged: (String text) => setState(() => _url = text)),
              Button.ghost(
                label: 'Load URL',
                size: ButtonSize.small,
                disabled: _loading || _url.trim().isEmpty,
                onPressed: () => _load((String key) => SkinImages.fetchUrl(key, _url.trim())),
              ),
            ]),
            if (_loading) const dom.p(classes: 'animus-muted', attributes: <String, String>{'role': 'status'}, <Widget>[Component.text('Loading the PNG...')]),
            if (_failure != null)
              dom.p(classes: 'animus-gui-problem is-error', attributes: const <String, String>{'role': 'alert'}, <Widget>[Component.text(_failure!)]),
          ],
        ),
        GuiSection(
          title: 'Worn by',
          count: wearers.length,
          children: <Widget>[
            if (wearers.isEmpty)
              const dom.p(classes: 'animus-muted', <Widget>[Component.text('No NPC or group wears this skin. Set it under Appearance.')])
            else
              dom.ul(classes: 'animus-skin-wearers', <Widget>[
                for (final AnimusDocument wearer in wearers)
                  dom.li(<Widget>[
                    if (component.onOpen != null)
                      Button.ghost(label: _wearerName(wearer), size: ButtonSize.small, onPressed: () => component.onOpen!(wearer.kind, wearer.id))
                    else
                      Component.text(_wearerName(wearer)),
                  ]),
              ]),
          ],
        ),
      ],
    );
  }
}
