/// The rig editor: bone tree, 3D stage with the skeleton and move, rotate
/// and scale gizmos, the bone inspector and the keyframe timeline. The stage
/// samples with the Dart port of the server's sampler, so a pose here is the
/// pose the server sends. Every change is a workspace commit (undoable);
/// gizmo drags preview locally and commit once on release.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:js_interop';
import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart' hide Easing;
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../config/docs_links.dart';
import '../../logic/bbmodel_import.dart' show sanitizeName;
import '../../logic/data_folder_export.dart';
import '../../logic/rig_edit.dart';
import '../../logic/rig_gizmo_edit.dart';
import '../../logic/rig_sampler.dart';
import '../../mc/npc/npc_look.dart';
import '../../mc/npc/npc_scene.dart';
import '../../mc/rig/rig_scene.dart';
import '../../mc/rigs/mc_rigs.dart';
import '../../mc/scene/mc_camera.dart';
import '../../mc/scene/mc_math.dart';
import '../../mc/scene/mc_scene.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../services/browser.dart';
import '../../state/editor_store.dart';
import '../../state/workspace_repository.dart';
import '../gui/gui_fields.dart';
import '../info_tip.dart';
import '../mc/mc_asset_loader.dart';
import '../mc/mc_stage.dart';
import 'rig_actions.dart';
import 'rig_animations_panel.dart';
import 'rig_gizmo.dart';
import 'rig_inspector.dart';
import 'rig_timeline.dart';
import 'rig_tree.dart';

/// Bodies the stage can show under a `body: shown` rig: every type a
/// transport presents.
const Map<String, String> previewBodies = <String, String>{
  'minecraft:player': 'Player',
  'minecraft:armor_stand': 'Armor stand',
  'minecraft:horse': 'Horse',
  'minecraft:llama': 'Llama',
  'minecraft:turtle': 'Turtle',
  'minecraft:frog': 'Frog',
  'minecraft:armadillo': 'Armadillo',
  'minecraft:ender_dragon': 'Ender dragon',
};

const String _restPose = '';

/// The phone layout shows one of these under the stage at a time.
enum RigPane { tree, inspector, timeline }

class RigEditor extends StatefulWidget {
  const RigEditor({required this.store, required this.rig, required this.onOpen, this.animationId, super.key});

  final EditorStore store;
  final AnimusDocument rig;

  /// The animation to open with, when the editor came from one.
  final String? animationId;

  /// Opens another document (an imported rig, a new animation).
  final void Function(DocumentKind kind, String id) onOpen;

  @override
  State<RigEditor> createState() => _RigEditorState();
}

class _RigEditorState extends State<RigEditor> {
  final McStageController _stage = McStageController();
  final Map<String, (Uint8List, String)> _textures = <String, (Uint8List, String)>{};

  String? _selected;
  String _animation = _restPose;
  double _tick = 0;
  bool _playing = false;
  double _speed = 1;
  double? _lastFrame;

  /// After a once clip ends the preview plays the rig's idle, as the server
  /// does; this is the idle clip's playback tick.
  double? _idleTick;
  GizmoMode _mode = GizmoMode.move;
  JsonMap? _dragRig;
  JsonMap? _dragAnimation;
  TrackTarget _keyTarget = TrackTarget.rotation;
  Blend _keyBlend = Blend.replace;
  int? _track;
  (int, int)? _keyframe;
  int? _event;
  String _bodyType = 'minecraft:player';
  String _newAnimation = '';
  String? _problem;
  List<String> _notes = const <String>[];
  Map<String, BonePose> _poses = const <String, BonePose>{};
  bool _framed = false;
  bool _busy = false;
  RigPane _pane = RigPane.timeline;

  JSFunction? _move;
  JSFunction? _up;

  String get _rigId => component.rig.id;

  JsonMap get _rigJson {
    final Object? json = component.rig.json;
    return _dragRig ?? (json is JsonMap ? json : <String, Object?>{'bones': <Object?>[]});
  }

  List<AnimusDocument> get _allAnimations => <AnimusDocument>[
    for (final AnimusDocument document in component.store.workspace?.documents ?? const <AnimusDocument>[])
      if (document.kind == DocumentKind.animation) document,
  ];

