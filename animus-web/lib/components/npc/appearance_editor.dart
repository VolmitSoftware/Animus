/// Typed editors for how an NPC's body looks beyond skin and equipment: the
/// hologram text above it with a colored preview, and an armor stand's parts
/// and pose angles. Style fields come from the NPC schema, so their bounds
/// match the server's.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_text.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/npc/npc_appearance.dart';
import '../../logic/suggest/field_profile.dart';
import '../../mc/npc/mc_text.dart';
import '../../mc/npc/npc_look.dart';
import '../../model/animus_document.dart';
import '../../ui/action_row.dart';
import '../../ui/confirm_dialog.dart';
import '../../ui/field.dart';
import '../../ui/menu.dart';
import '../../ui/mode.dart';
import '../gui/gui_fields.dart';
import '../inspect/inspect_fields.dart';
import '../suggest/autocomplete_input.dart';
import '../../ui/number_input.dart';
import 'npc_layout.dart';
import '../../ui/stack.dart';
import '../gui/gui_stack.dart';
import '../../ui/problem_text.dart';

/// The definition's `hologram`: lines of text above the body. Simple shows
/// each line as it looks in game with Edit beside it (a gradient's codes
/// are unreadable as text), its style carried on from the lines above while
/// they share one display; Advanced edits the codes directly.
class HologramLinesEditor extends StatefulWidget {
  const HologramLinesEditor({required this.value, required this.schema, required this.onChanged, this.diagnostics = const <Diagnostic>[], super.key});

  /// The `hologram` object, or null.
  final Object? value;

  /// The NPC schema, for the style fields.
  final SchemaView schema;

  /// The whole new `hologram`, null to remove it.
  final void Function(Object? value) onChanged;
  final List<Diagnostic> diagnostics;

  @override
  State<HologramLinesEditor> createState() => _HologramLinesEditorState();
}

class _HologramLinesEditorState extends State<HologramLinesEditor> {
  /// The lines open as text in Simple.
  final Set<int> _editing = <int>{};

  /// One line's rows as the game draws them, on its own plate.
  static Widget _plate(List<List<McTextRun>> rows, int index) => dom.div(
        classes: 'animus-hologram-preview',
        attributes: <String, String>{'aria-label': 'Hologram line ${index + 1}', 'data-hologram-line': '${index + 1}'},
        <Widget>[for (final List<McTextRun> row in rows) dom.div(classes: 'animus-hologram-preview-line', <Widget>[GuiStyledText.runs(row)])],
      );

