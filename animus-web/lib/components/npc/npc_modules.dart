/// Editors for two small NPC modules: `animus:shop` (the shop a click opens)
/// and `animus:mount` (another NPC this one rides).
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../gui/gui_fields.dart';
import '../inspect/inspect_fields.dart';
import '../../logic/npc/route_model.dart';
import 'npc_section.dart';
import 'route_editor.dart';

const String shopModule = 'animus:shop';
const String mountModule = 'animus:mount';

class ShopBindingEditor extends StatelessWidget {
  const ShopBindingEditor({
    required this.value,
    required this.shops,
    required this.onChanged,
    this.imported = false,
    this.diagnostics = const <Diagnostic>[],
    this.onOpen,
    super.key,
  });

  /// The module object, or null.
  final Object? value;

  /// Shop ids of the workspace.
  final List<String> shops;

  /// The whole new module, null to remove it.
  final void Function(Object? value) onChanged;

  /// The definition came from an import and needs its review accepted.
  final bool imported;
  final List<Diagnostic> diagnostics;
  final void Function(DocumentKind kind, String id)? onOpen;

  @override
  Widget build(BuildContext context) {
    const String shopId = 'animus-npc-shop';
    const String openOnId = 'animus-npc-shop-open-on';
    if (value == null) {
      if (shops.isEmpty) return const dom.div(<Widget>[]);
      return dom.div(classes: 'animus-npc-module', <Widget>[
        const NpcSubhead('Shop', help: 'No shop opens on click.'),
        const dom.label(classes: 'animus-sr-only', attributes: <String, String>{'for': shopId}, <Widget>[Component.text('Open a workspace shop on click')]),
        GuiSelect(
          id: shopId,
          value: null,
          options: shops,
          noneLabel: 'Open a workspace shop on click',
          onChanged: (String? id) {
            if (id != null) onChanged(<String, Object?>{'shop': id});
          },
        ),
      ]);
    }
    final JsonMap module = asMap(value);
    final String path = childPath(r'$.modules', shopModule);
    final String? shop = asString(module['shop']);
    return dom.div(classes: 'animus-npc-module', <Widget>[
      const NpcSubhead('Shop'),
      GuiRow(
        label: 'Shop',
        help: 'The shop a click opens. A sneaking administrator selects the NPC instead.',
        problems: diagnosticsAt(diagnostics, '$path.shop'),
        child: dom.div(classes: 'animus-gui-line', <Widget>[
          SuggestInput(
            value: shop ?? '',
            label: 'Shop id',
            placeholder: 'town:baker',
            suggestions: <FieldOption>[for (final String id in shops) FieldOption(id)],
            onChanged: (String text) => onChanged(<String, Object?>{...module, 'shop': text.trim()}),
          ),
          if (shop != null && shops.contains(shop) && onOpen != null)
            Button.ghost(label: 'Open', size: ButtonSize.small, onPressed: () => onOpen!(DocumentKind.shop, shop)),
          GuiIconButton(
            icon: ArcaneIcon.x(size: IconSize.sm),
            label: 'Remove the shop',
            onPressed: () => onChanged(null),
          ),
        ]),
      ),
      if (shop != null && !shops.contains(shop))
        const dom.span(classes: 'animus-gui-problem is-warning', <Widget>[Component.text('Not a shop in this workspace; the server may have it.')]),
      InspectRow(
        label: 'Open on',
        labelFor: openOnId,
        help: 'Which click opens it.',
        problems: diagnosticsAt(diagnostics, '$path.openOn'),
        child: GuiSelect(
          id: openOnId,
          value: asString(module['openOn']),
          options: const <String>['right', 'left', 'both'],
          allowNone: true,
          noneLabel: 'right (default)',
          onChanged: (String? click) {
            final JsonMap next = <String, Object?>{...module};
            if (click == null) {
              next.remove('openOn');
            } else {
              next['openOn'] = click;
            }
            onChanged(next);
          },
        ),
      ),
      if (imported)
        GuiSwitch(
          value: module['reviewAccepted'] == true,
          label: 'Review accepted',
          onChanged: (bool accepted) => onChanged(<String, Object?>{...module, 'reviewAccepted': accepted}),
        ),
    ]);
  }
}

