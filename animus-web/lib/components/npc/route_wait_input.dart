/// A `waitTicks` field shown in seconds: one wait, or with Range a shortest
/// and a longest drawn per arrival. It writes ticks: an int, `[min, max]`,
/// or null when emptied. [RouteWaitInput.id] names the inputs `<id>` and
/// `<id>-max`.
library;

import 'package:jaspr/dom.dart' as dom;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../logic/gui/gui_json.dart';
import '../../logic/npc/route_fields.dart';
import '../../ui/toggle_choice.dart';
import '../../ui/number_input.dart';
import 'npc_layout.dart';
import '../../ui/stack.dart';

class RouteWaitInput extends StatefulWidget {
  const RouteWaitInput({required this.id, required this.label, required this.value, required this.onChanged, this.placeholder, super.key});

  final String id;

  /// What the inputs are named for assistive technology, such as `Point 2`.
  final String label;

  /// The stored `waitTicks`.
  final Object? value;
  final void Function(Object? ticks) onChanged;

  /// Shown in the empty single field: the wait that applies without one.
  final String? placeholder;

  @override
  State<RouteWaitInput> createState() => _RouteWaitInputState();
}

class _RouteWaitInputState extends State<RouteWaitInput> {
  /// Range chosen before both ends were typed.
  bool? _range;

  bool get _ranged => _range ?? component.value is List<Object?>;

  /// The ends in seconds; a single wait is both ends.
  List<num?> get _ends {
    final Object? value = component.value;
    num? seconds(Object? ticks) => asInt(ticks) == null ? null : ticksToSeconds(asInt(ticks)!);
    if (value is List<Object?>) return <num?>[value.isNotEmpty ? seconds(value[0]) : null, value.length > 1 ? seconds(value[1]) : null];
    return <num?>[seconds(value), seconds(value)];
  }

  void _setRange(bool range) {
    final List<num?> ends = _ends;
    setState(() => _range = range);
    component.onChanged(waitFromSeconds(ends[0], range ? ends[1] : null, range: range));
  }

  void _end(int which, num? typed) {
    final List<num?> ends = _ends;
    ends[which] = typed;
    component.onChanged(waitFromSeconds(ends[0], ends[1], range: true));
  }

  @override
  Widget build(BuildContext context) {
    final bool ranged = _ranged;
    final List<num?> ends = _ends;
    final String label = component.label;
    // The boxes give way first, so the switch keeps its size on a phone.
    Widget box(Widget input) => wrapItem('0 1 7rem', input, minWidth: '3rem');
    return dom.div(attributes: <String, String>{'role': 'group', 'aria-label': '$label wait'}, <Widget>[
      Row(gapSize: Gap.sm, children: <Widget>[
        if (ranged) ...<Widget>[
          box(NumberInput(id: component.id, value: ends[0], integer: false, label: '$label shortest wait in seconds', placeholder: '0', onChanged: (num? value) => _end(0, value))),
          mutedText('to'),
          box(NumberInput(id: '${component.id}-max', value: ends[1], integer: false, label: '$label longest wait in seconds', onChanged: (num? value) => _end(1, value))),
        ] else
          box(NumberInput(
            id: component.id,
            value: ends[0],
            integer: false,
            label: '$label wait in seconds',
            placeholder: component.placeholder,
            onChanged: (num? value) => component.onChanged(waitFromSeconds(value, null, range: false)),
          )),
        wrapItem(
          'none',
          ToggleChoice<bool>(
            label: '$label wait kind',
            options: const <ChoiceOption<bool>>[ChoiceOption<bool>(false, 'Fixed'), ChoiceOption<bool>(true, 'Range')],
            value: ranged,
            onChanged: _setRange,
          ),
        ),
      ]),
    ]);
  }
}
