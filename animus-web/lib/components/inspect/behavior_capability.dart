/// The capability part of action and condition nodes: the capability
/// picker (NPC-only ones hidden in a server behavior), each argument as
/// written or filled from a variable, expression or template, the other
/// placeholder slots keyed by JSON Pointer, and a call's arguments.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/behavior_edit.dart';
import '../../logic/inspect/behavior_kept.dart';
import '../../logic/inspect/typed_values.dart';
import '../../logic/inspect/behavior_operands.dart';
import '../../logic/inspect/completion_catalog.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/suggest/field_profile.dart';
import '../../logic/suggest/field_profiles.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart';
import 'behavior_inputs.dart';
import 'behavior_nodes.dart';
import 'behavior_operand.dart';
import 'inspect_fields.dart';
import 'typed_values_editor.dart' show KeyValueEditor;
import '../select/suggest_input.dart';
import '../../model/document_kind.dart';

class CapabilityFields extends StatelessWidget {
  const CapabilityFields({required this.context, required this.node, required this.steps, required this.kind, super.key});

  final BehaviorContext context;
  final JsonMap node;
  final List<JsonStep> steps;

  /// `action` or `condition`.
  final String kind;

  void _put(JsonMap next) => context.write(steps, next);

  String _at(List<JsonStep> rest) => formatPath(<JsonStep>[...steps, ...rest]);

