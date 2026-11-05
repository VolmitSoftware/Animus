/// The keyframe timeline under the rig stage: the animation picker, duration,
/// loop mode and speed, play and scrub, one lane per track with its keyframe
/// diamonds, and the selected keyframe's tick, value and easing.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/rig_edit.dart';
import '../../logic/rig_sampler.dart';

typedef AnimationEdit = void Function(JsonMap Function(JsonMap animation) change, {String? coalesce});

const List<double> playbackSpeeds = <double>[0.25, 0.5, 1, 2, 4];

class RigTimeline extends StatefulWidget {
  const RigTimeline({
    required this.animations,
    required this.selected,
    required this.animation,
    required this.valid,
    required this.time,
    required this.playing,
    required this.speed,
    required this.selectedBone,
    required this.keyTarget,
    required this.keyframe,
    required this.newName,
    required this.onAnimation,
    required this.onNewName,
    required this.onCreate,
    required this.onPlay,
    required this.onScrub,
    required this.onSpeed,
    required this.onKeyTarget,
    required this.onAddKeyframe,
    required this.onSelectKeyframe,
    required this.onEdit,
    required this.onKeyframeEdit,
    super.key,
  });

  /// (label, id); the rest pose has the empty id.
  final List<(String, String)> animations;
  final String selected;
  final JsonMap? animation;
  final bool valid;

  /// Clip time at the playhead, ticks.
  final double time;
  final bool playing;
  final double speed;
  final String? selectedBone;
  final TrackTarget keyTarget;
  final (int, int)? keyframe;
  final String newName;
  final void Function(String id) onAnimation;
  final void Function(String value) onNewName;
  final void Function() onCreate;
  final void Function() onPlay;
  final void Function(double tick) onScrub;
  final void Function(double speed) onSpeed;
  final void Function(TrackTarget target) onKeyTarget;
  final void Function() onAddKeyframe;
  final void Function(int track, int keyframe, double tick) onSelectKeyframe;
  final AnimationEdit onEdit;
  final void Function(JsonMap Function(JsonMap animation) change) onKeyframeEdit;

  @override
  State<RigTimeline> createState() => _RigTimelineState();
}

class _RigTimelineState extends State<RigTimeline> {
  final Map<String, String> _drafts = <String, String>{};

  static double _number(Object? value) => value is num ? value.toDouble() : 0;

  String _format(double value) {
    final double rounded = (value * 1000).roundToDouble() / 1000;
    return rounded == rounded.roundToDouble() ? '${rounded.round()}' : '$rounded';
  }

  @override
  Widget build(BuildContext context) {
    final JsonMap? animation = component.animation;
    return dom.div(classes: 'animus-rig-timeline', attributes: const <String, String>{'aria-label': 'Timeline'}, <Widget>[
      dom.div(classes: 'animus-rig-toolbar', <Widget>[
        dom.label(classes: 'animus-rig-field', <Widget>[
          const dom.span(<Widget>[Component.text('Animation')]),
          ArcaneSelect(
            size: ComponentSize.sm,
            value: component.selected,
            options: <ArcaneSelectOption>[
              for (final (String label, String id) in component.animations) ArcaneSelectOption(label: label, value: id),
            ],
            onChanged: component.onAnimation,
          ),
        ]),
        dom.div(classes: 'animus-row', <Widget>[
          TextInput(
            size: ComponentSize.sm,
            placeholder: 'new animation name',
            value: component.newName,
            attributes: const <String, String>{'aria-label': 'New animation name'},
            onChanged: component.onNewName,
            onSubmitted: (String _) => component.onCreate(),
          ),
          Button.secondary(label: 'New animation', icon: ArcaneIcon.plus(), size: ButtonSize.small, onPressed: component.onCreate),
        ]),
      ]),
      if (animation == null)
        const dom.p(classes: 'animus-muted', <Widget>[
          Component.text('Showing the rest pose. Pick or create an animation to key bones.'),
        ])
      else
        ..._clipView(animation),
    ]);
  }

