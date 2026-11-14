/// An action list editor shared by chest GUIs, menus, holograms and shops'
/// layouts. Only actions Animus runs are offered; an action Animus refuses is
/// flagged with its exact field and can only be removed or replaced.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../config/docs_links.dart';
import '../../logic/gui/action_targets.dart';
import '../../logic/gui/gui_catalog.dart';
import '../../logic/gui/gui_expression.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../inspect/typed_values_editor.dart';
import '../mc/item_picker.dart';
import '../select/suggest_input.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/confirm_dialog.dart';
import '../../ui/icon_button.dart';
import '../../ui/section.dart';
import 'gui_fields.dart';
import 'gui_rows.dart';
import 'gui_targets.dart';
import '../../logic/suggest/field_profile.dart';
import '../select/animus_select.dart';
import '../suggest/autocomplete_input.dart';
import '../../ui/field.dart';
import '../../ui/number_input.dart';
import 'gui_stack.dart';
import '../../ui/stack.dart';

/// Labels for the add menu.
const Map<String, String> actionLabels = <String, String>{
  'command': 'Run a command',
  'sound': 'Play a sound',
  'message': 'Send a message',
  'title': 'Show a title',
  'actionbar': 'Show an action bar',
  'close': 'Close',
  'navigate': 'Navigate',
  'inventory': 'Open a chest GUI',
  'setSession': 'Set a session value',
  'give': 'Give items',
  'take': 'Take items',
  'economy': 'Money',
  'delay': 'Wait',
  'sequence': 'Timed sequence',
  'if': 'If',
  'switch': 'Switch',
  'chance': 'Chance',
  'cooldown': 'Cooldown',
  'stop': 'Stop',
  'call': 'Call a named list',
  'teleport': 'Teleport',
  'bossbar': 'Show a boss bar',
  'broadcast': 'Broadcast',
  'effect': 'Potion effect',
  'particle': 'Particles',
  'parallel': 'Run side by side',
  'repeat': 'Repeat',
  'animus:open-shop': 'Open a shop (Animus)',
};

class ActionsEditor extends StatelessWidget {
  const ActionsEditor({
    required this.actions,
    required this.onChanged,
    required this.path,
    required this.diagnostics,
    this.names = ExpressionNames.standard,
    this.namedLists = const <String>[],
    this.lists = const <String>[],
    this.steps = false,
    this.unavailable = const <String>[],
    super.key,
  });

  /// Actions this surface refuses on top of the shared refused list
  /// (holograms have no window or named lists).
  final List<String> unavailable;

  final JsonList actions;
  final void Function(JsonList actions) onChanged;

  /// The list's path in the document, for diagnostics.
  final String path;
  final List<Diagnostic> diagnostics;
  final ExpressionNames names;

  /// Named lists under `actions`, offered to `call`.
  final List<String> namedLists;

  /// Ids of the menu's list components, offered to `navigate.list`.
  final List<String> lists;

  /// A sequence's steps, which carry `atTicks`.
  final bool steps;

  @override
  Widget build(BuildContext context) {
    final GuiActionLists lists = GuiActionLists.instance;
    final GuiTargets? targets = GuiTargets.of(context);
    return stack(<Widget>[
      if (actions.isEmpty) mutedText(steps ? 'No steps yet.' : 'Nothing runs yet.'),
      if (actions.isNotEmpty)
        OrderedRows(
          label: steps ? 'Steps' : 'Actions',
          dense: false,
          rows: <OrderedRow>[for (int index = 0; index < actions.length; index++) _action(index, asMap(actions[index]), lists, targets)],
          onMove: (int from, int to) => onChanged(moveItem(actions, from, to)),
          onDuplicate: (int index) => onChanged(<Object?>[...actions]..insert(index + 1, deepCopy(actions[index]))),
          onDelete: (int index) => onChanged(<Object?>[...actions]..removeAt(index)),
        ),
      AnimusSelect.values(
        value: null,
        hiddenLabel: steps ? 'Add step' : 'Add action',
        noneLabel: steps ? 'Add step' : 'Add action',
        values: <String>[
          for (final String type in lists.offered)
            if (!unavailable.contains(type)) type,
        ],
        labels: actionLabels,
        onChanged: (String? type) {
          if (type == null) return;
          onChanged(<Object?>[...actions, newAction(type)]);
        },
      ),
    ]);
  }

