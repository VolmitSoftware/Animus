/// The keyframe timeline under the rig stage: the animation picker and its
/// names, the transport (play, speed, loop, duration), a ruler with tick
/// numbers, one lane per track with draggable, focusable keyframe diamonds,
/// the events lane, and the selected track, keyframe or event's fields.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component, ValueKey;
import 'package:web/web.dart' as web;

import '../../config/docs_links.dart';
import '../../logic/rig_edit.dart';
import '../../logic/rig_sampler.dart';
import '../../model/document_kind.dart';
import '../gui/gui_fields.dart';
import '../info_tip.dart';
import 'rig_events.dart';
import 'rig_gizmo.dart';
import 'rig_keyframe_panel.dart';

typedef AnimationEdit = void Function(JsonMap Function(JsonMap animation) change, {String? coalesce});

const List<double> playbackSpeeds = <double>[0.25, 0.5, 1, 2, 4];

/// What each loop mode does, as the server plays it.
const Map<String, String> loopDescriptions = <String, String>{
  'once': 'Plays once, then returns to idle.',
  'loop': 'Restarts from tick 0.',
  'pingpong': 'Plays forward, then backward, repeating.',
  'hold': 'Plays once and keeps the last pose.',
};

String formatTick(double value) {
  final double rounded = (value * 1000).roundToDouble() / 1000;
  return rounded == rounded.roundToDouble() ? '${rounded.round()}' : '$rounded';
}

/// Ruler label spacing: the smallest step that shows at most ten labels.
int rulerStep(double span) {
  for (final int step in const <int>[1, 2, 5, 10, 20, 50, 100, 200]) {
    if (span / step <= 10) return step;
  }
  return 200;
}

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
    required this.keyBlend,
    required this.track,
    required this.keyframe,
    required this.event,
    required this.newName,
    required this.onAnimation,
    required this.onNewName,
    required this.onCreate,
    required this.onPlay,
    required this.onScrub,
    required this.onSpeed,
    required this.onKeyTarget,
    required this.onKeyBlend,
    required this.onAddKeyframe,
    required this.onNewTrack,
    required this.onSelectTrack,
    required this.onSelectKeyframe,
    required this.onSelectEvent,
    required this.onEdit,
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

  /// What the preview plays besides the clip (back to idle after a once clip).
  final String? status;

  /// The rig's named animations panel.
  final Widget names;
  final String? selectedBone;
  final TrackTarget keyTarget;
  final Blend keyBlend;
  final int? track;
  final (int, int)? keyframe;
  final int? event;
  final String newName;
  final void Function(String id) onAnimation;
  final void Function(String value) onNewName;
  final void Function() onCreate;
  final void Function() onPlay;
  final void Function(double tick) onScrub;
  final void Function(double speed) onSpeed;
  final void Function(TrackTarget target) onKeyTarget;
  final void Function(Blend blend) onKeyBlend;
  final void Function() onAddKeyframe;
  final void Function() onNewTrack;
  final void Function(int? track) onSelectTrack;
  final void Function(int track, int keyframe, double tick) onSelectKeyframe;
  final void Function(int? event) onSelectEvent;
  final AnimationEdit onEdit;

  @override
  State<RigTimeline> createState() => _RigTimelineState();
}

class _RigTimelineState extends State<RigTimeline> {
  String? _duration;
  int? _trim;
  bool _namesOpen = false;
  String? _focus;
  (int, int)? _dragging;
  double? _dragTick;
  JSFunction? _move;
  JSFunction? _up;

  static double _number(Object? value) => value is num ? value.toDouble() : 0;

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