/// `animus:navigation`: waypoints of the NPC's own, or a reusable route.
class NavigationEditor extends StatelessWidget {
  const NavigationEditor({
    required this.value,
    required this.routes,
    required this.onChanged,
    this.recordings = const <String, int?>{},
    this.origin,
    this.imported = false,
    this.diagnostics = const <Diagnostic>[],
    this.onOpen,
    super.key,
  });

  /// The module object, or null.
  final Object? value;

  /// The workspace's route documents.
  final List<AnimusDocument> routes;
  final void Function(Object? value) onChanged;

  /// The workspace's recordings for route steps, as [routeRecordings] gives them.
  final Map<String, int?> recordings;

  /// The NPC's x, y and z.
  final (double, double, double)? origin;
  final bool imported;
  final List<Diagnostic> diagnostics;
  final void Function(DocumentKind kind, String id)? onOpen;

  List<Object?> get _originPoint {
    final (double, double, double)? at = origin;
    return <Object?>[
      if (at != null) <String, Object?>{'x': at.$1.floor(), 'y': at.$2.floor(), 'z': at.$3.floor()},
    ];
  }

  @override
  Widget build(BuildContext context) {
    final JsonMap module = asMap(value);
    final String path = childPath(r'$.modules', navigationModule);
    final List<String> routeIds = <String>[for (final AnimusDocument route in routes) route.id];
    final String? reference = asString(module['route']);
    const String routeId = 'animus-npc-route';
    if (value == null) {
      return dom.div(classes: 'animus-npc-route', <Widget>[
        const dom.p(classes: 'animus-gui-help', <Widget>[
          Component.text('The NPC stands still. Give it waypoints of its own or a reusable route under routes/.'),
        ]),
        dom.div(classes: 'animus-row', <Widget>[
          Button.secondary(
            label: 'Add waypoints',
            size: ButtonSize.small,
            onPressed: () => onChanged(<String, Object?>{'enabled': true, 'points': _originPoint}),
          ),
          if (routeIds.isNotEmpty) ...<Widget>[
            const dom.label(classes: 'animus-sr-only', attributes: <String, String>{'for': routeId}, <Widget>[Component.text('Use a reusable route')]),
            GuiSelect(
              id: routeId,
              value: null,
              options: routeIds,
              noneLabel: 'Use a reusable route',
              onChanged: (String? id) {
                if (id != null) onChanged(<String, Object?>{'enabled': true, 'route': id});
              },
            ),
          ],
        ]),
      ]);
    }
    final AnimusDocument? routeDocument = reference == null ? null : routes.where((AnimusDocument d) => d.id == reference).firstOrNull;
    return dom.div(classes: 'animus-npc-route', <Widget>[
      dom.div(classes: 'animus-row', <Widget>[
        GuiSwitch(value: module['enabled'] == true, label: 'Enabled', onChanged: (bool on) => onChanged(<String, Object?>{...module, 'enabled': on})),
        if (imported)
          GuiSwitch(
            value: module['reviewAccepted'] == true,
            label: 'Review accepted',
            onChanged: (bool accepted) => onChanged(<String, Object?>{...module, 'reviewAccepted': accepted}),
          ),
        const dom.label(classes: 'animus-sr-only', attributes: <String, String>{'for': routeId}, <Widget>[Component.text('Route source')]),
        GuiSelect(
          id: routeId,
          value: reference,
          options: routeIds,
          allowNone: true,
          noneLabel: 'Waypoints of its own',
          onChanged: (String? id) {
            final JsonMap next = <String, Object?>{
              for (final MapEntry<String, Object?> entry in module.entries)
                if (!routeFields.contains(entry.key) && entry.key != 'route') entry.key: entry.value,
            };
            if (id != null) {
              next['route'] = id;
            } else {
              next['points'] = _originPoint;
            }
            onChanged(next);
          },
        ),
        GuiIconButton(
          icon: ArcaneIcon.x(size: IconSize.sm),
          label: 'Remove the route',
          onPressed: () => onChanged(null),
        ),
      ]),
      if (reference != null) ...<Widget>[
        dom.p(classes: 'animus-gui-help', <Widget>[
          Component.text('Follows routes/$reference.json; edit the route there. '),
          if (routeDocument != null && onOpen != null)
            Button.ghost(label: 'Open route $reference', size: ButtonSize.small, onPressed: () => onOpen!(DocumentKind.route, reference)),
        ]),
        if (routeDocument == null)
          const dom.span(classes: 'animus-gui-problem is-warning', <Widget>[Component.text('Not a route in this workspace; the server may have it.')])
        else
          RouteEditor(value: asMap(routeDocument.json), onChanged: (JsonMap _) {}, origin: origin, recordings: recordings, readOnly: true),
      ] else
        RouteEditor(value: module, origin: origin, path: path, diagnostics: diagnostics, recordings: recordings, onChanged: (JsonMap next) => onChanged(next)),
    ]);
  }
}

