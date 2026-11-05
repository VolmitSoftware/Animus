/// The NPC document's Preview: the 3D stage showing the definition's body,
/// pose, skin, equipment and text, with the skin controls and a readout of
/// what the stage cannot show.
library;

import 'dart:convert';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../logic/gui/gui_json.dart';
import '../logic/npc/route_model.dart';
import '../mc/npc/npc_look.dart';
import '../mc/npc/npc_scene.dart';
import '../mc/npc/npc_skin.dart';
import '../mc/npc/route_overlay.dart';
import '../model/animus_document.dart';
import '../services/skin_images.dart';
import 'mc/mc_stage.dart';

class NpcPreview extends StatefulWidget {
  const NpcPreview({required this.document, this.skinDocument, this.route, super.key});

  final AnimusDocument document;

  /// The `skins/<hash>.json` document the definition names, when the
  /// workspace has it.
  final AnimusDocument? skinDocument;

  /// The route the NPC follows (its inline navigation fields, or the route
  /// document it names), drawn around the body when shown.
  final JsonMap? route;

  @override
  State<NpcPreview> createState() => _NpcPreviewState();
}

class _NpcPreviewState extends State<NpcPreview> {
  final McStageController _stage = McStageController();
  bool? _slim;
  String _url = '';
  bool _loading = false;
  String? _shown;
  NpcScene? _scene;
  bool _showRoute = true;

  NpcLook get _look {
    final Object? json = component.document.json;
    return NpcLook.fromDefinition(json is Map<String, Object?> ? json : const <String, Object?>{});
  }

  NpcSkinInfo? get _skinInfo => NpcSkinInfo.fromDocument(component.skinDocument?.json);

  String _skinKey(NpcLook look) => look.skin ?? 'npc:${component.document.id}';

  bool _slimFor(NpcLook look) => _slim ?? _skinInfo?.slim ?? false;

  NpcSkinChoice _skinFor(NpcLook look) {
    final bool slim = _slimFor(look);
    final LoadedSkin? loaded = SkinImages.forKey(_skinKey(look));
    if (loaded != null) return NpcSkinChoice(textureId: loaded.textureId, slim: slim);
    return slim ? NpcSkinChoice.alex : NpcSkinChoice.steve;
  }

  @override
  void initState() {
    super.initState();
    _sync(frame: true);
  }

  @override
  void didUpdateWidget(NpcPreview oldComponent) {
    super.didUpdateWidget(oldComponent);
    _sync(frame: oldComponent.document.id != component.document.id);
  }

  /// Rebuilds the scene when the definition or the skin changed.
  void _sync({bool frame = false}) {
    final NpcLook look = _look;
    final NpcSkinChoice skin = _skinFor(look);
    final JsonMap? route = _showRoute ? component.route : null;
    final String signature = '${component.document.source}|${skin.textureId}|${skin.slim}|${route == null ? '' : jsonEncode(route)}';
    if (signature == _shown && !frame) return;
    _shown = signature;
    final NpcScene body = npcScene(look, skin: skin);
    final JsonMap position = asMap(asMap(component.document.json)['position']);
    final NpcScene scene = _scene = route == null
        ? body
        : withRoute(body, RouteSpec(route), (asDouble(position['x']) ?? 0, asDouble(position['y']) ?? 0, asDouble(position['z']) ?? 0));
    _stage.show(scene.scene, scene.texts, yawDeg: look.yawDeg, frame: frame);
  }

