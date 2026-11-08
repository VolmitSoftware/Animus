/// The text animation editor: frames with add, remove, reorder and edit,
/// mode, interval and `show`, beside a live preview that cycles the frames
/// the way core picks them, with a pause control. Under reduced motion the
/// preview starts paused and steps by hand.
library;

import 'dart:async';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_expression.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/text_animation.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import 'gui_fields.dart';
import 'presets_editor.dart';

const List<String> textAnimationModes = <String>['ascend', 'descend', 'ascend_descend', 'random'];

const Map<String, String> _modeLabels = <String, String>{
  'ascend': 'ascend (first to last, repeat)',
  'descend': 'descend (last to first, repeat)',
  'ascend_descend': 'ascend_descend (there and back)',
  'random': 'random (scrambled by tick)',
};

/// The frame preview: plays, pauses and steps.
class TextAnimationPreview extends StatefulWidget {
  const TextAnimationPreview({required this.id, required this.document, super.key});

  final String id;
  final JsonMap document;

  @override
  State<TextAnimationPreview> createState() => _TextAnimationPreviewState();
}

class _TextAnimationPreviewState extends State<TextAnimationPreview> {
  Timer? _timer;
  late bool _playing = !prefersReducedMotion();
  int _now = DateTime.now().millisecondsSinceEpoch;

  /// The frame shown while paused.
  int? _held;

  int get _interval {
    final Object? raw = component.document['frameIntervalMs'];
    return raw is num && raw >= 1 ? raw.toInt() : 1000;
  }

  int get _count => asList(component.document['frames']).length;

  @override
  void initState() {
    super.initState();
    _schedule();
  }

  @override
  void didUpdateWidget(TextAnimationPreview oldComponent) {
    super.didUpdateWidget(oldComponent);
    _schedule();
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  void _schedule() {
    _timer?.cancel();
    _timer = null;
    if (!_playing || _count < 2) return;
    final int every = _interval < 50 ? 50 : _interval;
    _timer = Timer.periodic(Duration(milliseconds: every), (Timer _) {
      if (mounted) setState(() => _now = DateTime.now().millisecondsSinceEpoch);
    });
  }

  int get _index {
    if (_count == 0) return 0;
    final int? held = _held;
    if (!_playing && held != null) return held.clamp(0, _count - 1);
    return textAnimationFrameIndex(component.id, component.document, _now);
  }

  void _toggle() {
    setState(() {
      if (_playing) _held = _index;
      _playing = !_playing;
      _now = DateTime.now().millisecondsSinceEpoch;
    });
    _schedule();
  }

  void _step(int by) => setState(() {
        _playing = false;
        _held = (_index + by) % _count;
        _schedule();
      });

  @override
  Widget build(BuildContext context) {
    final JsonList frames = asList(component.document['frames']);
    final bool hidden = component.document.containsKey('show') && !sampleCondition(component.document['show']);
    final int index = _index;
    final Object? frame = frames.isEmpty ? null : frames[index];
    return dom.div(classes: 'animus-gui-anim-preview', <Widget>[
      dom.div(classes: 'animus-gui-anim-stage', attributes: const <String, String>{'aria-live': 'off'}, <Widget>[
        if (frames.isEmpty)
          const dom.p(classes: 'animus-muted', <Widget>[Component.text('No frames yet. Add one below.')])
        else if (hidden)
          const dom.p(classes: 'animus-muted', <Widget>[Component.text('Hidden: show is false for the sample viewer.')])
        else
          GuiStyledText(frame is String ? frame : ''),
      ]),
      dom.div(classes: 'animus-row', <Widget>[
        Button.ghost(
          label: _playing ? 'Pause' : 'Play',
          size: ButtonSize.small,
          disabled: frames.length < 2,
          attributes: <String, String>{'aria-pressed': '${!_playing}', 'data-anim': 'toggle'},
          onPressed: _toggle,
        ),
        GuiIconButton(icon: ArcaneIcon.arrowLeft(size: IconSize.sm), label: 'Previous frame', disabled: frames.length < 2, onPressed: () => _step(-1)),
        GuiIconButton(icon: ArcaneIcon.arrowRight(size: IconSize.sm), label: 'Next frame', disabled: frames.length < 2, onPressed: () => _step(1)),
        dom.span(classes: 'animus-muted', attributes: const <String, String>{'data-anim': 'frame'}, <Widget>[
          Component.text(frames.isEmpty ? 'No frames' : 'Frame ${index + 1} of ${frames.length}${_playing ? ', every $_interval ms' : ', paused'}'),
        ]),
      ]),
      if (!_playing && prefersReducedMotion())
        const dom.p(classes: 'animus-muted', <Widget>[Component.text('Paused for reduced motion.')]),
    ]);
  }
}

class TextAnimationEditor extends StatelessWidget {
  const TextAnimationEditor({required this.document, required this.onJson, this.presets, super.key});

