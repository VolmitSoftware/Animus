/// Trait group parts of the NPC form: an NPC's groups list, what it takes
/// from them, and a group's skin pool.
library;

import 'dart:convert';
import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:arcane_jaspr/html.dart' show ArcaneCodeBlock;
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/npc/group_merge.dart';
import '../../logic/npc/group_model.dart';
import '../../logic/npc/skin_edits.dart' show skinDocumentLabel;
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../gui/gui_fields.dart';
import '../../ui/action_row.dart';
import '../../ui/badge.dart';
import '../../ui/dialog.dart';
import '../../ui/field.dart';
import '../../ui/section.dart';
import '../../ui/mode.dart';
import 'chip_row.dart';
import 'npc_layout.dart';
import 'skin_field.dart';
import '../../logic/suggest/field_profile.dart';
import '../suggest/autocomplete_input.dart';
import '../select/animus_select.dart';
import '../../ui/stack.dart';
import '../../ui/problem_text.dart';

/// Skin hashes as the server stores them.
final RegExp _skinHash = RegExp(r'^[a-f0-9]{64}$');

/// At most this many skins in a group's pool.
const int _poolLimit = 64;

/// An NPC's `groups` in merge order: a chip row (drag or Alt+arrow to
/// reorder, remove, open) and a picker that adds a workspace group, whose
/// "+ New group" asks for a name and makes and lists it in one change.
class NpcGroupsList extends StatefulWidget {
  const NpcGroupsList({required this.npc, required this.groups, required this.onChanged, this.diagnostics = const <Diagnostic>[], this.onOpen, this.onCreate, super.key});

  final JsonMap npc;

  /// The workspace's groups by id.
  final Map<String, JsonMap> groups;

  /// The new list, or null to drop the field.
  final void Function(List<String>? groups) onChanged;
  final List<Diagnostic> diagnostics;
  final void Function(DocumentKind kind, String id)? onOpen;

  /// Makes a group from a name and lists it here in one change; answers its
  /// id, or null when nothing was made. Null leaves "+ New group" to the
  /// New page.
  final Future<String?> Function(String search)? onCreate;

  @override
  State<NpcGroupsList> createState() => _NpcGroupsListState();
}

class _NpcGroupsListState extends State<NpcGroupsList> {
  /// The group made here last, offered to open.
  String? _made;

  /// The name typed in the New group dialog; null while it is closed.
  String? _naming;

  Future<void> _create(String name) async {
    setState(() => _naming = null);
    final String? id = await component.onCreate!(name);
    if (mounted) setState(() => _made = id);
  }

