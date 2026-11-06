/// The behavior document's Visual view, in reading order: the document
/// fields (with the server switch), typed variables, routines as
/// collapsible node trees, then triggers. Every edit writes the whole
/// document through [JsonEdit], keeping unknown keys.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/behavior_edit.dart';
import '../../logic/inspect/behavior_operands.dart';
import '../../logic/inspect/completion_catalog.dart';
import '../../logic/inspect/inspect_schemas.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/npc/dialogue_tree.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart';
import '../npc/dialogue_view.dart';
import 'behavior_inputs.dart';
import 'behavior_nodes.dart';
import 'inspect_fields.dart';

class BehaviorEditor extends StatelessWidget {
  const BehaviorEditor({required this.document, required this.onJson, required this.schemas, this.diagnostics = const <Diagnostic>[], super.key});

  final AnimusDocument document;
  final JsonEdit onJson;
  final InspectSchemas schemas;

  /// The browser's and the server's diagnostics together.
  final List<Diagnostic> diagnostics;

  JsonMap get _doc => asMap(document.json);
  CompletionCatalog get _catalog => schemas.catalog;

  void _write(List<JsonStep> steps, Object? value) => onJson(writeIn(_doc, steps, value), coalesce: 'behavior:${steps.join('.')}');

  FieldSpec _spec(String key) =>
      schemas.behavior.fields(schemas.behavior.root, _doc).where((FieldSpec field) => field.key == key).firstOrNull ?? FieldSpec(key: key, kind: FieldKind.any);

  static const Set<String> _documentKeys = <String>{'schemaVersion', 'revision', 'id', 'server', 'entry', 'variables', 'routines', 'triggers'};

  @override
  Widget build(BuildContext context) {
    final JsonMap doc = _doc;
    final BehaviorContext behavior = BehaviorContext(
      catalog: _catalog,
      schema: schemas.behavior,
      document: doc,
      diagnostics: diagnostics,
      write: _write,
      writeDocument: (JsonMap next) => onJson(next, coalesce: 'behavior:document'),
    );
    final bool server = behavior.server;
    return dom.section(classes: 'animus-panel animus-inspector animus-behavior-editor', attributes: const <String, String>{'aria-label': 'Behavior editor'}, <Widget>[
      if (schemas.live) const dom.p(classes: 'animus-muted', <Widget>[Component.text('Capabilities and triggers come from the connected server.')]),
      GuiSection(title: 'Document', children: <Widget>[
        GuiRow(
          label: 'ID',
          help: '${_spec('id').description ?? ''} Change it by moving the file.',
          problems: diagnosticsAt(diagnostics, r'$.id'),
          child: dom.code(classes: 'animus-inspect-const', <Widget>[Component.text(asString(doc['id']) ?? '-')]),
        ),
        InspectField(field: _spec('revision'), value: doc['revision'], path: r'$.revision', diagnostics: diagnostics, onChanged: (Object? v) => _write(<JsonStep>['revision'], v)),
        GuiRow(
          label: fieldLabel(_spec('entry')),
          help: _spec('entry').help,
          problems: diagnosticsAt(diagnostics, r'$.entry'),
          child: GuiSelect(value: asString(doc['entry']), options: behavior.routines, noneLabel: 'Pick a routine', label: 'Entry routine', onChanged: (String? v) => _write(<JsonStep>['entry'], v)),
        ),
        GuiRow(
          label: 'Server behavior',
          help: server
              ? 'On: no NPC runs this. It reacts to server events (joins, chat, commands, timers of at least $serverTimerMinimum ticks), declares only execution, player and global variables, '
                  'cannot use NPC-only capabilities, and runs only when server-behaviors.json lists it.'
              : 'Off: NPCs that list this behavior run it. Turn it on for joins, chat, commands and other server events with no NPC.',
          problems: diagnosticsAt(diagnostics, r'$.server'),
          child: GuiSwitch(
            value: server,
            label: 'Run on the server with no NPC',
            onChanged: (bool on) => onJson(setServerBehavior(doc, on), coalesce: 'behavior:server'),
          ),
        ),
        for (final MapEntry<String, Object?> entry in doc.entries)
          if (!_documentKeys.contains(entry.key))
            GuiRow(
              label: entry.key,
              help: 'Kept as written; the server reads it.',
              problems: diagnosticsAt(diagnostics, childPath(r'$', entry.key), nested: true),
              child: KeptJsonEditor(value: entry.value, label: entry.key, onChanged: (Object? v) => _write(<JsonStep>[entry.key], v)),
            ),
      ]),
      _variables(doc, server),
      _routines(doc, behavior),
      _triggers(doc, behavior),
      if (dialogueTree(doc) case final DialogueTree tree when tree.hasDialogue)
        GuiSection(title: 'Conversation', children: <Widget>[DialogueView(tree: tree, write: _write)]),
    ]);
  }

