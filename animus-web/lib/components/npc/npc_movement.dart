/// The Movement tab: whether the NPC stands still, walks a route (points it
/// walks between) or replays a recording, each offered beside the other
/// with a line that tells them apart. The route editor is the one route
/// documents open in, embedded as it is. Advanced adds how it walks, riding
/// another NPC and turning movement off.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/npc/npc_catalog.dart';
import '../../logic/npc/npc_tabs.dart';
import '../../logic/npc/route_copy.dart';
import '../../logic/npc/route_model.dart';
import '../../logic/recording/recording_facts.dart' show playbackLabels;
import '../../logic/plural.dart';
import '../../logic/suggest/field_profile.dart';
import '../../logic/suggest/field_profiles.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../ui/advanced_in_use.dart';
import '../../ui/action_row.dart';
import '../../ui/field.dart';
import '../../ui/mode.dart';
import '../../ui/section.dart';
import '../../ui/toggle_choice.dart';
import '../conversation/conversation_attach.dart' show ConversationCommit;
import '../gui/gui_fields.dart';
import '../inspect/inspect_fields.dart';
import 'npc_form.dart';
import 'npc_parts.dart';
import 'route_editor.dart';
import 'npc_layout.dart';
import '../suggest/autocomplete_input.dart';
import '../select/animus_select.dart';
import '../../ui/number_input.dart';
import '../../ui/problem_text.dart';

/// The value of the route select that stands for the NPC's own points.
const String _ownPoints = '+own';

class NpcMovementTab extends StatelessWidget {
  const NpcMovementTab({required this.form, required this.extras, required this.onReveal, this.inUse = 0, super.key});

  final NpcForm form;
  final bool extras;
  final void Function() onReveal;

  /// The settings Simple counts on this tab's line.
  final int inUse;

  @override
  Widget build(BuildContext context) {
    final NpcForm form = this.form;
    final JsonMap doc = form.doc;
    final JsonMap modules = form.modules;
    return tabBody(<Widget>[
      MovementPicker(
        value: modules[navigationModule],
        routes: form.documents(DocumentKind.route),
        recordings: routeRecordings(form.workspaceDocuments),
        origin: form.origin,
        world: form.world,
        diagnostics: form.diagnostics,
        ownStyle: asString(asMap(doc['movement'])['style']),
        ownSpeed: asDouble(asMap(doc['movement'])['speed']),
        onOpen: form.onOpen,
        onCopyRoute: _copyRoute(form),
        copyLabel: form.group ? 'Copy for this group' : 'Copy for this NPC',
        onChanged: (Object? value, {JsonMap? from}) => form.putModule(navigationModule, value, from: from),
      ),
      if (!extras) AdvancedInUse(count: inUse, onShow: onReveal),
      if (extras) ...<Widget>[
        if (modules.containsKey(navigationModule))
          Field(
            label: 'Moving',
            tip: 'Off keeps the route or recording but the NPC stands still.',
            child: ArcaneToggleSwitch(
              value: asMap(modules[navigationModule])['enabled'] != false,
              ariaLabel: 'Moving',
              onChanged: (bool on) => form.putModule(navigationModule, <String, Object?>{...asMap(modules[navigationModule]), 'enabled': on}),
              size: ComponentSize.sm,
            ),
          ),
        AppSection(
          title: 'Walking',
          open: doc.containsKey('movement'),
          tip: "How the NPC walks wherever it walks. A route's own speed wins over this one.",
          docs: Docs.movement,
          children: <Widget>[
            if (form.spec('movement').schema.isNotEmpty)
              InspectObject(
                key: const ValueKey<String>('npc:movement'),
                schema: form.schema,
                node: form.spec('movement').schema,
                value: asMap(doc['movement']),
                path: r'$.movement',
                diagnostics: form.diagnostics,
                // The NPC's own route has the one Speed field.
                skip: _ownRoute(modules) ? const <String>{'speed'} : const <String>{},
                onChanged: (JsonMap value) => form.write(const <JsonStep>['movement'], value.isEmpty ? null : value),
              ),
          ],
        ),
        AppSection(
          title: 'Riding',
          open: modules.containsKey(mountModuleKey),
          tip: 'Another NPC this one rides.',
          docs: Docs.mounts,
          children: <Widget>[
            MountEditor(
              value: modules[mountModuleKey],
              vehicles: <String>[
                for (final NpcEntry entry in npcEntries(form.workspaceDocuments))
                  if (entry.key != form.document.id) entry.localId,
              ],
              diagnostics: form.diagnostics,
              onChanged: (Object? value) => form.putModule(mountModuleKey, value),
            ),
          ],
        ),
      ],
    ]);
  }

