/// A player or an NPC picked by the name people know and written as its
/// UUID: players the paired server has seen (`GET /v1/lookups`), NPCs of the
/// workspace and those the server lists. Simple never shows a UUID;
/// Advanced also takes one typed in, for someone the lists do not hold.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../logic/suggest/person_names.dart';
import '../../ui/density.dart';
import '../../ui/mode.dart';
import '../../ui/stack.dart';
import '../suggest/suggest_scope.dart';
import 'animus_select.dart';

class PersonPicker extends StatelessWidget {
  const PersonPicker({required this.kind, required this.value, required this.label, required this.onChanged, this.id, this.knownName, this.exclude = const <String>{}, super.key});

  final PersonKind kind;

  /// The UUID written now; null or empty for none.
  final String? value;

  /// Names the controls for assistive technology ("Owner").
  final String label;
  final void Function(String? uuid) onChanged;
  final String? id;

  /// The name the document keeps for [value] (a shop's `owner.name`), shown
  /// when the lists do not name it.
  final String? knownName;

  /// UUIDs not to offer, such as the NPC itself.
  final Set<String> exclude;

  @override
  Widget build(BuildContext context) {
    final bool advanced = ModeScope.advanced(context);
    final Map<String, String> names = personNames(SuggestScope.sourcesOf(context), kind);
    final String? current = value == null || value!.trim().isEmpty ? null : value!.trim();
    final List<String> uuids = <String>[
      for (final String uuid in names.keys)
        if (uuid == current || !exclude.contains(uuid)) uuid,
    ]..sort((String a, String b) => names[a]!.toLowerCase().compareTo(names[b]!.toLowerCase()));
    final Widget select = AnimusSelect.values(
      id: id,
      // A field that names the control by [id] labels it already.
      hiddenLabel: id == null ? label : null,
      value: current,
      values: uuids,
      labels: <String, String>{
        ...names,
        if (current != null && !names.containsKey(current))
          current: knownName != null && knownName!.trim().isNotEmpty ? knownName!.trim() : unknownPersonLabel(kind, current, advanced: advanced),
      },
      details: advanced ? <String, String>{for (final String uuid in uuids) uuid: uuid} : const <String, String>{},
      allowNone: true,
      noneLabel: 'None',
      onChanged: (String? picked) => onChanged(picked == null || picked.isEmpty ? null : picked),
    );
    final String? empty = names.isEmpty
        ? (kind == PersonKind.player ? 'Players show here once a paired server has seen them.' : 'NPCs with a UUID show here.')
        : null;
    if (!advanced) return empty == null ? select : stack(<Widget>[select, mutedText(empty, small: true)]);
    return stack(<Widget>[
      select,
      TextInput(
        // Its own id: the field's label names the dropdown above.
        id: '${id ?? 'animus-person-${kind.name}'}-uuid',
        value: current ?? '',
        placeholder: 'Or a UUID',
        size: controlSize(DensityScope.of(context)),
        fullWidth: true,
        attributes: <String, String>{'aria-label': '$label UUID', 'autocomplete': 'off', 'spellcheck': 'false', 'data-person': kind.name},
        onChanged: (String text) => onChanged(text.trim().isEmpty ? null : text.trim()),
      ),
    ]);
  }
}
