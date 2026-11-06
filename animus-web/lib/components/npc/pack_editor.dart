/// A behavior pack manifest's Visual view (`packs/<pack>/pack.json`): its
/// name, version and description from the pack schema, and the Java modules
/// the pack uses, each required or optional with its own configuration.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/npc/pack_model.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart';
import '../info_tip.dart';
import '../inspect/inspect_fields.dart';

class PackEditor extends StatefulWidget {
  const PackEditor({required this.document, required this.schema, required this.onJson, this.diagnostics = const <Diagnostic>[], super.key});

  final AnimusDocument document;

  /// The pack manifest schema.
  final SchemaView schema;
  final JsonEdit onJson;
  final List<Diagnostic> diagnostics;

  @override
  State<PackEditor> createState() => _PackEditorState();
}

class _PackEditorState extends State<PackEditor> {
  String _adding = '';

  /// Module names being typed, by the name they had, kept until they are valid.
  final Map<String, String> _renaming = <String, String>{};

  JsonMap get _pack => asMap(component.document.json);

  void _write(JsonMap next, String what) => component.onJson(writeAt(_pack, const <JsonStep>[], next), coalesce: 'pack:$what');

  @override
  Widget build(BuildContext context) {
    final JsonMap pack = _pack;
    final JsonMap modules = asMap(pack['modules']);
    final String? addProblem = _adding.isEmpty ? null : packModuleNameProblem(_adding, modules.keys.toList());
    return dom.section(
      classes: 'animus-panel animus-inspector animus-pack-editor',
      attributes: const <String, String>{'aria-label': 'Pack'},
      <Widget>[
        GuiSection(
          title: 'Pack',
          children: <Widget>[
            InspectObject(
              schema: component.schema,
              node: component.schema.root,
              value: pack,
              path: r'$',
              diagnostics: component.diagnostics,
              skip: const <String>{'modules'},
              onChanged: (JsonMap next) => _write(next, 'fields'),
            ),
          ],
        ),
        GuiSection(
          title: 'Modules',
          count: modules.length,
          children: <Widget>[
            dom.p(classes: 'animus-gui-help', <Widget>[
              labelWithTip(
                const dom.span(<Widget>[Component.text("Java modules the pack's behaviors use.")]),
                "A missing required module disables the pack's behaviors. A missing optional one only switches off what needs it.",
                'Pack modules',
                docs: Docs.packs,
              ),
            ]),
            if (modules.isEmpty)
              const dom.p(classes: 'animus-muted', <Widget>[
                Component.text('No modules.'),
              ]),
            for (final MapEntry<String, Object?> entry in modules.entries) _module(pack, entry.key, asMap(entry.value)),
            dom.div(classes: 'animus-gui-line', <Widget>[
              GuiTextInput(value: _adding, label: 'New module name', placeholder: 'economy', onChanged: (String text) => setState(() => _adding = text.trim())),
              Button.secondary(
                label: 'Add module',
                size: ButtonSize.small,
                disabled: _adding.isEmpty || addProblem != null,
                onPressed: () {
                  _write(putPackModule(pack, _adding, const <String, Object?>{}), 'modules');
                  setState(() => _adding = '');
                },
              ),
            ]),
            if (addProblem != null) dom.span(classes: 'animus-gui-problem is-warning', <Widget>[Component.text(addProblem)]),
          ],
        ),
      ],
    );
  }

  Widget _module(JsonMap pack, String name, JsonMap module) {
    final String path = childPath(r'$.modules', name);
    final String typed = _renaming[name] ?? name;
    final String? renameProblem = typed == name
        ? null
        : packModuleNameProblem(typed, <String>[
            for (final String key in asMap(pack['modules']).keys)
              if (key != name) key,
          ]);
    void put(JsonMap next) => _write(putPackModule(pack, name, next), 'module:$name');
    return dom.div(
      key: ValueKey<String>('pack-module:$name'),
      classes: 'animus-inspect-item animus-pack-module',
      attributes: <String, String>{'data-module': name},
      <Widget>[
        dom.div(classes: 'animus-gui-line', <Widget>[
          GuiTextInput(
            value: typed,
            label: 'Module $name name',
            onChanged: (String text) {
              final String next = text.trim();
              if (packModuleNameProblem(next, <String>[
                    for (final String key in asMap(pack['modules']).keys)
                      if (key != name) key,
                  ]) ==
                  null) {
                setState(() => _renaming.remove(name));
                _write(renamePackModule(pack, name, next), 'module:$name');
              } else {
                setState(() => _renaming[name] = next);
              }
            },
          ),
          GuiIconButton(
            icon: ArcaneIcon.x(size: IconSize.sm),
            label: 'Remove module $name',
            onPressed: () => _write(putPackModule(pack, name, null), 'modules'),
          ),
        ]),
        if (renameProblem != null)
          dom.span(classes: 'animus-gui-problem is-warning', <Widget>[Component.text('$renameProblem The name stays $name until then.')]),
        GuiSwitch(
          value: module['required'] != false,
          label: 'Required',
          onChanged: (bool required) =>
              put(<String, Object?>{...module, 'required': required}..removeWhere((String key, Object? value) => key == 'required' && value == true)),
        ),
        GuiRow(
          label: 'Configuration',
          help: 'Kept as written.',
          problems: diagnosticsAt(component.diagnostics, '$path.configuration', nested: true),
          child: KeptJsonEditor(
            value: module['configuration'] ?? const <String, Object?>{},
            label: 'Module $name configuration',
            onChanged: (Object? value) {
              final JsonMap next = <String, Object?>{...module};
              if (value is Map<String, Object?> && value.isEmpty || value == null) {
                next.remove('configuration');
              } else {
                next['configuration'] = value;
              }
              put(next);
            },
          ),
        ),
        for (final Diagnostic problem in diagnosticsAt(component.diagnostics, path, nested: true))
          if (!problem.path.startsWith('$path.configuration'))
            dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[
              dom.code(<Widget>[Component.text(problem.path)]),
              Component.text(' ${problem.message}'),
            ]),
      ],
    );
  }
}
