/// The picked point, guide or recording step of a route: where it is and how
/// long the NPC waits there, with the rare per-point settings (spread,
/// style, speed, teleport, weight, next points) under More. Points are
/// numbered from 1; the JSON keeps ticks and 0-based `to` indexes.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/npc/route_fields.dart';
import '../../logic/npc/npc_display.dart';
import '../../logic/npc/route_model.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../ui/action_row.dart';
import '../../ui/field.dart';
import '../../ui/menu.dart';
import '../../ui/mode.dart';
import 'route_wait_input.dart';
import '../../logic/suggest/field_profile.dart';
import '../suggest/autocomplete_input.dart';
import '../../ui/icon_button.dart';
import '../select/animus_select.dart';
import '../../ui/number_input.dart';
import 'npc_layout.dart';
import '../gui/gui_stack.dart';
import '../../ui/problem_text.dart';

/// One labelled number or text box of a coordinate row.
Widget routeAxis(String id, String label, Widget input) => namedAbove(label, input, forId: id);

class RoutePointEditor extends StatelessWidget {
  const RoutePointEditor({
    required this.grid,
    required this.route,
    required this.picked,
    required this.problems,
    required this.recordings,
    required this.onWrite,
    required this.onPick,
    required this.addAt,
    super.key,
  });

  /// The editor's id, which prefixes every control id here.
  final String grid;
  final JsonMap route;

  /// `points` or `guides`, and the index.
  final (String, int) picked;

  /// The route's problems: field path and message.
  final List<(String, String)> problems;
  final Map<String, int?> recordings;
  final void Function(JsonMap next) onWrite;
  final void Function((String, int)? next) onPick;

  /// Where a recording step goes when it becomes a position.
  final (double, double, double) addAt;

  static String _coord(num? value) => value == null ? '' : (value == value.roundToDouble() ? '${value.toInt()}' : '$value');

  @override
  Widget build(BuildContext context) {
    final (String key, int index) = picked;
    final JsonMap node = asMap(asList(route[key])[index]);
    final RouteSpec spec = RouteSpec(route);
    final bool guide = key == 'guides';
    final bool step = !guide && node.containsKey('recording');
    final int number = guide ? spec.points.length + index + 1 : index + 1;
    final String label = guide ? 'Guide $number' : (step ? 'Step $number' : 'Point $number');
    final String field = '$key[$index]';
    final int count = asList(route[key]).length;
    void set(String name, Object? value) => onWrite(setRouteNodeField(route, key, index, name, value));
    final List<Widget> more = guide ? const <Widget>[] : _more(node, index, label, field, spec, step, set);
    final int inUse = <String>['jitter', 'style', 'speed', 'teleport', 'weight', 'next'].where(node.containsKey).length;
    return dom.div(
      classes: 'animus-route-point',
      styles: stackStyles(),
      attributes: <String, String>{'role': 'group', 'aria-label': 'Selected $label', 'data-route-point': '$key:$index'},
      <Widget>[
        groupHeading(label),
        if (step) ..._stepFields(node, label, set) else _position(node, label, field, set),
        if (!guide)
          Field(
            label: 'Wait',
            forId: '$grid-point-wait',
            problems: routeProblemsAt(problems, '$field.waitTicks'),
            child: RouteWaitInput(
              key: ValueKey<String>('wait:$key:$index'),
              id: '$grid-point-wait',
              label: label,
              value: node['waitTicks'],
              placeholder: waitText(spec.waitTicks),
              onChanged: (Object? value) => set('waitTicks', value),
            ),
          ),
        if (more.isNotEmpty)
          MoreFields(
            key: ValueKey<String>('more:$key:$index'),
            label: inUse == 0 ? 'More' : 'More ($inUse set)',
            open: routeProblemsAt(problems, field, within: true).isNotEmpty && inUse > 0,
            children: more,
          ),
        ActionRow(
          dense: true,
          label: '$label actions',
          moreId: '$grid-point-more',
          more: <AppMenuEntry>[
            if (step)
              AppMenuEntry('Make a position', () {
                final (double x, double y, double z) = addAt;
                onWrite(routeStepToPosition(route, index, x, y, z));
              })
            else if (!guide && spec.order != 'wander')
              AppMenuEntry('Make a recording step', () => onWrite(routePointToStep(route, index, recordings.keys.firstOrNull ?? ''))),
            if (!guide) const AppMenuEntry.separator(),
            AppMenuEntry('Remove ${label.toLowerCase()}', () {
              onWrite(removeRouteNode(route, key, index));
              onPick(null);
            }, destructive: true),
          ],
          secondary: <RowAction>[
            if (!guide) ...<RowAction>[
              RowAction('Earlier', index == 0 ? null : () => _move(index, index - 1), icon: ArcaneIcon.arrowUp(), id: '$grid-point-earlier'),
              RowAction('Later', index >= count - 1 ? null : () => _move(index, index + 1), icon: ArcaneIcon.arrowDown(), id: '$grid-point-later'),
            ],
          ],
        ),
      ],
    );
  }

