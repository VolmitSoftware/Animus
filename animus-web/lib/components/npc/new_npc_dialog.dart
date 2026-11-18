/// New NPC, the one way to make an NPC: from the NPC page's primary button
/// and its empty state, and from the New page and a dropdown's "+ New NPC".
/// A name and a body from the full list (Advanced adds the folder and the
/// file name). The NPC starts where the player who paired the editor
/// stands, else on the ground of the server's terrain. Create writes it,
/// then opens it.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../config/docs_links.dart';
import '../../logic/npc/npc_appearance.dart' show bodyLabel;
import '../../logic/gui/gui_json.dart';
import '../../logic/npc/npc_paths.dart';
import '../../logic/offline/new_documents.dart';
import '../../logic/terrain/terrain_data.dart';
import '../../logic/terrain/terrain_snap.dart';
import '../../model/document_kind.dart';
import '../../services/browser.dart';
import '../../services/terrain/terrain_client.dart';
import '../../services/terrain/terrain_link.dart';
import '../../state/editor_store.dart';
import '../../state/workspace_route.dart';
import '../../ui/action_row.dart';
import '../../ui/dialog.dart';
import 'body_type_field.dart' show bodyGroups, bodyOptions;
import 'npc_list_dialogs.dart';
import 'npc_list_rows.dart';
import '../select/animus_select.dart';
import '../../ui/field.dart';

/// The Body select's labels, worked out once.
final Map<String, String> _bodyLabels = <String, String>{for (final String body in bodyOptions) body: bodyLabel(body)};

/// The feet height on the ground at the spawn spot of [world], when the
/// paired server's terrain shows it there.
Future<int?> _ground(String world) async {
  final TerrainClient? client = TerrainLink.client;
  if (client == null) return null;
  try {
    final TerrainSurfaceTile? tile = await client.surface(world, 0, 0).timeout(const Duration(seconds: 3));
    final TerrainColumn? column = TerrainSpot(tile, null).column(0, 0);
    return column == null ? null : terrainSurfaceStand(column);
  } on Object {
    return null;
  }
}

class NewNpcDialog extends StatefulWidget {
  const NewNpcDialog({
    required this.store,
    required this.workspaceId,
    required this.folders,
    required this.folder,
    required this.taken,
    required this.world,
    required this.advanced,
    required this.onClose,
    this.owner,
    this.adminSpot,
    this.name = '',
    this.onCreated,
    super.key,
  });

  final EditorStore store;
  final String workspaceId;

  /// Every folder that holds NPCs.
  final List<String> folders;

  /// Where the NPC goes unless Advanced picks another folder.
  final String folder;

  /// Every NPC id of the workspace.
  final Set<String> taken;

  /// The world it stands in.
  final String world;
  final bool advanced;
  final void Function() onClose;

  /// The paired player's UUID, which the NPC takes as its owner.
  final String? owner;

  /// Where the paired player stands now, when the workspace is paired.
  final Future<NpcSpot?> Function()? adminSpot;

  /// The name the dialog starts with, typed into a dropdown's search.
  final String name;

  /// Goes on after the NPC [id] is made, in place of closing the dialog
  /// and opening it.
  final void Function(String id)? onCreated;

  @override
  State<NewNpcDialog> createState() => _NewNpcDialogState();
}

class _NewNpcDialogState extends State<NewNpcDialog> {
  late String _name = component.name;
  String _body = bodyOptions.first;
  late String _folder = component.folder;

  /// The file name as typed in Advanced; null follows the name.
  String? _id;
  String? _nameProblem;

  /// Why the id cannot be used, or why the workspace refused the NPC.
  String? _problem;
  bool _busy = false;

  String get _proposed => newNpcId(_name, _folder, component.taken);

  /// Where the NPC starts: see [newNpcPlace].
  Future<NpcSpot> _place() async {
    // A server that is slow to say only delays Create a little; the NPC then starts on the ground.
    final NpcSpot? admin = await component.adminSpot?.call().timeout(const Duration(seconds: 4), onTimeout: () => null);
    if (admin != null) return newNpcPlace(world: component.world, admin: admin);
    return newNpcPlace(world: component.world, ground: await _ground(component.world));
  }