  List<AnimusDocument> get _animations => <AnimusDocument>[
    for (final AnimusDocument document in _allAnimations)
      if (document.json is JsonMap && (document.json! as JsonMap)['rig'] == _rigId) document,
  ];

  AnimusDocument? get _animationDocument {
    if (_animation == _restPose) return null;
    return component.store.shown(DocumentKind.animation, _animation);
  }

  JsonMap? get _animationJson {
    if (_dragAnimation != null) return _dragAnimation;
    final Object? json = _animationDocument?.json;
    return json is JsonMap ? json : null;
  }

  static Clip? _parse(Object? json) {
    if (json is! JsonMap) return null;
    try {
      return Clip.fromJson(json);
    } on Object {
      return null;
    }
  }

  Clip? get _clip => _parse(_animationJson);

  /// The rig's idle clip, when it names one that exists for this rig.
  Clip? get _idle {
    final Object? id = animationNames(_rigJson)['idle'];
    if (id is! String) return null;
    final Object? json = component.store.document(DocumentKind.animation, id)?.json;
    return json is JsonMap && json['rig'] == _rigId ? _parse(json) : null;
  }

  double get _clipTime => _clip?.clipTime(_tick) ?? 0;

  static String _firstError(AnimusDocument? document) {
    for (final Diagnostic diagnostic in document?.diagnostics ?? const <Diagnostic>[]) {
      if (diagnostic.severity == DiagnosticSeverity.error) return ' ${diagnostic.path}: ${diagnostic.message}.';
    }
    return '';
  }

  @override
  void initState() {
    super.initState();
    final String? start = component.animationId;
    if (start != null && component.store.document(DocumentKind.animation, start) != null) _animation = start;
    final List<JsonMap> bones = bonesOf(_rigJson);
    if (bones.isNotEmpty) _selected = '${bones.first['id']}';
    McAssetLoader.addListener(_onPack);
    _refresh();
  }

  @override
  void didUpdateWidget(RigEditor oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (component.animationId != oldComponent.animationId && component.animationId != null) {
      _animation = component.animationId!;
    }
    if (_selected != null && boneById(_rigJson, _selected!) == null) {
      final List<JsonMap> bones = bonesOf(_rigJson);
      _selected = bones.isEmpty ? null : '${bones.first['id']}';
    }
    _refresh();
  }

  @override
  void dispose() {
    _playing = false;
    _release();
    McAssetLoader.removeListener(_onPack);
    super.dispose();
  }

  void _onPack() {
    if (mounted) _refresh();
  }

  // --- scene -----------------------------------------------------------------

  String _texture(String path) {
    final Uint8List? bytes = component.store.workspace?.resources[path];
    if (bytes == null) return 'missing:$path';
    final (Uint8List, String)? known = _textures[path];
    if (known != null && identical(known.$1, bytes)) return known.$2;
    final String id = 'workspace:$path#${_textures.length}';
    _textures[path] = (bytes, id);
    McAssetLoader.registerImage(id, 'data:image/png;base64,${base64Encode(bytes)}');
    return id;
  }

  /// Samples the rig at the playhead and shows it on the stage.
  void _refresh() {
    final JsonMap rigJson = _rigJson;
    final Rig rig;
    try {
      rig = Rig.fromJson(rigJson);
    } on Object {
      _problem = 'The rig is invalid.${_firstError(component.rig)}';
      _stage.show(McScene.empty, const <NpcText>[], yawDeg: 0);
      if (mounted) setState(() {});
      return;
    }
    final Clip? clip = _clip;
    _problem = _animation != _restPose && clip == null
        ? 'The animation is invalid.${_firstError(_animationDocument)} Showing the rest pose.'
        : null;
    final Clip? idle = _idleTick == null ? null : _idle;
    final List<Layer> layers = <Layer>[
      if (idle != null) Layer(idle) else if (clip != null && _idleTick == null) Layer(clip),
    ];
    final Map<String, BonePose> poses = sampleRig(rig, layers, 0, idle != null ? _idleTick! : _tick);
    final RigScene scene = rigScene(
      rigJson,
      poses,
      resources: component.store.workspace?.resources ?? const <String, Uint8List>{},
      textureId: _texture,
      equipment: socketSampleItems,
    );
    final List<McSceneNode> nodes = <McSceneNode>[];
    final List<NpcText> texts = <NpcText>[];
    final McBounds bounds = scene.bounds;
    final List<McVec3> frame = <McVec3>[
      for (final double x in <double>[bounds.min.x, bounds.max.x])
        for (final double y in <double>[bounds.min.y, bounds.max.y])
          for (final double z in <double>[bounds.min.z, bounds.max.z]) McVec3(x, y, z),
    ];
    if (rigJson['body'] == 'shown') {
      final NpcScene body = npcScene(NpcLook.fromDefinition(<String, Object?>{'type': _bodyType}));
      nodes.addAll(body.scene.nodes);
      texts.addAll(body.texts);
      frame.addAll(body.scene.frame);
    }
    nodes.addAll(scene.nodes);
    texts.addAll(scene.texts);
    _poses = poses;
    _notes = scene.notes;
    _stage.show(McScene(nodes, frame: frame), texts, yawDeg: 0, frame: !_framed);
    _framed = true;
    if (mounted) setState(() {});
  }

