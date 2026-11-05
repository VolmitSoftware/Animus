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

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/bbmodel_import.dart';
import '../../logic/data_folder_export.dart';
import '../../logic/rig_edit.dart';
import '../../logic/rig_sampler.dart';
import '../../mc/npc/npc_look.dart';
import '../../mc/npc/npc_scene.dart';
import '../../mc/rig/rig_scene.dart';
import '../../mc/scene/mc_camera.dart';
import '../../mc/scene/mc_math.dart';
import '../../mc/scene/mc_scene.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../services/browser.dart';
import '../../state/editor_store.dart';
import '../../state/workspace_repository.dart';
import '../mc/mc_asset_loader.dart';
import '../mc/mc_stage.dart';
import 'rig_actions.dart';
import 'rig_gizmo.dart';
import 'rig_timeline.dart';

const List<String> socketSlots = <String>['main_hand', 'off_hand', 'head', 'chest', 'legs', 'feet', 'body', 'saddle'];

/// Bodies the stage can show under a `body: shown` rig.
const List<(String, String)> previewBodies = <(String, String)>[
  ('minecraft:player', 'Player'),
  ('minecraft:armor_stand', 'Armor stand'),
  ('minecraft:horse', 'Horse'),
  ('minecraft:llama', 'Llama'),
  ('minecraft:turtle', 'Turtle'),
  ('minecraft:frog', 'Frog'),
  ('minecraft:armadillo', 'Armadillo'),
];

