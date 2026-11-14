/// One behavior node and its children as a card (the kit's quiet
/// disclosure): the summary names the node in words and says what it does;
/// the body starts with a toolbar (its type, and a "..." menu to move,
/// duplicate or delete it), then its fields and its children.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../config/docs_links.dart';
import '../../logic/plural.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/behavior_edit.dart';
import '../../logic/inspect/behavior_language.dart';
import '../../logic/inspect/behavior_operands.dart';
import '../../logic/inspect/behavior_summary.dart';
import '../../logic/inspect/behavior_words.dart';
import '../../logic/inspect/completion_catalog.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/suggest/field_profile.dart';
import '../../model/animus_document.dart';
import '../../ui/action_row.dart';
import '../../ui/badge.dart';
import '../../ui/confirm_dialog.dart';
import '../../ui/info_tip.dart';
import '../../ui/menu.dart';
import '../../ui/section.dart';
import '../../ui/tone.dart';
import '../gui/gui_fields.dart';
import 'behavior_capability.dart';
import 'behavior_inputs.dart';
import 'behavior_operand.dart';
import 'inspect_fields.dart';
import '../select/animus_select.dart';
import '../../ui/field.dart';
import '../../ui/number_input.dart';
import '../../ui/stack.dart';
import '../../ui/problem_text.dart';

/// What every node card needs: the catalog, the document and its writers.
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

/// The labels the type picker shows, in words.
Map<String, String> nodeTypeLabels(Iterable<String> types) => <String, String>{for (final String type in types) type: nodeLabel(type)};

/// A menu button that adds a node of the picked type.
class AddNodeMenu extends StatelessWidget {
  const AddNodeMenu({required this.context, required this.label, required this.onAdd, super.key});

  final BehaviorContext context;
  final String label;
  final void Function(String type) onAdd;

  @override
  Widget build(BuildContext build) => AppMenu(
        label: label,
        icon: ArcaneIcon.plus(),
        outline: true,
        entries: <AppMenuEntry>[for (final String type in context.nodeTypes) AppMenuEntry(nodeLabel(type), () => onAdd(type))],
      );
}

/// Asks before deleting [what] (a step and what it holds).
Future<void> confirmDelete(String what, String detail, void Function() remove) async {
  if (await askToConfirm(title: 'Delete $what?', message: '$detail Undo brings it back.', confirmLabel: 'Delete', destructive: true)) remove();
}

/// A node card. [flat] draws a part's top node without its own card: the
/// part's card already holds it.
class BehaviorNodeView extends StatelessWidget {
  const BehaviorNodeView({
    required this.context,
    required this.node,
    required this.steps,
    this.onRemove,
    this.onUp,
    this.onDown,
    this.onDuplicate,
    this.caption,
    this.inLoop = false,
    this.flat = false,
    super.key,
  });

  final BehaviorContext context;
  final Object? node;
  final List<JsonStep> steps;
  final void Function()? onRemove;
  final void Function()? onUp;
  final void Function()? onDown;
  final void Function()? onDuplicate;

  /// Where the node sits: `Yes`, `Step 2`, ...
  final String? caption;

  /// Whether a loop of this routine encloses the node, outside parallel
  /// branches: `break` and `continue` need one.
  final bool inLoop;
  final bool flat;

  BehaviorContext get ctx => context;
  String get _path => formatPath(steps);
  String get _label => caption ?? 'Step';

  void _put(JsonMap next) => ctx.write(steps, next);

  /// The short line beside the type: a count for lists, else the summary.
  String _line(JsonMap value) => switch (asString(value['type'])) {
        'sequence' => _count(asList(value['steps']).length, 'step'),
        'parallel' => _count(asList(value['branches']).length, 'branch'),
        _ => nodeSummary(value, variables: ctx.variables),
      };

  static String _count(int count, String noun) => countOf(count, noun, plural: noun == 'branch' ? 'branches' : null);

