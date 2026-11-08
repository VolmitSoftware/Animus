/// Trait group parts of the NPC inspector: an NPC's groups list, the
/// Effective table, and a group's skin pool.
library;

import 'dart:convert';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/npc/group_merge.dart';
import '../../logic/npc/group_model.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../fold.dart';
import '../gui/gui_fields.dart';
import '../info_tip.dart';
import '../select/skin_faces.dart';
import 'chip_row.dart';

/// Skin hashes as the server stores them.
final RegExp _skinHash = RegExp(r'^[a-f0-9]{64}$');

/// At most this many skins in a group's pool.
const int _poolLimit = 64;

/// An NPC's `groups` in merge order: a chip row (drag or Alt+arrow to
/// reorder, remove, open) and a picker that adds a workspace group or makes
/// a new one from the search text.
class NpcGroupsList extends StatefulWidget {
  const NpcGroupsList({required this.npc, required this.groups, required this.onChanged, this.diagnostics = const <Diagnostic>[], this.onOpen, this.onCreate, super.key});

  final JsonMap npc;

  /// The workspace's groups by id.
  final Map<String, JsonMap> groups;

  /// The new list, or null to drop the field.
  final void Function(List<String>? groups) onChanged;
  final List<Diagnostic> diagnostics;
  final void Function(DocumentKind kind, String id)? onOpen;

  /// Makes a group named from the search text and lists it here in one
  /// change; answers its id, or null when nothing was made. Null leaves
  /// "+ New group" to the New page.
  final Future<String?> Function(String search)? onCreate;

  @override
  State<NpcGroupsList> createState() => _NpcGroupsListState();
}

class _NpcGroupsListState extends State<NpcGroupsList> {
  /// The group made here last, offered to open.
  String? _made;

  Future<void> _create(String search) async {
    final String? id = await component.onCreate!(search);
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
    return GuiRow(
      label: 'Groups',
      forId: listed.length < npcGroupLimit ? addId : null,
      tip: "Trait groups this NPC takes values from, first to last. A later group overrides an earlier one, and the NPC's own fields override every group. Drag a group, or press Alt with an arrow key, to reorder.",
      problems: diagnosticsAt(component.diagnostics, r'$.groups', nested: true),
      child: dom.div(classes: 'animus-npc-groups', attributes: const <String, String>{'data-npc': 'groups'}, <Widget>[
        dom.div(classes: 'animus-chip-line', <Widget>[
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
              GuiSelect(
                references: DocumentKind.group,
                id: addId,
                value: null,
                options: available,
                noneLabel: 'Add group',
                hiddenLabel: 'Add a group',
                onNew: component.onCreate == null ? null : _create,
                onChanged: (String? id) {
                  if (id != null) put(<String>[...listed, id]);
                },
              ),
          ]),
        for (final Diagnostic problem in unknown)
          dom.span(classes: 'animus-gui-problem is-warning', <Widget>[Component.text(problem.message)]),
        if (made != null && open != null)
          dom.p(classes: 'animus-gui-help animus-made-note', attributes: const <String, String>{'role': 'status'}, <Widget>[
            Component.text('Made group $made. '),
            Button.ghost(label: 'Open $made', size: ButtonSize.small, onPressed: () => open(DocumentKind.group, made)),
          ]),
      ]),
    );
  }
}

