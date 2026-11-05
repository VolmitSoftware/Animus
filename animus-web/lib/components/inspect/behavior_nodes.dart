/// One behavior node and its children as a card tree: type picker, moves,
/// removal, the capability picker with typed arguments for actions and
/// conditions, and fields for every other node type.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/behavior_edit.dart';
import '../../logic/inspect/completion_catalog.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart';
import 'inspect_fields.dart';

/// What every node card needs: the catalog, the document and a writer.
class BehaviorContext {
  const BehaviorContext({required this.catalog, required this.schema, required this.document, required this.diagnostics, required this.write});

  final CompletionCatalog catalog;
  final SchemaView schema;
  final JsonMap document;
  final List<Diagnostic> diagnostics;

  /// Writes [value] at [steps] of the document; null removes it.
  final void Function(List<JsonStep> steps, Object? value) write;

  List<String> get routines => asMap(document['routines']).keys.toList();
  JsonMap get variables => asMap(document['variables']);
  List<String> get nodeTypes => catalog.nodes.keys.toList();

  String? get entry => asString(document['entry']);

  JsonMap newNode(String type) => defaultNode(type, catalog, routine: entry ?? routines.firstOrNull, variables: variables);
}

/// A picker that adds a node of the chosen type.
class AddNodeSelect extends StatelessWidget {
  const AddNodeSelect({required this.context, required this.label, required this.onAdd, super.key});

  final BehaviorContext context;
  final String label;
  final void Function(JsonMap node) onAdd;

  @override
  Widget build(BuildContext build) => GuiSelect(
        value: null,
        options: context.nodeTypes,
        noneLabel: label,
        onChanged: (String? type) {
          if (type != null) onAdd(context.newNode(type));
        },
      );
}

class BehaviorNodeView extends StatelessWidget {
  const BehaviorNodeView({
    required this.context,
    required this.node,
    required this.steps,
    this.onRemove,
    this.onUp,
    this.onDown,
    this.caption,
    super.key,
  });

  final BehaviorContext context;
  final Object? node;
  final List<JsonStep> steps;
  final void Function()? onRemove;
  final void Function()? onUp;
  final void Function()? onDown;

  /// Where the node sits: `then`, `step 2`, ...
  final String? caption;

  String get _path => formatPath(steps);

  void _put(JsonMap next) => context.write(steps, next);

  @override
  Widget build(BuildContext build) {
    final JsonMap value = asMap(node);
    final String? type = asString(value['type']);
    final NodeSpec? spec = context.catalog.nodes[type];
    final List<Diagnostic> own = diagnosticsAt(context.diagnostics, _path);
    final bool failing = diagnosticsAt(context.diagnostics, _path, nested: true).any((Diagnostic d) => d.severity == DiagnosticSeverity.error);
    final List<String> kept = unknownKeys(value, spec?.fields.map((FieldSpec field) => field.key) ?? const <String>[]);
    return dom.div(
      classes: 'animus-node${failing ? ' has-error' : ''}',
      attributes: <String, String>{'data-node-path': _path, 'role': 'group', 'aria-label': '${caption ?? 'node'} ${type ?? ''}'.trim()},
      <Widget>[
        dom.div(classes: 'animus-node-head', <Widget>[
          if (caption != null) dom.span(classes: 'animus-node-caption', <Widget>[Component.text(caption!)]),
          GuiSelect(
            value: type,
            options: context.nodeTypes,
            noneLabel: 'choose a type',
            onChanged: (String? next) {
              if (next != null) {
                _put(changeNodeType(value, next, context.catalog, routine: context.entry, variables: context.variables));
              }
            },
          ),
          dom.span(classes: 'animus-node-help', <Widget>[Component.text(spec?.description ?? (type == null ? 'A node needs a type.' : 'Unknown node type; kept as written.'))]),
          if (onUp != null) GuiIconButton(icon: ArcaneIcon.arrowUp(size: IconSize.sm), label: 'Move ${caption ?? 'node'} up', onPressed: onUp!),
          if (onDown != null) GuiIconButton(icon: ArcaneIcon.arrowDown(size: IconSize.sm), label: 'Move ${caption ?? 'node'} down', onPressed: onDown!),
          if (onRemove != null) GuiIconButton(icon: ArcaneIcon.x(size: IconSize.sm), label: 'Remove ${caption ?? 'node'}', onPressed: onRemove!),
        ]),
        for (final Diagnostic problem in own)
          dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', attributes: const <String, String>{'role': 'note'}, <Widget>[Component.text(problem.message)]),
        dom.div(classes: 'animus-node-body', <Widget>[
          if (type == 'action' || type == 'condition') ..._capability(value, type!),
          if (spec != null)
            for (final FieldSpec field in spec.fields) ..._field(value, field),
          if (kept.isNotEmpty)
            dom.p(classes: 'animus-gui-note', <Widget>[Component.text('Kept as written: ${kept.join(', ')}')]),
        ]),
      ],
    );
  }