  void _set(int index, JsonMap action) => onChanged(<Object?>[...actions]..[index] = action);

  OrderedRow _action(int index, JsonMap action, GuiActionLists lists, GuiTargets? targets) {
    final String actionPath = indexPath(path, index);
    final String type = asString(action['type']) ?? '';
    final bool offered = lists.isOffered(type) && !unavailable.contains(type);
    final List<Diagnostic> typeProblems = diagnosticsAt(diagnostics, childPath(actionPath, 'type'));
    return OrderedRow(
      key: '$index',
      name: '${steps ? 'step' : 'action'} ${index + 1}',
      open: true,
      attributes: <String, String>{'data-path': actionPath, if (!offered) 'data-refused': 'true'},
      line: fieldLine(<Widget>[
        _ActionTypePicker(
          id: guiPathId('$actionPath-type'),
          label: '${steps ? 'Step' : 'Action'} ${index + 1}',
          action: action,
          options: <String>[
            for (final String one in lists.offered)
              if (!unavailable.contains(one)) one,
          ],
          labels: <String, String>{...actionLabels, if (!offered) type: lists.refused.contains(type) || unavailable.contains(type) ? '$type (not available here)' : '$type (unknown)'},
          onChanged: (JsonMap changed) => _set(index, changed),
        ),
      ]),
      body: stack(<Widget>[
        if (!offered)
          GuiNote(tone: AlertTone.error, message: '${typeProblems.isNotEmpty ? typeProblems.first.message : "'$type' is not available in Animus"}. Pick another action or delete it.')
        else ...<Widget>[
          for (final GuiField field in actionFields[type] ?? const <GuiField>[]) _input(index, action, field, actionPath, targets),
          if (type == 'command') const GuiNote(message: 'Runs only when config.toml allows commands from this GUI.'),
          for (final String refused in refusedSequenceFields)
            if (type == 'sequence' && action.containsKey(refused))
              GuiNote(
                tone: AlertTone.error,
                message: "'$refused' is not available in Animus.",
                action: Button.outline(label: 'Remove $refused', size: ButtonSize.sm, onPressed: () => _set(index, withActionField(action, refused, null))),
              ),
          if (steps)
            Field(
              label: 'At tick',
              forId: guiPathId('$actionPath.atTicks'),
              help: 'Ticks from sequence start',
              problems: diagnosticsAt(diagnostics, childPath(actionPath, 'atTicks')),
              child: NumberInput(
                id: guiPathId('$actionPath.atTicks'),
                value: asInt(action['atTicks']),
                label: 'At tick',
                onChanged: (num? value) => _set(index, withActionField(action, 'atTicks', value)),
              ),
            ),
          AppSection(
            title: 'Click and condition',
            tip: 'Which click runs this action, a condition it needs and a cooldown per viewer.',
            docs: Docs.guiActions,
            open: action.containsKey('trigger') || (action.containsKey('when') && type != 'if') || action.containsKey('cooldownTicks'),
            children: <Widget>[
              for (final GuiField field in commonActionFields)
                if (!(field.name == 'when' && type == 'if')) _input(index, action, field, actionPath, targets),
            ],
          ),
        ],
      ]),
    );
  }

