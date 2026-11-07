/// The chest GUI editor: the window painted like the game's, a mask brush,
/// and inspectors for the window, the selected slot, keys, the list,
/// variants and named action lists. Edits write the document's JSON.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_expression.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_layout.dart';
import '../../logic/gui/gui_validation.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../info_tip.dart';
import 'actions_editor.dart';
import 'chest_window.dart';
import 'component_editor.dart';
import 'gui_fields.dart';
import 'presets_editor.dart';

const List<String> chestResolutions = <String>['9x1', '9x2', '9x3', '9x4', '9x5', '9x6', '5x1', '3x3'];

/// The Preview: the painted window for the chosen variant. Tapping a slot
/// shows its name and lore, for screens without hover.
class InventoryPreview extends StatefulWidget {
  const InventoryPreview({required this.document, this.variant, super.key});

  final AnimusDocument document;
  final int? variant;

  @override
  State<InventoryPreview> createState() => _InventoryPreviewState();
}

class _InventoryPreviewState extends State<InventoryPreview> {
  int? _slot;

  @override
  Widget build(BuildContext context) {
    final ChestLayout layout = layoutChest(asMap(component.document.json), variant: component.variant);
    return dom.section(classes: 'animus-panel animus-gui-preview', <Widget>[
      ChestWindow(
        layout: layout,
        label: 'Chest window ${component.document.id}',
        selected: _slot,
        pinSelected: true,
        onSlot: (int slot) => setState(() => _slot = _slot == slot ? null : slot),
      ),
      const dom.p(classes: 'animus-muted', <Widget>[
        Component.text('Sample viewer: Steve at 12:00. Server-only values show as ?.'),
      ]),
    ]);
  }
}

class InventoryEditor extends StatefulWidget {
  const InventoryEditor({required this.document, required this.onJson, this.presets, super.key});

  final AnimusDocument document;
  final JsonEdit onJson;

  /// The workspace's presets document, for the preset fix.
  final JsonMap? presets;

  @override
  State<InventoryEditor> createState() => _InventoryEditorState();
}

enum _Tab { slot, window, keys, list, variants, actions }

class _InventoryEditorState extends State<InventoryEditor> {
  int? _slot = 0;
  int? _variant;
  String? _brush;
  _Tab _tab = _Tab.slot;

  JsonMap get _doc => asMap(component.document.json);
  List<Diagnostic> get _diagnostics => component.document.diagnostics;

  void _write(List<JsonStep> steps, Object? value, {String? coalesce}) =>
      component.onJson(writeAt(_doc, steps, value), coalesce: coalesce ?? steps.join('.'));

  List<String> get _namedLists => asMap(_doc['actions']).keys.toList();

  ExpressionNames get _listNames => ExpressionNames.standard.withLocal(asString(asMap(_doc['list'])['var']) ?? 'entry');

  /// Mask rows of the shown presentation, padded to the window.
  List<String> _mask(int width, int rows) {
    final List<JsonStep> owner = chestFieldOwner(_doc, _variant, 'mask');
    final JsonList raw = asList(readIn(_doc, <JsonStep>[...owner, 'mask']));
    return <String>[
      for (int row = 0; row < rows; row++) (row < raw.length && raw[row] is String ? raw[row]! as String : '').padRight(width).substring(0, width),
    ];
  }

  /// The mask a brush stroke has written, until the document shows it: a
  /// fast drag paints again before the last write comes back.
  List<String>? _stroke;

  void _paint(int slot, ChestLayout layout) {
    final String brush = _brush!;
    final List<String> mask = <String>[...(_stroke ?? _mask(layout.width, layout.rows))];
    final int row = slot ~/ layout.width, column = slot % layout.width;
    final String line = mask[row];
    mask[row] = line.replaceRange(column, column + 1, brush);
    final List<JsonStep> owner = chestFieldOwner(_doc, _variant, 'mask');
    _stroke = mask;
    _write(<JsonStep>[...owner, 'mask'], mask, coalesce: 'mask-paint');
  }