  List<Widget> _clipView(JsonMap animation) {
    final double duration = _number(animation['durationTicks']);
    final String loop = animation['loop'] is String ? animation['loop']! as String : 'once';
    final List<JsonMap> tracks = tracksOf(animation);
    final double span = duration <= 0 ? 1 : duration;
    final double time = component.time;
    return <Widget>[
      dom.div(classes: 'animus-rig-toolbar', <Widget>[
        Button.primary(
          label: component.playing ? 'Pause' : 'Play',
          icon: component.playing ? ArcaneIcon.pause() : ArcaneIcon.play(),
          size: ButtonSize.small,
          disabled: !component.valid,
          onPressed: component.onPlay,
        ),
        dom.label(classes: 'animus-rig-field is-inline', <Widget>[
          const dom.span(<Widget>[Component.text('Duration')]),
          TextInput(
            size: ComponentSize.sm,
            type: TextInputType.number,
            value: _drafts['duration'] ?? _format(duration),
            attributes: const <String, String>{'aria-label': 'Duration in ticks', 'min': '1', 'max': '1200'},
            onChanged: (String value) => _drafts['duration'] = value,
            onSubmitted: (String _) => _commitDuration(duration),
            onBlur: () => _commitDuration(duration),
          ),
        ]),
        dom.label(classes: 'animus-rig-field is-inline', <Widget>[
          const dom.span(<Widget>[Component.text('Loop')]),
          ArcaneSelect(
            size: ComponentSize.sm,
            value: loop,
            options: const <ArcaneSelectOption>[
              ArcaneSelectOption(label: 'once', value: 'once'),
              ArcaneSelectOption(label: 'loop', value: 'loop'),
              ArcaneSelectOption(label: 'pingpong', value: 'pingpong'),
              ArcaneSelectOption(label: 'hold', value: 'hold'),
            ],
            onChanged: (String value) => component.onEdit((JsonMap json) => cloneJson(json)..['loop'] = value),
          ),
        ]),
        dom.label(classes: 'animus-rig-field is-inline', <Widget>[
          const dom.span(<Widget>[Component.text('Speed')]),
          ArcaneSelect(
            size: ComponentSize.sm,
            value: '${component.speed}',
            options: <ArcaneSelectOption>[
              for (final double speed in playbackSpeeds) ArcaneSelectOption(label: '${_format(speed)}x', value: '$speed'),
            ],
            onChanged: (String value) => component.onSpeed(double.parse(value)),
          ),
        ]),
      ]),
      dom.div(classes: 'animus-rig-scrubber', <Widget>[
        // A native range input: ArcaneSlider's gesture runtime is an inline
        // script, which the editor's CSP does not run.
        dom.input(
          type: dom.InputType.range,
          classes: 'animus-rig-range',
          value: _format(time.clamp(0, span).toDouble()),
          attributes: <String, String>{'min': '0', 'max': _format(span), 'step': '0.25', 'aria-label': 'Playhead tick'},
          events: <String, void Function(web.Event)>{
            'input': (web.Event event) {
              final double? tick = double.tryParse((event.target! as web.HTMLInputElement).value);
              if (tick != null) component.onScrub(tick);
            },
          },
        ),
        dom.span(classes: 'animus-rig-tick', attributes: const <String, String>{'data-tick': 'true'}, <Widget>[
          Component.text('tick ${_format(time)} / ${_format(duration)}'),
        ]),
      ]),
      dom.div(classes: 'animus-rig-tracks', <Widget>[
        for (int index = 0; index < tracks.length; index++) _lane(tracks, index, span, time),
        if (tracks.isEmpty) const dom.p(classes: 'animus-muted', <Widget>[Component.text('No tracks yet. Add a keyframe or drag a gizmo handle.')]),
      ]),
      dom.div(classes: 'animus-rig-toolbar', <Widget>[
        dom.label(classes: 'animus-rig-field is-inline', <Widget>[
          const dom.span(<Widget>[Component.text('Channel')]),
          ArcaneSelect(
            size: ComponentSize.sm,
            value: component.keyTarget.name,
            options: <ArcaneSelectOption>[
              for (final TrackTarget target in TrackTarget.values) ArcaneSelectOption(label: target.name, value: target.name),
            ],
            onChanged: (String value) => component.onKeyTarget(TrackTarget.values.byName(value)),
          ),
        ]),
        Button.secondary(
          label: 'Add keyframe',
          icon: ArcaneIcon.plus(),
          size: ButtonSize.small,
          disabled: component.selectedBone == null,
          onPressed: component.onAddKeyframe,
        ),
        dom.span(classes: 'animus-muted', <Widget>[
          Component.text(component.selectedBone == null ? 'Select a bone first.' : 'on ${component.selectedBone} at tick ${_format(time)}'),
        ]),
      ]),
      ..._keyframeInspector(animation, tracks),
    ];
  }

