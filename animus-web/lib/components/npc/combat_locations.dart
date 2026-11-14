/// Places (combat's return point, health's respawn points): a top-down grid
/// to place them, numbered from 1, and a world, x, y and z row per place.
/// Keys a place does not use stay listed under it, to rename or remove.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:web/web.dart' as web;

import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/inspect/typed_values.dart';
import '../../logic/npc/combat_forms.dart';
import '../../logic/suggest/completion_sources.dart';
import '../../logic/suggest/field_profile.dart';
import '../../logic/suggest/field_profiles.dart';
import '../../model/animus_document.dart';
import '../../ui/action_row.dart';
import '../../ui/field.dart';
import '../../ui/icon_button.dart';
import '../inspect/typed_values_editor.dart';
import '../select/suggest_input.dart';
import 'combat_fields.dart';
import 'position_grid.dart';
import '../../ui/number_input.dart';
import 'npc_layout.dart';

const Set<String> _placeKeys = <String>{'world', 'x', 'y', 'z'};

int _nextLocationsId = 0;

class CombatLocationsEditor extends StatefulWidget {
  const CombatLocationsEditor({
    required this.points,
    required this.onChanged,
    required this.label,
    required this.noun,
    this.tip,
    this.maximum = 16,
    this.origin,
    this.problems,
    super.key,
  });

  final List<Object?> points;

  /// The whole new list; empty removes the field.
  final void Function(List<Object?> points) onChanged;

  /// The field's label ("Points", "Return point").
  final String label;

  /// One place, as its rows and buttons name it ("Point", "Return point").
  final String noun;
  final String? tip;
  final int maximum;
  final (double, double, double)? origin;

  /// The problems at place [index], paths relative to it.
  final List<Diagnostic> Function(int index)? problems;

  @override
  State<CombatLocationsEditor> createState() => _CombatLocationsEditorState();
}

class _CombatLocationsEditorState extends State<CombatLocationsEditor> {
  final String _id = 'animus-places-${_nextLocationsId++}';
  int? _picked;

  String _name(int index) => component.maximum == 1 ? component.noun : '${component.noun} ${index + 1}';

  void _set(int index, Object? point) => component.onChanged(<Object?>[...component.points]..[index] = point);

  /// The list index of grid mark [mark]; marks skip places without x and z.
  int _pointIndex(int mark) {
    int seen = -1;
    for (int index = 0; index < component.points.length; index++) {
      if (combatLocationXZ(component.points[index]) == null) continue;
      seen++;
      if (seen == mark) return index;
    }
    return mark;
  }

  void _add() {
    final List<Object?> points = component.points;
    final JsonMap? last = points.isEmpty ? null : asMap(points.last);
    setState(() => _picked = points.length);
    component.onChanged(<Object?>[...points, newCombatLocation(near: last, origin: last == null ? component.origin : null)]);
  }

  @override
  Widget build(BuildContext context) {
    final List<Object?> points = component.points;
    final int? picked = _picked != null && _picked! < points.length ? _picked : null;
    final List<String> worlds = <String>{
      'world',
      for (final Object? point in points)
        if (asString(asMap(point)['world']) case final String world when world.isNotEmpty) world,
    }.toList();
    final bool single = component.maximum == 1;
    return Field(
      label: component.label,
      tip: component.tip,
      child: dom.div(styles: stackStyles(), attributes: <String, String>{'data-places': component.noun}, <Widget>[
        if (points.isNotEmpty)
          PositionGrid(
            label: component.label,
            origin: component.origin,
            picked: picked,
            marks: <PositionMark>[
              for (int index = 0; index < points.length; index++)
                if (combatLocationXZ(points[index]) case (final double x, final double z))
                  PositionMark(number: '${index + 1}', name: _name(index), x: x, y: asDouble(asMap(points[index])['y']) ?? 0, z: z),
            ],
            onPick: (int mark) => setState(() => _picked = _pointIndex(mark)),
            onMove: (int mark, double x, double z) {
              final int index = _pointIndex(mark);
              setState(() => _picked = index);
              _set(index, placeCombatLocation(asMap(points[index]), x, z));
            },
            onNudge: (int mark, double dx, double dz) {
              final int index = _pointIndex(mark);
              _set(index, nudgeCombatLocation(asMap(points[index]), dx, dz));
            },
          ),
        for (int index = 0; index < points.length; index++) _point(index, points[index], worlds, picked == index),
        if (points.length < component.maximum)
          ActionRow(
            label: component.label,
            secondary: <RowAction>[RowAction(single ? 'Set ${component.noun.toLowerCase()}' : 'Add ${component.noun.toLowerCase()}', _add, icon: ArcaneIcon.plus())],
          ),
      ]),
    );
  }

