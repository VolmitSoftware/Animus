/// The definition's `skin` (an NPC's, or a group's `values.skin`): the face
/// and name of the skin worn, Change to pick another and Remove. Change
/// opens a chooser with every skin of the workspace and the built-in ones
/// as a windowed list, so only the rows in view are drawn and only their
/// faces load; nothing is decoded until the chooser opens. A PNG file or
/// address makes a new skin document (`lib/logic/npc/skin_edits.dart`).
library;

import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/npc/placeholder_skins.dart';
import '../../logic/npc/skin_edits.dart';
import '../../logic/skin_label.dart';
import '../../logic/offline/new_documents.dart' show skinImagePath;
import '../../logic/suggest/field_profiles.dart';
import '../../mc/npc/npc_skin.dart';
import '../../mc/npc/npc_skin_failure.dart';
import '../../model/animus_document.dart';
import '../../services/skin_images.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/dialog.dart';
import '../../ui/field.dart';
import '../../ui/library_list.dart';
import '../../ui/library_query.dart';
import '../../ui/mode.dart';
import '../../ui/toggle_choice.dart';
import 'skin_face.dart';
import '../../logic/suggest/field_profile.dart';
import '../suggest/autocomplete_input.dart';
import 'npc_layout.dart';
import '../../ui/stack.dart';
import '../../logic/gui/gui_text.dart' show guiPlainText;

final RegExp _hash = RegExp(r'^[a-f0-9]{64}$');

/// A workspace skin's face, worked out when the row is drawn.
class SkinDocumentFace extends StatelessWidget {
  const SkinDocumentFace({required this.skin, this.resources = const <String, Uint8List>{}, this.size = SkinFaceSize.small, super.key});

  final AnimusDocument skin;
  final Map<String, Uint8List> resources;
  final SkinFaceSize size;

  @override
  Widget build(BuildContext context) =>
      SkinFace(skinId: skin.id, texture: skinInfoOf(skin)?.textureUrl, png: resources[skinImagePath(skin.id)], size: size);
}

/// A built-in skin's face, from the PNG shipped with the editor.
class _BuiltInFace extends StatelessWidget {
  const _BuiltInFace(this.look);

  final PlaceholderLook look;

  @override
  Widget build(BuildContext context) => dom.span(
        classes: 'animus-skin-face is-small',
        attributes: const <String, String>{'aria-hidden': 'true'},
        styles: dom.Styles(raw: <String, String>{'background-image': 'url("${placeholderSkinFor(look, slim: false).url}")'}),
        const <Widget>[],
      );
}

class SkinField extends StatefulWidget {
  const SkinField({
    required this.target,
    required this.value,
    required this.skins,
    required this.documents,
    required this.onChanged,
    this.resources = const <String, Uint8List>{},
    this.onCommit,
    this.diagnostics = const <Diagnostic>[],
    this.onLoaded,
    this.label = 'Skin',
    super.key,
  });

  /// The NPC or group whose skin this edits.
  final SkinTarget target;

  /// The definition's `skin` hash.
  final String? value;

  /// The workspace's skin documents.
  final List<AnimusDocument> skins;

  /// Every document of the workspace, which a new skin repoints.
  final List<AnimusDocument> documents;
  final void Function(String? value) onChanged;

  /// Workspace resources, for the PNGs local skins keep.
  final Map<String, Uint8List> resources;

  /// Writes a skin edit over several documents; null hides new skins from a
  /// PNG and the built-in ones.
  final SkinCommit? onCommit;
  final List<Diagnostic> diagnostics;

  /// A PNG was loaded or a built-in skin worn; the preview redraws.
  final void Function()? onLoaded;
  final String label;

  @override
  State<SkinField> createState() => _SkinFieldState();
}

class _SkinFieldState extends State<SkinField> {
  bool _choosing = false;