  void _commitDuration(double current) {
    final String? draft = _drafts.remove('duration');
    final int? parsed = draft == null ? null : int.tryParse(draft.trim());
    if (parsed == null || parsed == current) {
      setState(() {});
      return;
    }
    if (parsed < 1 || parsed > maximumDurationTicks) {
      ArcaneSonner.warning('Durations are 1 to $maximumDurationTicks ticks.');
      setState(() {});
      return;
    }
    component.onEdit((JsonMap json) => cloneJson(json)..['durationTicks'] = parsed);
  }

  Widget _lane(List<JsonMap> tracks, int index, double span, double time) {
    final JsonMap track = tracks[index];
    final List<JsonMap> keys = keyframesOf(track);
    final String blend = track['blend'] is String ? track['blend']! as String : 'replace';
    return dom.div(classes: 'animus-rig-lane${track['bone'] == component.selectedBone ? ' is-bone' : ''}', <Widget>[
      dom.span(classes: 'animus-rig-lane-label', <Widget>[
        Component.text('${track['bone']} ${track['target']}${blend == 'replace' ? '' : ' ($blend)'}'),
      ]),
      dom.div(classes: 'animus-rig-lane-keys', <Widget>[
        dom.span(
          classes: 'animus-rig-playhead',
          styles: dom.Styles(raw: <String, String>{'left': '${(time / span * 100).clamp(0, 100).toStringAsFixed(2)}%'}),
          const <Widget>[],
        ),
        for (int key = 0; key < keys.length; key++)
          dom.button(
            classes: 'animus-rig-key${component.keyframe == (index, key) ? ' is-selected' : ''}',
            attributes: <String, String>{
              'type': 'button',
              'aria-label': '${track['bone']} ${track['target']} keyframe at tick ${_format(_number(keys[key]['tick']))}',
              'data-key': '$index:$key',
            },
            styles: dom.Styles(raw: <String, String>{'left': '${(_number(keys[key]['tick']) / span * 100).clamp(0, 100).toStringAsFixed(2)}%'}),
            events: <String, void Function(web.Event)>{
              'click': (web.Event _) => component.onSelectKeyframe(index, key, _number(keys[key]['tick'])),
            },
            const <Widget>[],
          ),
      ]),
    ]);
  }

