/// The hologram editor: rows of text, items, heads, blocks and entities,
/// pages and variants, the anchor, the click box and its actions, with the
/// hologram drawn live in the 3D stage.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_catalog.dart';
import '../../logic/gui/gui_expression.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/text_animation.dart';
import '../../mc/gui/gui_scene.dart';
import '../../model/animus_document.dart';
import 'actions_editor.dart';
import 'component_editor.dart';
import 'gui_fields.dart';
import 'gui_stage_preview.dart';

const List<String> _lineKinds = <String>['text', 'item', 'head', 'block', 'entity'];

GuiAnimationClock _clock(AnimusDocument document, Map<String, JsonMap> animations, int nowMs) =>
    GuiAnimationClock(from: document.id, animations: animations, nowMs: nowMs);

class HologramPreview extends StatelessWidget {
  const HologramPreview({required this.document, this.page, this.variant, this.animations = const <String, JsonMap>{}, super.key});

  final AnimusDocument document;
  final int? page;
  final int? variant;

  /// The workspace's text animations by id.
  final Map<String, JsonMap> animations;

  @override
  Widget build(BuildContext context) {
    final JsonMap doc = asMap(document.json);
    return dom.section(classes: 'animus-panel animus-gui-preview', <Widget>[
      GuiStagePreview(
        scene: hologramScene(doc, page: page, variant: variant, clock: _clock(document, animations, DateTime.now().millisecondsSinceEpoch)),
        sceneAt: (int nowMs) => hologramScene(doc, page: page, variant: variant, clock: _clock(document, animations, nowMs)),
        tickMs: animationTickMs(document.source, document.id, animations),
        signature: '${document.source}|$page|$variant',
        label: 'Hologram ${document.id}',
      ),
    ]);
  }
}

class HologramEditor extends StatefulWidget {
  const HologramEditor({required this.document, required this.onJson, this.animations = const <String, JsonMap>{}, super.key});

  final AnimusDocument document;
  final JsonEdit onJson;

  /// The workspace's text animations by id.
  final Map<String, JsonMap> animations;

  @override
  State<HologramEditor> createState() => _HologramEditorState();
}

class _HologramEditorState extends State<HologramEditor> {
  /// Which lines are edited and shown: null for the base lines.
  int? _page;
  int? _variant;

  JsonMap get _doc => asMap(component.document.json);
  List<Diagnostic> get _diagnostics => component.document.diagnostics;

  void _write(List<JsonStep> steps, Object? value, {String? coalesce}) =>
      component.onJson(writeIn(_doc, steps, value), coalesce: coalesce ?? steps.join('.'));

  List<JsonStep> get _linesSteps {
    if (_page != null) return <JsonStep>['pages', _page!, 'lines'];
    if (_variant != null) return <JsonStep>['variants', _variant!, 'presentation', 'lines'];
    return const <JsonStep>['lines'];
  }

