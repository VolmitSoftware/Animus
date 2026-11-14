/// The keyframe timeline under the rig stage: the animation picker, the
/// clip's loop and length, playback, a ruler with tick numbers, one lane
/// per track with draggable, focusable keyframe diamonds, the events lane,
/// the key bar, the selected track, keyframe or event's fields, and the
/// rig's animation names.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../config/docs_links.dart';
import '../../logic/plural.dart';
import '../../logic/rig_edit.dart';
import '../../logic/rig_sampler.dart';
import '../../logic/rig_view.dart';
import '../../ui/action_row.dart';
import '../../ui/confirm_dialog.dart';
import '../../ui/empty_state.dart';
import '../../ui/info_tip.dart';
import '../../ui/menu.dart';
import 'rig_events.dart';
import 'rig_gizmo.dart';
import 'rig_keyframe_panel.dart';
import '../select/animus_select.dart';
import '../npc/npc_layout.dart';
import '../../ui/stack.dart';

typedef AnimationEdit = void Function(JsonMap Function(JsonMap animation) change, {String? coalesce});

const List<double> playbackSpeeds = <double>[0.25, 0.5, 1, 2, 4];

/// What each loop mode does, as the server plays it.
const Map<String, String> loopDescriptions = <String, String>{
  'once': 'Plays once, then returns to idle.',
  'loop': 'Restarts from tick 0.',
  'pingpong': 'Plays forward, then backward, repeating.',
  'hold': 'Plays once and keeps the last pose.',
};

/// Ruler label spacing: the smallest step that shows at most ten labels.
int rulerStep(double span) {
  for (final int step in const <int>[1, 2, 5, 10, 20, 50, 100, 200]) {
    if (span / step <= 10) return step;
  }
  return 200;
}

double _number(Object? value) => value is num ? value.toDouble() : 0;

class RigTimeline extends StatefulWidget {
  const RigTimeline({
    required this.animations,
    required this.selected,
    required this.animation,
    required this.valid,
    required this.time,
    required this.playing,
    required this.speed,
    required this.status,
    required this.names,
    required this.selectedBone,
    required this.keyTarget,
    required this.track,
    required this.keyframe,
    required this.event,
    required this.onAnimation,
    required this.onNewAnimation,
    required this.onPlay,
    required this.onScrub,
    required this.onSpeed,
    required this.onKeyTarget,
    required this.onAddKeyframe,
    required this.onNewTrack,
    required this.onSelectTrack,
    required this.onSelectKeyframe,
    required this.onSelectEvent,
    required this.onEdit,
    super.key,
  });

  /// This rig's animations as (label, id).
  final List<(String, String)> animations;

  /// The open animation's id; empty for the rest pose.
  final String selected;
  final JsonMap? animation;
  final bool valid;

  /// Clip time at the playhead, ticks.
  final double time;
  final bool playing;
  final double speed;

  /// What the preview plays besides the clip (back to idle after a once clip).
  final String? status;

  /// The rig's named animations panel.
  final Widget names;
  final String? selectedBone;
  final TrackTarget keyTarget;
  final int? track;
  final (int, int)? keyframe;
  final int? event;
  final void Function(String id) onAnimation;
  final void Function() onNewAnimation;
  final void Function() onPlay;
  final void Function(double tick) onScrub;
  final void Function(double speed) onSpeed;
  final void Function(TrackTarget target) onKeyTarget;
  final void Function() onAddKeyframe;
  final void Function(Blend blend) onNewTrack;
  final void Function(int? track) onSelectTrack;
  final void Function(int track, int keyframe, double tick) onSelectKeyframe;
  final void Function(int? event) onSelectEvent;
  final AnimationEdit onEdit;

  @override
  State<RigTimeline> createState() => _RigTimelineState();
}

class _RigTimelineState extends State<RigTimeline> {
  String? _duration;
  String? _focus;
  (int, int)? _dragging;
  double? _dragTick;
  JSFunction? _move;
  JSFunction? _up;