  /// Whether the NPC walks points of its own, whose route form has Speed.
  static bool _ownRoute(JsonMap modules) =>
      modules[navigationModule] is Map<String, Object?> && navigationSource(asMap(modules[navigationModule])) == NavigationSource.points;

  /// Copies the saved route the NPC walks into a route of its own, in one
  /// commit; null when there is nothing to copy.
  static void Function()? _copyRoute(NpcForm form) {
    final ConversationCommit? commit = form.onCommit;
    final String? reference = asString(asMap(form.modules[navigationModule])['route']);
    final AnimusDocument? route = reference == null ? null : form.documents(DocumentKind.route).where((AnimusDocument d) => d.id == reference).firstOrNull;
    if (commit == null || route == null) return null;
    return () {
      final RouteCopy copy = copyRouteFor(form.document, route, form.workspaceDocuments);
      commit(copy.changes, 'copy route $reference to ${copy.id}');
    };
  }
}

/// `animus:navigation`: stands still, a route (its own points or a saved
/// route) or a recording.
class MovementPicker extends StatelessWidget {
  const MovementPicker({
    required this.value,
    required this.routes,
    required this.onChanged,
    this.recordings = const <String, int?>{},
    this.origin,
    this.world,
    this.diagnostics = const <Diagnostic>[],
    this.onOpen,
    this.onCopyRoute,
    this.copyLabel = 'Copy for this NPC',
    this.ownStyle,
    this.ownSpeed,
    super.key,
  });

  /// The module object, or null.
  final Object? value;

  /// The workspace's route documents.
  final List<AnimusDocument> routes;

  /// The new module (null removes it); [from] is the module a route edit
  /// was worked out from.
  final void Function(Object? value, {JsonMap? from}) onChanged;

  /// The workspace's recordings, as [routeRecordings] gives them.
  final Map<String, int?> recordings;
  final (double, double, double)? origin;
  final String? world;
  final List<Diagnostic> diagnostics;
  final void Function(DocumentKind kind, String id)? onOpen;

  /// Copies the saved route to one this NPC alone walks; null hides it.
  final void Function()? onCopyRoute;
  final String copyLabel;

  /// The NPC's own `movement.style`, which decides whether its points may
  /// hang in the air.
  final String? ownStyle;

  /// The NPC's own `movement.speed`, which its route walks at while it sets
  /// none.
  final num? ownSpeed;

  List<Object?> get _originPoint {
    final (double, double, double)? at = origin;
    return <Object?>[
      if (at != null) <String, Object?>{'x': at.$1.floor(), 'y': at.$2.floor(), 'z': at.$3.floor()},
    ];
  }

  /// The module moved to [source]: the old source's fields go, `enabled`
  /// and unknown keys stay.
  JsonMap _withSource(JsonMap module, NavigationSource source, {String? route}) {
    final JsonMap next = <String, Object?>{
      for (final MapEntry<String, Object?> entry in module.entries)
        if (!routeFields.contains(entry.key) && entry.key != 'route' && entry.key != 'recording' && entry.key != 'playback') entry.key: entry.value,
    };
    next['enabled'] ??= true;
    switch (source) {
      case NavigationSource.points:
        next['points'] = _originPoint;
      case NavigationSource.route:
        next['route'] = route ?? routes.firstOrNull?.id ?? '';
      case NavigationSource.recording:
        return setNavigationRecording(next, recordings.keys.firstOrNull ?? '');
    }
    return next;
  }