  /// Focuses [selector] once it is in the page (after the edit lands).
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
    final String? restLabel = <String>[for (final (String label, String id) in component.animations) if (id.isEmpty) label].firstOrNull;
    return dom.div(classes: 'animus-rig-timeline', attributes: const <String, String>{'aria-label': 'Timeline'}, <Widget>[
      dom.div(classes: 'animus-rig-toolbar', <Widget>[
        dom.label(classes: 'animus-rig-field', <Widget>[
          const dom.span(<Widget>[Component.text('Animation')]),
          GuiSelect(
            value: component.selected,
            options: <String>[for (final (String _, String id) in component.animations) if (id.isNotEmpty) id],
            labels: <String, String>{for (final (String label, String id) in component.animations) if (id.isNotEmpty) id: label},
            allowNone: restLabel != null,
            noneLabel: restLabel ?? 'default',
            hiddenLabel: 'Animation',
            references: DocumentKind.animation,
            onChanged: (String? value) => component.onAnimation(value ?? ''),
          ),
        ]),
        dom.div(classes: 'animus-row', <Widget>[
          TextInput(
            size: ComponentSize.sm,
            placeholder: 'new animation name',
            value: component.newName,
            attributes: const <String, String>{'aria-label': 'Name for a new animation'},
            onChanged: component.onNewName,
            onSubmitted: (String _) => component.onCreate(),
          ),
          Button.secondary(label: 'New animation', icon: ArcaneIcon.plus(), size: ButtonSize.small, onPressed: component.onCreate),
        ]),
      ]),
      dom.div(classes: 'animus-rig-names-box', <Widget>[
        dom.button(
          classes: 'animus-rig-names-toggle',
          attributes: <String, String>{'type': 'button', 'aria-expanded': '$_namesOpen', 'aria-controls': 'animus-rig-names'},
          events: <String, void Function(web.Event)>{'click': (web.Event _) => setState(() => _namesOpen = !_namesOpen)},
          <Widget>[
            dom.span(classes: 'animus-rig-names-caret', attributes: const <String, String>{'aria-hidden': 'true'}, <Widget>[Component.text(_namesOpen ? '-' : '+')]),
            const Component.text('Animation names'),
          ],
        ),
        const InfoTip(text: 'Behaviors play animations by name. idle and walk play on their own.', topic: 'Animation names', docs: Docs.rigs),
        if (_namesOpen) dom.div(id: 'animus-rig-names', <Widget>[component.names]),
      ]),
      if (animation == null)
        const dom.p(classes: 'animus-muted', <Widget>[
          Component.text('Rest pose. Pick or create an animation to key bones.'),
        ])
      else
        ..._clipView(animation),
    ]);
  }

  List<Widget> _clipView(JsonMap animation) {
    final double duration = _number(animation['durationTicks']);
    final String loop = animation['loop'] is String ? animation['loop']! as String : 'once';
    final List<JsonMap> tracks = tracksOf(animation);
    final List<JsonMap> events = eventsOf(animation);
    final double span = duration <= 0 ? 1 : duration;
    final double time = component.time;
    final int? trim = _trim;
    final ({int keyframes, int events}) beyond = trim == null ? (keyframes: 0, events: 0) : pastEnd(animation, trim);
    return <Widget>[
      dom.div(classes: 'animus-rig-transport', attributes: const <String, String>{'role': 'group', 'aria-label': 'Playback'}, <Widget>[
        Button.primary(
          label: component.playing ? 'Pause' : 'Play',
          icon: component.playing ? ArcaneIcon.pause() : ArcaneIcon.play(),
          size: ButtonSize.small,
          disabled: !component.valid,
          onPressed: component.onPlay,
        ),
        dom.label(classes: 'animus-rig-field is-inline', <Widget>[
          const dom.span(<Widget>[Component.text('Speed')]),
          GuiSelect(
            value: '${component.speed}',
            options: <String>[for (final double speed in playbackSpeeds) '$speed'],
            labels: <String, String>{for (final double speed in playbackSpeeds) '$speed': '${formatTick(speed)}x'},
            hiddenLabel: 'Speed',
            onChanged: (String? value) {
              if (value != null) component.onSpeed(double.parse(value));
            },
          ),
        ]),
        dom.label(key: ValueKey<String>('loop:$loop'), classes: 'animus-rig-field is-inline', <Widget>[
          const dom.span(<Widget>[Component.text('Loop')]),
          GuiSelect(
            value: loop,
            options: const <String>['once', 'loop', 'pingpong', 'hold'],
            hiddenLabel: 'Loop',
            onChanged: (String? value) {
              if (value != null) component.onEdit((JsonMap json) => cloneJson(json)..['loop'] = value);
            },
          ),
        ]),
        InfoTip(text: loopDescriptions[loop] ?? loop, topic: 'Loop', docs: Docs.animations),
        dom.label(classes: 'animus-rig-field is-inline', <Widget>[
          const dom.span(<Widget>[Component.text('Duration (ticks)')]),
          TextInput(
            size: ComponentSize.sm,
            type: TextInputType.number,
            value: _duration ?? formatTick(duration),
            attributes: const <String, String>{'aria-label': 'Duration in ticks', 'min': '1', 'max': '1200'},
            onChanged: (String value) => _duration = value,
            onSubmitted: (String _) => _commitDuration(animation, duration),
            onBlur: () => _commitDuration(animation, duration),
          ),
        ]),
      ]),
      if (component.status != null) dom.p(classes: 'animus-rig-status', <Widget>[Component.text(component.status!)]),
      if (trim != null)
        dom.div(classes: 'animus-rig-trim', attributes: const <String, String>{'role': 'alert'}, <Widget>[
          dom.span(<Widget>[
            Component.text('Shortening to $trim ticks drops ${beyond.keyframes} keyframe${beyond.keyframes == 1 ? '' : 's'}'
                '${beyond.events > 0 ? ' and ${beyond.events} event${beyond.events == 1 ? '' : 's'}' : ''} after tick $trim.'),
          ]),
          Button.secondary(
            label: 'Trim and shorten',
            size: ButtonSize.small,
            onPressed: () {
              setState(() => _trim = null);
              component.onEdit((JsonMap json) => setDuration(json, trim, trim: true));
            },
          ),
          Button.ghost(label: 'Keep ${formatTick(span)} ticks', size: ButtonSize.small, onPressed: () => setState(() => _trim = null)),
        ]),
      dom.div(classes: 'animus-rig-lanes', <Widget>[
        dom.div(classes: 'animus-rig-lane is-ruler', <Widget>[
          const dom.span(classes: 'animus-rig-lane-label animus-muted', <Widget>[Component.text('Tick')]),
          dom.div(classes: 'animus-rig-ruler', attributes: const <String, String>{'aria-hidden': 'true'}, <Widget>[
            for (int tick = 0; tick <= span; tick += rulerStep(span))
              dom.span(classes: 'animus-rig-ruler-mark${tick + rulerStep(span) > span ? ' is-end' : ''}', styles: _at(tick.toDouble(), span), <Widget>[Component.text('$tick')]),
            for (final JsonMap event in events)
              dom.span(classes: 'animus-rig-ruler-event', styles: _at(_number(event['tick']), span), const <Widget>[]),
            _playhead(time, span),
          ]),
        ]),
        dom.div(classes: 'animus-rig-lane is-scrubber', <Widget>[
          dom.span(classes: 'animus-rig-tick', attributes: const <String, String>{'data-tick': 'true'}, <Widget>[
            Component.text('tick ${formatTick(time)} / ${formatTick(duration)}'),
          ]),
          // A native range input: ArcaneSlider's gesture runtime is an inline
          // script, which the editor's CSP does not run.
          dom.input(
            type: dom.InputType.range,
            classes: 'animus-rig-range',
            value: formatTick(time.clamp(0, span).toDouble()),
            attributes: <String, String>{'min': '0', 'max': formatTick(span), 'step': '0.25', 'aria-label': 'Playhead tick'},
            events: <String, void Function(web.Event)>{
              'input': (web.Event event) {
                final double? tick = double.tryParse((event.target! as web.HTMLInputElement).value);
                if (tick != null) component.onScrub(tick);
              },
            },
          ),
        ]),
        for (int index = 0; index < tracks.length; index++) _lane(tracks, index, span, time),
        if (tracks.isEmpty)
          const dom.p(classes: 'animus-muted', <Widget>[Component.text('No tracks yet. Select a bone and add a keyframe.')]),
        _eventLane(events, span, time),
      ]),
      dom.div(classes: 'animus-rig-toolbar animus-rig-keybar', <Widget>[
        dom.label(classes: 'animus-rig-field is-inline', <Widget>[
          const dom.span(<Widget>[Component.text('Channel')]),
          GuiSelect(
            value: component.keyTarget.name,
            options: <String>[for (final TrackTarget target in TrackTarget.values) target.name],
            hiddenLabel: 'Channel',
            onChanged: (String? value) {
              if (value != null) component.onKeyTarget(TrackTarget.values.byName(value));
            },
          ),
        ]),
        dom.label(classes: 'animus-rig-field is-inline', <Widget>[
          const dom.span(<Widget>[Component.text('New track blend')]),
          GuiSelect(
            value: component.keyTarget == TrackTarget.visible ? 'replace' : component.keyBlend.name,
            inactive: component.keyTarget == TrackTarget.visible,
            options: <String>[for (final Blend blend in Blend.values) blend.name],
            hiddenLabel: 'New track blend',
            onChanged: (String? value) {
              if (value != null) component.onKeyBlend(Blend.values.byName(value));
            },
          ),
        ]),
        Button.secondary(
          label: 'Add keyframe',
          icon: ArcaneIcon.plus(),
          size: ButtonSize.small,
          disabled: component.selectedBone == null,
          onPressed: component.onAddKeyframe,
        ),
        Button.ghost(label: 'New track', size: ButtonSize.small, disabled: component.selectedBone == null, onPressed: component.onNewTrack),
        Button.ghost(
          label: 'Add event',
          size: ButtonSize.small,
          disabled: events.length >= maximumEvents,
          onPressed: () {
            final int tick = time.round().clamp(0, duration.round());
            component.onEdit((JsonMap json) => addEvent(json, tick, 'animus:play-sound', starterArguments('animus:play-sound')));
            component.onSelectEvent(events.where((JsonMap event) => _number(event['tick']) <= tick).length);
          },
        ),
        dom.span(classes: 'animus-muted', <Widget>[
          Component.text(component.selectedBone == null ? 'Select a bone to key it.' : 'Keys ${component.selectedBone} at tick ${formatTick(time)}.'),
        ]),
      ]),
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

  dom.Styles _at(double tick, double span) =>
      dom.Styles(raw: <String, String>{'left': '${(tick / span * 100).clamp(0, 100).toStringAsFixed(2)}%'});

  Widget _playhead(double time, double span) => dom.span(classes: 'animus-rig-playhead', styles: _at(time, span), const <Widget>[]);

  void _commitDuration(JsonMap animation, double current) {
    final String? draft = _duration;
    _duration = null;
    final int? parsed = draft == null ? null : int.tryParse(draft.trim());
    if (parsed == null || parsed == current) {
      setState(() {});
      return;
    }
    if (parsed < 1 || parsed > maximumDurationTicks) {
      ArcaneSonner.warning('Durations are 1 to $maximumDurationTicks ticks (60 seconds).');
      setState(() {});
      return;
    }
    final ({int keyframes, int events}) beyond = pastEnd(animation, parsed);
    if (beyond.keyframes + beyond.events > 0) {
      setState(() => _trim = parsed);
      return;
    }
    setState(() => _trim = null);
    component.onEdit((JsonMap json) => setDuration(json, parsed));
  }

  Widget _lane(List<JsonMap> tracks, int index, double span, double time) {
    final JsonMap track = tracks[index];
    final List<JsonMap> keys = keyframesOf(track);
    final String blend = track['blend'] is String ? track['blend']! as String : 'replace';
    final bool visible = track['target'] == 'visible';
    final String name = '${track['bone']} ${track['target']}';
    final bool selected = component.track == index;
    return dom.div(
      classes: 'animus-rig-lane${track['bone'] == component.selectedBone ? ' is-bone' : ''}${selected ? ' is-selected' : ''}',
      attributes: <String, String>{'data-track': '$index'},
      <Widget>[
        dom.button(
          classes: 'animus-rig-lane-label',
          attributes: <String, String>{'type': 'button', 'aria-pressed': '$selected', 'title': '$name, $blend'},
          events: <String, void Function(web.Event)>{'click': (web.Event _) => component.onSelectTrack(selected ? null : index)},
          <Widget>[
            dom.span(<Widget>[Component.text(name)]),
            if (blend != 'replace') dom.span(classes: 'animus-rig-blend', <Widget>[Component.text(blend)]),
          ],
        ),
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
    final String label = '${track['bone']} ${track['target']} keyframe at tick ${formatTick(tick)}${track['target'] == 'visible' ? (hidden ? ', hidden' : ', shown') : ''}';
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
        ArcaneSonner.warning('Another keyframe is at tick ${formatTick(to)}. Drop it on a free tick.');
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
      const dom.span(classes: 'animus-rig-lane-label', <Widget>[Component.text('events')]),
      dom.div(classes: 'animus-rig-lane-keys', <Widget>[
        _playhead(time, span),
        for (int index = 0; index < events.length; index++)
          dom.button(
            classes: 'animus-rig-event${component.event == index ? ' is-selected' : ''}',
            attributes: <String, String>{
              'type': 'button',
              'data-event': '$index',
              'aria-pressed': '${component.event == index}',
              'aria-label': '${eventSummary(events[index])} at tick ${formatTick(_number(events[index]['tick']))}',
              'title': '${eventSummary(events[index])} at tick ${formatTick(_number(events[index]['tick']))}',
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
