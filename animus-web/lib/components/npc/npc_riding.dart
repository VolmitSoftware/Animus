/// The Movement tab's Riding and Seat sections: what the NPC rides
/// (`animus:mount`: another NPC, or a real entity near it) and whether
/// players sit on top of it (`animus:seat`). Simple picks NPCs and vehicles
/// by name; Advanced takes any entity type or a UUID, and the seat's
/// permission, stack and empty hand.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/npc/mount_seat.dart';
import '../../logic/npc/npc_appearance.dart' show bodyLabel;
import '../../logic/suggest/completion_sources.dart' show RegistryKind;
import '../../logic/suggest/field_profile.dart';
import '../../logic/suggest/field_profiles.dart' show idProfile;
import '../../model/animus_document.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/mode.dart';
import '../../ui/number_input.dart';
import '../../ui/toggle_choice.dart';
import '../gui/gui_fields.dart';
import '../quest/quest_fields.dart' show QuestNpcs, npcPicker;
import '../select/animus_select.dart';
import '../suggest/autocomplete_input.dart';

/// Problems at [field] of module [key], or at the module itself when null:
/// the schema writes `$.modules["animus:seat"]`, the server `$.modules.animus:seat`.
List<Diagnostic> _at(List<Diagnostic> all, String key, String? field) {
  final String schema = childPath(r'$.modules', key), server = '\$.modules.$key';
  return <Diagnostic>[
    ...diagnosticsAt(all, field == null ? schema : childPath(schema, field)),
    ...diagnosticsAt(all, field == null ? server : '$server.$field'),
  ];
}

/// Problems inside module [key] at a field none of [shown] edits, such as
/// one the server does not know.
List<Diagnostic> _elsewhere(List<Diagnostic> all, String key, Set<String> shown) {
  final String server = '\$.modules.$key.';
  return <Diagnostic>[
    for (final Diagnostic diagnostic in all)
      if (diagnostic.path.replaceAllMapped(RegExp(r'\["([^"]*)"\]'), (Match part) => '.${part[1]}') case final String path
          when path.startsWith(server) && !shown.contains(path.substring(server.length).split(RegExp(r'[.\[]')).first))
        diagnostic,
  ];
}

/// `animus:mount`: nothing, another NPC, or a real entity: the nearest of a
/// type within range, or one entity by UUID.
class MountEditor extends StatelessWidget {
  const MountEditor({required this.value, required this.npcs, required this.onChanged, this.self, this.diagnostics = const <Diagnostic>[], super.key});

  /// The module object, or null.
  final Object? value;

  /// The workspace NPCs by their ids, which a mount names.
  final QuestNpcs npcs;

  /// This NPC's own id, which it cannot ride; null for a trait group.
  final String? self;
  final void Function(Object? value) onChanged;
  final List<Diagnostic> diagnostics;

  @override
  Widget build(BuildContext context) {
    const String vehicleId = 'animus-npc-vehicle', entityId = 'animus-npc-mount-entity', rangeId = 'animus-npc-mount-range';
    final bool advanced = ModeScope.advanced(context);
    final JsonMap module = asMap(value);
    final MountKind kind = mountKindOf(value);
    final String? vehicle = asString(module['vehicle']);
    final String entity = asString(module['entity']) ?? '';
    final String first = npcs.ids.firstWhere((String id) => id != self, orElse: () => '');
    List<Diagnostic> at(String? field) => _at(diagnostics, mountModuleKey, field);
    return dom.div(classes: 'animus-npc-mount', <Widget>[
      Field(
        label: 'Rides',
        problems: <Diagnostic>[...at(null), ..._elsewhere(diagnostics, mountModuleKey, const <String>{'vehicle', 'entity', 'range'})],
        child: ToggleChoice<MountKind>(
          label: 'Rides',
          options: const <ChoiceOption<MountKind>>[
            ChoiceOption<MountKind>(MountKind.none, 'Nothing'),
            ChoiceOption<MountKind>(MountKind.npc, 'Another NPC'),
            ChoiceOption<MountKind>(MountKind.entity, 'Real entity'),
          ],
          value: kind,
          onChanged: (MountKind next) {
            if (next != kind) onChanged(withMountKind(value, next, vehicle: first));
          },
        ),
      ),
      if (kind == MountKind.npc)
        Field(
          label: 'NPC',
          forId: vehicleId,
          tip: 'The NPC this one rides. It follows wherever that NPC goes.',
          docs: Docs.mounts,
          problems: <Diagnostic>[
            ...at('vehicle'),
            if (vehicle != null && vehicle.isNotEmpty && !npcs.idSet.contains(vehicle))
              Diagnostic(DiagnosticSeverity.warning, childPath(childPath(r'$.modules', mountModuleKey), 'vehicle'), 'Not an NPC in this workspace; the server may have it.'),
          ],
          child: npcPicker(
            id: vehicleId,
            label: 'NPC it rides',
            value: vehicle == null || vehicle.isEmpty ? null : vehicle,
            npcs: npcs,
            advanced: advanced,
            exclude: <String>{?self},
            onChanged: (String? id) => onChanged(<String, Object?>{...module, 'vehicle': id ?? ''}),
          ),
        ),
      if (kind == MountKind.entity) ...<Widget>[
        Field(
          label: 'Entity',
          forId: entityId,
          tip: advanced
              ? 'A type such as minecraft:horse rides the nearest one within range; a UUID rides that one entity.'
              : 'It rides the nearest one within range, such as a horse a player left.',
          docs: Docs.mountEntities,
          problems: at('entity'),
          child: advanced
              ? AutocompleteInput(
                  id: entityId,
                  value: entity,
                  label: 'Entity type or UUID',
                  placeholder: 'minecraft:horse',
                  profile: idProfile(const ValueRef.registry(RegistryKind.entity)),
                  onChanged: (String text) => onChanged(withMountEntity(module, text.trim())),
                )
              : AnimusSelect.values(
                  id: entityId,
                  value: entity.isEmpty ? null : entity,
                  values: commonVehicles,
                  labels: <String, String>{
                    for (final String type in <String>[...commonVehicles, if (entity.isNotEmpty) entity]) type: isEntityUuid(type) ? 'One exact entity' : bodyLabel(type),
                  },
                  onChanged: (String? type) => onChanged(withMountEntity(module, type ?? commonVehicles.first)),
                ),
        ),
        if (!isEntityUuid(entity))
          Field(
            label: 'Range',
            forId: rangeId,
            help: 'Blocks, 1 to 32',
            tip: 'How far from the NPC to look for one. 8 blocks by default.',
            problems: at('range'),
            child: NumberInput(
              digits: 4,
              id: rangeId,
              value: asDouble(module['range']),
              integer: false,
              label: 'Range',
              placeholder: '8',
              onChanged: (num? range) => onChanged(<String, Object?>{
                for (final MapEntry<String, Object?> entry in module.entries)
                  if (entry.key != 'range') entry.key: entry.value,
                'range': ?range,
              }),
            ),
          ),
      ],
      if (value != null)
        Field(
          label: 'Riding now',
          tip: 'Off keeps the setting but the NPC walks on its own.',
          child: ArcaneToggleSwitch(value: module['enabled'] != false, ariaLabel: 'Riding now', onChanged: (bool on) => onChanged(<String, Object?>{...module, 'enabled': on}), size: ComponentSize.sm),
        ),
    ]);
  }
}

