/// New NPC, from the NPC page's primary button and its empty state: a name
/// and a body (Advanced adds the folder and the file name). Create writes
/// the NPC, then opens it.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../config/docs_links.dart';
import '../../logic/npc/npc_appearance.dart' show bodyLabel;
import '../../logic/gui/gui_json.dart';
import '../../logic/npc/npc_paths.dart';
import '../../logic/offline/new_documents.dart';
import '../../model/document_kind.dart';
import '../../services/browser.dart';
import '../../state/editor_store.dart';
import '../../state/workspace_route.dart';
import '../../ui/action_row.dart';
import '../../ui/dialog.dart';
import 'npc_list_dialogs.dart';
import 'npc_list_rows.dart';
import '../select/animus_select.dart';
import '../../ui/field.dart';

/// The Body select's value for a body typed by id.
const String _otherBody = 'other';

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

  @override
  State<NewNpcDialog> createState() => _NewNpcDialogState();
}

class _NewNpcDialogState extends State<NewNpcDialog> {
  String _name = '';
  String _body = commonBodies.first;
  String _other = '';
  late String _folder = component.folder;

  /// The file name as typed in Advanced; null follows the name.
  String? _id;
  String? _nameProblem;
  String? _bodyProblem;

  /// Why the id cannot be used, or why the workspace refused the NPC.
  String? _problem;
  bool _busy = false;

  String get _proposed => newNpcId(_name, _folder, component.taken);

  String get _type => _body == _otherBody ? _other.trim() : _body;

  Future<void> _create() async {
    if (_busy) return;
    final String name = _name.trim();
    final String id = (_id ?? _proposed).trim();
    final String? problem = newNpcProblem(id, _folder, component.taken);
    setState(() {
      _nameProblem = name.isEmpty ? 'Give the NPC a name.' : null;
      _bodyProblem = _type.isEmpty ? 'Type the body id.' : null;
      _problem = problem;
    });
    if (_nameProblem != null || _bodyProblem != null || _problem != null) return;
    final String documentId = npcIdIn(_folder, id);
    final JsonMap json = newNpc(
      NpcSeed(name: name, type: _type, group: _folder, id: id, world: component.world, owner: component.owner),
      uuid: newUuid(),
    );
    setState(() => _busy = true);
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
              child: AnimusSelect.values(
                id: 'npc-new-body',
                value: _body,
                values: const <String>[...commonBodies, _otherBody],
                labels: <String, String>{for (final String body in commonBodies) body: bodyLabel(body), _otherBody: 'Other'},
                onChanged: (String? value) => setState(() => _body = value ?? commonBodies.first),
              ),
            ),
            if (_body == _otherBody)
              Field(
                label: 'Body id',
                forId: 'npc-new-body-id',
                error: _bodyProblem,
                child: TextInput(
                  id: 'npc-new-body-id',
                  value: _other,
                  placeholder: 'minecraft:fox',
                  onChanged: (String value) => setState(() {
                    _other = value;
                    _bodyProblem = null;
                  }),
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
