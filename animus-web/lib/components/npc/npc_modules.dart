/// Editors for two small NPC modules: `animus:shop` (the shop a click opens)
/// and `animus:mount` (another NPC this one rides).
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../gui/gui_fields.dart';
import '../select/picker_scope.dart';
import '../inspect/inspect_fields.dart';
import '../../logic/npc/route_model.dart';
import 'npc_section.dart';
import 'route_editor.dart';
import '../../logic/suggest/field_profile.dart';
import '../../logic/suggest/field_profiles.dart';

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

  static const String _emptyField = 'Open a workspace shop on click';
  static const String _pickField = 'Pick a shop';

  /// "+ New shop": the create step, coming back with the new shop picked
  /// in the dropdown named [field].
  Widget _newShop(BuildContext context, String field) {
    final PickerScope? scope = PickerScope.of(context);
    if (scope == null) return const dom.span(<Widget>[]);
    return Button.ghost(label: '+ New shop', size: ButtonSize.small, onPressed: () => scope.run(newActionId, DocumentKind.shop, field, ''));
  }

  @override
  Widget build(BuildContext context) {
    const String shopId = 'animus-npc-shop';
    const String openOnId = 'animus-npc-shop-open-on';
    if (value == null) {
      return dom.div(classes: 'animus-npc-module', <Widget>[
        const NpcSubhead('Shop', help: 'No shop.', tip: 'A shop document that opens when a player clicks the NPC.', docs: Docs.shops),
        const dom.label(classes: 'animus-sr-only', attributes: <String, String>{'for': shopId}, <Widget>[Component.text('Open a workspace shop on click')]),
        dom.div(classes: 'animus-gui-line', <Widget>[
          GuiSelect(
            references: DocumentKind.shop,
            id: shopId,
            value: null,
            options: shops,
            noneLabel: _emptyField,
            onChanged: (String? id) {
              if (id != null) onChanged(<String, Object?>{'shop': id});
            },
          ),
          _newShop(context, _emptyField),
        ]),
      ]);
    }
    final JsonMap module = asMap(value);
    final String path = childPath(r'$.modules', shopModule);
    final String? shop = asString(module['shop']);
    return dom.div(classes: 'animus-npc-module', <Widget>[
      const NpcSubhead('Shop', tip: 'A shop document that opens when a player clicks the NPC.', docs: Docs.shops),
      InspectRow(
        label: 'Shop',
        labelFor: shopId,
        help: 'Opens on click. Sneaking admins select the NPC.',
        problems: diagnosticsAt(diagnostics, '$path.shop'),
        child: dom.div(classes: 'animus-gui-line', <Widget>[
          GuiSelect(
            references: DocumentKind.shop,
            id: shopId,
            value: shop == null || shop.isEmpty ? null : shop,
            options: shops,
            noneLabel: _pickField,
            onChanged: (String? id) {
              if (id != null) onChanged(<String, Object?>{...module, 'shop': id});
            },
          ),
          if (shop != null && shops.contains(shop) && onOpen != null)
            Button.secondary(label: 'Edit shop', size: ButtonSize.small, attributes: <String, String>{'aria-label': 'Edit shop $shop'}, onPressed: () => onOpen!(DocumentKind.shop, shop)),
          _newShop(context, _pickField),
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

/// `animus:navigation`: waypoints of the NPC's own, a reusable route, or a
/// recording played once, in a loop or back and forth.
class NavigationEditor extends StatelessWidget {
  const NavigationEditor({
    required this.value,
    required this.routes,
    required this.onChanged,
    this.recordings = const <String, int?>{},
    this.origin,
    this.world,
    this.imported = false,
    this.diagnostics = const <Diagnostic>[],
    this.onOpen,
    this.onCopyRoute,
    this.copyLabel = 'Make a copy for this NPC',
    super.key,
  });

  /// The module object, or null.
  final Object? value;

  /// Copies the referenced route to a new route document this NPC alone
  /// uses; null hides the button.
  final void Function()? onCopyRoute;
  final String copyLabel;

  /// The workspace's route documents.
  final List<AnimusDocument> routes;

  /// The new module; [from] is the module a route edit was worked out from.
  final void Function(Object? value, {JsonMap? from}) onChanged;

  /// The workspace's recordings for route steps, as [routeRecordings] gives them.
  final Map<String, int?> recordings;

  /// The NPC's x, y and z.
  final (double, double, double)? origin;

  /// The NPC's world, whose terrain the route grid draws.
  final String? world;
  final bool imported;
  final List<Diagnostic> diagnostics;
  final void Function(DocumentKind kind, String id)? onOpen;

  List<Object?> get _originPoint {
    final (double, double, double)? at = origin;
    return <Object?>[
      if (at != null) <String, Object?>{'x': at.$1.floor(), 'y': at.$2.floor(), 'z': at.$3.floor()},
    ];
  }

  static const Map<String, String> _sourceLabels = <String, String>{
    'points': 'Waypoints of its own',
    'route': 'A reusable route',
    'recording': 'A recording',
  };

  static const Map<String, String> _playbackHelp = <String, String>{
    'once': 'Plays once, stops on the last frame.',
    'loop': 'Repeats. End near the start for a clean loop.',
    'pingpong': 'Forward, then backward, forever.',
  };

  /// The module moved to [source]: the old source's fields go, enabled,
  /// reviewAccepted and unknown keys stay.
  JsonMap _withSource(JsonMap module, NavigationSource source, List<String> routeIds) {
    final JsonMap next = <String, Object?>{
      for (final MapEntry<String, Object?> entry in module.entries)
        if (!routeFields.contains(entry.key) && entry.key != 'route' && entry.key != 'recording' && entry.key != 'playback') entry.key: entry.value,
    };
    switch (source) {
      case NavigationSource.points:
        next['points'] = _originPoint;
      case NavigationSource.route:
        next['route'] = routeIds.firstOrNull ?? '';
      case NavigationSource.recording:
        return setNavigationRecording(next, recordings.keys.firstOrNull ?? '');
    }
    return next;
  }

  @override
  Widget build(BuildContext context) {
    final JsonMap module = asMap(value);
    final String path = childPath(r'$.modules', navigationModule);
    final List<String> routeIds = <String>[for (final AnimusDocument route in routes) route.id];
    const String routeId = 'animus-npc-route';
    const String sourceId = 'animus-npc-nav-source';
    if (value == null) {
      return dom.div(classes: 'animus-npc-route', <Widget>[
        const dom.p(classes: 'animus-gui-help', <Widget>[
          Component.text('Stands still.'),
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
              references: DocumentKind.route,
              id: routeId,
              value: null,
              options: routeIds,
              noneLabel: 'Use a reusable route',
              onChanged: (String? id) {
                if (id != null) onChanged(<String, Object?>{'enabled': true, 'route': id});
              },
            ),
          ],
          Button.ghost(
            id: 'animus-npc-add-recording',
            label: 'Play a recording',
            size: ButtonSize.small,
            onPressed: () => onChanged(setNavigationRecording(<String, Object?>{'enabled': true}, recordings.keys.firstOrNull ?? '')),
          ),
        ]),
      ]);
    }
    final NavigationSource source = navigationSource(module);
    final String? reference = asString(module['route']);
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
        GuiIconButton(
          icon: ArcaneIcon.x(size: IconSize.sm),
          label: 'Remove the route',
          onPressed: () => onChanged(null),
        ),
      ]),
      InspectRow(
        label: 'Moves by',
        tip: 'Where the waypoints come from: written on this NPC, a route document, or a recorded performance.',
        labelFor: sourceId,
        help: 'One source at a time.',
        problems: diagnosticsAt(diagnostics, path),
        child: GuiSelect(
          id: sourceId,
          value: source.name,
          options: <String>[for (final NavigationSource option in NavigationSource.values) option.name],
          labels: _sourceLabels,
          onChanged: (String? name) {
            if (name == null || name == source.name) return;
            onChanged(_withSource(module, NavigationSource.values.byName(name), routeIds));
          },
        ),
      ),
      ...switch (source) {
        NavigationSource.recording => _recording(module, path),
        NavigationSource.route => <Widget>[
            InspectRow(
              label: 'Route',
              labelFor: routeId,
              help: 'Edit it in routes/<id>.json.',
              problems: diagnosticsAt(diagnostics, childPath(path, 'route')),
              child: routeIds.isEmpty
                  ? GuiTextInput(id: routeId, value: reference ?? '', label: 'Route id', profile: idProfile(const ValueRef.document(DocumentKind.route)), placeholder: 'town-patrol', onChanged: (String id) => onChanged(<String, Object?>{...module, 'route': id.trim()}))
                  : GuiSelect(references: DocumentKind.route, id: routeId, value: reference, options: routeIds, onChanged: (String? id) => onChanged(<String, Object?>{...module, 'route': id ?? ''})),
            ),
            if (routeDocument != null && (onOpen != null || onCopyRoute != null))
              dom.div(classes: 'animus-row', <Widget>[
                if (onOpen != null)
                  Button.secondary(
                    label: 'Edit route',
                    size: ButtonSize.small,
                    attributes: <String, String>{'aria-label': 'Edit route $reference'},
                    onPressed: () => onOpen!(DocumentKind.route, reference!),
                  ),
                if (onCopyRoute != null) Button.ghost(label: copyLabel, size: ButtonSize.small, onPressed: onCopyRoute),
              ]),
            if (routeDocument == null)
              const dom.span(classes: 'animus-gui-problem is-warning', <Widget>[Component.text('Not a route in this workspace; the server may have it.')])
            else
              RouteEditor(value: asMap(routeDocument.json), onChanged: (JsonMap _, JsonMap _) {}, origin: origin, recordings: recordings, readOnly: true),
          ],
        NavigationSource.points => <Widget>[
            RouteEditor(value: module, origin: origin, world: world, path: path, diagnostics: diagnostics, recordings: recordings, onChanged: (JsonMap next, JsonMap from) => onChanged(next, from: from)),
          ],
      },
    ]);
  }

  /// The recording a module plays and how.
  List<Widget> _recording(JsonMap module, String path) {
    const String recordingId = 'animus-npc-recording';
    const String playbackId = 'animus-npc-playback';
    final String recording = asString(module['recording']) ?? '';
    final String? playback = asString(module['playback']);
    final List<(String, String)> problems = navigationProblems(module);
    List<Diagnostic> at(String field) => <Diagnostic>[
          for (final (String name, String message) in problems)
            if (name == field) Diagnostic(DiagnosticSeverity.error, childPath(path, name), message),
          ...diagnosticsAt(diagnostics, childPath(path, field)),
        ];
    final bool known = recordings.containsKey(recording);
    final int? duration = recordings[recording];
    return <Widget>[
      InspectRow(
        label: 'Recording',
        labelFor: recordingId,
        help: recordings.isEmpty
            ? 'No recordings. Record with /animus record start <id>, or type an id.'
            : (duration == null ? '' : '$duration ticks, ${(duration / 20).toStringAsFixed(1)} seconds.'),
        problems: at('recording'),
        child: recordings.isEmpty
            ? GuiTextInput(id: recordingId, value: recording, label: 'Recording id', profile: idProfile(const ValueRef.document(DocumentKind.recording)), placeholder: 'market-loop', onChanged: (String id) => onChanged(<String, Object?>{...module, 'recording': id.trim()}))
            : GuiSelect(
                references: DocumentKind.recording,
                id: recordingId,
                value: recording.isEmpty ? null : recording,
                options: recordings.keys.toList(),
                onChanged: (String? id) => onChanged(<String, Object?>{...module, 'recording': id ?? ''}),
              ),
      ),
      if (recording.isNotEmpty && !known && recordings.isNotEmpty)
        const dom.span(classes: 'animus-gui-problem is-warning', <Widget>[Component.text('Not a recording in this workspace; the server may have it.')]),
      if (known && onOpen != null)
        dom.div(classes: 'animus-row', <Widget>[
          Button.ghost(id: 'animus-npc-open-recording', label: 'Open recording $recording', size: ButtonSize.small, onPressed: () => onOpen!(DocumentKind.recording, recording)),
        ]),
      InspectRow(
        label: 'Playback',
        labelFor: playbackId,
        help: playback == null ? "The recording's own playback, else loop." : _playbackHelp[playback] ?? 'Expected once, loop or pingpong.',
        problems: at('playback'),
        child: GuiSelect(
          id: playbackId,
          value: playback,
          options: navigationPlaybacks,
          allowNone: true,
          noneLabel: 'recording default',
          onChanged: (String? next) {
            final JsonMap updated = <String, Object?>{...module};
            if (next == null) {
              updated.remove('playback');
            } else {
              updated['playback'] = next;
            }
            onChanged(updated);
          },
        ),
      ),
      const dom.p(classes: 'animus-gui-help', <Widget>[
        Component.text('Walks to the first frame, then plays. Combat and /animus navigate interrupt it.'),
      ]),
    ];
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
        const NpcSubhead('Mount', help: 'Rides nothing.', tip: 'Another NPC this one rides, by id.', docs: Docs.mounts),
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
      const NpcSubhead('Mount', tip: 'Another NPC this one rides, by id.', docs: Docs.mounts),
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
        help: 'NPC id to ride.',
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