  @override
  Widget build(BuildContext context) {
    final JsonMap module = asMap(value);
    final String path = childPath(r'$.modules', navigationModule);
    final NavigationSource? source = value == null ? null : navigationSource(module);
    final String choice = switch (source) {
      null => 'none',
      NavigationSource.recording => 'recording',
      _ => 'route',
    };
    return dom.div(classes: 'animus-npc-movement', attributes: const <String, String>{'data-npc': 'movement'}, <Widget>[
      Field(
        label: 'Moves',
        tip: 'Route: walks point to point. Recording: replays what you did.',
        docs: Docs.routes,
        problems: diagnosticsAt(diagnostics, path),
        child: ToggleChoice<String>(
          label: 'Moves',
          options: const <ChoiceOption<String>>[
            ChoiceOption<String>('none', 'Stands still'),
            ChoiceOption<String>('route', 'Route'),
            ChoiceOption<String>('recording', 'Recording'),
          ],
          value: choice,
          onChanged: (String next) {
            if (next == choice) return;
            if (next == 'none') {
              onChanged(null);
            } else {
              onChanged(_withSource(module, next == 'route' ? NavigationSource.points : NavigationSource.recording));
            }
          },
        ),
      ),
      ...switch (source) {
        null => const <Widget>[],
        NavigationSource.recording => _recording(context, module, path),
        _ => _route(context, module, path, source),
      },
    ]);
  }

  List<Widget> _route(BuildContext context, JsonMap module, String path, NavigationSource source) {
    const String routeId = 'animus-npc-route';
    final String? reference = asString(module['route']);
    final AnimusDocument? routeDocument = reference == null ? null : routes.where((AnimusDocument d) => d.id == reference).firstOrNull;
    final List<String> routeIds = <String>[for (final AnimusDocument route in routes) route.id];
    final void Function(DocumentKind, String)? open = onOpen;
    final Widget select = AnimusSelect.values(
      references: DocumentKind.route,
      id: routeId,
      value: source == NavigationSource.points ? _ownPoints : reference,
      values: <String>[_ownPoints, ...routeIds, if (reference != null && reference.isNotEmpty && !routeIds.contains(reference)) reference],
      labels: <String, String>{_ownPoints: 'Its own points'},
      onChanged: (String? id) {
        if (id == null || id == _ownPoints) {
          if (source != NavigationSource.points) onChanged(_withSource(module, NavigationSource.points));
        } else {
          onChanged(source == NavigationSource.route ? <String, Object?>{...module, 'route': id} : _withSource(module, NavigationSource.route, route: id));
        }
      },
    );
    final List<RowAction> actions = <RowAction>[
      if (routeDocument != null && open != null) RowAction('Edit route', () => open(DocumentKind.route, reference!), attributes: <String, String>{'aria-label': 'Edit route $reference'}),
      if (routeDocument != null && onCopyRoute != null) RowAction(copyLabel, onCopyRoute),
    ];
    return <Widget>[
      Field(
        label: 'Route',
        forId: routeId,
        tip: "The NPC's own points, or a saved route other NPCs can walk too.",
        docs: Docs.routes,
        problems: diagnosticsAt(diagnostics, childPath(path, 'route')),
        // Actions only for a saved route; alone, the select fills the field
        // column like every other select.
        child: actions.isEmpty ? select : ActionRow(label: 'Route', leading: <Widget>[NpcPick(child: select)], secondary: actions),
      ),
      if (source == NavigationSource.route && routeDocument == null)
        problemLine(DiagnosticSeverity.warning, 'Not a route in this workspace; the server may have it.'),
      if (source == NavigationSource.route && routeDocument != null)
        RouteEditor(value: asMap(routeDocument.json), onChanged: (JsonMap _, JsonMap _) {}, origin: origin, recordings: recordings, readOnly: true),
      if (source == NavigationSource.points)
        RouteEditor(value: module, origin: origin, world: world, path: path, diagnostics: diagnostics, recordings: recordings, ownStyle: ownStyle, ownSpeed: ownSpeed, onChanged: (JsonMap next, JsonMap from) => onChanged(next, from: from)),
      MoreFields(label: 'Pause', open: module.containsKey('pause'), children: <Widget>[_Pause(module: module, path: path, diagnostics: diagnostics, onChanged: onChanged)]),
    ];
  }