  @override
  Widget build(BuildContext context) {
    final Object? value = component.value;
    final SchemaView schema = component.schema;
    final void Function(Object? value) onChanged = component.onChanged;
    final List<Diagnostic> diagnostics = component.diagnostics;
    final bool advanced = ModeScope.advanced(context);
    if (value == null) {
      return ActionRow(
        label: 'Hologram',
        leading: <Widget>[mutedText('No lines.')],
        secondary: <RowAction>[RowAction('Add text', () => onChanged(<String, Object?>{'lines': <Object?>['']}), icon: ArcaneIcon.plus())],
      );
    }
    final JsonMap hologram = asMap(value);
    final List<Object?> lines = asList(hologram['lines']);
    final List<List<List<McTextRun>>> rows =
        guiDisplayRows(<String>[for (final Object? line in lines) hologramLineText(line)], separate: NpcLook.separateRowsOf(hologram), sample: false);
    void putLines(List<Object?> next) => onChanged(<String, Object?>{...hologram, 'lines': next});
    // A move or a removal renumbers the lines: close them all.
    void reorder(List<Object?> next) {
      _editing.clear();
      putLines(next);
    }
    final Map<String, Object?> node = schema.at(<JsonStep>['hologram'], <String, Object?>{'hologram': hologram}) ?? const <String, Object?>{};
    final bool full = lines.length >= hologramMaxLines;
    return dom.div(
      styles: stackStyles(),
      attributes: const <String, String>{'role': 'group', 'aria-label': 'Hologram'},
      <Widget>[
        if (advanced)
          dom.div(
            classes: 'animus-hologram-preview',
            attributes: const <String, String>{'aria-label': 'Hologram preview'},
            <Widget>[
              if (lines.isEmpty) const dom.span(classes: 'animus-hologram-preview-line', <Widget>[Component.text('No lines yet.')]),
              for (final List<List<McTextRun>> line in rows)
                for (final List<McTextRun> row in line) dom.div(classes: 'animus-hologram-preview-line', <Widget>[GuiStyledText.runs(row)]),
            ],
          ),
        for (int index = 0; index < lines.length; index++)
          if (hologramLineText(lines[index]) case final String text)
            if (advanced || text.trim().isEmpty || _editing.contains(index))
              ActionRow(
                key: ValueKey<String>('hologram-line-$index'),
                label: 'Hologram line ${index + 1}',
                // The input fills the line, as the hologram's own Lines tab does.
                leading: <Widget>[
                  Expanded(
                    child: AutocompleteInput(
                      value: text,
                      profile: FieldProfile.richText,
                      label: 'Hologram line ${index + 1}',
                      onChanged: (String next) => putLines(setHologramLineText(lines, index, next)),
                    ),
                  ),
                ],
                secondary: <RowAction>[
                  if (!advanced && _editing.contains(index))
                    RowAction('Done', () => setState(() => _editing.remove(index)), attributes: <String, String>{'aria-label': 'Done editing line ${index + 1}'}),
                  RowAction('Move line ${index + 1} up', index == 0 ? null : () => reorder(moveItem(lines, index, index - 1)), icon: ArcaneIcon.arrowUp(), iconOnly: true),
                  RowAction('Remove line ${index + 1}', () => reorder(<Object?>[...lines]..removeAt(index)), icon: ArcaneIcon.x(), iconOnly: true),
                ],
              )
            else
              ActionRow(
                key: ValueKey<String>('hologram-line-$index'),
                label: 'Hologram line ${index + 1}',
                leading: <Widget>[_plate(rows[index], index)],
                secondary: <RowAction>[
                  RowAction('Edit', () => setState(() => _editing.add(index)), attributes: <String, String>{'aria-label': 'Edit line ${index + 1}'}),
                  RowAction('Move line ${index + 1} up', index == 0 ? null : () => reorder(moveItem(lines, index, index - 1)), icon: ArcaneIcon.arrowUp(), iconOnly: true),
                  RowAction('Remove line ${index + 1}', () => reorder(<Object?>[...lines]..removeAt(index)), icon: ArcaneIcon.x(), iconOnly: true),
                ],
              ),
        for (final Diagnostic problem in diagnosticsAt(diagnostics, r'$.hologram.lines', nested: true))
          problemText(problem.message, severity: problem.severity),
        ActionRow(
          label: 'Hologram lines',
          more: <AppMenuEntry>[
            AppMenuEntry('Remove hologram', () async {
              if (await askToConfirm(title: 'Remove the hologram?', message: 'Undo brings it back.', confirmLabel: 'Remove', destructive: true)) onChanged(null);
            }, destructive: true),
          ],
          secondary: <RowAction>[RowAction(full ? 'At most $hologramMaxLines lines' : 'Add line', full ? null : () => putLines(<Object?>[...lines, '']), icon: ArcaneIcon.plus())],
        ),
        if (node.isNotEmpty)
          MoreFields(label: 'Text style', children: <Widget>[
            InspectObject(
              schema: schema,
              node: node,
              value: hologram,
              path: r'$.hologram',
              diagnostics: diagnostics,
              skip: const <String>{'lines'},
              onChanged: (JsonMap next) => onChanged(next),
            ),
          ]),
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
      styles: stackStyles(),
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
            onChanged: put,
          ),
        for (final String part in armorStandParts)
          dom.div(
            key: ValueKey<String>('part:$part'),
            attributes: <String, String>{'data-part': part},
            <Widget>[
              Field(
                label: _partNames[part]!,
                problems: diagnosticsAt(diagnostics, childPath(r'$.armorStand', part)),
                child: equalLine(<Widget>[
                  for (int axis = 0; axis < 3; axis++)
                    namedAbove(
                      const <String>['X', 'Y', 'Z'][axis],
                      NumberInput(
                        value: partAngles(stand, part)?[axis] ?? 0,
                        integer: false,
                        label: '${_partNames[part]} ${const <String>['x', 'y', 'z'][axis]} degrees',
                        onChanged: (num? degrees) => put(setPartAngle(stand, part, axis, degrees ?? 0)),
                      ),
                    ),
                ]),
              ),
            ],
          ),
      ],
    );
  }
}