  @override
  Widget build(BuildContext context) {
    final List<String> listed = npcGroups(component.npc);
    final Map<String, JsonMap> groups = component.groups;
    final List<String> available = <String>[
      for (final String id in groups.keys)
        if (!listed.contains(id)) id,
    ];
    final List<Diagnostic> unknown = npcGroupDiagnostics(component.npc, groups.keys.toSet());
    void put(List<String> next) => component.onChanged(next.isEmpty ? null : next);
    final void Function(DocumentKind, String)? open = component.onOpen;
    final String? made = _made != null && listed.contains(_made) ? _made : null;
    const String addId = 'animus-npc-add-group';
    final String? naming = _naming;
    return Field(
      label: 'Groups',
      forId: listed.length < npcGroupLimit ? addId : null,
      tip: "Trait groups this NPC takes values from, first to last. A later group overrides an earlier one, and the NPC's own fields override every group. Drag a group, or press Alt with an arrow key, to reorder.",
      problems: diagnosticsAt(component.diagnostics, r'$.groups', nested: true),
      child: dom.div(styles: stackStyles(), attributes: const <String, String>{'data-npc': 'groups'}, <Widget>[
        Wrap(spacing: Gap.sm.value, runSpacing: Gap.sm.value, crossAxisAlignment: WrapCrossAlignment.center, children: <Widget>[
          if (listed.isNotEmpty)
            ChipRow(
              noun: 'group',
              attribute: 'group',
              items: <ChipItem>[
                for (int index = 0; index < listed.length; index++)
                  ChipItem(
                    listed[index],
                    onOpen: open != null && groups.containsKey(listed[index]) ? () => open(DocumentKind.group, listed[index]) : null,
                    problem: unknown.where((Diagnostic d) => d.path == '\$.groups[$index]').firstOrNull?.message,
                  ),
              ],
              onRemove: (int index) => put(<String>[...listed]..removeAt(index)),
              onMove: (int from, int to) => put(<String>[for (final Object? id in moveItem(listed, from, to)) id! as String]),
            ),
          if (listed.length < npcGroupLimit)
            AnimusSelect.values(
              references: DocumentKind.group,
              id: addId,
              value: null,
              values: available,
              noneLabel: 'Add group',
              hiddenLabel: 'Add a group',
              onNew: component.onCreate == null ? null : (String search) => setState(() => _naming = search),
              onChanged: (String? id) {
                if (id != null) put(<String>[...listed, id]);
              },
            ),
        ]),
        for (final Diagnostic problem in unknown) problemText(problem.message, severity: DiagnosticSeverity.warning),
        if (made != null && open != null)
          dom.div(attributes: const <String, String>{'role': 'status'}, <Widget>[
            ActionRow(
              label: 'Made group',
              leading: <Widget>[mutedText('Made group $made.')],
              secondary: <RowAction>[RowAction('Open $made', () => open(DocumentKind.group, made))],
            ),
          ]),
        if (naming != null)
          AppDialog(
            open: true,
            title: 'New trait group',
            id: 'animus-npc-new-group',
            onClose: () => setState(() => _naming = null),
            footer: DialogFooter(
              cancel: RowAction('Cancel', () => setState(() => _naming = null)),
              primary: RowAction('Create', naming.trim().isEmpty ? null : () => _create(naming.trim())),
            ),
            children: <Widget>[
              Field(
                label: 'Name',
                forId: 'animus-npc-new-group-name',
                child: AutocompleteInput(id: 'animus-npc-new-group-name', value: naming, label: 'Name', onChanged: (String text) => setState(() => _naming = text), profile: FieldProfile.none),
              ),
            ],
          ),
      ]),
    );
  }
}

/// The effective definition, one row per field or module with the group or
/// NPC that set it, then the whole effective JSON.
class EffectiveTable extends StatelessWidget {
  const EffectiveTable({required this.effective, required this.known, this.onOpen, super.key});

  final NpcEffective effective;

  /// Group ids the workspace has, which get an Open link.
  final Set<String> known;
  final void Function(DocumentKind kind, String id)? onOpen;

  /// A value on one line, cut at 120 characters.
  static String _oneLine(Object? value) {
    final String text = value is String ? value : jsonEncode(value);
    return text.length > 120 ? '${text.substring(0, 117)}...' : text;
  }

