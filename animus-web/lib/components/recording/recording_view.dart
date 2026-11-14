/// A recording: a top-down drawing of the recorded steps with one playback
/// row under it (Play, the scrub, the time and a preview speed), the trim
/// row, the events, and the details form beside it. Facts that need the
/// engine's words (frame gap, start point, pose changes, version) show in
/// Advanced. Frames are made in game with `/animus record` and stay as
/// recorded; only the start and end can be trimmed here.
library;

import 'dart:async';
import 'dart:math' as math;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/gui/gui_json.dart';
import '../../logic/recording/recording_edit.dart';
import '../../logic/recording/recording_facts.dart';
import '../../logic/recording/recording_model.dart';
import '../../model/animus_document.dart';
import '../../ui/action_row.dart';
import '../../ui/empty_state.dart';
import '../../ui/icon_button.dart';
import '../../ui/info_tip.dart';
import '../../ui/mode.dart';
import '../../ui/stage_frame.dart';
import '../../ui/toggle_choice.dart';
import '../gui/gui_fields.dart' show JsonEdit, prefersReducedMotion;
import 'recording_details.dart';
import '../npc/npc_layout.dart';
import '../../ui/field.dart';
import '../../ui/problem_text.dart';

/// The id of the scrub slider (its `data-arcane-slider`).
const String recordingScrubId = 'animus-recording-scrub';

/// The id of the Play and Pause button.
const String recordingPlayId = 'animus-recording-play';

/// The id of the drawing.
const String recordingPathId = 'animus-recording-path';

/// Preview speeds, against the recording's own speed.
const List<ChoiceOption<double>> _rates = <ChoiceOption<double>>[
  ChoiceOption<double>(0.5, '0.5x'),
  ChoiceOption<double>(1, '1x'),
  ChoiceOption<double>(2, '2x'),
];

class RecordingView extends StatefulWidget {
  const RecordingView({required this.document, this.onJson, this.onMakeRoute, this.knownTags = const <String>[], super.key});

  final AnimusDocument document;

  /// Edits the details and trims; null shows the recording read-only.
  final JsonEdit? onJson;

  /// Makes a route under [id] that follows the recording; returns a problem to show, or null.
  final Future<String?> Function(String id)? onMakeRoute;

  /// Tags other recordings use.
  final List<String> knownTags;

  @override
  State<RecordingView> createState() => _RecordingViewState();
}

class _RecordingViewState extends State<RecordingView> {
  double _tick = 0;
  bool _playing = false;
  double _rate = 1;
  Timer? _timer;

  /// The trim range picked on the scrub, as frame ticks; null keeps that end.
  int? _trimFrom;
  int? _trimTo;

  /// The last trim preview and what it was computed from, so playing does
  /// not trim the document again on every frame.
  (Object?, int, int)? _trimmedFrom;
  RecordingTrim? _trimmed;

  RecordingTrim _preview(int from, int to) {
    final Object? json = component.document.json;
    final (Object?, int, int)? key = _trimmedFrom;
    if (key == null || !identical(key.$1, json) || key.$2 != from || key.$3 != to || _trimmed == null) {
      _trimmedFrom = (json, from, to);
      _trimmed = trimRecording(asMap(json), from, to);
    }
    return _trimmed!;
  }

  /// The parsed recording, kept while the document's JSON is the same
  /// object, so playing at 20 frames a second does not parse it again.
  Object? _parsedFrom;
  Recording? _parsed;

  Recording? get _recording {
    final Object? json = component.document.json;
    if (!identical(json, _parsedFrom)) {
      _parsedFrom = json;
      _parsed = Recording.parse(json);
    }
    return _parsed;
  }

  /// The facts, worked out once per document source: the summary measures
  /// the file, which playing at 20 frames a second must not repeat.
  String? _factsFrom;
  List<RecordingFact> _factsCache = const <RecordingFact>[];

