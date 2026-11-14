/// The text animation page: its frames, the order they play in and how long
/// each shows, beside a preview that plays them the way core picks them.
/// Frames that are only a colour show as a grid of swatches with the picked
/// one's field under it; text frames are a reorderable list, thirty to a
/// page. Under reduced motion the preview starts paused and steps by hand.
library;

import 'dart:async';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:web/web.dart' as web;

import '../../logic/diagnostic_text.dart';
import '../../logic/gui/gui_expression.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_text.dart';
import '../../logic/gui/text_animation.dart';
import '../../mc/npc/mc_text.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/menu.dart';
import '../../ui/mode.dart';
import '../../ui/stage_frame.dart';
import 'component_editor.dart';
import 'gui_fields.dart';
import 'gui_rows.dart';
import 'gui_split.dart';
import 'gui_stack.dart';
import 'presets_editor.dart';
import '../select/animus_select.dart';
import '../suggest/autocomplete_input.dart';
import '../../ui/field.dart';
import '../../logic/suggest/field_profile.dart';
import '../../ui/number_input.dart';
import '../../ui/stack.dart';

const List<String> textAnimationModes = <String>['ascend', 'descend', 'ascend_descend', 'random'];

const Map<String, String> textAnimationModeLabels = <String, String>{
  'ascend': 'First to last',
  'descend': 'Last to first',
  'ascend_descend': 'There and back',
  'random': 'Random',
};

/// The frames playing in the page's StageFrame, with Play or Pause and the
/// frame steps in its header.
class TextAnimationPreview extends StatefulWidget {
  const TextAnimationPreview({required this.id, required this.document, this.band = false, super.key});

  final String id;
  final JsonMap document;

  /// The preview sits in the band above the form.
  final bool band;

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
    final bool steps = frames.length > 1;
    final String token = '|animation.${component.id.substring(component.id.indexOf(':') + 1)}|';
    return StageFrame(
      title: 'Preview',
      free: true,
      fullscreen: false,
      collapsible: component.band,
      state: frames.isEmpty ? StageState.empty : StageState.ready,
      emptyMessage: 'No frames yet.',
      actions: <RowAction>[
        RowAction('Previous frame', steps ? () => _step(-1) : null, icon: ArcaneIcon.chevronLeft(), iconOnly: true),
        RowAction(
          _playing ? 'Pause' : 'Play',
          steps ? _toggle : null,
          icon: _playing ? ArcaneIcon.pause() : ArcaneIcon.play(),
          iconOnly: true,
          pressed: !_playing,
          attributes: const <String, String>{'data-anim': 'toggle'},
        ),
        RowAction('Next frame', steps ? () => _step(1) : null, icon: ArcaneIcon.chevronRight(), iconOnly: true),
      ],
      status: frames.isEmpty ? null : 'Frame ${index + 1} of ${frames.length}${_playing && steps ? ', every $_interval ms' : (steps ? ', paused' : '')}',
      statusTip: 'Put $token in hologram or menu text to show it; chest GUIs cannot. A frame of only a colour shows sample text in it.',
      child: dom.div(classes: 'animus-anim-stage', attributes: const <String, String>{'aria-live': 'off', 'data-anim': 'frame'}, <Widget>[
        if (hidden)
          mutedText('Hidden for the sample viewer.')
        else
          GuiStyledText(textAnimationPreviewText(frame is String ? frame : '')),
      ]),
    );
  }
}

class TextAnimationEditor extends StatefulWidget {
  const TextAnimationEditor({required this.document, required this.onJson, this.presets, super.key});

  final AnimusDocument document;
  final JsonEdit onJson;

  /// The workspace's presets document, for the preset fix.
  final JsonMap? presets;

  @override
  State<TextAnimationEditor> createState() => _TextAnimationEditorState();
}

class _TextAnimationEditorState extends State<TextAnimationEditor> {
  /// The page of the frame list shown.
  int _page = 0;

  /// The swatch whose field is open.
  int _picked = 0;

  /// Swatches or a list, decided when the page opens and when a frame is
  /// added or removed, never while one is typed in, so the field being
  /// edited stays put.
  bool _swatchView = false;
  String? _viewFor;

  JsonMap get _doc => asMap(component.document.json);

  void _write(List<JsonStep> steps, Object? value, {String? coalesce}) => component.onJson(writeAt(_doc, steps, value), coalesce: coalesce ?? steps.join('.'));

  void _frames(List<String> next, {String coalesce = 'frames'}) => _write(const <JsonStep>['frames'], next, coalesce: coalesce);

