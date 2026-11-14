/// The route's own settings under the map. Simple shows the order (with
/// Loop for an in-order route) and the speed; how it travels, its style,
/// the wait, random picks, the start, spread, link reach and wander size
/// sit under More settings with plain labels. Every edit writes one key.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/npc/route_fields.dart';
import '../../logic/npc/route_model.dart';
import '../../logic/select/select_model.dart' show withDefault;
import '../../ui/field.dart';
import '../../ui/mode.dart';
import 'combat_fields.dart' show plainNumber;
import 'route_wait_input.dart';
import '../select/animus_select.dart';
import '../../ui/number_input.dart';

/// The keys More settings holds, for its "n set" count.
const List<String> routeMoreKeys = <String>['mode', 'style', 'waitTicks', 'seed', 'startOffset', 'jitter', 'linkDistance', 'wander'];

class RouteSettings extends StatelessWidget {
  const RouteSettings({required this.grid, required this.route, required this.problems, required this.onWrite, required this.onOrder, this.ownSpeed, super.key});

  final String grid;
  final JsonMap route;

  /// The NPC's own walking speed, shown while the route sets none.
  final num? ownSpeed;
  final List<(String, String)> problems;
  final void Function(JsonMap next) onWrite;

  /// Tells the editor the order changed (guides go with random).
  final void Function(String order) onOrder;

  void _field(String key, Object? value) => onWrite(routeWith(route, key, value));

  JsonMap? _wander(JsonMap wander, String key, num? value) {
    final JsonMap next = routeWith(wander, key, value?.toInt());
    return next.isEmpty ? null : next;
  }

  /// A choice whose default is shown by its label and stored as no key.
  Widget _choice(String id, String key, List<String> values, Map<String, String> labels, String fallback) {
    final ({String? value, List<String> options}) picked = withDefault(asString(route[key]), values, fallback);
    return AnimusSelect.values(id: id, value: picked.value, values: picked.options, labels: labels, allowNone: true, noneLabel: labels[fallback]!, onChanged: (String? value) => _field(key, value));
  }

