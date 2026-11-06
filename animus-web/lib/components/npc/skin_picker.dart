/// Picks the definition's `skin` from the workspace's `skins/` documents,
/// or by hash, and loads a PNG for a skin from a file or a CORS-enabled URL
/// so the stage can wear it (kept for the page session, per skin hash). The
/// placeholder grid picks which built-in skin the preview wears while the NPC
/// has no skin texture here; that choice is never written to the definition.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/npc/placeholder_skins.dart';
import '../../mc/npc/npc_skin.dart';
import '../../model/animus_document.dart';
import '../../services/skin_images.dart';
import '../gui/gui_fields.dart';

final RegExp _hash = RegExp(r'^[a-f0-9]{64}$');

class SkinPicker extends StatefulWidget {
  const SkinPicker({required this.npcId, required this.value, required this.onChanged, required this.skins, this.diagnostics = const <Diagnostic>[], this.onLoaded, super.key});

  /// The NPC document id, which keys its placeholder.
  final String npcId;

  /// The definition's `skin` hash.
  final String? value;
  final void Function(String? value) onChanged;

  /// The workspace's skin documents.
  final List<AnimusDocument> skins;
  final List<Diagnostic> diagnostics;

  /// Called after a PNG was loaded or a placeholder picked, so the stage
  /// redraws.
  final void Function()? onLoaded;

  @override
  State<SkinPicker> createState() => _SkinPickerState();
}

class _SkinPickerState extends State<SkinPicker> {
  String _url = '';
  bool _loading = false;

