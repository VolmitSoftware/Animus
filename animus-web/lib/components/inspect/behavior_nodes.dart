/// One behavior node and its children as a collapsible tree row: the
/// summary line shows the node's type and what it does in plain words; the
/// body holds its tools, its fields and its children on a thin rail per
/// level. Keyboard: Up and Down move between rows, Right opens, Left
/// closes, Enter toggles, Delete asks before removing.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/behavior_edit.dart';
import '../../logic/inspect/behavior_language.dart';
import '../../logic/inspect/behavior_operands.dart';
import '../../logic/inspect/behavior_summary.dart';
import '../../logic/inspect/completion_catalog.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/suggest/field_profile.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart';
import '../info_tip.dart';
import 'behavior_capability.dart';
import 'behavior_inputs.dart';
import 'behavior_operand.dart';
import 'inspect_fields.dart';

/// What every node row needs: the catalog, the document and its writers.
class BehaviorContext {
  const BehaviorContext({
    required this.catalog,
    required this.schema,
    required this.document,
    required this.diagnostics,
    required this.write,
    required this.writeDocument,
  });

  final CompletionCatalog catalog;
  final SchemaView schema;
  final JsonMap document;
  final List<Diagnostic> diagnostics;

  /// Writes [value] at [steps] of the document; null removes it.
  final void Function(List<JsonStep> steps, Object? value) write;

  /// Writes a whole new document, for edits that touch several parts.
  final void Function(JsonMap document) writeDocument;

  List<String> get routines => asMap(document['routines']).keys.toList();
  JsonMap get variables => asMap(document['variables']);
  List<String> get nodeTypes => catalog.nodes.keys.toList();
  bool get server => document['server'] == true;

  String? get entry => asString(document['entry']);

  /// The names expressions in this document can read.
  ExpressionScope get scope => ExpressionScope(
        language: catalog.expressions,
        variables: <String, String>{
          for (final MapEntry<String, Object?> entry in variables.entries) entry.key: asString(asMap(entry.value)['type']) ?? 'any',
        },
        contextKeys: <String>{
          for (final Object? trigger in asList(document['triggers'])) ...catalog.triggers[asString(asMap(trigger)['event'])]?.context ?? const <String>[],
        }.toList(),
        server: server,
      );

  JsonMap newNode(String type) => defaultNode(type, catalog, routine: entry ?? routines.firstOrNull, variables: variables, server: server);

  /// Adds a new node of [type] with [place], declaring what it needs first.
  void add(String type, JsonMap Function(JsonMap document, JsonMap node) place) {
    final JsonMap node = newNode(type);
    final JsonMap needed = variablesForNewNode(type, variables);
    final JsonMap base = needed.isEmpty ? document : <String, Object?>{...document, 'variables': <String, Object?>{...variables, ...needed}};
    writeDocument(place(base, node));
  }

  /// Adds a node of [type] at [steps] (a list when [append]).
  void addAt(List<JsonStep> steps, String type, {bool append = false}) => add(type, (JsonMap base, JsonMap node) {
        final Object value = append ? <Object?>[...asList(readIn(base, steps)), node] : node;
        return asMap(writeIn(base, steps, value));
      });
}

/// A picker that adds a node of the chosen type.
class AddNodeSelect extends StatelessWidget {
  const AddNodeSelect({required this.context, required this.label, required this.onAdd, super.key});

  final BehaviorContext context;
  final String label;
  final void Function(String type) onAdd;

  @override
  Widget build(BuildContext build) => dom.div(classes: 'animus-add-node', <Widget>[
        GuiSelect(
          value: null,
          options: context.nodeTypes,
          noneLabel: label,
          label: label,
          onChanged: (String? type) {
            if (type != null) onAdd(type);
          },
        ),
      ]);
}