  Widget _input(int index, JsonMap action, GuiField field, String actionPath, GuiTargets? targets) {
    final String fieldPath = childPath(actionPath, field.name);
    final Object? value = action[field.name];
    void set(Object? next) => _set(index, withActionField(action, field.name, next));
    final String name = humanizeKey(field.name);
    final String label = '$name${field.required ? ' *' : ''}';
    final String id = guiPathId(fieldPath);
    final List<Diagnostic> problems = diagnosticsAt(diagnostics, fieldPath);
    switch (field.kind) {
      case GuiFieldKind.template || GuiFieldKind.expression || GuiFieldKind.text:
        final String type = asString(action['type']) ?? '';
        final List<FieldOption>? picks = _picks(type, field.name, action, targets);
        if (picks != null) {
          return Field(
            label: label,
            forId: id,
            help: field.help,
            problems: problems,
            child: SuggestInput(
              id: id,
              value: value is String ? value : (value == null ? '' : '$value'),
              label: name,
              suggestions: picks,
              references: actionReference(type, field.name, targets?.kind ?? DocumentKind.inventory),
              onChanged: set,
            ),
          );
        }
        return Field(
          label: label,
          forId: id,
          help: field.help,
          problems: problems,
          child: AutocompleteInput(
            id: id,
            value: value is String ? value : (value == null ? '' : '$value'),
            profile: switch (field.kind) {
              GuiFieldKind.template => FieldProfile.guiText,
              GuiFieldKind.expression => FieldProfile.guiExpression,
              _ => FieldProfile.none,
            },
            preview: field.kind == GuiFieldKind.template ? templateSample : null,
            gui: names,
            label: name,
            onChanged: set,
          ),
        );
      case GuiFieldKind.integer || GuiFieldKind.number:
        return Field(
          label: label,
          forId: id,
          help: field.help,
          problems: problems,
          child: NumberInput(id: id, value: value is num ? value : null, integer: field.kind == GuiFieldKind.integer, label: name, onChanged: set),
        );
      case GuiFieldKind.boolean:
        return Field(
          label: name,
          help: field.help,
          problems: problems,
          child: ArcaneToggleSwitch(value: value == true, label: name, onChanged: (bool on) => set(on ? true : null), size: ComponentSize.sm),
        );
      case GuiFieldKind.choice:
        return Field(
          label: label,
          forId: id,
          help: field.help,
          problems: problems,
          child: AnimusSelect.values(id: id, value: asString(value), values: field.choices, allowNone: !field.required, onChanged: set),
        );
      case GuiFieldKind.multiChoice:
        final List<String> chosen = stringList(value);
        return Field(
          label: label,
          help: field.help,
          problems: problems,
          child: switchRow(<Widget>[
            for (final String choice in field.choices)
              ArcaneToggleSwitch(
                value: chosen.contains(choice),
                label: choice,
                onChanged: (bool on) {
                  final List<String> next = <String>[
                    for (final String one in field.choices)
                      if (one == choice ? on : chosen.contains(one)) one,
                  ];
                  set(next.isEmpty ? null : next);
                },
                size: ComponentSize.sm,
              ),
          ]),
        );
      case GuiFieldKind.actions:
        return AppSection(
          title: name,
          count: asList(value).length,
          tip: field.help.isEmpty ? 'Actions run in order inside this one.' : field.help,
          docs: Docs.guiActions,
          children: <Widget>[
            ActionsEditor(
              actions: asList(value),
              path: fieldPath,
              diagnostics: diagnostics,
              names: names,
              namedLists: namedLists,
              lists: lists,
              steps: field.name == 'steps' && asString(action['type']) == 'sequence',
              unavailable: unavailable,
              onChanged: (JsonList next) => _set(index, <String, Object?>{...action, field.name: next}),
            ),
          ],
        );
      case GuiFieldKind.branches:
        final JsonList branches = asList(value);
        return AppSection(
          title: 'Branches',
          count: branches.length,
          tip: 'Action lists that start together and run side by side.',
          docs: Docs.guiActions,
          children: <Widget>[
            for (int branch = 0; branch < branches.length; branch++)
              AppSection(
                title: 'Branch ${branch + 1}',
                tip: 'One list, run in order alongside the other branches.',
                docs: Docs.guiActions,
                children: <Widget>[
                  ActionsEditor(
                    actions: asList(branches[branch]),
                    path: indexPath(fieldPath, branch),
                    diagnostics: diagnostics,
                    unavailable: unavailable,
                    names: names,
                    namedLists: namedLists,
                    lists: lists,
                    onChanged: (JsonList next) => set(<Object?>[...branches]..[branch] = next),
                  ),
                  ActionRow(
                    dense: true,
                    label: 'Branch ${branch + 1}',
                    secondary: <RowAction>[
                      RowAction('Remove branch', branches.length <= 1 ? null : () => set(<Object?>[...branches]..removeAt(branch))),
                    ],
                  ),
                ],
              ),
            ActionRow(
              dense: true,
              label: 'Branches',
              secondary: <RowAction>[
                RowAction('Add branch', () => set(<Object?>[...branches, <Object?>[]]), icon: ArcaneIcon.plus()),
              ],
            ),
          ],
        );
      case GuiFieldKind.cases:
        final JsonMap cases = asMap(value);
        return AppSection(
          title: 'Cases',
          count: cases.length,
          tip: 'The list whose value matches on runs. No match runs default.',
          docs: Docs.guiActions,
          children: <Widget>[
            for (final MapEntry<String, Object?> entry in cases.entries)
              stack(<Widget>[
                Field(
                  label: 'When on is',
                  child: fieldLine(<Widget>[
                    AutocompleteInput(
                      value: entry.key,
                      label: 'Case value',
                      onChanged: (String next) {
                        if (next.isEmpty || cases.containsKey(next)) return;
                        set(renameKey(cases, entry.key, next));
                      },
                      profile: FieldProfile.none,
                    ),
                    AppIconButton(icon: ArcaneIcon.trash(), label: 'Delete case ${entry.key}', onPressed: () => set(<String, Object?>{...cases}..remove(entry.key))),
                  ]),
                ),
                ActionsEditor(
                  actions: asList(entry.value),
                  path: childPath(fieldPath, entry.key),
                  diagnostics: diagnostics,
                  unavailable: unavailable,
                  names: names,
                  namedLists: namedLists,
                  lists: lists,
                  onChanged: (JsonList next) => set(<String, Object?>{...cases, entry.key: next}),
                ),
              ]),
            ActionRow(
              dense: true,
              label: 'Cases',
              secondary: <RowAction>[
                RowAction('Add case', () {
                  int n = cases.length + 1;
                  while (cases.containsKey('case$n')) {
                    n++;
                  }
                  set(<String, Object?>{...cases, 'case$n': <Object?>[]});
                }, icon: ArcaneIcon.plus()),
              ],
            ),
          ],
        );
      case GuiFieldKind.icon:
        final JsonMap icon = asMap(value);
        // The item keeps every key it has; type is item when absent.
        JsonMap with_(String key, Object? next) {
          final JsonMap out = <String, Object?>{...icon};
          if (next == null || next == '' || (next is List<Object?> && next.isEmpty)) {
            out.remove(key);
          } else {
            out[key] = next;
          }
          return out;
        }

        Widget row(String key, String rowLabel, Widget Function(String id) child, {String? help, bool labelled = true}) {
          final String rowId = guiPathId(childPath(fieldPath, key));
          return Field(
            label: rowLabel,
            forId: labelled ? rowId : null,
            help: help,
            problems: diagnosticsAt(diagnostics, childPath(fieldPath, key), nested: key == 'lore'),
            child: child(rowId),
          );
        }

        return AppSection(
          title: name,
          tip: 'The item, its count, name and lore.',
          docs: Docs.guiActions,
          children: <Widget>[
            for (final Diagnostic problem in problems) GuiNote(tone: problem.severity == DiagnosticSeverity.error ? AlertTone.error : AlertTone.warning, message: problem.message),
            row(
              'item',
              'Item *',
              (String rowId) => ItemPicker(id: rowId, value: asString(icon['item']) ?? '', label: 'Item id', placeholder: 'minecraft:bread', onChanged: (String v) => set(with_('item', v))),
              help: 'An item, or an expression giving one',
            ),
            row(
              'count',
              'Count',
              (String rowId) => NumberInput(id: rowId, value: asInt(icon['count']), label: 'Item count', onChanged: (num? v) => set(with_('count', v))),
              help: '1 to 99 per stack',
            ),
            row(
              'name',
              'Name',
              (String rowId) =>
                  AutocompleteInput(id: rowId, value: asString(icon['name']) ?? '', profile: FieldProfile.guiText, preview: itemNameSample, gui: names, label: 'Item name', onChanged: (String v) => set(with_('name', v))),
            ),
            row('lore', 'Lore', (String _) => GuiLines(label: 'Lore', lines: stringList(icon['lore']), names: names, onChanged: (List<String> lines) => set(with_('lore', lines))), labelled: false),
            row(
              'customModelValue',
              'Custom model',
              (String rowId) => NumberInput(id: rowId, value: asInt(icon['customModelValue']), label: 'Custom model value', onChanged: (num? v) => set(with_('customModelValue', v))),
            ),
          ],
        );
      case GuiFieldKind.object:
        final JsonMap args = inventoryArgs(value);
        return Field(
          label: name,
          help: field.help,
          problems: diagnosticsAt(diagnostics, fieldPath, nested: true),
          child: stack(<Widget>[
            KeyValueEditor(
              value: args,
              label: name,
              addLabel: 'Add argument',
              onChanged: (JsonMap next) => set(next.isEmpty ? null : next),
              valueControl: (String key, Object? entry, void Function(Object? value) onEntry) => entry is String || entry == null
                  ? AutocompleteInput(value: asString(entry) ?? '', profile: FieldProfile.guiText, preview: templateSample, gui: names, label: 'Argument $key', onChanged: onEntry)
                  : TypedValueEditor(value: entry, label: 'Argument $key', profileFor: (String _) => FieldProfile.guiText, onChanged: onEntry),
            ),
            for (final String key in args.keys)
              if (argumentNameProblem(key) case final String problem) GuiNote(tone: AlertTone.error, message: '$key: $problem'),
          ]),
        );
    }
  }