  final AnimusDocument document;
  final JsonEdit onJson;

  /// The workspace's presets document, for the preset fix.
  final JsonMap? presets;

  JsonMap get _doc => asMap(document.json);

  void _write(List<JsonStep> steps, Object? value, {String? coalesce}) => onJson(writeAt(_doc, steps, value), coalesce: coalesce ?? steps.join('.'));

  @override
  Widget build(BuildContext context) {
    final JsonMap doc = _doc;
    final List<Diagnostic> diagnostics = document.diagnostics;
    final List<String> frames = <String>[for (final Object? frame in asList(doc['frames'])) frame is String ? frame : ''];
    final String modeId = guiPathId(r'$.mode');
    final String intervalId = guiPathId(r'$.frameIntervalMs');
    final String showId = guiPathId(r'$.show');
    final Object? show = doc['show'];
    return dom.div(classes: 'animus-gui-editor', <Widget>[
      dom.section(classes: 'animus-panel animus-gui-stage', attributes: const <String, String>{'aria-label': 'Preview'}, <Widget>[
        TextAnimationPreview(id: document.id, document: doc),
        dom.p(classes: 'animus-muted', <Widget>[
          Component.text('Use |animation.${document.id.substring(document.id.indexOf(':') + 1)}| in hologram or menu text. Not in chest GUIs.'),
        ]),
      ]),
      dom.section(classes: 'animus-panel animus-gui-inspector', <Widget>[
        PresetFix(document: document, presets: presets, kind: DocumentKind.textAnimation, onJson: onJson),
        GuiSection(title: 'Frames', count: frames.length, tip: 'The texts shown in turn, one per line. A frame cannot show another animation.', docs: Docs.textAnimations, children: <Widget>[
          if (frames.isEmpty) const dom.p(classes: 'animus-muted', <Widget>[Component.text('Needs at least one frame. Add one.')]),
          for (final Diagnostic problem in diagnosticsAt(diagnostics, r'$.frames'))
            dom.p(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
          GuiLinesEditor(
            lines: frames,
            addLabel: 'Add frame',
            onChanged: (List<String> next) => _write(<JsonStep>['frames'], next, coalesce: 'frames'),
          ),
          for (int index = 0; index < frames.length; index++)
            if (frames[index].contains('|animation.'))
              dom.p(classes: 'animus-gui-problem is-error', <Widget>[
                dom.code(<Widget>[Component.text('\$.frames[$index]')]),
                const Component.text(' a frame cannot show another animation'),
              ]),
        ]),
        GuiSection(title: 'Timing', tip: 'The order frames play in and how long each one shows.', docs: Docs.textAnimations, children: <Widget>[
          GuiRow(
            label: 'Mode *',
            forId: modeId,
            problems: diagnosticsAt(diagnostics, r'$.mode'),
            child: GuiSelect(id: modeId, value: asString(doc['mode']), options: textAnimationModes, labels: _modeLabels, onChanged: (String? v) => _write(<JsonStep>['mode'], v)),
          ),
          GuiRow(
            label: 'Frame interval *',
            forId: intervalId,
            help: '1 to 60000 ms per frame',
            problems: diagnosticsAt(diagnostics, r'$.frameIntervalMs'),
            child: GuiNumberInput(id: intervalId, value: asInt(doc['frameIntervalMs']), label: 'Frame interval in milliseconds', onChanged: (num? v) => _write(<JsonStep>['frameIntervalMs'], v)),
          ),
          GuiRow(
            label: 'Show when',
            forId: showId,
            help: 'False shows empty text',
            problems: diagnosticsAt(diagnostics, r'$.show'),
            child: GuiTextInput(
              id: showId,
              value: show is String ? show : (show == false ? 'false' : ''),
              mode: GuiTextMode.expression,
              label: 'Show when',
              placeholder: 'always',
              onChanged: (String v) => _write(<JsonStep>['show'], v.trim().isEmpty ? null : (v.trim() == 'false' ? false : v)),
            ),
          ),
        ]),
      ]),
    ]);
  }
}