  Future<void> _load(Future<LoadedSkin?> Function(String key) load) async {
    setState(() => _loading = true);
    try {
      final LoadedSkin? skin = await load(_skinKey(_look));
      if (skin != null) ArcaneSonner.success('Skin loaded from ${skin.source}.');
    } on Object catch (failure) {
      ArcaneSonner.error('Could not load the skin: $failure');
    } finally {
      if (mounted) {
        setState(() => _loading = false);
        _sync();
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final NpcLook look = _look;
    final NpcScene scene = _scene!;
    final bool player = look.isPlayer;
    final NpcSkinInfo? info = _skinInfo;
    final LoadedSkin? loaded = SkinImages.forKey(_skinKey(look));
    final bool slim = _slimFor(look);
    return dom.section(classes: 'animus-panel animus-npc-preview', <Widget>[
      dom.div(classes: 'animus-npc-preview-bar', <Widget>[
        Button.ghost(label: 'Reset view', size: ButtonSize.small, onPressed: _stage.resetCamera),
        if (component.route != null)
          Button(
            label: 'Route',
            size: ButtonSize.small,
            variant: _showRoute ? ButtonVariant.primary : ButtonVariant.ghost,
            attributes: <String, String>{'aria-pressed': '$_showRoute'},
            onPressed: () {
              setState(() => _showRoute = !_showRoute);
              _sync(frame: true);
            },
          ),
        if (player) ...<Widget>[
          Button.secondary(
            label: 'Load skin PNG',
            size: ButtonSize.small,
            disabled: _loading,
            onPressed: () => _load(SkinImages.pickFile),
          ),
          dom.div(classes: 'animus-npc-skin-url', <Widget>[
            TextInput(
              placeholder: 'Skin PNG URL',
              value: _url,
              onChanged: (String value) => setState(() => _url = value),
            ),
            Button.ghost(
              label: 'Load',
              size: ButtonSize.small,
              disabled: _loading || _url.trim().isEmpty,
              onPressed: () => _load((String key) => SkinImages.fetchUrl(key, _url)),
            ),
          ]),
          dom.div(classes: 'animus-segmented', attributes: const <String, String>{'role': 'group', 'aria-label': 'Arm model'}, <Widget>[
            Button(
              label: 'Classic',
              size: ButtonSize.small,
              variant: slim ? ButtonVariant.ghost : ButtonVariant.primary,
              attributes: <String, String>{'aria-pressed': '${!slim}'},
              onPressed: () {
                setState(() => _slim = false);
                _sync();
              },
            ),
            Button(
              label: 'Slim',
              size: ButtonSize.small,
              variant: slim ? ButtonVariant.primary : ButtonVariant.ghost,
              attributes: <String, String>{'aria-pressed': '$slim'},
              onPressed: () {
                setState(() => _slim = true);
                _sync();
              },
            ),
          ]),
        ],
      ]),
      McStage(controller: _stage, label: 'Preview of ${component.document.title}'),
      dom.dl(classes: 'animus-npc-facts', <Widget>[
        ..._fact('Body', scene.presentable ? look.type : '${look.type} (not presented)'),
        if (player) ..._fact('Pose', look.pose.name),
        if (player) ..._fact('Skin', _skinText(look, info, loaded, slim)),
        if (look.armorStand != null) ..._fact('Armor stand', _armorStandText(look.armorStand!)),
        ..._fact(
          'Equipment',
          look.equipment.isEmpty
              ? 'none'
              : <String>[for (final MapEntry<NpcSlot, NpcItem> entry in look.equipment.entries) '${entry.key.key} ${entry.value.id}'].join(', '),
        ),
        ..._fact('Text', look.textLines.isEmpty ? (look.nativeName ? 'nameplate only' : 'none') : '${look.textLines.length} line${look.textLines.length == 1 ? '' : 's'}'),
      ]),
      if (scene.notes.isNotEmpty)
        dom.ul(classes: 'animus-npc-notes', <Widget>[
          for (final String note in scene.notes) dom.li(<Widget>[Component.text(note)]),
        ]),
    ]);
  }

  String _skinText(NpcLook look, NpcSkinInfo? info, LoadedSkin? loaded, bool slim) {
    final String model = slim ? 'slim' : 'classic';
    if (loaded != null) return '${loaded.source} ($model)';
    if (look.skin == null) return 'default ${slim ? 'Alex' : 'Steve'} ($model); the definition names no skin';
    if (info == null) return 'skins/${look.skin}.json is not in this workspace; default skin ($model)';
    return 'signed skin ${look.skin!.substring(0, 12)} ($model); the server supplies its texture once connected, or load a PNG';
  }

  String _armorStandText(NpcArmorStand stand) => <String>[
    if (stand.small) 'small',
    stand.arms ? 'arms' : 'no arms',
    stand.basePlate ? 'base plate' : 'no base plate',
    if (stand.invisible) 'invisible',
    if (stand.angles.isNotEmpty) '${stand.angles.length} posed part${stand.angles.length == 1 ? '' : 's'}',
  ].join(', ');

  List<Widget> _fact(String label, String value) => <Widget>[
    dom.dt(<Widget>[Component.text(label)]),
    dom.dd(<Widget>[Component.text(value)]),
  ];
}
