/// The bone tree: one tree item per bone, indented by depth, with the tree
/// keyboard model (Up and Down move, Home and End jump, Right goes to the
/// first child, Left to the parent; selection follows focus), and the add,
/// delete, rename and parent controls for the selected bone.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../config/docs_links.dart';
import '../../logic/rig_edit.dart';
import '../gui/gui_fields.dart';
import '../info_tip.dart';
import 'rig_inspector.dart';

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
  final void Function(String bone) onDelete;
  final void Function(String from, String to) onRename;
  final RigChange onEdit;

  @override
  State<RigTree> createState() => _RigTreeState();
}

class _RigTreeState extends State<RigTree> {
  String? _rename;
  String? _renaming;

  static String _visualLabel(Object? visual) {
    if (visual is! JsonMap || visual['type'] == 'none') return 'pivot';
    return '${visual['type']}';
  }

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
    if (_renaming != selected) {
      _renaming = selected;
      _rename = null;
    }
    final String draft = _rename ?? selected ?? '';
    return dom.div(classes: 'animus-rig-tree', <Widget>[
      dom.h3(id: 'animus-rig-tree-heading', <Widget>[withTip(Component.text('Bones (${bones.length})'), 'The rig\'s bones, children under their parent. Arrow keys move the selection.', docs: Docs.rigs)]),
      dom.ul(attributes: const <String, String>{'role': 'tree', 'aria-labelledby': 'animus-rig-tree-heading'}, <Widget>[
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
            styles: dom.Styles(raw: <String, String>{'padding-left': '${0.5 + (boneDepth(rig, '${bone['id']}') - 1) * 0.9}rem'}),
            events: <String, void Function(web.Event)>{
              'click': (web.Event _) => component.onSelect('${bone['id']}'),
              'keydown': (web.Event event) => _key(event as web.KeyboardEvent, order, '${bone['id']}'),
            },
            <Widget>[
              dom.span(<Widget>[Component.text('${bone['id']}')]),
              dom.span(classes: 'animus-muted', <Widget>[Component.text(_visualLabel(bone['visual']))]),
            ],
          ),
      ]),
      dom.div(classes: 'animus-rig-actions', <Widget>[
        Button.secondary(
          label: selected == null ? 'Add bone' : 'Add child',
          icon: ArcaneIcon.plus(),
          size: ButtonSize.small,
          disabled: bones.length >= maximumBones,
          onPressed: component.onAdd,
        ),
        if (selected != null)
          Button.ghost(
            label: 'Delete bone',
            size: ButtonSize.small,
            disabled: bones.length <= 1,
            onPressed: () => component.onDelete(selected),
          ),
      ]),
      if (bones.length >= maximumBones) const dom.p(classes: 'animus-muted', <Widget>[Component.text('A rig holds at most 64 bones.')]),
      if (selected != null) ...<Widget>[
        dom.label(classes: 'animus-rig-field', <Widget>[
          const dom.span(<Widget>[Component.text('Name')]),
          dom.div(classes: 'animus-row', <Widget>[
            TextInput(
              size: ComponentSize.sm,
              value: draft,
              attributes: const <String, String>{'aria-label': 'Bone name'},
              onChanged: (String value) => setState(() => _rename = value),
              onSubmitted: (String value) => component.onRename(selected, value),
            ),
            Button.secondary(
              label: 'Rename',
              size: ButtonSize.small,
              disabled: draft == selected,
              onPressed: () => component.onRename(selected, draft),
            ),
          ]),
        ]),
        dom.label(classes: 'animus-rig-field', <Widget>[
          dom.span(<Widget>[withTip(const Component.text('Parent'), 'The bone this one moves with. A bone cannot sit under its own children.', docs: Docs.rigs)]),
          GuiSelect(
            value: parentOf(boneById(rig, selected) ?? const <String, Object?>{}) ?? '',
            options: <String>[
              for (final JsonMap bone in bones)
                if (!under.contains(bone['id'])) '${bone['id']}',
            ],
            allowNone: true,
            noneLabel: '(rig origin)',
            hiddenLabel: 'Parent',
            onChanged: (String? value) => component.onEdit((JsonMap json) => reparentBone(json, selected, value)),
          ),
        ]),
      ],
    ]);
  }
}
