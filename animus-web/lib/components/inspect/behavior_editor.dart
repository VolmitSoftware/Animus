/// A behavior that is not a conversation. Simple shows it read only, in
/// plain sentences ([BehaviorSentences]). Advanced edits it, in reading
/// order: the behavior's own fields (its id, renamed in place, and the
/// server switch), typed variables, its routines as cards of steps, then
/// its triggers. Every edit writes the whole document through
/// [LabeledJsonEdit], keeping unknown keys; adding, moving and deleting are
/// one undo step each, named in the notice.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/behavior_edit.dart';
import '../../logic/inspect/behavior_kept.dart';
import '../../logic/inspect/behavior_operands.dart';
import '../../logic/inspect/behavior_steps.dart';
import '../../logic/inspect/behavior_words.dart';
import '../../logic/inspect/completion_catalog.dart';
import '../../logic/inspect/inspect_schemas.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/inspect/typed_values.dart';
import '../../logic/npc/dialogue_prompts.dart';
import '../../logic/npc/reference_options.dart';
import '../../logic/suggest/field_profile.dart';
import '../../logic/suggest/field_profiles.dart';
import '../../model/animus_document.dart';
import '../../ui/action_row.dart';
import '../../ui/badge.dart';
import '../../ui/density.dart';
import '../../ui/info_tip.dart';
import '../../ui/menu.dart';
import '../../ui/mode.dart';
import '../../ui/section.dart';
import '../../ui/tone.dart';
import '../gui/gui_fields.dart';
import '../suggest/suggest_scope.dart';
import 'behavior_inputs.dart';
import 'behavior_nodes.dart';
import 'behavior_sentences.dart';
import 'inspect_fields.dart';
import 'typed_values_editor.dart' show KeyValueEditor;
import '../select/animus_select.dart';
import '../suggest/autocomplete_input.dart';
import '../../ui/field.dart';
import '../../ui/stack.dart';
import '../../ui/problem_text.dart';

/// The first name a new variable of [type] takes.
String _variableBase(String type) => switch (type) {
      'boolean' => 'flag',
      'integer' || 'decimal' => 'count',
      'string' => 'text',
      'list' => 'items',
      'map' => 'table',
      _ => 'value',
    };

class BehaviorEditor extends StatelessWidget {
  const BehaviorEditor({required this.document, required this.onJson, required this.schemas, this.diagnostics = const <Diagnostic>[], this.onRename, super.key});

  final AnimusDocument document;
  final LabeledJsonEdit onJson;
  final InspectSchemas schemas;

  /// Moves the behavior to a new id, NPCs that list it following; returns
  /// why it could not. Null keeps the id read only.
  final Future<String?> Function(String id)? onRename;

  /// The browser's and the server's diagnostics together.
  final List<Diagnostic> diagnostics;

  JsonMap get _doc => asMap(document.json);
  CompletionCatalog get _catalog => schemas.catalog;

  /// Renames the [field] entry at [index] of the latest document. It is found
  /// by position: a quicker keystroke may have renamed it already.
  static JsonUpdate _renameAt(String field, int index, JsonMap Function(JsonMap latest, String from) rename) => (Object? latest) {
        final List<String> keys = asMap(asMap(latest)[field]).keys.toList();
        return index < keys.length ? rename(asMap(latest), keys[index]) : latest;
      };

  void _write(List<JsonStep> steps, Object? value) => onJson(writeAt(_doc, steps, value), coalesce: 'behavior:${steps.join('.')}');

  /// A structural edit: its own undo step, named [label].
  void _change(List<JsonStep> steps, Object? value, String label) => onJson(writeAt(_doc, steps, value), label: label);

  FieldSpec _spec(String key) =>
      schemas.behavior.fields(schemas.behavior.root, _doc).where((FieldSpec field) => field.key == key).firstOrNull ?? FieldSpec(key: key, kind: FieldKind.any);

