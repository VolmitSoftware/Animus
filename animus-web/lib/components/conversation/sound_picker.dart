/// A sound by its plain name ("Note block chime"), grouped by what makes
/// it, from the game's sound list (the paired server's, or the bundled
/// one). Simple mode picks sounds here; Advanced types the key. The stored
/// value stays the namespaced key.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../logic/inspect/behavior_words.dart';
import '../../logic/suggest/completion_sources.dart';
import '../select/animus_select.dart';
import '../suggest/suggest_scope.dart';

/// The picker's values, names and groups, built once per sound list.
class SoundChoices {
  SoundChoices._(this.keys, this.names, this.groups);

  factory SoundChoices.of(List<String> sounds) {
    final SoundChoices? cached = _cached;
    if (cached != null && identical(_from, sounds)) return cached;
    final List<String> keys = <String>[for (final String sound in sounds) sound.contains(':') ? sound : 'minecraft:$sound'];
    final SoundChoices built = SoundChoices._(
      keys,
      <String, String>{for (final String key in keys) key: soundName(key)},
      <String, String>{for (final String key in keys) key: soundGroup(key)},
    );
    _from = sounds;
    return _cached = built;
  }

  static List<String>? _from;
  static SoundChoices? _cached;

  final List<String> keys;
  final Map<String, String> names;
  final Map<String, String> groups;
}

class SoundPicker extends StatelessWidget {
  const SoundPicker({required this.value, required this.label, required this.onChanged, this.id, this.allowNone = false, super.key});

  /// The sound key as stored, or null for none.
  final String? value;
  final String label;
  final void Function(String? key) onChanged;
  final String? id;

  /// Offers "No sound" first, which clears it.
  final bool allowNone;

  @override
  Widget build(BuildContext context) {
    final SoundChoices choices = SoundChoices.of(SuggestScope.sourcesOf(context).registry(RegistryKind.sound));
    final String? shown = value == null || value!.isEmpty ? null : value;
    return AnimusSelect.values(
      id: id,
      hiddenLabel: label,
      value: shown,
      values: choices.keys,
      labels: shown == null || choices.names.containsKey(shown) ? choices.names : <String, String>{...choices.names, shown: soundName(shown)},
      groups: choices.groups,
      allowNone: allowNone,
      noneLabel: allowNone ? 'No sound' : 'Pick a sound',
      onChanged: onChanged,
    );
  }
}
