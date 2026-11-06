/// The rig sampler from dev/editor-contracts.md. The server's Java sampler
/// implements the same rules; both are pinned by
/// schema/fixtures/animation/samples/.
library;

import 'dart:math' as math;

typedef Vector3 = List<double>;

/// A row-major affine transform: three rows of four numbers.
class Affine {
  const Affine(this.m);

  /// 12 entries: rows of [r00 r01 r02 tx], [r10 r11 r12 ty], [r20 r21 r22 tz].
  final List<double> m;

  static const Affine identity = Affine(<double>[1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0]);

  static Affine translation(Vector3 v) => Affine(<double>[1, 0, 0, v[0], 0, 1, 0, v[1], 0, 0, 1, v[2]]);

  static Affine scale(Vector3 v) => Affine(<double>[v[0], 0, 0, 0, 0, v[1], 0, 0, 0, 0, v[2], 0]);

  static Affine rotationX(double degrees) {
    final double c = math.cos(degrees * math.pi / 180), s = math.sin(degrees * math.pi / 180);
    return Affine(<double>[1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0]);
  }

  static Affine rotationY(double degrees) {
    final double c = math.cos(degrees * math.pi / 180), s = math.sin(degrees * math.pi / 180);
    return Affine(<double>[c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0]);
  }

  static Affine rotationZ(double degrees) {
    final double c = math.cos(degrees * math.pi / 180), s = math.sin(degrees * math.pi / 180);
    return Affine(<double>[c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0]);
  }

  /// Euler degrees applied X, then Y, then Z: Rz * Ry * Rx.
  static Affine euler(Vector3 degrees) => rotationZ(degrees[2]) * rotationY(degrees[1]) * rotationX(degrees[0]);

  Affine operator *(Affine other) {
    final List<double> a = m, b = other.m;
    final List<double> out = List<double>.filled(12, 0);
    for (int row = 0; row < 3; row++) {
      for (int column = 0; column < 4; column++) {
        double sum = a[row * 4] * b[column] + a[row * 4 + 1] * b[4 + column] + a[row * 4 + 2] * b[8 + column];
        if (column == 3) sum += a[row * 4 + 3];
        out[row * 4 + column] = sum;
      }
    }
    return Affine(out);
  }
}

enum LoopMode { once, loop, pingpong, hold }

enum Blend { replace, add, multiply }

enum TrackTarget { rotation, translation, scale, visible }

/// An easing curve from schema/animation-v1.schema.json.
class Easing {
  const Easing._(this.name, [this.controls = const <double>[]]);

  final String name;
  final List<double> controls;

  static const Easing linear = Easing._('linear');

  /// The bezier form schema/animation-v1.schema.json and the server's
  /// `Easing` accept: x values within 0..1 written as `0`, `0.x`, `1` or
  /// `1.0`, y values plain decimals, no exponents and no leading `+`.
  static final RegExp bezierPattern = RegExp(
    r'^bezier\(\s*(0(?:\.\d+)?|1(?:\.0+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*,\s*(0(?:\.\d+)?|1(?:\.0+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*\)$',
  );

  /// What the editor says about a curve [parse] refuses.
  static const String bezierHint = 'Use bezier(x1,y1,x2,y2) with numbers like 0.25. The x values stay within 0 and 1.';

  static Easing parse(String? source) {
    if (source == null || source == 'linear') return linear;
    if (const <String>{'hold', 'ease_in', 'ease_out', 'ease_in_out', 'back_out'}.contains(source)) return Easing._(source);
    final RegExpMatch? bezier = bezierPattern.firstMatch(source);
    if (bezier != null) {
      return Easing._('bezier', <double>[for (int group = 1; group <= 4; group++) double.parse(bezier.group(group)!)]);
    }
    if (source.startsWith('bezier')) throw const FormatException(bezierHint);
    throw FormatException('Unknown easing $source. Pick linear, hold, ease_in, ease_out, ease_in_out, back_out or a bezier curve.');
  }

  double apply(double progress) {
    final double u = progress.clamp(0.0, 1.0).toDouble();
    switch (name) {
      case 'hold':
        return 0;
      case 'ease_in':
        return u * u * u;
      case 'ease_out':
        return 1 - math.pow(1 - u, 3).toDouble();
      case 'ease_in_out':
        return u < 0.5 ? 4 * u * u * u : 1 - math.pow(-2 * u + 2, 3).toDouble() / 2;
      case 'back_out':
        final double shifted = u - 1;
        return 1 + 2.70158 * shifted * shifted * shifted + 1.70158 * shifted * shifted;
      case 'bezier':
        return _bezier(u);
      default:
        return u;
    }
  }