  @override
  Widget build(BuildContext context) {
    final JsonMap doc = _doc;
    if (!ModeScope.advanced(context)) return BehaviorSentences(behavior: doc, diagnostics: diagnostics);
    final BehaviorContext behavior = BehaviorContext(
      catalog: _catalog,
      schema: schemas.behavior,
      document: doc,
      diagnostics: diagnostics,
      write: _write,
      change: _change,
      changeDocument: (JsonMap next, String label) => onJson(writeAt(doc, const <JsonStep>[], next), label: label),
    );
    final BehaviorNames names = BehaviorNames(
      scope: behavior.scope,
      variableScopes: <String, String>{
        for (final MapEntry<String, Object?> entry in behavior.variables.entries)
          if (asString(asMap(entry.value)['scope']) case final String scope) entry.key: scope,
      },
    );
    return SuggestScope.withBehavior(context, names, _editor(doc, behavior));
  }

  Widget _editor(JsonMap doc, BehaviorContext behavior) {
    final bool server = behavior.server;
    return dom.section(classes: 'animus-behavior-editor', attributes: const <String, String>{'aria-label': 'Behavior editor'}, <Widget>[
      if (schemas.live) noteLine('Steps and triggers come from the server.'),
      AppSection(title: 'Behavior', tip: 'The file: its id, the routine it starts in, and whether it runs on the server.', docs: Docs.behaviors, children: <Widget>[
        Field(
          label: 'Id',
          tip: onRename == null
              ? (_spec('id').description ?? 'Set by the file path.')
              : 'Folder and name, such as town:greeter. Behaviors in one folder share flags and quests. Renaming moves the file; NPCs that list it follow.',
          problems: diagnosticsAt(diagnostics, r'$.id'),
          child: onRename == null
              ? dom.code(classes: 'animus-inspect-const', <Widget>[Component.text(asString(doc['id']) ?? '-')])
              : RenameInput(value: document.id, label: 'Id', onRename: onRename!),
        ),
        Field(
          label: 'Starts in',
          tip: 'The routine that runs when a trigger names none. Written as entry.',
          problems: diagnosticsAt(diagnostics, r'$.entry'),
          child: AnimusSelect.values(
            value: asString(doc['entry']),
            values: behavior.routines,
            labels: <String, String>{for (final String name in behavior.routines) name: behaviorTitle(name)},
            noneLabel: 'Pick a routine',
            hiddenLabel: 'Routine it starts in',
            onChanged: (String? v) => _write(<JsonStep>['entry'], v),
          ),
        ),
        Field(
          label: 'Server behavior',
          tip: server
              ? 'Runs on server events with no NPC, and only when the server behaviors list names it.'
              : 'Off: NPCs that list it run it.',
          problems: diagnosticsAt(diagnostics, r'$.server'),
          child: ArcaneToggleSwitch(
            value: server,
            label: 'Run on the server',
            onChanged: (bool on) => onJson((Object? latest) => setServerBehavior(asMap(latest), on), coalesce: 'behavior:server'),
            size: ComponentSize.sm,
          ),
        ),
        if (unknownEntries(doc, behaviorDocumentKeys) case final JsonMap other when other.isNotEmpty)
          _otherFields(other, r'$', (JsonMap next) => onJson(writeAt(doc, const <JsonStep>[], withUnknownEntries(doc, behaviorDocumentKeys, next)), coalesce: 'behavior:other')),
      ]),
      _variables(doc, server),
      _routines(doc, behavior),
      _triggers(doc, behavior),
    ]);
  }

  /// What an unknown field's text completes: a trigger filter's known id
  /// source, else behavior `{{ }}` holes.
  static FieldProfile _otherProfile(String key) {
    final FieldProfile known = triggerFieldProfile(key, type: 'string');
    return known.isNone ? FieldProfile.behaviorTemplate : known;
  }

  /// Keys no control here lays out, as a typed key/value editor.
  Widget _otherFields(JsonMap other, String path, void Function(JsonMap next) put) => Field(
        label: 'Other fields',
        tip: 'Keys this editor does not know. They are kept as written and the server reads them.',
        problems: <Diagnostic>[for (final String key in other.keys) ...diagnosticsAt(diagnostics, childPath(path, key), nested: true)],
        child: KeyValueEditor(value: other, label: path == r'$' ? 'Other fields' : 'Other fields of ${path.substring(2)}', profileFor: _otherProfile, onChanged: put),
      );