  Widget _variables(JsonMap doc, bool server) {
    final JsonMap variables = asMap(doc['variables']);
    final List<String> names = variables.keys.toList();
    void put(JsonMap next) => _write(<JsonStep>['variables'], next.isEmpty ? null : next);
    return GuiSection(title: 'Variables', count: variables.length, open: variables.isNotEmpty, children: <Widget>[
      dom.p(classes: 'animus-gui-help', <Widget>[Component.text(_spec('variables').description ?? '')]),
      if (variables.isEmpty) const dom.p(classes: 'animus-muted', <Widget>[Component.text('No variables yet. Add one to count, remember or pass values; expressions read them by name.')]),
      for (int index = 0; index < names.length; index++) _variable(doc, variables, names[index], index, put, server),
      Button.ghost(
        label: 'Add variable',
        size: ButtonSize.small,
        onPressed: () => put(<String, Object?>{...variables, uniqueName('flag', names): <String, Object?>{'type': 'boolean', 'initial': false}}),
      ),
    ]);
  }

  Widget _variable(JsonMap doc, JsonMap variables, String name, int index, void Function(JsonMap next) put, bool server) {
    final JsonMap variable = asMap(variables[name]);
    final String path = childPath(r'$.variables', name);
    final String type = asString(variable['type']) ?? 'string';
    void field(String key, Object? value) => put(<String, Object?>{...variables, name: asMap(writeIn(variable, <JsonStep>[key], value))});
    final List<String> scopes = scopesFor(_catalog.variableScopes, server: server);
    return dom.div(key: ValueKey<String>('variable-$index'), classes: 'animus-inspect-item', attributes: <String, String>{'data-variable': name}, <Widget>[
      dom.div(classes: 'animus-gui-line', <Widget>[
        VariableNameInput(
          name: name,
          taken: <String>[for (final String other in variables.keys) if (other != name) other],
          label: 'Variable ${index + 1} name',
          onRename: (String next) => onJson(renameVariable(doc, name, next, _catalog), coalesce: 'behavior:rename-variable:$index'),
        ),
        GuiSelect(
          value: type,
          options: _catalog.variableTypes,
          label: 'Type of $name',
          onChanged: (String? next) {
            if (next == null || next == type) return;
            put(<String, Object?>{...variables, name: <String, Object?>{...variable, 'type': next, 'initial': literalFor(next)}});
          },
        ),
        GuiIconButton(icon: ArcaneIcon.x(size: IconSize.sm), label: 'Remove variable $name', onPressed: () => put(<String, Object?>{...variables}..remove(name))),
      ]),
      for (final Diagnostic problem in diagnosticsAt(diagnostics, path))
        dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
      GuiRow(
        label: 'Starts as *',
        problems: diagnosticsAt(diagnostics, childPath(path, 'initial'), nested: true),
        child: TypedValueEditor(type: type, value: variable['initial'], label: 'Starting value of $name', onChanged: (Object? value) => field('initial', value)),
      ),
      GuiRow(
        label: 'Scope',
        help: server
            ? 'One run (execution, the default), one player, or everyone. A server behavior has no NPC, so npc and relationship are not offered.'
            : 'One run (execution, the default), one NPC, one player, one NPC and player pair, or everyone.',
        problems: diagnosticsAt(diagnostics, childPath(path, 'scope')),
        child: GuiSelect(
          value: asString(variable['scope']),
          options: <String>[for (final String scope in scopes) if (scope != 'execution') scope],
          allowNone: true,
          noneLabel: 'execution',
          label: 'Scope of $name',
          onChanged: (String? v) {
            final JsonMap next = asMap(writeIn(variable, <JsonStep>['scope'], v));
            // Every scope but execution needs a lifetime; execution must not have one.
            if (v == null || v == 'execution') {
              next.remove('lifetime');
            } else {
              next.putIfAbsent('lifetime', () => 'transient');
            }
            put(<String, Object?>{...variables, name: next});
          },
        ),
      ),
      if (variable['scope'] != null && variable['scope'] != 'execution')
        GuiRow(
          label: 'Lifetime *',
          help: 'Transient values last until the server stops; persistent values are stored.',
          problems: diagnosticsAt(diagnostics, childPath(path, 'lifetime')),
          child: GuiSelect(value: asString(variable['lifetime']), options: _catalog.variableLifetimes, label: 'Lifetime of $name', onChanged: (String? v) => field('lifetime', v)),
        ),
    ]);
  }