  /// What a text field that names something offers, or null for plain text.
  List<FieldOption>? _picks(String type, String field, JsonMap action, GuiTargets? targets) {
    final DocumentKind? kind = actionReference(type, field, targets?.kind ?? DocumentKind.inventory);
    final List<String> ids = kind == null ? const <String>[] : (targets?.ids(kind) ?? const <String>[]);
    return switch ((type, field)) {
      ('call', 'action') => callTargetOptions(namedLists),
      ('navigate', 'list') => <FieldOption>[for (final String list in lists) FieldOption(list, description: 'list component')],
      ('navigate', 'target') => navigateTargetOptions(mode: asString(action['mode']), ids: ids),
      ('inventory', 'id') || ('animus:open-shop', 'shop') => <FieldOption>[for (final String id in ids) FieldOption(id)],
      _ => null,
    };
  }
}

/// The action's type select. A change that drops nested action lists asks
/// first, in place of the select, and focus comes back to the select after
/// either answer.
class _ActionTypePicker extends StatelessWidget {
  const _ActionTypePicker({required this.id, required this.label, required this.action, required this.options, required this.labels, required this.onChanged});

  final String id;
  final String label;
  final JsonMap action;
  final List<String> options;
  final Map<String, String> labels;
  final void Function(JsonMap action) onChanged;

