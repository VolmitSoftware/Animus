/// The bone tree: one tree item per bone, indented by depth, with the tree
/// keyboard model (Up and Down move, Home and End jump, Right goes to the
/// first child, Left to the parent; selection follows focus), Add and
/// Delete in its header row, and the selected bone's name (saved when the
/// field is left or Enter is pressed) and parent.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component, ValueKey;
import 'package:web/web.dart' as web;

import '../../config/docs_links.dart';
import '../../logic/rig_edit.dart';
import '../../logic/rig_view.dart';
import '../../ui/action_row.dart';
import '../../ui/field.dart';
import '../../ui/info_tip.dart';
import '../../ui/menu.dart';
import 'rig_inputs.dart';
import 'rig_inspector.dart';
import '../select/animus_select.dart';
import '../npc/npc_layout.dart';
import '../../ui/stack.dart';

class RigTree extends StatefulWidget {
  const RigTree({
    required this.rig,
    required this.selected,
    required this.onSelect,
    required this.onAdd,
    required this.onDelete,
    required this.onRename,
    required this.onEdit,
    super.key,
  });

  final JsonMap rig;
  final String? selected;
  final void Function(String bone) onSelect;
  final void Function() onAdd;

  /// Asks, then deletes [bone].
  final void Function(String bone) onDelete;

  /// Renames a bone; returns why it cannot, or null once it is done.
  final String? Function(String from, String to) onRename;
  final RigChange onEdit;

  @override
  State<RigTree> createState() => _RigTreeState();
}

class _RigTreeState extends State<RigTree> {
  static String _visualLabel(Object? visual) => switch (visual is JsonMap ? visual['type'] : null) {
        'item' => 'Item',
        'block' => 'Block',
        'text' => 'Text',
        _ => 'Pivot',
      };

  void _focus(String bone) {
    component.onSelect(bone);
    context.binding.addPostFrameCallback(() {
      final web.Element? item = web.document.querySelector('.animus-rig-tree [data-bone="$bone"]');
      if (item != null) (item as web.HTMLElement).focus();
    });
  }

  void _key(web.KeyboardEvent event, List<String> order, String bone) {
    final JsonMap rig = component.rig;
    final int at = order.indexOf(bone);
    String? next;
    switch (event.key) {
      case 'ArrowDown':
        if (at + 1 < order.length) next = order[at + 1];
      case 'ArrowUp':
        if (at > 0) next = order[at - 1];
      case 'Home':
        next = order.first;
      case 'End':
        next = order.last;
      case 'ArrowRight':
        for (final JsonMap candidate in bonesOf(rig)) {
          if (parentOf(candidate) == bone) {
            next = '${candidate['id']}';
            break;
          }
        }
      case 'ArrowLeft':
        next = parentOf(boneById(rig, bone) ?? const <String, Object?>{});
      case 'Enter':
      case ' ':
        next = bone;
      default:
        return;
    }
    event.preventDefault();
    if (next != null) _focus(next);
  }

  @override
  Widget build(BuildContext context) {
    final JsonMap rig = component.rig;
    final List<JsonMap> bones = bonesOf(rig);
    final List<String> order = <String>[for (final JsonMap bone in bones) '${bone['id']}'];
    final String? selected = component.selected;
    final String focusable = selected != null && order.contains(selected) ? selected : (order.isEmpty ? '' : order.first);
    final Set<String> under = selected == null ? const <String>{} : subtree(rig, selected).toSet();
    return dom.div(classes: 'animus-rig-tree', styles: stackStyles(), <Widget>[
      ActionRow(
        dense: true,
        label: 'Bones',
        moreId: 'rig-bone-more',
        leading: <Widget>[
          dom.h3(id: 'animus-rig-tree-heading', styles: groupHeadingStyles, <Widget>[
            TipLabel(
              label: Component.text('Bones (${bones.length})'),
              tip: 'Children sit under their parent. Arrow keys move the selection. A rig holds at most $maximumBones bones.',
              docs: Docs.rigs,
            ),
          ]),
        ],
        secondary: <RowAction>[
          RowAction(selected == null ? 'Add bone' : 'Add child', bones.length >= maximumBones ? null : component.onAdd, icon: ArcaneIcon.plus()),
        ],
        more: <AppMenuEntry>[
          if (selected != null) AppMenuEntry('Delete bone', bones.length <= 1 ? null : () => component.onDelete(selected), destructive: true),
        ],
      ),
      dom.ul(classes: 'animus-rig-bones', attributes: const <String, String>{'role': 'tree', 'aria-labelledby': 'animus-rig-tree-heading'}, <Widget>[
        for (final JsonMap bone in bones)
          dom.li(
            classes: 'animus-rig-bone${bone['id'] == selected ? ' is-selected' : ''}',
            attributes: <String, String>{
              'role': 'treeitem',
              'aria-level': '${boneDepth(rig, '${bone['id']}')}',
              'aria-selected': '${bone['id'] == selected}',
              'tabindex': bone['id'] == focusable ? '0' : '-1',
              'data-bone': '${bone['id']}',
            },
            styles: dom.Styles(raw: <String, String>{'--depth': '${boneDepth(rig, '${bone['id']}') - 1}'}),
            events: <String, void Function(web.Event)>{
              'click': (web.Event _) => component.onSelect('${bone['id']}'),
              'keydown': (web.Event event) => _key(event as web.KeyboardEvent, order, '${bone['id']}'),
            },
            <Widget>[
              dom.span(<Widget>[Component.text('${bone['id']}')]),
              mutedText(_visualLabel(bone['visual'])),
            ],
          ),
      ]),
      if (selected != null) ...<Widget>[
        CommitField(
          key: ValueKey<String>('bone-name:$selected'),
          label: 'Name',
          id: 'rig-bone-name',
          value: selected,
          onCommit: (String draft) {
            final String? target = boneRenameTarget(selected, draft);
            return target == null ? null : component.onRename(selected, target);
          },
        ),
        Field(
          label: 'Parent',
          forId: 'rig-bone-parent',
          tip: 'The bone this one moves with. A bone cannot sit under its own children.',
          docs: Docs.rigs,
          child: AnimusSelect.values(
            id: 'rig-bone-parent',
            value: parentOf(boneById(rig, selected) ?? const <String, Object?>{}) ?? '',
            values: <String>[
              for (final JsonMap bone in bones)
                if (!under.contains(bone['id'])) '${bone['id']}',
            ],
            allowNone: true,
            noneLabel: 'Rig origin',
            onChanged: (String? value) => component.onEdit((JsonMap json) => reparentBone(json, selected, value)),
          ),
        ),
      ],
    ]);
  }
}
