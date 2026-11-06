/// The presets document editor (`gui/presets.json`): per-kind defaults and
/// named presets with `extends` and values. Also [PresetFix], shown in the
/// chest GUI, hologram and text animation editors when a document names a
/// preset, which core refuses.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_presets.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../config/docs_links.dart';
import '../info_tip.dart';
import 'gui_fields.dart';

/// The `preset` refusal with the fix: copy the preset's fields in.
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
    return dom.div(classes: 'animus-gui-fix', attributes: const <String, String>{'role': 'alert', 'data-path': r'$.preset'}, <Widget>[
      const dom.p(classes: 'animus-gui-refused', <Widget>[
        dom.code(<Widget>[Component.text(r'$.preset')]),
        Component.text(' $presetRefusal.'),
      ]),
      if (inlined != null)
        dom.div(classes: 'animus-row', <Widget>[
          Button.primary(
            label: 'Inline preset',
            size: ButtonSize.small,
            onPressed: () => onJson(writeAt(doc, const <JsonStep>[], inlined)),
          ),
          dom.span(classes: 'animus-muted', <Widget>[
            Component.text('Copies defaults.${presetKindFor(kind)} and ${doc['preset']} in, then removes preset.'),
          ]),
        ])
      else
        dom.div(classes: 'animus-row', <Widget>[
          dom.span(classes: 'animus-muted', <Widget>[Component.text('Cannot inline: $problem. Remove preset and set the fields here.')]),
          Button.secondary(label: 'Remove preset', size: ButtonSize.small, onPressed: () => onJson(writeAt(doc, const <JsonStep>['preset'], null))),
        ]),
    ]);
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

  JsonMap get _doc => asMap(component.document.json);
  List<Diagnostic> get _diagnostics => component.document.diagnostics;

  void _write(List<JsonStep> steps, Object? value, {String? coalesce}) =>
      component.onJson(writeAt(_doc, steps, value), coalesce: coalesce ?? steps.join('.'));

  /// Kinds with defaults or presets, in schema order.
  List<String> get _used => <String>[
        for (final String kind in presetKinds)
          if (asMap(_doc['defaults']).containsKey(kind) || asMap(_doc['presets']).containsKey(kind)) kind,
      ];

  @override
  Widget build(BuildContext context) {
    final JsonMap doc = _doc;
    final String kindId = guiPathId(r'$.kind');
    final JsonMap named = asMap(asMap(doc['presets'])[_kind]);
    final String defaultsPath = childPath(r'$.defaults', _kind);
    return dom.div(classes: 'animus-gui-editor is-single', <Widget>[
      dom.section(classes: 'animus-panel animus-gui-inspector', attributes: const <String, String>{'aria-label': 'Presets'}, <Widget>[
        dom.p(classes: 'animus-muted', <Widget>[
          InfoTip(
            tip: 'Only Gloss applies presets. Animus refuses documents that name one; their editors can inline it.',
            docs: Docs.gloss,
            child: Component.text('Kinds in use: ${_used.isEmpty ? 'none' : _used.join(', ')}'),
          ),
        ]),
        GuiRow(
          label: 'Revision',
          forId: guiPathId(r'$.revision'),
          help: 'Raise on each change',
          problems: diagnosticsAt(_diagnostics, r'$.revision'),
          child: GuiNumberInput(id: guiPathId(r'$.revision'), value: asInt(doc['revision']), label: 'Revision', onChanged: (num? v) => _write(<JsonStep>['revision'], v)),
        ),
        GuiRow(
          label: 'Kind',
          forId: kindId,
          help: 'Kind the presets apply to',
          child: GuiSelect(
            id: kindId,
            value: _kind,
            options: presetKinds,
            labels: <String, String>{for (final String kind in _used) kind: '$kind (in use)'},
            onChanged: (String? v) => setState(() => _kind = v ?? _kind),
          ),
        ),
        GuiSection(title: 'Defaults for $_kind', open: asMap(doc['defaults']).containsKey(_kind), children: <Widget>[
          GuiRow(
            label: 'Values',
            forId: guiPathId(defaultsPath),
            help: 'Base fields for every $_kind',
            problems: diagnosticsAt(_diagnostics, defaultsPath, nested: true),
            child: GuiJsonObjectInput(
              key: ValueKey<String>('defaults-$_kind'),
              id: guiPathId(defaultsPath),
              value: asMap(asMap(doc['defaults'])[_kind]).isEmpty ? null : asMap(asMap(doc['defaults'])[_kind]),
              multiline: true,
              label: 'Defaults for $_kind',
              onChanged: (JsonMap? v) => _write(<JsonStep>['defaults', _kind], v, coalesce: 'defaults.$_kind'),
            ),
          ),
        ]),
        GuiSection(title: 'Presets for $_kind', count: named.length, children: <Widget>[
          if (named.isEmpty) dom.p(classes: 'animus-muted', <Widget>[Component.text('No $_kind presets yet.')]),
          for (final MapEntry<String, Object?> entry in named.entries) _preset(named, entry.key, asMap(entry.value)),
          Button.secondary(
            label: 'Add a preset',
            size: ButtonSize.small,
            onPressed: () {
              int n = named.length + 1;
              while (named.containsKey('preset$n')) {
                n++;
              }
              _write(<JsonStep>['presets', _kind, 'preset$n'], <String, Object?>{'extends': null, 'values': <String, Object?>{}});
            },
          ),
        ]),
      ]),
    ]);
  }

  Widget _preset(JsonMap named, String name, JsonMap preset) {
    final String path = childPath(childPath(r'$.presets', _kind), name);
    final String nameId = guiPathId('$path-name');
    final String extendsId = guiPathId('$path.extends');
    final String valuesId = guiPathId('$path.values');
    return GuiSection(title: name, open: false, children: <Widget>[
      GuiRow(
        label: 'Name',
        forId: nameId,
        help: 'No .., / or \\, or leading space. Renaming updates presets that extend it.',
        problems: diagnosticsAt(_diagnostics, path),
        child: dom.div(classes: 'animus-row', <Widget>[
          GuiTextInput(
            id: nameId,
            value: name,
            label: 'Preset name',
            onChanged: (String next) {
              if (!validPresetName(next) || named.containsKey(next)) return;
              final JsonMap renamed = renameKey(named, name, next);
              _write(<JsonStep>['presets', _kind], <String, Object?>{
                for (final MapEntry<String, Object?> entry in renamed.entries)
                  entry.key: asMap(entry.value)['extends'] == name ? <String, Object?>{...asMap(entry.value), 'extends': next} : entry.value,
              });
            },
          ),
          GuiIconButton(
            icon: ArcaneIcon.trash(size: IconSize.sm),
            label: 'Remove preset $name',
            onPressed: () => _write(<JsonStep>['presets', _kind], named.length == 1 ? null : (<String, Object?>{...named}..remove(name))),
          ),
        ]),
      ),
      GuiRow(
        label: 'Extends',
        forId: extendsId,
        help: 'This preset overrides it',
        problems: diagnosticsAt(_diagnostics, '$path.extends'),
        child: GuiSelect(
          id: extendsId,
          value: asString(preset['extends']),
          options: <String>[for (final String other in named.keys) if (other != name) other],
          allowNone: true,
          noneLabel: 'nothing',
          onChanged: (String? v) => _write(<JsonStep>['presets', _kind, name], <String, Object?>{...preset, 'extends': v}),
        ),
      ),
      GuiRow(
        label: 'Values',
        forId: valuesId,
        help: 'JSON object. No schemaVersion, revision, id, uuid or preset.',
        problems: diagnosticsAt(_diagnostics, '$path.values', nested: true),
        child: GuiJsonObjectInput(
          key: ValueKey<String>('$path.values'),
          id: valuesId,
          value: asMap(preset['values']),
          multiline: true,
          label: 'Values of $name',
          onChanged: (JsonMap? v) => _write(<JsonStep>['presets', _kind, name, 'values'], v ?? <String, Object?>{}, coalesce: '$path.values'),
        ),
      ),
    ]);
  }
}
