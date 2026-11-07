/// An action list editor shared by chest GUIs, menus, holograms and shops'
/// layouts. Only actions Animus runs are offered; an action Animus refuses is
/// flagged with its exact field and can only be removed or replaced.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

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
import '../offline/offline_fields.dart' show ConfirmRow;
import '../select/suggest_input.dart';
import '../server/live_confirm.dart' show focusSoon;
import 'gui_fields.dart';
import 'gui_targets.dart';
import '../../logic/suggest/field_profile.dart';

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
    return dom.div(classes: 'animus-gui-actions', <Widget>[
      if (actions.isEmpty) dom.p(classes: 'animus-muted', <Widget>[Component.text(steps ? 'No steps yet. Add a step below.' : 'Nothing runs yet. Add an action below.')]),
      for (int index = 0; index < actions.length; index++) _action(index, asMap(actions[index]), lists, targets),
      dom.div(classes: 'animus-gui-add', <Widget>[
        GuiSelect(
          value: null,
          hiddenLabel: steps ? 'Add a step' : 'Add an action',
          noneLabel: steps ? 'Add a step...' : 'Add an action...',
          options: <String>[for (final String type in lists.offered) if (!unavailable.contains(type)) type],
          labels: actionLabels,
          onChanged: (String? type) {
            if (type == null) return;
            onChanged(<Object?>[...actions, newAction(type)]);
          },
        ),
      ]),
    ]);
  }

  void _set(int index, JsonMap action) => onChanged(<Object?>[...actions]..[index] = action);

  Widget _action(int index, JsonMap action, GuiActionLists lists, GuiTargets? targets) {
    final String actionPath = indexPath(path, index);
    final String type = asString(action['type']) ?? '';
    final bool offered = lists.isOffered(type) && !unavailable.contains(type);
    final List<Diagnostic> typeProblems = diagnosticsAt(diagnostics, childPath(actionPath, 'type'));
    return dom.div(
      classes: 'animus-gui-action${offered ? '' : ' is-refused'}',
      attributes: <String, String>{'data-path': actionPath},
      <Widget>[
        dom.div(classes: 'animus-gui-action-head', <Widget>[
          dom.label(htmlFor: guiPathId('$actionPath-type'), classes: 'animus-gui-action-index', <Widget>[
            dom.span(classes: 'animus-sr-only', <Widget>[Component.text(steps ? 'Step ' : 'Action ')]),
            Component.text('${index + 1}'),
          ]),
          _ActionTypePicker(
            id: guiPathId('$actionPath-type'),
            action: action,
            options: <String>[for (final String one in lists.offered) if (!unavailable.contains(one)) one],
            labels: <String, String>{
              ...actionLabels,
              if (!offered) type: lists.refused.contains(type) || unavailable.contains(type) ? '$type (not available here)' : '$type (unknown)',
            },
            onChanged: (JsonMap changed) => _set(index, changed),
          ),
          GuiIconButton(
            icon: ArcaneIcon.arrowUp(size: IconSize.sm),
            label: 'Move action ${index + 1} up',
            disabled: index == 0,
            onPressed: () => onChanged(moveItem(actions, index, index - 1)),
          ),
          GuiIconButton(
            icon: ArcaneIcon.arrowDown(size: IconSize.sm),
            label: 'Move action ${index + 1} down',
            disabled: index == actions.length - 1,
            onPressed: () => onChanged(moveItem(actions, index, index + 1)),
          ),
          GuiIconButton(
            icon: ArcaneIcon.trash(size: IconSize.sm),
            label: 'Remove action ${index + 1}',
            onPressed: () => onChanged(<Object?>[...actions]..removeAt(index)),
          ),
        ]),
        if (!offered)
          dom.p(classes: 'animus-gui-refused', attributes: const <String, String>{'role': 'alert'}, <Widget>[
            dom.code(<Widget>[Component.text(childPath(actionPath, 'type'))]),
            Component.text(typeProblems.isNotEmpty ? ' ${typeProblems.first.message}' : " action '$type' is not available in Animus"),
            const Component.text('. Pick another action or remove it.'),
          ])
        else ...<Widget>[
          for (final GuiField field in actionFields[type] ?? const <GuiField>[]) _input(index, action, field, actionPath, targets),
          if (type == 'command')
            const dom.p(classes: 'animus-gui-note', <Widget>[
              Component.text('Needs this GUI on commandBehaviors or commandNamespaces under [behaviors.actions] in config.toml.'),
            ]),
          for (final String refused in refusedSequenceFields)
            if (type == 'sequence' && action.containsKey(refused))
              dom.p(classes: 'animus-gui-refused', attributes: const <String, String>{'role': 'alert'}, <Widget>[
                dom.code(<Widget>[Component.text(childPath(actionPath, refused))]),
                Component.text(" sequence field '$refused' is not available in Animus. "),
                Button.ghost(label: 'Remove $refused', size: ButtonSize.small, onPressed: () => _set(index, withActionField(action, refused, null))),
              ]),
          if (steps)
            GuiRow(
              label: 'At tick',
              forId: guiPathId('$actionPath.atTicks'),
              help: 'Ticks from sequence start',
              problems: diagnosticsAt(diagnostics, childPath(actionPath, 'atTicks')),
              child: GuiNumberInput(
                id: guiPathId('$actionPath.atTicks'),
                value: asInt(action['atTicks']),
                label: 'At tick',
                onChanged: (num? value) => _set(index, withActionField(action, 'atTicks', value)),
              ),
            ),
          GuiSection(
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
      ],
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
          return GuiRow(
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
        return GuiRow(
          label: label,
          forId: id,
          help: field.help,
          problems: problems,
          child: GuiTextInput(
            id: id,
            value: value is String ? value : (value == null ? '' : '$value'),
            mode: switch (field.kind) {
              GuiFieldKind.template => GuiTextMode.template,
              GuiFieldKind.expression => GuiTextMode.expression,
              _ => GuiTextMode.plain,
            },
            names: names,
            label: name,
            onChanged: set,
          ),
        );
      case GuiFieldKind.integer || GuiFieldKind.number:
        return GuiRow(
          label: label,
          forId: id,
          help: field.help,
          problems: problems,
          child: GuiNumberInput(id: id, value: value is num ? value : null, integer: field.kind == GuiFieldKind.integer, label: name, onChanged: set),
        );
      case GuiFieldKind.boolean:
        return GuiRow(label: name, help: field.help, problems: problems, child: GuiSwitch(value: value == true, label: name, onChanged: (bool on) => set(on ? true : null)));
      case GuiFieldKind.choice:
        return GuiRow(
          label: label,
          forId: id,
          help: field.help,
          problems: problems,
          child: GuiSelect(id: id, value: asString(value), options: field.choices, allowNone: !field.required, onChanged: set),
        );
      case GuiFieldKind.multiChoice:
        final List<String> chosen = stringList(value);
        return GuiRow(
          label: label,
          help: field.help,
          problems: problems,
          child: dom.div(classes: 'animus-row', <Widget>[
            for (final String choice in field.choices)
              GuiSwitch(
                value: chosen.contains(choice),
                label: choice,
                onChanged: (bool on) {
                  final List<String> next = <String>[for (final String one in field.choices) if (one == choice ? on : chosen.contains(one)) one];
                  set(next.isEmpty ? null : next);
                },
              ),
          ]),
        );
      case GuiFieldKind.actions:
        return GuiSection(
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
        return GuiSection(title: 'Branches', count: branches.length, tip: 'Action lists that start together and run side by side.', docs: Docs.guiActions, children: <Widget>[
          for (int branch = 0; branch < branches.length; branch++)
            GuiSection(title: 'Branch ${branch + 1}', tip: 'One list, run in order alongside the other branches.', docs: Docs.guiActions, children: <Widget>[
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
              Button.ghost(
                label: 'Remove branch ${branch + 1}',
                size: ButtonSize.small,
                disabled: branches.length <= 1,
                onPressed: () => set(<Object?>[...branches]..removeAt(branch)),
              ),
            ]),
          Button.ghost(label: 'Add branch', size: ButtonSize.small, onPressed: () => set(<Object?>[...branches, <Object?>[]])),
        ]);
      case GuiFieldKind.cases:
        final JsonMap cases = asMap(value);
        return GuiSection(title: 'Cases', count: cases.length, tip: 'The list whose value matches on runs. No match runs default.', docs: Docs.guiActions, children: <Widget>[
          for (final MapEntry<String, Object?> entry in cases.entries)
            dom.div(classes: 'animus-gui-case', <Widget>[
              GuiRow(
                label: 'When on is',
                child: dom.div(classes: 'animus-row', <Widget>[
                  GuiTextInput(
                    value: entry.key,
                    label: 'Case value',
                    onChanged: (String next) {
                      if (next.isEmpty || cases.containsKey(next)) return;
                      set(renameKey(cases, entry.key, next));
                    },
                  ),
                  GuiIconButton(
                    icon: ArcaneIcon.trash(size: IconSize.sm),
                    label: 'Remove case ${entry.key}',
                    onPressed: () => set(<String, Object?>{...cases}..remove(entry.key)),
                  ),
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
          Button.ghost(
            label: 'Add case',
            size: ButtonSize.small,
            onPressed: () {
              int n = cases.length + 1;
              while (cases.containsKey('case$n')) {
                n++;
              }
              set(<String, Object?>{...cases, 'case$n': <Object?>[]});
            },
          ),
        ]);
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
          return GuiRow(label: rowLabel, forId: labelled ? rowId : null, help: help, problems: diagnosticsAt(diagnostics, childPath(fieldPath, key), nested: key == 'lore'), child: child(rowId));
        }

        return GuiSection(title: name, tip: 'The item, its count, name and lore.', docs: Docs.guiActions, children: <Widget>[
          for (final Diagnostic problem in problems) dom.p(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
          row('item', 'Item *', (String rowId) => ItemPicker(id: rowId, value: asString(icon['item']) ?? '', label: 'Item id', placeholder: 'minecraft:bread', onChanged: (String v) => set(with_('item', v))),
              help: 'Item id or expression'),
          row('count', 'Count', (String rowId) => GuiNumberInput(id: rowId, value: asInt(icon['count']), label: 'Item count', onChanged: (num? v) => set(with_('count', v))),
              help: '1 to 99 per stack'),
          row('name', 'Name', (String rowId) => GuiTextInput(id: rowId, value: asString(icon['name']) ?? '', mode: GuiTextMode.template, names: names, label: 'Item name', onChanged: (String v) => set(with_('name', v)))),
          row('lore', 'Lore', (String _) => GuiLinesEditor(lines: stringList(icon['lore']), names: names, onChanged: (List<String> lines) => set(with_('lore', lines))), labelled: false),
          row('customModelValue', 'Custom model', (String rowId) => GuiNumberInput(id: rowId, value: asInt(icon['customModelValue']), label: 'Custom model value', onChanged: (num? v) => set(with_('customModelValue', v)))),
        ]);
      case GuiFieldKind.object:
        final JsonMap args = inventoryArgs(value);
        return GuiRow(
          label: name,
          help: field.help,
          problems: diagnosticsAt(diagnostics, fieldPath, nested: true),
          child: dom.div(classes: 'animus-gui-args', <Widget>[
            KeyValueEditor(
              value: args,
              label: name,
              addLabel: 'Add argument',
              onChanged: (JsonMap next) => set(next.isEmpty ? null : next),
              valueControl: (String key, Object? entry, void Function(Object? value) onEntry) => entry is String || entry == null
                  ? GuiTextInput(value: asString(entry) ?? '', mode: GuiTextMode.template, names: names, label: 'Argument $key', onChanged: onEntry)
                  : TypedValueEditor(value: entry, label: 'Argument $key', profileFor: (String _) => FieldProfile.guiText, onChanged: onEntry),
            ),
            for (final String key in args.keys)
              if (argumentNameProblem(key) case final String problem)
                dom.span(classes: 'animus-gui-problem is-error', attributes: const <String, String>{'role': 'note'}, <Widget>[
                  dom.code(<Widget>[Component.text(childPath(fieldPath, key))]),
                  Component.text(' $problem'),
                ]),
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
class _ActionTypePicker extends StatefulWidget {
  const _ActionTypePicker({required this.id, required this.action, required this.options, required this.labels, required this.onChanged});

  final String id;
  final JsonMap action;
  final List<String> options;
  final Map<String, String> labels;
  final void Function(JsonMap action) onChanged;

  @override
  State<_ActionTypePicker> createState() => _ActionTypePickerState();
}

class _ActionTypePickerState extends State<_ActionTypePicker> {
  /// The type asked about, the action it would write and the lists it drops.
  ({String type, JsonMap changed, List<String> dropped})? _pending;

  void _pick(String? next) {
    if (next == null || next == asString(component.action['type'])) return;
    final (JsonMap changed, List<String> dropped) = changeActionType(component.action, next);
    if (dropped.isEmpty) {
      component.onChanged(changed);
      return;
    }
    setState(() => _pending = (type: next, changed: changed, dropped: dropped));
  }

  void _answer(bool change) {
    final JsonMap? changed = _pending?.changed;
    setState(() => _pending = null);
    if (change && changed != null) component.onChanged(changed);
    focusSoon(component.id);
  }

  @override
  Widget build(BuildContext context) {
    final ({String type, JsonMap changed, List<String> dropped})? pending = _pending;
    if (pending != null) {
      return ConfirmRow(
        question: 'Change to ${component.labels[pending.type] ?? pending.type}?',
        detail: 'It has no ${pending.dropped.join(', ')}, so the actions in them are dropped.',
        confirmLabel: 'Change type',
        onConfirm: () => _answer(true),
        onCancel: () => _answer(false),
      );
    }
    return GuiSelect(
      id: component.id,
      value: asString(component.action['type']) ?? '',
      options: component.options,
      labels: component.labels,
      onChanged: _pick,
    );
  }
}