  List<RecordingFact> _factsOf(Recording recording) {
    final String source = component.document.source;
    if (!identical(source, _factsFrom)) {
      _factsFrom = source;
      _factsCache = recordingFacts(recording, RecordingSummary.of(recording, source));
    }
    return _factsCache;
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  void _stop() {
    _timer?.cancel();
    _timer = null;
    _playing = false;
  }

  /// Plays from the scrub at the recording's speed times the preview speed,
  /// as the runtime plays it, and stops at the last frame. It starts paused,
  /// so nothing moves until Play.
  void _toggle(Recording recording) {
    if (_playing) {
      setState(_stop);
      return;
    }
    setState(() {
      if (_tick >= recording.durationTicks) _tick = 0;
      _playing = true;
    });
    double last = web.window.performance.now();
    _timer = Timer.periodic(const Duration(milliseconds: 1000 ~/ recordingTicksPerSecond), (Timer timer) {
      if (!mounted) {
        timer.cancel();
        return;
      }
      final double now = web.window.performance.now();
      final double elapsed = now - last;
      last = now;
      setState(() {
        _tick = advanceRecordingTick(_tick, elapsed, RecordingDetails.read(asMap(component.document.json)).speed * _rate, recording.durationTicks);
        if (_tick >= recording.durationTicks) _stop();
      });
    });
  }

  void _seek(double tick) => setState(() {
        _stop();
        _tick = tick;
      });

  @override
  Widget build(BuildContext context) {
    final Recording? recording = _recording;
    if (recording == null) {
      return const EmptyState(message: 'Not a JSON object, so there is nothing to draw. Fix it in Code.');
    }
    final double tick = _tick.clamp(0, math.max(0, recording.durationTicks)).toDouble();
    final List<RecordingFact> facts = _factsOf(recording);
    final bool editable = component.onJson != null;
    return dom.div(classes: 'animus-recording', attributes: const <String, String>{'role': 'group', 'aria-label': 'Recording'}, <Widget>[
      dom.div(classes: 'animus-recording-body', <Widget>[
        dom.div(classes: 'animus-recording-preview', styles: stackStyles(), <Widget>[
          StageFrame(
            title: 'Preview',
            free: true,
            fullscreen: false,
            toggles: <Widget>[ToggleChoice<double>(label: 'Preview speed', options: _rates, value: _rate, onChanged: (double rate) => setState(() => _rate = rate))],
            status: <String>[for (final RecordingFact fact in facts) if (!fact.advanced) fact.value].join(' · '),
            statusTip: 'Top down, north up. The square marks the start, small squares mark events, and the dot shows the scrub and which way the recorder faced.',
            child: dom.div(classes: 'animus-recording-stage', styles: _stageStyles, <Widget>[
              _drawing(recording, tick),
              _playback(recording, tick),
              if (editable) ..._trim(recording, tick),
              if (!_playing && prefersReducedMotion()) mutedLine('Reduced motion is on. Press Play or move the scrub.'),
            ]),
          ),
          if (recording.events.isNotEmpty)
            if (eventProblems(component.document.diagnostics) case final Map<int, List<String>> problems)
              MoreFields(label: 'Events (${recording.events.length})', open: problems.isNotEmpty, children: <Widget>[_events(recording, tick, problems)]),
        ]),
        dom.div(classes: 'animus-recording-form', styles: stackStyles(), <Widget>[
          if (component.onJson case final JsonEdit onJson)
            RecordingDetailsForm(document: component.document, onJson: onJson, onMakeRoute: component.onMakeRoute, knownTags: component.knownTags),
          AdvancedOnly(child: MoreFields(label: 'Facts', open: !editable, children: <Widget>[_facts(facts)])),
        ]),
      ]),
    ]);
  }

  Widget _facts(List<RecordingFact> facts) => dom.div(
        attributes: const <String, String>{'data-recording': 'facts'},
        <Widget>[
          for (final RecordingFact fact in facts) Field(label: fact.label, tip: fact.tip, child: Text(fact.value, cssStyle: const ArcaneStyleData(wordBreak: WordBreak.breakWord))),
        ],
      );

  /// The drawing and its controls, inset from the frame.
  static final dom.Styles _stageStyles = const ArcaneStyleData(display: Display.flex, flexDirection: FlexDirection.column, gap: Gap.sm, paddingStringCustom: '0.75rem').toStyles();

  /// The scrub takes what the playback row leaves, at the row's height.
  static const ArcaneStyleData _scrubStyles = ArcaneStyleData(
    flexCustom: '1 1 6rem',
    minWidth: '3rem',
    alignSelf: CrossAxisAlignment.stretch,
    display: Display.flex,
    alignItems: AlignItems.center,
  );

  /// An event the scrub has not reached yet.
  static const dom.Styles _upcomingStyles = dom.Styles(raw: <String, String>{'color': 'var(--muted-foreground)'});

  /// A time readout: one line, figures that do not jump as they change.
  static const dom.Styles _readoutStyles = dom.Styles(raw: <String, String>{
    'flex': 'none',
    'white-space': 'nowrap',
    'font-variant-numeric': 'tabular-nums',
    'font-size': '0.875rem',
    'color': 'var(--muted-foreground)',
  });

  static String _f(double value) => value.toStringAsFixed(2);

  Widget _drawing(Recording recording, double tick) {
    if (recording.frames.isEmpty) {
      return const EmptyState(message: 'No readable frames to draw.');
    }
    final (double minX, double minZ, double maxX, double maxZ) = recording.bounds();
    final double size = math.max(8, math.max(maxX - minX, maxZ - minZ) + 4);
    final double left = (minX + maxX) / 2 - size / 2, top = (minZ + maxZ) / 2 - size / 2;
    final double radius = size / 60;
    final RecordingOrigin origin = recording.origin;
    final RecordingFrame first = recording.frames.first;
    final RecordingPose pose = recording.poseAt(tick);
    final double yaw = pose.yaw * math.pi / 180;
    // Minecraft yaw 0 faces +z and 90 faces -x.
    final double headX = pose.x - math.sin(yaw) * radius * 4, headZ = pose.z + math.cos(yaw) * radius * 4;
    final String points = <String>[for (final RecordingFrame frame in recording.frames) '${_f(origin.x + frame.dx)},${_f(origin.z + frame.dz)}'].join(' ');
    final (int from, int to) = _range(recording);
    final bool trimming = from != 0 || to != recording.frames.last.tick;
    final String kept = <String>[
      for (final RecordingFrame frame in recording.frames)
        if (frame.tick >= from && frame.tick <= to) '${_f(origin.x + frame.dx)},${_f(origin.z + frame.dz)}',
    ].join(' ');
    return dom.svg(
      <Component>[
        dom.polyline(const <Component>[], points: points, classes: 'animus-recording-line${trimming ? ' is-cut' : ''}'),
        if (trimming) dom.polyline(const <Component>[], points: kept, classes: 'animus-recording-line is-kept'),
        for (final RecordingEvent event in recording.events)
          if (recording.poseAt(event.tick) case final RecordingPose at)
            dom.rect(
              const <Component>[],
              x: _f(at.x - radius * 0.7),
              y: _f(at.z - radius * 0.7),
              width: _f(radius * 1.4),
              height: _f(radius * 1.4),
              classes: 'animus-recording-event',
              attributes: <String, String>{'data-event': event.type},
            ),
        dom.rect(const <Component>[], x: _f(origin.x + first.dx - radius), y: _f(origin.z + first.dz - radius), width: _f(radius * 2), height: _f(radius * 2), classes: 'animus-recording-start'),
        dom.line(const <Component>[], x1: _f(pose.x), y1: _f(pose.z), x2: _f(headX), y2: _f(headZ), classes: 'animus-recording-heading'),
        dom.circle(const <Component>[], cx: _f(pose.x), cy: _f(pose.z), r: _f(radius * 1.4), classes: 'animus-recording-marker'),
      ],
      id: recordingPathId,
      classes: 'animus-recording-path',
      viewBox: '${_f(left)} ${_f(top)} ${_f(size)} ${_f(size)}',
      attributes: <String, String>{
        'role': 'img',
        'aria-label': 'Recorded steps, top down, north up: ${recording.frames.length} frames; the dot is at ${clockText(tick)}',
        'data-marker-x': _f(pose.x),
        'data-marker-z': _f(pose.z),
      },
    );
  }

  /// Play, the scrub and where playback is, on one row; the preview speed
  /// sits in the frame's header.
  Widget _playback(Recording recording, double tick) {
    final int duration = recording.durationTicks;
    final String status = playbackStatus(tick, duration);
    final List<String> flags = <String>[for (final String flag in recordingFlagNames(recording.poseAt(tick).flags)) if (flag != 'on ground') flag];
    return ActionRow(
      dense: true,
      label: 'Playback',
      leading: <Widget>[
        AppIconButton(
          id: recordingPlayId,
          icon: _playing ? ArcaneIcon.pause() : ArcaneIcon.play(),
          label: _playing ? 'Pause' : 'Play',
          pressed: _playing,
          disabled: duration <= 0,
          onPressed: () => _toggle(recording),
        ),
        ArcaneSlider(
          id: recordingScrubId,
          value: tick.roundToDouble(),
          max: math.max(1, duration).toDouble(),
          step: 1,
          showValue: false,
          showBounds: false,
          disabled: duration <= 0,
          ariaLabel: 'Scrub',
          ariaValueText: '$status${flags.isEmpty ? '' : ', ${flags.join(', ')}'}',
          styles: _scrubStyles,
          onChanged: _seek,
        ),
        dom.span(styles: _readoutStyles, attributes: const <String, String>{'data-recording': 'readout'}, <Widget>[Component.text(status)]),
      ],
    );
  }

  /// The kept range: the picked ends, else the first and last frame.
  (int, int) _range(Recording recording) {
    final int last = recording.frames.isEmpty ? 0 : recording.frames.last.tick;
    return (_trimFrom ?? 0, _trimTo ?? last);
  }

  void _apply(int from, int to) {
    final JsonEdit onJson = component.onJson!;
    onJson((Object? latest) => latest is Map<String, Object?> ? trimRecording(latest, from, to).json ?? latest : latest);
    setState(() {
      _stop();
      _tick = 0;
      _trimFrom = null;
      _trimTo = null;
    });
    ArcaneSonner.success('Trimmed to ${lengthText(to - from)}. Undo brings the cut part back.');
  }

  /// Start and end picked from the scrub, snapped to frames, then one
  /// undoable trim that drops the frames and events outside them.
  List<Widget> _trim(Recording recording, double tick) {
    final List<int> ticks = <int>[for (final RecordingFrame frame in recording.frames) frame.tick];
    if (ticks.length < 2) return const <Widget>[];
    final (int from, int to) = _range(recording);
    final bool changed = from != 0 || to != ticks.last;
    final RecordingTrim? result = changed ? _preview(from, to) : null;
    final int frames = ticks.where((int frameTick) => frameTick >= from && frameTick <= to).length;
    final int at = snapRecordingTick(ticks, tick);
    return <Widget>[
      // The range on its own line, so the buttons keep their text and one
      // height at every width.
      dom.span(
        styles: _readoutStyles,
        attributes: const <String, String>{'data-recording': 'trim-range', 'aria-live': 'polite'},
        <Widget>[
          TipLabel(
            label: Component.text('Keep ${clockText(from.toDouble())} to ${clockText(to.toDouble())}'),
            tip: 'Move the scrub, press Set start and Set end, then Trim. It keeps $frames of ${ticks.length} frames; Undo brings back what is cut.',
          ),
        ],
      ),
      ActionRow(
        dense: true,
        label: 'Trim',
        secondary: <RowAction>[
          RowAction(
            'Set start',
            at >= to ? null : () => setState(() => _trimFrom = at),
            icon: ArcaneIcon.arrowRightToLine(),
            id: 'animus-recording-trim-start',
          ),
          RowAction(
            'Set end',
            at <= from ? null : () => setState(() => _trimTo = at),
            icon: ArcaneIcon.arrowLeftToLine(),
            id: 'animus-recording-trim-end',
          ),
        ],
        cancel: changed
            ? RowAction('Reset', () => setState(() {
                  _trimFrom = null;
                  _trimTo = null;
                }), id: 'animus-recording-trim-reset')
            : null,
        primary: RowAction('Trim', result?.json == null ? null : () => _apply(from, to), id: 'animus-recording-trim-apply'),
      ),
      if (result?.problem case final String problem)
        problemLine(DiagnosticSeverity.error, problem, role: 'alert', attributes: const <String, String>{'data-recording': 'trim-problem'}),
    ];
  }

  /// The events at their times, each with its own [problems] (keyed by its
  /// place in the document's `events`, which may hold unreadable ones).
  Widget _events(Recording recording, double tick, Map<int, List<String>> problems) {
    final List<Object?> raw = asList(asMap(component.document.json)['events']);
    final Map<Object?, int> placeOf = Map<Object?, int>.identity()..addAll(<Object?, int>{for (int at = 0; at < raw.length; at++) raw[at]: at});
    return dom.ol(
      styles: plainListStyles,
      attributes: const <String, String>{'aria-label': 'Recorded events', 'data-recording': 'events'},
      <Widget>[
        for (int index = 0; index < recording.events.length; index++)
          if (recording.events[index] case final RecordingEvent event)
            dom.li(styles: event.tick <= tick ? null : _upcomingStyles, <Widget>[
              Button(
                label: clockText(event.tick.toDouble()),
                variant: ButtonVariant.link,
                size: ButtonSize.sm,
                attributes: <String, String>{'aria-label': 'Go to ${clockText(event.tick.toDouble())}: ${eventText(event)}'},
                onPressed: () => _seek(event.tick.toDouble()),
              ),
              dom.span(<Widget>[Component.text(eventText(event))]),
              for (final String problem in problems[placeOf[event.json] ?? index] ?? const <String>[])
                dom.span(attributes: const <String, String>{'data-recording': 'event-problem'}, <Widget>[problemText(' $problem')]),
            ]),
      ],
    );
  }
}
