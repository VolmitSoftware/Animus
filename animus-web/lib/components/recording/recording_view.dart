/// A recording's Visual and Preview views: the facts, a top-down path of the
/// recorded positions with a scrub and Play, and the events in order. With
/// [RecordingView.onJson], Visual also edits the details and trims the start
/// and end to frames picked on the scrub; the frames themselves are made in
/// game with `/animus record` and stay as recorded.
library;

import 'dart:async';
import 'dart:math' as math;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/recording/recording_edit.dart';
import '../../logic/recording/recording_model.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart' show JsonEdit, prefersReducedMotion;
import '../info_tip.dart';
import 'recording_details.dart';

/// The id of the scrub input.
const String recordingScrubId = 'animus-recording-scrub';

/// The id of the Play and Pause button.
const String recordingPlayId = 'animus-recording-play';

/// The id of the path drawing.
const String recordingPathId = 'animus-recording-path';

class RecordingView extends StatefulWidget {
  const RecordingView({required this.document, this.facts = true, this.onJson, this.onRename, this.onMakeRoute, this.knownTags = const <String>[], super.key});

  final AnimusDocument document;

  /// Visual shows the facts and events around the path; Preview the path alone.
  final bool facts;

  /// Edits the details and trims; null shows the recording read-only.
  final JsonEdit? onJson;

  /// Moves the recording to a new id; null hides the id field.
  final Future<String?> Function(String id)? onRename;

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

