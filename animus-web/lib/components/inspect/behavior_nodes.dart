/// One behavior node and its children as a card (the kit's quiet
/// disclosure): the summary has a drag handle, names the node in words and
/// says what it does; the body starts with a toolbar (its type, and a "..."
/// menu to move, duplicate, copy, paste or delete it), then its fields and
/// its children. Every structural edit is one undo step named for the
/// notice ("move Say up"); a type change that drops content asks first.
library;

import 'dart:js_interop';

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
import '../../logic/inspect/behavior_steps.dart';
import '../../logic/inspect/behavior_summary.dart';
import '../../logic/inspect/behavior_types.dart';
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
import '../../ui/stack.dart';
import '../../ui/problem_text.dart';
import '../shortcut_sheet.dart' show macPlatform;
import 'step_inputs.dart';

/// What every node card needs: the catalog, the document and its writers.
class BehaviorContext {
  const BehaviorContext({
    required this.catalog,
    required this.schema,
    required this.document,
    required this.diagnostics,
    required this.write,
    required this.change,
    required this.changeDocument,
  });

  final CompletionCatalog catalog;
  final SchemaView schema;
  final JsonMap document;
  final List<Diagnostic> diagnostics;

  /// Writes [value] at [steps] of the document; null removes it. Typing in
  /// one field joins one undo step.
  final void Function(List<JsonStep> steps, Object? value) write;

  /// A structural edit (add, move, delete, paste, a type change): its own
  /// undo step, named [label] in the Undo and Redo notices.
  final void Function(List<JsonStep> steps, Object? value, String label) change;

  /// A structural edit of the whole document, named [label].
  final void Function(JsonMap document, String label) changeDocument;

  List<String> get routines => asMap(document['routines']).keys.toList();
  JsonMap get variables => asMap(document['variables']);
  List<String> get nodeTypes => catalog.nodes.keys.toList();
  bool get server => document['server'] == true;

  String? get entry => asString(document['entry']);

  /// The names expressions in this document can read, with every trigger's
  /// context keys.
  ExpressionScope get scope => behaviorScope(document, catalog);

  /// The names an expression at [steps] can read: the context keys are those
  /// of the triggers that start its routine.
  ExpressionScope scopeAt(List<JsonStep> steps) =>
      behaviorScope(document, catalog, routine: steps.length > 1 && steps.first == 'routines' && steps[1] is String ? steps[1] as String : null);

  JsonMap newNode(String type) => defaultNode(type, catalog, routine: entry ?? routines.firstOrNull, variables: variables, server: server);

  /// Adds a new node of [type] with [place], declaring what it needs first.
  void add(String type, JsonMap Function(JsonMap document, JsonMap node) place) {
    final JsonMap node = newNode(type);
    final JsonMap needed = variablesForNewNode(type, variables);
    final JsonMap base = needed.isEmpty ? document : <String, Object?>{...document, 'variables': <String, Object?>{...variables, ...needed}};
    changeDocument(place(base, node), 'add ${stepName(node)}');
  }

  /// Adds a node of [type] at [steps] (a list when [append]).
  void addAt(List<JsonStep> steps, String type, {bool append = false}) => add(type, (JsonMap base, JsonMap node) {
        final Object value = append ? <Object?>[...asList(readIn(base, steps)), node] : node;
        return asMap(writeIn(base, steps, value));
      });

  /// Adds a node of [type] after the single step at [slot], both in order.
  void addAfter(List<JsonStep> slot, String type) => add(type, (JsonMap base, JsonMap node) {
        final Object? existing = readIn(base, slot);
        return asMap(writeIn(base, slot, existing is Map<String, Object?> ? wrapWithStep(existing, node) : node));
      });

  /// Moves the step at [from] of the list [fromList] to before index [to] of
  /// [intoList] as one undo step, then focuses it there.
  void move(List<JsonStep> fromList, int from, List<JsonStep> intoList, int to, String label) {
    final JsonMap? moved = moveStep(document, fromList, from, intoList, to);
    if (moved == null) return;
    changeDocument(moved, label);
    final bool same = formatPath(fromList) == formatPath(intoList);
    focusStep(formatPath(<JsonStep>[...intoList, same && to > from ? to - 1 : to]));
  }

  /// Copies [node] for pasting here or into another routine or behavior.
  void copy(Object? node) {
    final CopiedStep copied = copyStep(document, node, catalog);
    copiedStep = copied;
    ArcaneSonner.info('Copied ${copied.name}.');
  }

