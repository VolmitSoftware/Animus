/// The definition's `attributes`: one number per attribute it sets, with the
/// range and vanilla default beside it and what Animus does with it as help,
/// a reset that removes it, and an add dropdown of the schema's list grouped
/// and ordered for the body type.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/npc/npc_attributes.dart';
import '../../model/animus_document.dart';
import '../../logic/select/select_model.dart';
import '../gui/gui_fields.dart';
import '../select/animus_select.dart';

class AttributesEditor extends StatelessWidget {
  const AttributesEditor({required this.value, required this.specs, required this.onChanged, this.bodyType, this.diagnostics = const <Diagnostic>[], super.key});

  /// The `attributes` field as written.
  final Object? value;

  /// The attributes the schema knows.
  final List<AttributeSpec> specs;

  /// The new field, ids bare, or null to drop it.
  final void Function(JsonMap? attributes) onChanged;
  final List<Diagnostic> diagnostics;

  /// The NPC's body type, which orders the add dropdown and marks what has
  /// no effect on it; null for a group, which has no body.
  final String? bodyType;

  @override
  Widget build(BuildContext context) {
    final Map<String, AttributeSpec> table = <String, AttributeSpec>{for (final AttributeSpec spec in specs) spec.id: spec};
    final JsonMap set = bareAttributes(value);
    final List<SelectOption> options = attributeOptions(specs, set, bodyType);
    return dom.div(classes: 'animus-npc-attributes', attributes: const <String, String>{'data-npc': 'attributes'}, <Widget>[
      if (set.isEmpty) const dom.p(classes: 'animus-muted', <Widget>[Component.text('No attributes set: the body has the vanilla defaults.')]),
      for (final MapEntry<String, Object?> entry in set.entries) _row(entry.key, entry.value, table[entry.key]),
      AnimusSelect(
        value: null,
        options: options,
        hiddenLabel: 'Attribute to add',
        placeholder: options.every((SelectOption option) => option.disabled) ? 'Every attribute is set' : 'Add an attribute',
        disabled: options.every((SelectOption option) => option.disabled),
        onChanged: (String id) {
          final AttributeSpec? spec = table[id];
          if (spec != null && !set.containsKey(id)) onChanged(withAttribute(value, id, spec.defaultValue));
        },
      ),
    ]);
  }

  /// Diagnostics for [id], whichever way the definition spells it.
  List<Diagnostic> _diagnostics(String id) => <Diagnostic>[
        for (final String key in asMap(value).keys)
          if (bareAttributeId(key) == id) ...diagnosticsAt(diagnostics, childPath(r'$.attributes', key)),
      ];

  Widget _row(String id, Object? current, AttributeSpec? spec) {
    final String inputId = guiPathId('attributes.$id');
    final List<Diagnostic> found = _diagnostics(id);
    final String? local = spec == null ? 'not an attribute the schema lists; the server refuses it' : spec.problem(current);
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
              value: current is num ? current : null,
              integer: false,
              label: '$id value',
              placeholder: spec == null ? null : formatAttribute(spec.defaultValue),
              onChanged: (num? next) {
                if (next != null) onChanged(withAttribute(value, id, next));
              },
            ),
            if (spec != null)
              dom.span(classes: 'animus-npc-attribute-range', attributes: const <String, String>{'data-attribute-hint': ''}, <Widget>[
                Component.text('${spec.range}, vanilla ${formatAttribute(spec.defaultValue)}'),
              ]),
            GuiIconButton(
              icon: ArcaneIcon.x(size: IconSize.sm),
              label: spec == null ? 'Remove $id' : 'Reset $id to vanilla ${formatAttribute(spec.defaultValue)}',
              onPressed: () => onChanged(withAttribute(value, id, null)),
            ),
          ]),
        ),
      ],
    );
  }
}
