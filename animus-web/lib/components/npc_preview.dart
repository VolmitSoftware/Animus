/// The NPC document's Preview: the 3D stage showing the definition's body,
/// pose, skin, equipment, text and the rig it names (with its animations
/// playable), with the skin controls and a readout of what the stage cannot
/// show. It is drawn in the browser; no server takes part.
library;

import 'dart:convert';
import 'dart:js_interop';
import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../logic/gui/gui_json.dart';
import '../logic/npc/npc_attributes.dart';
import '../logic/npc/route_model.dart';
import '../logic/offline/new_documents.dart';
import '../logic/rig_sampler.dart';
import '../mc/npc/npc_look.dart';
import '../mc/npc/npc_playground.dart';
import '../mc/npc/npc_scene.dart';
import '../mc/npc/npc_skin.dart';
import '../mc/npc/npc_skin_failure.dart';
import '../mc/npc/route_overlay.dart';
import '../model/animus_document.dart';
import '../services/skin_images.dart';
import '../services/skin_textures.dart';
import 'mc/mc_asset_loader.dart';
import 'mc/mc_stage.dart';

class NpcPreview extends StatefulWidget {
  const NpcPreview({
    required this.document,
    this.skinDocument,
    this.route,
    this.rig,
    this.animations = const <AnimusDocument>[],
    this.resources = const <String, Uint8List>{},
    super.key,
  });

  final AnimusDocument document;

  /// The `skins/<hash>.json` document the definition names, when the
  /// workspace has it.
  final AnimusDocument? skinDocument;

  /// The route the NPC follows (its inline navigation fields, or the route
  /// document it names), drawn around the body when shown.
  final JsonMap? route;

  /// The rig the definition's `rig` names, when the workspace has it.
  final AnimusDocument? rig;

  /// That rig's animations, to play on the stage.
  final List<AnimusDocument> animations;

  /// Workspace resources: rig textures and the PNGs of local skins.
  final Map<String, Uint8List> resources;

  @override
  State<NpcPreview> createState() => _NpcPreviewState();
}

class _NpcPreviewState extends State<NpcPreview> {
  final McStageController _stage = McStageController();
  final Map<String, (Uint8List, String)> _textures = <String, (Uint8List, String)>{};
  bool? _slim;
  String? _awaitingSkin;
  String _url = '';
  bool _loading = false;
  String? _shown;
  NpcScene? _scene;
  bool _showRoute = true;
  String? _animation;
  double _tick = 0;
  bool _playing = false;
  double? _lastFrame;
  bool _backToIdle = false;
  String? _skinProblem;

  NpcLook get _look {
    final Object? json = component.document.json;
    return NpcLook.fromDefinition(json is Map<String, Object?> ? json : const <String, Object?>{});
  }

  NpcSkinInfo? get _skinInfo => NpcSkinInfo.fromDocument(component.skinDocument?.json);

  String _skinKey(NpcLook look) => look.skin ?? 'npc:${component.document.id}';

  bool _slimFor(NpcLook look) => _slim ?? _skinInfo?.slim ?? false;

  /// A PNG loaded this session, or the one a local skin keeps in the
  /// workspace.
  LoadedSkin? _loaded(NpcLook look) =>
      SkinImages.forKeyOrWorkspace(_skinKey(look), look.skin == null ? null : component.resources[skinImagePath(look.skin!)]);

  NpcSkinChoice _skinFor(NpcLook look) {
    final bool slim = _slimFor(look);
    final LoadedSkin? loaded = _loaded(look);
    if (loaded != null) return NpcSkinChoice(textureId: loaded.textureId, slim: slim);
    return NpcSkinChoice(textureId: SkinImages.placeholderFor(component.document.id, slim: slim).textureId, slim: slim);
  }

  /// The rig's idle animation id, when the workspace has it.
  String? get _idleId {
    final Object? rig = component.rig?.json;
    final Object? names = rig is Map<String, Object?> ? rig['animations'] : null;
    final Object? idle = names is Map<String, Object?> ? names['idle'] : null;
    return idle is String && component.animations.any((AnimusDocument animation) => animation.id == idle) ? idle : null;
  }

  Clip? get _clip {
    final String? id = _animation;
    if (id == null) return null;
    for (final AnimusDocument animation in component.animations) {
      final Object? json = animation.json;
      if (animation.id != id || json is! Map<String, Object?>) continue;
      try {
        return Clip.fromJson(json);
      } on Object {
        return null;
      }
    }
    return null;
  }