  Widget _routines(JsonMap doc, BehaviorContext behavior) {
    final JsonMap routines = asMap(doc['routines']);
    final List<String> names = routines.keys.toList();
    return GuiSection(title: 'Routines', count: routines.length, children: <Widget>[
      dom.p(classes: 'animus-gui-help', <Widget>[
        Component.text('${_spec('routines').description ?? ''} Up and Down move between steps, Right and Left open and close one, Delete removes it.'),
      ]),
      dom.div(classes: 'animus-behavior-tree', <Widget>[
        for (int index = 0; index < names.length; index++)
          dom.div(key: ValueKey<String>('routine-$index'), classes: 'animus-routine', attributes: <String, String>{'data-routine': names[index]}, <Widget>[
            dom.div(classes: 'animus-gui-line', <Widget>[
              GuiTextInput(
                value: names[index],
                label: 'Routine ${index + 1} name',
                onChanged: (String next) {
                  if (behaviorIdentifier.hasMatch(next) && !routines.containsKey(next)) {
                    onJson(renameRoutine(doc, names[index], next, _catalog), coalesce: 'behavior:rename:$index');
                  }
                },
              ),
              if (doc['entry'] == names[index])
                const dom.span(classes: 'animus-tag', <Widget>[Component.text('entry')])
              else
                Button.ghost(label: 'Make entry', size: ButtonSize.small, onPressed: () => _write(<JsonStep>['entry'], names[index])),
              if (names.length > 1)
                GuiIconButton(
                  icon: ArcaneIcon.x(size: IconSize.sm),
                  label: 'Remove routine ${names[index]}',
                  onPressed: () => _write(<JsonStep>['routines'], <String, Object?>{...routines}..remove(names[index])),
                ),
            ]),
            BehaviorNodeView(
              key: ValueKey<String>('routine-node-$index'),
              context: behavior,
              node: routines[names[index]],
              steps: <JsonStep>['routines', names[index]],
              caption: names[index],
            ),
          ]),
      ]),
      Button.ghost(
        label: 'Add routine',
        size: ButtonSize.small,
        onPressed: () => _write(<JsonStep>['routines'], <String, Object?>{...routines, uniqueName('routine', names): defaultNode('sequence', _catalog)}),
      ),
    ]);
  }