  /// The keys the shortcut sheet lists for a focused step.
  void _onKey(web.KeyboardEvent key, void Function(bool open) show, String line) {
    switch (key.key) {
      case 'ArrowRight' || 'ArrowLeft':
        key.preventDefault();
        show(key.key == 'ArrowRight');
      case 'ArrowDown' || 'ArrowUp':
        key.preventDefault();
        _focusStep(key.target! as web.Element, key.key == 'ArrowDown' ? 1 : -1);
      case 'Delete':
        if (onRemove == null) return;
        key.preventDefault();
        confirmDelete(_label.toLowerCase(), '$line, with what it holds.', onRemove!);
    }
  }

  /// Focuses the shown step summary [by] places from [from].
  static void _focusStep(web.Element from, int by) {
    final web.Element? editor = from.closest('.animus-behavior-editor');
    if (editor == null) return;
    final web.NodeList found = editor.querySelectorAll('.animus-node > details > summary');
    final List<web.HTMLElement> shown = <web.HTMLElement>[
      for (int index = 0; index < found.length; index++)
        if ((found.item(index)! as web.Element).checkVisibility()) found.item(index)! as web.HTMLElement,
    ];
    final int at = shown.indexWhere((web.HTMLElement summary) => identical(summary, from));
    if (at < 0 || at + by < 0 || at + by >= shown.length) return;
    shown[at + by].focus();
  }

  @override
  Widget build(BuildContext build) {
    final JsonMap value = asMap(node);
    final String? type = asString(value['type']);
    final NodeSpec? spec = ctx.catalog.nodes[type];
    final List<Diagnostic> own = diagnosticsAt(ctx.diagnostics, _path);
    final bool failing = diagnosticsAt(ctx.diagnostics, _path, nested: true).any((Diagnostic d) => d.severity == DiagnosticSeverity.error);
    final bool strayJump = (type == 'break' || type == 'continue') && !inLoop;
    final List<String> kept = unknownKeys(value, spec?.fields.map((FieldSpec field) => field.key) ?? const <String>[]);
    final String line = _line(value);
    final List<AppMenuEntry> menu = <AppMenuEntry>[
      if (onUp != null) AppMenuEntry('Move up', onUp),
      if (onDown != null) AppMenuEntry('Move down', onDown),
      if (onDuplicate != null) AppMenuEntry('Duplicate', onDuplicate),
      if (onRemove != null) ...<AppMenuEntry>[
        if (onUp != null || onDown != null || onDuplicate != null) const AppMenuEntry.separator(),
        AppMenuEntry('Delete', () => confirmDelete(_label.toLowerCase(), '$line, with what it holds.', onRemove!), destructive: true),
      ],
    ];
    final List<Widget> body = <Widget>[
      ActionRow(
        dense: true,
        label: _label,
        leading: <Widget>[
          AnimusSelect.values(
            value: type,
            values: ctx.nodeTypes,
            labels: nodeTypeLabels(ctx.nodeTypes),
            noneLabel: 'Pick a type',
            hiddenLabel: 'Type of ${_label.toLowerCase()}',
            onChanged: (String? next) {
              if (next != null) _put(changeNodeType(value, next, ctx.catalog, routine: ctx.entry, variables: ctx.variables, server: ctx.server));
            },
          ),
          if (spec == null) noteLine(type == null ? 'Pick a type' : 'Unknown type, kept as written'),
        ],
        more: menu,
      ),
      for (final Diagnostic problem in own) _problem(problem.severity, problem.message),
      if (strayJump) _problem(DiagnosticSeverity.warning, '${nodeLabel(type)} only works inside a repeat, a for each or a repeat while.'),
      ..._fields(value, type, spec),
      if (kept.isNotEmpty) noteLine('Kept as written: ${kept.join(', ')}'),
    ];
    final Map<String, String> attributes = <String, String>{'data-node-path': _path, 'data-node-type': type ?? ''};
    if (flat) return dom.div(classes: 'animus-node is-flat', attributes: attributes, body);
    return AppSection(
      classes: 'animus-node${failing ? ' has-error' : ''}',
      attributes: attributes,
      onKey: (web.KeyboardEvent key, void Function(bool open) show) => _onKey(key, show, line),
      summary: <Widget>[
        Text(_label, size: FontSize.sm, color: TextColor.muted),
        TipLabel(label: Component.text(nodeLabel(type)), tip: _tip(type, spec), docs: Docs.behaviorNodes),
        Text(line, size: FontSize.sm, color: TextColor.muted),
        if (failing) const StatusBadge('Problem', tone: BadgeTone.error),
        if (strayJump) const StatusBadge('Outside a loop', tone: BadgeTone.warning),
      ],
      children: body,
    );
  }

