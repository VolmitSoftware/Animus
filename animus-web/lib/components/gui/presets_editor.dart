/// The Gloss presets document (defaults and named presets per kind), and
/// the fix on a document that names a preset: Animus refuses `preset`, so
/// its fields are copied in.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_presets.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/suggest/field_profile.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/section.dart';
import '../inspect/inspect_fields.dart';
import '../inspect/typed_values_editor.dart';
import 'component_editor.dart' show guiFieldRow;
import 'gui_fields.dart';
import 'gui_rows.dart';
import '../select/animus_select.dart';
import '../suggest/autocomplete_input.dart';
import '../../ui/field.dart';
import 'gui_stack.dart';
import '../../ui/stack.dart';

/// A preset kind by plain name: "Damage indicators" for `damage-indicators`.
String presetKindLabel(String kind) => switch (kind) {
  'inventories' => 'Chest GUIs',
  'animations' => 'Text animations',
  'motd' => 'Server list text',
  'real-drops' => 'Real drops',
  'emoji' => 'Emoji',
  _ => '${kind[0].toUpperCase()}${kind.substring(1).replaceAll('-', ' ')}',
};

/// Animus refuses a `preset` on chest GUIs, holograms and text animations:
/// an alert with the fix, copying the preset's fields in, or removing it
/// when it cannot be copied.
class PresetFix extends StatelessWidget {
  const PresetFix({required this.document, required this.presets, required this.kind, required this.onJson, super.key});

  final AnimusDocument document;
  final JsonMap? presets;
  final DocumentKind kind;
  final JsonEdit onJson;

  @override
  Widget build(BuildContext context) {
    final JsonMap doc = asMap(document.json);
    if (!doc.containsKey('preset') || doc['preset'] == null) return const dom.div(<Widget>[]);
    final (JsonMap? inlined, String? problem) = inlinePreset(doc, kind, presets);
    return dom.div(
      attributes: const <String, String>{'data-path': r'$.preset'},
      <Widget>[
        AppAlert(
          tone: AlertTone.error,
          title: 'Uses preset ${doc['preset']}',
          message: inlined != null ? 'Animus does not read presets. Copy its fields into this document to keep the look.' : 'Animus does not read presets, and this one cannot be copied in: $problem.',
          action: inlined != null
              ? Button.outline(label: 'Copy fields in', size: ButtonSize.sm, onPressed: () => onJson(writeAt(doc, const <JsonStep>[], inlined)))
              : Button.outline(label: 'Remove preset', size: ButtonSize.sm, onPressed: () => onJson(writeAt(doc, const <JsonStep>['preset'], null))),
        ),
      ],
    );
  }
}

class PresetsEditor extends StatefulWidget {
  const PresetsEditor({required this.document, required this.onJson, super.key});

  final AnimusDocument document;
  final JsonEdit onJson;

  @override
  State<PresetsEditor> createState() => _PresetsEditorState();
}

class _PresetsEditorState extends State<PresetsEditor> {
  late String _kind = _used.isNotEmpty ? _used.first : 'inventories';
  String? _open;

  JsonMap get _doc => asMap(component.document.json);
  List<Diagnostic> get _diagnostics => component.document.diagnostics;

  void _write(List<JsonStep> steps, Object? value, {String? coalesce}) => component.onJson(writeAt(_doc, steps, value), coalesce: coalesce ?? steps.join('.'));

  /// Kinds with defaults or presets, in schema order.
  List<String> get _used => <String>[
    for (final String kind in presetKinds)
      if (asMap(_doc['defaults']).containsKey(kind) || asMap(_doc['presets']).containsKey(kind)) kind,
  ];

