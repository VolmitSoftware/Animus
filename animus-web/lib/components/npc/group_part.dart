/// A trait group's own part of the inspector: its id, description, members
/// (with a picker that adds one) and tags.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_text.dart';
import '../../logic/npc/group_members.dart';
import '../../logic/npc/npc_bulk.dart';
import '../../logic/npc/npc_catalog.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../conversation/conversation_attach.dart' show ConversationCommit;
import '../gui/gui_fields.dart';
import 'chip_row.dart';
import 'npc_section.dart';

/// Members listed before the rest fold into a count.
const int _shownMembers = 24;

class GroupPart extends StatefulWidget {
  const GroupPart({
    required this.id,
    required this.file,
    required this.entries,
    required this.groups,
    required this.tags,
    required this.onDescription,
    required this.open,
    required this.onToggle,
    this.diagnostics = const <Diagnostic>[],
    this.onOpen,
    this.onCommit,
    super.key,
  });

  /// The group's id.
  final String id;

  /// The whole group document.
  final JsonMap file;

  /// Every NPC of the workspace.
  final List<NpcEntry> entries;

  /// The workspace's groups by id.
  final Map<String, JsonMap> groups;

  /// The tags row, shared with the NPC inspector.
  final Widget tags;

  /// The new description, or null to drop it.
  final void Function(String? description) onDescription;
  final bool open;
  final void Function(bool open) onToggle;
  final List<Diagnostic> diagnostics;
  final void Function(DocumentKind kind, String id)? onOpen;

  /// Writes the picked NPC; null hides the picker.
  final ConversationCommit? onCommit;

  @override
  State<GroupPart> createState() => _GroupPartState();
}

class _GroupPartState extends State<GroupPart> {
  bool _busy = false;

  /// Adds or removes member [key] in one undoable change.
  Future<void> _member(String key, {required bool assign}) async {
    final ConversationCommit? commit = component.onCommit;
    if (commit == null) return;
    final NpcBulkResult result = assign
        ? addGroupMember(component.entries, component.groups, component.id, key)
        : removeGroupMember(component.entries, component.groups, component.id, key);
    if (result.changes.isEmpty) {
      ArcaneSonner.warning(describeGroupChange(component.id, result, assign: assign));
      return;
    }
    setState(() => _busy = true);
    try {
      await commit(result.changes, '${assign ? 'assign' : 'unassign'} group ${component.id}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final String id = component.id;
    final String description = asString(component.file['description']) ?? '';
    final List<NpcEntry> members = groupMembers(component.entries, id);
    final List<NpcEntry> candidates = groupCandidates(component.entries, id);
    final bool editable = component.onCommit != null;
    final List<NpcEntry> shown = members.take(_shownMembers).toList();
    const String descriptionId = 'animus-group-description';
    const String addId = 'animus-group-add-member';
    return NpcSection(
      id: 'group',
      title: 'Group',
      tip: 'A trait group: shared values its member NPCs take unless they set their own.',
      docs: Docs.groups,
      summary: '$id, ${members.length} NPC${members.length == 1 ? '' : 's'}',
      open: component.open,
      onToggle: component.onToggle,
      children: <Widget>[
        GuiRow(
          label: 'ID',
          help: 'Rename updates member NPCs.',
          child: dom.code(classes: 'animus-inspect-const', <Widget>[Component.text(id)]),
        ),
        GuiRow(
          label: 'Description',
          forId: descriptionId,
          help: 'Shown in /animus groups list.',
          problems: diagnosticsAt(component.diagnostics, r'$.description'),
          child: GuiTextInput(
            id: descriptionId,
            value: description,
            label: 'Group description',
            placeholder: 'What NPCs in this group share',
            onChanged: (String text) => component.onDescription(text.trim().isEmpty ? null : text),
          ),
        ),
        GuiRow(
          label: 'Members',
          forId: editable ? addId : null,
          tip: 'NPCs whose groups list this one. Adding a member appends the group to that NPC, as /animus groups assign does. Assign in bulk from Browse NPCs.',
          child: dom.div(classes: 'animus-chip-line', attributes: const <String, String>{'data-group': 'members'}, <Widget>[
            if (shown.isNotEmpty)
              ChipRow(
                noun: 'member',
                attribute: 'member',
                items: <ChipItem>[
                  for (final NpcEntry entry in shown)
                    ChipItem(
                      entry.key,
                      label: guiPlainText(entry.name),
                      onOpen: component.onOpen == null ? null : () => component.onOpen!(DocumentKind.npc, entry.key),
                    ),
                ],
                onRemove: editable
                    ? (int index) {
                        if (!_busy) _member(shown[index].key, assign: false);
                      }
                    : null,
              ),
            if (members.length > _shownMembers) dom.span(classes: 'animus-muted', <Widget>[Component.text('and ${members.length - _shownMembers} more')]),
            if (editable)
              GuiSelect(
                id: addId,
                value: null,
                options: <String>[for (final NpcEntry entry in candidates) entry.key],
                labels: <String, String>{for (final NpcEntry entry in candidates) entry.key: '${guiPlainText(entry.name)} (${entry.key})'},
                noneLabel: 'Add member',
                hiddenLabel: 'Add a member NPC',
                inactive: _busy,
                onChanged: (String? key) {
                  if (key != null) _member(key, assign: true);
                },
              ),
          ]),
        ),
        component.tags,
      ],
    );
  }
}