  /// Pastes the copied step at index [at] of the list [list].
  void paste(List<JsonStep> list, int at) {
    final CopiedStep? copied = copiedStep;
    if (copied == null) return;
    changeDocument(pasteStep(document, list, at, copied), 'paste ${copied.name}');
    focusStep(formatPath(<JsonStep>[...list, at]));
  }

  /// Pastes the copied step into the single-step field [slot].
  void pasteIn(List<JsonStep> slot) {
    final CopiedStep? copied = copiedStep;
    if (copied == null) return;
    changeDocument(pasteInto(document, slot, copied), 'paste ${copied.name}');
  }
}

/// Focuses the summary of the step at [path] once the page shows it.
void focusStep(String path) {
  int tries = 0;
  void attempt() {
    final web.NodeList found = web.document.querySelectorAll('.animus-node');
    for (int index = 0; index < found.length; index++) {
      final web.Element node = found.item(index)! as web.Element;
      if (node.getAttribute('data-node-path') != path) continue;
      final web.Element? summary = node.querySelector(':scope > details > summary');
      if (summary != null) {
        (summary as web.HTMLElement).focus();
        return;
      }
    }
    if (++tries < 30) web.window.setTimeout(attempt.toJS, 16.toJS);
  }

  web.window.setTimeout(attempt.toJS, 0.toJS);
}

/// The step being dragged: its list and place, in which behavior.
class _StepDrag {
  const _StepDrag(this.behavior, this.list, this.index, this.name);

  final String behavior;
  final List<JsonStep> list;
  final int index;
  final String name;
}

_StepDrag? _dragging;

/// The labels the type picker shows, in words.
Map<String, String> nodeTypeLabels(Iterable<String> types) => <String, String>{for (final String type in types) type: nodeLabel(type)};

/// A menu button that adds a node of the picked type, or with [onPaste]
/// the copied step.
class AddNodeMenu extends StatelessWidget {
  const AddNodeMenu({required this.context, required this.label, required this.onAdd, this.onPaste, super.key});

  final BehaviorContext context;
  final String label;
  final void Function(String type) onAdd;
  final void Function()? onPaste;

  @override
  Widget build(BuildContext build) {
    final CopiedStep? copied = copiedStep;
    return AppMenu(
      label: label,
      icon: ArcaneIcon.plus(),
      outline: true,
      entries: <AppMenuEntry>[
        for (final String type in context.nodeTypes) AppMenuEntry(nodeLabel(type), () => onAdd(type)),
        if (onPaste != null && copied != null) ...<AppMenuEntry>[
          const AppMenuEntry.separator(),
          AppMenuEntry('Paste ${copied.name}', onPaste, hint: macPlatform ? 'Cmd+V' : 'Ctrl+V'),
        ],
      ],
    );
  }
}

/// Asks before deleting [what] (a step and what it holds).
Future<void> confirmDelete(String what, String detail, void Function() remove) async {
  if (await askToConfirm(title: 'Delete $what?', message: '$detail Undo brings it back.', confirmLabel: 'Delete', destructive: true)) remove();
}

/// A node card. [flat] draws a routine's top node without its own card: the
/// routine's card already holds it. A step of a list gets [list] (the
/// list's path) and its [index] there, for moving, pasting and dragging.
class BehaviorNodeView extends StatelessWidget {
  const BehaviorNodeView({
    required this.context,
    required this.node,
    required this.steps,
    this.list,
    this.index,
    this.onRemove,
    this.caption,
    this.flat = false,
    super.key,
  });

  final BehaviorContext context;
  final Object? node;
  final List<JsonStep> steps;

  /// The list this step is in, and its place there.
  final List<JsonStep>? list;
  final int? index;

  /// Removes a step that is not in a list (an If's Yes or No).
  final void Function()? onRemove;

  /// Where the node sits: `Yes`, `Step 2`, ...
  final String? caption;

  final bool flat;

  BehaviorContext get ctx => context;
  String get _path => formatPath(steps);
  String get _label => caption ?? 'Step';
  String get _name => stepName(node);

  void _put(JsonMap next) => ctx.write(steps, next);

  int get _count => asList(readIn(ctx.document, list ?? const <JsonStep>[])).length;

  void Function()? get _up => list == null || index! == 0 ? null : () => ctx.move(list!, index!, list!, index! - 1, 'move $_name up');