  /// Fields the node card lays out by kind; capability ones come first.
  List<Widget> _field(JsonMap value, FieldSpec field) {
    final String kind = fieldType(field);
    final List<JsonStep> at = <JsonStep>[...steps, field.key];
    if (const <String>{'capability', 'arguments', 'bindings'}.contains(field.key) && (value['type'] == 'action' || value['type'] == 'condition')) {
      return const <Widget>[];
    }
    if (kind == nodeListType) return <Widget>[_list(value, field)];
    if (kind == nodeType) {
      final Object? child = value[field.key];
      if (child == null) {
        return <Widget>[
          GuiRow(
            label: fieldLabel(field),
            help: field.help,
            problems: diagnosticsAt(context.diagnostics, formatPath(at)),
            child: AddNodeSelect(context: context, label: 'Add ${field.key}', onAdd: (JsonMap node) => context.write(at, node)),
          ),
        ];
      }
      return <Widget>[
        dom.div(classes: 'animus-node-slot', <Widget>[
          BehaviorNodeView(
            key: ValueKey<String>(formatPath(at)),
            context: context,
            node: child,
            steps: at,
            caption: field.key,
            onRemove: field.required ? null : () => context.write(at, null),
          ),
        ]),
      ];
    }
    if (field.key == 'routine') {
      return <Widget>[
        GuiRow(
          label: fieldLabel(field),
          help: field.help,
          problems: <Diagnostic>[
            ...diagnosticsAt(context.diagnostics, formatPath(at)),
            if (value[field.key] is String && !context.routines.contains(value[field.key]))
              Diagnostic(DiagnosticSeverity.warning, formatPath(at), 'No routine ${value[field.key]} in this document.'),
          ],
          child: GuiSelect(
            value: asString(value[field.key]),
            options: context.routines,
            allowNone: !field.required,
            onChanged: (String? next) => context.write(at, next),
          ),
        ),
      ];
    }
    if (field.key == 'variable' && value['type'] == 'set') return <Widget>[_setVariable(value, field)];
    if (field.key == 'value' && value['type'] == 'set') return <Widget>[_setValue(value, field)];
    return <Widget>[
      InspectField(
        key: ValueKey<String>(formatPath(at)),
        field: field,
        value: value[field.key],
        path: formatPath(at),
        diagnostics: context.diagnostics,
        onChanged: (Object? next) => context.write(at, next),
      ),
    ];
  }

  Widget _list(JsonMap value, FieldSpec field) {
    final List<JsonStep> at = <JsonStep>[...steps, field.key];
    final JsonList items = asList(value[field.key]);
    final String noun = field.key == 'branches' ? 'branch' : 'step';
    return dom.div(classes: 'animus-node-list', <Widget>[
      for (int index = 0; index < items.length; index++)
        BehaviorNodeView(
          key: ValueKey<String>(formatPath(<JsonStep>[...at, index])),
          context: context,
          node: items[index],
          steps: <JsonStep>[...at, index],
          caption: '$noun ${index + 1}',
          onUp: index == 0 ? null : () => context.write(at, moveItem(items, index, index - 1)),
          onDown: index == items.length - 1 ? null : () => context.write(at, moveItem(items, index, index + 1)),
          onRemove: () => context.write(at, removeItem(items, index)),
        ),
      for (final Diagnostic problem in diagnosticsAt(context.diagnostics, formatPath(at)))
        dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
      dom.div(classes: 'animus-row', <Widget>[
        AddNodeSelect(context: context, label: 'Add $noun', onAdd: (JsonMap node) => context.write(at, <Object?>[...items, node])),
      ]),
    ]);
  }