  @override
  Widget build(BuildContext context) {
    final JsonMap doc = _doc;
    final JsonMap named = asMap(asMap(doc['presets'])[_kind]);
    final List<String> names = named.keys.toList();
    final String? open = named.containsKey(_open) ? _open : null;
    final String kind = presetKindLabel(_kind).toLowerCase();
    final List<String> used = _used;
    return dom.div(
      classes: 'ui-doc-form',
      attributes: const <String, String>{'role': 'region', 'aria-label': 'Presets'},
      <Widget>[stack(spacing: 16, <Widget>[
        const AppAlert(message: 'Only Gloss reads presets. Animus refuses a document that names one; its page offers to copy the fields in.'),
        Field(
          label: 'For',
          forId: 'animus-presets-kind',
          tip: 'The kind of document these defaults and presets fill.',
          child: AnimusSelect.values(
            id: 'animus-presets-kind',
            value: _kind,
            values: <String>[
              ...used,
              for (final String one in presetKinds)
                if (!used.contains(one)) one,
            ],
            labels: <String, String>{for (final String one in presetKinds) one: presetKindLabel(one)},
            groups: <String, String>{for (final String one in presetKinds) one: used.contains(one) ? 'In use' : 'Others'},
            onChanged: (String? v) => setState(() {
              _kind = v ?? _kind;
              _open = null;
            }),
          ),
        ),
        AppSection(
          title: 'Defaults',
          open: asMap(doc['defaults']).containsKey(_kind),
          tip: 'Fields every $kind document starts from before its own.',
          docs: Docs.gloss,
          children: <Widget>[
            _values(
              key: ValueKey<String>('defaults-$_kind'),
              values: asMap(asMap(doc['defaults'])[_kind]),
              path: childPath(r'$.defaults', _kind),
              label: 'Defaults',
              onChanged: (JsonMap v) => component.onJson(writeAt(_doc, const <JsonStep>[], withPresetDefaults(_doc, _kind, v)), coalesce: 'defaults.$_kind'),
            ),
          ],
        ),
        AppSection(
          title: 'Presets',
          count: named.length,
          tip: 'Named field sets a $kind document takes by naming one.',
          docs: Docs.gloss,
          children: <Widget>[
            stack(<Widget>[
              if (names.isEmpty)
                mutedText('No presets yet.')
              else
                OrderedRows(
                  label: 'Presets',
                  deleteNoun: 'preset',
                  rows: <OrderedRow>[
                    for (final String name in names)
                      OrderedRow(
                        // By position: renaming keeps the open row and its field.
                        key: '${names.indexOf(name)}',
                        name: name,
                        open: open == name,
                        line: RowOpener(
                          open: open == name,
                          onToggle: () => setState(() => _open = open == name ? null : name),
                          children: <Widget>[
                            truncatedText(name),
                          ],
                          body: open == name ? _preset(named, name, asMap(named[name])) : null,
                        ),
                      ),
                  ],
                  onDelete: (int index) => _write(<JsonStep>['presets', _kind], named.length == 1 ? null : (<String, Object?>{...named}..remove(names[index]))),
                ),
              ActionRow(
                dense: true,
                label: 'Presets',
                secondary: <RowAction>[
                  RowAction('Add preset', () {
                    int n = named.length + 1;
                    while (named.containsKey('preset$n')) {
                      n++;
                    }
                    _write(<JsonStep>['presets', _kind, 'preset$n'], <String, Object?>{'extends': null, 'values': <String, Object?>{}});
                    setState(() => _open = 'preset$n');
                  }, icon: ArcaneIcon.plus()),
                ],
              ),
            ]),
          ],
        ),
      ])],
    );
  }

  /// A preset kind's values: the form of the document it fills when Animus
  /// has a schema for it, else typed keys and values.
  Widget _values({required Key key, required JsonMap values, required String path, required String label, required void Function(JsonMap values) onChanged}) {
    final SchemaView? schema = presetSchema(_kind);
    return dom.div(
      key: key,
      attributes: <String, String>{'role': 'group', 'aria-label': label},
      <Widget>[stack(<Widget>[
        if (schema == null)
          for (final Diagnostic problem in diagnosticsAt(_diagnostics, path, nested: true)) AppAlert(tone: AlertTone.warning, message: problem.message),
        if (schema != null)
          InspectObject(schema: schema, node: presetFormNode(schema.root), value: values, path: path, diagnostics: _diagnostics, onChanged: onChanged)
        else
          KeyValueEditor(value: values, label: label, profileFor: (String _) => FieldProfile.guiText, onChanged: onChanged),
      ])],
    );
  }

  Widget _preset(JsonMap named, String name, JsonMap preset) {
    final String path = childPath(childPath(r'$.presets', _kind), name);
    return stack(<Widget>[
      guiFieldRow(
        'Name',
        path,
        _diagnostics,
        tip: 'Renaming updates the presets that extend it. No .., / or \\.',
        (String id) => AutocompleteInput(
          id: id,
          value: name,
          label: 'Preset name',
          onChanged: (String next) {
            if (!validPresetName(next) || named.containsKey(next)) return;
            final JsonMap renamed = renameKey(named, name, next);
            _write(
              <JsonStep>['presets', _kind],
              <String, Object?>{
                for (final MapEntry<String, Object?> entry in renamed.entries)
                  entry.key: asMap(entry.value)['extends'] == name ? <String, Object?>{...asMap(entry.value), 'extends': next} : entry.value,
              },
            );
            setState(() => _open = next);
          },
          profile: FieldProfile.none,
        ),
      ),
      guiFieldRow(
        'Builds on',
        '$path.extends',
        _diagnostics,
        tip: 'Another preset whose fields this one starts from.',
        (String id) => AnimusSelect.values(
          id: id,
          value: asString(preset['extends']),
          values: <String>[
            for (final String other in named.keys)
              if (other != name) other,
          ],
          allowNone: true,
          noneLabel: 'Nothing',
          onChanged: (String? v) => _write(<JsonStep>['presets', _kind, name], <String, Object?>{...preset, 'extends': v}),
        ),
      ),
      _values(
        key: ValueKey<String>('$path.values'),
        values: asMap(preset['values']),
        path: '$path.values',
        label: 'Values of $name',
        onChanged: (JsonMap v) => component.onJson(writeAt(_doc, const <JsonStep>[], withPresetValues(_doc, _kind, name, v)), coalesce: '$path.values'),
      ),
    ]);
  }
}
