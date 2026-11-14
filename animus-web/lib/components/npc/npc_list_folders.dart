/// Rename folder and Move folder (Advanced, the NPC page's "..." menu): a
/// folder under `npcs/` with every NPC in it and below it moves as one undo
/// step. A move to a typed path makes a folder, so there is no New folder,
/// and a folder exists only while it holds NPCs, so there is nothing to
/// delete.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../logic/npc/npc_paths.dart';
import '../../ui/action_row.dart';
import '../../ui/dialog.dart';
import 'npc_list_dialogs.dart';
import 'npc_list_rows.dart';
import '../select/animus_select.dart';
import '../../ui/field.dart';

enum FolderAction { rename, move }

class NpcFolderDialog extends StatefulWidget {
  const NpcFolderDialog({required this.action, required this.folders, required this.onSubmit, required this.onClose, this.initial, super.key});

  final FolderAction action;

  /// Every folder that holds NPCs.
  final List<String> folders;

  /// The folder picked first (the list's folder filter).
  final String? initial;

  /// Moves folder `from` to `to`; returns why it cannot, or null once done.
  final Future<String?> Function(String from, String to) onSubmit;
  final void Function() onClose;

  @override
  State<NpcFolderDialog> createState() => _NpcFolderDialogState();
}

class _NpcFolderDialogState extends State<NpcFolderDialog> {
  late String? _from = component.folders.contains(component.initial) ? component.initial : component.folders.firstOrNull;
  late String _name = _from == null ? '' : npcFolderName(_from!);
  String? _into;
  String? _problem;
  bool _busy = false;

  bool get _rename => component.action == FolderAction.rename;

  /// Where a move may go: `npcs/` and every folder outside [from] other
  /// than the one it is in.
  List<String> _targets(String from) => <String>[
        if (npcParentFolder(from).isNotEmpty) rootFolder,
        for (final String folder in component.folders)
          if (!npcFolderWithin(folder, from) && folder != npcParentFolder(from)) folder,
      ];

  String? get _to {
    final String? from = _from;
    if (from == null) return null;
    if (_rename) return _name.trim().isEmpty ? null : npcIdIn(npcParentFolder(from), normalizeNpcFolder(_name));
    final String? into = _into;
    return into == null ? null : npcIdIn(folderOf(into), npcFolderName(from));
  }

  Future<void> _submit() async {
    final String? from = _from;
    final String? to = _to;
    if (from == null || to == null || _busy) return;
    setState(() => _busy = true);
    final String? problem = await component.onSubmit(from, to);
    if (mounted) {
      setState(() {
        _busy = false;
        _problem = problem;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final String? from = _from;
    final List<String> targets = from == null ? const <String>[] : _targets(from);
    return AppDialog(
      open: true,
      id: 'npc-folder-dialog',
      title: _rename ? 'Rename folder' : 'Move folder',
      onClose: component.onClose,
      footer: DialogFooter(
        cancel: RowAction('Cancel', component.onClose),
        primary: RowAction(_rename ? 'Rename' : 'Move', _to == null ? null : _submit, loading: _busy),
      ),
      children: <Widget>[
        dialogFields(<Widget>[
          Field(
            label: 'Folder',
            forId: 'npc-folder-from',
            child: AnimusSelect.values(
              id: 'npc-folder-from',
              value: from,
              values: component.folders,
              labels: <String, String>{for (final String folder in component.folders) folder: folderLabel(folder)},
              noneLabel: 'Pick a folder',
              onChanged: (String? value) => setState(() {
                _from = value;
                _name = value == null ? '' : npcFolderName(value);
                _into = null;
                _problem = null;
              }),
            ),
          ),
          if (_rename)
            Field(
              label: 'New name',
              forId: 'npc-folder-name',
              error: _problem,
              child: TextInput(
                id: 'npc-folder-name',
                value: _name,
                onChanged: (String value) => setState(() {
                  _name = value;
                  _problem = null;
                }),
                onSubmitted: (String _) => _submit(),
              ),
            )
          else
            Field(
              label: 'Move into',
              forId: 'npc-folder-into',
              error: _problem,
              child: AnimusSelect.values(
                id: 'npc-folder-into',
                value: _into != null && targets.contains(_into) ? _into : null,
                values: targets,
                labels: folderLabels(component.folders),
                noneLabel: 'Pick a folder',
                onChanged: (String? value) => setState(() {
                  _into = value;
                  _problem = null;
                }),
              ),
            ),
        ]),
      ],
    );
  }
}