  Future<void> _pick(String? next) async {
    if (next == null || next == asString(action['type'])) return;
    final (JsonMap changed, List<String> dropped) = changeActionType(action, next);
    if (dropped.isNotEmpty &&
        !await askToConfirm(
          title: 'Change to ${labels[next] ?? next}?',
          message: 'It has no ${dropped.join(', ')}, so the actions in them are dropped.',
          confirmLabel: 'Change type',
          destructive: true,
        )) {
      return;
    }
    onChanged(changed);
  }

  @override
  Widget build(BuildContext context) {
    return AnimusSelect.values(id: id, hiddenLabel: label, value: asString(action['type']) ?? '', values: options, labels: labels, onChanged: _pick);
  }
}

/// A document's named action lists (`actions`), which any click runs with
/// Call: one row per list, opened to rename it and edit its actions.
class NamedActionLists extends StatefulWidget {
  const NamedActionLists({required this.named, required this.diagnostics, required this.onChanged, this.lists = const <String>[], super.key});

  final JsonMap named;
  final List<Diagnostic> diagnostics;

  /// The whole new `actions` map; null removes it.
  final void Function(JsonMap? named) onChanged;

  /// Ids of a menu's list components, offered to `navigate.list`.
  final List<String> lists;

  @override
  State<NamedActionLists> createState() => _NamedActionListsState();
}