  Widget _triggers(JsonMap doc, BehaviorContext behavior) {
    final JsonList triggers = asList(doc['triggers']);
    void put(JsonList next) => _write(<JsonStep>['triggers'], next.isEmpty ? null : next);
    final List<String> ids = <String>[for (final Object? trigger in triggers) asString(asMap(trigger)['id']) ?? ''];
    final List<String> events = _catalog.eventsFor(server: behavior.server);
    return GuiSection(title: 'Triggers', count: triggers.length, children: <Widget>[
      dom.p(classes: 'animus-gui-help', <Widget>[
        Component.text(behavior.server
            ? 'What starts this server behavior: server events only. NPC events need the server switch off.'
            : '${_spec('triggers').description ?? ''} Server events need the server switch on.'),
      ]),
      if (triggers.isEmpty)
        dom.p(classes: 'animus-muted', <Widget>[
          Component.text(behavior.server ? 'No triggers yet, so nothing starts this behavior. Add a join, chat or command trigger.' : 'No triggers yet; the behavior runs only when something calls it. Add a click or proximity trigger.'),
        ]),
      for (int index = 0; index < triggers.length; index++) _trigger(triggers, index, put, behavior),
      GuiSelect(
        value: null,
        options: events,
        labels: <String, String>{for (final String event in events) event: '$event: ${_catalog.triggers[event]?.description ?? ''}'},
        noneLabel: 'Add a trigger',
        label: 'Add a trigger',
        onChanged: (String? event) {
          if (event != null) put(<Object?>[...triggers, defaultTrigger(event, _catalog, ids, server: behavior.server)]);
        },
      ),
    ]);
  }

  /// [field] with the server behavior timer minimum.
  static FieldSpec _serverInterval(FieldSpec field) => FieldSpec(
        key: field.key,
        kind: field.kind,
        required: field.required,
        description: field.description,
        minimum: serverTimerMinimum,
        maximum: field.maximum,
        schema: field.schema,
      );

