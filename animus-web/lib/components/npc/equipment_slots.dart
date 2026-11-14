/// The definition's `equipment`, laid out like the player inventory: armor
/// down the left, mount slots beside it and the hands along the bottom. Only
/// slots the body draws are shown, plus any set slot it does not, flagged.
/// The stage beside the inspector draws the result.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/gui/gui_json.dart';
import '../../logic/npc/item_slots.dart';
import '../../mc/npc/npc_look.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart';
import '../inspect/inspect_fields.dart';
import 'item_slots.dart';
import '../../ui/field.dart';
import 'npc_layout.dart';
import '../../ui/problem_text.dart';

class EquipmentSlots extends StatelessWidget {
  const EquipmentSlots({required this.value, required this.onChanged, this.diagnostics = const <Diagnostic>[], this.bodyType = 'minecraft:player', this.components = false, super.key});

  /// The `equipment` object.
  final Object? value;

  /// The whole new `equipment` object, null to remove it, and [from], the
  /// object the slot editor worked it out from when that is ahead of [value].
  final void Function(Object? value, {Object? from}) onChanged;
  final List<Diagnostic> diagnostics;
  final String bodyType;

  /// Offers each item's components text (Advanced); Simple keeps it as written.
  final bool components;

  @override
  Widget build(BuildContext context) {
    final JsonMap equipment = asMap(value);
    final List<NpcSlot> unsupported = unsupportedEquipmentSlots(bodyType, equipment);
    final List<SlotPlace> places = equipmentPlaces(shownEquipmentSlots(bodyType, equipment));
    return dom.div(classes: 'animus-equipment', styles: stackStyles(), attributes: const <String, String>{'role': 'group', 'aria-label': 'Equipment'}, <Widget>[
      if (places.isEmpty)
        mutedLine(noEquipmentLine(bodyType))
      else
        ItemSlots(
          id: 'animus-equipment',
          label: 'Equipment slots',
          tip: components ? 'An item id, its stack count, and item components in brackets such as [enchantment_glint_override=true].' : 'An item and its stack count.',
          components: components,
          captioned: true,
          cells: <ItemSlotCell>[for (final SlotPlace place in places) _cell(place, equipment, unsupported.contains(place.slot))],
          onPut: (String key, JsonMap? item, JsonMap? seen) =>
              onChanged(putEquipmentItem(equipment, NpcSlot.parse(key)!, item), from: putEquipmentItem(equipment, NpcSlot.parse(key)!, seen)),
          onMove: (String from, String to) => onChanged(swapEquipment(equipment, NpcSlot.parse(from)!, NpcSlot.parse(to)!)),
        ),
      if (unsupported.isNotEmpty)
        problemText(unshownSlotsLine(bodyType, unsupported), severity: DiagnosticSeverity.warning),
      for (final MapEntry<String, Object?> entry in equipment.entries)
        if (NpcSlot.parse(entry.key) == null)
          Field(
            label: entry.key,
            help: 'Not an equipment slot; kept as written.',
            problems: diagnosticsAt(diagnostics, childPath(r'$.equipment', entry.key), nested: true),
            child: KeptJsonEditor(
              value: entry.value,
              label: 'equipment.${entry.key}',
              onChanged: (Object? next) => onChanged(asMap(writeIn(equipment, <JsonStep>[entry.key], next))),
            ),
          ),
    ]);
  }

  ItemSlotCell _cell(SlotPlace place, JsonMap equipment, bool unsupported) {
    final NpcSlot slot = place.slot;
    return ItemSlotCell(
      key: slot.key,
      column: place.column,
      row: place.row,
      name: slotLabel(slot),
      fieldLabel: 'equipment.${slot.key}',
      item: equipment[slot.key] is Map<String, Object?> ? asMap(equipment[slot.key]) : null,
      placeholder: slotPlaceholder(slot, bodyType),
      preferred: slotSuggestions(slot),
      help: slotHelp[slot],
      problems: <SlotProblem>[
        if (unsupported) (severity: 'warning', message: unshownSlotLine(bodyType)),
        for (final Diagnostic problem in diagnosticsAt(diagnostics, childPath(r'$.equipment', slot.key), nested: true))
          (severity: problem.severity.name, message: problem.message),
      ],
    );
  }
}
