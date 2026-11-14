/// An NPC's body `type`, picked by name ("Iron golem") from the bodies the
/// editor draws, grouped as players, mobs and objects. While the NPC wears a
/// skin (its own or one its groups give), a body other than a player asks
/// first: the server refuses a skin on any other body
/// (`DefinitionSpec.activation`), so the change removes the skin in the same
/// undo step, or nothing changes.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../logic/npc/npc_appearance.dart';
import '../../mc/npc/npc_objects.dart';
import '../../mc/rigs/mc_mob_bodies.dart';
import '../../mc/rigs/mc_rigs.dart';
import '../../model/document_kind.dart';
import '../../ui/confirm_dialog.dart';
import '../select/animus_select.dart';
import '../select/picker_scope.dart';

const String _player = 'minecraft:player';

/// The heading each body sits under in the list.
final Map<String, String> bodyGroups = <String, String>{
  _player: 'Players',
  mcMannequin: 'Players',
  for (final String type in mcBodyTypes)
    if (type != _player && type != 'minecraft:armor_stand') type: 'Mobs',
  for (final String type in mcMobBodyTypes) type: 'Mobs',
  'minecraft:armor_stand': 'Objects',
  for (final String type in mcObjectBodyTypes) type: 'Objects',
};

/// Every body in list order: players, then mobs and objects by name.
final List<String> bodyOptions = <String>[
  _player,
  mcMannequin,
  ...(<String>[for (final String type in mcPresentedBodyTypes) if (bodyGroups[type] == 'Mobs') type]..sort((String a, String b) => bodyLabel(a).compareTo(bodyLabel(b)))),
  ...(<String>[for (final String type in mcPresentedBodyTypes) if (bodyGroups[type] == 'Objects') type]..sort((String a, String b) => bodyLabel(a).compareTo(bodyLabel(b)))),
];

final Map<String, String> _labels = <String, String>{for (final String type in bodyOptions) type: bodyLabel(type)};

class BodyTypeField extends StatefulWidget {
  const BodyTypeField({
    required this.value,
    required this.onChanged,
    required this.onChangeWithoutSkin,
    this.id,
    this.skinned = false,
    this.skinGroups = const <String>[],
    this.otherMembers = 0,
    super.key,
  });

  final String value;
  final void Function(String type) onChanged;

  /// Changes the type and removes the skin, as one undo step.
  final void Function(String type) onChangeWithoutSkin;

  /// The select's element id, for a field label's `for`.
  final String? id;

  /// Whether the NPC sets a skin itself.
  final bool skinned;

  /// The groups that give it a skin.
  final List<String> skinGroups;

  /// Other NPCs in [skinGroups], who lose that skin too.
  final int otherMembers;

  @override
  State<BodyTypeField> createState() => _BodyTypeFieldState();
}

class _BodyTypeFieldState extends State<BodyTypeField> {
  /// Raised when a pick is declined, so the select draws the kept body again.
  int _kept = 0;

  String get value => component.value;
  List<String> get skinGroups => component.skinGroups;
  int get otherMembers => component.otherMembers;
  bool get _skinned => component.skinned || skinGroups.isNotEmpty;

  String _detail(BuildContext context) {
    if (skinGroups.isEmpty) return 'Undo brings it back.';
    final List<String> names = <String>[for (final String group in skinGroups) PickerScope.nameIn(context, DocumentKind.group, group)];
    final String named = names.length == 1 ? 'trait group ${names.single}' : 'trait groups ${names.join(', ')}';
    final String others = otherMembers == 0 ? '' : ', which $otherMembers other NPC${otherMembers == 1 ? '' : 's'} use${otherMembers == 1 ? 's' : ''}';
    return 'It also leaves $named$others. Undo brings it back.';
  }

  Future<void> _pick(String? type) async {
    if (type == null || type == value) return;
    if (!_skinned || type == _player) {
      component.onChanged(type);
      return;
    }
    final bool remove = await askToConfirm(
      title: 'Remove the skin?',
      message: 'Only a player body wears a skin. ${_detail(context)}',
      confirmLabel: 'Remove skin',
      destructive: true,
    );
    if (remove) {
      component.onChangeWithoutSkin(type);
    } else if (mounted) {
      setState(() => _kept++);
    }
  }

  @override
  Widget build(BuildContext context) => AnimusSelect.values(
        key: ValueKey<String>('body:$_kept'),
        id: component.id,
        value: value.isEmpty ? _player : value,
        values: bodyOptions,
        labels: <String, String>{..._labels, if (!_labels.containsKey(value) && value.isNotEmpty) value: bodyLabel(value)},
        groups: bodyGroups,
        onChanged: _pick,
      );
}
