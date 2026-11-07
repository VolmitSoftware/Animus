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

/// Skin hashes as the server stores them.
final RegExp _skinHash = RegExp(r'^[a-f0-9]{64}$');

/// At most this many skins in a group's pool.
const int _poolLimit = 64;

/// An NPC's `groups` in merge order: move earlier, remove, add from the
/// workspace, and a warning for a group the workspace lacks.
class NpcGroupsList extends StatelessWidget {
  const NpcGroupsList({required this.npc, required this.groups, required this.onChanged, this.diagnostics = const <Diagnostic>[], this.onOpen, super.key});

  final JsonMap npc;

  /// The workspace's groups by id.
  final Map<String, JsonMap> groups;

  /// The new list, or null to drop the field.
  final void Function(List<String>? groups) onChanged;
  final List<Diagnostic> diagnostics;
  final void Function(DocumentKind kind, String id)? onOpen;

  @override
  Widget build(BuildContext context) {
    final List<String> listed = npcGroups(npc);
    final List<String> available = <String>[
      for (final String id in groups.keys)
        if (!listed.contains(id)) id,
    ];
    final List<Diagnostic> unknown = npcGroupDiagnostics(npc, groups.keys.toSet());
    void put(List<String> next) => onChanged(next.isEmpty ? null : next);
    const String addId = 'animus-npc-add-group';
    return GuiRow(
      label: 'Groups',
      help: 'First to last; own fields win.',
      tip: "Trait groups this NPC takes values from. A later group overrides an earlier one, and the NPC's own fields override every group.",
      problems: diagnosticsAt(diagnostics, r'$.groups', nested: true),
      child: dom.div(classes: 'animus-npc-groups', attributes: const <String, String>{'data-npc': 'groups'}, <Widget>[
        if (listed.isEmpty) const dom.p(classes: 'animus-muted', <Widget>[Component.text('No groups.')]),
        if (listed.isNotEmpty)
          dom.ol(classes: 'animus-npc-group-list', <Widget>[
            for (int index = 0; index < listed.length; index++)
              dom.li(key: ValueKey<String>('group:${listed[index]}'), attributes: <String, String>{'data-group': listed[index]}, <Widget>[
                dom.div(classes: 'animus-gui-line', <Widget>[
                  dom.code(<Widget>[Component.text(listed[index])]),
                  if (onOpen != null && groups.containsKey(listed[index]))
                    Button.ghost(
                      label: 'Open',
                      size: ButtonSize.small,
                      attributes: <String, String>{'aria-label': 'Open group ${listed[index]}'},
                      onPressed: () => onOpen!(DocumentKind.group, listed[index]),
                    ),
                  GuiIconButton(
                    icon: ArcaneIcon.arrowUp(size: IconSize.sm),
                    label: 'Move group ${listed[index]} earlier',
                    disabled: index == 0,
                    onPressed: () => put(<String>[for (final Object? id in moveItem(listed, index, index - 1)) id! as String]),
                  ),
                  GuiIconButton(
                    icon: ArcaneIcon.x(size: IconSize.sm),
                    label: 'Remove group ${listed[index]}',
                    onPressed: () => put(<String>[...listed]..removeAt(index)),
                  ),
                ]),
                for (final Diagnostic problem in unknown.where((Diagnostic d) => d.path == '\$.groups[$index]'))
                  dom.span(classes: 'animus-gui-problem is-warning', <Widget>[Component.text(problem.message)]),
              ]),
          ]),
        if (listed.length < npcGroupLimit && available.isNotEmpty)
          dom.div(classes: 'animus-row', <Widget>[
            const dom.label(classes: 'animus-sr-only', attributes: <String, String>{'for': addId}, <Widget>[Component.text('Add a group')]),
            GuiSelect(
              references: DocumentKind.group,
              id: addId,
              value: null,
              options: available,
              noneLabel: 'Add a group',
              onChanged: (String? id) {
                if (id != null) put(<String>[...listed, id]);
              },
            ),
          ])
        else if (groups.isEmpty)
          const dom.p(classes: 'animus-muted', <Widget>[Component.text('No groups yet. Use New document.')]),
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
        if (pool.isEmpty) const dom.p(classes: 'animus-muted', <Widget>[Component.text('No pool. Members use the skin above.')]),
        if (pool.isNotEmpty)
          dom.ul(classes: 'animus-group-skin-list', <Widget>[
            for (final String hash in pool)
              dom.li(key: ValueKey<String>('skin:$hash'), <Widget>[
                dom.code(attributes: <String, String>{'title': hash}, <Widget>[Component.text('${short(hash)}...')]),
                GuiIconButton(
                  icon: ArcaneIcon.x(size: IconSize.sm),
                  label: 'Remove skin ${short(hash)} from the pool',
                  onPressed: () => put(<String>[...pool]..remove(hash)),
                ),
              ]),
          ]),
        dom.div(classes: 'animus-row', <Widget>[
          if (offered.isNotEmpty) ...<Widget>[
            const dom.label(classes: 'animus-sr-only', attributes: <String, String>{'for': pickId}, <Widget>[Component.text('Add a workspace skin to the pool')]),
            GuiSelect(
              references: DocumentKind.skin,
              id: pickId,
              value: null,
              options: offered,
              skinFaces: skinFacesOf(offered),
              noneLabel: 'Add a workspace skin',
              onChanged: (String? hash) {
                if (hash != null && pool.length < _poolLimit) put(<String>[...pool, hash]);
              },
            ),
          ],
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
