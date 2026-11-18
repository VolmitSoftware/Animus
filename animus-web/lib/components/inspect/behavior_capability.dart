/// The capability part of action and condition nodes: what it does (or
/// checks), picked from the catalog (NPC-only ones hidden in a server
/// behavior), each argument as a value or filled from a variable,
/// expression or template, the other placeholder slots keyed by JSON
/// Pointer, and a call's arguments.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/action_groups.dart';
import '../../logic/inspect/behavior_edit.dart';
import '../../logic/inspect/behavior_kept.dart';
import '../../logic/inspect/typed_values.dart';
import '../../logic/inspect/behavior_operands.dart';
import '../../logic/inspect/behavior_words.dart';
import '../../logic/inspect/command_scope.dart';
import '../../logic/inspect/completion_catalog.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/suggest/field_profile.dart';
import '../../logic/suggest/field_profiles.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart';
import '../pending_field.dart';
import 'behavior_inputs.dart';
import 'behavior_nodes.dart';
import 'behavior_operand.dart';
import 'inspect_fields.dart';
import 'typed_values_editor.dart' show KeyValueEditor;
import '../../model/document_kind.dart';
import '../../ui/action_row.dart';
import '../../ui/info_tip.dart';
import '../../ui/menu.dart';
import '../select/suggest_input.dart';
import '../../logic/suggest/person_names.dart';
import '../select/animus_select.dart';
import '../select/person_picker.dart';
import '../../ui/field.dart';
import '../../ui/stack.dart';
import '../../ui/problem_text.dart';

/// Arguments that name a player or an NPC by UUID.
const Map<String, PersonKind> _personArguments = <String, PersonKind>{'player': PersonKind.player, 'npc': PersonKind.npc};

class CapabilityFields extends StatelessWidget {
  const CapabilityFields({required this.context, required this.node, required this.steps, required this.kind, super.key});

  final BehaviorContext context;
  final JsonMap node;
  final List<JsonStep> steps;

  /// `action` or `condition`.
  final String kind;

  void _put(JsonMap next) => context.write(steps, next);

  String _at(List<JsonStep> rest) => formatPath(<JsonStep>[...steps, ...rest]);

  String get _blockId => 'animus-capability:${_at(const <JsonStep>[])}';

  /// Picker values that turn the step into another type of step (Flow).
  static const String _flowPrefix = 'step:';

