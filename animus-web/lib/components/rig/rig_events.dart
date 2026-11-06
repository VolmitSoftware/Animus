/// The selected animation event: its tick, the capability (sound or
/// particle, the only ones animations run) and that capability's arguments,
/// built from the argument specs the behavior schema generator publishes.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component, ValueKey;

import '../../config/docs_links.dart';
import '../../logic/inspect/inspect_schemas.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/rig_edit.dart';
import '../info_tip.dart';

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
  return '${capabilityLabel(capability)} ${what ?? '(not set)'}';
}

class RigEventEditor extends StatefulWidget {
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

  @override
  State<RigEventEditor> createState() => _RigEventEditorState();
}

class _RigEventEditorState extends State<RigEventEditor> {
  final Map<String, String> _drafts = <String, String>{};
  final Map<String, String> _errors = <String, String>{};

  String get _capability => '${component.event['capability']}';

  JsonMap get _arguments => component.event['arguments'] is JsonMap ? component.event['arguments']! as JsonMap : const <String, Object?>{};

  int get _tick => component.event['tick'] is num ? (component.event['tick']! as num).round() : 0;

  /// The capability's arguments, minus those that pick a triggering player:
  /// an animation has none, so the server refuses them.
  List<FieldSpec> get _fields => (InspectSchemas.embedded.catalog.capability(_capability)?.arguments ?? const <FieldSpec>[])
      .where((FieldSpec field) => !playerArguments.contains(field.key))
      .toList();

  @override
  void didUpdateWidget(RigEventEditor oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (oldComponent.index != component.index) {
      _drafts.clear();
      _errors.clear();
    }
  }

  void _commitTick() {
    final String? draft = _drafts.remove('tick');
    final int? tick = draft == null ? null : int.tryParse(draft.trim());
    if (draft != null && (tick == null || tick < 0 || tick > component.duration)) {
      setState(() => _errors['tick'] = 'Use a whole tick from 0 to ${component.duration}.');
      return;
    }
    _errors.remove('tick');
    if (tick != null && tick != _tick) component.onUpdate(tick, _capability, _arguments);
    setState(() {});
  }

  void _commitField(FieldSpec field) {
    final String? draft = _drafts.remove(field.key);
    if (draft == null) return;
    final String text = draft.trim();
    final Object? value = text.isEmpty
        ? null
        : switch (field.kind) {
            FieldKind.integer => int.tryParse(text),
            FieldKind.number => double.tryParse(text),
            _ => text,
          };
    final String? problem = text.isNotEmpty && value == null ? 'Use a number.' : field.check(value);
    if (problem != null) {
      setState(() => _errors[field.key] = problem);
      return;
    }
    _errors.remove(field.key);
    _set(field.key, value);
  }

  void _set(String key, Object? value) {
    final JsonMap next = cloneJson(_arguments);
    if (value == null) {
      next.remove(key);
    } else {
      next[key] = value is double && value == value.roundToDouble() ? value.round() : value;
    }
    component.onUpdate(_tick, _capability, next);
    setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final List<FieldSpec> fields = _fields;
    return dom.div(classes: 'animus-rig-keyframe animus-rig-event-editor', attributes: <String, String>{'aria-label': 'Event at tick $_tick'}, <Widget>[
      labelWithTip(
        dom.strong(<Widget>[Component.text('Event at tick $_tick')]),
        'Plays at the NPC for nearby players each time the clip reaches this tick.',
        'Animation events',
        docs: Docs.animationEvents,
      ),
      _input('Tick', 'tick', '$_tick', _commitTick, number: true),
      dom.label(key: ValueKey<String>('runs:${component.index}:$_capability'), classes: 'animus-rig-field is-inline', <Widget>[
        const dom.span(<Widget>[Component.text('Runs')]),
        ArcaneSelect(
          size: ComponentSize.sm,
          value: _capability,
          options: <ArcaneSelectOption>[
            for (final String capability in eventCapabilities) ArcaneSelectOption(label: capabilityLabel(capability), value: capability),
          ],
          onChanged: (String value) {
            if (value != _capability) component.onUpdate(_tick, value, starterArguments(value));
          },
        ),
      ]),
      for (final FieldSpec field in fields)
        if (field.kind == FieldKind.choice)
          dom.label(classes: 'animus-rig-field is-inline', <Widget>[
            dom.span(<Widget>[Component.text(field.key)]),
            ArcaneSelect(
              size: ComponentSize.sm,
              value: _arguments[field.key] == null ? '' : '${_arguments[field.key]}',
              options: <ArcaneSelectOption>[
                const ArcaneSelectOption(label: '(default)', value: ''),
                for (final FieldOption option in field.options) ArcaneSelectOption(label: option.label, value: option.label),
              ],
              onChanged: (String value) => _set(field.key, value.isEmpty ? null : value),
            ),
          ])
        else
          _input(
            '${field.key}${field.required ? '' : ' (optional)'}',
            field.key,
            _arguments[field.key] == null ? '' : '${_arguments[field.key]}',
            () => _commitField(field),
            number: field.kind == FieldKind.integer || field.kind == FieldKind.number,
            help: field.bounds,
          ),
      Button.ghost(label: 'Delete event', size: ButtonSize.small, onPressed: component.onRemove),
    ]);
  }

  Widget _input(String label, String key, String value, void Function() commit, {bool number = false, String help = ''}) {
    final String? error = _errors[key];
    return dom.label(classes: 'animus-rig-field', <Widget>[
      dom.span(<Widget>[Component.text(label)]),
      TextInput(
        size: ComponentSize.sm,
        type: number ? TextInputType.number : TextInputType.text,
        value: _drafts[key] ?? value,
        attributes: <String, String>{'aria-label': 'Event $label', 'step': 'any', if (error != null) 'aria-invalid': 'true'},
        onChanged: (String next) => _drafts[key] = next,
        onSubmitted: (String _) => commit(),
        onBlur: commit,
      ),
      if (error != null)
        dom.span(classes: 'animus-field-error', attributes: const <String, String>{'role': 'alert'}, <Widget>[Component.text(error)])
      else if (help.isNotEmpty)
        dom.span(classes: 'animus-muted', <Widget>[Component.text(help)]),
    ]);
  }
}