/// Moves focus between visible node rows of the same tree.
void _moveFocus(web.Element from, int by) {
  final web.Element? tree = from.closest('.animus-behavior-tree');
  if (tree == null) return;
  final web.NodeList all = tree.querySelectorAll('.animus-node-summary');
  final List<web.HTMLElement> visible = <web.HTMLElement>[
    for (int index = 0; index < all.length; index++)
      if ((all.item(index)! as web.HTMLElement).offsetParent != null) all.item(index)! as web.HTMLElement,
  ];
  final int at = visible.indexWhere((web.HTMLElement element) => element == from);
  final int next = at + by;
  if (at >= 0 && next >= 0 && next < visible.length) visible[next].focus();
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
    this.inLoop = false,
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

  /// Whether a loop of this routine encloses the node, outside parallel
  /// branches: `break` and `continue` need one.
  final bool inLoop;

  String get _path => formatPath(steps);

  void _put(JsonMap next) => context.write(steps, next);

  void _onKey(web.Event event) {
    if (!event.isA<web.KeyboardEvent>()) return;
    final web.KeyboardEvent key = event as web.KeyboardEvent;
    final web.Element? summary = key.currentTarget as web.Element?;
    if (summary == null) return;
    final web.Element? details = summary.parentElement;
    switch (key.key) {
      case 'ArrowDown':
        key.preventDefault();
        _moveFocus(summary, 1);
      case 'ArrowUp':
        key.preventDefault();
        _moveFocus(summary, -1);
      case 'ArrowRight':
        key.preventDefault();
        details?.setAttribute('open', '');
      case 'ArrowLeft':
        key.preventDefault();
        details?.removeAttribute('open');
      case 'Delete':
        final void Function()? remove = onRemove;
        if (remove == null) return;
        key.preventDefault();
        if (web.window.confirm('Remove ${caption ?? 'this step'} (${nodeSummary(node, variables: context.variables)}) and its contents?')) remove();
    }
  }

  @override
  Widget build(BuildContext build) {
    final JsonMap value = asMap(node);
    final String? type = asString(value['type']);
    final NodeSpec? spec = context.catalog.nodes[type];
    final List<Diagnostic> own = diagnosticsAt(context.diagnostics, _path);
    final List<Diagnostic> nested = diagnosticsAt(context.diagnostics, _path, nested: true);
    final bool failing = nested.any((Diagnostic d) => d.severity == DiagnosticSeverity.error);
    final bool strayJump = (type == 'break' || type == 'continue') && !inLoop;
    final List<String> kept = unknownKeys(value, spec?.fields.map((FieldSpec field) => field.key) ?? const <String>[]);
    final String label = caption ?? 'step';
    return dom.details(
      classes: 'animus-node${failing ? ' has-error' : ''}',
      attributes: <String, String>{'open': '', 'data-node-path': _path, 'data-node-type': type ?? ''},
      <Widget>[
        dom.summary(
          classes: 'animus-node-summary',
          attributes: <String, String>{'aria-label': '$label, ${type ?? 'no type'}: ${nodeSummary(value, variables: context.variables)}${failing ? ', has problems' : ''}'},
          events: <String, void Function(web.Event)>{'keydown': _onKey},
          <Widget>[
            dom.span(classes: 'animus-node-caption', <Widget>[Component.text(label)]),
            dom.span(classes: 'animus-node-type', <Widget>[withTip(Component.text(type ?? '?'), spec?.description, docs: Docs.behaviorNodes)]),
            dom.span(classes: 'animus-node-line', <Widget>[Component.text(nodeSummary(value, variables: context.variables))]),
            if (failing) const dom.span(classes: 'animus-node-flag is-error', <Widget>[Component.text('problem')]),
            if (strayJump) const dom.span(classes: 'animus-node-flag is-warning', <Widget>[Component.text('outside a loop')]),
          ],
        ),
        dom.div(classes: 'animus-node-body', <Widget>[
          dom.div(classes: 'animus-node-tools', <Widget>[
            GuiSelect(
              value: type,
              options: context.nodeTypes,
              noneLabel: 'Pick a step type',
              label: 'Type of $label',
              onChanged: (String? next) {
                if (next != null) {
                  _put(changeNodeType(value, next, context.catalog, routine: context.entry, variables: context.variables, server: context.server));
                }
              },
            ),
            if (spec == null)
              dom.span(classes: 'animus-node-help', <Widget>[Component.text(type == null ? 'Pick a type' : 'Unknown type, kept as written')]),
            if (onUp != null) GuiIconButton(icon: ArcaneIcon.arrowUp(size: IconSize.sm), label: 'Move $label up', onPressed: onUp!),
            if (onDown != null) GuiIconButton(icon: ArcaneIcon.arrowDown(size: IconSize.sm), label: 'Move $label down', onPressed: onDown!),
            if (onRemove != null) GuiIconButton(icon: ArcaneIcon.x(size: IconSize.sm), label: 'Remove $label', onPressed: onRemove!),
          ]),
          for (final Diagnostic problem in own)
            dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', attributes: const <String, String>{'role': 'note'}, <Widget>[Component.text(problem.message)]),
          if (strayJump)
            dom.span(classes: 'animus-gui-problem is-warning', attributes: const <String, String>{'role': 'note'}, <Widget>[
              Component.text('$type must be inside a repeat, foreach or while, outside parallel branches.'),
            ]),
          ..._fields(value, type, spec),
          if (kept.isNotEmpty) dom.p(classes: 'animus-gui-note', <Widget>[Component.text('Kept as written: ${kept.join(', ')}')]),
        ]),
      ],
    );
  }

  List<Diagnostic> _problems(String key, {bool nested = true}) => diagnosticsAt(context.diagnostics, formatPath(<JsonStep>[...steps, key]), nested: nested);

  FieldSpec _field(NodeSpec? spec, String key) => spec?.field(key) ?? FieldSpec(key: key, kind: FieldKind.any);

  List<Widget> _fields(JsonMap value, String? type, NodeSpec? spec) {
    final bool loop = const <String>{'repeat', 'foreach', 'while'}.contains(type);
    final bool childLoop = loop || (type != 'parallel' && inLoop);
    final List<Widget> out = <Widget>[];
    switch (type) {
      case 'action':
        out.add(CapabilityFields(context: context, node: value, steps: steps, kind: 'action'));
      case 'condition':
        out.add(_conditionMode(value));
        if (value.containsKey('expression')) {
          out.add(_expressionRow(value, _field(spec, 'expression')));
        } else {
          out.add(CapabilityFields(context: context, node: value, steps: steps, kind: 'condition'));
        }
      case 'while':
        out.add(_expressionRow(value, _field(spec, 'expression')));
      case 'repeat':
        out.add(_countRow(value, _field(spec, 'count')));
        out.add(_loopVariable(value, _field(spec, 'index'), const <String>['integer', 'decimal', 'any']));
      case 'foreach':
        out.add(_operandRow(value, _field(spec, 'list'), null, label: 'List or map'));
        out.add(_loopVariable(value, _field(spec, 'as'), const <String>[]));
        out.add(_loopVariable(value, _field(spec, 'index'), const <String>['integer', 'decimal', 'any']));
        out.add(_loopVariable(value, _field(spec, 'key'), const <String>['string', 'any']));
      case 'set':
        out.add(_variablePicker(value, _field(spec, 'variable'), context.variables.keys.toList()));
        out.add(_operandRow(value, _field(spec, 'value'), asString(asMap(context.variables[value['variable']])['type']) ?? 'any'));
      case 'return':
        out.add(_returnRow(value, _field(spec, 'value')));
      case 'call':
        out.add(_routineRow(value, _field(spec, 'routine')));
        out.add(CallArguments(context: context, node: value, steps: steps));
        out.add(_variablePicker(value, _field(spec, 'result'), context.variables.keys.toList(), optional: true));
    }
    const Set<String> custom = <String>{
      'capability', 'arguments', 'bindings', 'expressions', 'templates', 'expression', 'count', 'index', 'list', 'as', 'key', 'variable', 'value', 'routine', 'result',
    };
    final bool customType = _customTypes.contains(type);
    for (final FieldSpec field in spec?.fields ?? const <FieldSpec>[]) {
      final String kind = fieldType(field);
      if (kind == nodeListType) {
        out.add(_list(value, field, childLoop));
      } else if (kind == nodeType) {
        out.add(_slot(value, field, childLoop));
      } else if (!(customType && custom.contains(field.key))) {
        out.add(InspectField(
          key: ValueKey<String>(formatPath(<JsonStep>[...steps, field.key])),
          field: field,
          value: value[field.key],
          path: formatPath(<JsonStep>[...steps, field.key]),
          diagnostics: context.diagnostics,
          profile: kind == 'expression' ? FieldProfile.behaviorExpression : FieldProfile.none,
          onChanged: (Object? next) => context.write(<JsonStep>[...steps, field.key], next),
        ));
      }
    }
    return out;
  }

  static const Set<String> _customTypes = <String>{'action', 'condition', 'while', 'repeat', 'foreach', 'set', 'return', 'call'};

  Widget _conditionMode(JsonMap value) {
    final bool expression = value.containsKey('expression');
    return GuiRow(
      label: 'Test',
      help: 'A capability asks the game. An expression computes true or false.',
      child: GuiSelect(
        value: expression ? 'expression' : 'capability',
        options: const <String>['capability', 'expression'],
        labels: const <String, String>{'capability': 'A capability', 'expression': 'An expression'},
        label: 'Test of ${caption ?? 'step'}',
        onChanged: (String? next) {
          if (next == null || (next == 'expression') == expression) return;
          final JsonMap out = <String, Object?>{...value};
          for (final String key in const <String>['capability', 'arguments', 'bindings', 'expressions', 'templates', 'expression']) {
            out.remove(key);
          }
          if (next == 'expression') {
            out['expression'] = 'true';
          } else {
            final JsonMap fresh = defaultNode('condition', context.catalog, server: context.server);
            out['capability'] = fresh['capability'];
            out['arguments'] = fresh['arguments'];
          }
          _put(out);
        },
      ),
    );
  }

  Widget _expressionRow(JsonMap value, FieldSpec field) => GuiRow(
        label: fieldLabel(field, label: 'Expression'),
        help: field.description,
        problems: _problems('expression'),
        child: BehaviorExpressionInput(
          value: asString(value['expression']) ?? '',
          scope: context.scope,
          label: 'Expression of ${caption ?? 'step'}',
          placeholder: 'count < 3',
          onChanged: (String next) => context.write(<JsonStep>[...steps, 'expression'], next),
        ),
      );

  Widget _operandRow(JsonMap value, FieldSpec field, String? type, {String? label, Widget? literal}) => GuiRow(
        label: fieldLabel(field, label: label),
        help: field.description,
        problems: _problems(field.key),
        child: OperandControl(
          value: value[field.key],
          type: type,
          variables: context.variables,
          scope: context.scope,
          label: '${label ?? humanizeKey(field.key)} of ${caption ?? 'step'}',
          literal: literal,
          onChanged: (Object? next) => context.write(<JsonStep>[...steps, field.key], next),
        ),
      );

  Widget _countRow(JsonMap value, FieldSpec field) {
    final Object? count = value['count'];
    return _operandRow(
      value,
      field,
      'integer',
      label: 'Count',
      literal: GuiNumberInput(
        value: count is num ? count : null,
        label: 'Count of ${caption ?? 'step'}',
        placeholder: '0 to 128',
        onChanged: (num? next) => context.write(<JsonStep>[...steps, 'count'], (next ?? 0).round().clamp(0, 128)),
      ),
    );
  }

  Widget _returnRow(JsonMap value, FieldSpec field) {
    final bool has = value.containsKey('value');
    return GuiRow(
      label: 'Value',
      help: field.description,
      problems: _problems('value'),
      child: dom.div(classes: 'animus-control-stack', <Widget>[
        GuiSwitch(
          value: has,
          label: 'Return a value',
          onChanged: (bool on) => context.write(<JsonStep>[...steps, 'value'], on ? 0 : null),
        ),
        if (has)
          OperandControl(
            value: value['value'],
            variables: context.variables,
            scope: context.scope,
            label: 'Returned value',
            onChanged: (Object? next) => context.write(<JsonStep>[...steps, 'value'], next),
          ),
      ]),
    );
  }

  Widget _routineRow(JsonMap value, FieldSpec field) => GuiRow(
        label: fieldLabel(field),
        help: field.help,
        problems: <Diagnostic>[
          ..._problems('routine'),
          if (value['routine'] is String && !context.routines.contains(value['routine']))
            Diagnostic(DiagnosticSeverity.warning, formatPath(<JsonStep>[...steps, 'routine']), 'No routine ${value['routine']} in this document.'),
        ],
        child: GuiSelect(
          value: asString(value['routine']),
          options: context.routines,
          noneLabel: 'Pick a routine',
          label: 'Routine ${caption ?? 'step'} calls',
          onChanged: (String? next) => context.write(<JsonStep>[...steps, 'routine'], next),
        ),
      );

  Widget _variablePicker(JsonMap value, FieldSpec field, List<String> options, {bool optional = false}) => GuiRow(
        label: fieldLabel(field),
        help: field.description,
        problems: _problems(field.key, nested: false),
        child: options.isEmpty
            ? const dom.p(classes: 'animus-gui-note', <Widget>[Component.text('Declare a variable first.')])
            : GuiSelect(
                value: asString(value[field.key]),
                options: options,
                labels: <String, String>{for (final String name in options) name: '$name (${asString(asMap(context.variables[name])['type']) ?? '?'})'},
                allowNone: optional || !field.required,
                noneLabel: optional || !field.required ? 'Not set' : 'Pick a variable',
                label: '${humanizeKey(field.key)} of ${caption ?? 'step'}',
                onChanged: (String? next) => context.write(<JsonStep>[...steps, field.key], next),
              ),
      );

  /// A loop's `as`, `index` or `key`: execution variables of [types].
  Widget _loopVariable(JsonMap value, FieldSpec field, List<String> types) {
    final List<String> options = executionVariables(context.variables, types: types);
    final Widget picker = _variablePicker(value, field, options);
    if (options.isNotEmpty || !field.required) return picker;
    return GuiRow(
      label: fieldLabel(field),
      help: field.description,
      problems: _problems(field.key, nested: false),
      child: Button.ghost(
        label: 'Declare item and use it',
        size: ButtonSize.small,
        onPressed: () {
          final String name = uniqueName('item', context.variables.keys);
          final JsonMap base = <String, Object?>{...context.document, 'variables': <String, Object?>{...context.variables, name: <String, Object?>{'type': 'any', 'initial': ''}}};
          context.writeDocument(asMap(writeIn(base, <JsonStep>[...steps, field.key], name)));
        },
      ),
    );
  }

  Widget _list(JsonMap value, FieldSpec field, bool childLoop) {
    final List<JsonStep> at = <JsonStep>[...steps, field.key];
    final JsonList items = asList(value[field.key]);
    final String noun = field.key == 'branches' ? 'branch' : 'step';
    return dom.div(classes: 'animus-node-children', attributes: <String, String>{'role': 'group', 'aria-label': '${humanizeKey(field.key)} of ${caption ?? 'step'}'}, <Widget>[
      if (items.isEmpty) dom.p(classes: 'animus-muted', <Widget>[Component.text(noun == 'step' ? 'No steps yet.' : 'A parallel needs at least one branch.')]),
      for (int index = 0; index < items.length; index++)
        BehaviorNodeView(
          key: ValueKey<String>(formatPath(<JsonStep>[...at, index])),
          context: context,
          node: items[index],
          steps: <JsonStep>[...at, index],
          caption: '$noun ${index + 1}',
          inLoop: childLoop,
          onUp: index == 0 ? null : () => context.write(at, moveItem(items, index, index - 1)),
          onDown: index == items.length - 1 ? null : () => context.write(at, moveItem(items, index, index + 1)),
          onRemove: () => context.write(at, removeItem(items, index)),
        ),
      for (final Diagnostic problem in diagnosticsAt(context.diagnostics, formatPath(at)))
        dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
      AddNodeSelect(context: context, label: 'Add $noun', onAdd: (String type) => context.addAt(at, type, append: true)),
    ]);
  }

  Widget _slot(JsonMap value, FieldSpec field, bool childLoop) {
    final List<JsonStep> at = <JsonStep>[...steps, field.key];
    final Object? child = value[field.key];
    if (child == null) {
      return GuiRow(
        label: fieldLabel(field),
        help: field.help,
        problems: diagnosticsAt(context.diagnostics, formatPath(at)),
        child: AddNodeSelect(context: context, label: 'Add ${field.key}', onAdd: (String type) => context.addAt(at, type)),
      );
    }
    return dom.div(classes: 'animus-node-children', <Widget>[
      BehaviorNodeView(
        key: ValueKey<String>(formatPath(at)),
        context: context,
        node: child,
        steps: at,
        caption: field.key,
        inLoop: childLoop,
        onRemove: field.required ? null : () => context.write(at, null),
      ),
    ]);
  }
}
