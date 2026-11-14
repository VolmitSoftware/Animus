/// The selected track's blend, order and removal, and the selected
/// keyframe's tick, easing (with the curve checked at the field), value,
/// copy and removal. Removing asks first.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart' hide Easing;
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component, ValueKey;

import '../../config/docs_links.dart';
import '../../logic/plural.dart';
import '../../logic/rig_edit.dart';
import '../../logic/rig_sampler.dart';
import '../../logic/rig_view.dart';
import '../../ui/action_row.dart';
import '../../ui/confirm_dialog.dart';
import '../../ui/field.dart';
import '../../ui/menu.dart';
import '../../ui/toggle_choice.dart';
import 'rig_inputs.dart';
import '../select/animus_select.dart';
import '../npc/npc_layout.dart';

typedef KeyframeEdit = void Function(JsonMap Function(JsonMap animation) change, {String? coalesce});

const Map<String, String> blendDescriptions = <String, String>{
  'replace': 'Sets the channel, ignoring the tracks above it.',
  'add': 'Adds to the tracks above it.',
  'multiply': 'Multiplies the tracks above it.',
};

double _number(Object? value) => value is num ? value.toDouble() : 0;

class RigKeyframePanel extends StatelessWidget {
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
  Widget build(BuildContext context) {
    final List<JsonMap> tracks = tracksOf(animation);
    final int? index = track;
    return wrapLine(basis: '24rem', spacing: Gap.lg, <Widget>[
      if (index != null && index < tracks.length) _trackPanel(tracks, index),
      ?_keyframePanel(tracks),
    ]);
  }

  Widget _trackPanel(List<JsonMap> tracks, int index) {
    final JsonMap entry = tracks[index];
    final String blend = entry['blend'] is String ? entry['blend']! as String : 'replace';
    final bool visible = entry['target'] == 'visible';
    Future<void> delete() async {
      final bool yes = await askToConfirm(
        title: 'Delete track ${index + 1}?',
        message: 'Its ${countOf(keyframesOf(entry).length, 'keyframe')} go${keyframesOf(entry).length == 1 ? 'es' : ''} with it. Undo brings it back.',
        confirmLabel: 'Delete track',
        destructive: true,
      );
      if (!yes) return;
      onSelectTrack(null);
      onEdit((JsonMap json) => removeTrack(json, index));
    }

    return dom.section(styles: stackStyles(gap: Gap.xs), attributes: const <String, String>{'aria-label': 'Track'}, <Widget>[
      ActionRow(
        dense: true,
        label: 'Track',
        leading: <Widget>[
          dom.h4(styles: groupHeadingStyles, <Widget>[Component.text('Track ${index + 1}: ${trackLabel(entry)}')]),
        ],
        secondary: <RowAction>[
          RowAction('Move up', index == 0 ? null : () {
            onEdit((JsonMap json) => moveTrack(json, index, index - 1));
            onSelectTrack(index - 1);
          }, icon: ArcaneIcon.arrowUp(), iconOnly: true),
          RowAction('Move down', index + 1 >= tracks.length ? null : () {
            onEdit((JsonMap json) => moveTrack(json, index, index + 1));
            onSelectTrack(index + 1);
          }, icon: ArcaneIcon.arrowDown(), iconOnly: true),
        ],
        more: <AppMenuEntry>[AppMenuEntry('Delete track', delete, destructive: true)],
      ),
      Field(
        key: ValueKey<String>('blend:$index:$blend'),
        label: 'Blend',
        forId: 'rig-track-blend',
        tip: visible ? 'Visible tracks always replace.' : blendDescriptions[blend],
        docs: Docs.animations,
        child: AnimusSelect.values(
          id: 'rig-track-blend',
          value: visible ? 'replace' : blend,
          disabled: visible,
          values: <String>[for (final Blend option in Blend.values) option.name],
          labels: blendLabels,
          onChanged: (String? value) {
            if (value != null) onEdit((JsonMap json) => setTrackBlend(json, index, Blend.values.byName(value)));
          },
        ),
      ),
    ]);
  }