  @override
  Widget build(BuildContext context) {
    final JsonMap doc = _doc;
    final List<String>? stroke = _stroke;
    if (stroke != null) {
      final List<String> shown = stringList(readIn(doc, <JsonStep>[...chestFieldOwner(doc, _variant, 'mask'), 'mask']));
      if (shown.join('\n') == stroke.join('\n')) _stroke = null;
    }
    final ChestLayout layout = layoutChest(doc, variant: _variant);
    final JsonList variants = asList(doc['variants']);
    final JsonMap keys = asMap(readIn(doc, <JsonStep>[...chestFieldOwner(doc, _variant, 'keys'), 'keys']));
    return dom.div(classes: 'animus-gui-editor', <Widget>[
      dom.section(classes: 'animus-panel animus-gui-stage', <Widget>[
        dom.div(classes: 'animus-gui-toolbar', <Widget>[
          GuiSelect(
            value: _variant == null ? null : '$_variant',
            allowNone: true,
            noneLabel: 'Base presentation',
            hiddenLabel: 'Presentation shown',
            options: <String>[for (int i = 0; i < variants.length; i++) '$i'],
            labels: <String, String>{for (int i = 0; i < variants.length; i++) '$i': 'Variant ${asString(asMap(variants[i])['id']) ?? i}'},
            onChanged: (String? value) => setState(() {
              _variant = value == null ? null : int.parse(value);
              _stroke = null;
            }),
          ),
          GuiSelect(
            value: _brush,
            allowNone: true,
            noneLabel: 'Select slots',
            hiddenLabel: 'Mask brush',
            options: <String>[...keys.keys, ' '],
            labels: <String, String>{for (final String key in keys.keys) key: 'Paint key $key', ' ': 'Paint empty'},
            onChanged: (String? value) => setState(() {
              _brush = value;
              _stroke = null;
            }),
          ),
        ]),
        ChestWindow(
          layout: layout,
          selected: _slot,
          showMask: true,
          label: 'Chest window ${component.document.id}',
          onPaint: _brush == null ? null : (int slot) => _paint(slot, layout),
          onPaintEnd: () => _stroke = null,
          onSlot: (int slot) {
            if (_brush != null) {
              _paint(slot, layout);
            }
            setState(() {
              _slot = slot;
              _tab = _Tab.slot;
            });
          },
        ),
        if (_brush != null)
          dom.p(classes: 'animus-muted', <Widget>[
            Component.text('Painting ${_brush == ' ' ? 'empty cells' : 'key $_brush'}'),
          ]),
      ]),
      dom.section(classes: 'animus-panel animus-gui-inspector', <Widget>[
        PresetFix(document: component.document, presets: component.presets, kind: DocumentKind.inventory, onJson: component.onJson),
        GuiTabs(
          label: 'Chest GUI sections',
          tabs: <String>[
            for (final _Tab tab in _Tab.values)
              switch (tab) {
                _Tab.slot => _slot == null ? 'Slot' : 'Slot $_slot',
                _Tab.window => 'Window',
                _Tab.keys => 'Keys',
                _Tab.list => 'List',
                _Tab.variants => 'Variants',
                _Tab.actions => 'Named actions',
              },
          ],
          selected: _tab.index,
          onSelect: (int index) => setState(() => _tab = _Tab.values[index]),
        ),
        switch (_tab) {
          _Tab.slot => _slotInspector(layout, keys),
          _Tab.window => _window(doc),
          _Tab.keys => _keys(keys),
          _Tab.list => _list(doc),
          _Tab.variants => _variants(doc),
          _Tab.actions => _actions(doc),
        },
      ]),
    ]);
  }