  // --- commits ---------------------------------------------------------------

  Future<void> _commit({JsonMap? rig, Map<String, JsonMap> animations = const <String, JsonMap>{}, String? coalesce}) async {
    try {
      await component.store.commit(
        documents: <DocumentChange>[
          if (rig != null) DocumentChange(DocumentKind.rig, _rigId, encodeDocument(rig)),
          for (final MapEntry<String, JsonMap> animation in animations.entries)
            DocumentChange(DocumentKind.animation, animation.key, encodeDocument(animation.value)),
        ],
        coalesce: coalesce,
      );
    } on Object catch (failure) {
      web.console.warn('rig commit: $failure'.toJS);
      ArcaneSonner.error('Not saved: the browser refused to store it. Try again or export the workspace.');
    }
  }

  /// Runs a rig edit, reporting a refused one instead of saving it.
  void _edit(JsonMap Function(JsonMap rig) change, {String? coalesce, Map<String, JsonMap> Function(JsonMap rig)? animations}) {
    try {
      final JsonMap next = change(_rigJson);
      unawaited(_commit(rig: next, animations: animations?.call(next) ?? const <String, JsonMap>{}, coalesce: coalesce));
    } on RigEditException catch (refused) {
      ArcaneSonner.warning(refused.message);
    }
  }

  void _editAnimation(JsonMap Function(JsonMap animation) change, {String? coalesce}) {
    final JsonMap? json = _animationJson;
    if (json == null) return;
    try {
      unawaited(_commit(animations: <String, JsonMap>{_animation: change(json)}, coalesce: coalesce));
    } on RigEditException catch (refused) {
      ArcaneSonner.warning(refused.message);
    } on FormatException catch (invalid) {
      ArcaneSonner.warning(invalid.message);
    }
  }

  Map<String, JsonMap> _eachAnimation(JsonMap Function(JsonMap animation) change) => <String, JsonMap>{
    for (final AnimusDocument document in _animations) document.id: change(document.json! as JsonMap),
  };

  void _addBone() {
    final String id = freeBoneId(_rigJson, 'bone');
    _edit((JsonMap rig) => addBone(rig, id, parent: _selected));
    setState(() => _selected = id);
  }

  void _rename(String from, String to) {
    final String target = to.trim();
    if (target.isEmpty || target == from) return;
    _edit(
      (JsonMap rig) => renameBone(rig, from, target),
      animations: (JsonMap _) => _eachAnimation((JsonMap animation) => renameTrackBone(animation, from, target)),
    );
    setState(() => _selected = target);
  }

  void _delete(String bone) {
    final String? parent = parentOf(boneById(_rigJson, bone) ?? const <String, Object?>{});
    _edit(
      (JsonMap rig) => deleteBone(rig, bone),
      animations: (JsonMap _) => _eachAnimation((JsonMap animation) => dropTrackBone(animation, bone)),
    );
    setState(() {
      _selected = parent;
      _track = null;
      _keyframe = null;
    });
  }

  // --- playback --------------------------------------------------------------

