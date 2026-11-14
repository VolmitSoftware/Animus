/// A two-to-four-way switch (Mode, Form or Code, Routes or Recordings) and a
/// row of filter chips, both on shadcn's toggle group. Values are mapped to
/// the library's string ids by position, so any value type works. Their
/// height follows the row ([DensityScope]). A switch its row squeezes shows
/// as a dropdown instead of scrolling sideways; chips wrap onto a second row.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../components/select/animus_select.dart';
import 'density.dart';
import 'squeeze_fold.dart';

ToggleGroupSize _size(BuildContext context) => DensityScope.isDense(context) ? ToggleGroupSize.sm : ToggleGroupSize.md;

/// One choice: its value and visible label.
class ChoiceOption<T> {
  const ChoiceOption(this.value, this.label);

  final T value;
  final String label;
}

int _nextChoiceId = 0;

/// Exactly one of [options]. Pressing the chosen one again keeps it.
class ToggleChoice<T> extends StatefulWidget {
  const ToggleChoice({required this.label, required this.options, required this.value, required this.onChanged, super.key});

  /// The group's accessible name.
  final String label;
  final List<ChoiceOption<T>> options;
  final T value;
  final void Function(T value) onChanged;

  @override
  State<ToggleChoice<T>> createState() => _ToggleChoiceState<T>();
}

class _ToggleChoiceState<T> extends State<ToggleChoice<T>> with SqueezeFold<ToggleChoice<T>> {
  final String _id = 'ui-choice-${_nextChoiceId++}';

  @override
  String get squeezeId => _id;

  String get label => component.label;
  List<ChoiceOption<T>> get options => component.options;
  void Function(T value) get onChanged => component.onChanged;

  @override
  Widget build(BuildContext context) {
    measureSqueeze();
    final int at = options.indexWhere((ChoiceOption<T> option) => option.value == component.value);
    if (folded) {
      return dom.div(id: _id, classes: 'ui-toggle-choice is-folded', <Widget>[
        AnimusSelect.values(
          hiddenLabel: label,
          value: at < 0 ? null : '$at',
          values: <String>[for (int index = 0; index < options.length; index++) '$index'],
          labels: <String, String>{for (int index = 0; index < options.length; index++) '$index': options[index].label},
          onChanged: (String? picked) {
            final int? index = picked == null ? null : int.tryParse(picked);
            if (index != null && index < options.length) onChanged(options[index].value);
          },
        ),
      ]);
    }
    return dom.div(id: _id, classes: 'ui-toggle-choice', attributes: <String, String>{'role': 'group', 'aria-label': label}, <Widget>[
      ArcaneToggleGroup(
        items: <ToggleGroupItem>[
          for (int index = 0; index < options.length; index++) ToggleGroupItem(value: '$index', child: Component.text(options[index].label)),
        ],
        value: at < 0 ? null : '$at',
        variant: ToggleGroupVariant.outline,
        size: _size(context),
        onChanged: (String? picked) {
          final int? index = picked == null ? null : int.tryParse(picked);
          if (index != null && index < options.length) onChanged(options[index].value);
        },
      ),
    ]);
  }
}

/// Any number of [options] on or off: list filters.
class ToggleChips<T> extends StatelessWidget {
  const ToggleChips({required this.label, required this.options, required this.values, required this.onChanged, super.key});

  final String label;
  final List<ChoiceOption<T>> options;
  final Set<T> values;
  final void Function(Set<T> values) onChanged;

  @override
  Widget build(BuildContext context) => dom.div(classes: 'ui-toggle-chips', attributes: <String, String>{'role': 'group', 'aria-label': label}, <Widget>[
        ArcaneToggleGroup.multiple(
          items: <ToggleGroupItem>[
            // A check marks what is on: Midnight's "on" fill alone barely
            // differs from "off".
            for (int index = 0; index < options.length; index++)
              ToggleGroupItem(
                value: '$index',
                child: Component.fragment(<Widget>[
                  if (values.contains(options[index].value)) ArcaneIcon.check().sized(controlIconSize),
                  Component.text(options[index].label),
                ]),
              ),
          ],
          values: <String>{
            for (int index = 0; index < options.length; index++)
              if (values.contains(options[index].value)) '$index',
          },
          variant: ToggleGroupVariant.outline,
          size: _size(context),
          styles: const ArcaneStyleData(flexWrap: FlexWrap.wrap),
          onMultiChanged: (Set<String> picked) => onChanged(<T>{
            for (final String id in picked)
              if (int.tryParse(id) case final int index when index < options.length) options[index].value,
          }),
        ),
      ]);
}
