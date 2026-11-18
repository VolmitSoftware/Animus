/// A step's count or ticks field: a number held to its range, and for
/// ticks a typed time ("2 seconds", "1.5s", "3 min") turned into ticks.
/// Text it cannot read says what to type instead of being dropped, and a
/// value it held to the range says so.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../logic/inspect/behavior_steps.dart';
import '../../model/animus_document.dart' show DiagnosticSeverity;
import '../../ui/density.dart';
import '../../ui/problem_text.dart';
import '../../ui/stack.dart';

class BoundedNumberInput extends StatefulWidget {
  const BoundedNumberInput({
    required this.value,
    required this.minimum,
    required this.maximum,
    required this.label,
    required this.onChanged,
    this.ticks = false,
    super.key,
  });

  final num? value;
  final int minimum;
  final int maximum;

  /// The accessible name.
  final String label;
  final void Function(int value) onChanged;

  /// Reads typed times as ticks and says how long the value is.
  final bool ticks;

  @override
  State<BoundedNumberInput> createState() => _BoundedNumberInputState();
}

class _BoundedNumberInputState extends State<BoundedNumberInput> {
  /// The raw text while focused, kept over slower saves.
  String? _typed;
  String? _problem;
  String? _note;

  @override
  void didUpdateWidget(BoundedNumberInput oldComponent) {
    super.didUpdateWidget(oldComponent);
    // An undo or another field changed it: an old note no longer applies.
    if (oldComponent.value != component.value && _typed == null) _note = null;
  }

  void _typedText(String text) {
    final String raw = text.trim();
    _typed = text;
    if (raw.isEmpty) {
      setState(() {
        _problem = null;
        _note = null;
      });
      return;
    }
    final int? read = component.ticks ? parseTicks(raw) : int.tryParse(raw);
    if (read == null) {
      setState(() {
        _problem = component.ticks ? 'Type ticks or a time, such as 40 or 2 seconds.' : 'Type a whole number.';
        _note = null;
      });
      return;
    }
    final (num held, String? note) = fitNumber(read, component.minimum, component.maximum, unit: component.ticks ? 'ticks' : null);
    setState(() {
      _problem = null;
      _note = note ?? (component.ticks && raw != '$read' ? '$read ticks.' : null);
    });
    component.onChanged(held.toInt());
  }

  @override
  Widget build(BuildContext context) {
    final num? value = component.value;
    final String shown = value == null ? '' : (value == value.roundToDouble() ? '${value.toInt()}' : '$value');
    final String? length = component.ticks && value is num && _problem == null && _note == null ? durationWords(value.round()) : null;
    return stack(spacing: 4, <Widget>[
      TextInput(
        size: controlSize(DensityScope.of(context)),
        value: _typed ?? shown,
        attributes: <String, String>{
          'aria-label': component.label,
          'inputmode': component.ticks ? 'text' : 'numeric',
          if (_problem != null) 'aria-invalid': 'true',
        },
        onChanged: _typedText,
        onBlur: () {
          if (mounted) {
            setState(() {
              _typed = null;
              _problem = null;
            });
          }
        },
      ),
      if (_problem != null) problemLine(DiagnosticSeverity.error, _problem!, classes: 'animus-node-problem', role: 'alert'),
      if (_note != null) noteLine(_note!, attributes: const <String, String>{'role': 'status'}),
      if (length != null) noteLine(length),
    ]);
  }
}