  @override
  Widget build(BuildContext build) {
    final String? id = asString(node['capability']);
    final CapabilitySpec? spec = context.catalog.capability(id);
    final List<CapabilitySpec> choices = context.catalog.capabilitiesFor(kind, server: context.server);
    final JsonMap arguments = asMap(node['arguments']);
    final Set<String> declared = argumentKnownKeys(spec);
    final Set<String> topPointers = <String>{for (final String key in declared) argumentPointer(key)};
    return dom.div(classes: 'animus-capability', <Widget>[
      GuiRow(
        label: 'Capability *',
        tip: spec == null ? 'What this step does: a built-in capability or one a module adds, written namespace:name such as animus:say.' : null,
        help: spec == null
            ? (id == null || id.isEmpty ? null : 'Module capability, checked on install.')
            : <String>[
                if (!spec.available) 'Unavailable here: ${spec.reason}.',
                if (spec.privileged) 'Privileged: allow-listed behaviors only.',
                if (context.server && spec.npcOnly) 'NPC only: a server behavior cannot run it.',
                spec.description,
              ].join(' '),
        problems: diagnosticsAt(context.diagnostics, _at(<JsonStep>['capability'])),
        child: dom.div(classes: 'animus-control-stack', <Widget>[
          GuiSelect(
            value: id,
            options: <String>[for (final CapabilitySpec choice in choices) choice.id],
            labels: <String, String>{
              for (final CapabilitySpec choice in choices) choice.id: choice.note.isEmpty ? choice.id : '${choice.id} (${choice.available ? choice.note : 'unavailable offline'})',
            },
            noneLabel: 'Pick a capability',
            label: 'Capability of this $kind',
            onChanged: (String? next) {
              if (next != null) _put(changeCapability(node, next, context.catalog));
            },
          ),
          if (spec == null)
            SuggestInput(
              value: id ?? '',
              label: 'Capability id',
              placeholder: 'namespace:name',
              profile: FieldProfile(values: <ValueRef>[ValueRef.capabilities(kind)]),
              onChanged: (String next) => context.write(<JsonStep>[...steps, 'capability'], next)),
        ]),
      ),
      if (spec != null && spec.oneOfRequired.isNotEmpty)
        dom.p(classes: 'animus-gui-help', <Widget>[
          Component.text('Give one of: ${spec.oneOfRequired.map((List<String> group) => group.join(' + ')).join(', or ')}.'),
        ]),
      if (spec != null)
        for (final FieldSpec argument in spec.arguments)
          ArgumentField(
            key: ValueKey<String>(_at(<JsonStep>['arguments', argument.key])),
            node: node,
            argument: argument,
            path: _at(<JsonStep>['arguments', argument.key]),
            variables: context.variables,
            scope: context.scope,
            problems: <Diagnostic>[
              ...diagnosticsAt(context.diagnostics, _at(<JsonStep>['arguments', argument.key]), nested: true),
              for (final String field in slotFields) ...diagnosticsAt(context.diagnostics, '${_at(<JsonStep>[field])}[${argumentPointer(argument.key)}]', nested: true),
            ],
            onNode: _put,
            literal: InspectControl(
              field: argument,
              value: arguments[argument.key],
              references: argumentReference(id, argument.key, arguments),
              path: _at(<JsonStep>['arguments', argument.key]),
              profile: argumentProfile(id, argument.key),
              onChanged: (Object? next) => context.write(<JsonStep>[...steps, 'arguments', argument.key], next),
            ),
          ),
      if (unknownEntries(arguments, declared) case final JsonMap other when (spec == null && (id ?? '').isNotEmpty) || other.isNotEmpty)
        GuiRow(
          key: const ValueKey<String>('other-arguments'),
          label: spec == null ? 'Arguments' : 'Other arguments',
          tip: spec == null ? 'Arguments of a module capability, checked on install.' : 'Arguments this capability does not declare. They are kept as written.',
          problems: <Diagnostic>[for (final String key in other.keys) ...diagnosticsAt(context.diagnostics, _at(<JsonStep>['arguments', key]), nested: true)],
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

  /// Slots whose pointer is not a top-level argument this editor shows:
  /// nested pointers, and every slot of a module capability.
  Widget _otherSlots(Set<String> topPointers, Set<String> declared) {
    final List<(String, String, Object?)> others = <(String, String, Object?)>[
      for (final String field in slotFields)
        for (final MapEntry<String, Object?> entry in asMap(node[field]).entries)
          if (!topPointers.contains(entry.key)) (field, entry.key, entry.value),
    ];
    final List<String> stray = strayPointers(node);
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

    return dom.div(classes: 'animus-slots', <Widget>[
      if (others.isNotEmpty) const dom.h5(<Widget>[Component.text('Other placeholders')]),
      for (final (String field, String pointer, Object? value) in others)
        GuiRow(
          key: ValueKey<String>('slot:$field:$pointer'),
          label: '${humanizeKey(field)} $pointer',
          problems: <Diagnostic>[
            ...diagnosticsAt(context.diagnostics, '${_at(<JsonStep>[field])}[$pointer]', nested: true),
            if (stray.contains(pointer))
              Diagnostic(DiagnosticSeverity.warning, _at(<JsonStep>[field]), 'No null argument at $pointer. Set it to null or remove this slot.'),
          ],
          child: dom.div(classes: 'animus-control-stack', <Widget>[
            SuggestInput(value: pointer, label: 'Pointer of this placeholder', suggestions: pointers, onChanged: (String next) {
              if (next.startsWith('/') && next != pointer) putSlot(field, pointer, value, rename: next);
            }),
            if (field == 'bindings')
              GuiSelect(
                value: asString(asMap(value)['variable']),
                options: context.variables.keys.toList(),
                noneLabel: 'Pick a variable',
                label: 'Variable for $pointer',
                onChanged: (String? next) => putSlot(field, pointer, <String, Object?>{'variable': next ?? ''}),
              )
            else
              BehaviorExpressionInput(
                value: asString(value) ?? '',
                scope: context.scope,
                template: field == 'templates',
                label: '${field == 'templates' ? 'Template' : 'Expression'} for $pointer',
                onChanged: (String next) => putSlot(field, pointer, next),
              ),
            Button.ghost(label: 'Remove placeholder', size: ButtonSize.small, onPressed: () => putSlot(field, pointer, null)),
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
    final List<String> free = <String>[for (final String name in context.variables.keys) if (!arguments.containsKey(name)) name];
    final List<JsonStep> at = <JsonStep>[...steps, 'arguments'];
    void put(JsonMap next) => context.write(at, next.isEmpty ? null : next);
    return GuiRow(
      label: 'Arguments',
      help: 'Variables set before the call',
      problems: diagnosticsAt(context.diagnostics, formatPath(at)),
      child: dom.div(classes: 'animus-control-stack', <Widget>[
        for (final MapEntry<String, Object?> entry in arguments.entries)
          dom.div(key: ValueKey<String>('call-argument:${entry.key}'), classes: 'animus-value-row', <Widget>[
            dom.code(classes: 'animus-value-key', <Widget>[Component.text(entry.key)]),
            OperandControl(
              value: entry.value,
              type: asString(asMap(context.variables[entry.key])['type']) ?? 'any',
              variables: context.variables,
              scope: context.scope,
              label: 'Argument ${entry.key}',
              onChanged: (Object? next) => put(<String, Object?>{...arguments, entry.key: next}),
            ),
            GuiIconButton(icon: ArcaneIcon.x(size: IconSize.sm), label: 'Remove argument ${entry.key}', onPressed: () => put(<String, Object?>{...arguments}..remove(entry.key))),
            for (final Diagnostic problem in diagnosticsAt(context.diagnostics, formatPath(<JsonStep>[...at, entry.key]), nested: true))
              dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
          ]),
        if (free.isNotEmpty)
          GuiSelect(
            value: null,
            options: free,
            noneLabel: 'Add an argument',
            label: 'Add an argument',
            onChanged: (String? name) {
              if (name != null) put(<String, Object?>{...arguments, name: literalFor(asString(asMap(context.variables[name])['type']))});
            },
          )
        else if (context.variables.isEmpty)
          const dom.p(classes: 'animus-gui-note', <Widget>[Component.text('Declare a variable first.')]),
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