  /// Plays from the scrub position at the recording's speed in recorded ticks
  /// per server tick, as the runtime plays it, and stops at the last frame.
  /// It always starts paused, so nothing moves until the reader presses Play.
  void _toggle(Recording recording) {
    if (_playing) {
      setState(_stop);
      return;
    }
    setState(() {
      if (_tick >= recording.durationTicks) _tick = 0;
      _playing = true;
    });
    _timer = Timer.periodic(const Duration(milliseconds: 1000 ~/ recordingTicksPerSecond), (Timer timer) {
      if (!mounted) {
        timer.cancel();
        return;
      }
      setState(() {
        _tick = math.min(_tick + RecordingDetails.read(asMap(component.document.json)).speed, recording.durationTicks.toDouble());
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
      return const dom.section(classes: 'animus-panel animus-recording', <Widget>[
        dom.p(<Widget>[Component.text('Not a JSON object, so there is no path to draw.')]),
      ]);
    }
    final double tick = _tick.clamp(0, math.max(0, recording.durationTicks)).toDouble();
    return dom.section(
      classes: 'animus-panel animus-recording${component.facts ? '' : ' is-preview'}',
      attributes: const <String, String>{'aria-label': 'Recording'},
      <Widget>[
        if (component.facts)
          dom.div(classes: 'animus-recording-facts', <Widget>[
            if (component.onJson case final JsonEdit onJson) RecordingDetailsForm(
                document: component.document,
                onJson: onJson,
                onRename: component.onRename,
                onMakeRoute: component.onMakeRoute,
                knownTags: component.knownTags,
              ),
            _facts(recording),
          ]),
        _path(recording, tick),
        if (component.facts) _events(recording, tick),
      ],
    );
  }

  static String _n(double value) => value == value.roundToDouble() ? '${value.toInt()}' : '$value';

  Widget _facts(Recording recording) {
    final RecordingSummary summary = RecordingSummary.of(recording, component.document.source);
    final RecordingOrigin origin = recording.origin;
    final List<RecordingFrame> changes = recording.flagChanges();
    final String events = summary.events == 0 ? 'none' : '${summary.events}: ${summary.eventsByType.entries.map((MapEntry<String, int> entry) => '${entry.value} ${entry.key}').join(', ')}';
    final List<(String, String)> facts = <(String, String)>[
      ('Length', formatRecordingLength(summary.durationTicks)),
      ('Frames', '${summary.frames}'),
      ('Events', events),
      ('World', recording.world ?? '-'),
      ('Origin', '${_n(origin.x)}, ${_n(origin.y)}, ${_n(origin.z)}, yaw ${_n(origin.yaw)}, pitch ${_n(origin.pitch)}'),
      ('Sample ticks', recording.sampleTicks == null ? '-' : '${recording.sampleTicks}'),
      ('Recorded by', recording.recordedBy ?? '-'),
      ('Size', formatRecordingSize(summary.bytes)),
      ('Revision', recording.revision == null ? '-' : '${recording.revision}'),
      ('Flag changes', changes.isEmpty ? 'none' : '${changes.length}, at ticks ${changes.take(12).map((RecordingFrame frame) => frame.tick).join(', ')}${changes.length > 12 ? ', ...' : ''}'),
    ];
    return dom.dl(
      attributes: const <String, String>{'data-recording': 'facts'},
      <Widget>[
        for (final (String label, String value) in facts) ...<Widget>[
          dom.dt(<Widget>[withTip(Component.text(label), _factTips[label], docs: Docs.recordings)]),
          dom.dd(<Widget>[Component.text(value)]),
        ],
      ],
    );
  }

  /// Tips on the facts that need one.
  static const Map<String, String> _factTips = <String, String>{
    'Origin': 'Where the take started. Each frame stores its position as an offset from here.',
    'Sample ticks': 'Ticks between two captured frames, 1 to 20.',
    'Flag changes': 'Frames where on ground, sneaking, sprinting, swimming, gliding or jumping turns on or off.',
    'Revision': 'A number raised on each change, 1 or more.',
  };

  static String _f(double value) => value.toStringAsFixed(2);

  Widget _path(Recording recording, double tick) {
    if (recording.frames.isEmpty) {
      return const dom.p(classes: 'animus-empty-line', attributes: <String, String>{'data-recording': 'empty'}, <Widget>[Component.text('No readable frames to draw.')]);
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
    final int duration = recording.durationTicks;
    final (int from, int to) = _range(recording);
    final bool trimming = from != 0 || to != recording.frames.last.tick;
    final String kept = <String>[
      for (final RecordingFrame frame in recording.frames)
        if (frame.tick >= from && frame.tick <= to) '${_f(origin.x + frame.dx)},${_f(origin.z + frame.dz)}',
    ].join(' ');
    final String seconds = (tick / recordingTicksPerSecond).toStringAsFixed(1);
    final List<String> flags = recordingFlagNames(pose.flags);
    return dom.div(classes: 'animus-recording-stage', <Widget>[
      dom.svg(
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
          'aria-label':
              'Recorded path, top down, north up: ${recording.frames.length} frames over x ${minX.toStringAsFixed(1)} to ${maxX.toStringAsFixed(1)}, '
              'z ${minZ.toStringAsFixed(1)} to ${maxZ.toStringAsFixed(1)}; the marker is at tick ${tick.round()}',
          'data-marker-x': _f(pose.x),
          'data-marker-z': _f(pose.z),
        },
      ),
      const dom.p(classes: 'animus-recording-legend animus-muted', <Widget>[Component.text('North up. Square: first frame. Small squares: events. Pink dot: scrub position and facing.')]),
      dom.div(classes: 'animus-recording-scrub', <Widget>[
        Button.secondary(
          id: recordingPlayId,
          label: _playing ? 'Pause' : 'Play',
          size: ButtonSize.small,
          disabled: duration <= 0,
          attributes: <String, String>{'aria-pressed': '$_playing'},
          onPressed: () => _toggle(recording),
        ),
        const dom.label(classes: 'animus-sr-only', attributes: <String, String>{'for': recordingScrubId}, <Widget>[Component.text('Scrub tick')]),
        dom.input(
          id: recordingScrubId,
          type: dom.InputType.range,
          classes: 'animus-recording-range',
          value: '${tick.round()}',
          attributes: <String, String>{'min': '0', 'max': '$duration', 'step': '1', 'aria-valuetext': 'tick ${tick.round()} of $duration, $seconds seconds'},
          events: <String, void Function(web.Event)>{
            'input': (web.Event event) {
              final double? value = double.tryParse((event.target! as web.HTMLInputElement).value);
              if (value != null) _seek(value);
            },
          },
        ),
        dom.span(
          classes: 'animus-recording-readout',
          attributes: const <String, String>{'data-recording': 'readout'},
          <Widget>[Component.text('tick ${tick.round()} / $duration, $seconds s${flags.isEmpty ? '' : ', ${flags.join(', ')}'}')],
        ),
      ]),
      if (component.onJson != null && component.facts) _trim(recording, tick),
      if (!_playing && prefersReducedMotion()) const dom.p(classes: 'animus-muted', <Widget>[Component.text('Reduced motion is on. Press Play or drag the scrub.')]),
    ]);
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
    ArcaneSonner.success('Trimmed to ${to - from} ticks. Undo restores the cut frames.');
  }

  /// Start and end picked from the scrub, snapped to frames, then one
  /// undoable trim that drops the frames and events outside them.
  Widget _trim(Recording recording, double tick) {
    final List<int> ticks = <int>[for (final RecordingFrame frame in recording.frames) frame.tick];
    if (ticks.length < 2) return const dom.div(<Widget>[]);
    final (int from, int to) = _range(recording);
    final bool changed = from != 0 || to != ticks.last;
    final RecordingTrim? result = changed ? _preview(from, to) : null;
    final int frames = ticks.where((int frameTick) => frameTick >= from && frameTick <= to).length;
    final int at = snapRecordingTick(ticks, tick);
    final double width = math.max(1, ticks.last).toDouble();
    return dom.div(
      classes: 'animus-recording-trim',
      attributes: const <String, String>{'data-recording': 'trim'},
      <Widget>[
        dom.div(
          classes: 'animus-recording-trim-bar',
          attributes: <String, String>{'aria-hidden': 'true', 'style': '--trim-from: ${(from / width * 100).toStringAsFixed(2)}%; --trim-to: ${(to / width * 100).toStringAsFixed(2)}%'},
          const <Widget>[dom.span(<Widget>[])],
        ),
        dom.div(<Widget>[withTip(const Component.text('Trim range'), 'Pick a start and end on the scrub, then Trim. Frames and events outside are dropped; Undo brings them back.', docs: Docs.recordings)]),
        dom.span(
          classes: 'animus-recording-readout',
          attributes: const <String, String>{'data-recording': 'trim-range', 'aria-live': 'polite'},
          <Widget>[Component.text('Keep tick $from to $to: ${to - from} ticks, $frames of ${ticks.length} frames')],
        ),
        dom.div(classes: 'animus-row animus-recording-trim-actions', <Widget>[
          Button.secondary(
            id: 'animus-recording-trim-start',
            label: 'Start here',
            size: ButtonSize.small,
            disabled: at >= to,
            attributes: <String, String>{'aria-label': 'Start the kept range at frame tick $at'},
            onPressed: () => setState(() => _trimFrom = at),
          ),
          Button.secondary(
            id: 'animus-recording-trim-end',
            label: 'End here',
            size: ButtonSize.small,
            disabled: at <= from,
            attributes: <String, String>{'aria-label': 'End the kept range at frame tick $at'},
            onPressed: () => setState(() => _trimTo = at),
          ),
          Button.primary(id: 'animus-recording-trim-apply', label: 'Trim', size: ButtonSize.small, disabled: result?.json == null, onPressed: () => _apply(from, to)),
          if (changed)
            Button.ghost(
              id: 'animus-recording-trim-reset',
              label: 'Reset',
              size: ButtonSize.small,
              onPressed: () => setState(() {
                _trimFrom = null;
                _trimTo = null;
              }),
            ),
        ]),
        if (result?.problem case final String problem)
          dom.p(classes: 'animus-error', attributes: const <String, String>{'role': 'alert', 'data-recording': 'trim-problem'}, <Widget>[Component.text(problem)]),
      ],
    );
  }

  Widget _events(Recording recording, double tick) {
    return dom.div(classes: 'animus-recording-events', <Widget>[
      dom.h2(<Widget>[withTip(const Component.text('Events'), 'Swings, equipment changes and item use during the take. Click a tick to scrub there.', docs: Docs.recordings)]),
      if (recording.events.isEmpty)
        const dom.p(classes: 'animus-empty-line', <Widget>[Component.text('No events recorded.')])
      else
        dom.ol(
          attributes: const <String, String>{'aria-label': 'Recorded events', 'data-recording': 'events'},
          <Widget>[
            for (final RecordingEvent event in recording.events)
              dom.li(classes: event.tick <= tick ? 'is-past' : null, <Widget>[
                Button.ghost(
                  label: 'Tick ${event.tick}',
                  size: ButtonSize.small,
                  attributes: <String, String>{'aria-label': 'Scrub to tick ${event.tick}: ${event.describe()}'},
                  onPressed: () => _seek(event.tick.toDouble()),
                ),
                dom.span(classes: 'animus-tag', <Widget>[Component.text(event.type)]),
                dom.span(classes: 'animus-recording-event-text', <Widget>[Component.text(event.describe())]),
              ]),
          ],
        ),
    ]);
  }
}