  @override
  Widget build(BuildContext context) {
    final JsonMap doc = _doc;
    final List<Diagnostic> problems = component.document.diagnostics;
    final List<String> frames = <String>[for (final Object? frame in asList(doc['frames'])) frame is String ? frame : ''];
    final Object? show = doc['show'];
    final String? mode = asString(doc['mode']);
    final String shape = '${component.document.id}|${frames.length}';
    if (shape != _viewFor) {
      _viewFor = shape;
      _swatchView = colourFramesOnly(frames);
    }
    return GuiSplit(
      label: 'Text animation',
      form: <Widget>[
        PresetFix(document: component.document, presets: component.presets, kind: DocumentKind.textAnimation, onJson: component.onJson),
        stack(spacing: 16, <Widget>[
          Field(
            label: 'Frames',
            tip: 'The texts shown in turn. Colour codes such as &6 work; a frame cannot show another animation.',
            child: stack(<Widget>[
              for (final Diagnostic problem in diagnosticsAt(problems, r'$.frames', nested: true))
                AppAlert(tone: problem.severity == DiagnosticSeverity.error ? AlertTone.error : AlertTone.warning, message: diagnosticSentence(problem)),
              if (_swatchView && frames.isNotEmpty) ..._swatches(frames) else ..._list(frames),
              ActionRow(
                label: 'Frames',
                secondary: <RowAction>[
                  RowAction('Add frame', frames.length >= _maxFrames ? null : () => _add(frames), icon: ArcaneIcon.plus()),
                ],
              ),
            ]),
          ),
          Field(
            label: 'Order',
            forId: 'animus-anim-mode',
            problems: <Diagnostic>[
              for (final Diagnostic problem in diagnosticsAt(problems, r'$.mode'))
                Diagnostic(problem.severity, problem.path, mode == null ? 'Pick the order the frames play in.' : 'Pick one of these orders.'),
            ],
            child: AnimusSelect.values(
              id: 'animus-anim-mode',
              value: mode,
              noneLabel: 'Pick an order',
              values: textAnimationModes,
              labels: textAnimationModeLabels,
              onChanged: (String? v) => _write(const <JsonStep>['mode'], v),
            ),
          ),
          guiFieldRow('Each frame', r'$.frameIntervalMs', problems, help: 'Milliseconds, default 1000', (String id) => NumberInput(
                id: id,
                value: asInt(doc['frameIntervalMs']),
                label: 'Each frame in milliseconds',
                placeholder: '1000',
                onChanged: (num? v) => _write(const <JsonStep>['frameIntervalMs'], v),
              )),
          MoreFields(open: show != null, children: <Widget>[
            guiFieldRow('Show when', r'$.show', problems, help: 'False shows empty text.', tip: showWhenTip, (String id) => AutocompleteInput(
                  id: id,
                  value: conditionText(show),
                  profile: FieldProfile.guiExpression,
                  label: 'Show when',
                  onChanged: (String v) => _write(const <JsonStep>['show'], conditionValue(v)),
                )),
          ]),
        ]),
      ],
      preview: (bool band) => TextAnimationPreview(id: component.document.id, document: doc, band: band),
    );
  }

  /// A new frame at the end, a copy of the last colour in a swatch grid,
  /// with its page or swatch shown.
  void _add(List<String> frames) {
    final String next = _swatchView && colourFramesOnly(frames) ? frames.last : '';
    setState(() {
      _picked = frames.length;
      _page = framePage(frames.length + 1, frames.length ~/ framesPerPage).page;
    });
    _frames(<String>[...frames, next], coalesce: 'frames-structure');
  }