  Widget _slotInspector(ChestLayout layout, JsonMap keys) {
    final int? slot = _slot;
    if (slot == null || slot >= layout.cells.length) {
      return const dom.p(classes: 'animus-muted', <Widget>[Component.text('Pick a slot in the window.')]);
    }
    final ChestCell cell = layout.cell(slot);
    final List<JsonStep>? definition = cell.definition;
    final List<JsonStep> slotsOwner = <JsonStep>[...chestFieldOwner(_doc, _variant, 'slots'), 'slots'];
    final String where = switch (cell.source) {
      ChestCellSource.mask => 'Drawn by mask key ${cell.maskChar}. Edits apply to every slot with that key.',
      ChestCellSource.slot => 'Explicit slot, drawn over the mask.',
      ChestCellSource.list => 'List cell (entry ${cell.label}). Edit the list template.',
      _ => cell.maskChar == null
          ? 'Empty slot. Add a component or mask key.'
          : 'Mask character ${cell.maskChar} has no key. Add it under Keys.',
    };
    final List<Widget> controls = <Widget>[
      dom.p(classes: 'animus-muted', <Widget>[Component.text(where)]),
      dom.div(classes: 'animus-row', <Widget>[
        if (cell.source != ChestCellSource.slot && cell.source != ChestCellSource.list)
          Button.secondary(
            label: 'Make explicit slot',
            size: ButtonSize.small,
            onPressed: () => _write(
              <JsonStep>[...slotsOwner, '$slot'],
              definition == null
                  ? <String, Object?>{'type': 'decoration', 'icon': <String, Object?>{'type': 'item', 'item': 'minecraft:stone'}}
                  : deepCopy(readIn(_doc, definition)),
            ),
          ),
        if (cell.source == ChestCellSource.slot)
          Button.ghost(label: 'Remove explicit slot', size: ButtonSize.small, onPressed: () => _write(<JsonStep>[...slotsOwner, '$slot'], null)),
      ]),
    ];
    if (definition == null) {
      return dom.div(classes: 'animus-gui-fields', <Widget>[
        ...controls,
        if (keys.isNotEmpty)
          GuiRow(
            label: 'Mask key',
            forId: guiPathId('slot-$slot-key'),
            child: GuiSelect(
              id: guiPathId('slot-$slot-key'),
              value: null,
              noneLabel: 'Put a key here...',
              options: keys.keys.toList(),
              onChanged: (String? key) {
                if (key == null) return;
                setState(() => _brush = key);
                _paint(slot, layout);
                setState(() => _brush = null);
              },
            ),
          ),
      ]);
    }
    final String path = formatPath(definition);
    return dom.div(classes: 'animus-gui-fields', <Widget>[
      ...controls,
      ComponentEditor(
        key: ValueKey<String>(path),
        component: asMap(readIn(_doc, definition)),
        path: path,
        diagnostics: _diagnostics,
        chest: true,
        names: cell.source == ChestCellSource.list ? _listNames : ExpressionNames.standard,
        namedLists: _namedLists,
        onChanged: (JsonMap next) => _write(definition, next),
      ),
    ]);
  }

