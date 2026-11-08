/// The Terrain toggle and its radius, shared by the NPC stage and the 3D
/// route view.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../gui/gui_fields.dart';
import 'terrain_window.dart';

class TerrainControls extends StatelessWidget {
  const TerrainControls({required this.id, required this.on, required this.radius, required this.onToggle, required this.onRadius, this.disabled = false, super.key});

  /// Prefixes the radius dropdown's id.
  final String id;
  final bool on;
  final int radius;
  final void Function(bool on) onToggle;
  final void Function(int radius) onRadius;

  /// No server terrain to show: the toggle is off and cannot turn on.
  final bool disabled;

  @override
  Widget build(BuildContext context) => dom.div(classes: 'animus-terrain-controls', attributes: const <String, String>{'role': 'group', 'aria-label': 'Terrain'}, <Widget>[
        Button(
          label: 'Terrain',
          size: ButtonSize.small,
          variant: ButtonVariant.ghost,
          disabled: disabled,
          attributes: <String, String>{'aria-pressed': '${on && !disabled}', 'data-terrain-toggle': ''},
          onPressed: () => onToggle(!on),
        ),
        if (on && !disabled)
          dom.label(classes: 'animus-inline-field', <Widget>[
            const dom.span(<Widget>[Component.text('Radius')]),
            GuiSelect(
              id: '$id-radius',
              value: '$radius',
              options: <String>[for (final int option in terrainRadii) '$option'],
              labels: <String, String>{for (final int option in terrainRadii) '$option': '$option blocks'},
              hiddenLabel: 'Terrain radius',
              onChanged: (String? value) => onRadius(int.tryParse(value ?? '') ?? radius),
            ),
          ]),
      ]);
}
