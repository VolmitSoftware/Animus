/// The selected track's blend, order and removal, and the selected
/// keyframe's tick, easing (with the bezier curve checked at the field),
/// value, copy and removal.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart' hide Easing;
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component, ValueKey;

import '../../logic/rig_edit.dart';
import '../../logic/rig_sampler.dart';

typedef KeyframeEdit = void Function(JsonMap Function(JsonMap animation) change, {String? coalesce});

const Map<String, String> blendDescriptions = <String, String>{
  'replace': 'Sets the channel, ignoring the tracks above it.',
  'add': 'Adds to the tracks above it.',
  'multiply': 'Multiplies the tracks above it.',
};

class RigKeyframePanel extends StatefulWidget {
  const RigKeyframePanel({
    required this.animation,
    required this.track,
    required this.keyframe,
    required this.time,
    required this.onEdit,
    required this.onSelectTrack,
    super.key,
  });

  final JsonMap animation;
  final int? track;
  final (int, int)? keyframe;
  final double time;
  final KeyframeEdit onEdit;
  final void Function(int? track) onSelectTrack;

  @override
  State<RigKeyframePanel> createState() => _RigKeyframePanelState();
}

class _RigKeyframePanelState extends State<RigKeyframePanel> {
  final Map<String, String> _drafts = <String, String>{};
  String? _bezierError;

  static double _number(Object? value) => value is num ? value.toDouble() : 0;

  static String _format(double value) {
    final double rounded = (value * 1000).roundToDouble() / 1000;
    return rounded == rounded.roundToDouble() ? '${rounded.round()}' : '$rounded';
  }

  @override
  void didUpdateWidget(RigKeyframePanel oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (oldComponent.keyframe != component.keyframe) {
      _drafts.clear();
      _bezierError = null;
    }
  }

  @override
  Widget build(BuildContext context) {
    final List<JsonMap> tracks = tracksOf(component.animation);
    final int? track = component.track;
    return dom.div(classes: 'animus-rig-key-panels', <Widget>[
      if (track != null && track < tracks.length) _trackPanel(tracks, track),
      ..._keyframePanel(tracks),
    ]);
  }

  Widget _trackPanel(List<JsonMap> tracks, int index) {
    final JsonMap track = tracks[index];
    final String blend = track['blend'] is String ? track['blend']! as String : 'replace';
    final bool visible = track['target'] == 'visible';
    return dom.div(classes: 'animus-rig-keyframe', attributes: const <String, String>{'aria-label': 'Track'}, <Widget>[
      dom.strong(<Widget>[Component.text('Track ${index + 1}: ${track['bone']} ${track['target']}')]),
      // Keyed by the value: a native select the user changed keeps its own
      // selection when only the option attributes change.
      dom.label(key: ValueKey<String>('blend:$index:$blend'), classes: 'animus-rig-field is-inline', <Widget>[
        const dom.span(<Widget>[Component.text('Blend')]),
        ArcaneSelect(
          size: ComponentSize.sm,
          value: visible ? 'replace' : blend,
          disabled: visible,
          options: <ArcaneSelectOption>[for (final Blend option in Blend.values) ArcaneSelectOption(label: option.name, value: option.name)],
          onChanged: (String value) => component.onEdit((JsonMap json) => setTrackBlend(json, index, Blend.values.byName(value))),
        ),
      ]),
      dom.span(classes: 'animus-muted', <Widget>[
        Component.text(visible ? 'Visible tracks always replace.' : blendDescriptions[blend] ?? ''),
      ]),
      Button.ghost(
        label: 'Move up',
        size: ButtonSize.small,
        disabled: index == 0,
        onPressed: () {
          component.onEdit((JsonMap json) => moveTrack(json, index, index - 1));
          component.onSelectTrack(index - 1);
        },
      ),
      Button.ghost(
        label: 'Move down',
        size: ButtonSize.small,
        disabled: index + 1 >= tracks.length,
        onPressed: () {
          component.onEdit((JsonMap json) => moveTrack(json, index, index + 1));
          component.onSelectTrack(index + 1);
        },
      ),
      Button.ghost(
        label: 'Delete track',
        size: ButtonSize.small,
        onPressed: () {
          component.onSelectTrack(null);
          component.onEdit((JsonMap json) => removeTrack(json, index));
        },
      ),
    ]);
  }