  Widget _point(int index, Object? raw, List<String> worlds, bool picked) {
    final String name = _name(index);
    final String id = '$_id-$index';
    final List<Diagnostic> problems = component.problems?.call(index) ?? const <Diagnostic>[];
    Widget cell(String axis, String label, Widget control) => namedAbove(label, control, forId: '$id-$axis');
    return dom.div(
      key: ValueKey<String>('place:$index'),
      styles: stackStyles(),
      attributes: <String, String>{'role': 'group', 'aria-label': name},
      events: <String, void Function(web.Event)>{'focusin': (web.Event _) => setState(() => _picked = index)},
      <Widget>[
        if (raw is! Map<String, Object?>)
          TypedValueEditor(value: raw, label: name, onChanged: (Object? next) => _set(index, next))
        else
          // World, then x, y and z: one line where both fit, two otherwise.
          Wrap(spacing: Gap.sm.value, runSpacing: Gap.sm.value, crossAxisAlignment: WrapCrossAlignment.end, children: <Widget>[
            wrapItem(
              '3 1 11rem',
              Row(gapSize: Gap.sm, crossAxisAlignment: CrossAxisAlignment.end, children: <Widget>[
                pointNumber(index + 1, picked: picked),
                Expanded(
                  child: cell(
                    'world',
                    'World',
                    raw['world'] == null || raw['world'] is String
                        ? SuggestInput(
                            id: '$id-world',
                            value: asString(raw['world']) ?? '',
                            label: '$name world',
                            profile: idProfile(const ValueRef.registry(RegistryKind.world)),
                            placeholder: 'world',
                            suggestions: <FieldOption>[for (final String world in worlds) FieldOption(world)],
                            onChanged: (String next) => _set(index, withLocationField(raw, 'world', next.trim())),
                          )
                        : TypedValueEditor(value: raw['world'], label: '$name world', onChanged: (Object? next) => _set(index, withLocationField(raw, 'world', next))),
                  ),
                ),
              ]),
            ),
            wrapItem(
              '2 1 17rem',
              Row(gapSize: Gap.sm, crossAxisAlignment: CrossAxisAlignment.end, children: <Widget>[
                for (final String axis in const <String>['x', 'y', 'z'])
                  Expanded(
                    child: cell(
                      axis,
                      axis.toUpperCase(),
                      raw[axis] == null || raw[axis] is num
                          ? NumberInput(
                              id: '$id-$axis',
                              value: raw[axis] as num?,
                              integer: false,
                              label: '$name ${axis.toUpperCase()}',
                              onChanged: (num? next) => _set(index, withLocationField(raw, axis, next)),
                            )
                          : TypedValueEditor(value: raw[axis], label: '$name ${axis.toUpperCase()}', onChanged: (Object? next) => _set(index, withLocationField(raw, axis, next))),
                    ),
                  ),
                AppIconButton(
                  icon: ArcaneIcon.x(),
                  label: 'Remove ${name.toLowerCase()}',
                  onPressed: () {
                    setState(() => _picked = null);
                    component.onChanged(<Object?>[...component.points]..removeAt(index));
                  },
                ),
              ]),
            ),
          ]),
        if (raw is Map<String, Object?>)
          if (unknownEntries(raw, _placeKeys) case final JsonMap unknown when unknown.isNotEmpty)
            KeyValueEditor(value: unknown, label: '$name other fields', onChanged: (JsonMap next) => _set(index, withUnknownEntries(raw, _placeKeys, next))),
        problemLines(problems),
      ],
    );
  }
}
