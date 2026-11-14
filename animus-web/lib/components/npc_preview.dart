/// The NPC document's Preview: the 3D stage showing the definition's body,
/// pose, skin, equipment, text and the rig it names (with its animations
/// playable), in the kit's stage frame. It is drawn in the browser; with a
/// paired server that shares terrain, the blocks around the NPC and along
/// its route replace the grass patch, the other spawned NPCs nearby stand
/// around it (from the catalog, capped and counted), and while the NPC is
/// spawned the body stands where the NPC is now. What the stage cannot show
/// is one short line under the canvas with the details in its tip.
library;

import 'dart:convert';
import 'dart:js_interop';
import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:web/web.dart' as web;

import '../logic/gui/gui_json.dart';
import '../logic/gui/gui_text.dart';
import '../logic/npc/npc_appearance.dart' show bodyLabel, bodySettingsTypes;
import '../logic/npc/route_model.dart';
import '../logic/offline/new_documents.dart';
import '../logic/rig_sampler.dart';
import '../logic/stage/stage_status.dart';
import '../logic/terrain/terrain_access.dart';
import '../logic/terrain/terrain_path.dart';
import '../mc/npc/npc_look.dart';
import '../mc/npc/npc_neighbours.dart';
import '../mc/npc/npc_playground.dart';
import '../mc/npc/npc_scene.dart';
import '../mc/npc/npc_skin.dart';
import '../mc/npc/route_overlay.dart';
import '../mc/scene/mc_math.dart';
import '../mc/scene/mc_scene.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../logic/npc/placeholder_skins.dart';
import '../services/skin_images.dart';
import '../services/skin_textures.dart';
import '../services/terrain/terrain_client.dart';
import '../state/library.dart';
import '../state/workspace_route.dart';
import '../ui/action_row.dart';
import '../ui/menu.dart';
import '../ui/toggle_choice.dart' show ChoiceOption;
import 'mc/mc_asset_loader.dart';
import 'mc/mc_stage.dart';
import 'mc/mc_stage_frame.dart';
import 'mc/stage_neighbours.dart';
import 'select/picker_scope.dart';
import 'terrain/live_position.dart';
import 'terrain/stage_terrain.dart';
import 'terrain/terrain_controls.dart';

