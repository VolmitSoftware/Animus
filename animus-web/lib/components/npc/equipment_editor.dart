/// The definition's `equipment`: one row per slot with an item picker, its
/// inventory icon, count and `components` text. The stage beside the
/// inspector draws the result.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/minecraft_ids.dart';
import '../../mc/npc/npc_look.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart';
import '../inspect/inspect_fields.dart';
import '../mc/item_picker.dart';
import '../mc/mc_item_icons.dart';

/// What each slot holds, for the help line.
const Map<NpcSlot, String> slotHelp = <NpcSlot, String>{
  NpcSlot.mainHand: 'Right hand. Used by melee.fromEquipment.',
  NpcSlot.offHand: 'Held in the left hand.',
  NpcSlot.head: 'Helmet, head or block.',
  NpcSlot.chest: 'Chestplate or elytra.',
  NpcSlot.legs: 'Leggings.',
  NpcSlot.feet: 'Boots.',
  NpcSlot.body: 'Horse armour, llama carpet or wolf armour.',
  NpcSlot.saddle: 'Saddle on horses, pigs and striders.',
};

const List<String> _materials = <String>['leather', 'chainmail', 'iron', 'golden', 'diamond', 'netherite', 'copper'];
const List<String> _toolMaterials = <String>['wooden', 'stone', 'copper', 'iron', 'golden', 'diamond', 'netherite'];
const List<String> _colors = <String>[
  'white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray',
  'light_gray', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black',
];

/// Item ids offered per slot.
List<String> slotSuggestions(NpcSlot slot) => switch (slot) {
      NpcSlot.mainHand || NpcSlot.offHand => <String>[
          for (final String material in _toolMaterials) ...<String>['minecraft:${material}_sword', 'minecraft:${material}_axe', 'minecraft:${material}_pickaxe'],
          'minecraft:bow', 'minecraft:crossbow', 'minecraft:trident', 'minecraft:mace', 'minecraft:shield', 'minecraft:torch',
          'minecraft:fishing_rod', 'minecraft:book', 'minecraft:bread', 'minecraft:emerald', 'minecraft:lantern',
        ],
      NpcSlot.head => <String>[
          for (final String material in _materials) 'minecraft:${material}_helmet',
          'minecraft:turtle_helmet', 'minecraft:carved_pumpkin', 'minecraft:player_head', 'minecraft:skeleton_skull', 'minecraft:zombie_head',
        ],
      NpcSlot.chest => <String>[for (final String material in _materials) 'minecraft:${material}_chestplate', 'minecraft:elytra'],
      NpcSlot.legs => <String>[for (final String material in _materials) 'minecraft:${material}_leggings'],
      NpcSlot.feet => <String>[for (final String material in _materials) 'minecraft:${material}_boots'],
      NpcSlot.body => <String>[
          for (final String material in <String>['leather', 'iron', 'golden', 'diamond', 'netherite', 'copper']) 'minecraft:${material}_horse_armor',
          for (final String color in _colors) 'minecraft:${color}_carpet',
          'minecraft:wolf_armor',
        ],
      NpcSlot.saddle => const <String>['minecraft:saddle'],
    };

/// A label for a slot key: `main_hand` reads "Main hand".
String slotLabel(NpcSlot slot) => '${slot.key[0].toUpperCase()}${slot.key.substring(1).replaceAll('_', ' ')}';

class EquipmentEditor extends StatefulWidget {
  const EquipmentEditor({required this.value, required this.onChanged, this.diagnostics = const <Diagnostic>[], this.bodyType = 'minecraft:player', super.key});

  /// The `equipment` object.
  final Object? value;

  /// The whole new `equipment` object, null to remove it.
  final void Function(Object? value) onChanged;
  final List<Diagnostic> diagnostics;
  final String bodyType;

  @override
  State<EquipmentEditor> createState() => _EquipmentEditorState();
}

class _EquipmentEditorState extends State<EquipmentEditor> {
  @override
  void initState() {
    super.initState();
    McItemIcons.addListener(_refresh);
  }

  @override
  void dispose() {
    McItemIcons.removeListener(_refresh);
    super.dispose();
  }

  void _refresh() {
    if (mounted) setState(() {});
  }

  void _put(NpcSlot slot, Object? item) {
    final JsonMap next = asMap(writeIn(asMap(component.value), <JsonStep>[slot.key], item));
    component.onChanged(next.isEmpty ? null : next);
  }