const String _restPose = '';

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
  final Map<String, String> _drafts = <String, String>{};

  String? _selected;
  String _animation = _restPose;
  double _tick = 0;
  bool _playing = false;
  double _speed = 1;
  double? _lastFrame;
  GizmoMode _mode = GizmoMode.move;
  JsonMap? _dragRig;
  JsonMap? _dragAnimation;
  TrackTarget _keyTarget = TrackTarget.rotation;
  (int, int)? _keyframe;
  String _bodyType = 'minecraft:player';
  String _newAnimation = '';
  String? _problem;
  List<String> _notes = const <String>[];
  Map<String, BonePose> _poses = const <String, BonePose>{};
  bool _framed = false;
  bool _busy = false;

  JSFunction? _move;
  JSFunction? _up;

  String get _rigId => component.rig.id;

  JsonMap get _rigJson {
    final Object? json = component.rig.json;
    return _dragRig ?? (json is JsonMap ? json : <String, Object?>{'bones': <Object?>[]});
  }

  List<AnimusDocument> get _animations => <AnimusDocument>[
    for (final AnimusDocument document in component.store.workspace?.documents ?? const <AnimusDocument>[])
      if (document.kind == DocumentKind.animation && document.json is JsonMap && (document.json! as JsonMap)['rig'] == _rigId) document,
  ];

  AnimusDocument? get _animationDocument {
    if (_animation == _restPose) return null;
    return component.store.document(DocumentKind.animation, _animation);
  }

  JsonMap? get _animationJson {
    if (_dragAnimation != null) return _dragAnimation;
    final Object? json = _animationDocument?.json;
    return json is JsonMap ? json : null;
  }

  Clip? get _clip {
    final JsonMap? json = _animationJson;
    if (json == null) return null;
    try {
      return Clip.fromJson(json);
    } on Object {
      return null;
    }
  }

  double get _clipTime => _clip?.clipTime(_tick) ?? 0;

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
    } on Object catch (failure) {
      _problem = 'The rig cannot be drawn until it is valid: $failure';
      _stage.show(McScene.empty, const <NpcText>[], yawDeg: 0);
      return;
    }
    final Clip? clip = _clip;
    _problem = _animation != _restPose && clip == null ? 'The animation cannot be played until it is valid.' : null;
    final Map<String, BonePose> poses = sampleRig(rig, <Layer>[if (clip != null) Layer(clip)], 0, _tick);
    final RigScene scene = rigScene(
      rigJson,
      poses,
      resources: component.store.workspace?.resources ?? const <String, Uint8List>{},
      textureId: _texture,
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
      ArcaneSonner.error('Could not save: $failure');
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
    setState(() {
      _selected = target;
      _drafts.remove('rename');
    });
  }

  void _delete(String bone) {
    final String? parent = parentOf(boneById(_rigJson, bone) ?? const <String, Object?>{});
    _edit(
      (JsonMap rig) => deleteBone(rig, bone),
      animations: (JsonMap _) => _eachAnimation((JsonMap animation) => dropTrackBone(animation, bone)),
    );
    setState(() => _selected = parent);
  }

  // --- playback --------------------------------------------------------------

  void _play() {
    if (_playing) {
      setState(() => _playing = false);
      return;
    }
    final Clip? clip = _clip;
    if (clip == null) return;
    if ((clip.loop == LoopMode.once || clip.loop == LoopMode.hold) && _tick >= clip.durationTicks) _tick = 0;
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
          double tick = _tick + (time - last) / 50 * _speed;
          final double d = clip.durationTicks;
          switch (clip.loop) {
            case LoopMode.once:
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
          _refresh();
        }
        if (_playing) _frame();
      }).toJS,
    );
  }

  void _scrub(double value) {
    _tick = value;
    _playing = false;
    _refresh();
  }

  // --- gizmo -----------------------------------------------------------------

  void _grab(GizmoGrab grab, web.PointerEvent event) {
    final String? bone = _selected;
    if (bone == null) return;
    _playing = false;
    _release();
    final JsonMap startRig = _rigJson;
    final JsonMap? startAnimation = _animationJson;
    final double time = _clipTime;
    final BonePose? pose = _poses[bone];
    _move = ((web.Event moved) {
      final double x = eventNumber(moved, 'clientX'), y = eventNumber(moved, 'clientY');
      try {
        if (startAnimation == null) {
          _dragRig = _restDrag(startRig, bone, grab, x, y);
        } else {
          _dragAnimation = _keyDrag(startAnimation, bone, grab, x, y, time, pose);
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

  void _release() {
    if (_move != null) web.window.removeEventListener('pointermove', _move);
    if (_up != null) {
      web.window.removeEventListener('pointerup', _up);
      web.window.removeEventListener('pointercancel', _up);
    }
    _move = null;
    _up = null;
  }

  /// Without an animation the gizmo edits the rest pose: move shifts the
  /// bone's pivot with its subtree, rotate its rest rotation, scale its
  /// visual's scale.
  JsonMap _restDrag(JsonMap rig, String bone, GizmoGrab grab, double x, double y) {
    switch (_mode) {
      case GizmoMode.move:
        final double delta = _snap(grab.blocks(x, y), 1 / 64);
        final List<String> moving = subtree(rig, bone);
        JsonMap out = rig;
        for (final String id in moving) {
          out = updateBone(out, id, (JsonMap entry) {
            final List<double> pivot = _vector(entry['pivot'], 0);
            pivot[grab.axis] += delta;
            entry['pivot'] = _clean(pivot);
          });
        }
        return out;
      case GizmoMode.rotate:
        return updateBone(rig, bone, (JsonMap entry) {
          final List<double> rotation = _vector(entry['rotation'], 0);
          rotation[grab.axis] = _snap(rotation[grab.axis] + grab.along(x, y) * 0.75, 0.5);
          entry['rotation'] = _clean(rotation);
        });
      case GizmoMode.scale:
        return updateBone(rig, bone, (JsonMap entry) {
          final Object? visual = entry['visual'];
          if (visual is! JsonMap || visual['type'] == 'none') throw const RigEditException('Only a bone with a visual has a scale.');
          final List<double> scale = _vector(visual['scale'], 1);
          scale[grab.axis] = _scaled(scale[grab.axis], grab, x, y);
          visual['scale'] = _clean(scale);
        });
    }
  }

  /// With an animation the gizmo keys the bone at the playhead.
  JsonMap _keyDrag(JsonMap animation, String bone, GizmoGrab grab, double x, double y, double time, BonePose? pose) {
    final TrackTarget target = switch (_mode) {
      GizmoMode.move => TrackTarget.translation,
      GizmoMode.rotate => TrackTarget.rotation,
      GizmoMode.scale => TrackTarget.scale,
    };
    JsonMap? track;
    for (final JsonMap candidate in tracksOf(animation)) {
      if (candidate['bone'] == bone && candidate['target'] == target.name) {
        track = candidate;
        break;
      }
    }
    final Object? current = track == null ? null : trackValueAt(track, time);
    final List<double> value = current is List<double>
        ? List<double>.of(current)
        : (target == TrackTarget.scale ? <double>[1, 1, 1] : <double>[0, 0, 0]);
    switch (_mode) {
      case GizmoMode.move:
        value[grab.axis] = _snap(value[grab.axis] + grab.blocks(x, y), 1 / 64);
      case GizmoMode.rotate:
        value[grab.axis] = _snap(value[grab.axis] + grab.along(x, y) * 0.75, 0.5);
      case GizmoMode.scale:
        value[grab.axis] = _scaled(value[grab.axis], grab, x, y);
    }
    return setKeyframe(animation, bone, target, time, _clean(value));
  }

  static double _scaled(double base, GizmoGrab grab, double x, double y) {
    final double factor = 1 + grab.along(x, y) / (grab.armPixels <= 0 ? 100 : grab.armPixels);
    final double next = base * factor;
    return _snap(next.clamp(0.01, 64).toDouble(), 0.01);
  }

  static double _snap(double value, double step) => (value / step).roundToDouble() * step;

  static List<double> _vector(Object? value, double fallback) =>
      value is List<Object?> && value.length == 3
          ? <double>[for (final Object? component in value) component is num ? component.toDouble() : fallback]
          : <double>[fallback, fallback, fallback];

  static List<num> _clean(List<double> values) => <num>[
    for (final double value in values) _round(value),
  ];

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

  void _buildPack() {
    final WorkspaceSnapshot? workspace = component.store.workspace;
    if (workspace == null) return;
    final RigBundle bundle = rigBundle(workspace, _rigId);
    final String name = '${_rigId.replaceAll(':', '-').replaceAll('/', '-')}-server-files.zip';
    downloadBytes(name, zipFiles(bundle.files));
    ArcaneSonner.info(
      'Saved $name: the rig, ${bundle.files.keys.where((String path) => path.startsWith('animations/')).length} animations and '
      '${bundle.files.keys.where((String path) => path.startsWith('pack/')).length} pack source files for plugins/Animus. '
      'The server-side pack format is not documented yet, so the server builds the pack zip from these files.',
    );
    if (bundle.missing.isNotEmpty) ArcaneSonner.warning('Not in this workspace: ${bundle.missing.join(', ')}.');
  }

  void _createAnimation() {
    final String name = sanitizeName(_newAnimation, 'animation');
    final String id = '$_rigId/$name';
    if (!documentIdPattern.hasMatch(id)) {
      ArcaneSonner.warning('$id is not a valid animation id.');
      return;
    }
    if (component.store.document(DocumentKind.animation, id) != null) {
      ArcaneSonner.warning('Animation $id already exists.');
      return;
    }
    final JsonMap rig = cloneJson(_rigJson);
    final JsonMap named = rig['animations'] is JsonMap ? rig['animations']! as JsonMap : <String, Object?>{};
    final bool listed = named.length < 64 && !named.containsKey(name);
    if (listed) {
      named[name] = id;
      rig['animations'] = named;
    }
    unawaited(_commit(rig: listed ? rig : null, animations: <String, JsonMap>{id: newAnimation(id, _rigId)}).then((void _) {
      _animation = id;
      _newAnimation = '';
      _tick = 0;
      _refresh();
    }));
  }

  void _addKeyframe() {
    final String? bone = _selected;
    final JsonMap? animation = _animationJson;
    final BonePose? pose = bone == null ? null : _poses[bone];
    if (bone == null || animation == null || pose == null) return;
    final Object value = switch (_keyTarget) {
      TrackTarget.rotation => _clean(pose.rotation),
      TrackTarget.translation => _clean(pose.translation),
      TrackTarget.scale => _clean(pose.scale),
      TrackTarget.visible => pose.visible,
    };
    _editAnimation((JsonMap json) => setKeyframe(json, bone, _keyTarget, _clipTime, value));
  }

  // --- view ------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    final JsonMap rig = _rigJson;
    final List<JsonMap> bones = bonesOf(rig);
    final JsonMap? bone = _selected == null ? null : boneById(rig, _selected!);
    final GizmoOverlay overlay = GizmoOverlay(
      bones: <String>[for (final JsonMap entry in bones) '${entry['id']}'],
      parents: <String, String?>{for (final JsonMap entry in bones) '${entry['id']}': parentOf(entry)},
      poses: _poses,
      selected: _selected,
      mode: _mode,
      onSelect: (String id) => setState(() => _selected = id),
      onGrab: _grab,
    );
    return dom.section(classes: 'animus-panel animus-rig-editor', attributes: <String, String>{'data-rig': _rigId}, <Widget>[
      _toolbar(rig),
      if (_problem != null) dom.p(classes: 'animus-error', <Widget>[Component.text(_problem!)]),
      dom.div(classes: 'animus-rig-layout', <Widget>[
        _tree(bones),
        dom.div(classes: 'animus-rig-stage', <Widget>[
          dom.div(classes: 'animus-segmented', attributes: const <String, String>{'role': 'group', 'aria-label': 'Gizmo'}, <Widget>[
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
            label: 'Rig ${component.rig.id}',
            overlay: (McCamera camera, double width, double height) => overlay.build(camera, width, height),
          ),
          dom.p(classes: 'animus-muted animus-rig-hint', <Widget>[
            Component.text(_animation == _restPose
                ? 'Rest pose: the gizmo edits pivots, rest rotations and visual scales.'
                : 'Animation: the gizmo keys the selected bone at tick ${_round(_clipTime)}.'),
          ]),
          if (_notes.isNotEmpty)
            dom.ul(classes: 'animus-npc-notes', <Widget>[
              for (final String note in _notes) dom.li(<Widget>[Component.text(note)]),
            ]),
        ]),
        _inspector(rig, bone),
      ]),
      RigTimeline(
        animations: <(String, String)>[
          ('Rest pose', _restPose),
          for (final AnimusDocument document in _animations) (document.id.substring(document.id.indexOf(':') + 1), document.id),
        ],
        selected: _animation,
        animation: _animationJson,
        valid: _clip != null,
        time: _clipTime,
        playing: _playing,
        speed: _speed,
        selectedBone: _selected,
        keyTarget: _keyTarget,
        keyframe: _keyframe,
        newName: _newAnimation,
        onAnimation: (String id) {
          setState(() {
            _animation = id;
            _keyframe = null;
            _tick = 0;
            _playing = false;
          });
          _refresh();
        },
        onNewName: (String value) => setState(() => _newAnimation = value),
        onCreate: _createAnimation,
        onPlay: _play,
        onScrub: _scrub,
        onSpeed: (double value) => setState(() => _speed = value),
        onKeyTarget: (TrackTarget target) => setState(() => _keyTarget = target),
        onAddKeyframe: _addKeyframe,
        onSelectKeyframe: (int track, int key, double tick) {
          setState(() {
            _keyframe = (track, key);
            final String bone = '${tracksOf(_animationJson!)[track]['bone']}';
            if (boneById(_rigJson, bone) != null) _selected = bone;
          });
          _scrub(tick);
        },
        onEdit: (JsonMap Function(JsonMap animation) change, {String? coalesce}) {
          _editAnimation(change, coalesce: coalesce);
          if (coalesce == null) setState(() => _keyframe = null);
        },
        onKeyframeEdit: (JsonMap Function(JsonMap animation) change) => _editAnimation(change),
      ),
    ]);
  }

  Widget _toolbar(JsonMap rig) {
    return dom.div(classes: 'animus-rig-toolbar', <Widget>[
      Button.secondary(label: 'Import .bbmodel', icon: ArcaneIcon.upload(), size: ButtonSize.small, disabled: _busy, onPressed: _import),
      Button.secondary(label: 'Build resource pack', icon: ArcaneIcon.download(), size: ButtonSize.small, onPressed: _buildPack),
      dom.label(classes: 'animus-rig-field', <Widget>[
        const dom.span(<Widget>[Component.text('Body')]),
        ArcaneSelect(
          size: ComponentSize.sm,
          value: rig['body'] == 'shown' ? 'shown' : 'hidden',
          options: const <ArcaneSelectOption>[
            ArcaneSelectOption(label: 'Hidden: only the rig shows', value: 'hidden'),
            ArcaneSelectOption(label: 'Shown: parts on the NPC body', value: 'shown'),
          ],
          onChanged: (String value) => _edit((JsonMap json) => cloneJson(json)..['body'] = value),
        ),
      ]),
      if (rig['body'] == 'shown')
        dom.label(classes: 'animus-rig-field', <Widget>[
          const dom.span(<Widget>[Component.text('Preview body')]),
          ArcaneSelect(
            size: ComponentSize.sm,
            value: _bodyType,
            options: <ArcaneSelectOption>[
              for (final (String type, String label) in previewBodies) ArcaneSelectOption(label: label, value: type),
            ],
            onChanged: (String value) {
              _bodyType = value;
              _framed = false;
              _refresh();
            },
          ),
        ]),
    ]);
  }

  Widget _tree(List<JsonMap> bones) {
    final JsonMap rig = _rigJson;
    final String? selected = _selected;
    final Set<String> under = selected == null ? const <String>{} : subtree(rig, selected).toSet();
    return dom.div(classes: 'animus-rig-tree', <Widget>[
      dom.h3(<Widget>[Component.text('Bones (${bones.length})')]),
      dom.ul(attributes: const <String, String>{'role': 'tree', 'aria-label': 'Bones'}, <Widget>[
        for (final JsonMap bone in bones)
          dom.li(
            attributes: <String, String>{'role': 'treeitem', 'aria-selected': '${bone['id'] == selected}'},
            styles: dom.Styles(raw: <String, String>{'padding-left': '${(boneDepth(rig, '${bone['id']}') - 1) * 0.9}rem'}),
            <Widget>[
              dom.button(
                classes: 'animus-rig-bone${bone['id'] == selected ? ' is-selected' : ''}',
                attributes: <String, String>{'type': 'button', 'data-bone': '${bone['id']}'},
                events: <String, void Function(web.Event)>{'click': (web.Event _) => setState(() => _selected = '${bone['id']}')},
                <Widget>[
                  dom.span(<Widget>[Component.text('${bone['id']}')]),
                  dom.span(classes: 'animus-muted', <Widget>[Component.text(_visualLabel(bone['visual']))]),
                ],
              ),
            ],
          ),
      ]),
      dom.div(classes: 'animus-rig-actions', <Widget>[
        Button.secondary(
          label: selected == null ? 'Add bone' : 'Add child',
          icon: ArcaneIcon.plus(),
          size: ButtonSize.small,
          disabled: bones.length >= maximumBones,
          onPressed: _addBone,
        ),
        if (selected != null)
          Button.ghost(label: 'Delete', size: ButtonSize.small, disabled: bones.length <= 1, onPressed: () => _delete(selected)),
      ]),
      if (selected != null) ...<Widget>[
        dom.label(classes: 'animus-rig-field', <Widget>[
          const dom.span(<Widget>[Component.text('Name')]),
          dom.div(classes: 'animus-row', <Widget>[
            TextInput(
              size: ComponentSize.sm,
              value: _drafts['rename'] ?? selected,
              attributes: const <String, String>{'aria-label': 'Bone name'},
              onChanged: (String value) => setState(() => _drafts['rename'] = value),
              onSubmitted: (String value) => _rename(selected, value),
            ),
            Button.secondary(
              label: 'Rename',
              size: ButtonSize.small,
              disabled: (_drafts['rename'] ?? selected) == selected,
              onPressed: () => _rename(selected, _drafts['rename'] ?? selected),
            ),
          ]),
        ]),
        dom.label(classes: 'animus-rig-field', <Widget>[
          const dom.span(<Widget>[Component.text('Parent')]),
          ArcaneSelect(
            size: ComponentSize.sm,
            value: parentOf(boneById(rig, selected) ?? const <String, Object?>{}) ?? _restPose,
            options: <ArcaneSelectOption>[
              const ArcaneSelectOption(label: '(rig origin)', value: _restPose),
              for (final JsonMap bone in bones)
                if (!under.contains(bone['id'])) ArcaneSelectOption(label: '${bone['id']}', value: '${bone['id']}'),
            ],
            onChanged: (String value) => _edit((JsonMap json) => reparentBone(json, selected, value.isEmpty ? null : value)),
          ),
        ]),
      ],
    ]);
  }

  static String _visualLabel(Object? visual) {
    if (visual is! JsonMap || visual['type'] == 'none') return 'pivot';
    return switch (visual['type']) {
      'item' => 'item',
      'block' => 'block',
      'text' => 'text',
      _ => '${visual['type']}',
    };
  }

  Widget _inspector(JsonMap rig, JsonMap? bone) {
    if (bone == null) {
      return const dom.div(classes: 'animus-rig-inspector', <Widget>[
        dom.p(classes: 'animus-muted', <Widget>[Component.text('Select a bone.')]),
      ]);
    }
    final String id = '${bone['id']}';
    final Object? rawVisual = bone['visual'];
    final JsonMap? visual = rawVisual is JsonMap ? rawVisual : null;
    final String type = visual == null ? 'none' : '${visual['type']}';
    void setVisual(void Function(JsonMap visual) change) => _edit(
      (JsonMap json) => updateBone(json, id, (JsonMap entry) {
        final JsonMap next = entry['visual'] is JsonMap ? entry['visual']! as JsonMap : <String, Object?>{'type': 'none'};
        change(next);
        entry['visual'] = next;
      }),
    );
    final Object? sockets = rig['sockets'];
    return dom.div(classes: 'animus-rig-inspector', <Widget>[
      dom.h3(<Widget>[Component.text('Bone $id')]),
      _vectorField('Pivot', 'pivot:$id', bone['pivot'], 0, (List<num> value) => _edit((JsonMap json) => updateBone(json, id, (JsonMap entry) => entry['pivot'] = value))),
      _vectorField('Rest rotation', 'rotation:$id', bone['rotation'], 0, (List<num> value) => _edit((JsonMap json) => updateBone(json, id, (JsonMap entry) {
        if (value.every((num component) => component == 0)) {
          entry.remove('rotation');
        } else {
          entry['rotation'] = value;
        }
      }))),
      dom.label(classes: 'animus-rig-field', <Widget>[
        const dom.span(<Widget>[Component.text('Visual')]),
        ArcaneSelect(
          size: ComponentSize.sm,
          value: type,
          options: const <ArcaneSelectOption>[
            ArcaneSelectOption(label: 'None (pivot only)', value: 'none'),
            ArcaneSelectOption(label: 'Item display', value: 'item'),
            ArcaneSelectOption(label: 'Block display', value: 'block'),
            ArcaneSelectOption(label: 'Text display', value: 'text'),
          ],
          onChanged: (String value) => _edit((JsonMap json) => updateBone(json, id, (JsonMap entry) {
            if (value == 'none') {
              entry.remove('visual');
            } else {
              entry['visual'] = visualOfType(value, entry['visual'] is JsonMap ? entry['visual']! as JsonMap : null);
            }
          })),
        ),
      ]),
      if (visual != null && type == 'item') ...<Widget>[
        _textField('Item', 'item:$id', visual['item'] is JsonMap ? '${(visual['item']! as JsonMap)['id']}' : '', (String value) => setVisual((JsonMap v) {
          final JsonMap item = v['item'] is JsonMap ? v['item']! as JsonMap : <String, Object?>{};
          item['id'] = value;
          v['item'] = item;
        })),
        _textField('Components', 'components:$id', visual['item'] is JsonMap ? '${(visual['item']! as JsonMap)['components'] ?? ''}' : '', (String value) => setVisual((JsonMap v) {
          final JsonMap item = v['item'] is JsonMap ? v['item']! as JsonMap : <String, Object?>{'id': 'minecraft:stick'};
          if (value.trim().isEmpty) {
            item.remove('components');
          } else {
            item['components'] = value;
          }
          v['item'] = item;
        })),
        dom.label(classes: 'animus-rig-field', <Widget>[
          const dom.span(<Widget>[Component.text('Item context')]),
          ArcaneSelect(
            size: ComponentSize.sm,
            value: visual['itemDisplay'] is String ? visual['itemDisplay']! as String : 'none',
            options: <ArcaneSelectOption>[
              for (final String context in <String>['none', 'fixed', 'head', 'gui', 'ground', 'thirdperson_righthand', 'thirdperson_lefthand', 'firstperson_righthand', 'firstperson_lefthand'])
                ArcaneSelectOption(label: context, value: context),
            ],
            onChanged: (String value) => setVisual((JsonMap v) {
              if (value == 'none') {
                v.remove('itemDisplay');
              } else {
                v['itemDisplay'] = value;
              }
            }),
          ),
        ]),
      ],
      if (visual != null && type == 'block')
        _textField('Block state', 'block:$id', '${visual['block'] ?? ''}', (String value) => setVisual((JsonMap v) => v['block'] = value)),
      if (visual != null && type == 'text') ...<Widget>[
        _textField('Text', 'text:$id', '${visual['text'] ?? ''}', (String value) => setVisual((JsonMap v) => v['text'] = value)),
        dom.label(classes: 'animus-rig-field', <Widget>[
          const dom.span(<Widget>[Component.text('Billboard')]),
          ArcaneSelect(
            size: ComponentSize.sm,
            value: visual['billboard'] is String ? visual['billboard']! as String : 'fixed',
            options: <ArcaneSelectOption>[
              for (final String mode in <String>['fixed', 'vertical', 'horizontal', 'center']) ArcaneSelectOption(label: mode, value: mode),
            ],
            onChanged: (String value) => setVisual((JsonMap v) => v['billboard'] = value),
          ),
        ]),
      ],
      if (visual != null && type != 'none') ...<Widget>[
        _vectorField('Visual offset', 'vt:$id', visual['translation'], 0, (List<num> value) => setVisual((JsonMap v) => v['translation'] = value)),
        _vectorField('Visual rotation', 'vr:$id', visual['rotation'], 0, (List<num> value) => setVisual((JsonMap v) => v['rotation'] = value)),
        _vectorField('Visual scale', 'vs:$id', visual['scale'], 1, (List<num> value) => setVisual((JsonMap v) => v['scale'] = value)),
      ],
      const dom.h3(<Widget>[Component.text('Sockets')]),
      const dom.p(classes: 'animus-muted', <Widget>[Component.text('Equipment slots that attach to a bone of this rig.')]),
      for (final String slot in socketSlots)
        dom.label(classes: 'animus-rig-field is-inline', <Widget>[
          dom.span(<Widget>[Component.text(slot)]),
          ArcaneSelect(
            size: ComponentSize.sm,
            value: sockets is JsonMap && sockets[slot] is String ? sockets[slot]! as String : _restPose,
            options: <ArcaneSelectOption>[
              const ArcaneSelectOption(label: '(none)', value: _restPose),
              for (final JsonMap entry in bonesOf(rig)) ArcaneSelectOption(label: '${entry['id']}', value: '${entry['id']}'),
            ],
            onChanged: (String value) => _edit((JsonMap json) {
              final JsonMap out = cloneJson(json);
              final JsonMap map = out['sockets'] is JsonMap ? out['sockets']! as JsonMap : <String, Object?>{};
              if (value.isEmpty) {
                map.remove(slot);
              } else {
                map[slot] = value;
              }
              if (map.isEmpty) {
                out.remove('sockets');
              } else {
                out['sockets'] = map;
              }
              return out;
            }),
          ),
        ]),
    ]);
  }

  Widget _textField(String label, String key, String value, void Function(String value) onCommit) {
    void commit() {
      final String? draft = _drafts.remove(key);
      if (draft != null && draft != value) onCommit(draft);
      setState(() {});
    }

    return dom.label(classes: 'animus-rig-field', <Widget>[
      dom.span(<Widget>[Component.text(label)]),
      TextInput(
        size: ComponentSize.sm,
        value: _drafts[key] ?? value,
        attributes: <String, String>{'aria-label': label},
        onChanged: (String next) => _drafts[key] = next,
        onSubmitted: (String _) => commit(),
        onBlur: commit,
      ),
    ]);
  }

  Widget _vectorField(String label, String key, Object? value, double fallback, void Function(List<num> value) onCommit) {
    final List<double> current = _vector(value, fallback);
    void commit() {
      final List<double> next = List<double>.of(current);
      bool changed = false;
      for (int axis = 0; axis < 3; axis++) {
        final String? draft = _drafts.remove('$key:$axis');
        final double? parsed = draft == null ? null : double.tryParse(draft.trim());
        if (parsed != null && parsed.isFinite && parsed.abs() <= 1024 && parsed != next[axis]) {
          next[axis] = parsed;
          changed = true;
        }
      }
      if (changed) onCommit(_clean(next));
      setState(() {});
    }

    return dom.div(classes: 'animus-rig-field', <Widget>[
      dom.span(<Widget>[Component.text(label)]),
      dom.div(classes: 'animus-rig-vector', <Widget>[
        for (int axis = 0; axis < 3; axis++)
          TextInput(
            size: ComponentSize.sm,
            type: TextInputType.number,
            value: _drafts['$key:$axis'] ?? '${_round(current[axis])}',
            attributes: <String, String>{'aria-label': '$label ${const <String>['X', 'Y', 'Z'][axis]}', 'step': 'any'},
            onChanged: (String next) => _drafts['$key:$axis'] = next,
            onSubmitted: (String _) => commit(),
            onBlur: commit,
          ),
      ]),
    ]);
  }
}