  Future<void> _load(String key, Future<LoadedSkin?> Function(String key) load) async {
    setState(() => _loading = true);
    try {
      final LoadedSkin? skin = await load(key);
      if (skin != null) ArcaneSonner.success('Skin loaded from ${skin.source}.');
      component.onLoaded?.call();
    } on Object catch (failure) {
      ArcaneSonner.error('Could not load the skin: $failure');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final String? value = component.value;
    final bool known = value == null || component.skins.any((AnimusDocument skin) => skin.id == value);
    return dom.div(classes: 'animus-skin-picker', attributes: const <String, String>{'role': 'group', 'aria-label': 'Skin'}, <Widget>[
      dom.div(classes: 'animus-skin-grid', attributes: const <String, String>{'role': 'radiogroup', 'aria-label': 'Workspace skins'}, <Widget>[
        _card(null, 'Default', 'Steve or Alex', null, 'Default skin, Steve or Alex'),
        for (int index = 0; index < component.skins.length; index++)
          _card(
            component.skins[index].id,
            component.skins[index].id.substring(0, 12),
            _model(component.skins[index]),
            SkinImages.forKey(component.skins[index].id),
            'Skin ${index + 1} of ${component.skins.length}, ${_model(component.skins[index])}, ${component.skins[index].id.substring(0, 8)}',
          ),
        if (!known) _card(value, value.substring(0, value.length.clamp(0, 12)), 'not in this workspace', SkinImages.forKey(value), 'Skin ${value.substring(0, value.length.clamp(0, 8))}, not in this workspace'),
      ]),
      _placeholders(),
      GuiRow(
        label: 'Skin hash',
        help: 'SHA-256 of a skin under skins/.',
        problems: <Diagnostic>[
          ...component.diagnostics.where((Diagnostic diagnostic) => diagnostic.path == r'$.skin'),
          if (value != null && !_hash.hasMatch(value)) const Diagnostic(DiagnosticSeverity.error, r'$.skin', 'expected 64 lowercase hex characters'),
        ],
        child: GuiTextInput(
          value: value ?? '',
          label: 'Skin hash',
          placeholder: '64 hex characters',
          onChanged: (String text) => component.onChanged(text.trim().isEmpty ? null : text.trim()),
        ),
      ),
      if (value != null)
        dom.div(classes: 'animus-row animus-skin-load', <Widget>[
          Button.secondary(label: 'Load skin PNG', size: ButtonSize.small, disabled: _loading, onPressed: () => _load(value, SkinImages.pickFile)),
          dom.div(classes: 'animus-npc-skin-url', <Widget>[
            GuiTextInput(value: _url, label: 'Skin PNG URL', placeholder: 'https://.../skin.png', onChanged: (String text) => setState(() => _url = text)),
            Button.ghost(
              label: 'Load URL',
              size: ButtonSize.small,
              disabled: _loading || _url.trim().isEmpty,
              onPressed: () => _load(value, (String key) => SkinImages.fetchUrl(key, _url)),
            ),
          ]),
        ]),
    ]);
  }

  Widget _placeholders() {
    final PlaceholderLook worn = SkinImages.placeholderLook(component.npcId);
    return dom.div(classes: 'animus-skin-placeholders', attributes: const <String, String>{'role': 'group', 'aria-label': 'Preview placeholder'}, <Widget>[
      const dom.p(classes: 'animus-gui-help', <Widget>[
        Component.text('Preview only. Not saved.'),
      ]),
      dom.div(classes: 'animus-skin-grid', attributes: const <String, String>{'role': 'radiogroup', 'aria-label': 'Placeholder skins'}, <Widget>[
        for (final PlaceholderLook look in placeholderLooks)
          dom.button(
            classes: 'animus-skin-card${look.id == worn.id ? ' is-selected' : ''}',
            attributes: <String, String>{
              'type': 'button',
              'role': 'radio',
              'aria-checked': '${look.id == worn.id}',
              'aria-label': 'Placeholder ${look.label}',
              'data-placeholder': look.id,
            },
            events: <String, void Function(web.Event)>{
              'click': (web.Event _) {
                setState(() => SkinImages.choosePlaceholder(component.npcId, look));
                component.onLoaded?.call();
              },
            },
            <Widget>[
              dom.span(
                classes: 'animus-skin-face',
                styles: dom.Styles(raw: <String, String>{'background-image': 'url("${placeholderSkinFor(look, slim: false).url}")'}),
                const <Widget>[],
              ),
              dom.span(classes: 'animus-skin-card-text', <Widget>[
                dom.span(<Widget>[Component.text(look.label)]),
                dom.span(classes: 'animus-muted', <Widget>[Component.text(look.id == worn.id ? 'worn in the preview' : 'placeholder')]),
              ]),
            ],
          ),
      ]),
    ]);
  }

  String _model(AnimusDocument skin) {
    final NpcSkinInfo? info = NpcSkinInfo.fromDocument(skin.json);
    if (info == null) return 'unreadable';
    return '${info.slim ? 'slim' : 'classic'}${info.signed ? ', signed' : ''}';
  }

  Widget _card(String? hash, String title, String detail, LoadedSkin? loaded, String name) {
    final bool selected = hash == component.value;
    return dom.button(
      classes: 'animus-skin-card${selected ? ' is-selected' : ''}',
      attributes: <String, String>{'type': 'button', 'role': 'radio', 'aria-checked': '$selected', 'aria-label': name, 'data-skin': hash ?? 'default'},
      events: <String, void Function(web.Event)>{'click': (web.Event _) => component.onChanged(hash)},
      <Widget>[
        if (loaded != null)
          dom.span(
            classes: 'animus-skin-face',
            styles: dom.Styles(raw: <String, String>{'background-image': 'url("${loaded.dataUrl}")'}),
            const <Widget>[],
          )
        else
          dom.span(classes: 'animus-skin-face is-blank', <Widget>[Component.text(hash == null ? 'S' : '?')]),
        dom.span(classes: 'animus-skin-card-text', <Widget>[
          dom.code(<Widget>[Component.text(title)]),
          dom.span(classes: 'animus-muted', <Widget>[Component.text(loaded != null ? 'PNG loaded' : detail)]),
        ]),
      ],
    );
  }
}
