/// The chest GUI page. The window, painted like the game's, is the editor:
/// a click picks a slot and its form opens in the Slot tab; a key in Paint
/// draws that key over every slot a press and drag crosses. The Window tab
/// holds the title, size, mask and when it shows, then the keys, the list,
/// variants and named action lists as sections.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_expression.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_layout.dart';
import '../../logic/gui/gui_text.dart';
import '../../logic/gui/gui_validation.dart';
import '../../logic/plural.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/badge.dart';
import '../../ui/menu.dart';
import '../../ui/section.dart';
import '../../ui/stage_frame.dart';
import '../../ui/tabs.dart';
import '../../ui/toggle_choice.dart';
import '../../logic/item_ids.dart' show itemName;
import 'actions_editor.dart';
import 'chest_window.dart';
import 'component_editor.dart';
import 'gui_fields.dart';
import 'gui_rows.dart';
import 'gui_split.dart';
import 'presets_editor.dart';
import '../select/animus_select.dart';
import '../suggest/autocomplete_input.dart';
import '../../ui/field.dart';
import '../../logic/suggest/field_profile.dart';
import '../../ui/number_input.dart';
import 'gui_stack.dart';
import '../../ui/stack.dart';

const List<String> chestResolutions = <String>['9x1', '9x2', '9x3', '9x4', '9x5', '9x6', '5x1', '3x3'];

/// Letters a new key takes, in order.
const String _keyPool = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#@*+=';

/// What each mask letter of [layout] draws, in mask order: the letter and a
/// plain name (the key's name or item, "List" for the list area, "No key"
/// for a letter without one).
List<(String, String)> chestLegend(ChestLayout layout) {
  final List<String> order = <String>[];
  final Map<String, String> seen = <String, String>{};
  for (final ChestCell cell in layout.cells) {
    final String? char = cell.maskChar;
    if (char == null || char == ' ') continue;
    if (!order.contains(char)) order.add(char);
    // An own slot drawn over the mask does not say what its letter draws.
    if (seen.containsKey(char) || cell.source == ChestCellSource.slot) continue;
    seen[char] = switch (cell.source) {
      ChestCellSource.list || ChestCellSource.entry => 'List',
      ChestCellSource.mask => _iconName(cell.icon, char),
      _ => 'No key',
    };
  }
  return <(String, String)>[for (final String char in order) (char, seen[char] ?? 'Own slots only')];
}

/// A mask letter as a key cap, in the legend and on key rows.
Widget maskLetter(String char) => ArcaneKbd(char, size: ComponentSize.sm);