  Future<void> _create() async {
    if (_busy) return;
    final String name = _name.trim();
    final String id = (_id ?? _proposed).trim();
    final String? problem = newNpcProblem(id, _folder, component.taken);
    setState(() {
      _nameProblem = name.isEmpty ? 'Give the NPC a name.' : null;
      _problem = problem;
    });
    if (_nameProblem != null || _problem != null) return;
    final String documentId = npcIdIn(_folder, id);
    setState(() => _busy = true);
    final NpcSpot place = await _place();
    if (!mounted) return;
    final JsonMap json = newNpc(
      NpcSeed(name: name, type: _body, group: _folder, id: id, world: place.world, x: place.x, y: place.y, z: place.z, yaw: place.yaw, owner: component.owner),
      uuid: newUuid(),
    );
    await component.store.commit(
      documents: <DocumentChange>[DocumentChange(DocumentKind.npc, documentId, prettyJson(json), path: newDocumentPath(DocumentKind.npc, documentId))],
      label: 'create $name',
    );
    if (!mounted) return;
    if (component.store.document(DocumentKind.npc, documentId) == null) {
      final String failed = 'Not created: ${component.store.problem ?? 'the workspace changed'}. Press Create again.';
      setState(() {
        _busy = false;
        if (component.advanced) {
          _problem = failed;
        } else {
          _nameProblem = failed;
        }
      });
      return;
    }
    final void Function(String id)? created = component.onCreated;
    if (created != null) {
      created(documentId);
      return;
    }
    component.onClose();
    navigate(DocumentRoute(component.workspaceId, DocumentKind.npc, documentId).format());
  }

  @override
  Widget build(BuildContext context) => AppDialog(
        open: true,
        id: 'npc-new-dialog',
        title: 'New NPC',
        onClose: component.onClose,
        footer: DialogFooter(
          cancel: RowAction('Cancel', component.onClose),
          primary: RowAction('Create', _create, loading: _busy),
        ),
        children: <Widget>[
          dialogFields(<Widget>[
            Field(
              label: 'Name',
              forId: 'npc-new-name',
              error: _nameProblem,
              child: TextInput(
                id: 'npc-new-name',
                value: _name,
                onChanged: (String value) => setState(() {
                  _name = value;
                  _nameProblem = null;
                  _problem = null;
                }),
                onSubmitted: (String _) => _create(),
              ),
            ),
            Field(
              label: 'Body',
              forId: 'npc-new-body',
              tip: 'What the NPC looks like: a player, a villager, a zombie. Type to search every body.',
              docs: Docs.npcAppearance,
              child: AnimusSelect.values(
                id: 'npc-new-body',
                value: _body,
                values: bodyOptions,
                labels: _bodyLabels,
                groups: bodyGroups,
                onChanged: (String? value) => setState(() => _body = value ?? bodyOptions.first),
              ),
            ),
            if (component.advanced) ...<Widget>[
              Field(
                label: 'Folder',
                forId: 'npc-new-folder',
                tip: "Where the NPC's file is kept. Sorting only, changes nothing in game.",
                docs: Docs.fileLayout,
                child: AnimusSelect.values(
                  id: 'npc-new-folder',
                  value: _folder.isEmpty ? rootFolder : _folder,
                  values: folderOptions(component.folders),
                  labels: folderLabels(<String>{...component.folders, if (_folder.isNotEmpty) _folder}.toList()),
                  onChanged: (String? value) => setState(() {
                    _folder = folderOf(value ?? rootFolder);
                    _problem = null;
                  }),
                ),
              ),
              Field(
                label: 'Id',
                forId: 'npc-new-id',
                error: _problem,
                child: TextInput(
                  id: 'npc-new-id',
                  value: _id ?? _proposed,
                  onChanged: (String value) => setState(() {
                    _id = value;
                    _problem = null;
                  }),
                  onSubmitted: (String _) => _create(),
                ),
              ),
            ],
          ]),
        ],
      );
}
