/// The selected animation event: its tick, the capability (sound or
/// particle, the only ones animations run) and that capability's arguments,
/// built from the argument specs the behavior schema generator publishes.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component, ValueKey;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart' show humanizeKey;
import '../../logic/inspect/inspect_schemas.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/rig_edit.dart';
import '../../logic/rig_view.dart';
import '../../ui/action_row.dart';
import '../../ui/confirm_dialog.dart';
import '../../ui/field.dart';
import '../../ui/info_tip.dart';
import '../../ui/menu.dart';
import 'rig_inputs.dart';
import '../select/animus_select.dart';
import '../npc/npc_layout.dart';

/// Arguments the event form leaves out: events always play to nearby players.
const Set<String> playerArguments = <String>{'audience', 'at', 'player'};

/// Arguments a new event starts with, so it is valid as soon as it exists.
JsonMap starterArguments(String capability) => capability == 'animus:spawn-particle'
    ? <String, Object?>{'particle': 'minecraft:flame', 'count': 4}
    : <String, Object?>{'sound': 'minecraft:entity.iron_golem.step'};

String capabilityLabel(String capability) => capability == 'animus:spawn-particle' ? 'Particle' : 'Sound';

/// What the event does, short, for markers and lists.
String eventSummary(JsonMap event) {
  final String capability = '${event['capability']}';
  final Object? arguments = event['arguments'];
  final Object? what = arguments is JsonMap ? arguments[requiredArgument(capability)] : null;
  return '${capabilityLabel(capability)} ${what ?? 'not set'}';
}

class RigEventEditor extends StatelessWidget {
  const RigEventEditor({
    required this.event,
    required this.index,
    required this.duration,
    required this.onUpdate,
    required this.onRemove,
    super.key,
  });

  final JsonMap event;
  final int index;
  final int duration;
  final void Function(int tick, String capability, JsonMap arguments) onUpdate;
  final void Function() onRemove;

  String get _capability => '${event['capability']}';

  JsonMap get _arguments => event['arguments'] is JsonMap ? event['arguments']! as JsonMap : const <String, Object?>{};

  int get _tick => event['tick'] is num ? (event['tick']! as num).round() : 0;

  /// The capability's arguments, minus those that pick a triggering player:
  /// an animation has none, so the server refuses them.
  List<FieldSpec> get _fields => (InspectSchemas.embedded.catalog.capability(_capability)?.arguments ?? const <FieldSpec>[])
      .where((FieldSpec field) => !playerArguments.contains(field.key))
      .toList();

  String? _commitTick(String draft) {
    final int? tick = int.tryParse(draft.trim());
    if (tick == null || tick < 0 || tick > duration) return 'Use a whole tick from 0 to $duration.';
    if (tick != _tick) onUpdate(tick, _capability, _arguments);
    return null;
  }

  String? _commitField(FieldSpec field, String draft) {
    final String text = draft.trim();
    final Object? value = text.isEmpty
        ? null
        : switch (field.kind) {
            FieldKind.integer => int.tryParse(text),
            FieldKind.number => double.tryParse(text),
            _ => text,
          };
    final String? problem = text.isNotEmpty && value == null ? 'Use a number.' : field.check(value);
    if (problem != null) return problem;
    _set(field.key, value);
    return null;
  }

  void _set(String key, Object? value) {
    final JsonMap next = cloneJson(_arguments);
    if (value == null) {
      next.remove(key);
    } else {
      next[key] = value is double && value == value.roundToDouble() ? value.round() : value;
    }
    onUpdate(_tick, _capability, next);
  }

  Future<void> _remove() async {
    final bool yes = await askToConfirm(
      title: 'Delete this event?',
      message: '${eventSummary(event)} at tick $_tick. Undo brings it back.',
      confirmLabel: 'Delete event',
      destructive: true,
    );
    if (yes) onRemove();
  }

  @override
  Widget build(BuildContext context) {
    final List<FieldSpec> fields = _fields;
    return dom.section(classes: 'animus-rig-event-editor', styles: stackStyles(gap: Gap.xs), attributes: <String, String>{'aria-label': 'Event at tick $_tick'}, <Widget>[
      ActionRow(
        dense: true,
        label: 'Event',
        leading: <Widget>[
          dom.h4(styles: groupHeadingStyles, <Widget>[
            TipLabel(
              label: Component.text('Event at tick $_tick'),
              tip: 'Plays at the NPC for nearby players each time the animation reaches this tick.',
              docs: Docs.animationEvents,
            ),
          ]),
        ],
        more: <AppMenuEntry>[AppMenuEntry('Delete event', _remove, destructive: true)],
      ),
      CommitField(
        key: ValueKey<String>('event-tick:$index:$_tick'),
        label: 'Tick',
        id: 'rig-event-tick',
        number: true,
        hint: secondsLabel(_tick.toDouble()),
        value: '$_tick',
        onCommit: _commitTick,
      ),
      Field(
        key: ValueKey<String>('runs:$index:$_capability'),
        label: 'Plays',
        forId: 'rig-event-plays',
        child: AnimusSelect.values(
          id: 'rig-event-plays',
          value: _capability,
          values: eventCapabilities,
          labels: <String, String>{for (final String capability in eventCapabilities) capability: capabilityLabel(capability)},
          onChanged: (String? value) {
            if (value != null && value != _capability) onUpdate(_tick, value, starterArguments(value));
          },
        ),
      ),
      for (final FieldSpec field in fields)
        if (field.kind == FieldKind.choice)
          Field(
            key: ValueKey<String>('arg:$index:$_capability:${field.key}'),
            label: humanizeKey(field.key),
            forId: 'rig-event-${field.key}',
            child: AnimusSelect.values(
              id: 'rig-event-${field.key}',
              value: _arguments[field.key] == null ? '' : '${_arguments[field.key]}',
              values: <String>[for (final FieldOption option in field.options) option.label],
              allowNone: true,
              noneLabel: 'Default',
              onChanged: (String? value) => _set(field.key, value),
            ),
          )
        else
          CommitField(
            key: ValueKey<String>('arg:$index:$_capability:${field.key}'),
            label: humanizeKey(field.key),
            id: 'rig-event-${field.key}',
            required: field.required,
            number: field.kind == FieldKind.integer || field.kind == FieldKind.number,
            hint: field.bounds,
            value: _arguments[field.key] == null ? '' : '${_arguments[field.key]}',
            onCommit: (String draft) => _commitField(field, draft),
          ),
    ]);
  }
}
