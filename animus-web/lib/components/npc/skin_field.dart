/// The definition's `skin` (an NPC's, or a group's `values.skin`): the face
/// and name of the skin worn, Change to pick another and Remove. Change
/// opens a chooser with every skin of the workspace and the built-in ones
/// as a windowed list, so only the rows in view are drawn and only their
/// faces load; nothing is decoded until the chooser opens. A PNG file or
/// address makes a new skin document (`lib/logic/npc/skin_edits.dart`),
/// signed through the paired server, or unsigned with its PNG kept for the
/// server it is applied on. A player name or a MineSkin link or id is
/// looked up by the paired server, as `/animus npc skin` does.
library;

import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../logic/npc/placeholder_skins.dart';
import '../../logic/npc/skin_finds.dart';
import '../../logic/npc/skin_edits.dart';
import '../../logic/skin_label.dart';
import '../../logic/offline/new_documents.dart' show skinImagePath;
import '../../logic/suggest/field_profiles.dart';
import '../../mc/npc/npc_skin.dart';
import '../../mc/npc/npc_skin_failure.dart';
import '../../model/animus_document.dart';
import '../../services/skin_images.dart';
import '../../state/skin_signing.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/density.dart';
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
    this.sign,
    this.diagnostics = const <Diagnostic>[],
    this.onLoaded,
    this.label = 'Skin',
    this.defaultName = 'Default',
    this.defaultFace,
    this.findAbsence,
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

  /// Signs a new or unsigned skin through the paired server; null when none is paired.
  final SkinSigner? sign;
  final List<Diagnostic> diagnostics;

  /// A PNG was loaded or a built-in skin worn; the preview redraws.
  final void Function()? onLoaded;
  final String label;

  /// What the field says with no skin: the default skin players see.
  final String defaultName;

  /// That default skin's PNG, for the face; null draws the blank head.
  final String? defaultFace;

  /// Why skins cannot be looked up by player name or MineSkin link here.
  final String? findAbsence;

  @override
  State<SkinField> createState() => _SkinFieldState();
}

class _SkinFieldState extends State<SkinField> {
  bool _choosing = false;
  bool _signing = false;

  /// Signs the worn unsigned skin through the server and moves its wearers onto the signed one.
  Future<void> _signWorn(AnimusDocument skin, Uint8List png) async {
    final SkinSigner? sign = component.sign;
    final SkinCommit? commit = component.onCommit;
    if (sign == null || commit == null) return;
    setState(() => _signing = true);
    try {
      final SignedSkin signed = await sign(png: png, slim: skinInfoOf(skin)?.slim ?? false, label: skinDocumentLabel(skin.json));
      final LoadedSkin? shown = SkinImages.forKeyOrWorkspace(skin.id, png);
      if (shown != null) SkinImages.alias(signed.id, shown);
      final SkinChange? change = signedInPlace(documents: component.documents, resources: component.resources, hash: skin.id, signed: signed);
      if (change != null) await commit(change);
      component.onLoaded?.call();
      ArcaneSonner.success('Skin signed. Apply to show it in game.');
    } on Object catch (failure) {
      ArcaneSonner.error(signFailureText(failure));
    } finally {
      if (mounted) setState(() => _signing = false);
    }
  }