  AnimusDocument? get _current {
    final String? value = component.value;
    if (value == null) return null;
    for (final AnimusDocument skin in component.skins) {
      if (skin.id == value) return skin;
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final String? value = component.value;
    final AnimusDocument? current = _current;
    final String name = value == null
        ? 'Default (Steve or Alex)'
        : current == null
            ? (ModeScope.advanced(context) ? '${value.substring(0, value.length.clamp(0, 12))}, not here' : 'A skin not in this workspace')
            : skinLabel(current.id, label: skinDocumentLabel(current.json), wornBy: <String>[for (final AnimusDocument wearer in skinWearers(component.documents, current.id)) guiPlainText(wearer.title)]);
    return Field(
      label: component.label,
      tip: 'The look a player body wears. Pick a saved skin, a built-in one, or upload a PNG.',
      problems: <Diagnostic>[
        ...component.diagnostics.where((Diagnostic diagnostic) => diagnostic.path == r'$.skin'),
        if (value != null && !_hash.hasMatch(value)) const Diagnostic(DiagnosticSeverity.error, r'$.skin', 'Not a skin id.'),
        if (value != null && _hash.hasMatch(value) && current == null) const Diagnostic(DiagnosticSeverity.warning, r'$.skin', 'Not in this workspace; the server may have it.'),
      ],
      child: dom.div(classes: 'animus-skin-field', attributes: const <String, String>{'data-skin': 'field'}, <Widget>[
        ActionRow(
          label: 'Skin',
          leading: <Widget>[
            if (current != null) SkinDocumentFace(skin: current, resources: component.resources) else SkinFace(skinId: value),
            dom.span(classes: 'animus-skin-name', attributes: const <String, String>{'data-skin': 'name'}, <Widget>[Component.text(name)]),
          ],
          secondary: <RowAction>[
            if (value != null) RowAction('Remove skin', () => component.onChanged(null), icon: ArcaneIcon.x(), iconOnly: true),
            RowAction('Change', () => setState(() => _choosing = true), attributes: const <String, String>{'data-skin': 'change'}),
          ],
        ),
        if (_choosing)
          SkinChooser(
            title: 'Choose a skin',
            skins: component.skins,
            documents: component.documents,
            current: value,
            resources: component.resources,
            target: component.target,
            onCommit: component.onCommit,
            onLoaded: component.onLoaded,
            onPick: (String id) {
              setState(() => _choosing = false);
              component.onChanged(id);
            },
            onClose: () => setState(() => _choosing = false),
          ),
      ]),
    );
  }
}

/// The skins to pick from: the workspace's and the built-in ones, a PNG to
/// upload, and (Advanced) a PNG address.
class SkinChooser extends StatefulWidget {
  const SkinChooser({
    required this.title,
    required this.skins,
    required this.documents,
    required this.onPick,
    required this.onClose,
    this.current,
    this.resources = const <String, Uint8List>{},
    this.target,
    this.onCommit,
    this.onLoaded,
    this.exclude = const <String>{},
    super.key,
  });

  final String title;
  final List<AnimusDocument> skins;

  /// Every document of the workspace, which a new skin repoints.
  final List<AnimusDocument> documents;
  final String? current;
  final Map<String, Uint8List> resources;

  /// Who wears a skin made here; null leaves out the built-in skins and
  /// uploads (a skin pool picks saved skins only).
  final SkinTarget? target;
  final SkinCommit? onCommit;
  final void Function()? onLoaded;

  /// Skins not to offer (already in a pool).
  final Set<String> exclude;
  final void Function(String id) onPick;
  final void Function() onClose;

  @override
  State<SkinChooser> createState() => _SkinChooserState();
}

class _SkinChooserState extends State<SkinChooser> {
  late bool _builtIn = component.skins.isEmpty && component.target != null;
  String _url = '';
  String _hashText = '';
  bool _loading = false;
  String? _failure;

  bool get _canMake => component.onCommit != null && component.target != null;