  @override
  Widget build(BuildContext context) {
    final JsonMap doc = _doc;
    final JsonList pages = asList(doc['pages']);
    final JsonList variants = asList(doc['variants']);
    final String which = _page != null ? 'page:$_page' : (_variant != null ? 'variant:$_variant' : 'base');
    return dom.div(classes: 'animus-gui-editor', <Widget>[
      dom.section(classes: 'animus-panel animus-gui-stage', <Widget>[
        GuiStagePreview(
          scene: hologramScene(doc, page: _page, variant: _variant, clock: _clock(component.document, component.animations, DateTime.now().millisecondsSinceEpoch)),
          sceneAt: (int nowMs) => hologramScene(doc, page: _page, variant: _variant, clock: _clock(component.document, component.animations, nowMs)),
          tickMs: animationTickMs(component.document.source, component.document.id, component.animations),
          signature: '${component.document.source}|$which',
          label: 'Hologram ${component.document.id}',
          toolbar: <Widget>[
            GuiSelect(
              value: which == 'base' ? null : which,
              allowNone: true,
              noneLabel: 'Base lines',
              options: <String>[
                for (int i = 0; i < pages.length; i++) 'page:$i',
                for (int i = 0; i < variants.length; i++) 'variant:$i',
              ],
              labels: <String, String>{
                for (int i = 0; i < pages.length; i++) 'page:$i': 'Page ${asString(asMap(pages[i])['id']) ?? i + 1}',
                for (int i = 0; i < variants.length; i++) 'variant:$i': 'Variant ${asString(asMap(variants[i])['id']) ?? i + 1}',
              },
              onChanged: (String? value) => setState(() {
                _page = value != null && value.startsWith('page:') ? int.parse(value.substring(5)) : null;
                _variant = value != null && value.startsWith('variant:') ? int.parse(value.substring(8)) : null;
              }),
            ),
          ],
        ),
      ]),
      dom.section(classes: 'animus-panel animus-gui-inspector', <Widget>[
        GuiSection(title: _page != null ? 'Lines of page ${_page! + 1}' : (_variant != null ? 'Lines of variant ${_variant! + 1}' : 'Lines'), children: <Widget>[
          _lines(asList(readIn(doc, _linesSteps))),
        ]),
        GuiSection(title: 'Placement', children: _placement(doc)),
        GuiSection(title: 'Click actions', count: asList(doc['actions']).length, open: asList(doc['actions']).isNotEmpty, children: <Widget>[
          const dom.p(classes: 'animus-muted', <Widget>[
            Component.text('Run when a viewer clicks the hologram\'s box. navigate with mode page and target next, prev or a page id turns pages.'),
          ]),
          ActionsEditor(
            actions: asList(doc['actions']),
            path: r'$.actions',
            diagnostics: _diagnostics,
            unavailable: hologramRefusedActions,
            onChanged: (JsonList next) => _write(<JsonStep>['actions'], next.isEmpty ? null : next),
          ),
          GuiRow(
            label: 'Box width',
            child: GuiNumberInput(value: asDouble(asMap(doc['hitbox'])['width']), integer: false, label: 'Hitbox width', onChanged: (num? v) => _hitbox('width', v)),
          ),
          GuiRow(
            label: 'Row height',
            child: GuiNumberInput(value: asDouble(asMap(doc['hitbox'])['height']), integer: false, label: 'Hitbox height', onChanged: (num? v) => _hitbox('height', v)),
          ),
          GuiRow(
            label: 'Per line',
            child: GuiSwitch(value: asMap(doc['hitbox'])['perLine'] == true, label: 'One box per row', onChanged: (bool on) => _hitbox('perLine', on ? true : null)),
          ),
        ]),
        GuiSection(title: 'Pages', count: pages.length, open: pages.isNotEmpty, children: _pages(pages)),
        GuiSection(title: 'Variants', count: variants.length, open: variants.isNotEmpty, children: _variants(variants)),
      ]),
    ]);
  }

  void _hitbox(String key, Object? value) {
    final JsonMap next = withField(asMap(_doc['hitbox']), key, value);
    _write(<JsonStep>['hitbox'], next.isEmpty ? null : next, coalesce: 'hitbox.$key');
  }

  Widget _lines(JsonList lines) {
    final String base = formatPath(_linesSteps);
    void setLines(JsonList next) => _write(_linesSteps, next, coalesce: '$base-structure');
    return dom.div(classes: 'animus-gui-lines', <Widget>[
      for (int index = 0; index < lines.length; index++) _line(lines, index, '$base[$index]', setLines),
      dom.div(classes: 'animus-row', <Widget>[
        Button.ghost(label: 'Add text', size: ButtonSize.small, onPressed: () => setLines(<Object?>[...lines, '&fText'])),
        Button.ghost(
          label: 'Add item',
          size: ButtonSize.small,
          onPressed: () => setLines(<Object?>[...lines, <String, Object?>{'item': <String, Object?>{'type': 'item', 'item': 'minecraft:diamond'}}]),
        ),
        Button.ghost(label: 'Add block', size: ButtonSize.small, onPressed: () => setLines(<Object?>[...lines, <String, Object?>{'block': 'minecraft:stone'}])),
      ]),
    ]);
  }