  Widget? _keyframePanel(List<JsonMap> tracks) {
    final (int, int)? selected = keyframe;
    if (selected == null || selected.$1 >= tracks.length) return null;
    final JsonMap entry = tracks[selected.$1];
    final List<JsonMap> keys = keyframesOf(entry);
    if (selected.$2 >= keys.length) return null;
    final JsonMap key = keys[selected.$2];
    final String easing = key['easing'] is String ? key['easing']! as String : 'linear';
    final bool bezier = easing.startsWith('bezier');
    final Object? value = key['value'];
    final double tick = _number(key['tick']);
    final double playhead = (time * 1000).roundToDouble() / 1000;
    final String where = '${trackLabel(entry)} at tick ${trimmedNumber(tick)}';
    final String id = '${selected.$1}:${selected.$2}';
    Future<void> delete() async {
      final bool yes = await askToConfirm(title: 'Delete this keyframe?', message: '${where[0].toUpperCase()}${where.substring(1)}. Undo brings it back.', confirmLabel: 'Delete keyframe', destructive: true);
      if (yes) onEdit((JsonMap json) => removeKeyframe(json, selected.$1, selected.$2));
    }

    return dom.section(styles: stackStyles(gap: Gap.xs), attributes: const <String, String>{'aria-label': 'Keyframe'}, <Widget>[
      ActionRow(
        dense: true,
        label: 'Keyframe',
        leading: <Widget>[
          dom.h4(styles: groupHeadingStyles, <Widget>[Component.text(where)]),
        ],
        secondary: <RowAction>[
          RowAction(
            'Copy to tick ${trimmedNumber(playhead)}',
            playhead == tick || playhead > _number(animation['durationTicks']) ? null : () => onEdit((JsonMap json) => copyKeyframe(json, selected.$1, selected.$2, playhead)),
            icon: ArcaneIcon.copy(),
          ),
        ],
        more: <AppMenuEntry>[AppMenuEntry('Delete keyframe', delete, destructive: true)],
      ),
      CommitField(
        key: ValueKey<String>('tick:$id:$tick'),
        label: 'Tick',
        id: 'rig-key-tick',
        number: true,
        hint: secondsLabel(tick),
        value: trimmedNumber(tick),
        onCommit: (String draft) {
          final double? parsed = double.tryParse(draft.trim());
          if (parsed == null) return 'Use a number of ticks.';
          try {
            moveKeyframe(animation, selected.$1, selected.$2, parsed);
          } on RigEditException catch (refused) {
            return refused.message;
          }
          onEdit((JsonMap json) => moveKeyframe(json, selected.$1, selected.$2, parsed));
          return null;
        },
      ),
      Field(
        key: ValueKey<String>('easing:$id:$easing'),
        label: 'Easing',
        forId: 'rig-key-easing',
        tip: 'How the value moves from this keyframe to the next.',
        docs: Docs.animations,
        child: AnimusSelect.values(
          id: 'rig-key-easing',
          value: bezier ? 'bezier' : easing,
          values: <String>[...namedEasings, 'bezier'],
          labels: easingLabels,
          onChanged: (String? next) {
            if (next == null) return;
            onEdit((JsonMap json) => setKeyframeEasing(json, selected.$1, selected.$2, next == 'bezier' ? 'bezier(0.25,0.1,0.25,1)' : next));
          },
        ),
      ),
      if (bezier)
        CommitField(
          key: ValueKey<String>('curve:$id'),
          label: 'Curve',
          id: 'rig-key-curve',
          tip: 'A cubic bezier: bezier(x1,y1,x2,y2), each from 0 to 1.',
          value: easing,
          onCommit: (String draft) {
            final String curve = draft.replaceAll(' ', '');
            try {
              Easing.parse(curve);
            } on FormatException catch (refused) {
              return refused.message;
            }
            onEdit((JsonMap json) => setKeyframeEasing(json, selected.$1, selected.$2, curve));
            return null;
          },
        ),
      if (value is bool)
        Field(
          key: ValueKey<String>('visible:$id:$value'),
          label: 'Visible',
          child: ToggleChoice<bool>(
            label: 'Visible',
            value: value,
            options: const <ChoiceOption<bool>>[ChoiceOption<bool>(true, 'Shown'), ChoiceOption<bool>(false, 'Hidden')],
            onChanged: (bool next) => onEdit((JsonMap json) => setKeyframe(json, '${entry['bone']}', TrackTarget.visible, tick, next, track: selected.$1)),
          ),
        )
      else if (value is List<Object?>)
        VectorField(
          key: ValueKey<String>('value:$id'),
          label: 'Value',
          name: 'Keyframe value',
          value: <double>[for (final Object? each in value) _number(each)],
          limit: double.infinity,
          onCommit: (List<num> next) => onEdit(
            (JsonMap json) => setKeyframe(json, '${entry['bone']}', TrackTarget.values.byName('${entry['target']}'), tick, <double>[for (final num each in next) each.toDouble()], track: selected.$1),
          ),
        ),
    ]);
  }
}
