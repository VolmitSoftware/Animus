/// The hologram editor: rows of text, items, heads, blocks and entities,
/// pages and variants, the anchor, style, refresh, the click box and its
/// actions, with the hologram drawn live in the 3D stage.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_catalog.dart';
import '../../logic/gui/gui_expression.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/hologram_lines.dart';
import '../../logic/gui/text_animation.dart';
import '../../mc/gui/gui_scene.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import 'actions_editor.dart';
import 'component_editor.dart';
import 'gui_fields.dart';
import 'gui_stage_preview.dart';
import 'presets_editor.dart';
import 'style_editor.dart';

GuiAnimationClock _clock(AnimusDocument document, Map<String, JsonMap> animations, int nowMs) =>
    GuiAnimationClock(from: document.id, animations: animations, nowMs: nowMs);

const Map<String, String> _kindLabels = <String, String>{'text': 'Text', 'item': 'Item', 'head': 'Head', 'block': 'Block', 'entity': 'Entity'};

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
  const HologramEditor({required this.document, required this.onJson, this.animations = const <String, JsonMap>{}, this.presets, super.key});

  final AnimusDocument document;
  final JsonEdit onJson;

  /// The workspace's text animations by id.
  final Map<String, JsonMap> animations;

  /// The workspace's presets document, for the preset fix.
  final JsonMap? presets;

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
      component.onJson(writeAt(_doc, steps, value), coalesce: coalesce ?? steps.join('.'));

  List<JsonStep> get _linesSteps {
    if (_page != null) return <JsonStep>['pages', _page!, 'lines'];
    if (_variant != null) return <JsonStep>['variants', _variant!, 'presentation', 'lines'];
    return const <JsonStep>['lines'];
  }

  ExpressionNames get _names => ExpressionNames(animations: animationCompletionIds(component.document.id, component.animations.keys));

  @override
  Widget build(BuildContext context) {
    final JsonMap doc = _doc;
    final JsonList pages = asList(doc['pages']);
    final JsonList variants = asList(doc['variants']);
    if (_page != null && _page! >= pages.length) _page = pages.isEmpty ? null : pages.length - 1;
    if (_variant != null && _variant! >= variants.length) _variant = variants.isEmpty ? null : variants.length - 1;
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
              hiddenLabel: 'Lines shown',
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
        PresetFix(document: component.document, presets: component.presets, kind: DocumentKind.hologram, onJson: component.onJson),
        GuiSection(title: _page != null ? 'Lines of page ${_page! + 1}' : (_variant != null ? 'Lines of variant ${_variant! + 1}' : 'Lines'), children: <Widget>[
          if (_page == null && _variant == null && pages.isNotEmpty)
            const dom.p(classes: 'animus-muted', <Widget>[Component.text('Pages replace these lines. Pick a page above.')])
          else
            _lines(asList(readIn(doc, _linesSteps))),
        ]),
        GuiSection(title: 'Placement', children: _placement(doc)),
        DisplayStyleEditor(
          style: asMap(doc['style']),
          path: r'$.style',
          diagnostics: _diagnostics,
          note: 'Applies to every row. Default: billboard center, see-through.',
          onChanged: (JsonMap? next, String field) => _write(<JsonStep>['style'], next, coalesce: 'style.$field'),
        ),
        GuiSection(
          title: 'Click actions',
          count: asList(doc['actions']).length,
          open: asList(doc['actions']).isNotEmpty,
          tip: 'Runs when a viewer clicks the box. navigate with mode page and target next, prev or a page id turns pages.',
          children: <Widget>[
            ActionsEditor(
              actions: asList(doc['actions']),
              path: r'$.actions',
              diagnostics: _diagnostics,
              unavailable: hologramRefusedActions,
              onChanged: (JsonList next) => _write(<JsonStep>['actions'], next.isEmpty ? null : next),
            ),
            _number('Box width', r'$.hitbox.width', asDouble(asMap(doc['hitbox'])['width']), (num? v) => _hitbox('width', v), help: '0.01 to 64 blocks, default 1.2', integer: false),
            _number('Row height', r'$.hitbox.height', asDouble(asMap(doc['hitbox'])['height']), (num? v) => _hitbox('height', v), help: '0.01 to 64 blocks, default 0.35', integer: false),
            GuiRow(
              label: 'Per line',
              child: GuiSwitch(value: asMap(doc['hitbox'])['perLine'] == true, label: 'One box per row', onChanged: (bool on) => _hitbox('perLine', on ? true : null)),
            ),
          ],
        ),
        GuiSection(title: 'Pages', count: pages.length, open: pages.isNotEmpty, tip: 'Pages replace the lines. Each viewer sees one page.', children: _pages(pages)),
        GuiSection(
          title: 'Variants',
          count: variants.length,
          open: variants.isNotEmpty,
          tip: 'The highest-priority variant whose condition holds replaces the lines or style it names.',
          children: _variants(variants),
        ),
      ]),
    ]);
  }

  Widget _number(String label, String path, num? value, void Function(num?) onChanged, {String? help, bool integer = true}) {
    final String id = guiPathId(path);
    return GuiRow(
      label: label,
      forId: id,
      help: help,
      problems: diagnosticsAt(_diagnostics, path),
      child: GuiNumberInput(id: id, value: value, integer: integer, label: label, onChanged: onChanged),
    );
  }

  void _hitbox(String key, Object? value) {
    final JsonMap next = withField(asMap(_doc['hitbox']), key, value);
    _write(<JsonStep>['hitbox'], next.isEmpty ? null : next, coalesce: 'hitbox.$key');
  }

  Widget _lines(JsonList lines) {
    final String base = formatPath(_linesSteps);
    void setLines(JsonList next) => _write(_linesSteps, next, coalesce: '$base-structure');
    return dom.div(classes: 'animus-gui-lines', <Widget>[
      if (lines.isEmpty) const dom.p(classes: 'animus-muted', <Widget>[Component.text('No rows yet. Add one below.')]),
      for (int index = 0; index < lines.length; index++) _line(lines, index, '$base[$index]', setLines),
      dom.div(classes: 'animus-row', <Widget>[
        for (final String kind in hologramLineKinds)
          Button.ghost(
            label: 'Add ${_kindLabels[kind]!.toLowerCase()}',
            size: ButtonSize.small,
            disabled: lines.length >= 128,
            onPressed: () => setLines(<Object?>[...lines, newHologramLine(kind)]),
          ),
      ]),
    ]);
  }

  Widget _line(JsonList lines, int index, String path, void Function(JsonList) setLines) {
    final Object? line = lines[index];
    final JsonMap object = asMap(line);
    final String kind = hologramLineKind(line);
    final List<JsonStep> steps = <JsonStep>[..._linesSteps, index];
    void set(Object? next) => _write(steps, next, coalesce: path);
    final String kindId = guiPathId('$path-kind');
    final Widget value = switch (kind) {
      'text' => GuiTextInput(
          value: line is String ? line : (asString(object['text']) ?? ''),
          mode: GuiTextMode.template,
          names: _names,
          label: 'Row ${index + 1} text',
          onChanged: (String v) => set(withHologramLineValue(line, v)),
        ),
      'item' => _itemRow(asMap(object['item']), path, index, (JsonMap next) => set(withHologramLineValue(line, next))),
      _ => GuiTextInput(
          value: asString(object[kind]) ?? '',
          mode: kind == 'head' ? GuiTextMode.template : GuiTextMode.plain,
          names: _names,
          label: 'Row ${index + 1} ${kind == 'head' ? 'player' : kind}',
          placeholder: kind == 'head' ? 'a player name' : 'minecraft:$kind id',
          onChanged: (String v) => set(withHologramLineValue(line, v)),
        ),
    };
    final Object? show = object['show'];
    return dom.div(classes: 'animus-gui-holo-row', attributes: <String, String>{'data-path': path}, <Widget>[
      dom.div(classes: 'animus-gui-line', <Widget>[
        GuiSelect(
          id: kindId,
          value: kind,
          options: hologramLineKinds,
          labels: _kindLabels,
          hiddenLabel: 'Row ${index + 1} kind',
          onChanged: (String? next) {
            if (next == null || next == kind) return;
            set(withHologramLineKind(line, next));
          },
        ),
        if (kind != 'item') value,
        ...guiMoveButtons(index: index, length: lines.length, noun: 'row ${index + 1}', onMove: (int to) => setLines(moveItem(lines, index, to))),
        GuiIconButton(icon: ArcaneIcon.trash(size: IconSize.sm), label: 'Remove row ${index + 1}', onPressed: () => setLines(<Object?>[...lines]..removeAt(index))),
      ]),
      if (kind == 'item') value,
      dom.div(classes: 'animus-gui-holo-extras', <Widget>[
        GuiRow(
          label: 'Show when',
          forId: guiPathId('$path.show'),
          problems: diagnosticsAt(_diagnostics, '$path.show'),
          child: GuiTextInput(
            id: guiPathId('$path.show'),
            value: show is String ? show : (show == false ? 'false' : ''),
            mode: GuiTextMode.expression,
            names: _names,
            label: 'Row ${index + 1} show when',
            placeholder: 'always',
            onChanged: (String v) => set(withHologramLineField(line, 'show', v.trim().isEmpty ? null : (v.trim() == 'false' ? false : (v.trim() == 'true' ? true : v)))),
          ),
        ),
        _number('Scale', '$path.scale', asDouble(object['scale']), (num? v) => set(withHologramLineField(line, 'scale', v)), help: '0.01 to 64, default 1', integer: false),
      ]),
      for (final Diagnostic problem in diagnosticsAt(_diagnostics, path))
        dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text('${problem.path} ${problem.message}')]),
    ]);
  }

  /// An item row: the icon object, keeping keys it does not edit.
  Widget _itemRow(JsonMap icon, String path, int index, void Function(JsonMap) onChanged) {
    final String itemPath = '$path.item';
    void field(String key, Object? value) => onChanged(withField(icon, key, value));
    Widget row(String label, String key, Widget Function(String id) child, {String? help, bool labelled = true}) {
      final String id = guiPathId('$itemPath.$key');
      return GuiRow(label: label, forId: labelled ? id : null, help: help, problems: diagnosticsAt(_diagnostics, childPath(itemPath, key), nested: key == 'lore'), child: child(id));
    }

    return dom.div(classes: 'animus-gui-fields animus-gui-holo-item', <Widget>[
      row('Item', 'item', (String id) => GuiTextInput(id: id, value: asString(icon['item']) ?? '', mode: GuiTextMode.template, names: _names, label: 'Row ${index + 1} item', onChanged: (String v) => field('item', v)),
          help: 'Such as minecraft:diamond'),
      row('Count', 'count', (String id) => GuiNumberInput(id: id, value: asInt(icon['count']), label: 'Row ${index + 1} count', onChanged: (num? v) => field('count', v)), help: '1 to 99'),
      row('Name', 'name', (String id) => GuiTextInput(id: id, value: asString(icon['name']) ?? '', mode: GuiTextMode.template, names: _names, label: 'Row ${index + 1} name', onChanged: (String v) => field('name', v))),
      row('Lore', 'lore', (String _) => GuiLinesEditor(lines: stringList(icon['lore']), names: _names, onChanged: (List<String> lines) => field('lore', lines)), labelled: false),
      row('Custom model', 'customModelValue', (String id) => GuiNumberInput(id: id, value: asInt(icon['customModelValue']), label: 'Row ${index + 1} custom model value', onChanged: (num? v) => field('customModelValue', v))),
      if (icon.containsKey('style'))
        dom.p(classes: 'animus-gui-note', <Widget>[
          dom.code(<Widget>[Component.text('$itemPath.style')]),
          const Component.text(' is ignored on rows; the hologram style applies. '),
          Button.ghost(label: 'Remove it', size: ButtonSize.small, onPressed: () => field('style', null)),
        ]),
    ]);
  }

  List<Widget> _placement(JsonMap doc) {
    final JsonMap anchor = asMap(doc['anchor']);
    final JsonList position = asList(anchor['position']);
    final JsonMap refresh = asMap(doc['refresh']);
    double axis(int i) => asDouble(position.elementAtOrNull(i)) ?? 0;
    void setAxis(int i, num? value) {
      final List<double> next = <double>[axis(0), axis(1), axis(2)]..[i] = (value ?? 0).toDouble();
      _write(<JsonStep>['anchor', 'position'], next, coalesce: 'anchor.position.$i');
    }

    void setRefresh(String key, num? value) {
      final JsonMap next = withField(refresh, key, value);
      _write(<JsonStep>['refresh'], next.isEmpty ? null : next, coalesce: 'refresh.$key');
    }

    final String worldId = guiPathId(r'$.anchor.world');
    final String showId = guiPathId(r'$.show');
    return <Widget>[
      GuiRow(
        label: 'World *',
        forId: worldId,
        problems: diagnosticsAt(_diagnostics, r'$.anchor.world'),
        child: GuiTextInput(id: worldId, value: asString(anchor['world']) ?? '', label: 'World', onChanged: (String v) => _write(<JsonStep>['anchor', 'world'], v)),
      ),
      GuiRow(
        label: 'Position *',
        problems: diagnosticsAt(_diagnostics, r'$.anchor.position', nested: true),
        child: dom.div(classes: 'animus-row animus-gui-xyz', <Widget>[
          for (int i = 0; i < 3; i++) GuiNumberInput(value: axis(i), integer: false, label: 'Position ${'xyz'[i]}', placeholder: 'xyz'[i], onChanged: (num? v) => setAxis(i, v)),
        ]),
      ),
      _number('Yaw', r'$.yaw', asDouble(doc['yaw']), (num? v) => _write(<JsonStep>['yaw'], v), help: '-180 to 180 degrees', integer: false),
      _number('Pitch', r'$.pitch', asDouble(doc['pitch']), (num? v) => _write(<JsonStep>['pitch'], v), help: '-90 to 90 degrees', integer: false),
      _number('View distance', r'$.viewDistance', asDouble(doc['viewDistance']), (num? v) => _write(<JsonStep>['viewDistance'], v), help: '4 to 128 blocks, default 48', integer: false),
      _number('Refresh', r'$.refreshTicks', asInt(doc['refreshTicks']), (num? v) => _write(<JsonStep>['refreshTicks'], v), help: '1 to 200 ticks, default 10'),
      _number('Content refresh', r'$.refresh.contentTicks', asInt(refresh['contentTicks']), (num? v) => setRefresh('contentTicks', v), help: '1 to 1200 ticks, overrides Refresh'),
      _number('Visibility refresh', r'$.refresh.visibilityTicks', asInt(refresh['visibilityTicks']), (num? v) => setRefresh('visibilityTicks', v), help: '1 to 1200 ticks between checks'),
      _number('Motion refresh', r'$.refresh.motionTicks', asInt(refresh['motionTicks']), (num? v) => setRefresh('motionTicks', v), help: '1 to 1200, not used yet'),
      GuiRow(
        label: 'Show when',
        forId: showId,
        problems: diagnosticsAt(_diagnostics, r'$.show'),
        child: GuiTextInput(
          id: showId,
          value: doc['show'] is String ? doc['show']! as String : (doc['show'] == false ? 'false' : ''),
          mode: GuiTextMode.expression,
          label: 'Show when',
          placeholder: 'always',
          onChanged: (String v) => _write(<JsonStep>['show'], v.trim().isEmpty ? null : (v.trim() == 'false' ? false : v)),
        ),
      ),
      _number('Revision', r'$.revision', asInt(doc['revision']), (num? v) => _write(<JsonStep>['revision'], v)),
    ];
  }

  void _removePage(JsonList pages, int index) {
    final int remaining = pages.length - 1;
    setState(() => _page = remaining == 0 ? null : (index >= remaining ? remaining - 1 : index));
    _write(<JsonStep>['pages'], remaining == 0 ? null : (<Object?>[...pages]..removeAt(index)));
  }

  List<Widget> _pages(JsonList pages) => <Widget>[
        for (int index = 0; index < pages.length; index++)
          GuiSection(title: 'Page ${asString(asMap(pages[index])['id']) ?? index + 1}', open: _page == index, children: <Widget>[
            GuiRow(
              label: 'Id',
              forId: guiPathId('\$.pages[$index].id'),
              problems: diagnosticsAt(_diagnostics, '\$.pages[$index].id'),
              child: GuiTextInput(
                id: guiPathId('\$.pages[$index].id'),
                value: asString(asMap(pages[index])['id']) ?? '',
                label: 'Page ${index + 1} id',
                onChanged: (String v) => _write(<JsonStep>['pages', index, 'id'], v.isEmpty ? null : v),
              ),
            ),
            GuiRow(
              label: 'Show when',
              forId: guiPathId('\$.pages[$index].show'),
              help: 'Hidden pages are skipped',
              problems: diagnosticsAt(_diagnostics, '\$.pages[$index].show'),
              child: GuiTextInput(
                id: guiPathId('\$.pages[$index].show'),
                value: asMap(pages[index])['show'] is String ? asMap(pages[index])['show']! as String : (asMap(pages[index])['show'] == false ? 'false' : ''),
                mode: GuiTextMode.expression,
                label: 'Page ${index + 1} show when',
                placeholder: 'always',
                onChanged: (String v) => _write(<JsonStep>['pages', index, 'show'], v.trim().isEmpty ? null : (v.trim() == 'false' ? false : v)),
              ),
            ),
            dom.div(classes: 'animus-row', <Widget>[
              Button.secondary(label: 'Edit lines', size: ButtonSize.small, onPressed: () => setState(() {
                _page = index;
                _variant = null;
              })),
              ...guiMoveButtons(index: index, length: pages.length, noun: 'page ${index + 1}', onMove: (int to) {
                _write(<JsonStep>['pages'], moveItem(pages, index, to));
                if (_page == index) setState(() => _page = to);
              }),
              GuiIconButton(icon: ArcaneIcon.trash(size: IconSize.sm), label: 'Remove page ${index + 1}', onPressed: () => _removePage(pages, index)),
            ]),
          ]),
        Button.secondary(
          label: 'Add page',
          size: ButtonSize.small,
          disabled: pages.length >= 64,
          onPressed: () {
            final JsonList base = asList(_doc['lines']);
            Object? next = writeIn(_doc, <JsonStep>['pages'], <Object?>[
              ...pages,
              <String, Object?>{'id': 'page${pages.length + 1}', 'lines': pages.isEmpty && base.isNotEmpty ? deepCopy(base) : <Object?>['&fPage ${pages.length + 1}']},
            ]);
            // Core refuses lines and pages together; the first page takes the lines.
            if (pages.isEmpty) next = writeIn(next, <JsonStep>['lines'], null);
            component.onJson(writeAt(_doc, const <JsonStep>[], next));
            setState(() {
              _page = pages.length;
              _variant = null;
            });
          },
        ),
      ];

  List<Widget> _variants(JsonList variants) => <Widget>[
        for (int index = 0; index < variants.length; index++) _variantSection(variants, index),
        Button.secondary(
          label: 'Add variant',
          size: ButtonSize.small,
          disabled: variants.length >= 64,
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

  Widget _variantSection(JsonList variants, int index) {
    final JsonMap variant = asMap(variants[index]);
    final String path = '\$.variants[$index]';
    return GuiSection(title: 'Variant ${asString(variant['id']) ?? index + 1}', open: _variant == index, children: <Widget>[
      GuiRow(
        label: 'Id',
        forId: guiPathId('$path.id'),
        problems: diagnosticsAt(_diagnostics, '$path.id'),
        child: GuiTextInput(id: guiPathId('$path.id'), value: asString(variant['id']) ?? '', label: 'Variant id', onChanged: (String v) => _write(<JsonStep>['variants', index, 'id'], v)),
      ),
      _number('Priority', '$path.priority', asInt(variant['priority']), (num? v) => _write(<JsonStep>['variants', index, 'priority'], v), help: '-1000 to 1000, higher wins'),
      GuiRow(
        label: 'When *',
        forId: guiPathId('$path.when'),
        problems: diagnosticsAt(_diagnostics, '$path.when'),
        child: GuiTextInput(
          id: guiPathId('$path.when'),
          value: asString(variant['when']) ?? '',
          mode: GuiTextMode.expression,
          label: 'When',
          onChanged: (String v) => _write(<JsonStep>['variants', index, 'when'], v),
        ),
      ),
      DisplayStyleEditor(
        title: 'Style',
        style: asMap(asMap(variant['presentation'])['style']),
        path: '$path.presentation.style',
        diagnostics: _diagnostics,
        note: 'Overrides the hologram style while shown.',
        onChanged: (JsonMap? next, String field) => _write(<JsonStep>['variants', index, 'presentation', 'style'], next, coalesce: 'variants.$index.style.$field'),
      ),
      dom.div(classes: 'animus-row', <Widget>[
        Button.secondary(label: 'Edit lines', size: ButtonSize.small, onPressed: () => setState(() {
          _variant = index;
          _page = null;
        })),
        Button.ghost(
          label: 'Remove variant',
          size: ButtonSize.small,
          onPressed: () {
            final int remaining = variants.length - 1;
            setState(() => _variant = remaining == 0 ? null : (index >= remaining ? remaining - 1 : index));
            _write(<JsonStep>['variants'], remaining == 0 ? null : (<Object?>[...variants]..removeAt(index)));
          },
        ),
      ]),
    ]);
  }
}