  Widget _line(JsonList lines, int index, String path, void Function(JsonList) setLines) {
    final Object? line = lines[index];
    final JsonMap object = asMap(line);
    final String kind = line is String || line == null ? 'text' : _lineKinds.firstWhere(object.containsKey, orElse: () => 'text');
    void set(Object? value) => _write(<JsonStep>[..._linesSteps, index], value, coalesce: path);
    final Widget value = switch (kind) {
      'text' => GuiTextInput(
          value: line is String ? line : (asString(object['text']) ?? ''),
          mode: GuiTextMode.template,
          names: ExpressionNames(animations: animationCompletionIds(component.document.id, component.animations.keys)),
          label: 'Line ${index + 1} text',
          onChanged: (String v) => set(line is String || line == null ? v : <String, Object?>{'text': v}),
        ),
      'item' => GuiTextInput(
          value: asString(asMap(object['item'])['item']) ?? '',
          mode: GuiTextMode.template,
          label: 'Line ${index + 1} item',
          onChanged: (String v) => set(<String, Object?>{'item': <String, Object?>{'type': 'item', ...asMap(object['item']), 'item': v}}),
        ),
      _ => GuiTextInput(
          value: asString(object[kind]) ?? '',
          mode: kind == 'head' ? GuiTextMode.template : GuiTextMode.plain,
          label: 'Line ${index + 1} $kind',
          onChanged: (String v) => set(<String, Object?>{kind: v}),
        ),
    };
    return dom.div(classes: 'animus-gui-line', attributes: <String, String>{'data-path': path}, <Widget>[
      GuiSelect(
        value: kind,
        options: _lineKinds,
        onChanged: (String? next) {
          if (next == null || next == kind) return;
          set(switch (next) {
            'text' => '&fText',
            'item' => <String, Object?>{'item': <String, Object?>{'type': 'item', 'item': 'minecraft:diamond'}},
            'head' => <String, Object?>{'head': '{{ viewer.name }}'},
            'block' => <String, Object?>{'block': 'minecraft:stone'},
            _ => <String, Object?>{'entity': 'minecraft:pig'},
          });
        },
      ),
      value,
      GuiIconButton(
        icon: ArcaneIcon.arrowUp(size: IconSize.sm),
        label: 'Move line ${index + 1} up',
        disabled: index == 0,
        onPressed: () => setLines(moveItem(lines, index, index - 1)),
      ),
      GuiIconButton(icon: ArcaneIcon.trash(size: IconSize.sm), label: 'Remove line ${index + 1}', onPressed: () => setLines(<Object?>[...lines]..removeAt(index))),
      for (final Diagnostic problem in diagnosticsAt(_diagnostics, path, nested: true))
        dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text('${problem.path} ${problem.message}')]),
    ]);
  }

  List<Widget> _placement(JsonMap doc) {
    final JsonMap anchor = asMap(doc['anchor']);
    final JsonList position = asList(anchor['position']);
    double axis(int i) => asDouble(position.elementAtOrNull(i)) ?? 0;
    void setAxis(int i, num? value) {
      final List<double> next = <double>[axis(0), axis(1), axis(2)]..[i] = (value ?? 0).toDouble();
      _write(<JsonStep>['anchor', 'position'], next, coalesce: 'anchor.position.$i');
    }

    return <Widget>[
      GuiRow(
        label: 'World *',
        problems: diagnosticsAt(_diagnostics, r'$.anchor.world'),
        child: GuiTextInput(value: asString(anchor['world']) ?? '', label: 'World', onChanged: (String v) => _write(<JsonStep>['anchor', 'world'], v)),
      ),
      GuiRow(
        label: 'Position *',
        problems: diagnosticsAt(_diagnostics, r'$.anchor.position', nested: true),
        child: dom.div(classes: 'animus-row animus-gui-xyz', <Widget>[
          for (int i = 0; i < 3; i++) GuiNumberInput(value: axis(i), integer: false, label: 'xyz'[i], onChanged: (num? v) => setAxis(i, v)),
        ]),
      ),
      GuiRow(label: 'Yaw', child: GuiNumberInput(value: asDouble(doc['yaw']), integer: false, label: 'Yaw', onChanged: (num? v) => _write(<JsonStep>['yaw'], v))),
      GuiRow(label: 'Pitch', child: GuiNumberInput(value: asDouble(doc['pitch']), integer: false, label: 'Pitch', onChanged: (num? v) => _write(<JsonStep>['pitch'], v))),
      GuiRow(
        label: 'View distance',
        help: '4 to 128 blocks; 48 by default.',
        problems: diagnosticsAt(_diagnostics, r'$.viewDistance'),
        child: GuiNumberInput(value: asDouble(doc['viewDistance']), integer: false, label: 'View distance', onChanged: (num? v) => _write(<JsonStep>['viewDistance'], v)),
      ),
      GuiRow(
        label: 'Refresh',
        help: 'Ticks between text updates; 10 by default.',
        problems: diagnosticsAt(_diagnostics, r'$.refreshTicks'),
        child: GuiNumberInput(value: asInt(doc['refreshTicks']), label: 'Refresh ticks', onChanged: (num? v) => _write(<JsonStep>['refreshTicks'], v)),
      ),
      GuiRow(
        label: 'Show when',
        problems: diagnosticsAt(_diagnostics, r'$.show'),
        child: GuiTextInput(
          value: doc['show'] is String ? doc['show']! as String : '',
          mode: GuiTextMode.expression,
          label: 'Show when',
          onChanged: (String v) => _write(<JsonStep>['show'], v.isEmpty ? null : v),
        ),
      ),
      GuiRow(
        label: 'Revision',
        problems: diagnosticsAt(_diagnostics, r'$.revision'),
        child: GuiNumberInput(value: asInt(doc['revision']), label: 'Revision', onChanged: (num? v) => _write(<JsonStep>['revision'], v)),
      ),
    ];
  }

  List<Widget> _pages(JsonList pages) => <Widget>[
        const dom.p(classes: 'animus-muted', <Widget>[Component.text('Pages replace the lines; each viewer reads one page.')]),
        for (int index = 0; index < pages.length; index++)
          dom.div(classes: 'animus-row', <Widget>[
            GuiTextInput(
              value: asString(asMap(pages[index])['id']) ?? '',
              label: 'Page ${index + 1} id',
              onChanged: (String v) => _write(<JsonStep>['pages', index, 'id'], v),
            ),
            Button.ghost(label: 'Edit lines', size: ButtonSize.small, onPressed: () => setState(() {
              _page = index;
              _variant = null;
            })),
            GuiIconButton(
              icon: ArcaneIcon.trash(size: IconSize.sm),
              label: 'Remove page ${index + 1}',
              onPressed: () {
                setState(() => _page = null);
                _write(<JsonStep>['pages'], pages.length == 1 ? null : (<Object?>[...pages]..removeAt(index)));
              },
            ),
          ]),
        Button.secondary(
          label: 'Add page',
          size: ButtonSize.small,
          onPressed: () => _write(<JsonStep>['pages'], <Object?>[
            ...pages,
            <String, Object?>{'id': 'page${pages.length + 1}', 'lines': <Object?>['&fPage ${pages.length + 1}']},
          ]),
        ),
      ];

  List<Widget> _variants(JsonList variants) => <Widget>[
        const dom.p(classes: 'animus-muted', <Widget>[
          Component.text('The first variant whose condition holds, by descending priority, replaces the lines it names.'),
        ]),
        for (int index = 0; index < variants.length; index++)
          GuiSection(title: 'Variant ${asString(asMap(variants[index])['id']) ?? index + 1}', open: false, children: <Widget>[
            GuiRow(
              label: 'Id',
              problems: diagnosticsAt(_diagnostics, '\$.variants[$index].id'),
              child: GuiTextInput(value: asString(asMap(variants[index])['id']) ?? '', label: 'Variant id', onChanged: (String v) => _write(<JsonStep>['variants', index, 'id'], v)),
            ),
            GuiRow(
              label: 'Priority',
              child: GuiNumberInput(value: asInt(asMap(variants[index])['priority']), label: 'Priority', onChanged: (num? v) => _write(<JsonStep>['variants', index, 'priority'], v)),
            ),
            GuiRow(
              label: 'When *',
              problems: diagnosticsAt(_diagnostics, '\$.variants[$index].when'),
              child: GuiTextInput(
                value: asString(asMap(variants[index])['when']) ?? '',
                mode: GuiTextMode.expression,
                label: 'When',
                onChanged: (String v) => _write(<JsonStep>['variants', index, 'when'], v),
              ),
            ),
            dom.div(classes: 'animus-row', <Widget>[
              Button.ghost(label: 'Edit lines', size: ButtonSize.small, onPressed: () => setState(() {
                _variant = index;
                _page = null;
              })),
              Button.ghost(
                label: 'Remove variant',
                size: ButtonSize.small,
                onPressed: () {
                  setState(() => _variant = null);
                  _write(<JsonStep>['variants'], variants.length == 1 ? null : (<Object?>[...variants]..removeAt(index)));
                },
              ),
            ]),
          ]),
        Button.secondary(
          label: 'Add variant',
          size: ButtonSize.small,
          onPressed: () => _write(<JsonStep>['variants'], <Object?>[
            ...variants,
            <String, Object?>{
              'id': 'variant${variants.length + 1}',
              'priority': 0,
              'when': 'time.hour >= 20',
              'presentation': <String, Object?>{'lines': deepCopy(asList(_doc['lines']))},
            },
          ]),
        ),
      ];
}