  Widget _window(JsonMap doc) {
    final List<JsonStep> titleOwner = chestFieldOwner(doc, _variant, 'title');
    final JsonMap refresh = asMap(doc['refresh']);
    final JsonMap select = asMap(doc['select']);
    final List<Diagnostic> d = _diagnostics;
    void setRefresh(String key, Object? value) {
      final JsonMap next = withField(refresh, key, value);
      _write(<JsonStep>['refresh'], next.isEmpty ? null : next, coalesce: 'refresh.$key');
    }

    void setSelect(String key, Object? value) {
      final JsonMap next = withField(select, key, value);
      _write(<JsonStep>['select'], next.isEmpty ? null : next, coalesce: 'select.$key');
    }

    final String titlePath = formatPath(<JsonStep>[...titleOwner, 'title']);
    final List<JsonStep> maskSteps = <JsonStep>[...chestFieldOwner(doc, _variant, 'mask'), 'mask'];
    final (int _, int rows) = chestSize(asString(doc['resolution']));
    final int maskRows = stringList(readIn(doc, maskSteps)).length;
    return dom.div(classes: 'animus-gui-fields', <Widget>[
      guiFieldRow(_variant == null ? 'Title' : 'Title (variant)', titlePath, d, (String id) => GuiTextInput(
            id: id,
            value: asString(readIn(doc, <JsonStep>[...titleOwner, 'title'])) ?? '',
            mode: GuiTextMode.template,
            label: 'Title',
            onChanged: (String value) => _write(<JsonStep>[...titleOwner, 'title'], value.isEmpty ? null : value),
          )),
      guiFieldRow('Size', r'$.resolution', d, help: '5x1 and 3x3 fail on mod servers',
          (String id) => GuiSelect(id: id, value: asString(doc['resolution']) ?? '9x3', options: chestResolutions, onChanged: (String? value) => _write(<JsonStep>['resolution'], value))),
      GuiRow(
        label: 'Mask',
        help: 'One line per row. Each character draws that key; a space is empty.',
        problems: diagnosticsAt(d, formatPath(maskSteps), nested: true),
        child: GuiLinesEditor(
          lines: stringList(readIn(doc, maskSteps)),
          mode: GuiTextMode.plain,
          addLabel: 'Add row',
          onChanged: (List<String> next) => _write(maskSteps, next),
        ),
      ),
      if (maskRows != rows && maskRows > 0)
        dom.p(classes: 'animus-gui-problem is-warning', <Widget>[
          Component.text('Mask has $maskRows rows, window has $rows. They must match.'),
        ]),
      guiFieldRow('Show when', r'$.show', d, help: 'False keeps it closed', (String id) => GuiTextInput(
            id: id,
            value: conditionText(doc['show']),
            mode: GuiTextMode.expression,
            label: 'Show when',
            placeholder: 'always',
            onChanged: (String value) => _write(<JsonStep>['show'], conditionValue(value)),
          )),
      GuiRow(
        label: 'Teleport',
        help: 'On by default.',
        child: GuiSwitch(
          value: doc['closeOnTeleport'] != false,
          label: 'Close on teleport',
          onChanged: (bool on) => _write(<JsonStep>['closeOnTeleport'], on ? null : false),
        ),
      ),
      GuiSection(title: 'Refresh', open: refresh.isNotEmpty, tip: 'How often the open window redraws its title, slots and conditions.', docs: Docs.chestGuis, children: <Widget>[
        guiFieldRow('Mode', r'$.refresh.mode', d, help: 'dynamic redraws changes, always redraws all',
            (String id) => GuiSelect(id: id, value: asString(refresh['mode']), options: const <String>['dynamic', 'always'], allowNone: true, noneLabel: 'dynamic', onChanged: (String? v) => setRefresh('mode', v))),
        guiFieldRow('Title', r'$.refresh.titleTicks', d, help: '0 to 1200 ticks, 0 is off',
            (String id) => GuiNumberInput(id: id, value: asInt(refresh['titleTicks']), label: 'Title refresh ticks', onChanged: (num? v) => setRefresh('titleTicks', v))),
        guiFieldRow('Slots', r'$.refresh.slotsTicks', d, help: '0 to 1200 ticks, default 20',
            (String id) => GuiNumberInput(id: id, value: asInt(refresh['slotsTicks']), label: 'Slot refresh ticks', onChanged: (num? v) => setRefresh('slotsTicks', v))),
        guiFieldRow('Conditions', r'$.refresh.conditionsTicks', d, help: '0 to 1200 ticks between checks',
            (String id) => GuiNumberInput(id: id, value: asInt(refresh['conditionsTicks']), label: 'Condition refresh ticks', onChanged: (num? v) => setRefresh('conditionsTicks', v))),
        guiFieldRow('List', r'$.refresh.listTicks', d, help: 'Gloss only',
            (String id) => GuiNumberInput(id: id, value: asInt(refresh['listTicks']), label: 'List refresh ticks', onChanged: (num? v) => setRefresh('listTicks', v))),
      ]),
      GuiSection(title: 'Select', open: select.isNotEmpty, tip: 'Gloss uses these to pick one GUI per opener. Animus checks when and ignores priority.', children: <Widget>[
        guiFieldRow('Priority', r'$.select.priority', d,
            (String id) => GuiNumberInput(id: id, value: asInt(select['priority']), label: 'Select priority', onChanged: (num? v) => setSelect('priority', v))),
        guiFieldRow('When', r'$.select.when', d, (String id) => GuiTextInput(
              id: id,
              value: conditionText(select['when']),
              mode: GuiTextMode.expression,
              label: 'Select when',
              onChanged: (String v) => setSelect('when', conditionValue(v)),
            )),
      ]),
      guiFieldRow('Revision', r'$.revision', d,
          (String id) => GuiNumberInput(id: id, value: asInt(doc['revision']), label: 'Revision', onChanged: (num? v) => _write(<JsonStep>['revision'], v))),
    ]);
  }