  List<Widget> _capability(JsonMap value, String type) {
    final String? id = asString(value['capability']);
    final CapabilitySpec? spec = context.catalog.capability(id);
    final List<CapabilitySpec> choices = context.catalog.capabilitiesFor(type);
    final List<JsonStep> argumentsAt = <JsonStep>[...steps, 'arguments'];
    final JsonMap arguments = asMap(value['arguments']);
    final Set<String> declared = <String>{for (final FieldSpec field in spec?.arguments ?? const <FieldSpec>[]) field.key};
    final String capabilityPath = formatPath(<JsonStep>[...steps, 'capability']);
    return <Widget>[
      GuiRow(
        label: 'capability *',
        help: spec == null
            ? (id == null || id.isEmpty ? 'Choose what this $type runs.' : 'Not a built-in capability; a module checks it when installed.')
            : <String>[spec.description, if (!spec.available) 'Unavailable here: ${spec.reason}.', if (spec.privileged) 'Privileged: allowed only for behaviors on the allow-list.'].join(' '),
        problems: diagnosticsAt(context.diagnostics, capabilityPath),
        child: dom.div(classes: 'animus-capability', <Widget>[
          GuiSelect(
            value: id,
            options: <String>[for (final CapabilitySpec choice in choices) choice.id],
            labels: <String, String>{
              for (final CapabilitySpec choice in choices) choice.id: choice.note.isEmpty ? choice.id : '${choice.id} (${choice.available ? choice.note : 'unavailable'})',
            },
            noneLabel: 'choose',
            onChanged: (String? next) {
              if (next != null) _put(changeCapability(value, next, context.catalog));
            },
          ),
          if (spec == null)
            SuggestInput(
              value: id ?? '',
              label: 'Module capability',
              onChanged: (String next) => context.write(<JsonStep>[...steps, 'capability'], next),
            ),
        ]),
      ),
      if (spec != null && spec.oneOfRequired.isNotEmpty)
        dom.p(classes: 'animus-gui-help', <Widget>[
          Component.text('Give one of: ${spec.oneOfRequired.map((List<String> group) => group.join(' + ')).join(', or ')}.'),
        ]),
      if (spec != null)
        for (final FieldSpec argument in spec.arguments)
          InspectField(
            key: ValueKey<String>(formatPath(<JsonStep>[...argumentsAt, argument.key])),
            field: argument,
            value: arguments[argument.key],
            path: formatPath(<JsonStep>[...argumentsAt, argument.key]),
            diagnostics: context.diagnostics,
            onChanged: (Object? next) => context.write(<JsonStep>[...argumentsAt, argument.key], next),
          ),
      for (final MapEntry<String, Object?> entry in arguments.entries)
        if (!declared.contains(entry.key))
          GuiRow(
            key: ValueKey<String>('kept-argument:${entry.key}'),
            label: entry.key,
            help: spec == null ? 'Argument for the module.' : 'Not an argument of ${spec.id}; kept as written.',
            problems: diagnosticsAt(context.diagnostics, formatPath(<JsonStep>[...argumentsAt, entry.key]), nested: true),
            child: KeptJsonEditor(value: entry.value, label: entry.key, onChanged: (Object? next) => context.write(<JsonStep>[...argumentsAt, entry.key], next)),
          ),
      if (value.containsKey('bindings'))
        GuiRow(
          label: 'bindings',
          help: 'Fills null placeholders in arguments from declared variables, keyed by JSON Pointer.',
          problems: diagnosticsAt(context.diagnostics, formatPath(<JsonStep>[...steps, 'bindings']), nested: true),
          child: KeptJsonEditor(value: value['bindings'], label: 'bindings', onChanged: (Object? next) => context.write(<JsonStep>[...steps, 'bindings'], next)),
        ),
    ];
  }

  Widget _setVariable(JsonMap value, FieldSpec field) {
    final List<JsonStep> at = <JsonStep>[...steps, field.key];
    return GuiRow(
      label: fieldLabel(field),
      help: field.help,
      problems: diagnosticsAt(context.diagnostics, formatPath(at)),
      child: GuiSelect(
        value: asString(value[field.key]),
        options: context.variables.keys.toList(),
        noneLabel: context.variables.isEmpty ? 'declare a variable first' : 'choose',
        onChanged: (String? next) => context.write(at, next),
      ),
    );
  }

  Widget _setValue(JsonMap value, FieldSpec field) {
    final List<JsonStep> at = <JsonStep>[...steps, field.key];
    final String? variable = asString(value['variable']);
    final String type = asString(asMap(context.variables[variable])['type']) ?? '';
    final Object? current = value[field.key];
    final bool fromVariable = current is Map<String, Object?>;
    final FieldSpec typed = FieldSpec(
      key: 'value',
      kind: switch (type) {
        'boolean' => FieldKind.boolean,
        'integer' => FieldKind.integer,
        'decimal' => FieldKind.number,
        'string' => FieldKind.string,
        _ => FieldKind.any,
      },
      required: true,
    );
    return GuiRow(
      label: fieldLabel(field),
      help: field.help,
      problems: diagnosticsAt(context.diagnostics, formatPath(at), nested: true),
      child: dom.div(classes: 'animus-control-stack', <Widget>[
        GuiSwitch(
          value: fromVariable,
          label: 'From another variable',
          onChanged: (bool on) => context.write(
            at,
            on ? <String, Object?>{'variable': context.variables.keys.firstOrNull ?? ''} : asMap(context.variables[variable])['initial'] ?? '',
          ),
        ),
        if (fromVariable)
          GuiSelect(
            value: asString(current['variable']),
            options: context.variables.keys.toList(),
            onChanged: (String? next) => context.write(<JsonStep>[...at, 'variable'], next),
          )
        else
          InspectControl(field: typed, value: current, path: formatPath(at), onChanged: (Object? next) => context.write(at, next ?? '')),
      ]),
    );
  }
}