  @override
  Widget build(BuildContext build) {
    final String? id = asString(node['capability']);
    final CapabilitySpec? spec = context.catalog.capability(id);
    final List<CapabilitySpec> choices = <CapabilitySpec>[...context.catalog.capabilitiesFor(kind, server: context.server)]
      ..sort((CapabilitySpec a, CapabilitySpec b) {
        final int group = actionGroupRank(actionGroup(a.id)).compareTo(actionGroupRank(actionGroup(b.id)));
        return group != 0 ? group : actionLabel(a.id).compareTo(actionLabel(b.id));
      });
    final JsonMap arguments = asMap(node['arguments']);
    final Set<String> declared = argumentKnownKeys(spec);
    final Set<String> topPointers = <String>{for (final String key in declared) argumentPointer(key)};
    final bool action = kind == 'action';
    return dom.div(id: _blockId, classes: 'animus-capability', <Widget>[
      Field(
        label: action ? 'What it does' : 'What it checks',
        tip: spec?.description.isNotEmpty == true
            ? '${spec!.description} Written as the capability ${spec.id}.'
            : 'A capability: built in, or one a module adds, written namespace:name such as animus:say.',
        help: spec == null
            ? (id == null || id.isEmpty ? null : 'From a module. The server checks it when you apply.')
            : <String>[
                if (!spec.available && spec.reason != offlineCatalogReason) 'Not available on this server.',
                if (spec.privileged) 'Allowed behaviors only.',
                if (stepNeedsCommands(node, privilegedCapabilities(context.catalog)) != null) 'Applying it needs the Run commands permission.',
                if (context.server && spec.npcOnly) 'Needs an NPC: a server behavior cannot run it.',
              ].join(' '),
        problems: diagnosticsAt(context.diagnostics, _at(<JsonStep>['capability'])),
        child: stack(<Widget>[
          AnimusSelect.values(
            value: id,
            values: <String>[
              for (final CapabilitySpec choice in choices) choice.id,
              if (action)
                for (final String type in flowTypes)
                  if (context.nodeTypes.contains(type)) '$_flowPrefix$type',
            ],
            labels: <String, String>{
              for (final CapabilitySpec choice in choices) choice.id: capabilityChoiceLabel(choice),
              for (final String type in flowTypes) '$_flowPrefix$type': nodeLabel(type),
            },
            groups: <String, String>{
              for (final CapabilitySpec choice in choices) choice.id: actionGroup(choice.id),
              for (final String type in flowTypes) '$_flowPrefix$type': 'Flow',
            },
            keywords: <String, List<String>>{
              for (final CapabilitySpec choice in choices) choice.id: actionKeywords(choice.id, choice.description),
              for (final String type in flowTypes) '$_flowPrefix$type': flowKeywords(type),
            },
            noneLabel: action ? 'Pick what it does' : 'Pick what it checks',
            hiddenLabel: action ? 'What this step does' : 'What this step checks',
            onChanged: (String? next) {
              if (next == null) return;
              if (next.startsWith(_flowPrefix)) {
                _put(changeNodeType(node, next.substring(_flowPrefix.length), context.catalog, routine: context.entry, variables: context.variables, server: context.server));
                return;
              }
              // The new required arguments start empty: they are pending,
              // with focus on the first, until the user leaves the step.
              final FieldSpec? first = context.catalog.capability(next)?.arguments.where((FieldSpec argument) => argument.required).firstOrNull;
              if (first != null) holdField(_blockId, focus: argumentElementId(_at(<JsonStep>['arguments', first.key])));
              _put(changeCapability(node, next, context.catalog));
            },
          ),
          if (spec == null)
            SuggestInput(
              value: id ?? '',
              label: 'Module capability id',
              placeholder: 'namespace:name',
              profile: FieldProfile(values: <ValueRef>[ValueRef.capabilities(kind)]),
              onChanged: (String next) => context.write(<JsonStep>[...steps, 'capability'], next),
            ),
        ]),
      ),
      if (spec != null && spec.oneOfRequired.isNotEmpty) noteLine('Give one of: ${spec.oneOfRequired.map((List<String> group) => group.map(argumentLabel).join(' + ')).join(', or ')}.'),
      if (spec != null)
        for (final FieldSpec argument in spec.arguments)
          ArgumentField(
            key: ValueKey<String>(_at(<JsonStep>['arguments', argument.key])),
            node: node,
            argument: argument,
            path: _at(<JsonStep>['arguments', argument.key]),
            variables: context.variables,
            scope: context.scopeAt(steps),
            problems: <Diagnostic>[
              ...diagnosticsAt(context.diagnostics, _at(<JsonStep>['arguments', argument.key]), nested: true),
              for (final String field in slotFields) ...diagnosticsAt(context.diagnostics, '${_at(<JsonStep>[field])}[${argumentPointer(argument.key)}]', nested: true),
            ],
            onNode: _put,
            bounds: !(argument.kind == FieldKind.string && _personArguments.containsKey(argument.key)),
            literal: _literal(id, argument, arguments),
            textual: _textual(id, argument),
          ),
      if (unknownEntries(arguments, declared) case final JsonMap other when (spec == null && (id ?? '').isNotEmpty) || other.isNotEmpty)
        Field(
          key: const ValueKey<String>('other-arguments'),
          label: spec == null ? 'Arguments' : 'Other arguments',
          tip: spec == null ? 'Arguments of a module capability, checked on install.' : 'Arguments this capability does not declare. They are kept as written.',
          problems: <Diagnostic>[
            for (final String key in other.keys) ...diagnosticsAt(context.diagnostics, _at(<JsonStep>['arguments', key]), nested: true),
          ],
          child: KeyValueEditor(
            value: other,
            label: spec == null ? 'Arguments' : 'Other arguments',
            addLabel: 'Add argument',
            profileFor: (String key) {
              final FieldProfile known = argumentProfile(id, key);
              return known.isNone ? FieldProfile.behaviorTemplate : known;
            },
            onChanged: (JsonMap next) => context.write(<JsonStep>[...steps, 'arguments'], withUnknownEntries(arguments, declared, next)),
          ),
        ),
      _otherSlots(topPointers, declared),
    ]);
  }