  Widget _row(EffectiveRow row) {
    final void Function(DocumentKind, String)? open = onOpen;
    final bool own = row.source == groupMergeNpc;
    return dom.div(
      key: ValueKey<String>('effective:${row.path}'),
      attributes: <String, String>{'role': 'listitem', 'data-path': row.path, 'data-source': row.source},
      <Widget>[
        Field(
          label: row.path,
          child: ActionRow(
            label: row.path,
            leading: <Widget>[
              npcCode(_oneLine(row.value)),
              if (own) const StatusBadge('This NPC'),
              if (!own && (open == null || !known.contains(row.source))) StatusBadge(row.source),
            ],
            secondary: <RowAction>[
              if (!own && open != null && known.contains(row.source))
                RowAction(row.source, () => open(DocumentKind.group, row.source), attributes: <String, String>{'aria-label': 'Open group ${row.source}, which sets ${row.path}'}),
            ],
          ),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) => fieldStack(<Widget>[
        dom.div(
          attributes: const <String, String>{'role': 'list', 'aria-label': 'Effective definition'},
          <Widget>[for (final EffectiveRow row in effective.rows) _row(row)],
        ),
        AppSection(
          title: 'Effective JSON',
          open: false,
          tip: 'The merged definition the server runs for this NPC.',
          docs: Docs.groupMerge,
          children: <Widget>[
            ArcaneCodeBlock.code(styles: const ArcaneStyleData(maxHeight: '24rem', fontSize: FontSize.xs), children: <Widget>[Component.text(prettyJson(effective.json))]),
          ],
        ),
      ]);
}

/// A group's `skins`: hashes each member takes one of by its UUID.
class GroupSkinPool extends StatefulWidget {
  const GroupSkinPool({required this.pool, required this.skins, required this.onChanged, this.resources = const <String, Uint8List>{}, this.diagnostics = const <Diagnostic>[], super.key});

  final List<String> pool;

  /// The workspace's skin documents.
  final List<AnimusDocument> skins;
  final Map<String, Uint8List> resources;

  /// The new pool, or null to drop the field.
  final void Function(List<String>? pool) onChanged;
  final List<Diagnostic> diagnostics;

  @override
  State<GroupSkinPool> createState() => _GroupSkinPoolState();
}

class _GroupSkinPoolState extends State<GroupSkinPool> {
  String _adding = '';
  bool _choosing = false;

  void _put(List<String> next) => component.onChanged(next.isEmpty ? null : next);

  @override
  Widget build(BuildContext context) {
    final List<String> pool = component.pool;
    final Map<String, AnimusDocument> byId = <String, AnimusDocument>{for (final AnimusDocument skin in component.skins) skin.id: skin};
    final bool valid = _skinHash.hasMatch(_adding) && !pool.contains(_adding) && pool.length < _poolLimit;
    // A pool skin by its label; else its place in the pool (Simple) or the
    // start of its hash (Advanced), never a bare hash in Simple.
    final bool advanced = ModeScope.advanced(context);
    String short(String hash) =>
        skinDocumentLabel(byId[hash]?.json) ?? (advanced ? hash.substring(0, hash.length.clamp(0, 12)) : 'Skin ${pool.indexOf(hash) + 1}');
    return Field(
      label: 'Skin pool',
      hint: 'Each member keeps one, picked by its UUID.',
      tip: "Skins the members share. A member's own skin wins.",
      docs: Docs.groups,
      problems: diagnosticsAt(component.diagnostics, r'$.skins', nested: true),
      child: dom.div(styles: stackStyles(), attributes: const <String, String>{'data-group': 'skin-pool'}, <Widget>[
        if (pool.isNotEmpty)
          ChipRow(
            noun: 'pool skin',
            attribute: 'skin',
            items: <ChipItem>[for (final String hash in pool) ChipItem(hash, label: short(hash))],
            onRemove: (int index) => _put(<String>[...pool]..removeAt(index)),
          ),
        ActionRow(
          label: 'Skin pool',
          leading: <Widget>[
            if (ModeScope.advanced(context))
              wrapItem('1 1 16rem', AutocompleteInput(value: _adding, label: 'Skin id to add', placeholder: '64 hex characters', onChanged: (String value) => setState(() => _adding = value.trim().toLowerCase()), profile: FieldProfile.none)),
          ],
          secondary: <RowAction>[
            if (ModeScope.advanced(context))
              RowAction('Add id', valid
                  ? () {
                      _put(<String>[...pool, _adding]);
                      setState(() => _adding = '');
                    }
                  : null),
            RowAction('Add skin', pool.length < _poolLimit ? () => setState(() => _choosing = true) : null, icon: ArcaneIcon.plus()),
          ],
        ),
        if (_choosing)
          SkinChooser(
            title: 'Add a skin to the pool',
            skins: component.skins,
            documents: component.skins,
            resources: component.resources,
            exclude: pool.toSet(),
            onPick: (String id) {
              setState(() => _choosing = false);
              if (pool.length < _poolLimit) _put(<String>[...pool, id]);
            },
            onClose: () => setState(() => _choosing = false),
          ),
      ]),
    );
  }
}
