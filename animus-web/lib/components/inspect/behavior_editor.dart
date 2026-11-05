/// The behavior document's Visual view: the document fields, typed
/// variables, routines as node trees and triggers with their events. Every
/// edit writes the whole document through [JsonEdit], keeping unknown keys.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/behavior_edit.dart';
import '../../logic/inspect/completion_catalog.dart';
import '../../logic/inspect/inspect_schemas.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart';
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

  @override
  Widget build(BuildContext context) {
    final JsonMap doc = _doc;
    final BehaviorContext behavior = BehaviorContext(catalog: _catalog, schema: schemas.behavior, document: doc, diagnostics: diagnostics, write: _write);
    return dom.section(classes: 'animus-panel animus-inspector animus-behavior-editor', attributes: const <String, String>{'aria-label': 'Behavior editor'}, <Widget>[
      if (schemas.live) const dom.p(classes: 'animus-muted', <Widget>[Component.text("Capabilities and triggers from the connected server.")]),
      GuiSection(title: 'Document', children: <Widget>[
        GuiRow(
          label: 'id',
          help: '${_spec('id').description ?? ''} Change it by moving the file.',
          problems: diagnosticsAt(diagnostics, r'$.id'),
          child: dom.code(classes: 'animus-inspect-const', <Widget>[Component.text(asString(doc['id']) ?? '-')]),
        ),
        InspectField(field: _spec('revision'), value: doc['revision'], path: r'$.revision', diagnostics: diagnostics, onChanged: (Object? v) => _write(<JsonStep>['revision'], v)),
        GuiRow(
          label: fieldLabel(_spec('entry')),
          help: _spec('entry').help,
          problems: diagnosticsAt(diagnostics, r'$.entry'),
          child: GuiSelect(value: asString(doc['entry']), options: behavior.routines, onChanged: (String? v) => _write(<JsonStep>['entry'], v)),
        ),
        for (final MapEntry<String, Object?> entry in doc.entries)
          if (!const <String>{'schemaVersion', 'revision', 'id', 'entry', 'variables', 'routines', 'triggers'}.contains(entry.key))
            GuiRow(
              label: entry.key,
              help: 'Not in the schema; kept as written.',
              problems: diagnosticsAt(diagnostics, childPath(r'$', entry.key), nested: true),
              child: KeptJsonEditor(value: entry.value, label: entry.key, onChanged: (Object? v) => _write(<JsonStep>[entry.key], v)),
            ),
      ]),
      _variables(doc),
      _routines(doc, behavior),
      _triggers(doc, behavior),
    ]);
  }

  Widget _variables(JsonMap doc) {
    final JsonMap variables = asMap(doc['variables']);
    final List<String> names = variables.keys.toList();
    void put(JsonMap next) => _write(<JsonStep>['variables'], next.isEmpty ? null : next);
    return GuiSection(title: 'Variables', count: variables.length, open: variables.isNotEmpty, children: <Widget>[
      dom.p(classes: 'animus-gui-help', <Widget>[Component.text(_spec('variables').description ?? '')]),
      for (int index = 0; index < names.length; index++) _variable(variables, names[index], index, put),
      Button.ghost(
        label: 'Add variable',
        size: ButtonSize.small,
        onPressed: () => put(<String, Object?>{...variables, uniqueName('flag', names): <String, Object?>{'type': 'boolean', 'initial': false}}),
      ),
    ]);
  }

  Widget _variable(JsonMap variables, String name, int index, void Function(JsonMap next) put) {
    final JsonMap variable = asMap(variables[name]);
    final String path = childPath(r'$.variables', name);
    final String type = asString(variable['type']) ?? 'string';
    void field(String key, Object? value) => put(<String, Object?>{...variables, name: asMap(writeIn(variable, <JsonStep>[key], value))});
    final Object initial = switch (type) {
      'boolean' => false,
      'integer' || 'decimal' => 0,
      _ => '',
    };
    return dom.div(key: ValueKey<String>('variable-$index'), classes: 'animus-inspect-item', attributes: <String, String>{'data-variable': name}, <Widget>[
      dom.div(classes: 'animus-gui-line', <Widget>[
        GuiTextInput(
          value: name,
          label: 'Variable ${index + 1} name',
          onChanged: (String next) {
            if (next.isNotEmpty && !variables.containsKey(next)) put(renameKey(variables, name, next));
          },
        ),
        GuiSelect(
          value: type,
          options: _catalog.variableTypes,
          onChanged: (String? next) {
            if (next == null || next == type) return;
            put(<String, Object?>{
              ...variables,
              name: <String, Object?>{...variable, 'type': next, 'initial': switch (next) { 'boolean' => false, 'integer' || 'decimal' => 0, _ => '' }},
            });
          },
        ),
        GuiIconButton(icon: ArcaneIcon.x(size: IconSize.sm), label: 'Remove variable $name', onPressed: () => put(<String, Object?>{...variables}..remove(name))),
      ]),
      GuiRow(
        label: 'initial *',
        problems: diagnosticsAt(diagnostics, childPath(path, 'initial')),
        child: InspectControl(
          field: FieldSpec(key: 'initial', kind: switch (type) { 'boolean' => FieldKind.boolean, 'integer' => FieldKind.integer, 'decimal' => FieldKind.number, _ => FieldKind.string }, required: true),
          value: variable['initial'],
          path: childPath(path, 'initial'),
          onChanged: (Object? value) => field('initial', value ?? initial),
        ),
      ),
      GuiRow(
        label: 'scope',
        help: 'One run (execution, the default), one NPC, one player, one NPC and player pair, or everyone.',
        child: GuiSelect(value: asString(variable['scope']), options: _catalog.variableScopes, allowNone: true, noneLabel: 'execution', onChanged: (String? v) {
          final JsonMap next = asMap(writeIn(variable, <JsonStep>['scope'], v));
          // Every scope but execution needs a lifetime; execution must not have one.
          if (v == null || v == 'execution') {
            next.remove('lifetime');
          } else {
            next.putIfAbsent('lifetime', () => 'transient');
          }
          put(<String, Object?>{...variables, name: next});
        }),
      ),
      if (variable['scope'] != null && variable['scope'] != 'execution')
        GuiRow(
          label: 'lifetime *',
          help: 'Transient values last until the server stops; persistent values are stored.',
          problems: diagnosticsAt(diagnostics, path),
          child: GuiSelect(value: asString(variable['lifetime']), options: _catalog.variableLifetimes, onChanged: (String? v) => field('lifetime', v)),
        ),
    ]);
  }

  Widget _routines(JsonMap doc, BehaviorContext behavior) {
    final JsonMap routines = asMap(doc['routines']);
    final List<String> names = routines.keys.toList();
    return GuiSection(title: 'Routines', count: routines.length, children: <Widget>[
      dom.p(classes: 'animus-gui-help', <Widget>[Component.text(_spec('routines').description ?? '')]),
      for (int index = 0; index < names.length; index++)
        dom.div(key: ValueKey<String>('routine-$index'), classes: 'animus-routine', attributes: <String, String>{'data-routine': names[index]}, <Widget>[
          dom.div(classes: 'animus-gui-line', <Widget>[
            GuiTextInput(
              value: names[index],
              label: 'Routine ${index + 1} name',
              onChanged: (String next) {
                if (RegExp(r'^[a-z0-9][a-z0-9_-]{0,63}$').hasMatch(next) && !routines.containsKey(next)) {
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
            caption: 'root',
          ),
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
    return GuiSection(title: 'Triggers', count: triggers.length, children: <Widget>[
      dom.p(classes: 'animus-gui-help', <Widget>[Component.text(_spec('triggers').description ?? '')]),
      for (int index = 0; index < triggers.length; index++) _trigger(triggers, index, put, behavior),
      GuiSelect(
        value: null,
        options: _catalog.triggers.keys.toList(),
        noneLabel: 'Add a trigger',
        onChanged: (String? event) {
          if (event != null) put(<Object?>[...triggers, defaultTrigger(event, _catalog, ids)]);
        },
      ),
    ]);
  }

  Widget _trigger(JsonList triggers, int index, void Function(JsonList next) put, BehaviorContext behavior) {
    final JsonMap trigger = asMap(triggers[index]);
    final String? event = asString(trigger['event']);
    final TriggerSpec? spec = _catalog.triggers[event];
    final List<JsonStep> steps = <JsonStep>['triggers', index];
    final String path = formatPath(steps);
    final Set<String> known = <String>{for (final FieldSpec field in spec?.fields ?? const <FieldSpec>[]) field.key};
    return dom.div(
      key: ValueKey<String>('trigger-$index'),
      classes: 'animus-node animus-trigger${diagnosticsAt(diagnostics, path, nested: true).any((Diagnostic d) => d.severity == DiagnosticSeverity.error) ? ' has-error' : ''}',
      attributes: <String, String>{'data-node-path': path, 'role': 'group', 'aria-label': 'Trigger ${index + 1}'},
      <Widget>[
        dom.div(classes: 'animus-node-head', <Widget>[
          dom.span(classes: 'animus-node-caption', <Widget>[Component.text('trigger ${index + 1}')]),
          GuiSelect(
            value: event,
            options: _catalog.triggers.keys.toList(),
            noneLabel: 'choose an event',
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
        for (final Diagnostic problem in diagnosticsAt(diagnostics, path))
          dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
        dom.div(classes: 'animus-node-body', <Widget>[
          for (final FieldSpec field in spec?.fields ?? const <FieldSpec>[])
            if (field.key != 'event')
              if (fieldType(field) == 'routine')
                GuiRow(
                  key: ValueKey<String>('$path.${field.key}'),
                  label: fieldLabel(field),
                  help: field.help,
                  problems: diagnosticsAt(diagnostics, childPath(path, field.key)),
                  child: GuiSelect(
                    value: asString(trigger[field.key]),
                    options: behavior.routines,
                    allowNone: !field.required,
                    noneLabel: 'entry',
                    onChanged: (String? v) => _write(<JsonStep>[...steps, field.key], v),
                  ),
                )
              else
                InspectField(
                  key: ValueKey<String>('$path.${field.key}'),
                  field: field,
                  value: trigger[field.key],
                  path: childPath(path, field.key),
                  diagnostics: diagnostics,
                  onChanged: (Object? v) => _write(<JsonStep>[...steps, field.key], v),
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
