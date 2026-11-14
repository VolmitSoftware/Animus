/// A behavior pack manifest's Visual view (`packs/<pack>/pack.json`): its
/// name, version and description from the pack schema, and the Java modules
/// the pack uses, each required or optional with its own configuration.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/npc/pack_model.dart';
import '../../model/animus_document.dart';
import '../../ui/action_row.dart';
import '../../ui/field.dart';
import '../../ui/section.dart';
import '../gui/gui_fields.dart';
import '../inspect/inspect_fields.dart';
import '../../logic/suggest/field_profile.dart';
import '../suggest/autocomplete_input.dart';
import 'npc_layout.dart';
import '../../ui/problem_text.dart';

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
      styles: stackStyles(gap: Gap.md),
      attributes: const <String, String>{'aria-label': 'Pack'},
      <Widget>[
        InspectObject(
          schema: component.schema,
          node: component.schema.root,
          value: pack,
          path: r'$',
          diagnostics: component.diagnostics,
          skip: const <String>{'modules'},
          onChanged: (JsonMap next) => _write(next, 'fields'),
        ),
        AppSection(
          title: 'Modules',
          tip: "Java modules the pack's behaviors use. A missing required one disables the pack's behaviors; a missing optional one only switches off what needs it.",
          docs: Docs.packs,
          count: modules.length,
          children: <Widget>[
            if (modules.isEmpty) mutedLine('No modules.'),
            for (final MapEntry<String, Object?> entry in modules.entries) _module(pack, entry.key, asMap(entry.value)),
            ActionRow(
              label: 'Add module',
              leading: <Widget>[
                wrapItem('1 1 16rem', AutocompleteInput(value: _adding, label: 'New module name', onChanged: (String text) => setState(() => _adding = text.trim()), profile: FieldProfile.none)),
              ],
              secondary: <RowAction>[
                RowAction('Add module', _adding.isEmpty || addProblem != null
                    ? null
                    : () {
                        _write(putPackModule(pack, _adding, const <String, Object?>{}), 'modules');
                        setState(() => _adding = '');
                      }),
              ],
            ),
            if (addProblem != null) problemText(addProblem, severity: DiagnosticSeverity.warning),
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
      styles: stackStyles(),
      attributes: <String, String>{'data-module': name},
      <Widget>[
        ActionRow(label: 'Module $name', leading: <Widget>[
          wrapItem('1 1 16rem', AutocompleteInput(
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
            profile: FieldProfile.none,
          )),
        ], secondary: <RowAction>[
          RowAction('Remove module $name', () => _write(putPackModule(pack, name, null), 'modules'), icon: ArcaneIcon.x(), iconOnly: true),
        ]),
        if (renameProblem != null)
          problemText('$renameProblem The name stays $name until then.', severity: DiagnosticSeverity.warning),
        Field(
          label: 'Required',
          child: ArcaneToggleSwitch(
            value: module['required'] != false,
            label: 'Required',
            onChanged: (bool required) =>
                put(<String, Object?>{...module, 'required': required}..removeWhere((String key, Object? value) => key == 'required' && value == true)),
                size: ComponentSize.sm,
          ),
        ),
        _configuration(pack, name, module, path),
        for (final Diagnostic problem in diagnosticsAt(component.diagnostics, path, nested: true))
          if (!problem.path.startsWith('$path.configuration'))
            problemText(problem.message, severity: problem.severity),
      ],
    );
  }

  /// The module's settings: a form from its schema when the server publishes
  /// one, else typed keys and values. A value that is not an object stays JSON.
  Widget _configuration(JsonMap pack, String name, JsonMap module, String path) {
    final String at = '$path.configuration';
    final FieldSpec field = packModuleConfiguration(component.schema, pack, name);
    final Object? value = module['configuration'];
    final bool schemed = field.schema.containsKey('properties');
    return Field(
      label: 'Configuration',
      tip: schemed ? "Fields from the module's schema. Keys it does not name are kept." : 'The module publishes no schema here; its keys are kept as written.',
      problems: diagnosticsAt(component.diagnostics, at, nested: true),
      child: value != null && value is! Map<String, Object?>
          ? KeptJsonEditor(value: value, label: 'Module $name configuration', onChanged: (Object? next) => _write(putPackModule(pack, name, asMap(writeIn(module, const <JsonStep>['configuration'], next))), 'module:$name'))
          : InspectControl(
              field: field,
              value: value,
              path: at,
              schema: component.schema,
              diagnostics: component.diagnostics,
              onChanged: (Object? next) => _write(putPackModuleConfiguration(pack, name, next is Map<String, Object?> ? next : null), 'module:$name'),
            ),
    );
  }
}
