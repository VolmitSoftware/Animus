/// A number field on the library's text input, at its row's height. Empty
/// clears the value; text that is not a number yet keeps the last value.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import 'density.dart';

class NumberInput extends StatefulWidget {
  const NumberInput({required this.value, required this.onChanged, this.integer = true, this.label, this.placeholder, this.digits, this.id, super.key});

  /// The element id, so a `Field(forId:)` label names it.
  final String? id;
  final num? value;
  final void Function(num? value) onChanged;
  final bool integer;

  /// The accessible name where no row label names the field.
  final String? label;
  final String? placeholder;

  /// Characters of the widest allowed value; the field is sized to fit them.
  final int? digits;

  @override
  State<NumberInput> createState() => _NumberInputState();
}

class _NumberInputState extends State<NumberInput> {
  /// The raw text while focused, kept over slower saves.
  String? _typed;

  @override
  Widget build(BuildContext context) {
    final num? shown = component.value;
    final Widget input = TextInput(
      id: component.id,
      type: TextInputType.number,
      size: controlSize(DensityScope.of(context)),
      value: _typed ?? (shown == null ? '' : (shown == shown.roundToDouble() ? shown.toInt().toString() : shown.toString())),
      placeholder: component.placeholder,
      attributes: <String, String>{
        'step': component.integer ? '1' : 'any',
        'aria-label': ?component.label,
      },
      onChanged: (String value) {
        final String raw = value.trim();
        _typed = raw;
        if (raw.isEmpty) {
          component.onChanged(null);
          return;
        }
        final num? parsed = component.integer ? int.tryParse(raw) : num.tryParse(raw);
        if (parsed != null) component.onChanged(parsed);
      },
      onBlur: () {
        if (mounted) setState(() => _typed = null);
      },
    );
    final int? digits = component.digits;
    if (digits == null) return input;
    return dom.span(styles: dom.Styles(raw: <String, String>{'display': 'inline-block', 'width': 'calc(${digits}ch + 3.5rem)', 'max-width': '100%'}), <Widget>[input]);
  }
}