  String _texture(String path) {
    final Uint8List? bytes = component.resources[path];
    if (bytes == null) return 'missing:$path';
    final (Uint8List, String)? known = _textures[path];
    if (known != null && identical(known.$1, bytes)) return known.$2;
    final String id = 'npc-rig:${component.document.id}:$path#${_textures.length}';
    _textures[path] = (bytes, id);
    McAssetLoader.registerImage(id, 'data:image/png;base64,${base64Encode(bytes)}');
    return id;
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

  @override
  void dispose() {
    _playing = false;
    super.dispose();
  }

  /// Rebuilds the scene when the definition, the skin, the rig or the
  /// playhead changed.
  void _sync({bool frame = false}) {
    final NpcLook look = _look;
    _fetchSkin(look);
    final NpcSkinChoice skin = _skinFor(look);
    final JsonMap? route = _showRoute ? component.route : null;
    final AnimusDocument? rig = component.rig;
    final String signature = '${component.document.source}|${skin.textureId}|${skin.slim}|${route == null ? '' : jsonEncode(route)}'
        '|${rig?.source.hashCode}|$_animation|$_tick';
    if (signature == _shown && !frame) return;
    _shown = signature;
    final Object? rigJson = rig?.json;
    final NpcScene body = npcPlaygroundScene(
      look,
      skin: skin,
      rig: rigJson is Map<String, Object?> ? rigJson : null,
      clip: _clip,
      tick: _tick,
      resources: component.resources,
      textureId: _texture,
    );
    final JsonMap position = asMap(asMap(component.document.json)['position']);
    final NpcScene scene = _scene = route == null
        ? body
        : withRoute(body, RouteSpec(route), (asDouble(position['x']) ?? 0, asDouble(position['y']) ?? 0, asDouble(position['z']) ?? 0));
    _stage.show(scene.scene, scene.texts, yawDeg: look.yawDeg, frame: frame);
  }

  /// Fetches a signed skin's texture once its document is here, and redraws
  /// when it settles. A failed one waits for Retry.
  void _fetchSkin(NpcLook look, {bool retry = false}) {
    final String? hash = look.skin;
    final NpcSkinInfo? info = _skinInfo;
    if (hash == null || info == null || hash == _awaitingSkin || _loaded(look) != null) return;
    if (!retry && SkinImages.textures.stateOf(hash)?.status == SkinTextureStatus.failed) return;
    _awaitingSkin = hash;
    final Future<void> settled = retry ? SkinImages.textures.retry(hash, info.textureUrl) : SkinImages.textures.load(hash, info.textureUrl);
    settled.then((void _) {
      if (_awaitingSkin == hash) _awaitingSkin = null;
      if (!mounted) return;
      setState(() {});
      _sync();
    });
  }

  void _play() {
    final Clip? clip = _clip;
    if (_playing || clip == null) {
      setState(() => _playing = false);
      return;
    }
    if ((clip.loop == LoopMode.once || clip.loop == LoopMode.hold) && _tick >= clip.durationTicks) _tick = 0;
    setState(() => _playing = true);
    _lastFrame = null;
    _frame();
  }

  void _frame() {
    web.window.requestAnimationFrame(
      ((JSNumber now) {
        if (!_playing || !mounted) return;
        final double time = now.toDartDouble;
        final double? last = _lastFrame;
        _lastFrame = time;
        final Clip? clip = _clip;
        if (clip == null) {
          setState(() => _playing = false);
          return;
        }
        if (last != null) {
          _tick += (time - last) / 50;
          final String? idle = _idleId;
          if (clip.loop == LoopMode.once && _tick >= clip.durationTicks && idle != null && idle != _animation) {
            // The server ends a once clip and goes back to the rig's idle.
            _animation = idle;
            _tick = 0;
            _backToIdle = true;
          } else if ((clip.loop == LoopMode.once || clip.loop == LoopMode.hold) && _tick >= clip.durationTicks) {
            _tick = clip.durationTicks;
            _playing = false;
          }
          _sync();
          setState(() {});
        }
        if (_playing) _frame();
      }).toJS,
    );
  }

  Future<void> _load(Future<LoadedSkin?> Function(String key) load) async {
    setState(() => _loading = true);
    try {
      final LoadedSkin? skin = await load(_skinKey(_look));
      if (skin != null) {
        _skinProblem = null;
        ArcaneSonner.success('Skin loaded from ${skin.source}.');
      }
    } on Object catch (failure) {
      _skinProblem = skinFailureText(failure);
      ArcaneSonner.error(_skinProblem!);
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
    final LoadedSkin? loaded = _loaded(look);
    final bool slim = _slimFor(look);
    return dom.section(classes: 'animus-panel animus-npc-preview', <Widget>[
      const dom.p(classes: 'animus-preview-label', <Widget>[
        Component.text('Browser preview'),
      ]),
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
        if (component.rig != null) ...<Widget>[
          dom.label(classes: 'animus-inline-field', <Widget>[
            const dom.span(<Widget>[Component.text('Animation')]),
            ArcaneSelect(
              size: ComponentSize.sm,
              value: _animation ?? '',
              options: <ArcaneSelectOption>[
                const ArcaneSelectOption(label: 'Rest pose', value: ''),
                for (final AnimusDocument animation in component.animations) ArcaneSelectOption(label: animation.id, value: animation.id),
              ],
              onChanged: (String value) {
                setState(() {
                  _animation = value.isEmpty ? null : value;
                  _backToIdle = false;
                  _tick = 0;
                  _playing = false;
                });
                _sync();
              },
            ),
          ]),
          Button.secondary(
            label: _playing ? 'Pause' : 'Play',
            size: ButtonSize.small,
            disabled: _animation == null,
            attributes: <String, String>{'aria-pressed': '$_playing'},
            onPressed: _play,
          ),
        ],
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
              attributes: const <String, String>{'aria-label': 'Skin PNG URL'},
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
        if (component.rig != null)
          ..._fact('Rig', '${component.rig!.id}${_animation == null ? ', rest pose' : ', $_animation at tick ${_tick.toStringAsFixed(1)}${_backToIdle ? ' (back to idle)' : ''}'}'),
        if (look.scale != 1) ..._fact('Scale', '${formatAttribute(look.scale)} times the vanilla size'),
        if (player) ..._fact('Pose', look.pose.name),
        if (player) ...<Widget>[
          const dom.dt(<Widget>[Component.text('Skin')]),
          dom.dd(attributes: const <String, String>{'data-testid': 'npc-skin-state'}, <Widget>[
            Component.text(_skinText(look, info, loaded, slim)),
            if (loaded == null && look.skin != null && SkinImages.textures.stateOf(look.skin!)?.status == SkinTextureStatus.failed)
              Button.ghost(label: 'Retry texture', size: ButtonSize.small, onPressed: () => _fetchSkin(look, retry: true)),
          ]),
        ],
        if (look.armorStand != null) ..._fact('Armor stand', _armorStandText(look.armorStand!)),
        ..._fact(
          'Equipment',
          look.equipment.isEmpty
              ? 'none'
              : <String>[for (final MapEntry<NpcSlot, NpcItem> entry in look.equipment.entries) '${entry.key.key} ${entry.value.id}'].join(', '),
        ),
        ..._fact('Text', look.textLines.isEmpty ? (look.nativeName ? 'nameplate only' : 'none') : '${look.textLines.length} line${look.textLines.length == 1 ? '' : 's'}'),
      ]),
      if (scene.notes.isNotEmpty || _skinProblem != null)
        dom.ul(classes: 'animus-npc-notes', <Widget>[
          if (_skinProblem != null) dom.li(<Widget>[Component.text(_skinProblem!)]),
          for (final String note in scene.notes) dom.li(<Widget>[Component.text(note)]),
        ]),
    ]);
  }

  String _skinText(NpcLook look, NpcSkinInfo? info, LoadedSkin? loaded, bool slim) {
    final String model = slim ? 'slim' : 'classic';
    if (loaded != null) return '${loaded.source} ($model)';
    final String placeholder = 'placeholder ${SkinImages.placeholderLook(component.document.id).label} skin ($model)';
    if (look.skin == null) return 'none; preview shows the $placeholder';
    if (info == null) return 'skins/${look.skin}.json not in this workspace; showing the $placeholder';
    final String signed = 'signed skin ${look.skin!.substring(0, look.skin!.length.clamp(0, 12))} ($model)';
    final SkinTextureState? state = SkinImages.textures.stateOf(look.skin!);
    if (state?.status == SkinTextureStatus.failed) return '$signed; texture not loaded: ${state!.message}. Showing the $placeholder';
    return '$signed; loading texture';
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
