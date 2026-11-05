/// The chest GUI editor: the window painted like the game's, a mask brush,
/// and inspectors for the window, the selected slot, keys, the list,
/// variants and named action lists. Edits write the document's JSON.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_expression.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_layout.dart';
import '../../model/animus_document.dart';
import 'actions_editor.dart';
import 'chest_window.dart';
import 'component_editor.dart';
import 'gui_fields.dart';

const List<String> chestResolutions = <String>['9x1', '9x2', '9x3', '9x4', '9x5', '9x6', '5x1', '3x3'];

/// The Preview: the painted window for the chosen variant.
class InventoryPreview extends StatelessWidget {
  const InventoryPreview({required this.document, this.variant, super.key});

  final AnimusDocument document;
  final int? variant;

  @override
  Widget build(BuildContext context) {
    final ChestLayout layout = layoutChest(asMap(document.json), variant: variant);
    return dom.section(classes: 'animus-panel animus-gui-preview', <Widget>[
      ChestWindow(layout: layout, label: 'Chest window ${document.id}'),
      const dom.p(classes: 'animus-muted', <Widget>[
        Component.text('Sample viewer: Steve at 12:00. Values only the server knows show as ?.'),
      ]),
    ]);
  }
}

class InventoryEditor extends StatefulWidget {
  const InventoryEditor({required this.document, required this.onJson, super.key});

  final AnimusDocument document;
  final JsonEdit onJson;

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
      component.onJson(writeIn(_doc, steps, value), coalesce: coalesce ?? steps.join('.'));

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

  void _paint(int slot, ChestLayout layout) {
    final String brush = _brush!;
    final List<String> mask = _mask(layout.width, layout.rows);
    final int row = slot ~/ layout.width, column = slot % layout.width;
    final String line = mask[row];
    mask[row] = line.replaceRange(column, column + 1, brush);
    final List<JsonStep> owner = chestFieldOwner(_doc, _variant, 'mask');
    _write(<JsonStep>[...owner, 'mask'], mask, coalesce: 'mask-paint');
  }

