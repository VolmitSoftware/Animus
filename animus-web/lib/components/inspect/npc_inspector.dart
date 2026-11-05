/// The NPC definition's Visual view: identity, body type, presence,
/// position, skin, behaviors (completed from the workspace), modules kept as
/// JSON, and every other schema field generically. Edits change the parsed
/// definition and report it whole, so unknown keys survive.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/inspect_schemas.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../mc/rigs/mc_rigs.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart';
import 'inspect_fields.dart';

/// Keys the NPC inspector lays out itself; the rest render generically.
const Set<String> _laidOut = <String>{
  'schemaVersion', 'revision', 'uuid', 'id', 'group', 'name', 'type', 'spawned', 'position', 'skin', 'behaviors', 'modules',
};

class NpcInspector extends StatefulWidget {
  const NpcInspector({
    required this.document,
    required this.onJson,
    required this.schemas,
    this.diagnostics = const <Diagnostic>[],
    this.behaviorIds = const <String>[],
    this.skinIds = const <String>[],
    super.key,
  });

  final AnimusDocument document;
  final JsonEdit onJson;
  final InspectSchemas schemas;

  /// The browser's and the server's diagnostics together.
  final List<Diagnostic> diagnostics;

  /// Behavior documents in the workspace, for the behaviors list.
  final List<String> behaviorIds;

  /// Skin documents in the workspace.
  final List<String> skinIds;

  @override
  State<NpcInspector> createState() => _NpcInspectorState();
}

class _NpcInspectorState extends State<NpcInspector> {
  String? _newModule;

  JsonMap get _doc => asMap(component.document.json);
  SchemaView get _schema => component.schemas.npc;
  List<Diagnostic> get _diagnostics => component.diagnostics;

  void _write(List<JsonStep> steps, Object? value) => component.onJson(writeIn(_doc, steps, value), coalesce: 'npc:${steps.join('.')}');

  FieldSpec _spec(String key) {
    final List<FieldSpec> fields = _schema.fields(_schema.root, _doc);
    return fields.where((FieldSpec field) => field.key == key).firstOrNull ?? FieldSpec(key: key, kind: FieldKind.any);
  }

  Widget _field(String key, {List<FieldOption> suggestions = const <FieldOption>[], String? label}) => InspectField(
        key: ValueKey<String>('npc:$key'),
        field: _spec(key),
        value: _doc[key],
        path: childPath(r'$', key),
        diagnostics: _diagnostics,
        suggestions: suggestions,
        schema: _schema,
        label: label,
        onChanged: (Object? value) => _write(<JsonStep>[key], value),
      );

  Widget _readOnly(String key, String help) {
    final FieldSpec spec = _spec(key);
    return GuiRow(
      label: key,
      help: <String>[if (spec.description != null) spec.description!, help].join(' '),
      problems: diagnosticsAt(_diagnostics, childPath(r'$', key)),
      child: dom.code(classes: 'animus-inspect-const', <Widget>[Component.text('${_doc[key] ?? '-'}')]),
    );
  }

  @override
  Widget build(BuildContext context) {
    final JsonMap doc = _doc;
    final FieldSpec position = _spec('position');
    return dom.section(classes: 'animus-panel animus-inspector', attributes: const <String, String>{'aria-label': 'NPC inspector'}, <Widget>[
      if (component.schemas.live) const dom.p(classes: 'animus-muted', <Widget>[Component.text("Fields from the connected server's schemas.")]),
      GuiSection(title: 'Identity', children: <Widget>[
        _field('name'),
        _readOnly('id', 'Change it by moving the file.'),
        _readOnly('group', 'Change it by moving the file.'),
        _readOnly('uuid', 'Never changes.'),
        _field('revision'),
      ]),
      GuiSection(title: 'Body', children: <Widget>[
        GuiRow(
          label: fieldLabel(_spec('type'), label: 'type'),
          help: '${_spec('type').description ?? ''} The preview draws ${mcBodyTypes.length} body types; others are kept and presented by the server.',
          problems: fieldProblems(_spec('type'), doc['type'], r'$.type', _diagnostics),
          child: SuggestInput(
            value: asString(doc['type']) ?? '',
            label: 'type',
            suggestions: <FieldOption>[for (final String type in mcBodyTypes) FieldOption(type)],
            onChanged: (String value) => _write(<JsonStep>['type'], value),
          ),
        ),
        _field('spawned'),
      ]),
      GuiSection(title: 'Position', children: <Widget>[
        if (position.schema.isNotEmpty)
          InspectObject(
            schema: _schema,
            node: position.schema,
            value: asMap(doc['position']),
            path: r'$.position',
            diagnostics: _diagnostics,
            onChanged: (JsonMap value) => _write(<JsonStep>['position'], value),
          ),
      ]),
      GuiSection(title: 'Skin', children: <Widget>[
        _field('skin', suggestions: <FieldOption>[for (final String id in component.skinIds) FieldOption(id, description: 'skin in this workspace')]),
      ]),
      _behaviors(doc),
      _modules(doc),
      _other(doc),
    ]);
  }