  Widget _keys(JsonMap keys) {
    final List<JsonStep> owner = <JsonStep>[...chestFieldOwner(_doc, _variant, 'keys'), 'keys'];
    return dom.div(classes: 'animus-gui-fields', <Widget>[
      Button.secondary(
        label: 'Add key',
        size: ButtonSize.small,
        onPressed: () {
          const String pool = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#@*+=';
          final String char = pool.split('').firstWhere((String c) => !keys.containsKey(c), orElse: () => '?');
          _write(<JsonStep>[...owner, char], <String, Object?>{'type': 'decoration', 'icon': <String, Object?>{'type': 'item', 'item': 'minecraft:stone'}});
        },
      ),
      for (final MapEntry<String, Object?> entry in keys.entries)
        GuiSection(title: 'Key ${entry.key}', open: false, tip: 'The component drawn in every mask cell with this character.', docs: Docs.chestGuis, children: <Widget>[
          GuiRow(
            label: 'Character',
            help: 'One character, updates the mask',
            child: dom.div(classes: 'animus-row', <Widget>[
              GuiTextInput(
                value: entry.key,
                label: 'Key character',
                onChanged: (String next) {
                  if (next.length != 1 || next == ' ' || keys.containsKey(next)) return;
                  Object? doc = writeIn(_doc, owner, renameKey(keys, entry.key, next));
                  final List<JsonStep> maskOwner = <JsonStep>[...chestFieldOwner(_doc, _variant, 'mask'), 'mask'];
                  doc = writeIn(doc, maskOwner, <String>[for (final String row in stringList(readIn(doc, maskOwner))) row.replaceAll(entry.key, next)]);
                  component.onJson(writeAt(_doc, const <JsonStep>[], doc));
                },
              ),
              GuiIconButton(icon: ArcaneIcon.trash(size: IconSize.sm), label: 'Remove key ${entry.key}', onPressed: () => _write(<JsonStep>[...owner, entry.key], null)),
            ]),
          ),
          ComponentEditor(
            component: asMap(entry.value),
            path: formatPath(<JsonStep>[...owner, entry.key]),
            diagnostics: _diagnostics,
            chest: true,
            namedLists: _namedLists,
            onChanged: (JsonMap next) => _write(<JsonStep>[...owner, entry.key], next),
          ),
        ]),
    ]);
  }

  Widget _list(JsonMap doc) {
    final JsonMap list = asMap(doc['list']);
    if (list.isEmpty) {
      return dom.div(classes: 'animus-gui-fields', <Widget>[
        const dom.p(classes: 'animus-gui-help', <Widget>[
          InfoTip(tip: 'Fills one mask character\'s slots with a template, one per entry of a source expression.', child: Component.text('About lists')),
        ]),
        Button.secondary(
          label: 'Add a list',
          size: ButtonSize.small,
          onPressed: () => _write(<JsonStep>['list'], <String, Object?>{
            'area': '.',
            'var': 'entry',
            'source': "['a', 'b', 'c']",
            'template': <String, Object?>{'type': 'decoration', 'icon': <String, Object?>{'type': 'item', 'item': 'minecraft:paper', 'name': '{{ entry }}'}},
          }),
        ),
      ]);
    }
    void set(String key, Object? value) => _write(<JsonStep>['list', key], value, coalesce: 'list.$key');
    return dom.div(classes: 'animus-gui-fields', <Widget>[
      GuiRow(
        label: 'Area',
        help: 'Mask character to fill',
        problems: diagnosticsAt(_diagnostics, r'$.list.area'),
        child: GuiTextInput(value: asString(list['area']) ?? '', label: 'Area', onChanged: (String v) => set('area', v.isEmpty ? null : v.substring(0, 1))),
      ),
      GuiRow(label: 'Variable', help: 'Name for each entry', child: GuiTextInput(value: asString(list['var']) ?? '', label: 'Variable', onChanged: (String v) => set('var', v.isEmpty ? null : v))),
      GuiRow(
        label: 'Source',
        help: 'Expression giving a list',
        problems: diagnosticsAt(_diagnostics, r'$.list.source'),
        child: GuiTextInput(
          value: list['source'] is String ? list['source']! as String : '',
          mode: GuiTextMode.expression,
          label: 'Source',
          onChanged: (String v) => set('source', v.isEmpty ? null : v),
        ),
      ),
      GuiRow(
        label: 'Page size',
        help: 'Defaults to the area.',
        tip: 'Page buttons use navigate with mode page and target next or prev. page and pages give the position.',
        child: GuiNumberInput(value: asInt(list['pageSize']), label: 'Page size', onChanged: (num? v) => set('pageSize', v)),
      ),
      GuiRow(label: 'Refresh', help: 'Ticks between source reads.', child: GuiNumberInput(value: asInt(list['refreshTicks']), label: 'List refresh ticks', onChanged: (num? v) => set('refreshTicks', v))),
      GuiSection(title: 'Template', tip: 'The component drawn once per entry, in each cell of the area.', docs: Docs.chestGuis, children: <Widget>[
        ComponentEditor(
          component: asMap(list['template']),
          path: r'$.list.template',
          diagnostics: _diagnostics,
          chest: true,
          names: _listNames,
          namedLists: _namedLists,
          onChanged: (JsonMap next) => set('template', next),
        ),
      ]),
      Button.ghost(label: 'Remove the list', size: ButtonSize.small, onPressed: () => _write(<JsonStep>['list'], null)),
    ]);
  }