  /// Whether [argument] is text players read or a command, where `{{ }}`
  /// holes make it a template as it is typed.
  static bool _textual(String? id, FieldSpec argument) => argument.kind == FieldKind.string && argumentProfile(id, argument.key).holes == ExpressionSyntax.behavior;

  /// The control for an argument written as a value. An argument of any
  /// type holding text, a number or true or false is edited as that, never
  /// as JSON with quotes. Text with holes is shown as typed and stored as a
  /// template.
  Widget _literal(String? id, FieldSpec argument, JsonMap arguments) {
    final bool textual = _textual(id, argument);
    final bool template = textual && argumentSource(node, argument.key) == ArgumentSource.template;
    final Object? value = template ? argumentSlotText(node, argument.key) : arguments[argument.key];
    void write(Object? next) => textual
        ? _put(setArgumentText(node, argument.key, next as String?))
        : context.write(<JsonStep>[...steps, 'arguments', argument.key], next);
    if (argument.kind == FieldKind.any && (value == null || value is String || value is num || value is bool)) {
      return JsonValueEditor(value: value ?? '', label: humanizeKey(argument.key), onChanged: write);
    }
    // A player or an NPC another than the triggering one: picked by name, written as its UUID.
    final PersonKind? person = argument.kind == FieldKind.string && (value == null || value is String) ? _personArguments[argument.key] : null;
    if (person != null) {
      return PersonPicker(
        kind: person,
        label: humanizeKey(argument.key),
        value: value as String?,
        onChanged: write,
      );
    }
    return InspectControl(
      field: argument,
      value: value,
      references: argumentReference(id, argument.key, arguments),
      path: _at(<JsonStep>['arguments', argument.key]),
      profile: argumentProfile(id, argument.key),
      onChanged: write,
    );
  }

  /// Slots whose pointer is not a top-level argument this editor shows:
  /// nested pointers, and every slot of a module capability.
  Widget _otherSlots(Set<String> topPointers, Set<String> declared) {
    final List<(String, String, Object?)> others = <(String, String, Object?)>[
      for (final String field in slotFields)
        for (final MapEntry<String, Object?> entry in asMap(node[field]).entries)
          if (!topPointers.contains(entry.key)) (field, entry.key, entry.value),
    ];
    final List<FieldOption> pointers = <FieldOption>[
      for (final String key in <String>{...declared, ...asMap(node['arguments']).keys}) FieldOption(argumentPointer(key), description: 'argument $key'),
    ];
    void putSlot(String field, String pointer, Object? value, {String? rename}) {
      final JsonMap slots = rename == null ? <String, Object?>{...asMap(node[field])} : renameKey(asMap(node[field]), pointer, rename);
      final String key = rename ?? pointer;
      if (value == null) {
        slots.remove(key);
      } else {
        slots[key] = value;
      }
      final JsonMap out = <String, Object?>{...node, field: slots};
      if (slots.isEmpty) out.remove(field);
      _put(out);
    }

    return dom.div(<Widget>[
      if (others.isNotEmpty) const dom.h5(<Widget>[TipLabel(label: Component.text('Other placeholders'), tip: 'Values filled when the step runs, keyed by JSON Pointer into its arguments.')]),
      for (final (String field, String pointer, Object? value) in others)
        Field(
          key: ValueKey<String>('slot:$field:$pointer'),
          label: '${humanizeKey(field)} $pointer',
          problems: <Diagnostic>[
            ...diagnosticsAt(context.diagnostics, '${_at(<JsonStep>[field])}[$pointer]', nested: true),
          ],
          child: stack(<Widget>[
            SuggestInput(
              value: pointer,
              label: 'Pointer of this placeholder',
              suggestions: pointers,
              onChanged: (String next) {
                if (next.startsWith('/') && next != pointer) putSlot(field, pointer, value, rename: next);
              },
            ),
            if (field == 'bindings')
              AnimusSelect.values(
                value: asString(asMap(value)['variable']),
                values: context.variables.keys.toList(),
                noneLabel: 'Pick a variable',
                label: 'Variable for $pointer',
                onChanged: (String? next) => putSlot(field, pointer, <String, Object?>{'variable': next ?? ''}),
              )
            else
              BehaviorExpressionInput(
                value: asString(value) ?? '',
                scope: context.scopeAt(steps),
                template: field == 'templates',
                label: '${field == 'templates' ? 'Template' : 'Expression'} for $pointer',
                onChanged: (String next) => putSlot(field, pointer, next),
              ),
            ActionRow(dense: true, secondary: <RowAction>[RowAction('Remove', () => putSlot(field, pointer, null))]),
          ]),
        ),
    ]);
  }
}

