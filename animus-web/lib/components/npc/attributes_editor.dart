/// The definition's `attributes` (the Advanced "Attributes" tab): one number
/// per attribute it sets, named in words with its id in the (i) tip, its
/// range and vanilla default as the hint, a reset that removes it, and an
/// "Add attribute" dropdown grouped and ordered for the body. Max health and
/// armor are set in the Combat tab, so the dropdown leaves them out; a value
/// already written stays here, saying when a Combat setting replaces it.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/gui/gui_json.dart';
import '../../logic/npc/npc_attributes.dart';
import '../../logic/select/select_model.dart';
import '../../model/animus_document.dart';
import '../../ui/field.dart';
import '../../ui/icon_button.dart';
import '../gui/gui_fields.dart';
import '../select/animus_select.dart';
import '../../ui/number_input.dart';
import 'npc_layout.dart';

const String _addId = 'animus-attribute-add';

class AttributesEditor extends StatelessWidget {
  const AttributesEditor({
    required this.value,
    required this.specs,
    required this.onChanged,
    this.bodyType,
    this.diagnostics = const <Diagnostic>[],
    this.modules = const <String, Object?>{},
    super.key,
  });

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

  /// The definition's `modules`: a row says when a combat or health
  /// setting replaces its attribute.
  final JsonMap modules;

  @override
  Widget build(BuildContext context) {
    final Map<String, AttributeSpec> table = <String, AttributeSpec>{for (final AttributeSpec spec in specs) spec.id: spec};
    final JsonMap set = bareAttributes(value);
    final List<SelectOption> options = attributeOptions(specs, set, bodyType);
    final Map<String, String> overrides = attributeOverrides(modules);
    final bool full = options.every((SelectOption option) => option.disabled);
    return dom.div(styles: stackStyles(), attributes: const <String, String>{'data-npc': 'attributes'}, <Widget>[
      if (set.isEmpty) mutedLine('None set. Every attribute has its normal value.'),
      for (final MapEntry<String, Object?> entry in set.entries) _row(entry.key, entry.value, table[entry.key], overrides[entry.key]),
      Field(
        label: 'Add attribute',
        forId: _addId,
        child: AnimusSelect(
          id: _addId,
          value: null,
          options: options,
          field: 'Add attribute',
          placeholder: full ? 'Every attribute is set' : 'Pick an attribute',
          disabled: full,
          onChanged: (String id) {
            final AttributeSpec? spec = table[id];
            if (spec != null && !set.containsKey(id)) onChanged(withAttribute(value, id, spec.defaultValue));
          },
        ),
      ),
    ]);
  }

  /// Diagnostics for [id], whichever way the definition spells it.
  List<Diagnostic> _diagnostics(String id) => <Diagnostic>[
        for (final String key in asMap(value).keys)
          if (bareAttributeId(key) == id) ...diagnosticsAt(diagnostics, childPath(r'$.attributes', key)),
      ];

  Widget _row(String id, Object? current, AttributeSpec? spec, String? replaced) {
    final String inputId = guiPathId('attributes.$id');
    final String label = attributeLabel(id);
    final List<Diagnostic> found = _diagnostics(id);
    final String? local = spec == null ? 'Not an attribute the schema lists; the server refuses it' : spec.problem(current);
    return dom.div(
      key: ValueKey<String>('attribute:$id'),
      attributes: <String, String>{'data-attribute': id},
      <Widget>[
        Field(
          label: label,
          forId: inputId,
          tip: <String>[if (spec?.description case final String about when about.isNotEmpty) about, 'Id: $attributeNamespace$id'].join(' '),
          hint: <String>[if (spec != null) attributeRange(spec), ?replaced].join('. '),
          problems: <Diagnostic>[
            ...found,
            if (found.isEmpty && local != null) Diagnostic(DiagnosticSeverity.error, childPath(r'$.attributes', id), local, code: 'attribute'),
          ],
          child: Row(gapSize: Gap.sm, children: <Widget>[
            NumberInput(
              id: inputId,
              value: current is num ? current : null,
              integer: false,
              digits: 8,
              label: label,
              placeholder: spec == null ? null : formatAttribute(spec.defaultValue),
              onChanged: (num? next) {
                if (next != null) onChanged(withAttribute(value, id, next));
              },
            ),
            AppIconButton(
              icon: ArcaneIcon.x(),
              label: spec == null ? 'Remove $label' : 'Reset ${label.toLowerCase()} to vanilla ${formatAttribute(spec.defaultValue)}',
              onPressed: () => onChanged(withAttribute(value, id, null)),
            ),
          ]),
        ),
      ],
    );
  }
}
