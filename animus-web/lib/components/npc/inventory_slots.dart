/// Item rows drawn as inventory slots: the definition's stored `inventory`
/// (a size in whole rows of 9 and items by slot number, read by behaviors
/// and modules; nothing draws it in game) and the items an `animus:hire`
/// costs.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/npc/item_slots.dart';
import '../../logic/npc/npc_appearance.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart';
import '../info_tip.dart';
import '../inspect/inspect_fields.dart';
import 'item_slots.dart';
import '../select/animus_select.dart';
import '../../ui/field.dart';
import 'npc_layout.dart';
import '../../ui/action_row.dart';
import '../../ui/stack.dart';
import '../../ui/problem_text.dart';

const List<String> _stored = <String>['minecraft:arrow', 'minecraft:bread', 'minecraft:emerald'];

List<SlotProblem> _problems(List<Diagnostic> diagnostics, String path) => <SlotProblem>[
  for (final Diagnostic problem in diagnosticsAt(diagnostics, path, nested: true)) (severity: problem.severity.name, message: problem.message),
];

class NpcInventorySlots extends StatelessWidget {
  const NpcInventorySlots({required this.value, required this.onChanged, this.diagnostics = const <Diagnostic>[], super.key});

  /// The `inventory` object, or null.
  final Object? value;

  /// The whole new `inventory`, null to remove it, and [from], the object
  /// the slot editor worked it out from when that is ahead of [value].
  final void Function(Object? value, {Object? from}) onChanged;
  final List<Diagnostic> diagnostics;

  @override
  Widget build(BuildContext context) {
    if (value == null) {
      return ActionRow(
        label: 'Inventory',
        leading: <Widget>[mutedText('No stored items.')],
        secondary: <RowAction>[RowAction('Add an inventory', () => onChanged(<String, Object?>{'size': 9, 'slots': <String, Object?>{}}))],
      );
    }
    final JsonMap inventory = asMap(value);
    final JsonMap slots = asMap(inventory['slots']);
    final int size = asInt(inventory['size']) ?? inventoryColumns;
    final List<int> outside = slotsOutside(inventory);
    const String sizeId = 'animus-npc-inventory-size';
    return dom.div(
      classes: 'animus-npc-inventory',
      styles: stackStyles(),
      attributes: const <String, String>{'role': 'group', 'aria-label': 'Inventory'},
      <Widget>[
        Field(
          label: 'Size',
          forId: sizeId,
          help: 'Slots, in whole rows of 9.',
          problems: diagnosticsAt(diagnostics, r'$.inventory.size'),
          child: AnimusSelect.values(
            id: sizeId,
            value: '$size',
            values: <String>[for (final int option in inventorySizes) '$option'],
            labels: <String, String>{for (final int option in inventorySizes) '$option': '$option slots, ${option ~/ 9} row${option == 9 ? '' : 's'}'},
            onChanged: (String? next) => onChanged(<String, Object?>{...inventory, 'size': int.parse(next ?? '9')}),
          ),
        ),
        // In the field column, under Size.
        Field(
          label: 'Slots',
          child: ItemSlots(
            id: 'animus-npc-inventory',
            label: 'Inventory slots',
            tip: 'An item id and its stack count. Drag a slot onto another to trade them; right click empties one.',
            cells: <ItemSlotCell>[
              for (final int slot in inventoryGrid(inventory))
                ItemSlotCell(
                  key: '$slot',
                  column: slot % inventoryColumns,
                  row: slot ~/ inventoryColumns,
                  name: 'Slot $slot',
                  fieldLabel: 'Slot $slot item',
                  item: slots['$slot'] is Map<String, Object?> ? asMap(slots['$slot']) : null,
                  placeholder: 'minecraft:bread',
                  preferred: _stored,
                  problems: <SlotProblem>[
                    if (outside.contains(slot)) (severity: 'error', message: 'Slot $slot is past the size of $size; raise the size or empty it.'),
                    ..._problems(diagnostics, childPath(r'$.inventory.slots', '$slot')),
                  ],
                ),
            ],
            onPut: (String key, JsonMap? item, JsonMap? seen) => onChanged(putInventoryItem(inventory, int.parse(key), item), from: putInventoryItem(inventory, int.parse(key), seen)),
            onMove: (String from, String to) => onChanged(swapInventorySlots(inventory, int.parse(from), int.parse(to))),
          ),
        ),
        if (outside.isNotEmpty)
          problemText('Slot${outside.length == 1 ? '' : 's'} ${outside.join(', ')} ${outside.length == 1 ? 'is' : 'are'} past the size of $size.'),
        ActionRow(label: 'Inventory', secondary: <RowAction>[RowAction('Remove inventory', () => onChanged(null))]),
      ],
    );
  }
}

/// The `animus:hire` module: its fields from the schema, and `items` as a
/// row of six slots.
class HireModuleFields extends StatelessWidget {
  const HireModuleFields({
    required this.schema,
    required this.node,
    required this.value,
    required this.path,
    required this.onChanged,
    this.diagnostics = const <Diagnostic>[],
    super.key,
  });

  final SchemaView schema;
  final Map<String, Object?> node;
  final JsonMap value;
  final String path;

  /// The new module value, and [from], the value the item slots worked it
  /// out from when that is ahead of [value].
  final void Function(JsonMap value, {JsonMap? from}) onChanged;
  final List<Diagnostic> diagnostics;

  @override
  Widget build(BuildContext context) {
    final List<Object?> items = asList(value['items']);
    final String itemsPath = childPath(path, 'items');
    JsonMap withItems(List<Object?> next) => asMap(writeIn(value, <JsonStep>['items'], next.isEmpty ? null : next));
    void putItems(List<Object?> next, {List<Object?>? from}) => onChanged(withItems(next), from: from == null ? null : withItems(from));
    return fieldStack(<Widget>[
      InspectObject(schema: schema, node: node, value: value, path: path, diagnostics: diagnostics, skip: const <String>{'items'}, onChanged: onChanged),
      dom.div(classes: 'animus-hire-items', styles: stackStyles(), <Widget>[
        withTip(const Text('Items', size: FontSize.sm, weight: FontWeight.w500), 'Items one hire costs, at most $hireItemLimit. They fill from the left.'),
        ItemSlots(
          id: 'animus-hire-items',
          label: 'Hire items',
          tip: 'An item id and its stack count.',
          cells: <ItemSlotCell>[
            for (int index = 0; index < hireItemLimit; index++)
              ItemSlotCell(
                key: '$index',
                column: index,
                row: 0,
                name: 'Item ${index + 1}',
                fieldLabel: 'Hire item ${index + 1}',
                item: index < items.length && items[index] is Map<String, Object?> ? asMap(items[index]) : null,
                placeholder: 'minecraft:emerald',
                preferred: const <String>['minecraft:emerald', 'minecraft:gold_ingot', 'minecraft:diamond', 'minecraft:bread'],
                problems: _problems(diagnostics, indexPath(itemsPath, index)),
              ),
          ],
          // The list has no gaps: an empty slot edits the next free one.
          selectAs: (String key) => int.parse(key) > items.length ? '${items.length}' : key,
          onPut: (String key, JsonMap? item, JsonMap? seen) => putItems(putHireItem(items, int.parse(key), item), from: putHireItem(items, int.parse(key), seen)),
          onMove: (String from, String to) => putItems(moveHireItem(items, int.parse(from), int.parse(to))),
        ),
        for (final Diagnostic problem in diagnosticsAt(diagnostics, itemsPath))
          problemText(problem.message, severity: problem.severity),
      ]),
    ]);
  }
}