  /// The recording the module plays and how.
  List<Widget> _recording(BuildContext context, JsonMap module, String path) {
    const String recordingId = 'animus-npc-recording';
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
    final void Function(DocumentKind, String)? open = onOpen;
    final Widget picker = recordings.isEmpty
        ? AutocompleteInput(id: recordingId, value: recording, label: 'Recording id', profile: idProfile(const ValueRef.document(DocumentKind.recording)), onChanged: (String id) => onChanged(<String, Object?>{...module, 'recording': id.trim()}))
        : AnimusSelect.values(
            references: DocumentKind.recording,
            id: recordingId,
            value: recording.isEmpty ? null : recording,
            values: recordings.keys.toList(),
            onChanged: (String? id) => onChanged(<String, Object?>{...module, 'recording': id ?? ''}),
          );
    return <Widget>[
      Field(
        label: 'Recording',
        forId: recordingId,
        hint: recordings.isEmpty ? 'No recordings yet. Record one in game.' : (duration == null ? null : '${(duration / 20).toStringAsFixed(1)} seconds'),
        tip: 'Record one in game with /animus record start <name>. The NPC walks to the first frame, then plays it.',
        docs: Docs.recordingPlayback,
        problems: <Diagnostic>[
          ...at('recording'),
          if (recording.isNotEmpty && !known && recordings.isNotEmpty) Diagnostic(DiagnosticSeverity.warning, childPath(path, 'recording'), 'Not in this workspace; the server may have it.'),
        ],
        child: known && open != null
            ? ActionRow(
                label: 'Recording',
                leading: <Widget>[NpcPick(child: picker)],
                secondary: <RowAction>[
                  RowAction('Open', () => open(DocumentKind.recording, recording), id: 'animus-npc-open-recording', attributes: <String, String>{'aria-label': 'Open recording $recording'}),
                ],
              )
            : picker,
      ),
      // The same toggle group as the recording page's Playback, plus the
      // recording's own setting.
      Field(
        label: 'Playback',
        tip: "Once stops on the last frame. Loop repeats; end near the start for a clean loop. Back and forth plays forward, then backward. Recording's uses the recording's own playback.",
        problems: at('playback'),
        child: ToggleChoice<String?>(
          label: 'Playback',
          options: <ChoiceOption<String?>>[
            const ChoiceOption<String?>(null, "Recording's"),
            for (final String each in navigationPlaybacks) ChoiceOption<String?>(each, playbackLabels[each] ?? each),
          ],
          value: navigationPlaybacks.contains(playback) ? playback : null,
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
    ];
  }
}

/// The route's pause: it stands still a while when a player comes near or
/// clicks the NPC, so it can talk to them.
class _Pause extends StatelessWidget {
  const _Pause({required this.module, required this.path, required this.diagnostics, required this.onChanged});

  final JsonMap module;
  final String path;
  final List<Diagnostic> diagnostics;
  final void Function(Object? value, {JsonMap? from}) onChanged;

  @override
  Widget build(BuildContext context) {
    final JsonMap pause = asMap(module['pause']);
    final String at = childPath(path, 'pause');
    void put(String field, Object? value) {
      final JsonMap next = <String, Object?>{...pause};
      if (value == null || value == false) {
        next.remove(field);
      } else {
        next[field] = value;
      }
      final JsonMap updated = <String, Object?>{...module};
      if (next.isEmpty) {
        updated.remove('pause');
      } else {
        updated['pause'] = next;
      }
      onChanged(updated);
    }

    final bool advanced = ModeScope.advanced(context);
    return dom.div(classes: 'animus-npc-pause', <Widget>[
      Field(
        label: 'Near players',
        forId: 'animus-npc-pause-range',
        help: 'Blocks, up to 64',
        tip: 'Stops while a player is this close. Empty: players nearby never stop it.',
        problems: diagnosticsAt(diagnostics, childPath(at, 'playerRange')),
        child: NumberInput(digits: 5, id: 'animus-npc-pause-range', value: asDouble(pause['playerRange']), integer: false, label: 'Pause near players', placeholder: '0', onChanged: (num? value) => put('playerRange', value)),
      ),
      Field(
        label: 'On click',
        tip: 'Stops when a player right-clicks the NPC.',
        child: ArcaneToggleSwitch(value: pause['onClick'] == true, ariaLabel: 'Pause on right-click', onChanged: (bool on) => put('onClick', on), size: ComponentSize.sm),
      ),
      Field(
        label: 'For',
        forId: 'animus-npc-pause-ticks',
        help: '${countOf(asInt(pause['ticks']) ?? 20, 'tick')} (20 a second)',
        tip: 'How long it stands still, 1 to 12000 ticks. 20 by default.',
        problems: diagnosticsAt(diagnostics, childPath(at, 'ticks')),
        child: NumberInput(digits: 5, id: 'animus-npc-pause-ticks', value: asInt(pause['ticks']), label: 'Pause ticks', placeholder: '20', onChanged: (num? value) => put('ticks', value?.toInt())),
      ),
      if (advanced || pause.containsKey('lockoutTicks'))
        Field(
          label: 'Wait before next',
          forId: 'animus-npc-pause-lockout',
          help: 'Ticks',
          tip: 'No new pause for this long after one starts.',
          problems: diagnosticsAt(diagnostics, childPath(at, 'lockoutTicks')),
          child: NumberInput(digits: 5, id: 'animus-npc-pause-lockout', value: asInt(pause['lockoutTicks']), label: 'Pause lockout ticks', placeholder: '0', onChanged: (num? value) => put('lockoutTicks', value?.toInt())),
        ),
    ]);
  }
}

/// `animus:mount`: another NPC this one rides.
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
    final JsonMap module = asMap(value);
    final String? vehicle = asString(module['vehicle']);
    final String path = childPath(r'$.modules', mountModuleKey);
    return dom.div(classes: 'animus-npc-mount', <Widget>[
      Field(
        label: 'Rides',
        forId: vehicleId,
        problems: <Diagnostic>[
          ...diagnosticsAt(diagnostics, path, nested: true),
          if (vehicle != null && !vehicles.contains(vehicle)) Diagnostic(DiagnosticSeverity.warning, path, 'Not an NPC in this workspace; the server may have it.'),
        ],
        child: AnimusSelect.values(
          id: vehicleId,
          value: vehicle,
          values: <String>[...vehicles, if (vehicle != null && !vehicles.contains(vehicle)) vehicle],
          allowNone: true,
          noneLabel: 'Nothing',
          onChanged: (String? id) => onChanged(id == null ? null : <String, Object?>{...module, 'enabled': module['enabled'] ?? true, 'vehicle': id}),
        ),
      ),
      if (value != null)
        Field(
          label: 'Riding now',
          tip: 'Off keeps the setting but the NPC walks on its own.',
          child: ArcaneToggleSwitch(value: module['enabled'] != false, ariaLabel: 'Riding now', onChanged: (bool on) => onChanged(<String, Object?>{...module, 'enabled': on}), size: ComponentSize.sm),
        ),
    ]);
  }
}