  void _move(int from, int to) {
    onWrite(reorderRoutePoint(route, from, to));
    onPick(('points', to));
  }

  Widget _position(JsonMap node, String label, String field, void Function(String, Object?) set) => Field(
        label: 'Position',
        problems: <Diagnostic>[for (final String axis in const <String>['x', 'y', 'z']) ...routeProblemsAt(problems, '$field.$axis')],
        child: wrapLine(<Widget>[
          for (final String axis in const <String>['x', 'y', 'z'])
            routeAxis(
              '$grid-point-$axis',
              axis.toUpperCase(),
              NumberInput(id: '$grid-point-$axis', value: roundedForField(asDouble(node[axis]), 2), integer: false, label: '$label ${axis.toUpperCase()}', onChanged: (num? value) => set(axis, value ?? 0)),
            ),
        ]),
      );

  /// A step's recording and the part of it that plays, in seconds.
  List<Widget> _stepFields(JsonMap node, String label, void Function(String, Object?) set) {
    final String recording = asString(node['recording']) ?? '';
    final int? duration = recordings[recording];
    final String recordingId = '$grid-recording';
    num? seconds(Object? ticks) => asInt(ticks) == null ? null : ticksToSeconds(asInt(ticks)!);
    return <Widget>[
      Field(
        label: 'Recording',
        forId: recordingId,
        tip: 'The NPC walks to the part\'s first frame, plays it once, then goes on from its last frame.',
        docs: Docs.recordings,
        child: recordings.isEmpty
            ? AutocompleteInput(id: recordingId, value: recording, label: '$label recording', onChanged: (String value) => set('recording', value.trim()), profile: FieldProfile.none)
            : AnimusSelect.values(references: DocumentKind.recording, id: recordingId, value: recording.isEmpty ? null : recording, values: recordings.keys.toList(), onChanged: (String? value) => set('recording', value ?? '')),
      ),
      Field(
        label: 'Part',
        hint: 'seconds',
        child: wrapLine(<Widget>[
          routeAxis(
            '$grid-step-from',
            'From',
            NumberInput(id: '$grid-step-from', value: seconds(node['fromTick']), integer: false, label: '$label from, seconds', placeholder: '0', onChanged: (num? value) => set('fromTick', value == null ? null : secondsToTicks(value))),
          ),
          routeAxis(
            '$grid-step-to',
            'To',
            NumberInput(
              id: '$grid-step-to',
              value: seconds(node['toTick']),
              integer: false,
              label: '$label to, seconds',
              placeholder: duration == null ? 'End' : 'End, ${_coord(ticksToSeconds(duration))}',
              onChanged: (num? value) => set('toTick', value == null ? null : secondsToTicks(value)),
            ),
          ),
        ]),
      ),
    ];
  }

  List<Widget> _more(JsonMap node, int index, String label, String field, RouteSpec spec, bool step, void Function(String, Object?) set) => <Widget>[
        if (!step && (spec.order != 'wander' || node.containsKey('jitter')))
          Field(
            label: 'Spread',
            forId: '$grid-point-jitter',
            hint: 'blocks',
            tip: 'Each arrival lands up to this many blocks from the point. Empty uses the route\'s.',
            problems: routeProblemsAt(problems, '$field.jitter'),
            child: NumberInput(id: '$grid-point-jitter', value: asInt(node['jitter']), digits: 3, label: '$label spread', placeholder: 'Route\'s', onChanged: (num? value) => set('jitter', value?.toInt())),
          ),
        if (step && node.containsKey('jitter'))
          Field(
            label: 'Spread',
            problems: routeProblemsAt(problems, '$field.jitter'),
            child: ActionRow(secondary: <RowAction>[RowAction('Remove spread', () => set('jitter', null))]),
          ),
        if (!step)
          Field(
            label: 'Style',
            forId: '$grid-point-style',
            tip: 'How the NPC moves on the way to this point. Empty uses the route\'s.',
            problems: routeProblemsAt(problems, '$field.style'),
            child: AnimusSelect.values(id: '$grid-point-style', value: asString(node['style']), values: movementStyles, labels: movementStyleLabels, allowNone: true, noneLabel: 'Route\'s style', onChanged: (String? value) => set('style', value)),
          ),
        if (!step && (spec.order != 'wander' || node.containsKey('speed')))
          Field(
            label: 'Speed from here',
            forId: '$grid-point-speed',
            hint: 'Blocks per second, 1 to 5',
            tip: 'The route walks at this speed from this point on, until another point sets one.',
            problems: routeProblemsAt(problems, '$field.speed'),
            child: NumberInput(id: '$grid-point-speed', value: asDouble(node['speed']), integer: false, digits: 4, label: '$label speed', placeholder: 'Same', onChanged: (num? value) => set('speed', value)),
          ),
        if (!step && (spec.order != 'wander' || node.containsKey('teleport'))) _teleport(node, label, field, set),
        if (spec.order == 'random' || node.containsKey('weight'))
          Field(
            label: 'Weight',
            forId: '$grid-point-weight',
            hint: '1-$routeMaxWeight',
            tip: 'How likely the random order picks this point next, against the others.',
            problems: routeProblemsAt(problems, '$field.weight'),
            child: NumberInput(id: '$grid-point-weight', value: asInt(node['weight']), digits: 4, label: '$label weight', placeholder: '1', onChanged: (num? value) => set('weight', value?.toInt())),
          ),
        if (spec.order == 'sequence' || node.containsKey('next')) _junctions(node, index, label, field),
      ];