  /// Colour frames: one swatch each, then the picked frame's field and its
  /// menu.
  List<Widget> _swatches(List<String> frames) {
    final int picked = _picked.clamp(0, frames.length - 1);
    final String id = 'animus-anim-frame-$picked';
    List<String> moved(int to) => <String>[for (final Object? frame in moveItem(frames, picked, to)) frame! as String];
    void move(int to) {
      setState(() => _picked = to);
      _frames(moved(to), coalesce: 'frames-structure');
    }

    return <Widget>[
      dom.div(attributes: const <String, String>{'role': 'group', 'aria-label': 'Frame colours'}, <Widget>[
        Wrap(
          spacing: Gap.xs.value,
          runSpacing: Gap.xs.value,
          children: <Widget>[
            for (int index = 0; index < frames.length; index++) _swatch(frames[index], index, index == picked),
          ],
        ),
      ]),
      fieldLine(<Widget>[
        AutocompleteInput(
          key: ValueKey<String>(id),
          id: id,
          value: frames[picked],
          profile: FieldProfile.guiText,
          preview: (String text) => templateSample(textAnimationPreviewText(text)),
          label: 'Frame ${picked + 1}',
          onChanged: (String value) => _frames(<String>[...frames]..[picked] = value, coalesce: 'frame-$picked'),
        ),
        AppMenu(
          icon: ArcaneIcon.ellipsis(),
          iconLabel: 'Actions for frame ${picked + 1}',
          alignEnd: true,
          entries: <AppMenuEntry>[
            AppMenuEntry('Move earlier', picked == 0 ? null : () => move(picked - 1)),
            AppMenuEntry('Move later', picked == frames.length - 1 ? null : () => move(picked + 1)),
            AppMenuEntry('Duplicate', frames.length >= _maxFrames ? null : () => _frames(<String>[...frames]..insert(picked + 1, frames[picked]), coalesce: 'frames-structure')),
            const AppMenuEntry.separator(),
            AppMenuEntry('Delete', () {
              setState(() => _picked = picked == 0 ? 0 : picked - 1);
              _frames(<String>[...frames]..removeAt(picked), coalesce: 'frames-structure');
            }, destructive: true),
          ],
        ),
      ]),
    ];
  }

  /// One frame's colour as a square to pick it by (a frame edited into text
  /// shows its text's first colour); the picked one carries a check in a
  /// colour that reads on it.
  Widget _swatch(String frame, int index, bool picked) {
    final int rgb = textAnimationFrameColour(frame) ?? guiTextRuns(frame).firstOrNull?.color ?? mcDefaultTextColor;
    final String hex = '#${rgb.toRadixString(16).padLeft(6, '0').toUpperCase()}';
    final double light = (((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
    return dom.button(
      classes: 'animus-anim-swatch',
      attributes: <String, String>{
        'type': 'button',
        'aria-label': 'Frame ${index + 1}, $hex',
        'aria-pressed': '$picked',
        'title': 'Frame ${index + 1}, $hex',
        'data-frame': '$index',
      },
      styles: dom.Styles(raw: <String, String>{'background': hex, 'color': light > 140 ? '#000000' : '#ffffff'}),
      events: <String, void Function(web.Event)>{'click': (web.Event _) => setState(() => _picked = index)},
      <Widget>[if (picked) ArcaneIcon.check(size: IconSize.sm)],
    );
  }

  /// Text frames as a reorderable list, thirty to a page, with the pager
  /// above when there is more than one page.
  List<Widget> _list(List<String> frames) {
    final ({int start, int end, int page, int pages}) shown = framePage(frames.length, _page);
    final int start = shown.start;
    List<String> moved(int from, int to) => <String>[for (final Object? frame in moveItem(frames, from, to)) frame! as String];
    return <Widget>[
      if (shown.pages > 1)
        ActionRow(
          dense: true,
          label: 'Frame pages',
          leading: <Widget>[mutedText('Frames ${start + 1} to ${shown.end} of ${frames.length}')],
          secondary: <RowAction>[
            RowAction('Previous frames', shown.page == 0 ? null : () => setState(() => _page = shown.page - 1), icon: ArcaneIcon.chevronLeft(), iconOnly: true),
            RowAction('Next frames', shown.page >= shown.pages - 1 ? null : () => setState(() => _page = shown.page + 1), icon: ArcaneIcon.chevronRight(), iconOnly: true),
          ],
        ),
      if (frames.isNotEmpty)
        OrderedRows(
          key: ValueKey<String>('frames-page-${shown.page}'),
          label: 'Frames',
          dense: false,
          rows: <OrderedRow>[
            for (int index = start; index < shown.end; index++)
              OrderedRow(
                key: '$index',
                name: 'frame ${index + 1}',
                line: AutocompleteInput(
                  value: frames[index],
                  profile: FieldProfile.guiText,
                  preview: (String text) => templateSample(textAnimationPreviewText(text)),
                  label: 'Frame ${index + 1}',
                  onChanged: (String value) => _frames(<String>[...frames]..[index] = value, coalesce: 'frame-$index'),
                ),
              ),
          ],
          onMove: (int from, int to) => _frames(moved(start + from, start + to), coalesce: 'frames-structure'),
          onDuplicate: frames.length >= _maxFrames ? null : (int index) => _frames(<String>[...frames]..insert(start + index + 1, frames[start + index]), coalesce: 'frames-structure'),
          onDelete: (int index) => _frames(<String>[...frames]..removeAt(start + index), coalesce: 'frames-structure'),
        ),
    ];
  }
}

/// The most frames the editor adds; core reads up to 1024.
const int _maxFrames = 256;