/// `animus:seat`: players sit on top of the NPC with a click and sneak to
/// get off. Without the module the server's seats setting decides.
class SeatEditor extends StatelessWidget {
  const SeatEditor({required this.value, required this.riding, required this.onChanged, this.diagnostics = const <Diagnostic>[], super.key});

  /// The module object, or null.
  final Object? value;

  /// Whether players ride this NPC (`animus:riding`), which takes a plain right-click.
  final bool riding;
  final void Function(Object? value) onChanged;
  final List<Diagnostic> diagnostics;

  @override
  Widget build(BuildContext context) {
    const String permissionId = 'animus-npc-seat-permission', stackId = 'animus-npc-seat-stack';
    final bool advanced = ModeScope.advanced(context);
    final JsonMap seat = asMap(value);
    List<Diagnostic> at(String? field) => _at(diagnostics, seatModuleKey, field);
    void put(String field, Object? next) => onChanged(<String, Object?>{
          for (final MapEntry<String, Object?> entry in seat.entries)
            if (entry.key != field) entry.key: entry.value,
          field: ?next,
        });
    return dom.div(classes: 'animus-npc-seat', <Widget>[
      Field(
        label: 'Players sit',
        hint: value == null ? 'Server setting decides' : null,
        problems: <Diagnostic>[...at(null), ..._elsewhere(diagnostics, seatModuleKey, const <String>{'click', 'permission', 'stack', 'emptyHand'})],
        child: ArcaneToggleSwitch(
          value: seatOn(value),
          ariaLabel: 'Players sit',
          onChanged: (bool on) => onChanged(seatSwitched(value, on: on, riding: riding)),
          size: ComponentSize.sm,
        ),
      ),
      if (value != null) ...<Widget>[
        Field(
          label: 'Click',
          tip: 'Sneak right-click leaves a plain right-click for riding, talking and shops.',
          problems: at('click'),
          child: ToggleChoice<String>(
            label: 'Click',
            options: const <ChoiceOption<String>>[
              ChoiceOption<String>('right', 'Right-click'),
              ChoiceOption<String>('sneak-right', 'Sneak right-click'),
            ],
            value: asString(seat['click']) == 'sneak-right' ? 'sneak-right' : 'right',
            onChanged: (String click) => put('click', click == 'right' ? null : click),
          ),
        ),
        if (advanced || seat.containsKey('permission'))
          Field(
            label: 'Permission',
            forId: permissionId,
            tip: 'The permission a player needs to sit. animus.seat by default.',
            problems: at('permission'),
            child: TextInput(
              id: permissionId,
              size: controlSize(DensityScope.of(context)),
              value: asString(seat['permission']) ?? '',
              placeholder: 'animus.seat',
              onChanged: (String text) => put('permission', text.trim().isEmpty ? null : text.trim()),
            ),
          ),
        if (advanced || seat.containsKey('stack'))
          Field(
            label: 'Stack',
            forId: stackId,
            help: 'Players, 1 to 8',
            tip: 'How many players can sit, each on the one below. 4 by default.',
            problems: at('stack'),
            child: NumberInput(digits: 2, id: stackId, value: asInt(seat['stack']), label: 'Seat stack', placeholder: '4', onChanged: (num? stack) => put('stack', stack?.toInt())),
          ),
        if (advanced || seat.containsKey('emptyHand'))
          Field(
            label: 'Empty hand',
            tip: 'Only a player with nothing in the main hand sits. On by default.',
            problems: at('emptyHand'),
            child: ArcaneToggleSwitch(
              value: seat['emptyHand'] != false,
              ariaLabel: 'Empty hand only',
              onChanged: (bool on) => put('emptyHand', on ? null : false),
              size: ComponentSize.sm,
            ),
          ),
      ],
    ]);
  }
}