class _NamedActionListsState extends State<NamedActionLists> {
  String? _open;

  @override
  Widget build(BuildContext context) {
    final JsonMap named = component.named;
    final List<String> names = named.keys.toList();
    final String? open = named.containsKey(_open) ? _open : null;
    return AppSection(
      title: 'Named actions',
      count: named.length,
      open: named.isNotEmpty,
      tip: 'Action lists any click can run with Call. Calls must not loop.',
      docs: Docs.guiActions,
      children: <Widget>[
        stack(<Widget>[
          if (names.isNotEmpty)
            OrderedRows(
              label: 'Named actions',
              deleteNoun: 'action list',
              rows: <OrderedRow>[
                for (final String name in names)
                  OrderedRow(
                    // By position: renaming keeps the open row and its field.
                    key: '${names.indexOf(name)}',
                    name: name,
                    open: open == name,
                    attributes: <String, String>{'data-path': childPath(r'$.actions', name)},
                    line: RowOpener(
                      open: open == name,
                      label: '$name, ${asList(named[name]).length == 1 ? '1 action' : '${asList(named[name]).length} actions'}',
                      onToggle: () => setState(() => _open = open == name ? null : name),
                      children: <Widget>[
                        truncatedText(name),
                        mutedText(asList(named[name]).length == 1 ? '1 action' : '${asList(named[name]).length} actions'),
                      ],
                      body: open == name
                          ? stack(<Widget>[
                              Field(
                                label: 'Name',
                                forId: guiPathId(childPath(r'$.actions', name)),
                                tip: 'Letters, digits, _ and -.',
                                problems: diagnosticsAt(component.diagnostics, childPath(r'$.actions', name)),
                                child: AutocompleteInput(
                                  id: guiPathId(childPath(r'$.actions', name)),
                                  value: name,
                                  label: 'List name',
                                  onChanged: (String next) {
                                    if (!RegExp(r'^[A-Za-z0-9_-]{1,64}$').hasMatch(next) || named.containsKey(next)) return;
                                    component.onChanged(renameKey(named, name, next));
                                    setState(() => _open = next);
                                  },
                                  profile: FieldProfile.none,
                                ),
                              ),
                              ActionsEditor(
                                actions: asList(named[name]),
                                path: childPath(r'$.actions', name),
                                diagnostics: component.diagnostics,
                                namedLists: names,
                                lists: component.lists,
                                onChanged: (JsonList next) => component.onChanged(<String, Object?>{...named, name: next}),
                              ),
                            ])
                          : null,
                    ),
                  ),
              ],
              onDelete: (int index) => component.onChanged(named.length == 1 ? null : (<String, Object?>{...named}..remove(names[index]))),
            ),
          ActionRow(
            dense: true,
            label: 'Named actions',
            secondary: <RowAction>[
              RowAction('Add list', () {
                int n = named.length + 1;
                while (named.containsKey('list$n')) {
                  n++;
                }
                component.onChanged(<String, Object?>{...named, 'list$n': <Object?>[]});
                setState(() => _open = 'list$n');
              }, icon: ArcaneIcon.plus()),
            ],
          ),
        ]),
      ],
    );
  }
}
