/// Typed editors for how an NPC's body looks beyond skin and equipment: the
/// hologram text above it with a coloured preview, and an armor stand's parts
/// and pose angles. Style fields come from the NPC schema, so their bounds
/// match the server's.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/npc/npc_appearance.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart';
import '../inspect/inspect_fields.dart';

/// The definition's `hologram`: lines of text above the body.
class HologramLinesEditor extends StatelessWidget {
  const HologramLinesEditor({required this.value, required this.schema, required this.onChanged, this.diagnostics = const <Diagnostic>[], super.key});

  /// The `hologram` object, or null.
  final Object? value;

  /// The NPC schema, for the style fields.
  final SchemaView schema;

  /// The whole new `hologram`, null to remove it.
  final void Function(Object? value) onChanged;
  final List<Diagnostic> diagnostics;

  @override
  Widget build(BuildContext context) {
    if (value == null) {
      return dom.div(classes: 'animus-row', <Widget>[
        const dom.span(classes: 'animus-gui-help', <Widget>[Component.text('No text above the body. The nameplate still shows the name.')]),
        Button.secondary(
          label: 'Add hologram text',
          size: ButtonSize.small,
          onPressed: () => onChanged(<String, Object?>{
            'lines': <Object?>[''],
          }),
        ),
      ]);
    }
    final JsonMap hologram = asMap(value);
    final List<Object?> lines = asList(hologram['lines']);
    void putLines(List<Object?> next) => onChanged(<String, Object?>{...hologram, 'lines': next});
    final Map<String, Object?> node = schema.at(<JsonStep>['hologram'], <String, Object?>{'hologram': hologram}) ?? const <String, Object?>{};
    return dom.div(
      classes: 'animus-hologram-editor',
      attributes: const <String, String>{'role': 'group', 'aria-label': 'Hologram'},
      <Widget>[
        dom.div(
          classes: 'animus-hologram-preview',
          attributes: const <String, String>{'aria-label': 'Hologram preview'},
          <Widget>[
            if (lines.isEmpty) const dom.span(classes: 'animus-muted', <Widget>[Component.text('No lines yet.')]),
            for (final Object? line in lines) dom.div(classes: 'animus-hologram-preview-line', <Widget>[GuiStyledText(hologramLineText(line))]),
          ],
        ),
        for (int index = 0; index < lines.length; index++)
          dom.div(key: ValueKey<String>('hologram-line-$index'), classes: 'animus-gui-line', <Widget>[
            GuiTextInput(
              value: hologramLineText(lines[index]),
              mode: GuiTextMode.plain,
              label: 'Hologram line ${index + 1}',
              placeholder: '&aWelcome',
              onChanged: (String text) => putLines(setHologramLineText(lines, index, text)),
            ),
            GuiIconButton(
              icon: ArcaneIcon.arrowUp(size: IconSize.sm),
              label: 'Move hologram line ${index + 1} up',
              disabled: index == 0,
              onPressed: () => putLines(moveItem(lines, index, index - 1)),
            ),
            GuiIconButton(
              icon: ArcaneIcon.x(size: IconSize.sm),
              label: 'Remove hologram line ${index + 1}',
              onPressed: () => putLines(<Object?>[...lines]..removeAt(index)),
            ),
          ]),
        for (final Diagnostic problem in diagnosticsAt(diagnostics, r'$.hologram.lines', nested: true))
          dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[
            dom.code(<Widget>[Component.text(problem.path)]),
            Component.text(' ${problem.message}'),
          ]),
        dom.div(classes: 'animus-row', <Widget>[
          Button.ghost(
            label: lines.length >= hologramMaxLines ? 'At most $hologramMaxLines lines' : 'Add line',
            size: ButtonSize.small,
            disabled: lines.length >= hologramMaxLines,
            onPressed: () => putLines(<Object?>[...lines, '']),
          ),
          Button.ghost(label: 'Remove hologram', size: ButtonSize.small, onPressed: () => onChanged(null)),
        ]),
        const dom.p(classes: 'animus-gui-help', <Widget>[
          Component.text('& codes colour the text: &a green, &c red, &l bold. Each line holds at most 256 characters.'),
        ]),
        if (node.isNotEmpty)
          InspectObject(
            schema: schema,
            node: node,
            value: hologram,
            path: r'$.hologram',
            diagnostics: diagnostics,
            skip: const <String>{'lines'},
            humanize: true,
            onChanged: (JsonMap next) => onChanged(next),
          ),
      ],
    );
  }
}

/// `armorStand`: which parts show, and each part's `[x, y, z]` degrees.
class ArmorStandEditor extends StatelessWidget {
  const ArmorStandEditor({required this.value, required this.schema, required this.onChanged, this.diagnostics = const <Diagnostic>[], super.key});

  final Object? value;
  final SchemaView schema;
  final void Function(Object? value) onChanged;
  final List<Diagnostic> diagnostics;

  static const Map<String, String> _partNames = <String, String>{
    'head': 'Head',
    'body': 'Body',
    'leftArm': 'Left arm',
    'rightArm': 'Right arm',
    'leftLeg': 'Left leg',
    'rightLeg': 'Right leg',
  };

  @override
  Widget build(BuildContext context) {
    final JsonMap stand = asMap(value);
    void put(JsonMap next) => onChanged(next.isEmpty ? null : next);
    final Map<String, Object?> node =
        schema.at(<JsonStep>['armorStand'], <String, Object?>{'type': 'minecraft:armor_stand', 'armorStand': stand}) ?? const <String, Object?>{};
    return dom.div(
      classes: 'animus-armor-stand',
      attributes: const <String, String>{'role': 'group', 'aria-label': 'Armor stand'},
      <Widget>[
        if (node.isNotEmpty)
          InspectObject(
            schema: schema,
            node: node,
            value: stand,
            path: r'$.armorStand',
            diagnostics: diagnostics,
            skip: armorStandParts.toSet(),
            humanize: true,
            onChanged: put,
          ),
        const dom.p(classes: 'animus-gui-help', <Widget>[Component.text('Pose angles in degrees, -360 to 360. A part at 0, 0, 0 keeps the default pose.')]),
        dom.div(classes: 'animus-armor-stand-parts', <Widget>[
          for (final String part in armorStandParts)
            dom.div(
              key: ValueKey<String>('part:$part'),
              classes: 'animus-armor-stand-part',
              attributes: <String, String>{'data-part': part},
              <Widget>[
                dom.span(classes: 'animus-armor-stand-name', <Widget>[Component.text(_partNames[part]!)]),
                for (int axis = 0; axis < 3; axis++)
                  dom.label(<Widget>[
                    dom.span(<Widget>[
                      Component.text(const <String>['x', 'y', 'z'][axis]),
                    ]),
                    GuiNumberInput(
                      value: partAngles(stand, part)?[axis] ?? 0,
                      integer: false,
                      label: '${_partNames[part]} ${const <String>['x', 'y', 'z'][axis]} degrees',
                      onChanged: (num? degrees) => put(setPartAngle(stand, part, axis, degrees ?? 0)),
                    ),
                  ]),
                for (final Diagnostic problem in diagnosticsAt(diagnostics, childPath(r'$.armorStand', part)))
                  dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
              ],
            ),
        ]),
      ],
    );
  }
}