  Widget _behaviors(JsonMap doc) {
    final List<String> listed = stringList(doc['behaviors']);
    final List<String> available = <String>[for (final String id in component.behaviorIds) if (!listed.contains(id)) id];
    void put(List<String> next) => _write(<JsonStep>['behaviors'], next.isEmpty ? null : next);
    return GuiSection(title: 'Behaviors', count: listed.length, children: <Widget>[
      if (_spec('behaviors').description != null) dom.p(classes: 'animus-gui-help', <Widget>[Component.text(_spec('behaviors').description!)]),
      for (int index = 0; index < listed.length; index++)
        dom.div(key: ValueKey<String>('behavior-$index'), classes: 'animus-inspect-item', <Widget>[
          dom.div(classes: 'animus-gui-line', <Widget>[
            SuggestInput(
              value: listed[index],
              label: 'Behavior ${index + 1}',
              suggestions: <FieldOption>[for (final String id in component.behaviorIds) FieldOption(id)],
              onChanged: (String value) => put(<String>[...listed]..[index] = value),
            ),
            GuiIconButton(
              icon: ArcaneIcon.arrowUp(size: IconSize.sm),
              label: 'Move behavior ${index + 1} up',
              disabled: index == 0,
              onPressed: () => put(<String>[for (final Object? id in moveItem(listed, index, index - 1)) id! as String]),
            ),
            GuiIconButton(
              icon: ArcaneIcon.x(size: IconSize.sm),
              label: 'Remove behavior ${index + 1}',
              onPressed: () => put(<String>[...listed]..removeAt(index)),
            ),
          ]),
          if (!component.behaviorIds.contains(listed[index]))
            const dom.span(classes: 'animus-gui-problem is-warning', <Widget>[Component.text('Not a behavior in this workspace; the server may have it.')]),
          for (final Diagnostic problem in diagnosticsAt(_diagnostics, '\$.behaviors[$index]'))
            dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
        ]),
      dom.div(classes: 'animus-row', <Widget>[
        if (available.isNotEmpty)
          GuiSelect(
            value: null,
            options: available,
            noneLabel: 'Add a workspace behavior',
            onChanged: (String? id) {
              if (id != null) put(<String>[...listed, id]);
            },
          ),
        Button.ghost(label: 'Add by id', size: ButtonSize.small, onPressed: () => put(<String>[...listed, ''])),
      ]),
    ]);
  }

  Widget _modules(JsonMap doc) {
    final JsonMap modules = asMap(doc['modules']);
    final FieldSpec spec = _spec('modules');
    final List<FieldSpec> known = spec.schema.isEmpty ? const <FieldSpec>[] : _schema.fields(spec.schema, modules);
    final List<String> addable = <String>[for (final FieldSpec field in known) if (!modules.containsKey(field.key)) field.key];
    void put(String key, Object? value) {
      final JsonMap next = asMap(writeIn(modules, <JsonStep>[key], value));
      _write(<JsonStep>['modules'], next.isEmpty ? null : next);
    }

    final String? adding = _newModule;
    return GuiSection(title: 'Modules', count: modules.length, children: <Widget>[
      dom.p(classes: 'animus-gui-help', <Widget>[Component.text(spec.description ?? 'Per-NPC module settings, kept as written.')]),
      for (final MapEntry<String, Object?> entry in modules.entries)
        dom.div(key: ValueKey<String>('module:${entry.key}'), classes: 'animus-inspect-item', <Widget>[
          dom.div(classes: 'animus-gui-action-head', <Widget>[
            dom.code(<Widget>[Component.text(entry.key)]),
            dom.span(classes: 'animus-gui-help', <Widget>[
              Component.text(known.where((FieldSpec field) => field.key == entry.key).firstOrNull?.description ?? 'Kept as written; its module checks it.'),
            ]),
            GuiIconButton(icon: ArcaneIcon.x(size: IconSize.sm), label: 'Remove module ${entry.key}', onPressed: () => put(entry.key, null)),
          ]),
          KeptJsonEditor(value: entry.value, label: 'Module ${entry.key}', onChanged: (Object? value) => put(entry.key, value)),
          for (final Diagnostic problem in diagnosticsAt(_diagnostics, childPath(r'$.modules', entry.key), nested: true))
            dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[
              dom.code(<Widget>[Component.text(problem.path)]),
              Component.text(' ${problem.message}'),
            ]),
        ]),
      dom.div(classes: 'animus-row', <Widget>[
        if (addable.isNotEmpty)
          GuiSelect(
            value: null,
            options: addable,
            noneLabel: 'Add a known module',
            onChanged: (String? key) {
              if (key != null) put(key, <String, Object?>{'enabled': true});
            },
          ),
        GuiTextInput(value: adding ?? '', label: 'Module key', placeholder: 'namespace:name', onChanged: (String value) => setState(() => _newModule = value)),
        Button.ghost(
          label: 'Add',
          size: ButtonSize.small,
          disabled: adding == null || !RegExp(r'^[a-z0-9_.-]+:[a-z0-9_./-]+$').hasMatch(adding) || modules.containsKey(adding),
          onPressed: () {
            put(adding!, <String, Object?>{});
            setState(() => _newModule = null);
          },
        ),
      ]),
    ]);
  }

  Widget _other(JsonMap doc) {
    final List<FieldSpec> rest = <FieldSpec>[
      for (final FieldSpec field in _schema.fields(_schema.root, doc))
        if (!_laidOut.contains(field.key)) field,
    ];
    final Set<String> known = <String>{for (final FieldSpec field in _schema.fields(_schema.root, doc)) field.key};
    final List<String> kept = <String>[for (final String key in doc.keys) if (!known.contains(key)) key];
    if (rest.isEmpty && kept.isEmpty) return const dom.div(<Widget>[]);
    return GuiSection(title: 'Other fields', count: rest.length + kept.length, children: <Widget>[
      InspectObject(
        schema: _schema,
        node: _schema.root,
        value: doc,
        path: r'$',
        diagnostics: _diagnostics,
        skip: _laidOut,
        onChanged: (JsonMap value) => component.onJson(value, coalesce: 'npc:other'),
      ),
    ]);
  }
}
