/// A trait group's Members tab: its description, the NPCs that take it (a
/// list that opens each one, with Remove for the picked ones) and Add
/// members, which picks more from every other NPC. Advanced adds the tags
/// the group gives its members.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_text.dart';
import '../../logic/npc/group_members.dart';
import '../../logic/npc/npc_bulk.dart';
import '../../logic/npc/npc_catalog.dart';
import '../../model/document_kind.dart';
import '../../ui/advanced_in_use.dart';
import '../../ui/action_row.dart';
import '../../ui/dialog.dart';
import '../../ui/field.dart';
import '../../ui/library_list.dart';
import '../../ui/library_query.dart';
import '../conversation/conversation_attach.dart' show ConversationCommit;
import '../gui/gui_fields.dart';
import 'npc_form.dart';
import 'npc_parts.dart';
import 'npc_layout.dart';
import '../../logic/suggest/field_profile.dart';
import '../suggest/autocomplete_input.dart';

/// One NPC as a member row: its plain name (the id when it has none), body
/// and world, and the id in Advanced.
LibraryRow memberRow(NpcEntry entry) {
  final String name = guiPlainText(entry.name).trim();
  final String type = entry.type.startsWith('minecraft:') ? entry.type.substring(10).replaceAll('_', ' ') : entry.type;
  final List<String> line = <String>[if (type.isNotEmpty) '${type[0].toUpperCase()}${type.substring(1)}', if (entry.world.isNotEmpty) entry.world];
  return LibraryRow(
    key: entry.key,
    title: name.isEmpty ? entry.key : name,
    meta: line.isEmpty ? null : line.join(', '),
    advancedMeta: entry.key,
    keywords: entry.key,
  );
}

class NpcMembersTab extends StatefulWidget {
  const NpcMembersTab({required this.form, required this.extras, required this.onReveal, this.inUse = 0, super.key});

  final NpcForm form;
  final bool extras;
  final void Function() onReveal;

  /// The settings Simple counts on this tab's line.
  final int inUse;

  @override
  State<NpcMembersTab> createState() => _NpcMembersTabState();
}

class _NpcMembersTabState extends State<NpcMembersTab> {
  bool _adding = false;
  bool _busy = false;

  NpcForm get _form => component.form;

  /// Adds or removes [keys] as members in one undoable change.
  Future<void> _change(Set<String> keys, {required bool assign}) async {
    final ConversationCommit? commit = _form.onCommit;
    if (commit == null || keys.isEmpty || _busy) return;
    final String id = _form.document.id;
    final NpcBulkResult result = changeGroup(npcEntries(_form.workspaceDocuments), _form.groups, id, NpcGroupTarget.ids(keys), assign: assign);
    if (result.changes.isEmpty) {
      ArcaneSonner.warning(describeGroupChange(id, result, assign: assign));
      return;
    }
    setState(() => _busy = true);
    try {
      await commit(result.changes, '${assign ? 'assign' : 'unassign'} group $id');
      ArcaneSonner.success(describeGroupChange(id, result, assign: assign));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _open(LibraryRow row) => _form.onOpen?.call(DocumentKind.npc, row.key);

  @override
  Widget build(BuildContext context) {
    final NpcForm form = _form;
    final String id = form.document.id;
    final List<NpcEntry> entries = npcEntries(form.workspaceDocuments);
    final List<NpcEntry> members = groupMembers(entries, id);
    final bool editable = form.onCommit != null;
    const String descriptionId = 'animus-group-description';
    return tabBody(<Widget>[
      Field(
        label: 'Description',
        forId: descriptionId,
        tip: 'Shown in /animus groups list.',
        docs: Docs.groups,
        problems: diagnosticsAt(form.diagnostics, r'$.description'),
        child: AutocompleteInput(
          id: descriptionId,
          value: asString(form.file['description']) ?? '',
          label: 'Group description',
          placeholder: 'What these NPCs share',
          onChanged: (String text) => form.onJson(writeAt(form.file, const <JsonStep>['description'], text.trim().isEmpty ? null : text), coalesce: 'group:description'),
          profile: FieldProfile.none,
        ),
      ),
      Field(
        label: 'Members',
        tip: "NPCs that take this group's settings. Adding one appends the group to that NPC's list, as /animus groups assign does.",
        docs: Docs.groupList,
        child: dom.div(styles: stackStyles(), attributes: const <String, String>{'data-group': 'members'}, <Widget>[
          if (editable)
            ActionRow(
              label: 'Members',
              secondary: <RowAction>[RowAction('Add members', _busy ? null : () => setState(() => _adding = true), icon: ArcaneIcon.plus(), attributes: const <String, String>{'data-group': 'add-members'})],
            ),
          LibraryList(
            key: ValueKey<String>('members:$id'),
            id: 'animus-group-member-list',
            label: 'Members',
            noun: 'member',
            rows: <LibraryRow>[for (final NpcEntry entry in members) memberRow(entry)],
            fill: false,
            selectable: editable,
            bulkActions: <LibraryBulkAction>[
              LibraryBulkAction('Remove from group', (List<LibraryRow> rows) => _change(<String>{for (final LibraryRow row in rows) row.key}, assign: false), destructive: true),
            ],
            emptyMessage: 'No members yet.',
            onOpen: _open,
          ),
        ]),
      ),
      if (!component.extras) AdvancedInUse(count: component.inUse, onShow: component.onReveal),
      if (component.extras)
        NpcTagsField(
          tags: stringList(form.doc['tags']),
          known: () => <String>[for (final (String tag, int _) in npcTags(entries)) tag],
          diagnostics: form.diagnostics,
          onChanged: (List<String>? tags) => form.write(const <JsonStep>['tags'], tags),
        ),
      if (_adding)
        AppDialog(
          open: true,
          wide: true,
          title: 'Add members',
          id: 'animus-group-add-members',
          onClose: () => setState(() => _adding = false),
          footer: DialogFooter(cancel: RowAction('Close', () => setState(() => _adding = false))),
          children: <Widget>[
            LibraryList(
              id: 'animus-group-candidates',
              framed: false,
              label: 'NPCs',
              noun: 'NPC',
              rows: <LibraryRow>[for (final NpcEntry entry in groupCandidates(entries, id)) memberRow(entry)],
              fill: false,
              selectable: true,
              bulkActions: <LibraryBulkAction>[
                LibraryBulkAction('Add to group', (List<LibraryRow> rows) {
                  setState(() => _adding = false);
                  _change(<String>{for (final LibraryRow row in rows) row.key}, assign: true);
                }),
              ],
              emptyMessage: 'Every NPC is a member already.',
              onOpen: (LibraryRow row) {
                setState(() => _adding = false);
                _change(<String>{row.key}, assign: true);
              },
            ),
          ],
        ),
    ]);
  }
}
