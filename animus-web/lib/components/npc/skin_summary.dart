/// A skin document's Visual view: the face once its PNG is loaded, the
/// hash, the model and signature, the NPCs that wear it, and loading the PNG
/// from a file or a CORS-enabled URL, all in one place.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_text.dart';
import '../../logic/npc/npc_catalog.dart';
import '../../mc/npc/npc_skin.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../services/skin_images.dart';
import '../gui/gui_fields.dart';

class SkinSummary extends StatefulWidget {
  const SkinSummary({required this.document, required this.workspaceDocuments, this.onOpen, this.onLoaded, super.key});

  final AnimusDocument document;
  final List<AnimusDocument> workspaceDocuments;
  final void Function(DocumentKind kind, String id)? onOpen;

  /// Called after a PNG was loaded.
  final void Function()? onLoaded;

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
      if (mounted) setState(() => _failure = 'Could not load the PNG: $failure. Pick a 64 by 64 skin file, or a URL that allows cross-origin reads.');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final String hash = component.document.id;
    final NpcSkinInfo? info = NpcSkinInfo.fromDocument(component.document.json);
    final LoadedSkin? loaded = SkinImages.forKey(hash);
    final List<NpcEntry> wearers = npcsWearing(npcEntries(component.workspaceDocuments), hash);
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
            dom.dd(<Widget>[Component.text(info?.signed == true ? 'Signed by Mojang; players see it as is' : 'Unsigned; the server signs it when it can')]),
            const dom.dt(<Widget>[Component.text('Preview')]),
            dom.dd(<Widget>[Component.text(loaded == null ? 'No PNG loaded in this page yet' : 'PNG loaded from ${loaded.source}')]),
          ]),
        ]),
        GuiSection(
          title: 'Load the PNG',
          children: <Widget>[
            const dom.p(classes: 'animus-gui-help', <Widget>[
              Component.text('The document holds the texture reference, not the image. Load the PNG to see the face here and on every stage in this page.'),
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
              const dom.p(classes: 'animus-muted', <Widget>[Component.text('No NPC in this workspace wears this skin. Pick it under Appearance in an NPC.')])
            else
              dom.ul(classes: 'animus-skin-wearers', <Widget>[
                for (final NpcEntry entry in wearers)
                  dom.li(<Widget>[
                    if (component.onOpen != null)
                      Button.ghost(
                        label: '${guiPlainText(entry.name)} (${entry.key})',
                        size: ButtonSize.small,
                        onPressed: () => component.onOpen!(DocumentKind.npc, entry.key),
                      )
                    else
                      Component.text('${guiPlainText(entry.name)} (${entry.key})'),
                  ]),
              ]),
          ],
        ),
      ],
    );
  }
}
