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
  String? _adding;
  bool _busy = false;

  Future<void> _add(String key) async {
    final ConversationCommit? commit = component.onCommit;
    if (commit == null) return;
    final NpcBulkResult result = addGroupMember(component.entries, component.groups, component.id, key);
    if (result.changes.isEmpty) {
      ArcaneSonner.warning(describeGroupChange(component.id, result, assign: true));
      return;
    }
    setState(() => _busy = true);
    try {
      await commit(result.changes, 'assign group ${component.id}');
      if (mounted) setState(() => _adding = null);
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
    final String? adding = _adding != null && candidates.any((NpcEntry entry) => entry.key == _adding) ? _adding : null;
    final bool picker = component.onCommit != null && candidates.isNotEmpty;
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
          forId: picker ? addId : null,
          tip: 'NPCs whose groups list this one. Adding a member appends the group to that NPC, as /animus groups assign does.',
          help: picker ? 'Or assign in bulk from Browse NPCs.' : 'Assign in bulk from Browse NPCs.',
          child: dom.div(classes: 'animus-group-members-edit', <Widget>[
            if (members.isEmpty)
              dom.p(classes: 'animus-muted', <Widget>[
                Component.text(picker ? 'No members yet. Pick an NPC below and press Add member.' : 'No members yet.'),
              ])
            else
              dom.ul(classes: 'animus-group-members', attributes: const <String, String>{'data-group': 'members'}, <Widget>[
                for (final NpcEntry entry in members.take(_shownMembers))
                  dom.li(<Widget>[
                    if (component.onOpen != null)
                      Button.ghost(
                        label: entry.name,
                        size: ButtonSize.small,
                        attributes: <String, String>{'aria-label': 'Open NPC ${entry.key}'},
                        onPressed: () => component.onOpen!(DocumentKind.npc, entry.key),
                      )
                    else
                      dom.span(<Widget>[Component.text(entry.name)]),
                    dom.code(classes: 'animus-muted', <Widget>[Component.text(entry.key)]),
                  ]),
                if (members.length > _shownMembers) dom.li(classes: 'animus-muted', <Widget>[Component.text('and ${members.length - _shownMembers} more')]),
              ]),
            if (picker)
              dom.div(classes: 'animus-gui-line', attributes: const <String, String>{'data-group': 'add-member'}, <Widget>[
                GuiSelect(
                  id: addId,
                  value: adding,
                  options: <String>[for (final NpcEntry entry in candidates) entry.key],
                  labels: <String, String>{for (final NpcEntry entry in candidates) entry.key: '${guiPlainText(entry.name)} (${entry.key})'},
                  noneLabel: 'Pick an NPC',
                  onChanged: (String? key) => setState(() => _adding = key),
                ),
                Button.secondary(
                  label: 'Add member',
                  size: ButtonSize.small,
                  disabled: _busy || adding == null,
                  onPressed: () => _add(adding!),
                ),
              ]),
          ]),
        ),
        component.tags,
      ],
    );
  }
}