  /// Where arriving teleports the NPC: x, y and z with an optional facing
  /// and world. Clearing every coordinate removes it.
  Widget _teleport(JsonMap node, String label, String field, void Function(String, Object?) set) {
    final JsonMap teleport = asMap(node['teleport']);
    void axis(String key, Object? value) {
      final JsonMap next = <String, Object?>{...teleport};
      if (value == null || value == '') {
        next.remove(key);
      } else {
        next[key] = value;
      }
      final bool empty = !next.containsKey('x') && !next.containsKey('y') && !next.containsKey('z');
      set('teleport', empty ? null : <String, Object?>{'x': next['x'] ?? 0, 'y': next['y'] ?? 0, 'z': next['z'] ?? 0, ...next});
    }

    return Field(
      label: 'Teleport to',
      tip: 'On arrival the NPC moves here at once, before its wait. A world name moves it to that world. Empty: no teleport.',
      problems: routeProblemsAt(problems, '$field.teleport', within: true),
      child: wrapLine(<Widget>[
        for (final (String key, String name) in const <(String, String)>[('x', 'X'), ('y', 'Y'), ('z', 'Z'), ('yaw', 'Facing'), ('pitch', 'Tilt')])
          routeAxis(
            '$grid-teleport-$key',
            name,
            NumberInput(id: '$grid-teleport-$key', value: asDouble(teleport[key]), integer: false, label: '$label teleport $name', onChanged: (num? value) => axis(key, value)),
          ),
        routeAxis(
          '$grid-teleport-world',
          'World',
          AutocompleteInput(id: '$grid-teleport-world', value: asString(teleport['world']) ?? '', label: '$label teleport world', placeholder: 'Same', onChanged: (String value) => axis('world', value.trim()), profile: FieldProfile.none),
        ),
      ]),
    );
  }

  /// A point's next points: each leads to a point by weight.
  Widget _junctions(JsonMap node, int index, String label, String field) {
    final List<Object?> next = asList(node['next']);
    final int count = asList(route['points']).length;
    final List<String> targets = <String>[
      for (int point = 0; point < count; point++)
        if (point != index) '$point',
    ];
    return Field(
      label: 'Next points',
      tip: next.isEmpty
          ? 'None: the NPC walks on to point ${index + 2 <= count ? index + 2 : 'after the last'}. Add some to pick one by weight instead.'
          : 'One of these is picked by weight instead of point ${index + 2}.',
      problems: routeProblemsAt(problems, '$field.next'),
      child: dom.div(styles: stackStyles(), attributes: <String, String>{'role': 'group', 'aria-label': '$label next points'}, <Widget>[
        for (int junction = 0; junction < next.length; junction++)
          fieldStack(key: ValueKey<String>('junction:$index:$junction'), <Widget>[
            fieldLine(<Widget>[
            AnimusSelect.values(
              id: '$grid-next-$junction-to',
              hiddenLabel: 'Next point ${junction + 1}',
              value: asInt(asMap(next[junction])['to'])?.toString(),
              values: targets,
              labels: <String, String>{for (final String target in targets) target: 'Point ${int.parse(target) + 1}'},
              onChanged: (String? value) {
                final int? to = value == null ? null : int.tryParse(value);
                if (to != null) onWrite(setRouteJunction(route, index, junction, to: to));
              },
            ),
            NumberInput(
              id: '$grid-next-$junction-weight',
              value: asInt(asMap(next[junction])['weight']),
              digits: 4,
              label: 'Next point ${junction + 1} weight',
              placeholder: '1',
              onChanged: (num? value) => onWrite(setRouteJunction(route, index, junction, weight: value?.toInt())),
            ),
            AppIconButton(icon: ArcaneIcon.x(), label: 'Remove next point ${junction + 1}', onPressed: () => onWrite(removeRouteJunction(route, index, junction))),
            ]),
            for (final Diagnostic problem in routeProblemsAt(problems, '$field.next[$junction]', within: true))
              problemText(problem.message),
          ]),
        ActionRow(
          secondary: <RowAction>[
            RowAction('Add next point', next.length >= routeMaxJunctions || count < 2 ? null : () => onWrite(addRouteJunction(route, index)), icon: ArcaneIcon.plus(), id: '$grid-next-add'),
          ],
        ),
      ]),
    );
  }
}