  @override
  Widget build(BuildContext context) {
    final JsonMap doc = _doc;
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
            options: <String>[for (int i = 0; i < variants.length; i++) '$i'],
            labels: <String, String>{for (int i = 0; i < variants.length; i++) '$i': 'Variant ${asString(asMap(variants[i])['id']) ?? i}'},
            onChanged: (String? value) => setState(() => _variant = value == null ? null : int.parse(value)),
          ),
          GuiSelect(
            value: _brush,
            allowNone: true,
            noneLabel: 'Select slots',
            options: <String>[...keys.keys, ' '],
            labels: <String, String>{for (final String key in keys.keys) key: 'Paint key $key', ' ': 'Paint empty'},
            onChanged: (String? value) => setState(() => _brush = value),
          ),
        ]),
        ChestWindow(
          layout: layout,
          selected: _slot,
          showMask: true,
          label: 'Chest window ${component.document.id}',
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
        dom.p(classes: 'animus-muted', <Widget>[
          Component.text(_brush == null
              ? 'Click a slot to edit it. Letters mark mask keys; numbers mark list cells.'
              : 'Click slots to paint ${_brush == ' ' ? 'empty cells' : 'key $_brush'} into the mask.'),
        ]),
      ]),
      dom.section(classes: 'animus-panel animus-gui-inspector', <Widget>[
        dom.div(classes: 'animus-segmented animus-gui-tabs', attributes: const <String, String>{'role': 'tablist'}, <Widget>[
          for (final _Tab tab in _Tab.values)
            Button(
              label: switch (tab) {
                _Tab.slot => _slot == null ? 'Slot' : 'Slot $_slot',
                _Tab.window => 'Window',
                _Tab.keys => 'Keys',
                _Tab.list => 'List',
                _Tab.variants => 'Variants',
                _Tab.actions => 'Named actions',
              },
              size: ButtonSize.small,
              variant: tab == _tab ? ButtonVariant.primary : ButtonVariant.ghost,
              attributes: <String, String>{'role': 'tab', 'aria-selected': '${tab == _tab}'},
              onPressed: () => setState(() => _tab = tab),
            ),
        ]),
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
      ChestCellSource.mask => 'Drawn by mask key ${cell.maskChar}. Editing it changes every slot with that key.',
      ChestCellSource.slot => 'An explicit slot; it draws over the mask.',
      ChestCellSource.list => 'A list cell (entry ${cell.label}); edit the list template.',
      _ => cell.maskChar == null ? 'Empty.' : 'Mask character ${cell.maskChar} has no key.',
    };
    final List<Widget> controls = <Widget>[
      dom.p(classes: 'animus-muted', <Widget>[Component.text(where)]),
      dom.div(classes: 'animus-row', <Widget>[
        if (cell.source != ChestCellSource.slot && cell.source != ChestCellSource.list)
          Button.secondary(
            label: 'Give this slot its own component',
            size: ButtonSize.small,
            onPressed: () => _write(
              <JsonStep>[...slotsOwner, '$slot'],
              definition == null
                  ? <String, Object?>{'type': 'decoration', 'icon': <String, Object?>{'type': 'item', 'item': 'minecraft:stone'}}
                  : deepCopy(readIn(_doc, definition)),
            ),
          ),
        if (cell.source == ChestCellSource.slot)
          Button.ghost(label: 'Remove the explicit slot', size: ButtonSize.small, onPressed: () => _write(<JsonStep>[...slotsOwner, '$slot'], null)),
      ]),
    ];
    if (definition == null) {
      return dom.div(classes: 'animus-gui-fields', <Widget>[
        ...controls,
        if (keys.isNotEmpty)
          GuiRow(
            label: 'Mask key',
            child: GuiSelect(
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
    return dom.div(classes: 'animus-gui-fields', <Widget>[
      GuiRow(
        label: _variant == null ? 'Title' : 'Title (variant)',
        problems: diagnosticsAt(_diagnostics, formatPath(<JsonStep>[...titleOwner, 'title'])),
        child: GuiTextInput(
          value: asString(readIn(doc, <JsonStep>[...titleOwner, 'title'])) ?? '',
          mode: GuiTextMode.template,
          label: 'Title',
          onChanged: (String value) => _write(<JsonStep>[...titleOwner, 'title'], value.isEmpty ? null : value),
        ),
      ),
      GuiRow(
        label: 'Size',
        help: '5x1 (hopper) and 3x3 (dropper) do not open on mod servers.',
        problems: diagnosticsAt(_diagnostics, r'$.resolution'),
        child: GuiSelect(value: asString(doc['resolution']) ?? '9x3', options: chestResolutions, onChanged: (String? value) => _write(<JsonStep>['resolution'], value)),
      ),
      GuiRow(
        label: 'Mask',
        help: 'One row per window row; each character draws the key with that name, a space is empty.',
        problems: diagnosticsAt(_diagnostics, formatPath(<JsonStep>[...chestFieldOwner(doc, _variant, 'mask'), 'mask']), nested: true),
        child: GuiLinesEditor(
          lines: stringList(readIn(doc, <JsonStep>[...chestFieldOwner(doc, _variant, 'mask'), 'mask'])),
          mode: GuiTextMode.plain,
          addLabel: 'Add row',
          onChanged: (List<String> rows) => _write(<JsonStep>[...chestFieldOwner(doc, _variant, 'mask'), 'mask'], rows),
        ),
      ),
      GuiRow(
        label: 'Show when',
        help: 'When false, the GUI does not open for that viewer.',
        problems: diagnosticsAt(_diagnostics, r'$.show'),
        child: GuiTextInput(
          value: doc['show'] is String ? doc['show']! as String : '',
          mode: GuiTextMode.expression,
          label: 'Show when',
          onChanged: (String value) => _write(<JsonStep>['show'], value.isEmpty ? null : value),
        ),
      ),
      GuiRow(
        label: 'Teleport',
        child: GuiSwitch(
          value: doc['closeOnTeleport'] != false,
          label: 'Close on teleport',
          onChanged: (bool on) => _write(<JsonStep>['closeOnTeleport'], on),
        ),
      ),
      GuiRow(
        label: 'Title refresh',
        help: 'Ticks between title redraws; 0 turns it off.',
        child: GuiNumberInput(value: asInt(refresh['titleTicks']), label: 'Title refresh ticks', onChanged: (num? v) => _write(<JsonStep>['refresh'], withField(refresh, 'titleTicks', v).isEmpty ? null : withField(refresh, 'titleTicks', v))),
      ),
      GuiRow(
        label: 'Slot refresh',
        child: GuiNumberInput(value: asInt(refresh['slotsTicks']), label: 'Slot refresh ticks', onChanged: (num? v) => _write(<JsonStep>['refresh'], withField(refresh, 'slotsTicks', v).isEmpty ? null : withField(refresh, 'slotsTicks', v))),
      ),
      GuiRow(
        label: 'Revision',
        problems: diagnosticsAt(_diagnostics, r'$.revision'),
        child: GuiNumberInput(value: asInt(doc['revision']), label: 'Revision', onChanged: (num? v) => _write(<JsonStep>['revision'], v)),
      ),
    ]);
  }

  Widget _keys(JsonMap keys) {
    final List<JsonStep> owner = <JsonStep>[...chestFieldOwner(_doc, _variant, 'keys'), 'keys'];
    return dom.div(classes: 'animus-gui-fields', <Widget>[
      for (final MapEntry<String, Object?> entry in keys.entries)
        GuiSection(title: 'Key ${entry.key}', open: false, children: <Widget>[
          GuiRow(
            label: 'Character',
            help: 'One character; renaming updates the mask.',
            child: dom.div(classes: 'animus-row', <Widget>[
              GuiTextInput(
                value: entry.key,
                label: 'Key character',
                onChanged: (String next) {
                  if (next.length != 1 || next == ' ' || keys.containsKey(next)) return;
                  Object? doc = writeIn(_doc, owner, renameKey(keys, entry.key, next));
                  final List<JsonStep> maskOwner = <JsonStep>[...chestFieldOwner(_doc, _variant, 'mask'), 'mask'];
                  doc = writeIn(doc, maskOwner, <String>[for (final String row in stringList(readIn(doc, maskOwner))) row.replaceAll(entry.key, next)]);
                  component.onJson(doc);
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
      Button.secondary(
        label: 'Add key',
        size: ButtonSize.small,
        onPressed: () {
          const String pool = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#@*+=';
          final String char = pool.split('').firstWhere((String c) => !keys.containsKey(c), orElse: () => '?');
          _write(<JsonStep>[...owner, char], <String, Object?>{'type': 'decoration', 'icon': <String, Object?>{'type': 'item', 'item': 'minecraft:stone'}});
        },
      ),
    ]);
  }

  Widget _list(JsonMap doc) {
    final JsonMap list = asMap(doc['list']);
    if (list.isEmpty) {
      return dom.div(classes: 'animus-gui-fields', <Widget>[
        const dom.p(classes: 'animus-muted', <Widget>[
          Component.text('A list fills the slots of one mask character with pages of a template, one per entry of a source expression.'),
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
        help: 'The mask character whose slots the list fills.',
        problems: diagnosticsAt(_diagnostics, r'$.list.area'),
        child: GuiTextInput(value: asString(list['area']) ?? '', label: 'Area', onChanged: (String v) => set('area', v.isEmpty ? null : v.substring(0, 1))),
      ),
      GuiRow(label: 'Variable', help: 'Each entry is readable under this name.', child: GuiTextInput(value: asString(list['var']) ?? '', label: 'Variable', onChanged: (String v) => set('var', v.isEmpty ? null : v))),
      GuiRow(
        label: 'Source',
        help: 'An expression that gives a list.',
        problems: diagnosticsAt(_diagnostics, r'$.list.source'),
        child: GuiTextInput(
          value: list['source'] is String ? list['source']! as String : '',
          mode: GuiTextMode.expression,
          label: 'Source',
          onChanged: (String v) => set('source', v.isEmpty ? null : v),
        ),
      ),
      GuiRow(label: 'Page size', help: 'Defaults to the area.', child: GuiNumberInput(value: asInt(list['pageSize']), label: 'Page size', onChanged: (num? v) => set('pageSize', v))),
      GuiRow(label: 'Refresh', help: 'Ticks between source reads.', child: GuiNumberInput(value: asInt(list['refreshTicks']), label: 'List refresh ticks', onChanged: (num? v) => set('refreshTicks', v))),
      GuiSection(title: 'Template', children: <Widget>[
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
      const dom.p(classes: 'animus-muted', <Widget>[
        Component.text('Page buttons use navigate with mode page and target next or prev; page and pages give the position.'),
      ]),
      Button.ghost(label: 'Remove the list', size: ButtonSize.small, onPressed: () => _write(<JsonStep>['list'], null)),
    ]);
  }

  Widget _variants(JsonMap doc) {
    final JsonList variants = asList(doc['variants']);
    return dom.div(classes: 'animus-gui-fields', <Widget>[
      const dom.p(classes: 'animus-muted', <Widget>[
        Component.text('The highest priority variant whose condition is true replaces the title, mask, keys or slots it names. Nothing merges.'),
      ]),
      for (int index = 0; index < variants.length; index++)
        GuiSection(title: 'Variant ${asString(asMap(variants[index])['id']) ?? index}', open: _variant == index, children: <Widget>[
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
            help: 'Pick what this variant replaces; the window then edits the variant\'s copy.',
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
      Button.secondary(
        label: 'Add variant',
        size: ButtonSize.small,
        onPressed: () => _write(<JsonStep>['variants'], <Object?>[
          ...variants,
          <String, Object?>{'id': 'variant${variants.length + 1}', 'priority': 0, 'when': 'viewer.level > 10', 'presentation': <String, Object?>{'title': asString(doc['title']) ?? ''}},
        ]),
      ),
    ]);
  }

  Widget _actions(JsonMap doc) {
    final JsonMap named = asMap(doc['actions']);
    return dom.div(classes: 'animus-gui-fields', <Widget>[
      const dom.p(classes: 'animus-muted', <Widget>[
        Component.text('Named lists run from any action with call. Calls must exist and must not loop.'),
      ]),
      for (final MapEntry<String, Object?> entry in named.entries)
        GuiSection(title: entry.key, count: asList(entry.value).length, children: <Widget>[
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
    ]);
  }
}