  /// The engine's word for the type, then what it does.
  static String? _tip(String? type, NodeSpec? spec) {
    if (type == null) return null;
    final String about = nodeWords[type]?.line ?? spec?.description ?? '';
    return 'Written as $type in the file. $about'.trim();
  }

  static Widget _problem(DiagnosticSeverity severity, String message) => problemLine(severity, message, classes: 'animus-node-problem');

  List<Diagnostic> _problems(String key, {bool nested = true}) => diagnosticsAt(ctx.diagnostics, formatPath(<JsonStep>[...steps, key]), nested: nested);

  FieldSpec _field(NodeSpec? spec, String key) => spec?.field(key) ?? FieldSpec(key: key, kind: FieldKind.any);

  List<Widget> _fields(JsonMap value, String? type, NodeSpec? spec) {
    final bool loop = const <String>{'repeat', 'foreach', 'while'}.contains(type);
    final bool childLoop = loop || (type != 'parallel' && inLoop);
    final List<Widget> out = <Widget>[];
    switch (type) {
      case 'action':
        out.add(CapabilityFields(context: ctx, node: value, steps: steps, kind: 'action'));
      case 'condition':
        out.add(_conditionMode(value));
        if (value.containsKey('expression')) {
          out.add(_expressionRow(value, _field(spec, 'expression')));
        } else {
          out.add(CapabilityFields(context: ctx, node: value, steps: steps, kind: 'condition'));
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
        out.add(_variablePicker(value, _field(spec, 'variable'), ctx.variables.keys.toList()));
        out.add(_operandRow(value, _field(spec, 'value'), asString(asMap(ctx.variables[value['variable']])['type']) ?? 'any'));
      case 'return':
        out.add(_returnRow(value, _field(spec, 'value')));
      case 'call':
        out.add(_routineRow(value, _field(spec, 'routine')));
        out.add(CallArguments(context: ctx, node: value, steps: steps));
        out.add(_variablePicker(value, _field(spec, 'result'), ctx.variables.keys.toList(), optional: true));
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
          diagnostics: ctx.diagnostics,
          profile: kind == 'expression' ? FieldProfile.behaviorExpression : FieldProfile.none,
          onChanged: (Object? next) => ctx.write(<JsonStep>[...steps, field.key], next),
        ));
      }
    }
    return out;
  }

  static const Set<String> _customTypes = <String>{'action', 'condition', 'while', 'repeat', 'foreach', 'set', 'return', 'call'};

  Widget _conditionMode(JsonMap value) {
    final bool expression = value.containsKey('expression');
    return Field(
      label: 'Checks',
      tip: 'Something in the game (a capability), or an expression that is true or false.',
      child: AnimusSelect.values(
        value: expression ? 'expression' : 'capability',
        values: const <String>['capability', 'expression'],
        labels: const <String, String>{'capability': 'Something in game', 'expression': 'An expression'},
        hiddenLabel: 'What ${_label.toLowerCase()} checks',
        onChanged: (String? next) {
          if (next == null || (next == 'expression') == expression) return;
          final JsonMap out = <String, Object?>{...value};
          for (final String key in const <String>['capability', 'arguments', 'bindings', 'expressions', 'templates', 'expression']) {
            out.remove(key);
          }
          if (next == 'expression') {
            out['expression'] = 'true';
          } else {
            final JsonMap fresh = defaultNode('condition', ctx.catalog, server: ctx.server);
            out['capability'] = fresh['capability'];
            out['arguments'] = fresh['arguments'];
          }
          _put(out);
        },
      ),
    );
  }

  Widget _expressionRow(JsonMap value, FieldSpec field) => Field(
        label: 'Expression',
        tip: field.description,
        problems: _problems('expression'),
        child: BehaviorExpressionInput(
          value: asString(value['expression']) ?? '',
          scope: ctx.scope,
          label: 'Expression of ${_label.toLowerCase()}',
          placeholder: 'count < 3',
          onChanged: (String next) => ctx.write(<JsonStep>[...steps, 'expression'], next),
        ),
      );

  Widget _operandRow(JsonMap value, FieldSpec field, String? type, {String? label, Widget? literal}) => Field(
        label: label ?? humanizeKey(field.key),
        help: field.boundsHint,
        tip: field.description,
        problems: _problems(field.key),
        child: OperandControl(
          value: value[field.key],
          type: type,
          variables: ctx.variables,
          scope: ctx.scope,
          label: '${label ?? humanizeKey(field.key)} of ${_label.toLowerCase()}',
          literal: literal,
          onChanged: (Object? next) => ctx.write(<JsonStep>[...steps, field.key], next),
        ),
      );

  Widget _countRow(JsonMap value, FieldSpec field) {
    final Object? count = value['count'];
    return _operandRow(
      value,
      field,
      'integer',
      label: 'Times',
      literal: NumberInput(
        value: count is num ? count : null,
        label: 'Times ${_label.toLowerCase()} repeats',
        onChanged: (num? next) => ctx.write(<JsonStep>[...steps, 'count'], (next ?? 0).round().clamp(0, 128)),
      ),
    );
  }

  Widget _returnRow(JsonMap value, FieldSpec field) {
    final bool has = value.containsKey('value');
    return Field(
      label: 'Value',
      tip: field.description,
      problems: _problems('value'),
      child: stack(<Widget>[
        ArcaneToggleSwitch(value: has, label: 'Return a value', onChanged: (bool on) => ctx.write(<JsonStep>[...steps, 'value'], on ? 0 : null), size: ComponentSize.sm),
        if (has)
          OperandControl(
            value: value['value'],
            variables: ctx.variables,
            scope: ctx.scope,
            label: 'Returned value',
            onChanged: (Object? next) => ctx.write(<JsonStep>[...steps, 'value'], next),
          ),
      ]),
    );
  }

  Widget _routineRow(JsonMap value, FieldSpec field) => Field(
        label: 'Part',
        tip: 'The part (routine) this step runs. ${field.description ?? ''}'.trim(),
        problems: <Diagnostic>[
          ..._problems('routine'),
          if (value['routine'] is String && !ctx.routines.contains(value['routine']))
            Diagnostic(DiagnosticSeverity.warning, formatPath(<JsonStep>[...steps, 'routine']), 'No part ${value['routine']} in this behavior.'),
        ],
        child: AnimusSelect.values(
          value: asString(value['routine']),
          values: ctx.routines,
          labels: <String, String>{for (final String name in ctx.routines) name: behaviorTitle(name)},
          noneLabel: 'Pick a part',
          hiddenLabel: 'Part ${_label.toLowerCase()} runs',
          onChanged: (String? next) => ctx.write(<JsonStep>[...steps, 'routine'], next),
        ),
      );

  Widget _variablePicker(JsonMap value, FieldSpec field, List<String> options, {bool optional = false}) => Field(
        label: humanizeKey(field.key),
        tip: field.description,
        problems: _problems(field.key, nested: false),
        child: options.isEmpty
            ? noteLine('Add a variable first.')
            : AnimusSelect.values(
                value: asString(value[field.key]),
                values: options,
                details: <String, String>{for (final String name in options) name: variableTypeWords[asString(asMap(ctx.variables[name])['type'])] ?? 'Anything'},
                allowNone: optional || !field.required,
                noneLabel: optional || !field.required ? 'Not set' : 'Pick a variable',
                hiddenLabel: '${humanizeKey(field.key)} of ${_label.toLowerCase()}',
                onChanged: (String? next) => ctx.write(<JsonStep>[...steps, field.key], next),
              ),
      );

  /// A loop's `as`, `index` or `key`: execution variables of [types].
  Widget _loopVariable(JsonMap value, FieldSpec field, List<String> types) {
    final List<String> options = executionVariables(ctx.variables, types: types);
    final Widget picker = _variablePicker(value, field, options);
    if (options.isNotEmpty || !field.required) return picker;
    return Field(
      label: humanizeKey(field.key),
      tip: field.description,
      problems: _problems(field.key, nested: false),
      child: ActionRow(secondary: <RowAction>[
        RowAction('Add a variable', () {
          final String name = uniqueName('item', ctx.variables.keys);
          final JsonMap base = <String, Object?>{...ctx.document, 'variables': <String, Object?>{...ctx.variables, name: <String, Object?>{'type': 'any', 'initial': ''}}};
          ctx.writeDocument(asMap(writeIn(base, <JsonStep>[...steps, field.key], name)));
        }),
      ]),
    );
  }

  Widget _list(JsonMap value, FieldSpec field, bool childLoop) {
    final List<JsonStep> at = <JsonStep>[...steps, field.key];
    final JsonList items = asList(value[field.key]);
    final bool branches = field.key == 'branches';
    final String noun = branches ? 'Branch' : 'Step';
    return dom.div(classes: 'animus-node-children', attributes: <String, String>{'role': 'group', 'aria-label': '${branches ? 'Branches' : 'Steps'} of ${_label.toLowerCase()}'}, <Widget>[
      if (items.isEmpty) noteLine(branches ? 'Add at least one branch.' : 'No steps yet.'),
      for (int index = 0; index < items.length; index++)
        BehaviorNodeView(
          key: ValueKey<String>(formatPath(<JsonStep>[...at, index])),
          context: ctx,
          node: items[index],
          steps: <JsonStep>[...at, index],
          caption: '$noun ${index + 1}',
          inLoop: childLoop,
          onUp: index == 0 ? null : () => ctx.write(at, moveItem(items, index, index - 1)),
          onDown: index == items.length - 1 ? null : () => ctx.write(at, moveItem(items, index, index + 1)),
          onDuplicate: () => ctx.write(at, <Object?>[...items]..insert(index + 1, deepCopy(items[index]))),
          onRemove: () => ctx.write(at, removeItem(items, index)),
        ),
      for (final Diagnostic problem in diagnosticsAt(ctx.diagnostics, formatPath(at))) _problem(problem.severity, problem.message),
      ActionRow(dense: true, leading: <Widget>[
        AddNodeMenu(context: ctx, label: 'Add ${noun.toLowerCase()}', onAdd: (String type) => ctx.addAt(at, type, append: true)),
      ]),
    ]);
  }

  /// The caption of a single-node field: a condition's Yes and No, a loop's
  /// steps.
  static String slotCaption(String key) => switch (key) {
        'then' => 'Yes',
        'else' => 'No',
        'body' => 'Steps',
        _ => humanizeKey(key),
      };

  Widget _slot(JsonMap value, FieldSpec field, bool childLoop) {
    final List<JsonStep> at = <JsonStep>[...steps, field.key];
    final Object? child = value[field.key];
    final String caption = slotCaption(field.key);
    if (child == null) {
      return Field(
        label: caption,
        tip: field.description,
        problems: diagnosticsAt(ctx.diagnostics, formatPath(at)),
        child: ActionRow(dense: true, leading: <Widget>[
          AddNodeMenu(context: ctx, label: 'Add', onAdd: (String type) => ctx.addAt(at, type)),
        ]),
      );
    }
    return dom.div(classes: 'animus-node-children', <Widget>[
      BehaviorNodeView(
        key: ValueKey<String>(formatPath(at)),
        context: ctx,
        node: child,
        steps: at,
        caption: caption,
        inLoop: childLoop,
        onRemove: field.required ? null : () => ctx.write(at, null),
      ),
    ]);
  }
}
