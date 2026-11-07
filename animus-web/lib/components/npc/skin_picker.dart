/// The definition's `skin` (an NPC's, or a group's `values.skin`): picked
/// from the workspace's `skins/` documents with their faces, or by hash; a
/// new skin document from a PNG file or address; the arm model; and removing
/// it. Skin documents are content-addressed, so a model change or a new PNG
/// writes a new document and repoints this NPC or group in one undo step
/// (`lib/logic/npc/skin_edits.dart`). The placeholder grid picks which
/// built-in skin the preview wears while the NPC has no skin texture here;
/// that choice is never written.
library;

import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/npc/placeholder_skins.dart';
import '../../logic/npc/skin_edits.dart';
import '../../logic/suggest/field_profile.dart';
import '../../logic/suggest/field_profiles.dart';
import '../../mc/npc/npc_skin.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../services/skin_images.dart';
import '../gui/gui_fields.dart';
import '../select/skin_faces.dart';

final RegExp _hash = RegExp(r'^[a-f0-9]{64}$');

/// A skin's short name in lists: its label when it has one, then its first
/// 12 hex characters and model.
String skinLabel(AnimusDocument skin) {
  final NpcSkinInfo? info = NpcSkinInfo.fromDocument(skin.json);
  final String model = info == null ? 'unreadable' : '${info.slim ? 'slim' : 'classic'}${info.signed ? ', signed' : ''}';
  final String facts = '${skin.id.substring(0, 12)}, $model';
  final String? label = skinDocumentLabel(skin.json);
  return label == null ? facts : '$label ($facts)';
}

class SkinPicker extends StatefulWidget {
  const SkinPicker({
    required this.npcId,
    required this.target,
    required this.value,
    required this.onChanged,
    required this.workspaceDocuments,
    this.resources = const <String, Uint8List>{},
    this.onCommit,
    this.diagnostics = const <Diagnostic>[],
    this.onLoaded,
    super.key,
  });

  /// The NPC document id, which keys its placeholder.
  final String npcId;

  /// The NPC or group whose skin this edits.
  final SkinTarget target;

  /// The definition's `skin` hash.
  final String? value;
  final void Function(String? value) onChanged;

  /// Every workspace document: the skins to offer and who else wears them.
  final List<AnimusDocument> workspaceDocuments;

  /// Workspace resources, for the PNGs local skins keep.
  final Map<String, Uint8List> resources;

  /// Writes a multi-document skin edit; null hides the model switch and new
  /// skins from a PNG.
  final SkinCommit? onCommit;
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
  String? _failure;

  List<AnimusDocument> get _skins => <AnimusDocument>[
        for (final AnimusDocument document in component.workspaceDocuments)
          if (document.kind == DocumentKind.skin) document,
      ];