  double _bezier(double x) {
    if (x <= 0) return 0;
    if (x >= 1) return 1;
    double parameter = x;
    for (int iteration = 0; iteration < 8; iteration++) {
      final double error = _axis(parameter, controls[0], controls[2]) - x;
      if (error.abs() < 1e-9) return _axis(parameter, controls[1], controls[3]);
      final double slope = _slope(parameter, controls[0], controls[2]);
      if (slope.abs() < 1e-9) break;
      parameter -= error / slope;
    }
    double low = 0, high = 1;
    parameter = x;
    for (int iteration = 0; iteration < 60 && high - low > 1e-12; iteration++) {
      if (_axis(parameter, controls[0], controls[2]) > x) {
        high = parameter;
      } else {
        low = parameter;
      }
      parameter = (low + high) / 2;
    }
    return _axis(parameter, controls[1], controls[3]);
  }

  static double _axis(double t, double c1, double c2) {
    final double inverse = 1 - t;
    return 3 * inverse * inverse * t * c1 + 3 * inverse * t * t * c2 + t * t * t;
  }

  static double _slope(double t, double c1, double c2) {
    final double inverse = 1 - t;
    return 3 * inverse * inverse * c1 + 6 * inverse * t * (c2 - c1) + 3 * t * t * (1 - c2);
  }
}

class Keyframe {
  const Keyframe(this.tick, this.vector, this.flag, this.easing);

  final double tick;
  final Vector3? vector;
  final bool? flag;
  final Easing easing;
}

class Track {
  const Track(this.bone, this.target, this.blend, this.keyframes);

  final String bone;
  final TrackTarget target;
  final Blend blend;
  final List<Keyframe> keyframes;
}

class Clip {
  const Clip(this.id, this.durationTicks, this.loop, this.tracks);

  final String id;
  final double durationTicks;
  final LoopMode loop;
  final List<Track> tracks;

  static Clip fromJson(Map<String, Object?> json) {
    final List<Track> tracks = <Track>[];
    for (final Object? raw in json['tracks']! as List<Object?>) {
      final Map<String, Object?> track = raw! as Map<String, Object?>;
      final TrackTarget target = TrackTarget.values.byName(track['target']! as String);
      tracks.add(Track(
        track['bone']! as String,
        target,
        Blend.values.byName((track['blend'] as String?) ?? 'replace'),
        <Keyframe>[
          for (final Object? key in track['keyframes']! as List<Object?>)
            _keyframe(key! as Map<String, Object?>, target),
        ],
      ));
    }
    return Clip(
      json['id']! as String,
      (json['durationTicks']! as num).toDouble(),
      LoopMode.values.byName((json['loop'] as String?) ?? 'once'),
      tracks,
    );
  }

  static Keyframe _keyframe(Map<String, Object?> json, TrackTarget target) {
    final Object? value = json['value'];
    return Keyframe(
      (json['tick']! as num).toDouble(),
      target == TrackTarget.visible ? null : vector(value),
      target == TrackTarget.visible ? value! as bool : null,
      Easing.parse(json['easing'] as String?),
    );
  }

  /// Clip time for a playback tick, by loop mode.
  double clipTime(double tick) {
    final double d = durationTicks;
    switch (loop) {
      case LoopMode.once:
      case LoopMode.hold:
        return tick.clamp(0.0, d).toDouble();
      case LoopMode.loop:
        return tick - d * (tick / d).floorToDouble();
      case LoopMode.pingpong:
        final double phase = tick - 2 * d * (tick / (2 * d)).floorToDouble();
        return phase <= d ? phase : 2 * d - phase;
    }
  }
}

class Visual {
  const Visual(this.type, this.translation, this.rotation, this.scale);

  final String type;
  final Vector3 translation;
  final Vector3 rotation;
  final Vector3 scale;
}

class Bone {
  const Bone(this.id, this.parent, this.pivot, this.rotation, this.visual);

  final String id;
  final String? parent;
  final Vector3 pivot;
  final Vector3 rotation;
  final Visual? visual;
}

class Rig {
  const Rig(this.id, this.bones);

  final String id;

  /// Parents before children, as the rig format requires.
  final List<Bone> bones;

  static Rig fromJson(Map<String, Object?> json) {
    return Rig(json['id']! as String, <Bone>[
      for (final Object? raw in json['bones']! as List<Object?>) _bone(raw! as Map<String, Object?>),
    ]);
  }

  static Bone _bone(Map<String, Object?> json) {
    final Object? visual = json['visual'];
    Visual? parsed;
    if (visual is Map<String, Object?> && visual['type'] != 'none') {
      parsed = Visual(
        visual['type']! as String,
        vector(visual['translation'] ?? const <double>[0, 0, 0]),
        vector(visual['rotation'] ?? const <double>[0, 0, 0]),
        vector(visual['scale'] ?? const <double>[1, 1, 1]),
      );
    }
    return Bone(
      json['id']! as String,
      json['parent'] as String?,
      vector(json['pivot']),
      vector(json['rotation'] ?? const <double>[0, 0, 0]),
      parsed,
    );
  }
}

Vector3 vector(Object? value) {
  final List<Object?> list = value! as List<Object?>;
  return <double>[for (final Object? component in list) (component! as num).toDouble()];
}