  @override
  void dispose() {
    _release();
    super.dispose();
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

  /// Focuses [_focus] once it is in the page (after the edit lands).
  void _refocus() {
    final String? selector = _focus;
    if (selector == null) return;
    context.binding.addPostFrameCallback(() {
      final web.Element? element = web.document.querySelector(selector);
      if (element == null) return;
      _focus = null;
      (element as web.HTMLElement).focus();
    });
  }

  @override
  Widget build(BuildContext context) {
    _refocus();
    final JsonMap? animation = component.animation;
    final bool none = component.animations.isEmpty;
    return dom.div(classes: 'animus-rig-timeline', styles: stackStyles(gap: Gap.md), attributes: const <String, String>{'aria-label': 'Timeline'}, <Widget>[
      if (!none)
        ActionRow(
          dense: true,
          label: 'Animation',
          leading: <Widget>[
            AnimusSelect.values(
              id: 'rig-animation',
              value: component.selected,
              values: <String>[for (final (String _, String id) in component.animations) id],
              labels: <String, String>{for (final (String label, String id) in component.animations) id: label},
              allowNone: true,
              noneLabel: 'Rest pose',
              hiddenLabel: 'Animation',
              onChanged: (String? value) => component.onAnimation(value ?? ''),
            ),
          ],
          secondary: <RowAction>[RowAction('New animation', component.onNewAnimation, icon: ArcaneIcon.plus())],
        ),
      if (animation == null)
        EmptyState(
          message: none ? 'No animations yet.' : 'Pick an animation to key bones.',
          icon: ArcaneIcon.film(),
          action: none ? Button.outline(label: 'New animation', icon: ArcaneIcon.plus(), onPressed: component.onNewAnimation) : null,
        )
      else
        ..._clipView(animation),
      component.names,
    ]);
  }

  List<Widget> _clipView(JsonMap animation) {
    final double duration = _number(animation['durationTicks']);
    final String loop = animation['loop'] is String ? animation['loop']! as String : 'once';
    final List<JsonMap> tracks = tracksOf(animation);
    final List<JsonMap> events = eventsOf(animation);
    final double span = duration <= 0 ? 1 : duration;
    final int step = rulerStep(span);
    final double time = component.time;
    final String? bone = component.selectedBone;
    final bool visible = component.keyTarget == TrackTarget.visible;
    return <Widget>[
      wrapLine(basis: '24rem', spacing: Gap.lg, <Widget>[
        ActionRow(
          dense: true,
          label: 'Clip',
          leading: <Widget>[
            AnimusSelect.values(
              id: 'rig-loop',
              value: loop,
              values: loopLabels.keys.toList(),
              labels: loopLabels,
              hiddenLabel: 'Loop',
              onChanged: (String? value) {
                if (value != null) component.onEdit((JsonMap json) => cloneJson(json)..['loop'] = value);
              },
            ),
            InfoButton(tip: loopDescriptions[loop] ?? loop, docs: Docs.animations),
            wrapItem(
              '0 0 4.5rem',
              TextInput(
                id: 'rig-length',
                size: ComponentSize.sm,
                value: _duration ?? trimmedNumber(duration),
                attributes: const <String, String>{'aria-label': 'Length in ticks', 'inputmode': 'numeric', 'autocomplete': 'off', 'spellcheck': 'false'},
                onChanged: (String value) => _duration = value,
                onSubmitted: (String _) => _commitDuration(animation, duration),
                onBlur: () => _commitDuration(animation, duration),
              ),
            ),
            Text.truncated('ticks · ${secondsLabel(duration)}', size: FontSize.sm, color: TextColor.muted),
          ],
        ),
        ActionRow(
          dense: true,
          label: 'Playback',
          leading: <Widget>[
            AnimusSelect.values(
              value: '${component.speed}',
              values: <String>[for (final double speed in playbackSpeeds) '$speed'],
              labels: <String, String>{for (final double speed in playbackSpeeds) '$speed': '${trimmedNumber(speed)}x'},
              hiddenLabel: 'Speed',
              onChanged: (String? value) {
                if (value != null) component.onSpeed(double.parse(value));
              },
            ),
            dom.span(styles: _readoutStyles, attributes: const <String, String>{'data-tick': ''}, <Widget>[
              Component.text(playheadReadout(time, duration)),
            ]),
          ],
          primary: RowAction(
            component.playing ? 'Pause' : 'Play',
            component.valid ? component.onPlay : null,
            icon: component.playing ? ArcaneIcon.pause() : ArcaneIcon.play(),
          ),
        ),
      ]),
      if (component.status != null) dom.div(attributes: const <String, String>{'role': 'status'}, <Widget>[mutedText(component.status!)]),
      dom.div(classes: 'animus-rig-lanes', <Widget>[
        dom.div(classes: 'animus-rig-lane is-ruler', <Widget>[
          const dom.span(classes: 'animus-rig-lane-name', <Widget>[Component.text('Tick')]),
          dom.div(classes: 'animus-rig-ruler', attributes: const <String, String>{'aria-hidden': 'true'}, <Widget>[
            for (int tick = 0; tick <= span; tick += step)
              dom.span(
                classes: 'animus-rig-ruler-mark${tick + step > span ? ' is-end' : (tick ~/ step).isOdd ? ' is-odd' : ''}',
                styles: _at(tick.toDouble(), span),
                <Widget>[Component.text('$tick')],
              ),
            _playhead(time, span),
          ]),
        ]),
        dom.div(classes: 'animus-rig-lane is-scrubber', <Widget>[
          const dom.span(classes: 'animus-rig-lane-name', <Widget>[]),
          ArcaneSlider(
            id: 'rig-scrub',
            value: time.clamp(0, span).toDouble(),
            max: span,
            step: 0.25,
            showValue: false,
            showBounds: false,
            ariaLabel: 'Playhead tick',
            styles: _scrubStyles,
            onChanged: component.onScrub,
          ),
        ]),
        for (int index = 0; index < tracks.length; index++) _lane(tracks, index, span, time),
        if (tracks.isEmpty) mutedLine('No tracks yet. Select a bone and add a keyframe.'),
        _eventLane(events, span, time),
      ]),
      ActionRow(
        dense: true,
        label: 'Keys',
        moreId: 'rig-keys-more',
        leading: <Widget>[
          const dom.label(htmlFor: 'rig-channel', <Widget>[Text('Channel', size: FontSize.sm, color: TextColor.muted)]),
          AnimusSelect.values(
            id: 'rig-channel',
            value: component.keyTarget.name,
            values: <String>[for (final TrackTarget target in TrackTarget.values) target.name],
            labels: <String, String>{for (final TrackTarget target in TrackTarget.values) target.name: channelLabels[target]!},
            onChanged: (String? value) {
              if (value != null) component.onKeyTarget(TrackTarget.values.byName(value));
            },
          ),
        ],
        secondary: <RowAction>[
          RowAction('New track', bone == null ? null : () => component.onNewTrack(Blend.replace), icon: ArcaneIcon.listPlus()),
          RowAction('Add event', events.length >= maximumEvents ? null : () => _addEvent(events, time, duration), icon: ArcaneIcon.music()),
          RowAction('Add keyframe', bone == null ? null : component.onAddKeyframe, icon: ArcaneIcon.diamond()),
        ],
        more: <AppMenuEntry>[
          AppMenuEntry('New add track', bone == null || visible ? null : () => component.onNewTrack(Blend.add), hint: blendDescriptions['add']),
          AppMenuEntry('New multiply track', bone == null || visible ? null : () => component.onNewTrack(Blend.multiply), hint: blendDescriptions['multiply']),
        ],
      ),
      mutedLine(bone == null ? 'Select a bone to key it.' : 'Keys $bone at tick ${trimmedNumber(time)}.'),
      RigKeyframePanel(
        animation: animation,
        track: component.track,
        keyframe: component.keyframe,
        time: time,
        onEdit: component.onEdit,
        onSelectTrack: component.onSelectTrack,
      ),
      if (component.event != null && component.event! < events.length)
        RigEventEditor(
          event: events[component.event!],
          index: component.event!,
          duration: duration.round(),
          onUpdate: (int tick, String capability, JsonMap arguments) {
            final int index = component.event!;
            component.onEdit((JsonMap json) => updateEvent(json, index, tick, capability, arguments));
            final List<JsonMap> rest = <JsonMap>[...events]..removeAt(index);
            component.onSelectEvent(rest.where((JsonMap event) => _number(event['tick']) <= tick).length);
          },
          onRemove: () {
            final int index = component.event!;
            component.onSelectEvent(null);
            component.onEdit((JsonMap json) => removeEvent(json, index));
          },
        ),
    ];
  }

  void _addEvent(List<JsonMap> events, double time, double duration) {
    final int tick = time.round().clamp(0, duration.round());
    component.onEdit((JsonMap json) => addEvent(json, tick, 'animus:play-sound', starterArguments('animus:play-sound')));
    component.onSelectEvent(events.where((JsonMap event) => _number(event['tick']) <= tick).length);
  }

  dom.Styles _at(double tick, double span) => dom.Styles(raw: <String, String>{'left': '${(tick / span * 100).clamp(0, 100).toStringAsFixed(2)}%'});

  /// The playhead slider spans the lane's keys, inset like the ruler.
  static const ArcaneStyleData _scrubStyles = ArcaneStyleData(widthCustom: 'calc(100% - 1rem)', marginStringCustom: '0 0.5rem');

  /// The playhead's tick and time: one line, figures that keep their width.
  static const dom.Styles _readoutStyles = dom.Styles(raw: <String, String>{
    'min-width': '0',
    'overflow': 'hidden',
    'white-space': 'nowrap',
    'text-overflow': 'ellipsis',
    'font-variant-numeric': 'tabular-nums',
    'font-size': '0.875rem',
    'color': 'var(--muted-foreground)',
  });

  Widget _playhead(double time, double span) => dom.span(classes: 'animus-rig-playhead', styles: _at(time, span), const <Widget>[]);

  /// Saves the length field; a length that cuts keyframes or events asks
  /// first.
  Future<void> _commitDuration(JsonMap animation, double current) async {
    final String? draft = _duration;
    _duration = null;
    final int? parsed = draft == null ? null : int.tryParse(draft.trim());
    if (parsed == null || parsed == current) {
      setState(() {});
      return;
    }
    if (parsed < 1 || parsed > maximumDurationTicks) {
      ArcaneSonner.warning('Lengths are 1 to $maximumDurationTicks ticks (${secondsLabel(maximumDurationTicks.toDouble())}).');
      setState(() {});
      return;
    }
    final ({int keyframes, int events}) beyond = pastEnd(animation, parsed);
    final bool trim = beyond.keyframes + beyond.events > 0;
    if (trim) {
      setState(() {});
      final bool yes = await askToConfirm(
        title: 'Shorten to ${countOf(parsed, 'tick')}?',
        message: '${countOf(beyond.keyframes, 'keyframe')}${beyond.events > 0 ? ' and ${countOf(beyond.events, 'event')}' : ''} after tick $parsed '
            'are dropped. Undo brings them back.',
        confirmLabel: 'Trim and shorten',
        destructive: true,
      );
      if (!yes || !mounted) return;
    }
    component.onEdit((JsonMap json) => setDuration(json, parsed, trim: trim));
    if (mounted) setState(() {});
  }

  Widget _lane(List<JsonMap> tracks, int index, double span, double time) {
    final JsonMap track = tracks[index];
    final List<JsonMap> keys = keyframesOf(track);
    final bool visible = track['target'] == 'visible';
    final bool selected = component.track == index;
    final String name = trackLabel(track);
    return dom.div(
      classes: 'animus-rig-lane${track['bone'] == component.selectedBone ? ' is-bone' : ''}${selected ? ' is-selected' : ''}',
      attributes: <String, String>{'data-track': '$index'},
      <Widget>[
        dom.span(classes: 'animus-rig-lane-name', <Widget>[
          Button(
            label: name,
            size: ButtonSize.sm,
            variant: selected ? ButtonVariant.secondary : ButtonVariant.ghost,
            attributes: <String, String>{'aria-pressed': '$selected', 'title': name, 'data-lane': '$index'},
            onPressed: () => component.onSelectTrack(selected ? null : index),
          ),
        ]),
        dom.div(classes: 'animus-rig-lane-keys', <Widget>[
          if (visible)
            for (int key = 0; key < keys.length; key++)
              if (keys[key]['value'] == false)
                dom.span(
                  classes: 'animus-rig-hidden-span',
                  styles: dom.Styles(raw: <String, String>{
                    'left': '${(_number(keys[key]['tick']) / span * 100).clamp(0, 100).toStringAsFixed(2)}%',
                    'right': '${(100 - (key + 1 < keys.length ? _number(keys[key + 1]['tick']) : span) / span * 100).clamp(0, 100).toStringAsFixed(2)}%',
                  }),
                  const <Widget>[],
                ),
          _playhead(time, span),
          for (int key = 0; key < keys.length; key++) _diamond(track, keys, index, key, span),
        ]),
      ],
    );
  }

  Widget _diamond(JsonMap track, List<JsonMap> keys, int index, int key, double span) {
    final double tick = _dragging == (index, key) && _dragTick != null ? _dragTick! : _number(keys[key]['tick']);
    final bool hidden = keys[key]['value'] == false;
    final String label = '${trackLabel(track)} keyframe at tick ${trimmedNumber(tick)}${track['target'] == 'visible' ? (hidden ? ', hidden' : ', shown') : ''}';
    return dom.button(
      classes: 'animus-rig-key${component.keyframe == (index, key) ? ' is-selected' : ''}${hidden ? ' is-hidden' : ''}',
      attributes: <String, String>{
        'type': 'button',
        'aria-label': label,
        'aria-pressed': '${component.keyframe == (index, key)}',
        'data-key': '$index:$key',
        'title': label,
      },
      styles: _at(tick, span),
      events: <String, void Function(web.Event)>{
        'pointerdown': (web.Event event) => _grab(event as web.PointerEvent, keys, index, key, span),
        'keydown': (web.Event event) => _keyKey(event as web.KeyboardEvent, keys, index, key),
      },
      const <Widget>[],
    );
  }

  void _grab(web.PointerEvent event, List<JsonMap> keys, int index, int key, double span) {
    if (event.button != 0) return;
    event.preventDefault();
    final web.Element lane = (event.currentTarget! as web.Element).parentElement!;
    final web.DOMRect rect = lane.getBoundingClientRect();
    final double start = _number(keys[key]['tick']);
    final double startX = eventNumber(event, 'clientX');
    _release();
    setState(() {
      _dragging = (index, key);
      _dragTick = start;
    });
    _move = ((web.Event moved) {
      final double x = eventNumber(moved, 'clientX');
      if (rect.width <= 0) return;
      final double raw = start + (x - startX) / rect.width * span;
      setState(() => _dragTick = ((raw / 0.25).roundToDouble() * 0.25).clamp(0, span).toDouble());
    }).toJS;
    _up = ((web.Event _) {
      final double? to = _dragTick;
      _release();
      setState(() {
        _dragging = null;
        _dragTick = null;
      });
      if (to == null || to == start) {
        component.onSelectKeyframe(index, key, start);
        return;
      }
      if (keys.any((JsonMap other) => !identical(other, keys[key]) && _number(other['tick']) == to)) {
        ArcaneSonner.warning('Another keyframe is at tick ${trimmedNumber(to)}. Drop it on a free tick.');
        return;
      }
      final int landed = keys.where((JsonMap other) => !identical(other, keys[key]) && _number(other['tick']) < to).length;
      component.onEdit((JsonMap json) => moveKeyframe(json, index, key, to));
      component.onSelectKeyframe(index, landed, to);
    }).toJS;
    web.window.addEventListener('pointermove', _move);
    web.window.addEventListener('pointerup', _up);
    web.window.addEventListener('pointercancel', _up);
  }

  void _keyKey(web.KeyboardEvent event, List<JsonMap> keys, int index, int key) {
    final double tick = _number(keys[key]['tick']);
    final double step = event.shiftKey ? 0.25 : 1;
    switch (event.key) {
      case 'ArrowLeft':
      case 'ArrowRight':
        event.preventDefault();
        final double to = tick + (event.key == 'ArrowLeft' ? -step : step);
        final double end = _number(component.animation?['durationTicks']);
        if (to < 0 || to > end || keys.any((JsonMap other) => _number(other['tick']) == to)) return;
        final int landed = keys.where((JsonMap other) => !identical(other, keys[key]) && _number(other['tick']) < to).length;
        _focus = '[data-key="$index:$landed"]';
        component.onEdit((JsonMap json) => moveKeyframe(json, index, key, to), coalesce: 'key-nudge:$index');
        component.onSelectKeyframe(index, landed, to);
      case 'Delete':
      case 'Backspace':
        event.preventDefault();
        component.onEdit((JsonMap json) => removeKeyframe(json, index, key));
    }
  }

  Widget _eventLane(List<JsonMap> events, double span, double time) {
    return dom.div(classes: 'animus-rig-lane is-events', <Widget>[
      const dom.span(classes: 'animus-rig-lane-name', <Widget>[Component.text('Events')]),
      dom.div(classes: 'animus-rig-lane-keys', <Widget>[
        _playhead(time, span),
        for (int index = 0; index < events.length; index++)
          dom.button(
            classes: 'animus-rig-event${component.event == index ? ' is-selected' : ''}',
            attributes: <String, String>{
              'type': 'button',
              'data-event': '$index',
              'aria-pressed': '${component.event == index}',
              'aria-label': '${eventSummary(events[index])} at tick ${trimmedNumber(_number(events[index]['tick']))}',
              'title': '${eventSummary(events[index])} at tick ${trimmedNumber(_number(events[index]['tick']))}',
            },
            styles: _at(_number(events[index]['tick']), span),
            events: <String, void Function(web.Event)>{
              'click': (web.Event _) {
                component.onSelectEvent(component.event == index ? null : index);
                component.onScrub(_number(events[index]['tick']));
              },
            },
            const <Widget>[],
          ),
      ]),
    ]);
  }
}