  void Function()? get _down => list == null || index! >= _count - 1 ? null : () => ctx.move(list!, index!, list!, index! + 2, 'move $_name down');

  void Function()? get _duplicate => list == null
      ? null
      : () {
          ctx.change(list!, insertItem(asList(readIn(ctx.document, list!)), index! + 1, deepCopy(node)), 'duplicate $_name');
          focusStep(formatPath(<JsonStep>[...list!, index! + 1]));
        };

  void Function()? get _remove => list != null ? () => ctx.change(list!, removeItem(asList(readIn(ctx.document, list!)), index!), 'delete $_name') : onRemove;

  void Function()? get _pasteAfter {
    if (copiedStep == null) return null;
    if (list != null) return () => ctx.paste(list!, index! + 1);
    return () => ctx.pasteIn(steps);
  }

  /// The short line beside the type: a count for lists, else the summary.
  String _line(JsonMap value) => switch (asString(value['type'])) {
        'sequence' => _countWords(asList(value['steps']).length, 'step'),
        'parallel' => _countWords(asList(value['branches']).length, 'branch'),
        _ => nodeSummary(value, variables: ctx.variables),
      };

  static String _countWords(int count, String noun) => countOf(count, noun, plural: noun == 'branch' ? 'branches' : null);

  /// The keys the shortcut sheet lists for a focused step.
  void _onKey(web.KeyboardEvent key, void Function(bool open) show, String line) {
    final bool mod = macPlatform ? key.metaKey : key.ctrlKey;
    void run(void Function()? action) {
      if (action == null) return;
      key.preventDefault();
      action();
    }

    switch (key.key) {
      case 'ArrowUp' || 'ArrowDown' when key.altKey:
        run(key.key == 'ArrowUp' ? _up : _down);
      case 'ArrowRight' || 'ArrowLeft':
        key.preventDefault();
        show(key.key == 'ArrowRight');
      case 'ArrowDown' || 'ArrowUp':
        key.preventDefault();
        _focusStep(key.target! as web.Element, key.key == 'ArrowDown' ? 1 : -1);
      case 'c' || 'C' when mod:
        run(() => ctx.copy(node));
      case 'v' || 'V' when mod:
        run(_pasteAfter);
      case 'd' || 'D' when mod:
        run(_duplicate);
      case 'Delete':
        final void Function()? remove = _remove;
        if (remove == null) return;
        key.preventDefault();
        confirmDelete(_label.toLowerCase(), '$line, with what it holds.', remove);
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

  String get _behaviorId => asString(ctx.document['id']) ?? '';

  /// The grip a step is dragged by, to another place in any list of this
  /// behavior. The keyboard way is Alt+Up and Alt+Down.
  Widget _grip() => dom.span(
        classes: 'animus-conv-handle',
        attributes: <String, String>{'draggable': 'true', 'title': 'Drag to move', 'aria-hidden': 'true', 'data-step-grip': ''},
        events: <String, void Function(web.Event)>{
          'click': (web.Event event) => event.preventDefault(),
          'dragstart': (web.Event event) {
            event.stopPropagation();
            _dragging = _StepDrag(_behaviorId, list!, index!, _name);
            final web.DataTransfer? data = (event as web.DragEvent).dataTransfer;
            data?.setData('text/plain', _path);
            data?.effectAllowed = 'move';
            final web.Element? card = (event.target as web.Element?)?.closest('.animus-node');
            if (card != null) data?.setDragImage(card, 16, 16);
          },
          'dragend': (web.Event _) => _dragging = null,
        },
        <Widget>[ArcaneIcon.gripVertical(size: IconSize.sm)],
      );

  /// Drops a dragged step of this behavior before this one (after it when
  /// it came from above in the same list).
  Map<String, void Function(web.Event)> _dropEvents() {
    bool fits() => _dragging != null && list != null && _dragging!.behavior == _behaviorId;
    return <String, void Function(web.Event)>{
      'dragover': (web.Event event) {
        if (!fits()) return;
        event.preventDefault();
        event.stopPropagation();
      },
      'drop': (web.Event event) {
        if (!fits()) return;
        event.preventDefault();
        event.stopPropagation();
        final _StepDrag from = _dragging!;
        _dragging = null;
        final bool same = formatPath(from.list) == formatPath(list!);
        if (same && from.index == index) return;
        ctx.move(from.list, from.index, list!, same && from.index < index! ? index! + 1 : index!, 'move ${from.name}');
      },
    };
  }

  /// Confirms a type change that drops content, then writes it.
  Future<void> _changeType(JsonMap value, String next) async {
    final JsonMap changed = changeNodeType(value, next, ctx.catalog, routine: ctx.entry, variables: ctx.variables, server: ctx.server);
    final String? loss = typeChangeLoss(value, changed, ctx.catalog);
    final String to = nodeLabel(next);
    if (loss != null) {
      final String what = RegExp(r'^\d+ steps?$').hasMatch(loss) ? (loss.startsWith('1 ') ? '$loss goes' : '$loss go') : '"$loss" goes';
      if (!await askToConfirm(title: 'Change $_name to $to?', message: '$what. Undo brings it back.', confirmLabel: 'Change')) return;
    }
    ctx.change(steps, changed, 'change $_name to $to');
  }

  @override
  Widget build(BuildContext build) {
    final JsonMap value = asMap(node);
    final String? type = asString(value['type']);
    final NodeSpec? spec = ctx.catalog.nodes[type];
    final List<Diagnostic> own = diagnosticsAt(ctx.diagnostics, _path);
    final bool failing = diagnosticsAt(ctx.diagnostics, _path, nested: true).any((Diagnostic d) => d.severity == DiagnosticSeverity.error);
    final List<String> kept = unknownKeys(value, spec?.fields.map((FieldSpec field) => field.key) ?? const <String>[]);
    final String line = _line(value);
    final String mod = macPlatform ? 'Cmd' : 'Ctrl';
    final String alt = macPlatform ? 'Option' : 'Alt';
    final void Function()? up = _up, down = _down, duplicate = _duplicate, remove = _remove, pasteAfter = _pasteAfter;
    final CopiedStep? copied = copiedStep;
    final List<AppMenuEntry> menu = <AppMenuEntry>[
      if (list != null) ...<AppMenuEntry>[
        AppMenuEntry('Move up', up, hint: '$alt+Up'),
        AppMenuEntry('Move down', down, hint: '$alt+Down'),
        AppMenuEntry('Duplicate', duplicate, hint: '$mod+D'),
      ],
      if (!flat) AppMenuEntry('Copy', () => ctx.copy(node), hint: '$mod+C'),
      if (!flat && pasteAfter != null && copied != null) AppMenuEntry(list != null ? 'Paste ${copied.name} after' : 'Paste ${copied.name}', pasteAfter, hint: '$mod+V'),
      if (remove != null) ...<AppMenuEntry>[
        const AppMenuEntry.separator(),
        AppMenuEntry('Delete', () => confirmDelete(_label.toLowerCase(), '$line, with what it holds.', remove), destructive: true),
      ],
    ];
    final List<Widget> body = <Widget>[
      ActionRow(
        label: _label,
        leading: <Widget>[
          AnimusSelect.values(
            value: type,
            values: ctx.nodeTypes,
            labels: nodeTypeLabels(ctx.nodeTypes),
            noneLabel: 'Pick a type',
            hiddenLabel: 'Type of ${_label.toLowerCase()}',
            onChanged: (String? next) {
              if (next != null && next != type) _changeType(value, next);
            },
          ),
          if (spec == null) noteLine(type == null ? 'Pick a type' : 'Unknown type, kept as written'),
        ],
        more: menu,
      ),
      for (final Diagnostic problem in own) _problem(problem.severity, problem.message),
      ..._fields(value, type, spec),
      if (kept.isNotEmpty) noteLine('Kept as written: ${kept.join(', ')}'),
    ];
    final Map<String, String> attributes = <String, String>{'data-node-path': _path, 'data-node-type': type ?? ''};
    if (flat) return dom.div(classes: 'animus-node is-flat', attributes: attributes, body);
    final Widget card = AppSection(
      classes: 'animus-node${failing ? ' has-error' : ''}',
      attributes: attributes,
      onKey: (web.KeyboardEvent key, void Function(bool open) show) => _onKey(key, show, line),
      summary: <Widget>[
        if (list != null) _grip(),
        Text(_label, size: FontSize.sm, color: TextColor.muted),
        TipLabel(label: Component.text(nodeLabel(type)), tip: _tip(type, spec), docs: Docs.behaviorNodes),
        Text(line, size: FontSize.sm, color: TextColor.muted),
        if (failing) const StatusBadge('Problem', tone: BadgeTone.error),
      ],
      children: body,
    );
    return list == null ? card : dom.div(events: _dropEvents(), <Widget>[card]);
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
    final List<Widget> out = <Widget>[];
    switch (type) {
      case 'action':
        out.add(CapabilityFields(context: ctx, node: value, steps: steps, kind: 'action'));
        // Only a capability that reads something gives a result worth keeping.
        if (value.containsKey('result') || const <String>{'animus:read-inventory', 'animus:ray-trace', 'animus:read-block'}.contains(value['capability'])) {
          out.add(_resultPicker(value, _field(spec, 'result')));
        }
      case 'condition':
        out.add(_conditionMode(value));
        if (value.containsKey('expression')) {
          out.add(_expressionRow(value, _field(spec, 'expression')));
        } else {
          out.add(CapabilityFields(context: ctx, node: value, steps: steps, kind: 'condition'));
        }
      case 'while':
        out.add(_expressionRow(value, _field(spec, 'expression')));
      case 'wait':
        out.add(_ticksRow(value, _field(spec, 'ticks')));
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
        out.add(_operandRow(value, _field(spec, 'player'), 'string', label: 'For player'));
        out.add(_operandRow(value, _field(spec, 'npc'), 'string', label: 'For NPC'));
    }
    const Set<String> custom = <String>{
      'capability', 'arguments', 'bindings', 'expressions', 'templates', 'expression', 'count', 'ticks', 'index', 'list', 'as', 'key', 'variable', 'value', 'routine', 'result',
      'player', 'npc',
    };
    final bool customType = _customTypes.contains(type);
    for (final FieldSpec field in spec?.fields ?? const <FieldSpec>[]) {
      final String kind = fieldType(field);
      if (kind == nodeListType) {
        out.add(_list(value, field));
      } else if (kind == nodeType) {
        out.add(_slot(value, field));
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

  static const Set<String> _customTypes = <String>{'action', 'condition', 'while', 'wait', 'repeat', 'foreach', 'set', 'return', 'call'};

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
          scope: ctx.scopeAt(steps),
          label: 'Expression of ${_label.toLowerCase()}',
          placeholder: 'count < 3',
          expected: ValueType.boolean,
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
          scope: ctx.scopeAt(steps),
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
      literal: BoundedNumberInput(
        value: count is num ? count : null,
        minimum: 0,
        maximum: 128,
        label: 'Times',
        onChanged: (int next) => ctx.write(<JsonStep>[...steps, 'count'], next),
      ),
    );
  }

  Widget _ticksRow(JsonMap value, FieldSpec field) {
    final Object? ticks = value['ticks'];
    return _operandRow(
      value,
      field,
      'integer',
      label: 'Ticks',
      literal: BoundedNumberInput(
        value: ticks is num ? ticks : null,
        minimum: 1,
        maximum: 72000,
        ticks: true,
        label: 'Ticks',
        onChanged: (int next) => ctx.write(<JsonStep>[...steps, 'ticks'], next),
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
            scope: ctx.scopeAt(steps),
            label: 'Returned value',
            onChanged: (Object? next) => ctx.write(<JsonStep>[...steps, 'value'], next),
          ),
      ]),
    );
  }

  Widget _routineRow(JsonMap value, FieldSpec field) => Field(
        label: 'Routine',
        tip: 'The routine this step calls. ${field.description ?? ''}'.trim(),
        problems: <Diagnostic>[
          ..._problems('routine'),
          if (value['routine'] is String && !ctx.routines.contains(value['routine']))
            Diagnostic(DiagnosticSeverity.warning, formatPath(<JsonStep>[...steps, 'routine']), 'No routine ${value['routine']} in this behavior.'),
        ],
        child: AnimusSelect.values(
          value: asString(value['routine']),
          values: ctx.routines,
          labels: <String, String>{for (final String name in ctx.routines) name: behaviorTitle(name)},
          noneLabel: 'Pick a routine',
          hiddenLabel: 'Routine ${_label.toLowerCase()} calls',
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

  /// The variable an action's result map goes into, with New variable to
  /// declare one for it in place.
  Widget _resultPicker(JsonMap value, FieldSpec field) {
    final List<String> options = <String>[
      for (final MapEntry<String, Object?> variable in ctx.variables.entries)
        if (const <String>{'map', 'any'}.contains(asString(asMap(variable.value)['type']))) variable.key,
    ];
    void create() {
      final String name = uniqueName(resultVariableName(asString(value['capability'])), ctx.variables.keys);
      final JsonMap base = <String, Object?>{...ctx.document, 'variables': <String, Object?>{...ctx.variables, name: <String, Object?>{'type': 'map', 'initial': <String, Object?>{}}}};
      ctx.changeDocument(asMap(writeIn(base, <JsonStep>[...steps, field.key], name)), 'add variable $name');
    }

    return Field(
      label: humanizeKey(field.key),
      tip: field.description,
      problems: _problems(field.key, nested: false),
      child: Row(gapSize: Gap.sm, crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
        if (options.isNotEmpty)
          Expanded(
            child: AnimusSelect.values(
              value: asString(value[field.key]),
              values: options,
              allowNone: true,
              noneLabel: 'Not kept',
              hiddenLabel: '${humanizeKey(field.key)} of ${_label.toLowerCase()}',
              onChanged: (String? next) => ctx.write(<JsonStep>[...steps, field.key], next),
            ),
          ),
        Button.outline(label: 'New variable', onPressed: create),
      ]),
    );
  }

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
          ctx.changeDocument(asMap(writeIn(base, <JsonStep>[...steps, field.key], name)), 'add variable $name');
        }),
      ]),
    );
  }

  Widget _list(JsonMap value, FieldSpec field) {
    final List<JsonStep> at = <JsonStep>[...steps, field.key];
    final JsonList items = asList(value[field.key]);
    final bool branches = field.key == 'branches';
    final String noun = branches ? 'Branch' : 'Step';
    final String behavior = asString(ctx.document['id']) ?? '';
    return dom.div(classes: 'animus-node-children', attributes: <String, String>{'role': 'group', 'aria-label': '${branches ? 'Branches' : 'Steps'} of ${_label.toLowerCase()}'}, <Widget>[
      if (items.isEmpty) noteLine(branches ? 'Add at least one branch.' : 'No steps yet.'),
      for (int index = 0; index < items.length; index++)
        BehaviorNodeView(
          key: ValueKey<String>(formatPath(<JsonStep>[...at, index])),
          context: ctx,
          node: items[index],
          steps: <JsonStep>[...at, index],
          list: at,
          index: index,
          caption: '$noun ${index + 1}',
        ),
      for (final Diagnostic problem in diagnosticsAt(ctx.diagnostics, formatPath(at))) _problem(problem.severity, problem.message),
      // Dropping a dragged step on the Add row puts it last.
      dom.div(
        events: <String, void Function(web.Event)>{
          'dragover': (web.Event event) {
            if (_dragging?.behavior != behavior) return;
            event.preventDefault();
            event.stopPropagation();
          },
          'drop': (web.Event event) {
            final _StepDrag? from = _dragging;
            if (from == null || from.behavior != behavior) return;
            event.preventDefault();
            event.stopPropagation();
            _dragging = null;
            ctx.move(from.list, from.index, at, items.length, 'move ${from.name}');
          },
        },
        <Widget>[
          ActionRow(leading: <Widget>[
            AddNodeMenu(
              context: ctx,
              label: 'Add ${noun.toLowerCase()}',
              onAdd: (String type) => ctx.addAt(at, type, append: true),
              onPaste: () => ctx.paste(at, items.length),
            ),
          ]),
        ],
      ),
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

  Widget _slot(JsonMap value, FieldSpec field) {
    final List<JsonStep> at = <JsonStep>[...steps, field.key];
    final Object? child = value[field.key];
    final String caption = slotCaption(field.key);
    if (child == null) {
      return Field(
        label: caption,
        tip: field.description,
        problems: diagnosticsAt(ctx.diagnostics, formatPath(at)),
        child: ActionRow(leading: <Widget>[
          AddNodeMenu(context: ctx, label: 'Add', onAdd: (String type) => ctx.addAt(at, type), onPaste: () => ctx.pasteIn(at)),
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
        onRemove: field.required ? null : () => ctx.change(at, null, 'delete ${stepName(child)}'),
      ),
      ?growStep(ctx, at, child),
    ]);
  }
}

/// "Add step" under a single step that is not a list (an If's Yes, a
/// routine of one step): the new step joins it in order. Null under a list,
/// which has its own.
Widget? growStep(BehaviorContext ctx, List<JsonStep> slot, Object? child) {
  final String? type = asString(asMap(child)['type']);
  if (type == null || type == 'sequence' || type == 'parallel') return null;
  return ActionRow(leading: <Widget>[
    AddNodeMenu(context: ctx, label: 'Add step', onAdd: (String added) => ctx.addAfter(slot, added), onPaste: () => ctx.pasteIn(slot)),
  ]);
}