  List<Widget> _keyframeInspector(JsonMap animation, List<JsonMap> tracks) {
    final (int, int)? selected = component.keyframe;
    if (selected == null || selected.$1 >= tracks.length) return const <Widget>[];
    final JsonMap track = tracks[selected.$1];
    final List<JsonMap> keys = keyframesOf(track);
    if (selected.$2 >= keys.length) return const <Widget>[];
    final JsonMap key = keys[selected.$2];
    final String easing = key['easing'] is String ? key['easing']! as String : 'linear';
    final bool bezier = easing.startsWith('bezier');
    final String prefix = 'key:${selected.$1}:${selected.$2}';
    final Object? value = key['value'];
    return <Widget>[
      dom.div(classes: 'animus-rig-keyframe', attributes: const <String, String>{'aria-label': 'Keyframe'}, <Widget>[
        dom.strong(<Widget>[Component.text('${track['bone']} ${track['target']} at tick ${_format(_number(key['tick']))}')]),
        dom.label(classes: 'animus-rig-field is-inline', <Widget>[
          const dom.span(<Widget>[Component.text('Tick')]),
          TextInput(
            size: ComponentSize.sm,
            type: TextInputType.number,
            value: _drafts['$prefix:tick'] ?? _format(_number(key['tick'])),
            attributes: const <String, String>{'aria-label': 'Keyframe tick', 'step': 'any'},
            onChanged: (String next) => _drafts['$prefix:tick'] = next,
            onSubmitted: (String _) => _commitTick(selected),
            onBlur: () => _commitTick(selected),
          ),
        ]),
        dom.label(classes: 'animus-rig-field is-inline', <Widget>[
          const dom.span(<Widget>[Component.text('Easing')]),
          ArcaneSelect(
            size: ComponentSize.sm,
            value: bezier ? 'bezier' : easing,
            options: <ArcaneSelectOption>[
              for (final String name in <String>[...namedEasings, 'bezier']) ArcaneSelectOption(label: name, value: name),
            ],
            onChanged: (String next) => component.onKeyframeEdit(
              (JsonMap json) => setKeyframeEasing(json, selected.$1, selected.$2, next == 'bezier' ? 'bezier(0.25,0.1,0.25,1)' : next),
            ),
          ),
        ]),
        if (bezier)
          dom.label(classes: 'animus-rig-field is-inline', <Widget>[
            const dom.span(<Widget>[Component.text('Curve')]),
            TextInput(
              size: ComponentSize.sm,
              value: _drafts['$prefix:bezier'] ?? easing,
              attributes: const <String, String>{'aria-label': 'Bezier curve'},
              onChanged: (String next) => _drafts['$prefix:bezier'] = next,
              onSubmitted: (String _) => _commitBezier(selected, prefix),
              onBlur: () => _commitBezier(selected, prefix),
            ),
          ]),
        if (value is bool)
          dom.label(classes: 'animus-rig-field is-inline', <Widget>[
            const dom.span(<Widget>[Component.text('Visible')]),
            ArcaneSelect(
              size: ComponentSize.sm,
              value: '$value',
              options: const <ArcaneSelectOption>[
                ArcaneSelectOption(label: 'shown', value: 'true'),
                ArcaneSelectOption(label: 'hidden', value: 'false'),
              ],
              onChanged: (String next) => component.onKeyframeEdit(
                (JsonMap json) => setKeyframe(json, '${track['bone']}', TrackTarget.visible, _number(key['tick']), next == 'true'),
              ),
            ),
          ])
        else if (value is List<Object?>)
          dom.div(classes: 'animus-rig-vector', <Widget>[
            for (int axis = 0; axis < 3 && axis < value.length; axis++)
              TextInput(
                size: ComponentSize.sm,
                type: TextInputType.number,
                value: _drafts['$prefix:$axis'] ?? _format(_number(value[axis])),
                attributes: <String, String>{'aria-label': 'Keyframe value ${const <String>['X', 'Y', 'Z'][axis]}', 'step': 'any'},
                onChanged: (String next) => _drafts['$prefix:$axis'] = next,
                onSubmitted: (String _) => _commitValue(track, key, value, prefix),
                onBlur: () => _commitValue(track, key, value, prefix),
              ),
          ]),
        Button.ghost(
          label: 'Delete keyframe',
          size: ButtonSize.small,
          onPressed: () => component.onEdit((JsonMap json) => removeKeyframe(json, selected.$1, selected.$2)),
        ),
      ]),
    ];
  }

  void _commitTick((int, int) selected) {
    final String? draft = _drafts.remove('key:${selected.$1}:${selected.$2}:tick');
    final double? parsed = draft == null ? null : double.tryParse(draft.trim());
    if (parsed != null) component.onEdit((JsonMap json) => moveKeyframe(json, selected.$1, selected.$2, parsed));
    setState(() {});
  }

  void _commitBezier((int, int) selected, String prefix) {
    final String? draft = _drafts.remove('$prefix:bezier');
    if (draft != null) {
      component.onKeyframeEdit((JsonMap json) => setKeyframeEasing(json, selected.$1, selected.$2, draft.replaceAll(' ', '')));
    }
    setState(() {});
  }

  void _commitValue(JsonMap track, JsonMap key, List<Object?> value, String prefix) {
    final List<double> next = <double>[for (final Object? component in value) _number(component)];
    bool changed = false;
    for (int axis = 0; axis < next.length; axis++) {
      final String? draft = _drafts.remove('$prefix:$axis');
      final double? parsed = draft == null ? null : double.tryParse(draft.trim());
      if (parsed != null && parsed.isFinite && parsed != next[axis]) {
        next[axis] = parsed;
        changed = true;
      }
    }
    if (changed) {
      component.onKeyframeEdit(
        (JsonMap json) => setKeyframe(json, '${track['bone']}', TrackTarget.values.byName('${track['target']}'), _number(key['tick']), next),
      );
    }
    setState(() {});
  }
}
