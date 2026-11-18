/// Basics' Named facings and Anchors (Advanced): one row per name with its
/// own fields, Facing and Head tilt for a facing, X, Y, Z and an optional
/// world for an anchor. Add puts a new one where the NPC faces or stands.
/// A name the server refuses stays in its field with the reason and is not
/// written.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/gui/gui_json.dart';
import '../../logic/npc/named_places.dart';
import '../../model/animus_document.dart';
import '../../ui/action_row.dart';
import '../../ui/number_input.dart';
import '../../ui/problem_text.dart';
import '../gui/gui_fields.dart';
import '../gui/gui_stack.dart';
import 'npc_layout.dart';

/// What a [NamedPlacesEditor] edits.
enum NamedPlaces {
  facings('poses', 'facing', 'Add facing'),
  anchors('anchors', 'anchor', 'Add anchor');

  const NamedPlaces(this.field, this.noun, this.add);

  /// The definition field.
  final String field;
  final String noun;
  final String add;
}

class NamedPlacesEditor extends StatefulWidget {
  const NamedPlacesEditor({
    required this.places,
    required this.value,
    required this.at,
    required this.yaw,
    required this.onChanged,
    this.diagnostics = const <Diagnostic>[],
    super.key,
  });

  final NamedPlaces places;

  /// The map of names to places, or null.
  final Object? value;

  /// Where the NPC stands, where a new anchor starts.
  final ({num x, num y, num z}) at;

  /// The way the NPC faces, where a new facing starts.
  final num yaw;

  /// The whole new map; null removes the field.
  final void Function(Object? value) onChanged;
  final List<Diagnostic> diagnostics;

  @override
  State<NamedPlacesEditor> createState() => _NamedPlacesEditorState();
}

class _NamedPlacesEditorState extends State<NamedPlacesEditor> {
  /// Names typed that are not written yet, by row, with why.
  final Map<int, String> _typed = <int, String>{};

  NamedPlaces get _places => component.places;

  String _id(int index, String part) => 'animus-npc-${_places.noun}-$index-$part';

  void _rename(int index, String from, String to) {
    final String? problem = placeNameProblem(to, component.value, from);
    if (problem != null) {
      setState(() => _typed[index] = to);
      return;
    }
    setState(() => _typed.remove(index));
    if (to != from) component.onChanged(renamedPlace(component.value, from, to));
  }

  void _add() {
    setState(_typed.clear);
    component.onChanged(_places == NamedPlaces.anchors ? withNewAnchor(component.value, component.at) : withNewFacing(component.value, component.yaw));
  }

  void _remove(String name) {
    setState(_typed.clear);
    component.onChanged(withoutPlace(component.value, name));
  }

  Widget _number(String name, int index, String field, String label, {bool optional = false}) {
    final JsonMap place = asMap(asMap(component.value)[name]);
    return namedAbove(
      label,
      NumberInput(
        id: _id(index, field),
        value: asDouble(place[field]),
        integer: false,
        label: '$name $label',
        placeholder: optional ? '0' : null,
        onChanged: (num? next) => component.onChanged(withPlaceValue(component.value, name, field, next)),
      ),
      forId: _id(index, field),
    );
  }

  @override
  Widget build(BuildContext context) {
    final JsonMap map = asMap(component.value);
    final List<String> names = map.keys.toList();
    final bool anchors = _places == NamedPlaces.anchors;
    return dom.div(
      styles: stackStyles(),
      attributes: <String, String>{'role': 'group', 'aria-label': anchors ? 'Anchors' : 'Named facings', 'data-npc': _places.field},
      <Widget>[
        for (int index = 0; index < names.length; index++)
          dom.div(
            key: ValueKey<String>('place:$index'),
            styles: stackStyles(gap: Gap.xs),
            attributes: <String, String>{'data-place': names[index]},
            <Widget>[
              ActionRow(
                label: '${_places.noun} ${names[index]}',
                leading: <Widget>[
                  Expanded(
                    child: TextInput(
                      id: _id(index, 'name'),
                      value: _typed[index] ?? names[index],
                      placeholder: _places.noun,
                      onChanged: (String text) => _rename(index, names[index], text.trim()),
                    ),
                  ),
                ],
                secondary: <RowAction>[RowAction('Remove ${names[index]}', () => _remove(names[index]), icon: ArcaneIcon.x(), iconOnly: true)],
              ),
              if (_typed[index] case final String typed) problemText(placeNameProblem(typed, map, names[index]) ?? ''),
              if (anchors) ...<Widget>[
                equalLine(<Widget>[_number(names[index], index, 'x', 'X'), _number(names[index], index, 'y', 'Y'), _number(names[index], index, 'z', 'Z')]),
                namedAbove(
                  'World',
                  TextInput(
                    id: _id(index, 'world'),
                    value: asString(asMap(map[names[index]])['world']) ?? '',
                    placeholder: "The NPC's world",
                    onChanged: (String text) => component.onChanged(withPlaceValue(component.value, names[index], 'world', text.trim().isEmpty ? null : text.trim())),
                  ),
                  forId: _id(index, 'world'),
                ),
              ] else
                equalLine(<Widget>[_number(names[index], index, 'yaw', 'Facing'), _number(names[index], index, 'pitch', 'Head tilt', optional: true)]),
              for (final Diagnostic problem in diagnosticsAt(component.diagnostics, formatPath(<JsonStep>[_places.field, names[index]]), nested: true))
                problemText(problem.message, severity: problem.severity),
            ],
          ),
        ActionRow(label: _places.add, secondary: <RowAction>[RowAction(_places.add, _add, icon: ArcaneIcon.plus())]),
      ],
    );
  }
}
