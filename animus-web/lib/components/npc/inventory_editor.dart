/// The definition's stored `inventory`: a size in whole rows of 9 and items
/// by slot number. Behaviors and modules read it (combat counts ammunition
/// from it); nothing draws it in game.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/npc/npc_appearance.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart';
import '../inspect/inspect_fields.dart';
import '../mc/item_picker.dart';

class NpcInventoryEditor extends StatelessWidget {
  const NpcInventoryEditor({required this.value, required this.onChanged, this.diagnostics = const <Diagnostic>[], super.key});

  /// The `inventory` object, or null.
  final Object? value;

  /// The whole new `inventory`, null to remove it.
  final void Function(Object? value) onChanged;
  final List<Diagnostic> diagnostics;

  @override
  Widget build(BuildContext context) {
    if (value == null) {
      return dom.div(classes: 'animus-row', <Widget>[
        const dom.span(classes: 'animus-gui-help', <Widget>[Component.text('No stored items.')]),
        Button.secondary(
          label: 'Add an inventory',
          size: ButtonSize.small,
          onPressed: () => onChanged(<String, Object?>{'size': 9, 'slots': <String, Object?>{}}),
        ),
      ]);
    }
    final JsonMap inventory = asMap(value);
    final JsonMap slots = asMap(inventory['slots']);
    final int size = asInt(inventory['size']) ?? 9;
    final List<int> numbers = <int>[for (final String key in slots.keys) ?int.tryParse(key)]..sort();
    final List<int> outside = slotsOutside(inventory);
    void putSlots(JsonMap next) => onChanged(<String, Object?>{...inventory, 'slots': next});
    final int? free = <int>[
      for (int slot = 0; slot < size; slot++)
        if (!slots.containsKey('$slot')) slot,
    ].firstOrNull;
    const String sizeId = 'animus-npc-inventory-size';
    return dom.div(
      classes: 'animus-npc-inventory',
      attributes: const <String, String>{'role': 'group', 'aria-label': 'Inventory'},
      <Widget>[
        InspectRow(
          label: 'Size',
          labelFor: sizeId,
          help: 'Slots, in whole rows of 9.',
          problems: diagnosticsAt(diagnostics, r'$.inventory.size'),
          child: GuiSelect(
            id: sizeId,
            value: '$size',
            options: <String>[for (final int option in inventorySizes) '$option'],
            labels: <String, String>{for (final int option in inventorySizes) '$option': '$option slots, ${option ~/ 9} row${option == 9 ? '' : 's'}'},
            onChanged: (String? next) => onChanged(<String, Object?>{...inventory, 'size': int.parse(next ?? '9')}),
          ),
        ),
        if (numbers.isEmpty) const dom.p(classes: 'animus-muted', <Widget>[Component.text('Empty. Add an item.')]),
        for (final int slot in numbers) _slot(slot, asMap(slots['$slot']), slots, putSlots, outside.contains(slot), size),
        dom.div(classes: 'animus-row', <Widget>[
          Button.ghost(
            label: free == null ? 'Every slot is full' : 'Add an item in slot $free',
            size: ButtonSize.small,
            disabled: free == null,
            onPressed: () => putSlots(<String, Object?>{
              ...slots,
              '$free': <String, Object?>{'id': 'minecraft:stone'},
            }),
          ),
          Button.ghost(label: 'Remove inventory', size: ButtonSize.small, onPressed: () => onChanged(null)),
        ]),
      ],
    );
  }

  Widget _slot(int slot, JsonMap item, JsonMap slots, void Function(JsonMap) putSlots, bool outside, int size) {
    final String path = childPath(r'$.inventory.slots', '$slot');
    void field(String name, Object? next) => putSlots(<String, Object?>{
      ...slots,
      '$slot': writeIn(item, <JsonStep>[name], next),
    });
    return dom.div(
      key: ValueKey<String>('inventory-slot:$slot'),
      classes: 'animus-npc-inventory-slot',
      attributes: <String, String>{'data-slot': '$slot'},
      <Widget>[
        dom.code(classes: 'animus-npc-inventory-number', <Widget>[Component.text('$slot')]),
        ItemPicker(
          value: asString(item['id']) ?? '',
          label: 'Slot $slot item',
          placeholder: 'minecraft:bread',
          preferred: const <String>['minecraft:arrow', 'minecraft:bread', 'minecraft:emerald'],
          onChanged: (String text) => field('id', text.trim()),
        ),
        GuiNumberInput(
          value: asInt(item['count']),
          label: 'Slot $slot count',
          placeholder: '1',
          onChanged: (num? count) => field('count', count == null || count == 1 ? null : count.toInt()),
        ),
        GuiIconButton(
          icon: ArcaneIcon.x(size: IconSize.sm),
          label: 'Empty slot $slot',
          onPressed: () => putSlots(<String, Object?>{
            for (final MapEntry<String, Object?> entry in slots.entries)
              if (entry.key != '$slot') entry.key: entry.value,
          }),
        ),
        if (outside)
          dom.span(classes: 'animus-gui-problem is-error', <Widget>[Component.text('Slot $slot is past the size of $size; raise the size or empty it.')]),
        for (final Diagnostic problem in diagnosticsAt(diagnostics, path, nested: true))
          dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
      ],
    );
  }
}