/// One playing clip. Layers apply in list order; later layers blend over earlier ones.
class Layer {
  const Layer(this.clip, {this.speed = 1});

  final Clip clip;
  final double speed;
}

class BonePose {
  const BonePose({
    required this.rotation,
    required this.translation,
    required this.scale,
    required this.visible,
    required this.ownVisible,
    required this.model,
    required this.visual,
  });

  final Vector3 rotation;
  final Vector3 translation;
  final Vector3 scale;

  /// False when the bone or any ancestor is hidden.
  final bool visible;

  /// The bone's own visible channel, ancestors aside: what a visible track
  /// keyed on this bone holds.
  final bool ownVisible;

  /// The bone's model-space transform, body yaw included.
  final Affine model;

  /// The display entity transform for the bone's visual, or null for none.
  final Affine? visual;
}

class _Channels {
  Vector3 rotation = <double>[0, 0, 0];
  Vector3 translation = <double>[0, 0, 0];
  Vector3 scale = <double>[1, 1, 1];
  bool visible = true;
}

/// Samples [layers] on [rig] at [tick] playback ticks for a body facing [yaw]
/// degrees (Minecraft yaw: 0 faces +Z, 90 faces -X).
Map<String, BonePose> sampleRig(Rig rig, List<Layer> layers, double yaw, double tick) {
  final Map<String, _Channels> channels = <String, _Channels>{
    for (final Bone bone in rig.bones) bone.id: _Channels(),
  };
  for (final Layer layer in layers) {
    final double time = layer.clip.clipTime(tick * layer.speed);
    for (final Track track in layer.clip.tracks) {
      final _Channels? channel = channels[track.bone];
      if (channel == null) continue;
      _apply(channel, track, time);
    }
  }
  final Map<String, BonePose> poses = <String, BonePose>{};
  final Map<String, Bone> byId = <String, Bone>{for (final Bone bone in rig.bones) bone.id: bone};
  final Affine body = Affine.rotationY(-yaw);
  for (final Bone bone in rig.bones) {
    final _Channels channel = channels[bone.id]!;
    final String? parent = bone.parent;
    final BonePose? parentPose = parent == null ? null : poses[parent];
    final Vector3 parentPivot = parent == null ? const <double>[0, 0, 0] : byId[parent]!.pivot;
    final Affine local = Affine.translation(<double>[
          bone.pivot[0] - parentPivot[0],
          bone.pivot[1] - parentPivot[1],
          bone.pivot[2] - parentPivot[2],
        ]) *
        Affine.translation(channel.translation) *
        Affine.euler(bone.rotation) *
        Affine.euler(channel.rotation) *
        Affine.scale(channel.scale);
    final Affine model = (parentPose?.model ?? body) * local;
    final Visual? visual = bone.visual;
    poses[bone.id] = BonePose(
      rotation: channel.rotation,
      translation: channel.translation,
      scale: channel.scale,
      visible: channel.visible && (parentPose?.visible ?? true),
      ownVisible: channel.visible,
      model: model,
      visual: visual == null
          ? null
          : model * Affine.translation(visual.translation) * Affine.euler(visual.rotation) * Affine.scale(visual.scale),
    );
  }
  return poses;
}

void _apply(_Channels channel, Track track, double time) {
  final List<Keyframe> keys = track.keyframes;
  Keyframe? from, to;
  double progress = 0;
  if (time <= keys.first.tick) {
    from = keys.first;
  } else if (time >= keys.last.tick) {
    from = keys.last;
  } else {
    for (int index = 0; index + 1 < keys.length; index++) {
      if (keys[index].tick <= time && time < keys[index + 1].tick) {
        from = keys[index];
        to = keys[index + 1];
        progress = (time - from.tick) / (to.tick - from.tick);
        break;
      }
    }
  }
  if (track.target == TrackTarget.visible) {
    channel.visible = from!.flag!;
    return;
  }
  final Vector3 a = from!.vector!;
  final Vector3 value;
  if (to == null) {
    value = List<double>.of(a);
  } else {
    final double eased = from.easing.apply(progress);
    final Vector3 b = to.vector!;
    value = <double>[for (int axis = 0; axis < 3; axis++) a[axis] + (b[axis] - a[axis]) * eased];
  }
  final Vector3 current = switch (track.target) {
    TrackTarget.rotation => channel.rotation,
    TrackTarget.translation => channel.translation,
    _ => channel.scale,
  };
  final Vector3 blended = switch (track.blend) {
    Blend.replace => value,
    Blend.add => <double>[for (int axis = 0; axis < 3; axis++) current[axis] + value[axis]],
    Blend.multiply => <double>[for (int axis = 0; axis < 3; axis++) current[axis] * value[axis]],
  };
  switch (track.target) {
    case TrackTarget.rotation:
      channel.rotation = blended;
    case TrackTarget.translation:
      channel.translation = blended;
    case TrackTarget.scale:
      channel.scale = blended;
    case TrackTarget.visible:
      break;
  }
}
