/// The conversation editor's picker: one value from a list, drawn by the
/// editor's one dropdown ([AnimusSelect]).
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../logic/select/select_model.dart';
import '../select/animus_select.dart';

class PickOption {
  const PickOption(this.value, this.label, {this.hint});

  final String value;
  final String label;

  /// A short line under the label.
  final String? hint;
}

/// A dropdown that picks one of [options]. It shows the picked label, or
/// [placeholder] when [value] is not among them.
class ConversationPicker extends StatelessWidget {
  const ConversationPicker({required this.label, required this.options, required this.onPick, this.value, this.placeholder = 'Pick one', this.id, super.key});

  /// The accessible name, such as "Starts".
  final String label;
  final List<PickOption> options;
  final String? value;
  final String placeholder;
  final void Function(String value) onPick;

  /// The trigger's element id, for a `<label for>`.
  final String? id;

  @override
  Widget build(BuildContext context) => AnimusSelect(
        id: id,
        hiddenLabel: label,
        value: value,
        placeholder: placeholder,
        options: <SelectOption>[
          for (final PickOption option in options) SelectOption(value: option.value, label: option.label, description: option.hint),
        ],
        onChanged: onPick,
      );
}