  @override
  Widget build(BuildContext context) {
    final JsonMap equipment = asMap(component.value);
    final List<NpcSlot> drawn = mcDrawnSlots(component.bodyType);
    return dom.div(classes: 'animus-equipment', attributes: const <String, String>{'role': 'group', 'aria-label': 'Equipment'}, <Widget>[
      if (drawn.isEmpty && !NpcSlot.values.any((NpcSlot slot) => equipment.containsKey(slot.key)))
        dom.p(classes: 'animus-gui-help', <Widget>[Component.text('${component.bodyType} bodies show no equipment.')]),
      for (final NpcSlot slot in NpcSlot.values)
        if (drawn.contains(slot) || equipment.containsKey(slot.key)) _slot(slot, asMap(equipment[slot.key]), drawn: drawn.contains(slot)),
      for (final MapEntry<String, Object?> entry in equipment.entries)
        if (NpcSlot.parse(entry.key) == null)
          GuiRow(
            label: entry.key,
            help: 'Not an equipment slot; kept as written.',
            problems: diagnosticsAt(component.diagnostics, childPath(r'$.equipment', entry.key), nested: true),
            child: KeptJsonEditor(
              value: entry.value,
              label: 'equipment.${entry.key}',
              onChanged: (Object? value) => component.onChanged(asMap(writeIn(equipment, <JsonStep>[entry.key], value))),
            ),
          ),
    ]);
  }

  Widget _slot(NpcSlot slot, JsonMap item, {required bool drawn}) {
    final String id = asString(item['id']) ?? '';
    final String? key = McItemIcons.keyOf(id);
    final String? icon = key == null ? null : McItemIcons.url(key);
    final String path = childPath(r'$.equipment', slot.key);
    final List<Diagnostic> problems = diagnosticsAt(component.diagnostics, path, nested: true);
    final bool badId = id.isNotEmpty && !validMinecraftId(id);
    void field(String name, Object? value) {
      final JsonMap next = asMap(writeIn(item, <JsonStep>[name], value));
      _put(slot, next.isEmpty ? null : next);
    }

    return dom.div(
      key: ValueKey<String>('slot:${slot.key}'),
      classes: 'animus-equipment-slot${id.isEmpty ? ' is-empty' : ''}',
      attributes: <String, String>{'data-slot': slot.key},
      <Widget>[
        dom.div(classes: 'animus-equipment-icon', attributes: const <String, String>{'aria-hidden': 'true'}, <Widget>[
          if (icon != null) dom.img(src: icon, alt: '', classes: 'animus-equipment-img') else dom.span(<Widget>[Component.text(id.isEmpty ? '' : '?')]),
        ]),
        dom.div(classes: 'animus-equipment-fields', <Widget>[
          dom.div(classes: 'animus-equipment-head', <Widget>[
            dom.strong(<Widget>[Component.text(slotLabel(slot))]),
            dom.span(classes: 'animus-gui-help', <Widget>[Component.text(slotHelp[slot]!)]),
          ]),
          dom.div(classes: 'animus-gui-line', <Widget>[
            ItemPicker(
              value: id,
              label: 'equipment.${slot.key}',
              placeholder: slot == NpcSlot.saddle ? 'minecraft:saddle' : 'minecraft:iron_sword',
              preferred: slotSuggestions(slot),
              icon: false,
              onChanged: (String value) => value.trim().isEmpty ? _put(slot, null) : field('id', value.trim()),
            ),
            if (id.isNotEmpty) GuiIconButton(icon: ArcaneIcon.x(size: IconSize.sm), label: 'Clear ${slotLabel(slot).toLowerCase()}', onPressed: () => _put(slot, null)),
          ]),
          if (id.isNotEmpty)
            dom.div(classes: 'animus-gui-line', <Widget>[
              GuiNumberInput(
                value: asInt(item['count']),
                label: 'equipment.${slot.key}.count',
                placeholder: '1',
                onChanged: (num? value) => field('count', value == null || value == 1 ? null : value.toInt()),
              ),
              GuiTextInput(
                value: asString(item['components']) ?? '',
                label: 'equipment.${slot.key}.components',
                placeholder: '[enchantment_glint_override=true]',
                onChanged: (String value) => field('components', value.trim().isEmpty ? null : value.trim()),
              ),
            ]),
          if (!drawn)
            dom.span(classes: 'animus-gui-problem is-warning', <Widget>[
              Component.text('${component.bodyType} bodies do not show this slot. Clear it or change the body type.'),
            ]),
          if (badId) const dom.span(classes: 'animus-gui-problem is-error', <Widget>[Component.text('Use an item id such as iron_sword or minecraft:iron_sword.')]),
          for (final Diagnostic problem in problems)
            dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
        ]),
      ],
    );
  }
}
