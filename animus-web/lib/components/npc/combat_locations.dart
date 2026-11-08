/// Locations (combat's `avoidance.returnPoint`, health's `respawn.points`):
/// a top-down grid to place them and a world, x, y and z row per location.
/// Keys a location does not use are kept under "Other fields".
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/inspect/typed_values.dart';
import '../../logic/npc/combat_forms.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart';
import '../inspect/typed_values_editor.dart';
import '../select/suggest_input.dart';
import 'position_grid.dart';
import '../../logic/suggest/field_profile.dart';
import '../../logic/suggest/field_profiles.dart';
import '../../logic/suggest/completion_sources.dart';

const Set<String> _locationKeys = <String>{'world', 'x', 'y', 'z'};

class CombatLocationsEditor extends StatefulWidget {
  const CombatLocationsEditor({
    required this.points,
    required this.onChanged,
    required this.label,
    required this.noun,
    required this.path,
    this.maximum = 16,
    this.origin,
    this.diagnostics = const <Diagnostic>[],
    super.key,
  });

  final List<Object?> points;

  /// The whole new list; empty removes the field.
  final void Function(List<Object?> points) onChanged;

  /// Names the grid ("Respawn points").
  final String label;

  /// One location, as its rows and buttons name it ("Location", "Return point").
  final String noun;

  /// The field's path, as the combat checks name it.
  final String path;
  final int maximum;
  final (double, double, double)? origin;
  final List<Diagnostic> diagnostics;

  @override
  State<CombatLocationsEditor> createState() => _CombatLocationsEditorState();
}

class _CombatLocationsEditorState extends State<CombatLocationsEditor> {
  int? _picked;

  String _name(int index) => component.maximum == 1 ? component.noun : '${component.noun} ${index + 1}';

  String _itemPath(int index) => component.maximum == 1 ? component.path : indexPath(component.path, index);

  void _set(int index, Object? point) => component.onChanged(<Object?>[...component.points]..[index] = point);

  @override
  Widget build(BuildContext context) {
    final List<Object?> points = component.points;
    final int? picked = _picked != null && _picked! < points.length ? _picked : null;
    final List<String> worlds = <String>{
      'world',
      for (final Object? point in points)
        if (asString(asMap(point)['world']) case final String world when world.isNotEmpty) world,
    }.toList();
    return dom.div(classes: 'animus-combat-locations', <Widget>[
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
        dom.div(classes: 'animus-row', <Widget>[
          Button.ghost(
            label: component.maximum == 1 ? 'Set ${component.noun.toLowerCase()}' : 'Add ${component.noun.toLowerCase()}',
            size: ButtonSize.small,
            onPressed: () {
              final JsonMap? last = points.isEmpty ? null : asMap(points.last);
              setState(() => _picked = points.length);
              component.onChanged(<Object?>[...points, newCombatLocation(near: last, origin: last == null ? component.origin : null)]);
            },
          ),
        ]),
    ]);
  }

  /// The list index of grid mark [mark]; marks skip locations without x and z.
  int _pointIndex(int mark) {
    int seen = -1;
    for (int index = 0; index < component.points.length; index++) {
      if (combatLocationXZ(component.points[index]) == null) continue;
      seen++;
      if (seen == mark) return index;
    }
    return mark;
  }

  Widget _point(int index, Object? raw, List<String> worlds, bool picked) {
    final String name = _name(index);
    final String path = _itemPath(index);
    final List<Diagnostic> problems = diagnosticsAt(component.diagnostics, path, nested: true);
    return dom.div(
      key: ValueKey<String>('location:$index'),
      classes: 'animus-combat-item animus-combat-location-item${picked ? ' is-picked' : ''}',
      attributes: <String, String>{'role': 'group', 'aria-label': name},
      <Widget>[
        if (raw is! Map<String, Object?>)
          TypedValueEditor(value: raw, label: name, onChanged: (Object? next) => _set(index, next))
        else ...<Widget>[
          dom.div(classes: 'animus-combat-fields animus-combat-location', <Widget>[
            Button.ghost(
              label: '${index + 1}',
              size: ButtonSize.small,
              attributes: <String, String>{'aria-label': 'Pick $name on the grid', 'aria-pressed': '$picked'},
              onPressed: () => setState(() => _picked = index),
            ),
            if (raw['world'] == null || raw['world'] is String)
              SuggestInput(
                value: asString(raw['world']) ?? '',
                label: '$name world',
                profile: idProfile(const ValueRef.registry(RegistryKind.world)),
                placeholder: 'world',
                suggestions: <FieldOption>[for (final String world in worlds) FieldOption(world)],
                onChanged: (String next) => _set(index, withLocationField(raw, 'world', next.trim())),
              )
            else
              TypedValueEditor(value: raw['world'], label: '$name world', onChanged: (Object? next) => _set(index, withLocationField(raw, 'world', next))),
            for (final String axis in const <String>['x', 'y', 'z'])
              if (raw[axis] == null || raw[axis] is num)
                GuiNumberInput(
                  value: raw[axis] as num?,
                  integer: false,
                  label: '$name $axis',
                  placeholder: axis,
                  onChanged: (num? next) => _set(index, withLocationField(raw, axis, next)),
                )
              else
                TypedValueEditor(value: raw[axis], label: '$name $axis', onChanged: (Object? next) => _set(index, withLocationField(raw, axis, next))),
            GuiIconButton(
              icon: ArcaneIcon.x(size: IconSize.sm),
              label: 'Remove ${name.toLowerCase()}',
              onPressed: () {
                setState(() => _picked = null);
                component.onChanged(<Object?>[...component.points]..removeAt(index));
              },
            ),
          ]),
          if (unknownEntries(raw, _locationKeys) case final JsonMap unknown when unknown.isNotEmpty)
            GuiRow(
              label: 'Other fields',
              tip: 'Keys a location does not use. The server refuses them.',
              child: KeyValueEditor(value: unknown, label: '$name other fields', onChanged: (JsonMap next) => _set(index, withUnknownEntries(raw, _locationKeys, next))),
            ),
        ],
        if (problems.isNotEmpty)
          dom.div(classes: 'animus-combat-problems', <Widget>[
            for (final Diagnostic problem in problems)
              dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', attributes: const <String, String>{'role': 'note'}, <Widget>[
                dom.code(<Widget>[Component.text(problem.path)]),
                Component.text(' ${problem.message}'),
              ]),
          ]),
      ],
    );
  }
}
