/// A recording's Visual and Preview views. Recordings are made in game with
/// `/animus record`, so both are read-only: the facts, a top-down path of
/// the recorded positions with a scrub and Play, and the events in order.
library;

import 'dart:async';
import 'dart:math' as math;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/recording/recording_model.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart' show prefersReducedMotion;

/// The id of the scrub input.
const String recordingScrubId = 'animus-recording-scrub';

/// The id of the Play and Pause button.
const String recordingPlayId = 'animus-recording-play';

/// The id of the path drawing.
const String recordingPathId = 'animus-recording-path';

class RecordingView extends StatefulWidget {
  const RecordingView({required this.document, this.facts = true, super.key});

  final AnimusDocument document;

  /// Visual shows the facts and events around the path; Preview the path alone.
  final bool facts;

  @override
  State<RecordingView> createState() => _RecordingViewState();
}

class _RecordingViewState extends State<RecordingView> {
  double _tick = 0;
  bool _playing = false;
  Timer? _timer;

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

  /// Plays from the scrub position at one recording tick per server tick, as
  /// the runtime plays it, and stops at the last frame. It always starts
  /// paused, so nothing moves until the reader presses Play.
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
        _tick = math.min(_tick + 1, recording.durationTicks.toDouble());
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
        if (component.facts) _facts(recording),
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
    final String events = summary.events == 0
        ? 'none'
        : '${summary.events}: ${summary.eventsByType.entries.map((MapEntry<String, int> entry) => '${entry.value} ${entry.key}').join(', ')}';
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
    return dom.div(classes: 'animus-recording-facts', <Widget>[
      dom.dl(attributes: const <String, String>{'data-recording': 'facts'}, <Widget>[
        for (final (String label, String value) in facts) ...<Widget>[
          dom.dt(<Widget>[Component.text(label)]),
          dom.dd(<Widget>[Component.text(value)]),
        ],
      ]),
    ]);
  }

  static String _f(double value) => value.toStringAsFixed(2);

  Widget _path(Recording recording, double tick) {
    if (recording.frames.isEmpty) {
      return const dom.p(classes: 'animus-muted', attributes: <String, String>{'data-recording': 'empty'}, <Widget>[
        Component.text('No readable frames to draw.'),
      ]);
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
    final String points = <String>[
      for (final RecordingFrame frame in recording.frames) '${_f(origin.x + frame.dx)},${_f(origin.z + frame.dz)}',
    ].join(' ');
    final int duration = recording.durationTicks;
    final String seconds = (tick / recordingTicksPerSecond).toStringAsFixed(1);
    final List<String> flags = recordingFlagNames(pose.flags);
    return dom.div(classes: 'animus-recording-stage', <Widget>[
      dom.svg(
        <Component>[
          dom.polyline(const <Component>[], points: points, classes: 'animus-recording-line'),
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
          dom.rect(
            const <Component>[],
            x: _f(origin.x + first.dx - radius),
            y: _f(origin.z + first.dz - radius),
            width: _f(radius * 2),
            height: _f(radius * 2),
            classes: 'animus-recording-start',
          ),
          dom.line(const <Component>[], x1: _f(pose.x), y1: _f(pose.z), x2: _f(headX), y2: _f(headZ), classes: 'animus-recording-heading'),
          dom.circle(const <Component>[], cx: _f(pose.x), cy: _f(pose.z), r: _f(radius * 1.4), classes: 'animus-recording-marker'),
        ],
        id: recordingPathId,
        classes: 'animus-recording-path',
        viewBox: '${_f(left)} ${_f(top)} ${_f(size)} ${_f(size)}',
        attributes: <String, String>{
          'role': 'img',
          'aria-label': 'Recorded path, top down, north up: ${recording.frames.length} frames over x ${minX.toStringAsFixed(1)} to ${maxX.toStringAsFixed(1)}, '
              'z ${minZ.toStringAsFixed(1)} to ${maxZ.toStringAsFixed(1)}; the marker is at tick ${tick.round()}',
          'data-marker-x': _f(pose.x),
          'data-marker-z': _f(pose.z),
        },
      ),
      const dom.p(classes: 'animus-recording-legend animus-muted', <Widget>[
        Component.text('North up. Square: first frame. Small squares: events. Pink dot: scrub position and facing.'),
      ]),
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
          attributes: <String, String>{
            'min': '0',
            'max': '$duration',
            'step': '1',
            'aria-valuetext': 'tick ${tick.round()} of $duration, $seconds seconds',
          },
          events: <String, void Function(web.Event)>{
            'input': (web.Event event) {
              final double? value = double.tryParse((event.target! as web.HTMLInputElement).value);
              if (value != null) _seek(value);
            },
          },
        ),
        dom.span(classes: 'animus-recording-readout', attributes: const <String, String>{'data-recording': 'readout'}, <Widget>[
          Component.text('tick ${tick.round()} / $duration, $seconds s${flags.isEmpty ? '' : ', ${flags.join(', ')}'}'),
        ]),
      ]),
      if (!_playing && prefersReducedMotion())
        const dom.p(classes: 'animus-muted', <Widget>[Component.text('Reduced motion is on. Press Play or drag the scrub.')]),
    ]);
  }

  Widget _events(Recording recording, double tick) {
    return dom.div(classes: 'animus-recording-events', <Widget>[
      const dom.h2(<Widget>[Component.text('Events')]),
      if (recording.events.isEmpty)
        const dom.p(classes: 'animus-muted', <Widget>[Component.text('No events recorded.')])
      else
        dom.ol(attributes: const <String, String>{'aria-label': 'Recorded events', 'data-recording': 'events'}, <Widget>[
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
        ]),
    ]);
  }
}