class NpcPreview extends StatefulWidget {
  const NpcPreview({
    required this.document,
    this.skinDocument,
    this.route,
    this.rig,
    this.animations = const <AnimusDocument>[],
    this.resources = const <String, Uint8List>{},
    this.neighbours,
    this.skinTexture,
    this.collapsible = false,
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

  /// A server's NPCs from the catalog, drawn around this one; null in a
  /// local workspace.
  final List<LibraryEntry>? neighbours;

  /// A skin's texture address from the catalog, for the neighbours' skins.
  final String? Function(String skinId)? skinTexture;

  /// Hide and Show for the band above the form at 761-1099 px.
  final bool collapsible;

  @override
  State<NpcPreview> createState() => _NpcPreviewState();
}

class _NpcPreviewState extends State<NpcPreview> {
  final McStageController _stage = McStageController();
  final Map<String, (Uint8List, String)> _textures = <String, (Uint8List, String)>{};
  final StageNeighbours _near = StageNeighbours();
  String? _awaitingSkin;
  String? _shown;
  NpcScene? _scene;
  bool _showRoute = true;
  bool _showNearby = true;
  bool _layersFit = true;
  String? _animation;
  double _tick = 0;
  bool _playing = false;
  double? _lastFrame;
  bool _backToIdle = false;
  NpcPlace? _picked;
  (double, double)? _pickedAt;
  web.IntersectionObserver? _seen;
  late final StageTerrain _terrain = StageTerrain(_redraw);
  late final LivePosition _live = LivePosition(_redraw);

  void _redraw() {
    if (!mounted) return;
    setState(() {});
    _sync();
  }

  NpcLook get _look {
    final Object? json = component.document.json;
    return NpcLook.fromDefinition(json is Map<String, Object?> ? json : const <String, Object?>{});
  }

  NpcSkinInfo? get _skinInfo => NpcSkinInfo.fromDocument(component.skinDocument?.json);

  String _skinKey(NpcLook look) => look.skin ?? 'npc:${component.document.id}';

  /// The arm model is the skin document's; the inspector's Arms switch
  /// changes it.
  bool get _slim => _skinInfo?.slim ?? false;

  /// A PNG loaded this session, or the one a local skin keeps in the
  /// workspace.
  LoadedSkin? _loaded(NpcLook look) =>
      SkinImages.forKeyOrWorkspace(_skinKey(look), look.skin == null ? null : component.resources[skinImagePath(look.skin!)]);

  NpcSkinChoice _skinFor(NpcLook look) {
    final bool slim = _slim;
    final LoadedSkin? loaded = _loaded(look);
    if (loaded != null) return NpcSkinChoice(textureId: loaded.textureId, slim: slim);
    return NpcSkinChoice(textureId: npcPlaceholderSkin(component.document.id, slim: slim).textureId, slim: slim);
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

  String get _stageKey => component.document.id;

  @override
  void initState() {
    super.initState();
    _sync(frame: true);
    context.binding.addPostFrameCallback(_watchSeen);
  }

  /// Definition fields the stage draws from (the look, where it stands,
  /// whether it is spawned); the body settings field of each body type too.
  static const List<String> _drawnFields = <String>[
    'type', 'disguise', 'name', 'skin', 'equipment', 'hologram', 'nameplate', 'pose', 'position', 'armorStand',
    'attributes', 'glowing', 'teamColor', 'baby', 'spawned', 'rig',
  ];

  /// Everything the stage draws from [preview]'s inputs, as one string. A
  /// keystroke in a field the stage does not show (combat, tags, modules)
  /// leaves it alone, so the preview neither rebuilds nor redraws.
  static String _drawnKey(NpcPreview preview) {
    final JsonMap definition = asMap(preview.document.json);
    final JsonMap drawn = <String, Object?>{
      for (final String field in <String>[..._drawnFields, ...bodySettingsTypes.keys])
        if (definition.containsKey(field)) field: definition[field],
    };
    return <Object?>[
      preview.document.id,
      jsonEncode(drawn),
      preview.skinDocument?.source.hashCode,
      preview.route == null ? '' : jsonEncode(preview.route),
      preview.rig?.source.hashCode,
      <int>[for (final AnimusDocument animation in preview.animations) animation.source.hashCode].join(','),
      identityHashCode(preview.resources),
      preview.collapsible,
      preview.neighbours != null,
    ].join('|');
  }

  @override
  bool shouldRebuild(NpcPreview newComponent) => _drawnKey(newComponent) != _drawnKey(component);

  @override
  void didUpdateWidget(NpcPreview oldComponent) {
    super.didUpdateWidget(oldComponent);
    final bool other = oldComponent.document.id != component.document.id;
    if (other || _drawnKey(oldComponent) != _drawnKey(component)) {
      _sync(frame: other);
    } else if (!identical(oldComponent.neighbours, component.neighbours) && _sync()) {
      // Someone else moved: the line counts them again.
      setState(() {});
    }
  }

  @override
  void dispose() {
    _playing = false;
    _seen?.disconnect();
    _terrain.dispose();
    _live.dispose();
    super.dispose();
  }

  /// Asks where the spawned NPC is only while its stage is on screen.
  void _watchSeen() {
    if (!mounted) return;
    final web.Element? stage = web.document.querySelector('[data-npc-stage="${_stageKey.replaceAll('"', r'\"')}"]');
    if (stage == null) return;
    _seen = web.IntersectionObserver(((JSArray<web.IntersectionObserverEntry> entries, web.IntersectionObserver _) {
      final List<web.IntersectionObserverEntry> seen = entries.toDart;
      if (seen.isEmpty) return;
      final bool now = seen.last.isIntersecting;
      if (now == _live.seen) return;
      _live.seen = now;
      if (now) _live.wake();
    }).toJS)
      ..observe(stage);
  }

  /// Rebuilds the scene when the definition, the skin, the rig, the
  /// playhead, the terrain or the neighbours changed; true when the
  /// neighbours layer changed.
  bool _sync({bool frame = false}) {
    final NpcLook look = _look;
    _fetchSkin(look);
    final NpcSkinChoice skin = _skinFor(look);
    final JsonMap? route = _showRoute ? component.route : null;
    final AnimusDocument? rig = component.rig;
    final JsonMap definition = asMap(component.document.json);
    final JsonMap position = asMap(definition['position']);
    final String? world = asString(position['world']);
    final (double, double, double) home = (asDouble(position['x']) ?? 0, asDouble(position['y']) ?? 0, asDouble(position['z']) ?? 0);
    _live.follow(definition['spawned'] == true && _terrain.on ? component.document.id : null);
    final TerrainLivePosition? live = _live.at?.world == world ? _live.at : null;
    _terrain.show(world, McVec3(home.$1, home.$2, home.$3),
        path: terrainRoutePath(route == null ? null : RouteSpec(route), home: home, live: live == null ? null : (live.x, live.y, live.z)));
    final bool nearChanged = _near.update(
      entries: _showNearby ? component.neighbours : null,
      self: component.document.id,
      world: world,
      home: home,
      radius: _terrain.radius,
      skin: component.skinTexture,
      onSkin: _redraw,
    );
    final String signature = '${_drawnKey(component)}|${skin.textureId}|${skin.slim}|$_showRoute'
        '|$_animation|$_tick|${identityHashCode(_terrain.result)}|${_terrain.on}|$live|${identityHashCode(_near.nodes)}';
    if (signature == _shown && !frame) return nearChanged;
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
    final NpcScene placed = live == null ? body : _atLive(body, live, home);
    final NpcScene subject = route == null ? placed : withRoute(placed, RouteSpec(route), home, yawDeg: look.yawDeg);
    final NpcScene scene = _scene = _near.nodes.isEmpty
        ? subject
        : NpcScene(
            scene: McScene(<McSceneNode>[...subject.scene.nodes, ..._near.nodes], frame: subject.scene.frame, glow: subject.scene.glow, ground: subject.scene.ground),
            texts: <NpcText>[...subject.texts, ..._near.texts],
            notes: subject.notes,
            presentable: subject.presentable,
          );
    _stage.show(_terrain.apply(scene.scene), scene.texts, yawDeg: look.yawDeg, frame: frame, reach: _terrain.reach, focus: placed.scene.frame, occluders: _near.occluders);
    return nearChanged;
  }

  /// [body] standing where the spawned NPC is now, relative to [home]: its
  /// nodes, texts and framing moved there, so the camera follows it over the
  /// blocks it walks on.
  static NpcScene _atLive(NpcScene body, TerrainLivePosition live, (double, double, double) home) {
    final McVec3 by = McVec3(live.x - home.$1, live.y - home.$2, live.z - home.$3);
    final McMat4 move = McMat4.translation(by.x, by.y, by.z);
    return NpcScene(
      scene: McScene(<McSceneNode>[for (final McSceneNode node in body.scene.nodes) mcMovedNode(node, move)],
          frame: <McVec3>[for (final McVec3 point in body.scene.frame) point + by], glow: body.scene.glow, ground: body.scene.ground),
      texts: <NpcText>[for (final NpcText text in body.texts) text.moved(by)],
      notes: body.notes,
      presentable: body.presentable,
    );
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

  void _pickAnimation(String? id) {
    setState(() {
      _animation = id;
      _backToIdle = false;
      _tick = 0;
      _playing = false;
    });
    _sync();
  }

  /// Shows the layers in [next] (terrain, route, nearby) and hides the rest.
  void _showLayers(Set<String> next) {
    if (_terrain.available && next.contains('terrain') != _terrain.on) _terrain.toggle(next.contains('terrain'));
    setState(() {
      _showRoute = next.contains('route');
      _showNearby = next.contains('nearby');
    });
    _sync(frame: true);
  }

  /// A click on the stage: the neighbour under it offers to open.
  void _pick(double x, double y) {
    final JsonMap position = asMap(asMap(component.document.json)['position']);
    final (double, double, double) home = (asDouble(position['x']) ?? 0, asDouble(position['y']) ?? 0, asDouble(position['z']) ?? 0);
    final NpcPlace? hit = _near.pick(_stage.camera, x, y, widthPx: _width, heightPx: _height, home: home);
    setState(() {
      _picked = hit;
      _pickedAt = hit == null ? null : (x, y);
    });
  }

  double _width = 0;
  double _height = 0;

  /// The card a picked neighbour shows at the click: its name and Open.
  List<Widget> _overlay(double width, double height) {
    _width = width;
    _height = height;
    final NpcPlace? picked = _picked;
    final (double, double)? at = _pickedAt;
    if (picked == null || at == null) return const <Widget>[];
    final PickerScope? scope = PickerScope.of(context);
    return <Widget>[
      dom.div(
        classes: 'animus-stage-pick',
        attributes: const <String, String>{'data-gizmo': '', 'role': 'dialog', 'aria-label': 'Nearby NPC'},
        // The stylesheet keeps the card inside the canvas around this point.
        styles: dom.Styles(raw: <String, String>{'--pick-x': '${at.$1.round()}px', '--pick-y': '${at.$2.round()}px'}),
        <Widget>[
          ActionRow(
            dense: true,
            leading: <Widget>[Expanded(child: Text.truncated(picked.name, weight: FontWeight.w500, cssStyle: const ArcaneStyleData(display: Display.block)))],
            cancel: RowAction('Close', () => setState(() => _picked = null), icon: ArcaneIcon.x(), iconOnly: true),
            primary: scope == null ? null : RowAction('Open', () => scope.onNavigate(DocumentRoute(scope.workspaceId, DocumentKind.npc, picked.id))),
          ),
        ],
      ),
    ];
  }

  @override
  Widget build(BuildContext context) {
    final NpcLook look = _look;
    final NpcScene scene = _scene!;
    final bool player = look.isPlayer;
    final NpcSkinInfo? info = _skinInfo;
    final LoadedSkin? loaded = _loaded(look);
    final TerrainLivePosition? live = _live.at;
    final TerrainLine? terrain = _terrain.line();
    final bool skinFailed = loaded == null && look.skin != null && info?.textureUrl != null && SkinImages.textures.stateOf(look.skin!)?.status == SkinTextureStatus.failed;
    final bool nearby = component.neighbours != null;
    final Set<String> shown = <String>{
      if (_terrain.on && _terrain.available) 'terrain',
      if (_showRoute && component.route != null) 'route',
      if (_showNearby && nearby) 'nearby',
    };
    final List<ChoiceOption<String>> layers = <ChoiceOption<String>>[
      if (_terrain.available) const ChoiceOption<String>('terrain', 'Terrain'),
      if (component.route != null) const ChoiceOption<String>('route', 'Route'),
      if (nearby) const ChoiceOption<String>('nearby', 'Nearby'),
    ];
    final String id = component.document.id;
    final String? animationName = _animation == null ? null : _animationLabel(_animation!);
    return McStageFrame(
      title: 'Preview',
      controller: _stage,
      label: 'Preview of ${guiPlainText(component.document.title)}',
      collapsible: component.collapsible,
      onPick: nearby ? _pick : null,
      overlay: (_, double width, double height) => _overlay(width, height),
      attributes: <String, String>{
        'data-npc-stage': _stageKey,
        'data-terrain-meshes': '${_terrain.on ? _terrain.result?.nodes.length ?? 0 : 0}',
        'data-terrain-chunks': '${_terrain.on ? _terrain.result?.chunks ?? 0 : 0}',
        'data-neighbours': '${_near.near.shown.length}',
        'data-neighbours-total': '${_near.near.total}',
        if (player) 'data-skin': loaded?.source ?? 'stand-in',
        'data-scale': '${look.scale}',
        if (_animation != null) ...<String, String>{'data-animation': _animation!, 'data-tick': '${_tick.floor()}'},
        if (live != null) 'data-live-position': '${live.world} ${live.x} ${live.y} ${live.z}',
      },
      toggles: <Widget>[
        if (layers.isNotEmpty)
          StageShowControls(
            id: 'npc-show-$id',
            options: layers,
            values: shown,
            onChanged: _showLayers,
            fits: _layersFit,
            onFits: (bool fits) => setState(() => _layersFit = fits),
          ),
        if (component.rig != null)
          AppMenu(
            id: 'npc-animation-$id',
            label: animationName ?? 'Rest pose',
            outline: true,
            attributes: <String, String>{'aria-label': 'Animation, ${animationName ?? 'rest pose'}'},
            entries: <AppMenuEntry>[
              AppMenuEntry('Rest pose', () => _pickAnimation(null), icon: _animation == null ? ArcaneIcon.check() : null),
              for (final AnimusDocument animation in component.animations)
                AppMenuEntry(_animationLabel(animation.id), () => _pickAnimation(animation.id), icon: _animation == animation.id ? ArcaneIcon.check() : null),
            ],
          ),
      ],
      more: stageShowEntries(
        options: layers,
        values: shown,
        onChanged: _showLayers,
        layers: !_layersFit,
        radius: (_terrain.available && _terrain.on) || (nearby && _showNearby) ? _terrain.radius : null,
        onRadius: (int value) {
          _terrain.setRadius(value);
          _sync();
        },
      ),
      actions: <RowAction>[
        if (component.rig != null)
          RowAction(_playing ? 'Pause' : 'Play', _animation == null ? null : _play, icon: _playing ? ArcaneIcon.pause() : ArcaneIcon.play(), iconOnly: true, pressed: _playing),
        if (skinFailed) RowAction('Retry skin', () => _fetchSkin(look, retry: true), icon: ArcaneIcon.refreshCw(), iconOnly: true),
        if (terrain?.line == 'Terrain did not load') RowAction('Retry terrain', _terrain.retry, icon: ArcaneIcon.refreshCw(), iconOnly: true),
      ],
      status: <StageNote>[
        if (terrain?.line case final String line) StageNote(line, terrain?.detail),
        if (_showNearby && _near.near.line != null)
          StageNote(_near.near.line!, _near.near.capped ? 'Only the ${_near.near.shown.length} nearest are drawn; click one to open it.' : 'Click one to open it.'),
        if (skinFailed) StageNote('Skin did not load', 'Skin: ${_skinText(look, info, loaded, _slim)}.'),
        if (!scene.presentable) StageNote('Not shown in game', '${bodyLabel(look.type)} is a body no server shows.'),
        if (_backToIdle) const StageNote('Back to idle', 'A clip that plays once ends in the rig\'s idle animation, as on the server.'),
      ],
      extra: <String>[
        if (terrain?.line == null && terrain?.detail != null) terrain!.detail!,
        if (player && !skinFailed) 'Skin: ${_skinText(look, info, loaded, _slim)}.',
        if (live != null) 'Now at ${live.x.floor()}, ${live.y.floor()}, ${live.z.floor()} in ${live.world}.',
        'Drawn in this browser; nothing is sent.',
      ],
      notes: scene.notes,
    );
  }

  /// An animation's short name and the rig's role for it: "idle (golem_idle)".
  String _animationLabel(String id) {
    final Object? rig = component.rig?.json;
    final Object? names = rig is Map<String, Object?> ? rig['animations'] : null;
    final List<String> roles = <String>[
      if (names is Map<String, Object?>)
        for (final MapEntry<String, Object?> entry in names.entries)
          if (entry.value == id) entry.key,
    ];
    final String short = id.substring(id.indexOf(':') + 1);
    return roles.isEmpty ? short : roles.join(', ');
  }

  String _skinText(NpcLook look, NpcSkinInfo? info, LoadedSkin? loaded, bool slim) {
    final String model = slim ? 'slim arms' : 'classic arms';
    if (loaded != null) return '${loaded.source}, $model';
    final String placeholder = 'the ${defaultPlaceholderLook(component.document.id).label} stand-in, $model';
    if (look.skin == null) return 'none, so the preview shows $placeholder';
    if (info == null) return 'not in this workspace, so the preview shows $placeholder';
    if (info.textureUrl == null) return 'no picture in this workspace, so the preview shows $placeholder';
    final SkinTextureState? state = SkinImages.textures.stateOf(look.skin!);
    if (state?.status == SkinTextureStatus.failed) return 'did not load (${state!.message}), so the preview shows $placeholder';
    return 'loading';
  }
}