  void _play() {
    if (_playing) {
      setState(() => _playing = false);
      return;
    }
    final Clip? clip = _clip;
    if (clip == null) return;
    if (_idleTick != null || ((clip.loop == LoopMode.once || clip.loop == LoopMode.hold) && _tick >= clip.durationTicks)) {
      _tick = 0;
      _idleTick = null;
    }
    _playing = true;
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
          _playing = false;
          return;
        }
        if (last != null) {
          final double step = (time - last) / 50 * _speed;
          if (_idleTick != null) {
            _idleTick = _idleTick! + step;
          } else {
            double tick = _tick + step;
            final double d = clip.durationTicks;
            switch (clip.loop) {
              case LoopMode.once:
                if (tick >= d) {
                  tick = d;
                  // The server ends a once clip and goes back to idle.
                  if (_idle != null) {
                    _idleTick = 0;
                  } else {
                    _playing = false;
                  }
                }
              case LoopMode.hold:
                if (tick >= d) {
                  tick = d;
                  _playing = false;
                }
              case LoopMode.loop:
                tick = tick % d;
              case LoopMode.pingpong:
                tick = tick % (2 * d);
            }
            _tick = tick;
          }
          _refresh();
        }
        if (_playing) _frame();
      }).toJS,
    );
  }

  void _scrub(double value) {
    _tick = value;
    _idleTick = null;
    _playing = false;
    _refresh();
  }

  // --- gizmo -----------------------------------------------------------------

  JsonMap _gizmoEdit(JsonMap? animation, JsonMap rig, String bone, int axis, double amount, double time) => animation == null
      ? restGizmoEdit(rig, bone, _mode, axis, amount)
      : keyGizmoEdit(animation, bone, _mode, axis, amount, time, track: _track, blend: _keyBlend, pose: _poses[bone]);

  void _grab(GizmoGrab grab, web.PointerEvent event) {
    final String? bone = _selected;
    if (bone == null) return;
    _playing = false;
    _idleTick = null;
    _release();
    final JsonMap startRig = _rigJson;
    final JsonMap? startAnimation = _animationJson;
    final double time = _clipTime;
    _move = ((web.Event moved) {
      final double x = eventNumber(moved, 'clientX'), y = eventNumber(moved, 'clientY');
      final double amount = switch (_mode) {
        GizmoMode.move => grab.blocks(x, y),
        GizmoMode.rotate => grab.along(x, y) * 0.75,
        GizmoMode.scale => 1 + grab.along(x, y) / (grab.armPixels <= 0 ? 100 : grab.armPixels),
      };
      try {
        final JsonMap next = _gizmoEdit(startAnimation, startRig, bone, grab.axis, amount, time);
        if (startAnimation == null) {
          _dragRig = next;
        } else {
          _dragAnimation = next;
        }
      } on RigEditException {
        return;
      }
      _refresh();
    }).toJS;
    _up = ((web.Event _) {
      final JsonMap? rig = _dragRig;
      final JsonMap? animation = _dragAnimation;
      _release();
      if (rig != null || animation != null) {
        unawaited(_commit(rig: rig, animations: animation == null ? const <String, JsonMap>{} : <String, JsonMap>{_animation: animation}).then((void _) {
          _dragRig = null;
          _dragAnimation = null;
          _refresh();
        }));
      }
    }).toJS;
    web.window.addEventListener('pointermove', _move);
    web.window.addEventListener('pointerup', _up);
    web.window.addEventListener('pointercancel', _up);
  }

  /// An arrow key on a focused handle: one step along its axis, committed
  /// at once (repeated presses share one undo step).
  void _nudge(int axis, int direction, bool fine) {
    final String? bone = _selected;
    if (bone == null) return;
    final JsonMap? animation = _animationJson;
    try {
      final JsonMap next = _gizmoEdit(animation, _rigJson, bone, axis, _mode.nudge(direction, fine: fine), _clipTime);
      final String coalesce = 'nudge:$_animation:$bone:${_mode.name}:$axis';
      unawaited(animation == null
          ? _commit(rig: next, coalesce: coalesce)
          : _commit(animations: <String, JsonMap>{_animation: next}, coalesce: coalesce));
    } on RigEditException catch (refused) {
      ArcaneSonner.warning(refused.message);
    }
  }

  void _release() {
    if (_move != null) web.window.removeEventListener('pointermove', _move);
    if (_up != null) {
      web.window.removeEventListener('pointerup', _up);
      web.window.removeEventListener('pointercancel', _up);
    }
    _move = null;
    _up = null;
  }

  static num _round(double value) {
    final double rounded = (value * 100000).roundToDouble() / 100000;
    return rounded == rounded.roundToDouble() ? rounded.round() : rounded;
  }


  // --- import and export -----------------------------------------------------

  Future<void> _import() async {
    setState(() => _busy = true);
    try {
      final String? id = await importBbmodelFile(component.store, _rigId.substring(0, _rigId.indexOf(':')));
      if (id != null) component.onOpen(DocumentKind.rig, id);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _downloadServerFiles() {
    final WorkspaceSnapshot? workspace = component.store.workspace;
    if (workspace == null) return;
    final RigBundle bundle = rigBundle(workspace, _rigId);
    final String name = '${_rigId.replaceAll(':', '-').replaceAll('/', '-')}-server-files.zip';
    downloadBytes(name, zipFiles(bundle.files));
    final int animations = bundle.files.keys.where((String path) => path.startsWith('animations/')).length;
    final int pack = bundle.files.keys.where((String path) => path.startsWith('pack/')).length;
    ArcaneSonner.info(
      'Saved $name: rig, $animations animation${animations == 1 ? '' : 's'}, $pack pack file${pack == 1 ? '' : 's'}. '
      'Unzip into plugins/Animus, then run /animus pack build.',
    );
    if (bundle.missing.isNotEmpty) ArcaneSonner.warning('Not in this workspace, left out: ${bundle.missing.join(', ')}.');
  }

  void _createAnimation() {
    final String name = sanitizeName(_newAnimation, 'animation');
    _create('$_rigId/$name', name: name);
  }

  /// Creates an empty animation [id] for this rig, naming it [name] in the
  /// rig's map when that name is free.
  void _create(String id, {String? name}) {
    if (!documentIdPattern.hasMatch(id)) {
      ArcaneSonner.warning('Invalid animation id $id. Use lowercase letters, digits, - and _.');
      return;
    }
    if (component.store.document(DocumentKind.animation, id) != null) {
      ArcaneSonner.warning('Animation $id already exists.');
      return;
    }
    final JsonMap names = animationNames(_rigJson);
    final bool named = name != null && !names.containsKey(name) && animationNamePattern.hasMatch(name);
    unawaited(_commit(
      rig: named ? setAnimationName(_rigJson, name, id) : null,
      animations: <String, JsonMap>{id: newAnimation(id, _rigId)},
    ).then((void _) {
      if (name != null && !named) ArcaneSonner.info('Created $id. Name $name is taken; name it under Animation names.');
      _animation = id;
      _newAnimation = '';
      _tick = 0;
      _idleTick = null;
      _track = null;
      _keyframe = null;
      _event = null;
      _refresh();
    }));
  }

  void _addKeyframe({bool newTrack = false}) {
    final String? bone = _selected;
    final JsonMap? animation = _animationJson;
    final BonePose? pose = bone == null ? null : _poses[bone];
    if (bone == null || animation == null || pose == null) return;
    final TrackTarget target = _keyTarget;
    final Blend blend = target == TrackTarget.visible ? Blend.replace : _keyBlend;
    final int? track = newTrack ? null : trackFor(animation, bone, target, preferred: _track);
    final double time = _clipTime;
    final Object value;
    if (target == TrackTarget.visible) {
      final Object? own = track == null ? null : trackValueAt(tracksOf(animation)[track], time);
      value = own is bool ? own : pose.ownVisible;
    } else {
      value = cleanVector(ownTrackValue(animation, track, target, trackBlend(animation, track, blend), time, pose));
    }
    if (newTrack) {
      _editAnimation((JsonMap json) => addTrack(json, bone, target, blend, time, value));
      setState(() => _track = tracksOf(animation).length);
    } else {
      _editAnimation((JsonMap json) => setKeyframe(json, bone, target, time, value, track: track, blend: blend));
      if (track == null) setState(() => _track = tracksOf(animation).length);
    }
  }

  // --- view ------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    final JsonMap rig = _rigJson;
    final List<JsonMap> bones = bonesOf(rig);
    final GizmoOverlay overlay = GizmoOverlay(
      bones: <String>[for (final JsonMap entry in bones) '${entry['id']}'],
      parents: <String, String?>{for (final JsonMap entry in bones) '${entry['id']}': parentOf(entry)},
      poses: _poses,
      selected: _selected,
      mode: _mode,
      onSelect: (String id) => setState(() => _selected = id),
      onGrab: _grab,
      onNudge: _nudge,
    );
    final JsonMap names = animationNames(rig);
    String label(AnimusDocument document) {
      final List<String> named = <String>[
        for (final MapEntry<String, Object?> entry in names.entries)
          if (entry.value == document.id) entry.key,
      ];
      final String short = document.id.substring(document.id.indexOf(':') + 1);
      return named.isEmpty ? short : '${named.join(', ')} ($short)';
    }

    return dom.section(classes: 'animus-panel animus-rig-editor', attributes: <String, String>{'data-rig': _rigId}, <Widget>[
      _toolbar(rig),
      if (_problem != null) dom.p(classes: 'animus-error', attributes: const <String, String>{'role': 'alert'}, <Widget>[Component.text(_problem!)]),
      dom.div(classes: 'animus-rig-layout', attributes: <String, String>{'data-pane': _pane.name}, <Widget>[
        dom.div(classes: 'animus-rig-stage', <Widget>[
          dom.div(classes: 'animus-segmented', attributes: const <String, String>{'role': 'group', 'aria-label': 'Gizmo'}, <Widget>[
            dom.span(classes: 'animus-rig-gizmo-caption', <Widget>[
              InfoTip(
                tip: _animation == _restPose ? 'Edits pivots, rest rotations and visual scales.' : 'Keys the selected bone at tick ${_round(_clipTime)}.',
                docs: Docs.editorRigs,
                child: const Component.text('Gizmo'),
              ),
            ]),
            for (final GizmoMode mode in GizmoMode.values)
              Button(
                label: mode.label,
                size: ButtonSize.small,
                variant: mode == _mode ? ButtonVariant.primary : ButtonVariant.ghost,
                attributes: <String, String>{'aria-pressed': '${mode == _mode}'},
                onPressed: () => setState(() => _mode = mode),
              ),
            Button.ghost(label: 'Reset view', size: ButtonSize.small, onPressed: _stage.resetCamera),
          ]),
          McStage(
            controller: _stage,
            label: 'Rig ${component.rig.id}${_selected == null ? '' : ', bone $_selected selected'}',
            overlay: (McCamera camera, double width, double height) => overlay.build(camera, width, height),
          ),
          if (_notes.isNotEmpty)
            dom.ul(classes: 'animus-npc-notes', <Widget>[
              for (final String note in _notes) dom.li(<Widget>[Component.text(note)]),
            ]),
          dom.div(classes: 'animus-segmented animus-rig-panes', attributes: const <String, String>{'role': 'group', 'aria-label': 'Panel'}, <Widget>[
            for (final (RigPane pane, String text) in const <(RigPane, String)>[
              (RigPane.tree, 'Bones'),
              (RigPane.inspector, 'Inspector'),
              (RigPane.timeline, 'Timeline'),
            ])
              Button(
                label: text,
                size: ButtonSize.small,
                variant: pane == _pane ? ButtonVariant.primary : ButtonVariant.ghost,
                attributes: <String, String>{'aria-pressed': '${pane == _pane}'},
                onPressed: () => setState(() => _pane = pane),
              ),
          ]),
        ]),
        dom.div(classes: 'animus-rig-pane is-tree', <Widget>[
          RigTree(
            rig: rig,
            selected: _selected,
            onSelect: (String id) => setState(() => _selected = id),
            onAdd: _addBone,
            onDelete: _delete,
            onRename: _rename,
            onEdit: (JsonMap Function(JsonMap rig) change) => _edit(change),
          ),
        ]),
        dom.div(classes: 'animus-rig-pane is-inspector', <Widget>[
          RigInspector(rig: rig, bone: _selected, onEdit: (JsonMap Function(JsonMap rig) change) => _edit(change)),
        ]),
        dom.div(classes: 'animus-rig-pane is-timeline', <Widget>[
          RigTimeline(
            animations: <(String, String)>[
              ('Rest pose', _restPose),
              for (final AnimusDocument document in _animations) (label(document), document.id),
            ],
            selected: _animation,
            animation: _animationJson,
            valid: _clip != null,
            time: _clipTime,
            playing: _playing,
            speed: _speed,
            status: _idleTick == null ? null : 'Clip ended, playing idle. Press Play to restart.',
            names: RigAnimationsPanel(
              rig: rig,
              documents: <String, String?>{
                for (final AnimusDocument document in _allAnimations)
                  document.id: document.json is JsonMap && (document.json! as JsonMap)['rig'] is String ? (document.json! as JsonMap)['rig']! as String : null,
              },
              onEdit: (JsonMap Function(JsonMap rig) change) => _edit(change),
              onOpen: _open,
              onCreate: (String id) => _create(id),
            ),
            selectedBone: _selected,
            keyTarget: _keyTarget,
            keyBlend: _keyBlend,
            track: _track,
            keyframe: _keyframe,
            event: _event,
            newName: _newAnimation,
            onAnimation: _open,
            onNewName: (String value) => setState(() => _newAnimation = value),
            onCreate: _createAnimation,
            onPlay: _play,
            onScrub: _scrub,
            onSpeed: (double value) => setState(() => _speed = value),
            onKeyTarget: (TrackTarget target) => setState(() => _keyTarget = target),
            onKeyBlend: (Blend blend) => setState(() => _keyBlend = blend),
            onAddKeyframe: _addKeyframe,
            onNewTrack: () => _addKeyframe(newTrack: true),
            onSelectTrack: (int? track) => setState(() {
              _track = track;
              final JsonMap? json = _animationJson;
              if (track != null && json != null && track < tracksOf(json).length) {
                final JsonMap entry = tracksOf(json)[track];
                if (boneById(_rigJson, '${entry['bone']}') != null) _selected = '${entry['bone']}';
                _keyTarget = TrackTarget.values.byName('${entry['target']}');
              }
            }),
            onSelectKeyframe: (int track, int key, double tick) {
              setState(() {
                _keyframe = (track, key);
                _track = track;
                _event = null;
                final JsonMap entry = tracksOf(_animationJson!)[track];
                if (boneById(_rigJson, '${entry['bone']}') != null) _selected = '${entry['bone']}';
                _keyTarget = TrackTarget.values.byName('${entry['target']}');
              });
              _scrub(tick);
            },
            onSelectEvent: (int? event) => setState(() {
              _event = event;
              if (event != null) _keyframe = null;
            }),
            onEdit: (JsonMap Function(JsonMap animation) change, {String? coalesce}) => _editAnimation(change, coalesce: coalesce),
          ),
        ]),
      ]),
    ]);
  }

  void _open(String id) {
    setState(() {
      _animation = id;
      _keyframe = null;
      _track = null;
      _event = null;
      _tick = 0;
      _idleTick = null;
      _playing = false;
    });
    _refresh();
  }

  Widget _toolbar(JsonMap rig) {
    return dom.div(classes: 'animus-rig-toolbar', <Widget>[
      Button.secondary(label: 'Import .bbmodel', icon: ArcaneIcon.upload(), size: ButtonSize.small, disabled: _busy, onPressed: _import),
      Button.secondary(label: 'Download server files', icon: ArcaneIcon.download(), size: ButtonSize.small, onPressed: _downloadServerFiles),
      dom.label(classes: 'animus-rig-field', <Widget>[
        const dom.span(<Widget>[Component.text('Body')]),
        GuiSelect(
          value: rig['body'] == 'shown' ? 'shown' : 'hidden',
          options: const <String>['hidden', 'shown'],
          labels: const <String, String>{'hidden': 'Hidden (rig only)', 'shown': 'Shown (with NPC body)'},
          hiddenLabel: 'Body',
          onChanged: (String? value) => _edit((JsonMap json) => cloneJson(json)..['body'] = value ?? 'hidden'),
        ),
      ]),
      if (rig['body'] == 'shown')
        dom.label(classes: 'animus-rig-field', <Widget>[
          const dom.span(<Widget>[Component.text('Preview body')]),
          GuiSelect(
            value: _bodyType,
            options: mcBodyTypes,
            labels: previewBodies,
            hiddenLabel: 'Preview body',
            onChanged: (String? value) {
              if (value == null) return;
              _bodyType = value;
              _framed = false;
              _refresh();
            },
          ),
        ]),
    ]);
  }
}
