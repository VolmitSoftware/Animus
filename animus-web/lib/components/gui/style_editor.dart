/// The display style shared by menu icons and holograms (core's HoloParts
/// iconDisplayStyle): eighteen fields in four collapsed groups. Unset fields
/// keep the surface's default, which the help line names.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../model/animus_document.dart';
import 'component_editor.dart';
import 'gui_fields.dart';

enum _StyleKind { choice, boolean, integer, number, colour }

final class _StyleField {
  const _StyleField(this.key, this.kind, this.help, {this.choices = const <String>[]});

  final String key;
  final _StyleKind kind;
  final String help;
  final List<String> choices;
}

const List<(String, List<_StyleField>)> _groups = <(String, List<_StyleField>)>[
  ('Facing and size', <_StyleField>[
    _StyleField('billboard', _StyleKind.choice, 'How it turns to face the viewer.', choices: <String>['fixed', 'vertical', 'horizontal', 'center']),
    _StyleField('scaleX', _StyleKind.number, '0.01 to 64; 1 by default.'),
    _StyleField('scaleY', _StyleKind.number, '0.01 to 64; 1 by default.'),
    _StyleField('scaleZ', _StyleKind.number, '0.01 to 64; 1 by default.'),
    _StyleField('viewRange', _StyleKind.number, '0.01 to 64; 1 by default, a multiple of the entity view distance.'),
  ]),
  ('Text', <_StyleField>[
    _StyleField('textAlignment', _StyleKind.choice, 'center by default.', choices: <String>['center', 'left', 'right']),
    _StyleField('lineWidth', _StyleKind.integer, '1 to 16384 pixels before a line wraps.'),
    _StyleField('textOpacity', _StyleKind.integer, '0 to 255; 255 is opaque.'),
    _StyleField('backgroundArgb', _StyleKind.colour, '#AARRGGBB; #00000000 shows no background.'),
    _StyleField('shadow', _StyleKind.boolean, 'A drop shadow under the text.'),
    _StyleField('seeThrough', _StyleKind.boolean, 'Visible through blocks.'),
  ]),
  ('Light, shadow and glow', <_StyleField>[
    _StyleField('blockLight', _StyleKind.integer, '0 to 15; set together with sky light.'),
    _StyleField('skyLight', _StyleKind.integer, '0 to 15; set together with block light.'),
    _StyleField('shadowRadius', _StyleKind.number, '0 to 64 blocks.'),
    _StyleField('shadowStrength', _StyleKind.number, '0 to 1.'),
    _StyleField('glowColor', _StyleKind.colour, '#AARRGGBB; the low 24 bits colour the glow.'),
  ]),
  ('Culling', <_StyleField>[
    _StyleField('cullingWidth', _StyleKind.number, '0 to 4096. Checked; the server does not use it yet.'),
    _StyleField('cullingHeight', _StyleKind.number, '0 to 4096. Checked; the server does not use it yet.'),
  ]),
];

/// Every style key, for tests and copies.
List<String> get displayStyleKeys => <String>[
      for (final (String _, List<_StyleField> fields) in _groups)
        for (final _StyleField field in fields) field.key,
    ];

class DisplayStyleEditor extends StatelessWidget {
  const DisplayStyleEditor({required this.style, required this.onChanged, required this.path, required this.diagnostics, this.title = 'Style', this.note, super.key});

  final JsonMap style;

  /// The new style, or null when every field is unset.
  final void Function(JsonMap? style) onChanged;
  final String path;
  final List<Diagnostic> diagnostics;
  final String title;

  /// A line on what the surface's defaults are.
  final String? note;

  void _set(String key, Object? value) {
    final JsonMap next = withField(style, key, value);
    onChanged(next.isEmpty ? null : next);
  }

  Widget _field(_StyleField field) {
    final String fieldPath = childPath(path, field.key);
    final String id = guiPathId(fieldPath);
    final Object? value = style[field.key];
    final String label = humanizeKey(field.key);
    final Widget child = switch (field.kind) {
      _StyleKind.choice => GuiSelect(id: id, value: asString(value), options: field.choices, allowNone: true, noneLabel: 'default', onChanged: (String? v) => _set(field.key, v)),
      _StyleKind.boolean => GuiSelect(
          id: id,
          value: value == null ? null : '$value',
          options: const <String>['true', 'false'],
          allowNone: true,
          noneLabel: 'default',
          labels: const <String, String>{'true': 'On', 'false': 'Off'},
          onChanged: (String? v) => _set(field.key, v == null ? null : v == 'true'),
        ),
      _StyleKind.integer => GuiNumberInput(id: id, value: asInt(value), label: label, onChanged: (num? v) => _set(field.key, v)),
      _StyleKind.number => GuiNumberInput(id: id, value: asDouble(value), integer: false, label: label, onChanged: (num? v) => _set(field.key, v)),
      _StyleKind.colour => GuiTextInput(id: id, value: asString(value) ?? '', label: label, placeholder: '#AARRGGBB', onChanged: (String v) => _set(field.key, v.trim().isEmpty ? null : v.trim())),
    };
    return GuiRow(label: label, forId: id, help: field.help, problems: diagnosticsAt(diagnostics, fieldPath), child: child);
  }

  @override
  Widget build(BuildContext context) {
    final int set = style.length;
    return GuiSection(
      title: title,
      count: set == 0 ? null : set,
      open: diagnosticsAt(diagnostics, path, nested: true).isNotEmpty,
      children: <Widget>[
        if (note != null) dom.p(classes: 'animus-muted', <Widget>[Component.text(note!)]),
        for (final (String group, List<_StyleField> fields) in _groups)
          GuiSection(
            title: group,
            count: fields.where((_StyleField field) => style.containsKey(field.key)).length,
            open: false,
            children: <Widget>[for (final _StyleField field in fields) _field(field)],
          ),
      ],
    );
  }
}