/// A key's plain name: its icon's name, else its item.
String _iconName(JsonMap icon, String char) {
  final String name = guiPlainText(asString(icon['name']) ?? '').trim();
  if (name.isNotEmpty) return name;
  final String? item = asString(icon['type']) == 'playerHead' ? 'minecraft:player_head' : asString(icon['item']);
  return item == null || item.contains('{{') ? 'Key $char' : itemName(item);
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

class _InventoryEditorState extends State<InventoryEditor> {
  int? _slot = 0;
  int? _variant;
  String? _brush;
  int _tab = 0;

  /// The key or variant whose form is open in the Window tab.
  String? _openKey;
  int? _openVariant;

  /// The mask a brush stroke has written, until the document shows it: a
  /// fast drag paints again before the last write comes back.
  List<String>? _stroke;

  JsonMap get _doc => asMap(component.document.json);
  List<Diagnostic> get _diagnostics => component.document.diagnostics;

  void _write(List<JsonStep> steps, Object? value, {String? coalesce}) => component.onJson(writeAt(_doc, steps, value), coalesce: coalesce ?? steps.join('.'));

  List<String> get _namedLists => asMap(_doc['actions']).keys.toList();

  ExpressionNames get _listNames => ExpressionNames.standard.withLocal(asString(asMap(_doc['list'])['var']) ?? 'entry');

  List<JsonStep> _owner(String field) => <JsonStep>[...chestFieldOwner(_doc, _variant, field), field];

  /// Mask rows of the shown presentation, padded to the window.
  List<String> _mask(int width, int rows) {
    final JsonList raw = asList(readIn(_doc, _owner('mask')));
    return <String>[for (int row = 0; row < rows; row++) (row < raw.length && raw[row] is String ? raw[row]! as String : '').padRight(width).substring(0, width)];
  }

  void _paint(int slot, ChestLayout layout, String paint) {
    final List<String> mask = <String>[...(_stroke ?? _mask(layout.width, layout.rows))];
    final int row = slot ~/ layout.width, column = slot % layout.width;
    mask[row] = mask[row].replaceRange(column, column + 1, paint);
    _stroke = mask;
    _write(_owner('mask'), mask, coalesce: 'mask-paint');
  }

  @override
  Widget build(BuildContext context) {
    final JsonMap doc = _doc;
    final List<String>? stroke = _stroke;
    if (stroke != null && stringList(readIn(doc, _owner('mask'))).join('\n') == stroke.join('\n')) _stroke = null;
    final ChestLayout layout = layoutChest(doc, variant: _variant);
    final JsonMap keys = asMap(readIn(doc, _owner('keys')));
    return GuiSplit(
      label: 'Chest GUI',
      previewFirst: true,
      form: <Widget>[
        PresetFix(document: component.document, presets: component.presets, kind: DocumentKind.inventory, onJson: component.onJson),
        AppTabs(label: 'Chest GUI', tabs: <String>[_slot == null ? 'Slot' : slotLabel(_slot!), 'Window'], selected: _tab, onSelect: (int index) => setState(() => _tab = index)),
        if (_tab == 0) _slotTab(layout, keys) else _windowTab(doc, keys),
      ],
      preview: (bool band) => _stage(doc, layout, keys, band),
    );
  }

  /// The window is the editor, so it is never folded into a band; in the
  /// band it scales down to the band's height.
  Widget _stage(JsonMap doc, ChestLayout layout, JsonMap keys, bool band) {
    final JsonList variants = asList(doc['variants']);
    final List<(String, String)> legend = chestLegend(layout);
    final String? brush = _brush;
    return StageFrame(
      title: 'Preview',
      free: true,
      fullscreen: false,
      toggles: <Widget>[
        if (variants.isNotEmpty)
          AnimusSelect.values(
            value: _variant == null ? null : '$_variant',
            allowNone: true,
            noneLabel: 'Base',
            hiddenLabel: 'Variant shown',
            values: <String>[for (int i = 0; i < variants.length; i++) '$i'],
            labels: <String, String>{for (int i = 0; i < variants.length; i++) '$i': _variantName(variants, i)},
            onChanged: (String? value) => setState(() {
              _variant = value == null ? null : int.parse(value);
              _stroke = null;
            }),
          ),
        AnimusSelect.values(
          value: brush,
          allowNone: true,
          noneLabel: 'Pick slots',
          hiddenLabel: 'Paint',
          values: <String>[...keys.keys, ' '],
          labels: <String, String>{for (final String key in keys.keys) key: 'Paint $key', ' ': 'Paint empty'},
          onChanged: (String? value) => setState(() {
            _brush = value;
            _stroke = null;
          }),
        ),
      ],
      status: brush == null ? 'Click a slot to edit it.' : 'Painting ${brush == ' ' ? 'empty slots' : 'key $brush'}: click or drag over slots.',
      statusTip: 'Each letter is the mask key that draws the slot. Sample viewer: Steve at 12:00; server values show as ?.',
      child: previewPane(<Widget>[
        ChestWindow(
          layout: layout,
          selected: _slot,
          showMask: true,
          band: band,
          label: 'Chest window ${component.document.id}',
          onPaint: brush == null ? null : (int slot) => _paint(slot, layout, brush),
          onPaintEnd: () => _stroke = null,
          onSlot: (int slot) {
            if (brush != null) _paint(slot, layout, brush);
            setState(() {
              _slot = slot;
              _tab = 0;
            });
          },
        ),
        if (legend.isNotEmpty)
          dom.div(attributes: const <String, String>{'role': 'list', 'aria-label': 'Mask keys'}, <Widget>[
            Wrap(alignment: WrapAlignment.center, spacing: Gap.md.value, runSpacing: Gap.xs.value, crossAxisAlignment: WrapCrossAlignment.center, children: <Widget>[
              for (final (String char, String name) in legend)
                dom.span(attributes: const <String, String>{'role': 'listitem'}, <Widget>[
                  Row(mainAxisSize: MainAxisSize.min, gapSize: Gap.sm, children: <Widget>[maskLetter(char), mutedText(name)]),
                ]),
            ]),
          ]),
      ]),
    );
  }

  String _variantName(JsonList variants, int index) => 'Variant ${asString(asMap(variants[index])['id']) ?? index + 1}';

  // ---- Slot tab ----

  /// What each key draws, as the legend names it; a key the mask does not
  /// use goes by its icon.
  Map<String, String> _keyNames(ChestLayout layout, JsonMap keys) {
    final Map<String, String> legend = <String, String>{for (final (String char, String name) in chestLegend(layout)) char: name};
    return <String, String>{for (final String key in keys.keys) key: legend[key] ?? _iconName(asMap(asMap(keys[key])['icon']), key)};
  }

  Widget _slotTab(ChestLayout layout, JsonMap keys) {
    final Map<String, String> names = _keyNames(layout, keys);
    final int? slot = _slot;
    if (slot == null || slot >= layout.cells.length) {
      return mutedText('Click a slot in the window.');
    }
    final ChestCell cell = layout.cell(slot);
    final List<JsonStep>? definition = cell.definition;
    final List<JsonStep> slotSteps = <JsonStep>[..._owner('slots'), '$slot'];
    final String where = switch (cell.source) {
      ChestCellSource.mask => 'Key ${cell.maskChar}: every ${cell.maskChar} slot shows this.',
      ChestCellSource.slot => 'Own slot, drawn over the mask.',
      ChestCellSource.list || ChestCellSource.entry => 'List entry. Every entry uses this template.',
      _ => cell.maskChar == null ? 'Empty slot.' : 'Letter ${cell.maskChar} has no key yet.',
    };
    final bool keyed = cell.source != ChestCellSource.slot && cell.source != ChestCellSource.list && cell.source != ChestCellSource.entry;
    return stack(spacing: 16, <Widget>[
      ActionRow(
        dense: true,
        label: 'Slot',
        leading: <Widget>[
          mutedText(where),
        ],
        more: <AppMenuEntry>[if (cell.source == ChestCellSource.slot) AppMenuEntry('Use the mask', () => _write(slotSteps, null))],
        secondary: <RowAction>[
          if (keyed)
            RowAction(
              'Use own slot',
              () => _write(
                slotSteps,
                definition == null
                    ? <String, Object?>{
                        'type': 'decoration',
                        'icon': <String, Object?>{'type': 'item', 'item': 'minecraft:stone'},
                      }
                    : deepCopy(readIn(_doc, definition)),
              ),
            ),
        ],
      ),
      if (keyed)
        Field(
          label: 'Key',
          forId: 'animus-chest-slot-key',
          tip: 'The mask letter for this slot. Every slot with the same letter shows the same thing.',
          child: AnimusSelect.values(
            id: 'animus-chest-slot-key',
            value: cell.maskChar,
            allowNone: true,
            noneLabel: 'Empty',
            values: keys.keys.toList(),
            labels: <String, String>{for (final String key in keys.keys) key: '$key: ${names[key]}'},
            onChanged: (String? key) => _paint(slot, layout, key ?? ' '),
          ),
        ),
      if (definition != null)
        ComponentEditor(
          key: ValueKey<String>(formatPath(definition)),
          component: asMap(readIn(_doc, definition)),
          path: formatPath(definition),
          diagnostics: _diagnostics,
          chest: true,
          names: cell.source == ChestCellSource.list || cell.source == ChestCellSource.entry ? _listNames : ExpressionNames.standard,
          namedLists: _namedLists,
          onChanged: (JsonMap next) => _write(definition, next),
        ),
    ]);
  }

  // ---- Window tab ----

  Widget _windowTab(JsonMap doc, JsonMap keys) {
    final List<JsonStep> titleSteps = _owner('title');
    final List<JsonStep> maskSteps = _owner('mask');
    final List<Diagnostic> d = _diagnostics;
    final (int _, int rows) = chestSize(asString(doc['resolution']));
    final List<String> mask = stringList(readIn(doc, maskSteps));
    final String variant = _variant == null ? '' : ' (${_variantName(asList(doc['variants']), _variant!)})';
    return stack(spacing: 16, <Widget>[
      guiFieldRow(
        'Title$variant',
        formatPath(titleSteps),
        d,
        (String id) => AutocompleteInput(
          id: id,
          value: asString(readIn(doc, titleSteps)) ?? '',
          profile: FieldProfile.guiText,
          preview: windowTitleSample,
          label: 'Title',
          onChanged: (String value) => _write(titleSteps, value.isEmpty ? null : value),
        ),
      ),
      guiFieldRow(
        'Size',
        r'$.resolution',
        d,
        tip: '5x1 and 3x3 do not open on modded servers.',
        (String id) => AnimusSelect.values(id: id, value: asString(doc['resolution']) ?? '9x3', values: chestResolutions, onChanged: (String? value) => _write(const <JsonStep>['resolution'], value)),
      ),
      guiFieldRow(
        'Mask$variant',
        formatPath(maskSteps),
        d,
        nested: true,
        tip: 'One line per row of the window. Each letter draws its key; a space leaves the slot empty.',
        (String id) => AutocompleteInput(
          id: id,
          value: mask.join('\n'),
          multiline: true,
          label: 'Mask',
          code: true,
          onChanged: (String value) => _write(maskSteps, value.isEmpty ? null : value.split('\n')),
          profile: FieldProfile.none,
        ),
      ),
      if (mask.isNotEmpty && mask.length != rows) AppAlert(tone: AlertTone.warning, message: 'The mask has ${countOf(mask.length, 'row')} and the window $rows. They must match.'),
      guiFieldRow(
        'Show when',
        r'$.show',
        d,
        help: 'Empty shows it to everyone.',
        tip: showWhenTip,
        (String id) => AutocompleteInput(
          id: id,
          value: conditionText(doc['show']),
          profile: FieldProfile.guiExpression,
          label: 'Show when',
          onChanged: (String value) => _write(const <JsonStep>['show'], conditionValue(value)),
        ),
      ),
      Field(
        label: 'On teleport',
        child: ArcaneToggleSwitch(value: doc['closeOnTeleport'] != false, label: 'Close the window', onChanged: (bool on) => _write(const <JsonStep>['closeOnTeleport'], on ? null : false), size: ComponentSize.sm),
      ),
      _keysSection(keys, _keyNames(layoutChest(doc, variant: _variant), keys)),
      _listSection(doc),
      _variantsSection(doc),
      NamedActionLists(named: asMap(doc['actions']), diagnostics: _diagnostics, onChanged: (JsonMap? next) => _write(const <JsonStep>['actions'], next)),
      _refreshSection(doc),
    ]);
  }

  Widget _keysSection(JsonMap keys, Map<String, String> names) {
    final List<JsonStep> owner = _owner('keys');
    final String? open = keys.containsKey(_openKey) ? _openKey : null;
    void rename(String from, String to) {
      if (to.length != 1 || to == ' ' || keys.containsKey(to)) return;
      Object? doc = writeIn(_doc, owner, renameKey(keys, from, to));
      final List<JsonStep> maskOwner = _owner('mask');
      doc = writeIn(doc, maskOwner, <String>[for (final String row in stringList(readIn(doc, maskOwner))) row.replaceAll(from, to)]);
      component.onJson(writeAt(_doc, const <JsonStep>[], doc));
      setState(() => _openKey = to);
    }

    final List<String> letters = keys.keys.toList();
    return AppSection(
      title: 'Keys',
      count: keys.length,
      tip: 'Each key is drawn in every mask slot with its letter.',
      docs: Docs.chestGuis,
      children: <Widget>[
        stack(<Widget>[
          if (letters.isNotEmpty)
            OrderedRows(
              label: 'Keys',
              deleteNoun: 'key',
              rows: <OrderedRow>[
                for (final String key in letters)
                  OrderedRow(
                    // By position: renaming the letter keeps the open row and its field.
                    key: '${letters.indexOf(key)}',
                    name: 'key $key',
                    open: open == key,
                    attributes: <String, String>{'data-key': key},
                    line: RowOpener(
                      open: open == key,
                      label: 'Key $key, ${names[key]}',
                      onToggle: () => setState(() => _openKey = open == key ? null : key),
                      children: <Widget>[
                        maskLetter(key),
                        truncatedText(names[key]!),
                        mutedText(componentTypeLabels[asString(asMap(keys[key])['type'])] ?? ''),
                      ],
                      body: open == key
                          ? stack(<Widget>[
                              Field(
                                label: 'Letter',
                                forId: 'animus-chest-key-letter',
                                tip: 'One character. Renaming updates the mask.',
                                child: AutocompleteInput(id: 'animus-chest-key-letter', value: key, label: 'Letter', onChanged: (String next) => rename(key, next), profile: FieldProfile.none),
                              ),
                              ComponentEditor(
                                component: asMap(keys[key]),
                                path: formatPath(<JsonStep>[...owner, key]),
                                diagnostics: _diagnostics,
                                chest: true,
                                namedLists: _namedLists,
                                onChanged: (JsonMap next) => _write(<JsonStep>[...owner, key], next),
                              ),
                            ])
                          : null,
                    ),
                  ),
              ],
              onDelete: (int index) => _write(<JsonStep>[...owner, letters[index]], null),
            ),
          ActionRow(
            dense: true,
            label: 'Keys',
            secondary: <RowAction>[
              RowAction('Add key', () {
                final String char = _keyPool.split('').firstWhere((String c) => !keys.containsKey(c), orElse: () => '?');
                _write(
                  <JsonStep>[...owner, char],
                  <String, Object?>{
                    'type': 'decoration',
                    'icon': <String, Object?>{'type': 'item', 'item': 'minecraft:stone'},
                  },
                );
                setState(() => _openKey = char);
              }, icon: ArcaneIcon.plus()),
            ],
          ),
        ]),
      ],
    );
  }

  Widget _listSection(JsonMap doc) {
    final JsonMap list = asMap(doc['list']);
    const String tip = 'Fills the slots of one mask letter with an entry each, from an expression that gives a list.';
    if (list.isEmpty) {
      return AppSection(
        title: 'List',
        open: false,
        tip: tip,
        docs: Docs.chestGuis,
        children: <Widget>[
          ActionRow(
            dense: true,
            label: 'List',
            secondary: <RowAction>[
              RowAction(
                'Add list',
                () => _write(
                  const <JsonStep>['list'],
                  <String, Object?>{
                    'area': '.',
                    'var': 'entry',
                    'source': "['a', 'b', 'c']",
                    'template': <String, Object?>{
                      'type': 'decoration',
                      'icon': <String, Object?>{'type': 'item', 'item': 'minecraft:paper', 'name': '{{ entry }}'},
                    },
                  },
                ),
                icon: ArcaneIcon.plus(),
              ),
            ],
          ),
        ],
      );
    }
    void set(String key, Object? value) => _write(<JsonStep>['list', key], value, coalesce: 'list.$key');
    final List<Diagnostic> d = _diagnostics;
    return AppSection(
      title: 'List',
      tip: tip,
      docs: Docs.chestGuis,
      children: <Widget>[
        guiFieldRow(
          'Letter',
          r'$.list.area',
          d,
          tip: 'The mask letter whose slots hold the entries.',
          (String id) => AutocompleteInput(id: id, value: asString(list['area']) ?? '', label: 'List letter', onChanged: (String v) => set('area', v.isEmpty ? null : v.substring(0, 1)), profile: FieldProfile.none),
        ),
        guiFieldRow(
          'Entries',
          r'$.list.source',
          d,
          tip: 'An expression that gives a list, such as online players.',
          (String id) => AutocompleteInput(
            id: id,
            value: list['source'] is String ? list['source']! as String : '',
            profile: FieldProfile.guiExpression,
            label: 'Entries',
            onChanged: (String v) => set('source', v.isEmpty ? null : v),
          ),
        ),
        guiFieldRow(
          'Entry name',
          r'$.list.var',
          d,
          tip: 'What the template calls each entry.',
          (String id) => AutocompleteInput(id: id, value: asString(list['var']) ?? '', label: 'Entry name', onChanged: (String v) => set('var', v.isEmpty ? null : v), profile: FieldProfile.none),
        ),
        guiFieldRow(
          'Page size',
          r'$.list.pageSize',
          d,
          help: 'Defaults to the slots of the letter.',
          tip: 'Page buttons navigate with mode page and target next or prev.',
          (String id) => NumberInput(id: id, value: asInt(list['pageSize']), label: 'Page size', onChanged: (num? v) => set('pageSize', v)),
        ),
        guiFieldRow(
          'Refresh',
          r'$.list.refreshTicks',
          d,
          help: 'Ticks between reads.',
          (String id) => NumberInput(id: id, value: asInt(list['refreshTicks']), label: 'List refresh ticks', onChanged: (num? v) => set('refreshTicks', v)),
        ),
        AppSection(
          title: 'Template',
          tip: 'Drawn once per entry.',
          docs: Docs.chestGuis,
          children: <Widget>[
            ComponentEditor(
              component: asMap(list['template']),
              path: r'$.list.template',
              diagnostics: d,
              chest: true,
              names: _listNames,
              namedLists: _namedLists,
              onChanged: (JsonMap next) => set('template', next),
            ),
          ],
        ),
        ActionRow(
          dense: true,
          label: 'List',
          more: <AppMenuEntry>[
            AppMenuEntry('Remove list', () => _write(const <JsonStep>['list'], null), destructive: true),
          ],
        ),
      ],
    );
  }

  Widget _variantsSection(JsonMap doc) {
    final JsonList variants = asList(doc['variants']);
    final int? open = _openVariant != null && _openVariant! < variants.length ? _openVariant : null;
    const List<ChoiceOption<String>> replaceable = <ChoiceOption<String>>[
      ChoiceOption<String>('title', 'Title'),
      ChoiceOption<String>('mask', 'Mask'),
      ChoiceOption<String>('keys', 'Keys'),
      ChoiceOption<String>('slots', 'Slots'),
    ];
    Widget body(int index) {
      final JsonMap variant = asMap(variants[index]);
      final JsonMap presentation = asMap(variant['presentation']);
      final String path = '\$.variants[$index]';
      return stack(<Widget>[
        guiFieldRow(
          'Name',
          '$path.id',
          _diagnostics,
          (String id) => AutocompleteInput(id: id, value: asString(variant['id']) ?? '', label: 'Variant name', onChanged: (String v) => _write(<JsonStep>['variants', index, 'id'], v), profile: FieldProfile.none),
        ),
        guiFieldRow(
          'When',
          '$path.when',
          _diagnostics,
          tip: 'The variant replaces the window while this holds.',
          (String id) => AutocompleteInput(
            id: id,
            value: asString(variant['when']) ?? '',
            profile: FieldProfile.guiExpression,
            label: 'When',
            onChanged: (String v) => _write(<JsonStep>['variants', index, 'when'], v.isEmpty ? null : v),
          ),
        ),
        guiFieldRow(
          'Priority',
          '$path.priority',
          _diagnostics,
          tip: 'The highest priority whose condition holds wins.',
          (String id) => NumberInput(id: id, value: asInt(variant['priority']), label: 'Priority', onChanged: (num? v) => _write(<JsonStep>['variants', index, 'priority'], v)),
        ),
        Field(
          label: 'Replaces',
          tip: 'What this variant draws instead of the base window. Nothing merges.',
          child: ToggleChips<String>(
            label: 'Replaces',
            options: replaceable,
            values: <String>{
              for (final ChoiceOption<String> field in replaceable)
                if (presentation.containsKey(field.value)) field.value,
            },
            onChanged: (Set<String> next) {
              JsonMap out = <String, Object?>{...presentation};
              for (final ChoiceOption<String> field in replaceable) {
                final String key = field.value;
                if (next.contains(key) && !out.containsKey(key)) {
                  out[key] = deepCopy(doc[key] ?? (key == 'title' ? '' : (key == 'mask' ? <Object?>[] : <String, Object?>{})));
                } else if (!next.contains(key)) {
                  out = withField(out, key, null);
                }
              }
              _write(<JsonStep>['variants', index, 'presentation'], out);
            },
          ),
        ),
      ]);
    }

    return AppSection(
      title: 'Variants',
      count: variants.length,
      open: variants.isNotEmpty,
      tip: 'A different title, mask, keys or slots while a condition holds.',
      docs: Docs.chestGuis,
      children: <Widget>[
        stack(<Widget>[
          if (variants.isNotEmpty)
            OrderedRows(
              label: 'Variants',
              deleteNoun: 'variant',
              rows: <OrderedRow>[
                for (int index = 0; index < variants.length; index++)
                  OrderedRow(
                    key: '$index',
                    name: _variantName(variants, index).toLowerCase(),
                    open: open == index,
                    line: RowOpener(
                      open: open == index,
                      onToggle: () => setState(() => _openVariant = open == index ? null : index),
                      children: <Widget>[
                        truncatedText(_variantName(variants, index)),
                        if (_variant == index) const StatusBadge('Shown'),
                      ],
                      body: open == index ? body(index) : null,
                    ),
                    entries: <AppMenuEntry>[
                      AppMenuEntry(
                        _variant == index ? 'Show base window' : 'Show in window',
                        () => setState(() {
                          _variant = _variant == index ? null : index;
                          _stroke = null;
                        }),
                      ),
                    ],
                  ),
              ],
              onDelete: (int index) {
                setState(() {
                  _variant = null;
                  _openVariant = null;
                });
                _write(const <JsonStep>['variants'], variants.length == 1 ? null : (<Object?>[...variants]..removeAt(index)));
              },
            ),
          ActionRow(
            dense: true,
            label: 'Variants',
            secondary: <RowAction>[
              RowAction('Add variant', () {
                _write(
                  const <JsonStep>['variants'],
                  <Object?>[
                    ...variants,
                    <String, Object?>{
                      'id': 'variant${variants.length + 1}',
                      'priority': 0,
                      'when': 'viewer.level > 10',
                      'presentation': <String, Object?>{'title': asString(doc['title']) ?? ''},
                    },
                  ],
                );
                setState(() => _openVariant = variants.length);
              }, icon: ArcaneIcon.plus()),
            ],
          ),
        ]),
      ],
    );
  }

  Widget _refreshSection(JsonMap doc) {
    final JsonMap refresh = asMap(doc['refresh']);
    final JsonMap select = asMap(doc['select']);
    final List<Diagnostic> d = _diagnostics;
    void setRefresh(String key, Object? value) {
      final JsonMap next = withField(refresh, key, value);
      _write(const <JsonStep>['refresh'], next.isEmpty ? null : next, coalesce: 'refresh.$key');
    }

    void setSelect(String key, Object? value) {
      final JsonMap next = withField(select, key, value);
      _write(const <JsonStep>['select'], next.isEmpty ? null : next, coalesce: 'select.$key');
    }

    Widget ticks(String key, String label, String help) => guiFieldRow(
      label,
      '\$.refresh.$key',
      d,
      help: help,
      (String id) => NumberInput(id: id, value: asInt(refresh[key]), label: '$label refresh ticks', onChanged: (num? v) => setRefresh(key, v)),
    );
    return AppSection(
      title: 'Refresh',
      open: refresh.isNotEmpty || select.isNotEmpty,
      tip: 'How often the open window redraws.',
      docs: Docs.chestGuis,
      children: <Widget>[
        guiFieldRow(
          'Redraw',
          r'$.refresh.mode',
          d,
          (String id) => AnimusSelect.values(
            id: id,
            value: asString(refresh['mode']) ?? 'dynamic',
            values: const <String>['dynamic', 'always'],
            labels: const <String, String>{'dynamic': 'What changed', 'always': 'Everything'},
            onChanged: (String? v) => setRefresh('mode', v == 'dynamic' ? null : v),
          ),
        ),
        ticks('titleTicks', 'Title', '0 is off'),
        ticks('slotsTicks', 'Slots', 'Default 20'),
        ticks('conditionsTicks', 'Conditions', 'Ticks between checks'),
        ticks('listTicks', 'List', 'With Gloss only'),
        AppSection(
          title: 'With Gloss',
          open: select.isNotEmpty,
          tip: 'Gloss picks one GUI per opener by these. Animus checks Pick when and ignores priority.',
          docs: Docs.gloss,
          children: <Widget>[
            guiFieldRow(
              'Priority',
              r'$.select.priority',
              d,
              (String id) => NumberInput(id: id, value: asInt(select['priority']), label: 'Select priority', onChanged: (num? v) => setSelect('priority', v)),
            ),
            guiFieldRow(
              'Pick when',
              r'$.select.when',
              d,
              (String id) =>
                  AutocompleteInput(id: id, value: conditionText(select['when']), profile: FieldProfile.guiExpression, label: 'Pick when', onChanged: (String v) => setSelect('when', conditionValue(v))),
            ),
          ],
        ),
      ],
    );
  }
}
