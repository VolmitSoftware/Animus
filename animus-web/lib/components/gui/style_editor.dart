/// The display style shared by menu icons and holograms (core's HoloParts
/// iconDisplayStyle): eighteen fields in four groups, folded into one
/// section inside a larger form, or laid out open as a tab of their own.
/// Unset fields keep the surface's default, which the help line names.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../logic/gui/gui_json.dart';
import '../../model/animus_document.dart';
import 'component_editor.dart';
import '../../ui/section.dart';
import 'gui_fields.dart';
import '../select/animus_select.dart';
import '../suggest/autocomplete_input.dart';
import '../../ui/field.dart';
import '../../logic/suggest/field_profile.dart';
import '../../ui/number_input.dart';
import '../../ui/stack.dart';

enum _StyleKind { choice, boolean, integer, number, color }

final class _StyleField {
  const _StyleField(this.key, this.kind, this.help, {this.choices = const <String>[]});

  final String key;
  final _StyleKind kind;
  final String help;
  final List<String> choices;
}

const List<(String, String, List<_StyleField>)> _groups = <(String, String, List<_StyleField>)>[
  ('Facing and size', 'Whether it turns to face the viewer, its scale and how far away it still shows.', <_StyleField>[
    _StyleField('billboard', _StyleKind.choice, 'How it faces the viewer', choices: <String>['fixed', 'vertical', 'horizontal', 'center']),
    _StyleField('scaleX', _StyleKind.number, '0.01 to 64, default 1'),
    _StyleField('scaleY', _StyleKind.number, '0.01 to 64, default 1'),
    _StyleField('scaleZ', _StyleKind.number, '0.01 to 64, default 1'),
    _StyleField('viewRange', _StyleKind.number, 'Multiple of view distance, default 1'),
  ]),
  ('Text', 'Alignment, wrap width, opacity and background of text rows.', <_StyleField>[
    _StyleField('textAlignment', _StyleKind.choice, 'Default center', choices: <String>['center', 'left', 'right']),
    _StyleField('lineWidth', _StyleKind.integer, 'Wrap width, 1 to 16384 px'),
    _StyleField('textOpacity', _StyleKind.integer, '0 to 255, 255 is opaque'),
    _StyleField('backgroundArgb', _StyleKind.color, '#AARRGGBB, #00000000 for none'),
    _StyleField('shadow', _StyleKind.boolean, 'Text drop shadow'),
    _StyleField('seeThrough', _StyleKind.boolean, 'Visible through blocks'),
  ]),
  ('Light, shadow and glow', 'Fixed light levels, the shadow under it and the glow outline.', <_StyleField>[
    _StyleField('blockLight', _StyleKind.integer, '0 to 15, set with sky light'),
    _StyleField('skyLight', _StyleKind.integer, '0 to 15, set with block light'),
    _StyleField('shadowRadius', _StyleKind.number, '0 to 64 blocks'),
    _StyleField('shadowStrength', _StyleKind.number, '0 to 1'),
    _StyleField('glowColor', _StyleKind.color, '#AARRGGBB, low 24 bits used'),
  ]),
  ('Culling', 'The box the client uses to skip drawing it. Not used yet.', <_StyleField>[
    _StyleField('cullingWidth', _StyleKind.number, '0 to 4096, not used yet'),
    _StyleField('cullingHeight', _StyleKind.number, '0 to 4096, not used yet'),
  ]),
];

/// Every style key, for tests and copies.
List<String> get displayStyleKeys => <String>[
      for (final (String _, String _, List<_StyleField> fields) in _groups)
        for (final _StyleField field in fields) field.key,
    ];

class DisplayStyleEditor extends StatelessWidget {
  const DisplayStyleEditor({
    required this.style,
    required this.onChanged,
    required this.path,
    required this.diagnostics,
    this.title = 'Style',
    this.note,
    this.whole = false,
    super.key,
  });

  final JsonMap style;

  /// The new style, or null when every field is unset, and the field that
  /// changed.
  final void Function(JsonMap? style, String field) onChanged;
  final String path;
  final List<Diagnostic> diagnostics;
  final String title;

  /// A line on what the surface's defaults are.
  final String? note;

  /// The style is a tab of its own: the groups sit on the page, the common
  /// two and any in use open, instead of inside one folded section.
  final bool whole;

  void _set(String key, Object? value) {
    final JsonMap next = withField(style, key, value);
    onChanged(next.isEmpty ? null : next, key);
  }

  Widget _field(_StyleField field) {
    final String fieldPath = childPath(path, field.key);
    final String id = guiPathId(fieldPath);
    final Object? value = style[field.key];
    final String label = humanizeKey(field.key);
    final Widget child = switch (field.kind) {
      _StyleKind.choice => AnimusSelect.values(
          id: id,
          value: asString(value),
          values: field.choices,
          labels: <String, String>{for (final String choice in field.choices) choice: '${choice[0].toUpperCase()}${choice.substring(1)}'},
          allowNone: true,
          noneLabel: 'Default',
          onChanged: (String? v) => _set(field.key, v),
        ),
      _StyleKind.boolean => AnimusSelect.values(
          id: id,
          value: value == null ? null : '$value',
          values: const <String>['true', 'false'],
          allowNone: true,
          noneLabel: 'Default',
          labels: const <String, String>{'true': 'On', 'false': 'Off'},
          onChanged: (String? v) => _set(field.key, v == null ? null : v == 'true'),
        ),
      _StyleKind.integer => NumberInput(id: id, value: asInt(value), label: label, onChanged: (num? v) => _set(field.key, v)),
      _StyleKind.number => NumberInput(id: id, value: asDouble(value), integer: false, label: label, onChanged: (num? v) => _set(field.key, v)),
      _StyleKind.color => AutocompleteInput(id: id, value: asString(value) ?? '', label: label, placeholder: '#AARRGGBB', onChanged: (String v) => _set(field.key, v.trim().isEmpty ? null : v.trim()), profile: FieldProfile.none),
    };
    return Field(label: label, forId: id, help: field.help, problems: diagnosticsAt(diagnostics, fieldPath), child: child);
  }

  @override
  Widget build(BuildContext context) {
    final List<Widget> groups = <Widget>[
      for (final (int index, (String group, String tip, List<_StyleField> fields)) in _groups.indexed)
        AppSection(
          title: group,
          tip: tip,
          count: fields.where((_StyleField field) => style.containsKey(field.key)).length,
          open: whole && (index < 2 || fields.any((_StyleField field) => style.containsKey(field.key) || diagnosticsAt(diagnostics, childPath(path, field.key)).isNotEmpty)),
          children: <Widget>[for (final _StyleField field in fields) _field(field)],
        ),
    ];
    if (whole) return stack(spacing: 16, <Widget>[if (note != null) mutedText(note!), ...groups]);
    final int set = style.length;
    return AppSection(
      title: title,
      tip: note == null ? 'How it is drawn. Empty fields keep the default.' : 'How it is drawn. Empty fields keep the default. ${note!}',
      count: set == 0 ? null : set,
      open: diagnosticsAt(diagnostics, path, nested: true).isNotEmpty,
      children: groups,
    );
  }
}
