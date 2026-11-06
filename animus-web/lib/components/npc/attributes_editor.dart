/// The definition's `attributes`: one number per attribute it sets, with the
/// range and vanilla default beside it and what Animus does with it as help,
/// a reset that removes it, and an add searched from the schema's list.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/npc/npc_attributes.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart';
import '../select/suggest_input.dart';

class AttributesEditor extends StatefulWidget {
  const AttributesEditor({required this.value, required this.specs, required this.onChanged, this.diagnostics = const <Diagnostic>[], super.key});

  /// The `attributes` field as written.
  final Object? value;

  /// The attributes the schema knows.
  final List<AttributeSpec> specs;

  /// The new field, ids bare, or null to drop it.
  final void Function(JsonMap? attributes) onChanged;
  final List<Diagnostic> diagnostics;

  @override
  State<AttributesEditor> createState() => _AttributesEditorState();
}

class _AttributesEditorState extends State<AttributesEditor> {
  String _query = '';

  @override
  Widget build(BuildContext context) {
    final Map<String, AttributeSpec> table = <String, AttributeSpec>{for (final AttributeSpec spec in component.specs) spec.id: spec};
    final JsonMap set = bareAttributes(component.value);
    final List<AttributeSpec> offered = <AttributeSpec>[
      for (final AttributeSpec spec in component.specs)
        if (!set.containsKey(spec.id)) spec,
    ];
    final AttributeSpec? adding = offered.where((AttributeSpec spec) => spec.id == bareAttributeId(_query.trim())).firstOrNull;
    void add() {
      if (adding == null) return;
      component.onChanged(withAttribute(component.value, adding.id, adding.defaultValue));
      setState(() => _query = '');
    }

    return dom.div(classes: 'animus-npc-attributes', attributes: const <String, String>{'data-npc': 'attributes'}, <Widget>[
      if (set.isEmpty) const dom.p(classes: 'animus-muted', <Widget>[Component.text('No attributes set: the body has the vanilla defaults.')]),
      for (final MapEntry<String, Object?> entry in set.entries) _row(entry.key, entry.value, table[entry.key]),
      dom.div(classes: 'animus-gui-line', <Widget>[
        SuggestInput(
          value: _query,
          label: 'Attribute to add',
          placeholder: 'Search attributes, such as scale',
          suggestions: <FieldOption>[
            for (final AttributeSpec spec in offered) FieldOption(spec.id, description: '${spec.id}: ${spec.range}, vanilla ${formatAttribute(spec.defaultValue)}'),
          ],
          onChanged: (String text) => setState(() => _query = text),
        ),
        Button.ghost(label: 'Add attribute', size: ButtonSize.small, disabled: adding == null, onPressed: add),
      ]),
      if (_query.trim().isNotEmpty && adding == null)
        dom.span(classes: 'animus-gui-help', <Widget>[
          Component.text(set.containsKey(bareAttributeId(_query.trim())) ? 'Already set above.' : 'Not an attribute the schema lists.'),
        ]),
    ]);
  }

  /// Diagnostics for [id], whichever way the definition spells it.
  List<Diagnostic> _diagnostics(String id) => <Diagnostic>[
        for (final String key in asMap(component.value).keys)
          if (bareAttributeId(key) == id) ...diagnosticsAt(component.diagnostics, childPath(r'$.attributes', key)),
      ];

  Widget _row(String id, Object? value, AttributeSpec? spec) {
    final String inputId = guiPathId('attributes.$id');
    final List<Diagnostic> found = _diagnostics(id);
    final String? local = spec == null ? 'not an attribute the schema lists; the server refuses it' : spec.problem(value);
    return dom.div(
      key: ValueKey<String>('attribute:$id'),
      classes: 'animus-npc-attribute',
      attributes: <String, String>{'data-attribute': id},
      <Widget>[
        GuiRow(
          label: id,
          forId: inputId,
          help: spec?.description,
          problems: <Diagnostic>[
            ...found,
            if (found.isEmpty && local != null) Diagnostic(DiagnosticSeverity.error, childPath(r'$.attributes', id), local, code: 'attribute'),
          ],
          child: dom.div(classes: 'animus-gui-line', <Widget>[
            GuiNumberInput(
              id: inputId,
              value: value is num ? value : null,
              integer: false,
              label: '$id value',
              placeholder: spec == null ? null : formatAttribute(spec.defaultValue),
              onChanged: (num? next) {
                if (next != null) component.onChanged(withAttribute(component.value, id, next));
              },
            ),
            if (spec != null)
              dom.span(classes: 'animus-npc-attribute-range', attributes: const <String, String>{'data-attribute-hint': ''}, <Widget>[
                Component.text('${spec.range}, vanilla ${formatAttribute(spec.defaultValue)}'),
              ]),
            GuiIconButton(
              icon: ArcaneIcon.x(size: IconSize.sm),
              label: spec == null ? 'Remove $id' : 'Reset $id to vanilla ${formatAttribute(spec.defaultValue)}',
              onPressed: () => component.onChanged(withAttribute(component.value, id, null)),
            ),
          ]),
        ),
      ],
    );
  }
}
