/// Form rows of the rig and animation editors whose text saves when the
/// field is left or Enter is pressed, not on every key: one value
/// ([CommitField]) or an X, Y, Z triple ([VectorField]). A value the
/// editor refuses stays in the field with the reason under it.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../config/docs_links.dart';
import '../../logic/rig_view.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/stack.dart';
import '../gui/gui_stack.dart';

class CommitField extends StatefulWidget {
  const CommitField({
    required this.label,
    required this.value,
    required this.onCommit,
    this.id,
    this.tip,
    this.docs,
    this.hint,
    this.placeholder,
    this.number = false,
    this.required = false,
    super.key,
  });

  final String label;
  final String value;

  /// Saves [draft]; returns why it cannot, or null once saved.
  final String? Function(String draft) onCommit;
  final String? id;
  final String? tip;
  final DocLink? docs;
  final String? hint;
  final String? placeholder;
  final bool number;
  final bool required;

  @override
  State<CommitField> createState() => _CommitFieldState();
}

class _CommitFieldState extends State<CommitField> {
  String? _draft;
  String? _problem;

  @override
  void didUpdateWidget(CommitField oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (oldComponent.value != component.value && _problem == null) _draft = null;
  }

  void _commit() {
    final String? draft = _draft;
    if (draft == null || draft == component.value) {
      if (_draft != null || _problem != null) {
        setState(() {
          _draft = null;
          _problem = null;
        });
      }
      return;
    }
    final String? problem = component.onCommit(draft);
    setState(() {
      _problem = problem;
      if (problem == null) _draft = null;
    });
  }

  @override
  Widget build(BuildContext context) => Field(
        label: component.label,
        forId: component.id,
        tip: component.tip,
        docs: component.docs,
        hint: component.hint,
        error: _problem,
        required: component.required,
        child: TextInput(
          id: component.id,
          type: component.number ? TextInputType.number : TextInputType.text,
          size: controlSize(DensityScope.of(context)),
          required: component.required,
          value: _draft ?? component.value,
          placeholder: component.placeholder,
          attributes: <String, String>{
            'autocomplete': 'off',
            'spellcheck': 'false',
            if (component.number) 'step': 'any',
            if (component.id == null) 'aria-label': component.label,
            if (_problem != null) 'aria-invalid': 'true',
          },
          onChanged: (String value) => _draft = value,
          onSubmitted: (String _) => _commit(),
          onBlur: _commit,
        ),
      );
}

const List<String> _axes = <String>['X', 'Y', 'Z'];

/// Three numbers, each with its axis letter in front. A number past
/// [limit] or not a number is put back.
class VectorField extends StatefulWidget {
  const VectorField({required this.label, required this.value, required this.onCommit, this.name, this.tip, this.docs, this.limit = 1024, super.key});

  final String label;

  /// What assistive technology calls the three numbers, when [label] alone
  /// is not unique on the page ("Visual rotation").
  final String? name;
  final List<double> value;
  final void Function(List<num> value) onCommit;
  final String? tip;
  final DocLink? docs;
  final double limit;

  @override
  State<VectorField> createState() => _VectorFieldState();
}

class _VectorFieldState extends State<VectorField> {
  final Map<int, String> _drafts = <int, String>{};

  void _commit() {
    final List<double> next = List<double>.of(component.value);
    bool changed = false;
    for (int axis = 0; axis < next.length; axis++) {
      final String? draft = _drafts.remove(axis);
      final double? parsed = draft == null ? null : double.tryParse(draft.trim());
      if (parsed != null && parsed.isFinite && parsed.abs() <= component.limit && parsed != next[axis]) {
        next[axis] = parsed;
        changed = true;
      }
    }
    if (changed) component.onCommit(<num>[for (final double each in next) num.parse(trimmedNumber(each, decimals: 5))]);
    setState(() {});
  }

  @override
  Widget build(BuildContext context) => Field(
        label: component.label,
        tip: component.tip,
        docs: component.docs,
        child: dom.div(attributes: <String, String>{'role': 'group', 'aria-label': component.name ?? component.label}, <Widget>[
          equalLine(<Widget>[
          for (int axis = 0; axis < component.value.length && axis < _axes.length; axis++)
            dom.label(<Widget>[
              Row(gapSize: Gap.xs, children: <Widget>[
              dom.span(attributes: const <String, String>{'aria-hidden': 'true'}, <Widget>[mutedText(_axes[axis])]),
              Expanded(child: 
              // Text with a decimal keyboard: three step-arrow number boxes
              // side by side leave no room for the value.
              TextInput(
                size: controlSize(DensityScope.of(context)),
                value: _drafts[axis] ?? trimmedNumber(component.value[axis], decimals: 5),
                attributes: <String, String>{
                  'aria-label': '${component.name ?? component.label} ${_axes[axis]}',
                  'inputmode': 'decimal',
                  'autocomplete': 'off',
                  'spellcheck': 'false',
                },
                onChanged: (String next) => _drafts[axis] = next,
                onSubmitted: (String _) => _commit(),
                onBlur: _commit,
              )),
              ]),
            ]),
          ]),
        ]),
      );
}