  @override
  Widget build(BuildContext context) {
    final RouteSpec spec = RouteSpec(route);
    final JsonMap wander = asMap(route['wander']);
    final int inUse = routeMoreKeys.where(route.containsKey).length;
    final bool moreProblems = routeMoreKeys.any((String key) => routeProblemsAt(problems, key, within: true).isNotEmpty);
    return dom.div(styles: const ArcaneStyleData(maxWidthCustom: 'var(--ui-form-max)').toStyles(), attributes: const <String, String>{'data-route-settings': ''}, <Widget>[
      Field(
        label: 'Order',
        forId: '$grid-order',
        tip: 'In order walks the points one after another. Back and forth turns at each end. Random picks the next point. Wander roams near each point.',
        docs: Docs.routes,
        child: AnimusSelect.values(
          id: '$grid-order',
          value: spec.order == 'sequence' ? null : spec.order,
          values: <String>[for (final String order in routeOrders) if (order != 'sequence') order],
          labels: routeOrderLabels,
          allowNone: true,
          noneLabel: routeOrderLabels['sequence']!,
          onChanged: (String? value) {
            onWrite(value == null ? (setRouteOrder(route, 'sequence')..remove('order')) : setRouteOrder(route, value));
            onOrder(value ?? 'sequence');
          },
        ),
      ),
      if (spec.order == 'sequence')
        Field(
          label: 'Loop',
          tip: 'Starts over from the first point after the last.',
          child: ArcaneToggleSwitch(value: route['loop'] == true, ariaLabel: 'Loop', onChanged: (bool value) => _field('loop', value ? true : null), size: ComponentSize.sm),
        ),
      Field(
        label: 'Speed',
        forId: '$grid-speed',
        hint: 'blocks a second, 1-5',
        tip: ownSpeed == null ? null : "Empty walks at the NPC's own speed.",
        problems: routeProblemsAt(problems, 'speed'),
        child: NumberInput(
          id: '$grid-speed',
          value: asDouble(route['speed']),
          integer: false,
          digits: 4,
          label: 'Route speed',
          placeholder: ownSpeed == null ? null : plainNumber(ownSpeed!),
          onChanged: (num? value) => _field('speed', value),
        ),
      ),
      MoreFields(
        label: inUse == 0 ? 'More settings' : 'More settings ($inUse set)',
        open: moreProblems,
        children: <Widget>[
          Field(
            label: 'Travel',
            forId: '$grid-mode',
            tip: 'Walking points stand on whole blocks; swimming and flying ones may float.',
            problems: routeProblemsAt(problems, 'mode'),
            child: _choice('$grid-mode', 'mode', routeModes, routeModeLabels, 'walk'),
          ),
          Field(
            label: 'Style',
            forId: '$grid-style',
            tip: 'Sneak and crawl go at 0.3 times the speed, sprint and swim at 1.3. Empty uses each NPC\'s own.',
            problems: routeProblemsAt(problems, 'style'),
            child: AnimusSelect.values(id: '$grid-style', value: asString(route['style']), values: movementStyles, labels: movementStyleLabels, allowNone: true, noneLabel: 'NPC\'s own', onChanged: (String? value) => _field('style', value)),
          ),
          Field(
            label: 'Wait',
            forId: '$grid-wait',
            tip: 'How long the NPC stands at each point. Range draws a new wait each time.',
            problems: routeProblemsAt(problems, 'waitTicks'),
            child: RouteWaitInput(id: '$grid-wait', label: 'Route', value: route['waitTicks'], placeholder: '1 s', onChanged: (Object? value) => _field('waitTicks', value)),
          ),
          Field(
            label: 'Random picks',
            forId: '$grid-seed',
            tip: 'Whether random choices repeat for each NPC or change every time the route starts.',
            problems: routeProblemsAt(problems, 'seed'),
            child: _choice('$grid-seed', 'seed', routeSeeds, routeSeedLabels, 'npc'),
          ),
          Field(
            label: 'Start at',
            forId: '$grid-start',
            tip: 'A random start spreads out NPCs that share the route.',
            problems: routeProblemsAt(problems, 'startOffset'),
            child: _choice('$grid-start', 'startOffset', routeStartOffsets, routeStartLabels, 'first'),
          ),
          if (spec.order != 'wander' || route.containsKey('jitter'))
            Field(
              label: 'Spread',
              forId: '$grid-jitter',
              hint: 'blocks, 0-$routeMaxJitter',
              tip: 'Each arrival lands up to this many blocks from the point.',
              problems: routeProblemsAt(problems, 'jitter'),
              child: NumberInput(id: '$grid-jitter', value: asInt(route['jitter']), digits: 3, label: 'Route spread', placeholder: '0', onChanged: (num? value) => _field('jitter', value?.toInt())),
            ),
          if (spec.order == 'random')
            Field(
              label: 'Link reach',
              forId: '$grid-links',
              hint: 'blocks, 1-256',
              tip: 'Random order only moves between points this close. Default 32.',
              problems: routeProblemsAt(problems, 'linkDistance'),
              child: NumberInput(id: '$grid-links', value: asDouble(route['linkDistance']), integer: false, digits: 4, label: 'Link reach', placeholder: '32', onChanged: (num? value) => _field('linkDistance', value)),
            ),
          if (spec.order == 'wander') ...<Widget>[
            Field(
              label: 'Wander width',
              forId: '$grid-wander-radius',
              hint: 'blocks, 1-32',
              problems: routeProblemsAt(problems, 'wander.radius'),
              child: NumberInput(id: '$grid-wander-radius', value: asInt(wander['radius']), digits: 3, label: 'Wander width', placeholder: '25', onChanged: (num? value) => _field('wander', _wander(wander, 'radius', value))),
            ),
            Field(
              label: 'Wander height',
              forId: '$grid-wander-height',
              hint: 'blocks, 0-8',
              problems: routeProblemsAt(problems, 'wander.height'),
              child: NumberInput(id: '$grid-wander-height', value: asInt(wander['height']), digits: 3, label: 'Wander height', placeholder: '3', onChanged: (num? value) => _field('wander', _wander(wander, 'height', value))),
            ),
          ],
        ],
      ),
    ]);
  }
}