  /// Loads a PNG, makes its skin document and wears it, in one undo step.
  Future<void> _create(Future<LoadedSkin?> Function(String key) load, {String? url, String? label}) async {
    final SkinCommit? commit = component.onCommit;
    final SkinTarget? target = component.target;
    if (commit == null || target == null) return;
    setState(() {
      _loading = true;
      _failure = null;
    });
    try {
      final LoadedSkin? skin = await load('new:${DateTime.now().microsecondsSinceEpoch}');
      if (skin == null) return;
      final AnimusDocument? current = component.skins.where((AnimusDocument s) => s.id == component.current).firstOrNull;
      final bool slim = current != null && (skinInfoOf(current)?.slim ?? false);
      final SkinChange change = skinFromPng(documents: component.documents, png: SkinImages.pngBytes(skin), slim: slim, url: url, label: label, target: target);
      SkinImages.alias(change.skin!, label == null ? skin : LoadedSkin(textureId: skin.textureId, source: 'the built-in $label skin', dataUrl: skin.dataUrl));
      await commit(change);
      component.onLoaded?.call();
      component.onClose();
    } on Object catch (failure) {
      if (mounted) setState(() => _failure = skinFailureText(failure));
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  /// Who wears each skin, worked out once per workspace snapshot.
  List<AnimusDocument>? _wearersOf;
  Map<String, List<String>> _wearers = const <String, List<String>>{};

  Map<String, List<String>> get _wearerNames {
    if (!identical(_wearersOf, component.documents)) {
      _wearersOf = component.documents;
      _wearers = skinWearerNames(component.documents);
    }
    return _wearers;
  }

  List<LibraryRow> get _saved => <LibraryRow>[
        for (final AnimusDocument skin in component.skins)
          if (!component.exclude.contains(skin.id))
            LibraryRow(
              key: skin.id,
              // Named as every skin list names it; who wears a labelled one
              // under its label.
              title: skinLabel(skin.id, label: skinDocumentLabel(skin.json), wornBy: _wearerNames[skin.id] ?? const <String>[]),
              meta: skinDocumentLabel(skin.json) == null ? null : wornByLine(_wearerNames[skin.id] ?? const <String>[]),
              keywords: '${skin.id} ${(_wearerNames[skin.id] ?? const <String>[]).join(' ')}',
              advancedMeta: skin.id,
              badges: <LibraryBadge>[if (skin.id == component.current) const LibraryBadge('Worn')],
              leading: SkinDocumentFace(skin: skin, resources: component.resources),
            ),
      ];

  List<LibraryRow> get _builtIns => <LibraryRow>[
        for (final PlaceholderLook look in placeholderLooks) LibraryRow(key: 'builtin:${look.id}', title: look.label, leading: _BuiltInFace(look)),
      ];

  void _open(LibraryRow row) {
    if (_loading) return;
    if (!row.key.startsWith('builtin:')) {
      component.onPick(row.key);
      return;
    }
    final PlaceholderLook look = placeholderLooks.firstWhere((PlaceholderLook each) => 'builtin:${each.id}' == row.key);
    final AnimusDocument? current = component.skins.where((AnimusDocument s) => s.id == component.current).firstOrNull;
    final bool slim = current != null && (skinInfoOf(current)?.slim ?? false);
    _create((String key) => SkinImages.fetchUrl(key, Uri.base.resolve(placeholderSkinFor(look, slim: slim).url).toString()), label: look.label);
  }

  @override
  Widget build(BuildContext context) {
    final bool advanced = ModeScope.advanced(context);
    final bool builtIn = _builtIn && component.target != null;
    return AppDialog(
      open: true,
      wide: true,
      title: component.title,
      id: 'animus-skin-chooser',
      onClose: component.onClose,
      footer: DialogFooter(
        secondary: <RowAction>[
          if (_canMake) RowAction('Upload PNG', _loading ? null : () => _create(SkinImages.pickFile), icon: ArcaneIcon.upload()),
        ],
        cancel: RowAction('Cancel', component.onClose),
      ),
      children: <Widget>[
        if (component.target != null)
          ToggleChoice<bool>(
            label: 'Skins',
            // The list under it counts its skins.
            options: const <ChoiceOption<bool>>[
              ChoiceOption<bool>(false, 'Saved'),
              ChoiceOption<bool>(true, 'Built-in'),
            ],
            value: builtIn,
            onChanged: (bool next) => setState(() => _builtIn = next),
          ),
        if (advanced && _canMake)
          Field(
            label: 'PNG address',
            forId: 'animus-skin-url',
            tip: 'A 64x64 skin PNG on the web. Servers need a signed texture to show it in game.',
            child: dom.div(classes: 'animus-skin-url', <Widget>[
              ActionRow(
                label: 'Skin address',
                leading: <Widget>[
                  wrapItem('1 1 16rem', AutocompleteInput(id: 'animus-skin-url', value: _url, label: 'Skin PNG address', profile: skinAddressProfile, placeholder: 'https://textures.minecraft.net/texture/...', onChanged: (String text) => setState(() => _url = text))),
                ],
                secondary: <RowAction>[
                  RowAction('Load', _loading || _url.trim().isEmpty ? null : () => _create((String key) => SkinImages.fetchUrl(key, _url), url: _url.trim())),
                ],
              ),
            ]),
          ),
        if (advanced && component.target != null)
          Field(
            label: 'Skin id',
            forId: 'animus-skin-hash',
            tip: 'The SHA-256 of a skin under skins/, for a skin the server has and this workspace does not.',
            child: ActionRow(
              label: 'Skin id',
              leading: <Widget>[
                wrapItem('1 1 16rem', AutocompleteInput(id: 'animus-skin-hash', value: _hashText, label: 'Skin id', placeholder: '64 hex characters', onChanged: (String text) => setState(() => _hashText = text.trim().toLowerCase()), profile: FieldProfile.none)),
              ],
              secondary: <RowAction>[RowAction('Wear', _hash.hasMatch(_hashText) ? () => component.onPick(_hashText) : null)],
            ),
          ),
        if (_loading) dom.div(attributes: const <String, String>{'role': 'status'}, <Widget>[mutedText('Loading the PNG...')]),
        if (_failure != null) AppAlert(tone: AlertTone.error, message: _failure!, live: true),
        LibraryList(
          key: ValueKey<String>(builtIn ? 'skins-builtin' : 'skins-saved'),
          id: 'animus-skin-list',
          // The dialog is the surface; the list draws no box inside it.
          framed: false,
          label: builtIn ? 'Built-in skins' : 'Saved skins',
          noun: 'skin',
          rows: builtIn ? _builtIns : _saved,
          fill: false,
          emptyMessage: 'No saved skins yet.',
          onOpen: _open,
        ),
      ],
    );
  }
}

/// The arm model of the worn skin: a switch, or why it cannot change.
class SkinArmsField extends StatelessWidget {
  const SkinArmsField({required this.skin, required this.target, required this.documents, this.resources = const <String, Uint8List>{}, this.onCommit, this.onLoaded, super.key});

  final AnimusDocument skin;
  final SkinTarget target;

  /// Every document of the workspace: the skin's other wearers keep it.
  final List<AnimusDocument> documents;
  final Map<String, Uint8List> resources;
  final SkinCommit? onCommit;
  final void Function()? onLoaded;

  Future<void> _model(bool slim) async {
    final SkinCommit? commit = onCommit;
    if (commit == null) return;
    final SkinChange? change = setSkinModel(documents: documents, resources: resources, hash: skin.id, slim: slim, only: target);
    if (change == null) return;
    await commit(change);
    onLoaded?.call();
  }

  @override
  Widget build(BuildContext context) {
    final NpcSkinInfo? info = skinInfoOf(skin);
    final String? block = skinEditBlock(skin.json);
    final bool slim = info?.slim ?? false;
    if (block != null || onCommit == null) {
      return Field(label: 'Arms', tip: block, child: dom.span(<Widget>[Component.text(info == null ? 'Unreadable' : (slim ? 'Slim (Alex)' : 'Classic (Steve)'))]));
    }
    final int others = skinWearers(documents, skin.id).where((AnimusDocument d) => !target.matches(d)).length;
    return Field(
      label: 'Arms',
      tip: others == 0 ? 'Writes a new skin document.' : 'Writes a new skin document for this one; $others other${others == 1 ? '' : 's'} keep the current one.',
      child: ArcaneToggleSwitch(value: slim, label: 'Slim arms (Alex)', onChanged: _model, size: ComponentSize.sm),
    );
  }
}