  Widget _trigger(JsonList triggers, int index, void Function(JsonList next) put, BehaviorContext behavior) {
    final JsonMap trigger = asMap(triggers[index]);
    final String? event = asString(trigger['event']);
    final TriggerSpec? spec = _catalog.triggers[event];
    final List<JsonStep> steps = <JsonStep>['triggers', index];
    final String path = formatPath(steps);
    final Set<String> known = <String>{for (final FieldSpec field in spec?.fields ?? const <FieldSpec>[]) field.key};
    final bool misplaced = spec != null && !spec.allowedIn(server: behavior.server);
    final List<String> events = <String>[..._catalog.eventsFor(server: behavior.server), if (event != null && misplaced) event];
    return dom.div(
      key: ValueKey<String>('trigger-$index'),
      classes: 'animus-trigger${diagnosticsAt(diagnostics, path, nested: true).any((Diagnostic d) => d.severity == DiagnosticSeverity.error) ? ' has-error' : ''}',
      attributes: <String, String>{'data-node-path': path, 'data-event': event ?? '', 'role': 'group', 'aria-label': 'Trigger ${index + 1}'},
      <Widget>[
        dom.div(classes: 'animus-node-tools', <Widget>[
          dom.span(classes: 'animus-node-caption', <Widget>[Component.text('trigger ${index + 1}')]),
          GuiSelect(
            value: event,
            options: events,
            noneLabel: 'Pick an event',
            label: 'Event of trigger ${index + 1}',
            onChanged: (String? next) {
              if (next != null) put(<Object?>[...triggers]..[index] = changeTriggerEvent(trigger, next, _catalog));
            },
          ),
          dom.span(classes: 'animus-node-help', <Widget>[
            Component.text(spec == null ? 'Unknown event; kept as written.' : '${spec.description}${spec.carriesPlayer ? '' : ' No player is carried.'}'),
          ]),
          if (index > 0) GuiIconButton(icon: ArcaneIcon.arrowUp(size: IconSize.sm), label: 'Move trigger ${index + 1} up', onPressed: () => put(moveItem(triggers, index, index - 1))),
          if (index < triggers.length - 1)
            GuiIconButton(icon: ArcaneIcon.arrowDown(size: IconSize.sm), label: 'Move trigger ${index + 1} down', onPressed: () => put(moveItem(triggers, index, index + 1))),
          GuiIconButton(icon: ArcaneIcon.x(size: IconSize.sm), label: 'Remove trigger ${index + 1}', onPressed: () => put(removeItem(triggers, index))),
        ]),
        if (misplaced)
          dom.span(classes: 'animus-gui-problem is-error', attributes: const <String, String>{'role': 'note'}, <Widget>[
            Component.text(behavior.server ? '$event needs an NPC, and a server behavior has none. Pick a server event or remove this trigger.' : '$event is a server event; turn the server switch on or pick an NPC event.'),
          ]),
        for (final Diagnostic problem in diagnosticsAt(diagnostics, path))
          dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
        dom.div(classes: 'animus-node-body', <Widget>[
          for (final FieldSpec raw in spec?.fields ?? const <FieldSpec>[])
            if (raw.key != 'event')
              if (fieldType(raw) == 'routine')
                GuiRow(
                  key: ValueKey<String>('$path.${raw.key}'),
                  label: fieldLabel(raw),
                  help: raw.help,
                  problems: diagnosticsAt(diagnostics, childPath(path, raw.key)),
                  child: GuiSelect(
                    value: asString(trigger[raw.key]),
                    options: behavior.routines,
                    allowNone: !raw.required,
                    noneLabel: 'The entry routine',
                    label: 'Routine of trigger ${index + 1}',
                    onChanged: (String? v) => _write(<JsonStep>[...steps, raw.key], v),
                  ),
                )
              else
                InspectField(
                  key: ValueKey<String>('$path.${raw.key}'),
                  field: behavior.server && event == 'timer' && raw.key == 'intervalTicks' ? _serverInterval(raw) : raw,
                  value: trigger[raw.key],
                  path: childPath(path, raw.key),
                  diagnostics: diagnostics,
                  onChanged: (Object? v) => _write(<JsonStep>[...steps, raw.key], v),
                ),
          for (final MapEntry<String, Object?> entry in trigger.entries)
            if (!known.contains(entry.key) && entry.key != 'event')
              GuiRow(
                label: entry.key,
                help: 'Not a field of this event; kept as written.',
                problems: diagnosticsAt(diagnostics, childPath(path, entry.key), nested: true),
                child: KeptJsonEditor(value: entry.value, label: entry.key, onChanged: (Object? v) => _write(<JsonStep>[...steps, entry.key], v)),
              ),
        ]),
      ],
    );
  }
}

/// A variable's name, renamed on each valid edit; an invalid name shows
/// why and changes nothing.
class VariableNameInput extends StatefulWidget {
  const VariableNameInput({required this.name, required this.taken, required this.label, required this.onRename, super.key});

  final String name;
  final List<String> taken;
  final String label;
  final void Function(String next) onRename;

  @override
  State<VariableNameInput> createState() => _VariableNameInputState();
}

class _VariableNameInputState extends State<VariableNameInput> {
  String? _problem;

  @override
  Widget build(BuildContext context) {
    final String? note = _problem == null ? variableNameNote(component.name) : null;
    return dom.div(classes: 'animus-control-stack', <Widget>[
      GuiTextInput(
        value: component.name,
        label: component.label,
        onChanged: (String next) {
          final String? problem = next == component.name ? null : variableNameProblem(next, component.taken);
          setState(() => _problem = problem);
          if (problem == null && next != component.name) component.onRename(next);
        },
      ),
      if (_problem != null) dom.span(classes: 'animus-gui-problem is-error', attributes: const <String, String>{'role': 'alert'}, <Widget>[Component.text(_problem!)]),
      if (note != null) dom.span(classes: 'animus-gui-problem is-warning', <Widget>[Component.text(note)]),
    ]);
  }
}