/// The effective definition as a table, one row per field or module with
/// the group or NPC that set it, then the whole effective JSON.
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

  @override
  Widget build(BuildContext context) => dom.div(classes: 'animus-effective', <Widget>[
        dom.div(classes: 'animus-effective-wrap', <Widget>[
          dom.table(classes: 'animus-effective-table', attributes: const <String, String>{'aria-label': 'Effective definition'}, <Widget>[
            dom.thead(<Widget>[
              dom.tr(<Widget>[
                const dom.th(attributes: <String, String>{'scope': 'col'}, <Widget>[Component.text('Field')]),
                const dom.th(attributes: <String, String>{'scope': 'col'}, <Widget>[Component.text('Value')]),
                dom.th(attributes: <String, String>{'scope': 'col'}, <Widget>[
                  withTip(const Component.text('From'), 'The group that set the value, or this NPC when its own field wins.', docs: Docs.groupMerge),
                ]),
              ]),
            ]),
            dom.tbody(<Widget>[
              for (final EffectiveRow row in effective.rows)
                dom.tr(
                  classes: row.source == groupMergeNpc ? 'is-own' : 'is-group',
                  attributes: <String, String>{'data-path': row.path, 'data-source': row.source},
                  <Widget>[
                    dom.th(attributes: const <String, String>{'scope': 'row'}, <Widget>[dom.code(<Widget>[Component.text(row.path)])]),
                    dom.td(<Widget>[dom.code(<Widget>[Component.text(_oneLine(row.value))])]),
                    dom.td(<Widget>[
                      if (row.source == groupMergeNpc)
                        const dom.span(classes: 'animus-muted', <Widget>[Component.text('this NPC')])
                      else if (onOpen != null && known.contains(row.source))
                        Button.ghost(
                          label: row.source,
                          size: ButtonSize.small,
                          attributes: <String, String>{'aria-label': 'Open group ${row.source}, which sets ${row.path}'},
                          onPressed: () => onOpen!(DocumentKind.group, row.source),
                        )
                      else
                        dom.code(<Widget>[Component.text(row.source)]),
                    ]),
                  ],
                ),
            ]),
          ]),
        ]),
        Fold(
          classes: 'animus-effective-json',
          summary: <Widget>[withTip(const Component.text('Effective JSON'), 'The merged definition the server runs for this NPC.', docs: Docs.groupMerge)],
          children: <Widget>[dom.pre(<Widget>[dom.code(<Widget>[Component.text(prettyJson(effective.json))])])],
        ),
      ]);
}

/// A group's `skins`: hashes each member takes one of by its UUID.
class GroupSkinPool extends StatefulWidget {
  const GroupSkinPool({required this.pool, required this.skins, required this.onChanged, this.diagnostics = const <Diagnostic>[], super.key});

  final List<String> pool;

  /// The workspace's skin ids (their hashes).
  final List<String> skins;

  /// The new pool, or null to drop the field.
  final void Function(List<String>? pool) onChanged;
  final List<Diagnostic> diagnostics;

  @override
  State<GroupSkinPool> createState() => _GroupSkinPoolState();
}

class _GroupSkinPoolState extends State<GroupSkinPool> {
  String _adding = '';

  @override
  Widget build(BuildContext context) {
    final List<String> pool = component.pool;
    final List<String> offered = <String>[
      for (final String id in component.skins)
        if (!pool.contains(id)) id,
    ];
    void put(List<String> next) => component.onChanged(next.isEmpty ? null : next);
    final bool valid = _skinHash.hasMatch(_adding) && !pool.contains(_adding) && pool.length < _poolLimit;
    String short(String hash) => hash.substring(0, hash.length.clamp(0, 12));
    const String pickId = 'animus-group-add-skin';
    return GuiRow(
      label: 'Skin pool',
      help: 'Each member keeps one, picked by UUID. Its own skin wins.',
      tip: 'Skins the members share. Each member always gets the same one, chosen from its UUID.',
      problems: diagnosticsAt(component.diagnostics, r'$.skins', nested: true),
      child: dom.div(classes: 'animus-group-skins', <Widget>[
        dom.div(classes: 'animus-chip-line', <Widget>[
          if (pool.isNotEmpty)
            ChipRow(
              noun: 'pool skin',
              attribute: 'skin',
              items: <ChipItem>[for (final String hash in pool) ChipItem(hash, label: short(hash))],
              onRemove: (int index) => put(<String>[...pool]..removeAt(index)),
            ),
          if (pool.length < _poolLimit)
            GuiSelect(
              references: DocumentKind.skin,
              id: pickId,
              value: null,
              options: offered,
              skinFaces: skinFacesOf(offered),
              noneLabel: 'Add skin',
              hiddenLabel: 'Add a workspace skin to the pool',
              onChanged: (String? hash) {
                if (hash != null && pool.length < _poolLimit) put(<String>[...pool, hash]);
              },
            ),
        ]),
        dom.div(classes: 'animus-row', <Widget>[
          GuiTextInput(value: _adding, label: 'Skin hash to add', placeholder: '64 hex characters', onChanged: (String value) => setState(() => _adding = value.trim().toLowerCase())),
          Button.ghost(
            label: 'Add hash',
            size: ButtonSize.small,
            disabled: !valid,
            onPressed: () {
              put(<String>[...pool, _adding]);
              setState(() => _adding = '');
            },
          ),
        ]),
      ]),
    );
  }
}
