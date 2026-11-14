/// Simple's picker for a text value that names a game id or documents:
/// items and blocks by name with their icons, sounds grouped by what makes
/// them, entities, particles, effects and the rest by name, documents by
/// their list names. The value written is the id picked; one left alone
/// keeps what it holds.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../logic/inspect/named_ids.dart' show idName, particleName;
import '../../logic/npc/npc_appearance.dart' show bodyLabel;
import '../../logic/suggest/completion_sources.dart';
import '../../logic/suggest/field_profile.dart';
import '../conversation/sound_picker.dart';
import '../mc/item_picker.dart';
import '../suggest/suggest_scope.dart';
import 'animus_select.dart';

class NamedValuePicker extends StatelessWidget {
  const NamedValuePicker({required this.refs, required this.value, required this.label, required this.onChanged, this.id, super.key});

  /// What the value names: one registry, or one or more document kinds.
  final List<ValueRef> refs;
  final String? value;
  final String label;
  final void Function(String? value) onChanged;
  final String? id;

  /// What [profile] names when Simple shows it by name, or null for text
  /// shown as written (worlds, flags, commands, free text).
  static List<ValueRef>? refsOf(FieldProfile profile) {
    final List<ValueRef> refs = profile.values;
    if (refs.isEmpty || profile.expression != ExpressionSyntax.none || profile.holes != ExpressionSyntax.none) return null;
    if (refs.length == 1 && refs.single.source == ValueSource.registry && refs.single.registry != RegistryKind.world) return refs;
    if (refs.every((ValueRef ref) => ref.source == ValueSource.document)) return refs;
    return null;
  }

  static String _namespaced(String id) => id.contains(':') ? id : 'minecraft:$id';

  @override
  Widget build(BuildContext context) {
    final String current = value ?? '';
    final ValueRef first = refs.first;
    final CompletionSources sources = SuggestScope.sourcesOf(context);
    if (first.source == ValueSource.document) {
      final List<String> ids = <String>[
        for (final ValueRef ref in refs) ...sources.ids(ref.document!),
      ];
      return AnimusSelect.values(
        id: id,
        hiddenLabel: label,
        references: first.document,
        value: current.isEmpty ? null : current,
        values: <String>[...ids, if (current.isNotEmpty && !ids.contains(current)) current],
        allowNone: true,
        onChanged: onChanged,
      );
    }
    final RegistryKind kind = first.registry!;
    switch (kind) {
      case RegistryKind.item || RegistryKind.block:
        return ItemPicker(id: id, value: current, label: label, onChanged: (String next) => onChanged(next.isEmpty ? null : next));
      case RegistryKind.sound:
        return SoundPicker(id: id, value: value, label: label, allowNone: true, onChanged: onChanged);
      case RegistryKind.particle || RegistryKind.entity || RegistryKind.enchantment || RegistryKind.effect || RegistryKind.attribute || RegistryKind.world:
        final String Function(String id) name = switch (kind) {
          RegistryKind.entity => bodyLabel,
          RegistryKind.particle => particleName,
          _ => idName,
        };
        final List<String> ids = <String>[for (final String each in sources.registry(kind)) _namespaced(each)];
        final String shown = current.isEmpty ? '' : _namespaced(current);
        final List<String> values = <String>[...ids, if (shown.isNotEmpty && !ids.contains(shown)) shown];
        return AnimusSelect.values(
          id: id,
          hiddenLabel: label,
          value: shown.isEmpty ? null : shown,
          values: values,
          labels: <String, String>{for (final String each in values) each: name(each)},
          allowNone: true,
          onChanged: (String? next) {
            if (next != shown) onChanged(next);
          },
        );
    }
  }
}