class MountEditor extends StatelessWidget {
  const MountEditor({required this.value, required this.vehicles, required this.onChanged, this.diagnostics = const <Diagnostic>[], super.key});

  /// The module object, or null.
  final Object? value;

  /// Local ids of the other NPCs, which this one may ride.
  final List<String> vehicles;
  final void Function(Object? value) onChanged;
  final List<Diagnostic> diagnostics;

  @override
  Widget build(BuildContext context) {
    const String vehicleId = 'animus-npc-vehicle';
    if (value == null) {
      if (vehicles.isEmpty) return const dom.div(<Widget>[]);
      return dom.div(classes: 'animus-npc-module', <Widget>[
        const NpcSubhead('Mount', help: 'The NPC rides nothing.'),
        const dom.label(classes: 'animus-sr-only', attributes: <String, String>{'for': vehicleId}, <Widget>[Component.text('Ride another NPC')]),
        GuiSelect(
          id: vehicleId,
          value: null,
          options: vehicles,
          noneLabel: 'Ride another NPC',
          onChanged: (String? id) {
            if (id != null) onChanged(<String, Object?>{'enabled': true, 'vehicle': id});
          },
        ),
      ]);
    }
    final JsonMap module = asMap(value);
    final String? vehicle = asString(module['vehicle']);
    final String path = childPath(r'$.modules', mountModule);
    return dom.div(classes: 'animus-npc-module', <Widget>[
      const NpcSubhead('Mount'),
      dom.div(classes: 'animus-row', <Widget>[
        GuiSwitch(value: module['enabled'] != false, label: 'Riding', onChanged: (bool on) => onChanged(<String, Object?>{...module, 'enabled': on})),
        GuiIconButton(
          icon: ArcaneIcon.x(size: IconSize.sm),
          label: 'Stop riding',
          onPressed: () => onChanged(null),
        ),
      ]),
      InspectRow(
        label: 'Vehicle',
        labelFor: vehicleId,
        help: 'The NPC this one rides, by its id.',
        problems: diagnosticsAt(diagnostics, path, nested: true),
        child: GuiSelect(
          id: vehicleId,
          value: vehicle,
          options: vehicles,
          noneLabel: 'Pick the NPC to ride',
          onChanged: (String? id) => onChanged(<String, Object?>{...module, 'vehicle': ?id}),
        ),
      ),
      if (vehicle != null && !vehicles.contains(vehicle))
        const dom.span(classes: 'animus-gui-problem is-warning', <Widget>[Component.text('Not an NPC in this workspace; the server may have it.')]),
    ]);
  }
}
