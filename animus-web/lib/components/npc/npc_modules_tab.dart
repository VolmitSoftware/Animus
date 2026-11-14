/// The Advanced Modules and Integrations tabs. Modules lists every module
/// without a tab of its own (typed fields where the schema knows it, raw
/// values where it does not), adds one, and shows the definition's fields
/// no tab edits, kept as written. Integrations holds what works only with
/// another server plugin: a disguise and the model and mob modules.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/npc/item_slots.dart' show hireModule;
import '../../logic/npc/module_rows.dart';
import '../../logic/npc/npc_integrations.dart';
import '../../logic/npc/npc_tabs.dart';
import '../../logic/npc/schedule_model.dart';
import '../../model/animus_document.dart';
import '../../ui/action_row.dart';
import '../../ui/empty_state.dart';
import '../../ui/field.dart';
import '../../ui/info_tip.dart';
import '../../ui/section.dart';
import '../gui/gui_fields.dart';
import '../inspect/inspect_fields.dart';
import '../inspect/typed_values_editor.dart';
import 'inventory_slots.dart';
import 'npc_form.dart';
import 'npc_layout.dart';
import '../../logic/suggest/field_profile.dart';
import '../suggest/autocomplete_input.dart';
import '../select/animus_select.dart';
import '../../ui/problem_text.dart';

final RegExp _moduleKey = RegExp(r'^[a-z0-9_.-]+:[a-z0-9_./-]+$');

class NpcModulesTab extends StatefulWidget {
  const NpcModulesTab({required this.form, required this.integrations, super.key});

  final NpcForm form;

  /// The Integrations tab: plugin modules and fields instead of the rest.
  final bool integrations;

  @override
  State<NpcModulesTab> createState() => _NpcModulesTabState();
}

class _NpcModulesTabState extends State<NpcModulesTab> {
  String _newKey = '';
  final Set<String> _asJson = <String>{};

  NpcForm get _form => component.form;

  bool _shows(String key) => component.integrations ? integrationModules.containsKey(key) : !tabModules.contains(key) && !integrationModules.containsKey(key);

  @override
  Widget build(BuildContext context) {
    final NpcForm form = _form;
    final JsonMap modules = form.modules;
    final FieldSpec spec = form.spec('modules');
    final List<FieldSpec> known = spec.schema.isEmpty ? const <FieldSpec>[] : form.schema.fields(spec.schema, modules);
    final List<String> addable = <String>[
      for (final FieldSpec field in known)
        if (!modules.containsKey(field.key) && _shows(field.key) && offersModule(field.key, form.serverPlugins)) field.key,
    ];
    final List<MapEntry<String, Object?>> shown = <MapEntry<String, Object?>>[
      for (final MapEntry<String, Object?> entry in modules.entries)
        if (_shows(entry.key)) entry,
    ];
    final bool disguise = component.integrations && !form.group && (form.doc.containsKey('disguise') || offersField('disguise', form.serverPlugins));
    return tabBody(<Widget>[
      if (disguise) form.field('disguise', label: 'Disguise', bounds: false),
      for (final MapEntry<String, Object?> entry in shown) _module(entry.key, entry.value, known.where((FieldSpec field) => field.key == entry.key).firstOrNull),
      if (component.integrations && !disguise && shown.isEmpty && addable.isEmpty)
        const EmptyState(message: 'No plugin on this server adds settings here.'),
      if (addable.isNotEmpty || !component.integrations) _add(addable),
      if (!component.integrations) ..._kept(form),
    ]);
  }

  Widget _add(List<String> addable) {
    const String addId = 'animus-npc-add-module';
    const String keyId = 'animus-npc-module-key';
    final JsonMap modules = _form.modules;
    final bool valid = _moduleKey.hasMatch(_newKey) && !modules.containsKey(_newKey);
    return Field(
      label: 'Add module',
      forId: addable.isNotEmpty ? addId : keyId,
      tip: 'A module is one feature\'s settings, under a namespace:name key. Pick one the server knows, or type a key.',
      docs: Docs.npc,
      child: dom.div(classes: 'animus-npc-add-module', <Widget>[
        if (addable.isNotEmpty)
          AnimusSelect.values(
            id: addId,
            value: null,
            values: addable,
            noneLabel: 'Pick a module',
            onChanged: (String? key) {
              if (key != null) _form.putModule(key, key == scheduleModule ? scheduleStarter(_form.origin) : <String, Object?>{'enabled': true});
            },
          ),
        if (!component.integrations)
          ActionRow(
            label: 'Module key',
            leading: <Widget>[
              wrapItem('1 1 16rem', AutocompleteInput(id: keyId, value: _newKey, label: 'Module key', placeholder: 'namespace:name', onChanged: (String value) => setState(() => _newKey = value.trim()), profile: FieldProfile.none)),
            ],
            secondary: <RowAction>[
              RowAction('Add', valid
                  ? () {
                      _form.putModule(_newKey, <String, Object?>{});
                      setState(() => _newKey = '');
                    }
                  : null),
            ],
          ),
      ]),
    );
  }