  Widget _variants(JsonMap doc) {
    final JsonList variants = asList(doc['variants']);
    return dom.div(classes: 'animus-gui-fields', <Widget>[
      const dom.p(classes: 'animus-gui-help', <Widget>[
        InfoTip(tip: 'The highest-priority variant whose condition holds replaces the title, mask, keys or slots it names. Nothing merges.', child: Component.text('About variants')),
      ]),
      Button.secondary(
        label: 'Add variant',
        size: ButtonSize.small,
        onPressed: () => _write(<JsonStep>['variants'], <Object?>[
          ...variants,
          <String, Object?>{'id': 'variant${variants.length + 1}', 'priority': 0, 'when': 'viewer.level > 10', 'presentation': <String, Object?>{'title': asString(doc['title']) ?? ''}},
        ]),
      ),
      for (int index = 0; index < variants.length; index++)
        GuiSection(title: 'Variant ${asString(asMap(variants[index])['id']) ?? index}', open: _variant == index, tip: 'Replaces the fields it names while When holds. Show in the window previews it.', docs: Docs.chestGuis, children: <Widget>[
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
            label: 'When',
            problems: diagnosticsAt(_diagnostics, '\$.variants[$index].when'),
            child: GuiTextInput(
              value: asString(asMap(variants[index])['when']) ?? '',
              mode: GuiTextMode.expression,
              label: 'When',
              onChanged: (String v) => _write(<JsonStep>['variants', index, 'when'], v.isEmpty ? null : v),
            ),
          ),
          GuiRow(
            label: 'Replaces',
            help: 'Fields this variant overrides',
            child: dom.div(classes: 'animus-row', <Widget>[
              for (final String field in const <String>['title', 'mask', 'keys', 'slots'])
                GuiSwitch(
                  value: asMap(asMap(variants[index])['presentation']).containsKey(field),
                  label: field,
                  onChanged: (bool on) => _write(
                    <JsonStep>['variants', index, 'presentation', field],
                    on ? deepCopy(doc[field] ?? (field == 'title' ? '' : (field == 'mask' ? <Object?>[] : <String, Object?>{}))) : null,
                  ),
                ),
            ]),
          ),
          dom.div(classes: 'animus-row', <Widget>[
            Button.secondary(label: 'Show in the window', size: ButtonSize.small, onPressed: () => setState(() => _variant = index)),
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
    ]);
  }

  Widget _actions(JsonMap doc) {
    final JsonMap named = asMap(doc['actions']);
    return dom.div(classes: 'animus-gui-fields', <Widget>[
      const dom.p(classes: 'animus-gui-help', <Widget>[
        InfoTip(tip: 'Run a named list from any action with call. Calls must exist and must not loop.', child: Component.text('About named actions')),
      ]),
      Button.secondary(
        label: 'Add named list',
        size: ButtonSize.small,
        onPressed: () {
          int n = named.length + 1;
          while (named.containsKey('list$n')) {
            n++;
          }
          _write(<JsonStep>['actions', 'list$n'], <Object?>[]);
        },
      ),
      for (final MapEntry<String, Object?> entry in named.entries)
        GuiSection(title: entry.key, count: asList(entry.value).length, tip: 'Run this list from any action with call.', docs: Docs.guiActions, children: <Widget>[
          GuiRow(
            label: 'Name',
            problems: diagnosticsAt(_diagnostics, childPath(r'$.actions', entry.key)),
            child: dom.div(classes: 'animus-row', <Widget>[
              GuiTextInput(
                value: entry.key,
                label: 'List name',
                onChanged: (String next) {
                  if (!RegExp(r'^[A-Za-z0-9_-]{1,64}$').hasMatch(next) || named.containsKey(next)) return;
                  _write(<JsonStep>['actions'], renameKey(named, entry.key, next));
                },
              ),
              GuiIconButton(
                icon: ArcaneIcon.trash(size: IconSize.sm),
                label: 'Remove list ${entry.key}',
                onPressed: () => _write(<JsonStep>['actions'], named.length == 1 ? null : (<String, Object?>{...named}..remove(entry.key))),
              ),
            ]),
          ),
          ActionsEditor(
            actions: asList(entry.value),
            path: childPath(r'$.actions', entry.key),
            diagnostics: _diagnostics,
            namedLists: _namedLists,
            onChanged: (JsonList next) => _write(<JsonStep>['actions', entry.key], next),
          ),
        ]),
    ]);
  }
}