/// A call's `arguments`: declared variables set before the routine runs,
/// each from a literal or an operand of the variable's type.
class CallArguments extends StatelessWidget {
  const CallArguments({required this.context, required this.node, required this.steps, super.key});

  final BehaviorContext context;
  final JsonMap node;
  final List<JsonStep> steps;

  @override
  Widget build(BuildContext build) {
    final JsonMap arguments = asMap(node['arguments']);
    final List<String> free = <String>[
      for (final String name in context.variables.keys)
        if (!arguments.containsKey(name)) name,
    ];
    final List<JsonStep> at = <JsonStep>[...steps, 'arguments'];
    void put(JsonMap next) => context.write(at, next.isEmpty ? null : next);
    return Field(
      label: 'Arguments',
      tip: 'Variables set before the part runs.',
      problems: diagnosticsAt(context.diagnostics, formatPath(at)),
      child: stack(<Widget>[
        for (final MapEntry<String, Object?> entry in arguments.entries)
          stack(key: ValueKey<String>('call-argument:${entry.key}'), spacing: 4, <Widget>[
            Row(
              gapSize: Gap.sm,
              children: <Widget>[
                dom.code(<Widget>[Component.text(entry.key)]),
                Expanded(
                  child: OperandControl(
                    value: entry.value,
                    type: asString(asMap(context.variables[entry.key])['type']) ?? 'any',
                    variables: context.variables,
                    scope: context.scopeAt(steps),
                    label: 'Argument ${entry.key}',
                    onChanged: (Object? next) => put(<String, Object?>{...arguments, entry.key: next}),
                  ),
                ),
                AppMenu(
                  icon: ArcaneIcon.ellipsis(),
                  alignEnd: true,
                  entries: <AppMenuEntry>[
                    AppMenuEntry('Remove ${entry.key}', () => put(<String, Object?>{...arguments}..remove(entry.key)), destructive: true),
                  ],
                ),
              ],
            ),
            for (final Diagnostic problem in diagnosticsAt(context.diagnostics, formatPath(<JsonStep>[...at, entry.key]), nested: true))
              problemLine(problem.severity, problem.message, classes: 'animus-node-problem'),
          ]),
        if (free.isNotEmpty)
          ActionRow(
            dense: true,
            leading: <Widget>[
              AppMenu(
                label: 'Add argument',
                icon: ArcaneIcon.plus(),
                outline: true,
                entries: <AppMenuEntry>[
                  for (final String name in free) AppMenuEntry(name, () => put(<String, Object?>{...arguments, name: literalFor(asString(asMap(context.variables[name])['type']))})),
                ],
              ),
            ],
          )
        else if (context.variables.isEmpty)
          noteLine('Add a variable first.'),
      ]),
    );
  }
}

/// The document kind a capability argument names: the GUI of
/// `animus:open-gui` (a chest GUI unless its `kind` says otherwise) and the
/// shop of `animus:open-shop`.
DocumentKind? argumentReference(String? capability, String key, JsonMap arguments) => switch ((capability, key)) {
  ('animus:open-gui', 'gui') => switch (asString(arguments['kind']) ?? 'inventory') {
    'inventory' => DocumentKind.inventory,
    'menu' => DocumentKind.menu,
    'shop' => DocumentKind.shop,
    _ => null,
  },
  ('animus:open-shop', 'shop') => DocumentKind.shop,
  _ => null,
};