  /// An "Add ..." menu at the end of a section.
  static Widget _addMenu(String label, List<AppMenuEntry> entries) => ActionRow(leading: <Widget>[
        AppMenu(label: label, icon: ArcaneIcon.plus(), outline: true, entries: entries),
      ]);

  Widget _variables(JsonMap doc, bool server) {
    final JsonMap variables = asMap(doc['variables']);
    final List<String> names = variables.keys.toList();
    void put(JsonMap next) => _write(<JsonStep>['variables'], next.isEmpty ? null : next);
    return AppSection(title: 'Variables', count: variables.length, open: variables.isNotEmpty, tip: _spec('variables').description ?? 'Values the steps keep and read.', docs: Docs.behaviorVariables, children: <Widget>[
      if (variables.isEmpty) noteLine('No variables yet.'),
      for (int index = 0; index < names.length; index++) _variable(doc, variables, names[index], index, put, server),
      _addMenu('Add variable', <AppMenuEntry>[
        for (final String type in _catalog.variableTypes)
          AppMenuEntry(variableTypeWords[type] ?? type, () {
            final String name = uniqueName(_variableBase(type), names);
            _change(<JsonStep>['variables'], <String, Object?>{...variables, name: <String, Object?>{'type': type, 'initial': literalFor(type)}}, 'add variable $name');
          }),
      ]),
    ]);
  }