  Widget _module(String key, Object? value, FieldSpec? field) {
    final NpcForm form = _form;
    final String path = childPath(r'$.modules', key);
    if (field == null) return _unknown(key, value, path);
    final bool canType = field.schema.isNotEmpty && value is Map<String, Object?>;
    final bool typed = canType && !_asJson.contains(key);
    return dom.div(
      key: ValueKey<String>('module:$key'),
      styles: stackStyles(),
      attributes: <String, String>{'data-module': key},
      <Widget>[
        ActionRow(
          label: 'Module $key',
          leading: <Widget>[
            dom.span(classes: 'animus-npc-module-key', <Widget>[
              TipLabel(label: dom.code(<Widget>[Component.text(key)]), tip: field.description ?? 'No schema for this module; its value is kept as written.'),
            ]),
          ],
          secondary: <RowAction>[
            if (canType) RowAction(typed ? 'Edit as JSON' : 'Edit as fields', () => setState(() => typed ? _asJson.add(key) : _asJson.remove(key))),
            RowAction('Remove module $key', () => form.putModule(key, null), icon: ArcaneIcon.x(), iconOnly: true),
          ],
        ),
        if (typed && key == hireModule)
          HireModuleFields(
            schema: form.schema,
            node: field.schema,
            value: value,
            path: path,
            diagnostics: form.diagnostics,
            onChanged: (JsonMap next, {JsonMap? from}) => form.putModule(key, next, from: from),
          )
        else if (typed)
          InspectObject(schema: form.schema, node: field.schema, value: value, path: path, diagnostics: form.diagnostics, onChanged: (JsonMap next) => form.putModule(key, next))
        else ...<Widget>[
          if (value is Map<String, Object?>)
            KeyValueEditor(value: value, label: 'Module $key', onChanged: (JsonMap next) => form.putModule(key, next))
          else
            TypedValueEditor(value: value, label: 'Module $key', onChanged: (Object? next) => form.putModule(key, next)),
          for (final Diagnostic problem in diagnosticsAt(form.diagnostics, path, nested: true))
            problemText(problem.message, severity: problem.severity),
        ],
      ],
    );
  }

  /// A module no form knows (an importer's carried-over settings, say):
  /// one closed row that opens to its raw values; its problems show under
  /// the row.
  Widget _unknown(String key, Object? value, String path) {
    final NpcForm form = _form;
    final List<Diagnostic> problems = diagnosticsAt(form.diagnostics, path, nested: true);
    return dom.div(
      key: ValueKey<String>('module:$key'),
      styles: stackStyles(gap: Gap.xs),
      attributes: <String, String>{'data-module': key},
      <Widget>[
        AppSection(
          title: unknownModuleTitle(key),
          open: problems.isNotEmpty,
          count: moduleSettingCount(value),
          tip: '$key: no form here knows these settings, so they are kept as written.',
          children: <Widget>[
            ActionRow(
              label: 'Module $key',
              leading: <Widget>[dom.code(<Widget>[Component.text(key)])],
              secondary: <RowAction>[RowAction('Remove module $key', () => form.putModule(key, null), icon: ArcaneIcon.x(), iconOnly: true)],
            ),
            if (value is Map<String, Object?>)
              KeyValueEditor(value: value, label: 'Module $key', onChanged: (JsonMap next) => form.putModule(key, next))
            else
              TypedValueEditor(value: value, label: 'Module $key', onChanged: (Object? next) => form.putModule(key, next)),
          ],
        ),
        for (final Diagnostic problem in problems) problemText(problem.message, severity: problem.severity),
      ],
    );
  }

  /// The definition's fields no tab edits, kept as written, closed with
  /// their count. Import records (`migration`) are left to Code.
  List<Widget> _kept(NpcForm form) {
    final JsonMap doc = form.doc;
    final Set<String> skip = <String>{...npcLaidOut, ...importRecordKeys};
    final List<String> rest = advancedInUse(NpcTab.modules, <String, Object?>{for (final MapEntry<String, Object?> entry in doc.entries) if (entry.key != 'modules') entry.key: entry.value}, group: form.group, laidOut: npcLaidOut);
    final bool schemaRest = form.fields.any((FieldSpec field) => !skip.contains(field.key));
    if (rest.isEmpty && !schemaRest) return const <Widget>[];
    return <Widget>[
      AppSection(
        title: 'Other fields',
        open: false,
        count: rest.isEmpty ? null : rest.length,
        tip: 'Fields with no control in the form. They stay in the file as written.',
        docs: Docs.npc,
        attributes: const <String, String>{'data-npc': 'kept'},
        children: <Widget>[
          InspectObject(
            schema: form.schema,
            node: form.schema.root,
            value: doc,
            path: r'$',
            diagnostics: form.diagnostics,
            skip: skip,
            unknownLabel: 'Unknown keys',
            onChanged: (JsonMap value) => form.emit(const <JsonStep>[], value, coalesce: 'npc:other'),
          ),
        ],
      ),
    ];
  }
}

/// The NPC's or group's `attributes` with a one-line note for groups.
class NpcAttributesNote extends StatelessWidget {
  const NpcAttributesNote({required this.group, super.key});

  final bool group;

  @override
  Widget build(BuildContext context) => group
      ? mutedLine('Members take each value set here unless they set their own.')
      : const Component.fragment(<Widget>[]);
}