  /// Loads a PNG, makes its skin document and points the target at it.
  Future<void> _create(Future<LoadedSkin?> Function(String key) load, {String? url}) async {
    final SkinCommit? commit = component.onCommit;
    if (commit == null) return;
    setState(() {
      _loading = true;
      _failure = null;
    });
    try {
      final LoadedSkin? skin = await load('new:${DateTime.now().microsecondsSinceEpoch}');
      if (skin == null) return;
      final AnimusDocument? current = _current;
      final bool slim = current != null && (NpcSkinInfo.fromDocument(current.json)?.slim ?? false);
      final SkinChange change = skinFromPng(documents: component.workspaceDocuments, png: SkinImages.pngBytes(skin), slim: slim, url: url, target: component.target);
      SkinImages.alias(change.skin!, skin);
      await commit(change);
      if (mounted) setState(() => _url = '');
      component.onLoaded?.call();
    } on Object catch (failure) {
      if (mounted) setState(() => _failure = 'Skin did not load ($failure). Use a 64x64 PNG or a cross-origin address.');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  AnimusDocument? get _current {
    final String? value = component.value;
    for (final AnimusDocument skin in _skins) {
      if (skin.id == value) return skin;
    }
    return null;
  }

  Future<void> _model(bool slim) async {
    final SkinCommit? commit = component.onCommit;
    final String? value = component.value;
    if (commit == null || value == null) return;
    final SkinChange? change = setSkinModel(
      documents: component.workspaceDocuments,
      resources: component.resources,
      hash: value,
      slim: slim,
      only: component.target,
    );
    if (change == null) return;
    await commit(change);
    component.onLoaded?.call();
  }

  @override
  Widget build(BuildContext context) {
    final String? value = component.value;
    final List<AnimusDocument> skins = _skins;
    final bool known = value == null || skins.any((AnimusDocument skin) => skin.id == value);
    final AnimusDocument? current = _current;
    const String pickId = 'animus-skin-pick';
    return dom.div(classes: 'animus-skin-picker', attributes: const <String, String>{'role': 'group', 'aria-label': 'Skin'}, <Widget>[
      GuiRow(
        label: 'Skin',
        forId: pickId,
        tip: 'A skin document under skins/. Its id is the hash of its text, so editing it makes a new one.',
        problems: <Diagnostic>[
          ...component.diagnostics.where((Diagnostic diagnostic) => diagnostic.path == r'$.skin'),
          if (value != null && !_hash.hasMatch(value)) const Diagnostic(DiagnosticSeverity.error, r'$.skin', 'expected 64 lowercase hex characters'),
          if (value != null && _hash.hasMatch(value) && !known) const Diagnostic(DiagnosticSeverity.warning, r'$.skin', 'not in this workspace; may be on the server'),
        ],
        child: dom.div(classes: 'animus-gui-line', <Widget>[
          GuiSelect(
            id: pickId,
            references: DocumentKind.skin,
            value: value,
            options: <String>[for (final AnimusDocument skin in skins) skin.id, if (!known) value],
            labels: <String, String>{
              for (final AnimusDocument skin in skins) skin.id: skinLabel(skin),
              if (!known) value: '${value.substring(0, value.length.clamp(0, 12))}, not in this workspace',
            },
            skinFaces: workspaceSkinFaces(skins, component.resources, onFetched: () {
              if (mounted) setState(() {});
            }),
            allowNone: true,
            noneLabel: 'No skin (Steve or Alex)',
            onChanged: component.onChanged,
          ),
          if (value != null) Button.ghost(label: 'Remove skin', size: ButtonSize.small, onPressed: () => component.onChanged(null)),
        ]),
      ),
      if (current != null) _modelRow(current),
      if (component.onCommit != null) _newSkin(),
      GuiRow(
        label: 'Skin hash',
        tip: 'SHA-256 of a skin under skins/.',
        child: GuiTextInput(
          value: value ?? '',
          label: 'Skin hash',
          profile: idProfile(const ValueRef.document(DocumentKind.skin)),
          placeholder: '64 hex characters',
          onChanged: (String text) => component.onChanged(text.trim().isEmpty ? null : text.trim()),
        ),
      ),
      _placeholders(),
    ]);
  }

  Widget _modelRow(AnimusDocument skin) {
    final NpcSkinInfo? info = NpcSkinInfo.fromDocument(skin.json);
    final String? block = skinEditBlock(skin.json);
    final bool slim = info?.slim ?? false;
    if (block != null || component.onCommit == null) {
      return GuiRow(
        label: 'Arms',
        tip: block,
        child: dom.span(<Widget>[Component.text(info == null ? 'Unreadable' : (slim ? 'Slim (Alex)' : 'Classic (Steve)'))]),
      );
    }
    final int others = skinWearers(component.workspaceDocuments, skin.id).where((AnimusDocument d) => !component.target.matches(d)).length;
    return GuiRow(
      label: 'Arms',
      tip: others == 0 ? 'Writes a new skin document.' : 'Writes a new skin document for this one; $others other${others == 1 ? '' : 's'} keep the current one.',
      child: GuiSwitch(value: slim, label: 'Slim arms (Alex)', onChanged: _model),
    );
  }

  Widget _newSkin() => GuiRow(
        label: 'New skin',
        tip: 'Makes a skin document from a 64x64 PNG and wears it. The PNG is kept with the workspace. Servers need a signed texture to show it.',
        child: dom.div(classes: 'animus-skin-new', <Widget>[
          dom.div(classes: 'animus-row', <Widget>[
            Button.secondary(label: 'From PNG file', size: ButtonSize.small, disabled: _loading, onPressed: () => _create(SkinImages.pickFile)),
          ]),
          dom.div(classes: 'animus-gui-line animus-npc-skin-url', <Widget>[
            GuiTextInput(value: _url, label: 'Skin PNG address', profile: skinAddressProfile, placeholder: 'https://textures.minecraft.net/texture/...', onChanged: (String text) => setState(() => _url = text)),
            Button.ghost(
              label: 'From address',
              size: ButtonSize.small,
              disabled: _loading || _url.trim().isEmpty,
              onPressed: () => _create((String key) => SkinImages.fetchUrl(key, _url), url: _url.trim()),
            ),
          ]),
          if (_loading) const dom.p(classes: 'animus-muted', attributes: <String, String>{'role': 'status'}, <Widget>[Component.text('Loading the PNG...')]),
          if (_failure != null) dom.p(classes: 'animus-gui-problem is-error', attributes: const <String, String>{'role': 'alert'}, <Widget>[Component.text(_failure!)]),
        ]),
      );

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
}