  Widget _variable(JsonMap doc, JsonMap variables, String name, int index, void Function(JsonMap next) put, bool server) {
    final JsonMap variable = asMap(variables[name]);
    final String path = childPath(r'$.variables', name);
    final String type = asString(variable['type']) ?? 'string';
    void field(String key, Object? value) => put(<String, Object?>{...variables, name: asMap(writeIn(variable, <JsonStep>[key], value))});
    final List<String> scopes = scopesFor(_catalog.variableScopes, server: server);
    return dom.div(key: ValueKey<String>('variable-$index'), classes: 'animus-variable', attributes: <String, String>{'data-variable': name}, <Widget>[
      ActionRow(
        label: 'Variable $name',
        leading: <Widget>[
          NameInput(
            name: name,
            label: 'Variable ${index + 1} name',
            problemOf: (String next) => variableNameProblem(next, <String>[for (final String other in variables.keys) if (other != name) other]),
            noteOf: variableNameNote,
            onRename: (String next) =>
                onJson(_renameAt('variables', index, (JsonMap latest, String from) => renameVariable(latest, from, next, _catalog)), coalesce: 'behavior:rename-variable:$index'),
          ),
          AnimusSelect.values(
            value: type,
            values: _catalog.variableTypes,
            labels: variableTypeWords,
            hiddenLabel: 'Type of $name',
            onChanged: (String? next) {
              if (next == null || next == type) return;
              put(<String, Object?>{...variables, name: <String, Object?>{...variable, 'type': next, 'initial': literalFor(next)}});
            },
          ),
        ],
        more: <AppMenuEntry>[
          AppMenuEntry(
            'Delete variable',
            () => confirmDelete('variable $name', 'Steps that read it will show a problem.', () {
              final JsonMap rest = <String, Object?>{...variables}..remove(name);
              _change(<JsonStep>['variables'], rest.isEmpty ? null : rest, 'delete variable $name');
            }),
            destructive: true,
          ),
        ],
      ),
      for (final Diagnostic problem in diagnosticsAt(diagnostics, path)) problemLine(problem.severity, problem.message, classes: 'animus-node-problem'),
      Field(
        label: 'Starts as',
        problems: diagnosticsAt(diagnostics, childPath(path, 'initial'), nested: true),
        child: TypedValueEditor(type: type, value: variable['initial'], label: 'Starting value of $name', onChanged: (Object? value) => field('initial', value)),
      ),
      Field(
        label: 'Shared by',
        tip: server ? 'Who shares the value. A server behavior has no NPC to share it with. Written as scope.' : 'Who shares the value: one run, the NPC, each player or each NPC and player pair. Written as scope.',
        problems: diagnosticsAt(diagnostics, childPath(path, 'scope')),
        child: AnimusSelect.values(
          value: asString(variable['scope']),
          values: <String>[for (final String scope in scopes) if (scope != 'execution') scope],
          labels: variableScopeWords,
          allowNone: true,
          noneLabel: variableScopeWords['execution']!,
          hiddenLabel: 'Who shares $name',
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
        Field(
          label: 'Kept',
          tip: 'Until restart ends when the server stops. Saved survives restarts. Written as lifetime.',
          problems: diagnosticsAt(diagnostics, childPath(path, 'lifetime')),
          child: AnimusSelect.values(
            value: asString(variable['lifetime']),
            values: _catalog.variableLifetimes,
            labels: variableLifetimeWords,
            hiddenLabel: 'How long $name is kept',
            onChanged: (String? v) => field('lifetime', v),
          ),
        ),
    ]);
  }

  Widget _routines(JsonMap doc, BehaviorContext behavior) {
    final JsonMap routines = asMap(doc['routines']);
    final List<String> names = routines.keys.toList();
    return AppSection(
      title: 'Routines',
      count: routines.length,
      tip: 'Named lists of steps, written as routines. Triggers start them; a step can call another routine.',
      docs: Docs.behaviorNodes,
      children: <Widget>[
        for (int index = 0; index < names.length; index++) _routine(doc, routines, names, index, behavior),
        _addMenu('Add routine', <AppMenuEntry>[
          for (final String type in behavior.nodeTypes)
            AppMenuEntry(nodeLabel(type), () {
              final String name = uniqueName('routine', names);
              _change(<JsonStep>['routines'], <String, Object?>{...routines, name: behavior.newNode(type)}, 'add routine ${behaviorTitle(name)}');
            }),
        ]),
      ],
    );
  }

  Widget _routine(JsonMap doc, JsonMap routines, List<String> names, int index, BehaviorContext behavior) {
    final String name = names[index];
    final bool entry = doc['entry'] == name;
    return AppSection(
      key: ValueKey<String>('routine-$index'),
      classes: 'animus-routine',
      attributes: <String, String>{'data-routine': name},
      summary: <Widget>[
        Component.text(behaviorTitle(name)),
        if (entry) const StatusBadge('Starts here', tone: BadgeTone.info),
      ],
      children: <Widget>[
        ActionRow(
          label: 'Routine ${behaviorTitle(name)}',
          leading: <Widget>[
            Expanded(
              child: NameInput(
                name: name,
                label: 'Name of routine ${index + 1}',
                problemOf: (String next) => routineNameProblem(next, <String>[for (final String other in names) if (other != name) other]),
                onRename: (String next) =>
                    onJson(_renameAt('routines', index, (JsonMap latest, String from) => renameRoutine(latest, from, next, _catalog)), coalesce: 'behavior:rename:$index'),
              ),
            ),
          ],
          more: <AppMenuEntry>[
            if (!entry) AppMenuEntry('Start here', () => _write(<JsonStep>['entry'], name)),
            if (names.length > 1)
              AppMenuEntry(
                'Delete routine',
                () => confirmDelete('routine ${behaviorTitle(name)}', 'Its steps go with it.', () => _change(<JsonStep>['routines'], <String, Object?>{...routines}..remove(name), 'delete routine ${behaviorTitle(name)}')),
                destructive: true,
              ),
          ],
        ),
        BehaviorNodeView(
          key: ValueKey<String>('routine-node-$index'),
          context: behavior,
          node: routines[name],
          steps: <JsonStep>['routines', name],
          caption: behaviorTitle(name),
          flat: true,
        ),
        ?growStep(behavior, <JsonStep>['routines', name], routines[name]),
      ],
    );
  }

  Widget _triggers(JsonMap doc, BehaviorContext behavior) {
    final JsonList triggers = asList(doc['triggers']);
    void put(JsonList next) => _write(<JsonStep>['triggers'], next.isEmpty ? null : next);
    final List<String> ids = <String>[for (final Object? trigger in triggers) asString(asMap(trigger)['id']) ?? ''];
    final List<String> events = _catalog.eventsFor(server: behavior.server);
    return AppSection(
      title: 'Triggers',
      count: triggers.length,
      tip: behavior.server ? 'What starts a routine: server events. NPC events need Server behavior off.' : 'What starts a routine. Server events need Server behavior on.',
      docs: Docs.behaviorTriggers,
      children: <Widget>[
        if (triggers.isEmpty)
          noteLine(behavior.server ? 'Nothing starts it yet. Add a join, chat or command trigger.' : 'Nothing starts it yet. It runs only when called.'),
        for (int index = 0; index < triggers.length; index++) _trigger(triggers, index, put, behavior),
        _addMenu('Add trigger', <AppMenuEntry>[
          for (final String event in events)
            AppMenuEntry(eventWords(event), () {
              final JsonMap trigger = defaultTrigger(event, _catalog, ids, server: behavior.server);
              _change(<JsonStep>['triggers'], <Object?>[...triggers, trigger], 'add trigger ${triggerClause(trigger)}');
            }),
        ]),
      ],
    );
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

  /// The prompt and the option a `dialogue_choice` trigger answers, picked
  /// from the prompts this behavior asks.
  Widget _answerPicker(JsonMap trigger, FieldSpec raw, int index, List<DialoguePrompt> prompts, String path, void Function(JsonMap next) put) {
    final bool dialogue = raw.key == 'dialogue';
    final String? value = asString(trigger[raw.key]);
    final PickerOptions options = dialogue ? promptOptions(prompts) : choiceOptions(prompts, asString(trigger['dialogue']));
    final String? note = value == null || options.ids.contains(value)
        ? (dialogue && prompts.isEmpty ? 'No question yet. Add an Ask step to a routine.' : null)
        : (dialogue ? 'No question $value in this behavior.' : 'Not an answer of this question.');
    return Field(
      key: ValueKey<String>('$path.${raw.key}'),
      label: dialogue ? 'Question' : 'Answer',
      tip: raw.description,
      problems: diagnosticsAt(diagnostics, childPath(path, raw.key)),
      child: stack(spacing: 4, <Widget>[
        AnimusSelect.values(
          value: value,
          values: options.ids,
          labels: options.labels,
          allowNone: !dialogue,
          noneLabel: dialogue ? 'Pick a question' : 'Any answer',
          hiddenLabel: dialogue ? 'Question of trigger ${index + 1}' : 'Answer of trigger ${index + 1}',
          onChanged: (String? next) => put(dialogue ? withDialogue(trigger, next, prompts) : asMap(writeIn(trigger, <JsonStep>['choice'], next))),
        ),
        if (note != null) problemLine(DiagnosticSeverity.warning, note, classes: 'animus-node-problem'),
      ]),
    );
  }

  Widget _trigger(JsonList triggers, int index, void Function(JsonList next) put, BehaviorContext behavior) {
    final JsonMap trigger = asMap(triggers[index]);
    final String? event = asString(trigger['event']);
    final TriggerSpec? spec = _catalog.triggers[event];
    final List<JsonStep> steps = <JsonStep>['triggers', index];
    final String path = formatPath(steps);
    final Set<String> known = triggerKnownKeys(spec);
    final bool answers = event == 'dialogue_choice';
    final List<DialoguePrompt> prompts = answers ? dialoguePrompts(_doc) : const <DialoguePrompt>[];
    final bool misplaced = spec != null && !spec.allowedIn(server: behavior.server);
    final List<String> events = <String>[..._catalog.eventsFor(server: behavior.server), if (event != null && misplaced) event];
    final bool failing = diagnosticsAt(diagnostics, path, nested: true).any((Diagnostic d) => d.severity == DiagnosticSeverity.error);
    return AppSection(
      key: ValueKey<String>('trigger-$index'),
      classes: 'animus-trigger${failing ? ' has-error' : ''}',
      attributes: <String, String>{'data-node-path': path, 'data-event': event ?? '', 'role': 'group', 'aria-label': 'Trigger ${index + 1}'},
      summary: <Widget>[
        Component.text(triggerSentence(trigger)),
        if (trigger['routine'] is String) Text('runs ${behaviorTitle(trigger['routine']! as String)}', size: FontSize.sm, color: TextColor.muted),
        if (failing || misplaced) const StatusBadge('Problem', tone: BadgeTone.error),
      ],
      children: <Widget>[
        ActionRow(
          label: 'Trigger ${index + 1}',
          leading: <Widget>[
            AnimusSelect.values(
              value: event,
              values: events,
              labels: <String, String>{for (final String each in events) each: eventWords(each)},
              noneLabel: 'Pick an event',
              hiddenLabel: 'Event of trigger ${index + 1}',
              onChanged: (String? next) {
                if (next != null) put(<Object?>[...triggers]..[index] = changeTriggerEvent(trigger, next, _catalog));
              },
            ),
            if (spec != null) InfoButton(tip: '${spec.description}${spec.carriesPlayer ? '' : ' No player is carried.'} Written as $event.', docs: Docs.behaviorTriggers),
            if (spec == null) noteLine('Unknown event, kept as written'),
          ],
          more: <AppMenuEntry>[
            AppMenuEntry('Move up', index == 0 ? null : () => _change(<JsonStep>['triggers'], moveItem(triggers, index, index - 1), 'move trigger up')),
            AppMenuEntry('Move down', index == triggers.length - 1 ? null : () => _change(<JsonStep>['triggers'], moveItem(triggers, index, index + 1), 'move trigger down')),
            const AppMenuEntry.separator(),
            AppMenuEntry(
              'Delete trigger',
              () => confirmDelete('trigger ${index + 1}', triggerSentence(trigger), () {
                final JsonList rest = removeItem(triggers, index);
                _change(<JsonStep>['triggers'], rest.isEmpty ? null : rest, 'delete trigger ${triggerClause(trigger)}');
              }),
              destructive: true,
            ),
          ],
        ),
        if (misplaced)
          problemLine(
            DiagnosticSeverity.error,
            behavior.server
                ? '${eventWords(event ?? '')} needs an NPC. Pick a server event.'
                : '${eventWords(event ?? '')} is a server event. Turn on Server behavior or pick an NPC event.',
            classes: 'animus-node-problem',
          ),
        for (final Diagnostic problem in diagnosticsAt(diagnostics, path)) problemLine(problem.severity, problem.message, classes: 'animus-node-problem'),
        for (final FieldSpec raw in spec?.fields ?? const <FieldSpec>[])
          if (raw.key != 'event')
            if (fieldType(raw) == 'routine')
              Field(
                key: ValueKey<String>('$path.${raw.key}'),
                label: 'Runs',
                tip: 'The routine it starts. Written as routine.',
                problems: diagnosticsAt(diagnostics, childPath(path, raw.key)),
                child: AnimusSelect.values(
                  value: asString(trigger[raw.key]),
                  values: behavior.routines,
                  labels: <String, String>{for (final String name in behavior.routines) name: behaviorTitle(name)},
                  allowNone: !raw.required,
                  noneLabel: 'The routine it starts in',
                  hiddenLabel: 'Routine trigger ${index + 1} runs',
                  onChanged: (String? v) => _write(<JsonStep>[...steps, raw.key], v),
                ),
              )
            else if (answers && (raw.key == 'dialogue' || raw.key == 'choice'))
              _answerPicker(trigger, raw, index, prompts, path, (JsonMap next) => put(<Object?>[...triggers]..[index] = next))
            else if (event == 'interaction' && raw.key == 'action')
              Field(
                key: ValueKey<String>('$path.${raw.key}'),
                label: 'Click',
                tip: raw.description,
                problems: diagnosticsAt(diagnostics, childPath(path, raw.key)),
                child: AnimusSelect.values(
                  value: asString(trigger[raw.key]),
                  values: <String>[for (final FieldOption option in raw.options) option.label],
                  labels: interactionActionLabels,
                  allowNone: true,
                  noneLabel: 'Right-click',
                  hiddenLabel: 'Click of trigger ${index + 1}',
                  onChanged: (String? v) => _write(<JsonStep>[...steps, raw.key], v),
                ),
              )
            else
              InspectField(
                key: ValueKey<String>('$path.${raw.key}'),
                field: behavior.server && event == 'timer' && raw.key == 'intervalTicks' ? _serverInterval(raw) : raw,
                label: triggerFieldLabel(raw.key),
                profile: triggerFieldProfile(raw.key, type: fieldType(raw)),
                value: trigger[raw.key],
                path: childPath(path, raw.key),
                diagnostics: diagnostics,
                suggestions: answers && raw.key == 'id' && asString(trigger['dialogue']) != null
                    ? <FieldOption>[FieldOption(dialogueTriggerId(asString(trigger['dialogue'])!, asString(trigger['choice']), <String>[for (int other = 0; other < triggers.length; other++) if (other != index) asString(asMap(triggers[other])['id']) ?? '']))]
                    : const <FieldOption>[],
                onChanged: (Object? v) => _write(<JsonStep>[...steps, raw.key], v),
              ),
        if (unknownEntries(trigger, known) case final JsonMap other when other.isNotEmpty)
          _otherFields(other, path, (JsonMap next) => put(<Object?>[...triggers]..[index] = withUnknownEntries(trigger, known, next))),
      ],
    );
  }
}

/// A variable's or routine's name, renamed on each valid edit. An invalid
/// name changes nothing and says why, also after focus leaves and the field
/// shows the kept name again.
class NameInput extends StatefulWidget {
  const NameInput({required this.name, required this.label, required this.problemOf, required this.onRename, this.noteOf, super.key});

  final String name;
  final String label;

  /// Why a typed name cannot be used, or null.
  final String? Function(String name) problemOf;

  /// A warning about a valid name, or null.
  final String? Function(String name)? noteOf;
  final void Function(String next) onRename;

  @override
  State<NameInput> createState() => _NameInputState();
}

class _NameInputState extends State<NameInput> {
  String? _problem;
  bool _left = false;

  @override
  void didUpdateWidget(NameInput oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (oldComponent.name != component.name) _problem = null;
  }

  @override
  Widget build(BuildContext context) {
    final String? note = _problem == null ? component.noteOf?.call(component.name) : null;
    return dom.div(
      events: <String, void Function(web.Event)>{
        'focusout': (web.Event _) {
          if (_problem != null && !_left) setState(() => _left = true);
        },
      },
      <Widget>[
        stack(spacing: 4, <Widget>[
          AutocompleteInput(
            value: component.name,
            label: component.label,
            invalid: _problem != null && !_left,
            onChanged: (String next) {
              final String? problem = next == component.name ? null : component.problemOf(next);
              setState(() {
                _problem = problem;
                _left = false;
              });
              if (problem == null && next != component.name) component.onRename(next);
            },
            profile: FieldProfile.none,
          ),
          if (_problem != null) problemLine(DiagnosticSeverity.error, _left ? 'Kept ${component.name}. $_problem' : _problem!, classes: 'animus-node-problem', role: 'alert'),
          if (note != null) problemLine(DiagnosticSeverity.warning, note, classes: 'animus-node-problem'),
        ]),
      ],
    );
  }
}

/// A document id renamed when Enter is pressed or focus leaves: the move
/// takes the NPCs that list it along. A refused id says why and keeps the
/// old one.
class RenameInput extends StatefulWidget {
  const RenameInput({required this.value, required this.label, required this.onRename, super.key});

  final String value;
  final String label;
  final Future<String?> Function(String id) onRename;

  @override
  State<RenameInput> createState() => _RenameInputState();
}

class _RenameInputState extends State<RenameInput> {
  String? _typed;
  String? _problem;
  bool _busy = false;

  Future<void> _commit() async {
    final String next = (_typed ?? component.value).trim();
    if (_busy || next == component.value) {
      setState(() => _typed = null);
      return;
    }
    setState(() => _busy = true);
    final String? problem = await component.onRename(next);
    if (!mounted) return;
    setState(() {
      _busy = false;
      _problem = problem == null ? null : 'Kept ${component.value}. $problem';
      _typed = null;
    });
  }

  @override
  Widget build(BuildContext context) => stack(spacing: 4, <Widget>[
        TextInput(
          size: controlSize(DensityScope.of(context)),
          value: _typed ?? component.value,
          attributes: <String, String>{'aria-label': component.label, if (_problem != null) 'aria-invalid': 'true'},
          onChanged: (String value) => setState(() {
            _typed = value;
            _problem = null;
          }),
          onSubmitted: (String _) => _commit(),
          onBlur: _commit,
        ),
        if (_problem != null) problemLine(DiagnosticSeverity.error, _problem!, classes: 'animus-node-problem', role: 'alert'),
      ]);
}