  /// Whether players see the worn skin, and how it gets signed when they do not.
  Widget? _signature(AnimusDocument? current) {
    if (current == null || !skinUnsigned(current.json)) return null;
    final Uint8List? png = component.resources[skinImagePath(current.id)];
    final bool paired = component.sign != null && component.onCommit != null;
    final bool kept = skinKeepsImage(current.json);
    final String line = paired && png != null
        ? 'Unsigned. Players see the default skin.'
        : kept
            ? 'Unsigned. Signed when applied on a server.'
            : (paired ? 'Unsigned. Upload the PNG again to sign it.' : 'Unsigned. Upload the PNG on a paired server to sign it.');
    return ActionRow(
      label: 'Skin signing',
      leading: <Widget>[dom.span(attributes: const <String, String>{'data-skin': 'signature', 'role': 'status'}, <Widget>[mutedText(line)])],
      secondary: <RowAction>[
        if (paired && png != null) RowAction('Sign', _signing ? null : () => _signWorn(current, png), loading: _signing, attributes: const <String, String>{'data-skin': 'sign'}),
      ],
    );
  }

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
        ? component.defaultName
        : current == null
            ? (ModeScope.advanced(context) ? '${value.substring(0, value.length.clamp(0, 12))}, not here' : 'A skin not in this workspace')
            : skinLabel(current.id, label: skinDocumentLabel(current.json), wornBy: <String>[for (final AnimusDocument wearer in skinWearers(component.documents, current.id)) guiPlainText(wearer.title)]);
    return Field(
      label: component.label,
      tip: "The look a player body wears: a saved or built-in skin, a player's, a MineSkin skin or a PNG.",
      problems: <Diagnostic>[
        ...component.diagnostics.where((Diagnostic diagnostic) => diagnostic.path == r'$.skin'),
        if (value != null && !_hash.hasMatch(value)) const Diagnostic(DiagnosticSeverity.error, r'$.skin', 'Not a skin id.'),
        if (value != null && _hash.hasMatch(value) && current == null) const Diagnostic(DiagnosticSeverity.warning, r'$.skin', 'Not in this workspace; the server may have it.'),
      ],
      child: dom.div(classes: 'animus-skin-field', attributes: const <String, String>{'data-skin': 'field'}, <Widget>[
        ActionRow(
          label: 'Skin',
          leading: <Widget>[
            if (current != null) SkinDocumentFace(skin: current, resources: component.resources) else SkinFace(skinId: value, fallback: value == null ? component.defaultFace : null),
            dom.span(classes: 'animus-skin-name', attributes: const <String, String>{'data-skin': 'name'}, <Widget>[Component.text(name)]),
          ],
          secondary: <RowAction>[
            if (value != null) RowAction('Remove skin', () => component.onChanged(null), icon: ArcaneIcon.x(), iconOnly: true),
            RowAction('Change', () => setState(() => _choosing = true), attributes: const <String, String>{'data-skin': 'change'}),
          ],
        ),
        ?_signature(current),
        if (_choosing)
          SkinChooser(
            title: 'Choose a skin',
            skins: component.skins,
            documents: component.documents,
            current: value,
            resources: component.resources,
            target: component.target,
            onCommit: component.onCommit,
            sign: component.sign,
            findAbsence: component.findAbsence,
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
    this.sign,
    this.onLoaded,
    this.findAbsence,
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

  /// Signs a new skin through the paired server; null makes an unsigned one
  /// that keeps its PNG for the server it is applied on.
  final SkinSigner? sign;
  final void Function()? onLoaded;

  /// Why skins cannot be looked up by player name or MineSkin link here;
  /// without a signer the chooser says they need a paired server.
  final String? findAbsence;

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
  String _player = '';
  String _mineSkin = '';
  bool _loading = false;
  bool _signingNow = false;

  /// What the progress line says while the server looks a skin up.
  String? _finding;
  String? _failure;

  bool get _canMake => component.onCommit != null && component.target != null;

  /// Why Player name and MineSkin are off; null when the server can look skins up.
  String? get _findAbsence => component.sign == null ? skinFindAbsence(paired: false) : component.findAbsence;

  /// Asks the paired server for a skin by player name or MineSkin link and
  /// wears it, in one undo step.
  Future<void> _find({String? player, String? mineSkin}) async {
    final SkinSigner? sign = component.sign;
    final SkinCommit? commit = component.onCommit;
    final SkinTarget? target = component.target;
    if (sign == null || commit == null || target == null || _loading) return;
    setState(() {
      _loading = true;
      _finding = player != null ? 'Looking up $player...' : 'Reading the MineSkin skin...';
      _failure = null;
    });
    try {
      final SignedSkin signed = await sign(player: player, mineskin: mineSkin, slim: false);
      await commit(skinFromSigned(documents: component.documents, signed: signed, target: target));
      component.onLoaded?.call();
      ArcaneSonner.success(player != null ? "$player's skin. Apply to show it in game." : 'MineSkin skin added. Apply to show it in game.');
      component.onClose();
    } on Object catch (failure) {
      if (mounted) setState(() => _failure = signFailureText(failure));
    } finally {
      if (mounted) {
        setState(() {
          _loading = false;
          _finding = null;
        });
      }
    }
  }

  /// Player name and MineSkin link or id, both looked up by the paired server.
  List<Widget> _findRows(BuildContext context) {
    final String? absence = _findAbsence;
    final ComponentSize size = controlSize(DensityScope.of(context));
    final bool off = absence != null || _loading;
    final String player = _player.trim(), mineSkin = _mineSkin.trim();
    return <Widget>[
      Field(
        label: 'Player name',
        forId: 'animus-skin-player',
        tip: "A Minecraft account's skin, looked up by the server. Follow player keeps it up to date.",
        docs: Docs.npcSkins,
        child: ActionRow(
          label: 'Player name',
          leading: <Widget>[
            wrapItem(
              '1 1 16rem',
              TextInput(
                id: 'animus-skin-player',
                value: _player,
                placeholder: 'Notch',
                disabled: absence != null,
                size: size,
                fullWidth: true,
                attributes: const <String, String>{'aria-label': 'Player name', 'autocomplete': 'off', 'spellcheck': 'false', 'data-skin': 'player'},
                onChanged: (String text) => setState(() => _player = text),
                onSubmitted: (String text) {
                  if (!off && text.trim().isNotEmpty) _find(player: text.trim());
                },
              ),
            ),
          ],
          secondary: <RowAction>[
            RowAction('Wear', off || player.isEmpty ? null : () => _find(player: player), attributes: const <String, String>{'data-skin': 'wear-player'}),
          ],
        ),
      ),
      Field(
        label: 'MineSkin link or id',
        forId: 'animus-skin-mineskin',
        tip: 'A skin from mineskin.org, by its link or id, such as https://minesk.in/e51d0e34.',
        docs: Docs.npcSkins,
        child: ActionRow(
          label: 'MineSkin link or id',
          leading: <Widget>[
            wrapItem(
              '1 1 16rem',
              TextInput(
                id: 'animus-skin-mineskin',
                value: _mineSkin,
                placeholder: 'https://minesk.in/...',
                disabled: absence != null,
                size: size,
                fullWidth: true,
                attributes: const <String, String>{'aria-label': 'MineSkin link or id', 'autocomplete': 'off', 'spellcheck': 'false', 'data-skin': 'mineskin'},
                onChanged: (String text) => setState(() => _mineSkin = text),
                onSubmitted: (String text) {
                  if (!off && text.trim().isNotEmpty) _find(mineSkin: text.trim());
                },
              ),
            ),
          ],
          secondary: <RowAction>[
            RowAction('Wear', off || mineSkin.isEmpty ? null : () => _find(mineSkin: mineSkin), attributes: const <String, String>{'data-skin': 'wear-mineskin'}),
          ],
        ),
      ),
      if (absence != null) dom.div(attributes: const <String, String>{'data-skin': 'find-absent'}, <Widget>[mutedText(absence)]),
    ];
  }

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
      final Uint8List png = SkinImages.pngBytes(skin);
      final SkinSigner? sign = component.sign;
      final SkinChange change;
      if (sign != null) {
        setState(() => _signingNow = true);
        final SignedSkin signed = await sign(png: png, slim: slim, label: label);
        change = skinFromSigned(documents: component.documents, signed: signed, png: png, target: target);
      } else {
        change = skinFromPng(documents: component.documents, png: png, slim: slim, url: url, label: label, target: target);
      }
      SkinImages.alias(change.skin!, label == null ? skin : LoadedSkin(textureId: skin.textureId, source: 'the built-in $label skin', dataUrl: skin.dataUrl));
      await commit(change);
      component.onLoaded?.call();
      if (sign != null) {
        ArcaneSonner.success('Skin signed. Apply to show it in game.');
      } else {
        ArcaneSonner.info('Skin added. It is signed when applied on a server.');
      }
      component.onClose();
    } on SkinSignFailure catch (failure) {
      if (mounted) setState(() => _failure = signFailureText(failure));
    } on Object catch (failure) {
      if (mounted) setState(() => _failure = _signingNow ? signFailureText(failure) : skinFailureText(failure));
    } finally {
      if (mounted) {
        setState(() {
          _loading = false;
          _signingNow = false;
        });
      }
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
        if (_canMake) ..._findRows(context),
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
        if (_canMake && component.sign == null && !_loading)
          dom.div(attributes: const <String, String>{'data-skin': 'unpaired'}, <Widget>[mutedText('Not paired: a new skin is signed when applied on a server.')]),
        if (_loading)
          dom.div(attributes: const <String, String>{'role': 'status', 'data-skin': 'progress'}, <Widget>[
            mutedText(_finding ?? (_signingNow ? 'Signing on the server...' : 'Loading the PNG...')),
          ]),
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
      tip: others == 0 ? 'Writes a new skin file.' : 'Writes a new skin file for this one; $others other${others == 1 ? '' : 's'} keep the current one.',
      child: ArcaneToggleSwitch(value: slim, label: 'Slim arms (Alex)', onChanged: _model, size: ComponentSize.sm),
    );
  }
}