  List<Widget> _keyframePanel(List<JsonMap> tracks) {
    final (int, int)? selected = component.keyframe;
    if (selected == null || selected.$1 >= tracks.length) return const <Widget>[];
    final JsonMap track = tracks[selected.$1];
    final List<JsonMap> keys = keyframesOf(track);
    if (selected.$2 >= keys.length) return const <Widget>[];
    final JsonMap key = keys[selected.$2];
    final String easing = key['easing'] is String ? key['easing']! as String : 'linear';
    final bool bezier = easing.startsWith('bezier');
    final Object? value = key['value'];
    final double tick = _number(key['tick']);
    final double playhead = (component.time * 1000).roundToDouble() / 1000;
    return <Widget>[
      dom.div(classes: 'animus-rig-keyframe', attributes: const <String, String>{'aria-label': 'Keyframe'}, <Widget>[
        dom.strong(<Widget>[Component.text('${track['bone']} ${track['target']} at tick ${_format(tick)}')]),
        dom.label(classes: 'animus-rig-field is-inline', <Widget>[
          const dom.span(<Widget>[Component.text('Tick')]),
          TextInput(
            size: ComponentSize.sm,
            type: TextInputType.number,
            value: _drafts['tick'] ?? _format(tick),
            attributes: const <String, String>{'aria-label': 'Keyframe tick', 'step': 'any'},
            onChanged: (String next) => _drafts['tick'] = next,
            onSubmitted: (String _) => _commitTick(selected),
            onBlur: () => _commitTick(selected),
          ),
        ]),
        dom.label(key: ValueKey<String>('easing:${selected.$1}:${selected.$2}:$easing'), classes: 'animus-rig-field is-inline', <Widget>[
          const dom.span(<Widget>[Component.text('Easing')]),
          ArcaneSelect(
            size: ComponentSize.sm,
            value: bezier ? 'bezier' : easing,
            options: <ArcaneSelectOption>[
              for (final String name in <String>[...namedEasings, 'bezier']) ArcaneSelectOption(label: name, value: name),
            ],
            onChanged: (String next) => component.onEdit(
              (JsonMap json) => setKeyframeEasing(json, selected.$1, selected.$2, next == 'bezier' ? 'bezier(0.25,0.1,0.25,1)' : next),
            ),
          ),
        ]),
        if (bezier)
          dom.label(classes: 'animus-rig-field', <Widget>[
            const dom.span(<Widget>[Component.text('Curve')]),
            TextInput(
              size: ComponentSize.sm,
              value: _drafts['bezier'] ?? easing,
              attributes: <String, String>{'aria-label': 'Bezier curve', if (_bezierError != null) 'aria-invalid': 'true'},
              onChanged: (String next) => _drafts['bezier'] = next,
              onSubmitted: (String _) => _commitBezier(selected),
              onBlur: () => _commitBezier(selected),
            ),
            if (_bezierError != null)
              dom.span(classes: 'animus-field-error', attributes: const <String, String>{'role': 'alert'}, <Widget>[Component.text(_bezierError!)]),
          ]),
        if (value is bool)
          dom.label(key: ValueKey<String>('visible:${selected.$1}:${selected.$2}:$value'), classes: 'animus-rig-field is-inline', <Widget>[
            const dom.span(<Widget>[Component.text('Visible')]),
            ArcaneSelect(
              size: ComponentSize.sm,
              value: '$value',
              options: const <ArcaneSelectOption>[
                ArcaneSelectOption(label: 'shown', value: 'true'),
                ArcaneSelectOption(label: 'hidden', value: 'false'),
              ],
              onChanged: (String next) => component.onEdit(
                (JsonMap json) => setKeyframe(json, '${track['bone']}', TrackTarget.visible, tick, next == 'true', track: selected.$1),
              ),
            ),
          ])
        else if (value is List<Object?>)
          dom.div(classes: 'animus-rig-vector', <Widget>[
            for (int axis = 0; axis < 3 && axis < value.length; axis++)
              TextInput(
                size: ComponentSize.sm,
                type: TextInputType.number,
                value: _drafts['$axis'] ?? _format(_number(value[axis])),
                attributes: <String, String>{'aria-label': 'Keyframe value ${const <String>['X', 'Y', 'Z'][axis]}', 'step': 'any'},
                onChanged: (String next) => _drafts['$axis'] = next,
                onSubmitted: (String _) => _commitValue(track, selected, tick, value),
                onBlur: () => _commitValue(track, selected, tick, value),
              ),
          ]),
        Button.ghost(
          label: 'Copy to tick ${_format(playhead)}',
          size: ButtonSize.small,
          disabled: playhead == tick || playhead > _number(component.animation['durationTicks']),
          onPressed: () => component.onEdit((JsonMap json) => copyKeyframe(json, selected.$1, selected.$2, playhead)),
        ),
        Button.ghost(
          label: 'Delete keyframe',
          size: ButtonSize.small,
          onPressed: () => component.onEdit((JsonMap json) => removeKeyframe(json, selected.$1, selected.$2)),
        ),
      ]),
    ];
  }

  void _commitTick((int, int) selected) {
    final String? draft = _drafts.remove('tick');
    final double? parsed = draft == null ? null : double.tryParse(draft.trim());
    if (parsed != null) component.onEdit((JsonMap json) => moveKeyframe(json, selected.$1, selected.$2, parsed));
    setState(() {});
  }

  void _commitBezier((int, int) selected) {
    final String? draft = _drafts['bezier'];
    if (draft == null) return;
    final String curve = draft.replaceAll(' ', '');
    try {
      Easing.parse(curve);
    } on FormatException catch (refused) {
      setState(() => _bezierError = refused.message);
      return;
    }
    _drafts.remove('bezier');
    setState(() => _bezierError = null);
    component.onEdit((JsonMap json) => setKeyframeEasing(json, selected.$1, selected.$2, curve));
  }

  void _commitValue(JsonMap track, (int, int) selected, double tick, List<Object?> value) {
    final List<double> next = <double>[for (final Object? component in value) _number(component)];
    bool changed = false;
    for (int axis = 0; axis < next.length; axis++) {
      final String? draft = _drafts.remove('$axis');
      final double? parsed = draft == null ? null : double.tryParse(draft.trim());
      if (parsed != null && parsed.isFinite && parsed != next[axis]) {
        next[axis] = parsed;
        changed = true;
      }
    }
    if (changed) {
      component.onEdit(
        (JsonMap json) => setKeyframe(json, '${track['bone']}', TrackTarget.values.byName('${track['target']}'), tick, next, track: selected.$1),
      );
    }
    setState(() {});
  }
}
