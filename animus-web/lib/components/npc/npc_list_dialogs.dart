/// The NPC list's bulk dialogs (Advanced): tags, folder, module and trait
/// group for the picked NPCs. Each one gathers its input and hands it to
/// the list, which edits the NPCs as one undo step.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/inspect/schema_fields.dart';
import '../../logic/npc/npc_catalog.dart';
import '../../logic/npc/npc_paths.dart';
import '../../ui/action_row.dart';
import '../../ui/dialog.dart';
import '../../ui/field.dart';
import '../../ui/toggle_choice.dart';
import '../select/suggest_input.dart';
import 'npc_list_rows.dart';
import '../select/animus_select.dart';
import 'npc_layout.dart';

/// The fields of a dialog, spaced as a form.
Widget dialogFields(List<Widget> children) => tabStack(children);

/// "3 NPCs", "1 NPC".
String npcCount(int count) => '$count NPC${count == 1 ? '' : 's'}';

/// The root folder's value in folder selects (an empty value means none).
const String rootFolder = '/';

/// [folders] as select options with `npcs/` first.
List<String> folderOptions(List<String> folders) => <String>[rootFolder, ...folders];

Map<String, String> folderLabels(List<String> folders) => <String, String>{
      rootFolder: 'npcs/',
      for (final String folder in folders) folder: folderLabel(folder),
    };

/// The folder a select's value names.
String folderOf(String value) => value == rootFolder ? '' : value;

/// Add or remove one tag on the picked NPCs.
class NpcTagsDialog extends StatefulWidget {
  const NpcTagsDialog({required this.count, required this.known, required this.carried, required this.onSubmit, required this.onClose, super.key});

  final int count;

  /// Tags in use in the workspace, offered while typing.
  final List<String> known;

  /// Tags the picked NPCs carry, offered for removal.
  final List<String> carried;
  final void Function(String tag, {required bool remove}) onSubmit;
  final void Function() onClose;

  @override
  State<NpcTagsDialog> createState() => _NpcTagsDialogState();
}

class _NpcTagsDialogState extends State<NpcTagsDialog> {
  bool _remove = false;
  String _tag = '';
  String? _problem;

  void _submit() {
    final String tag = _tag.trim().toLowerCase();
    if (_remove) {
      if (component.carried.contains(tag)) component.onSubmit(tag, remove: true);
      return;
    }
    if (!npcTagPattern.hasMatch(tag)) {
      setState(() => _problem = 'Use 1-32 lowercase letters, digits, _ or -.');
      return;
    }
    component.onSubmit(tag, remove: false);
  }

  @override
  Widget build(BuildContext context) {
    final bool ready = _remove ? component.carried.contains(_tag) : _tag.trim().isNotEmpty;
    return AppDialog(
      open: true,
      id: 'npc-tags-dialog',
      title: 'Tags for ${npcCount(component.count)}',
      onClose: component.onClose,
      footer: DialogFooter(
        cancel: RowAction('Cancel', component.onClose),
        primary: RowAction(_remove ? 'Remove tag' : 'Add tag', ready ? _submit : null),
      ),
      children: <Widget>[
        dialogFields(<Widget>[
          ToggleChoice<bool>(
            label: 'Add or remove',
            options: const <ChoiceOption<bool>>[ChoiceOption<bool>(false, 'Add'), ChoiceOption<bool>(true, 'Remove')],
            value: _remove,
            onChanged: (bool remove) => setState(() {
              _remove = remove;
              _tag = '';
              _problem = null;
            }),
          ),
          if (_remove)
            Field(
              label: 'Tag',
              forId: 'npc-untag',
              child: AnimusSelect.values(
                id: 'npc-untag',
                value: component.carried.contains(_tag) ? _tag : null,
                values: component.carried,
                noneLabel: component.carried.isEmpty ? 'No tags' : 'Pick a tag',
                disabled: component.carried.isEmpty,
                onChanged: (String? tag) => setState(() => _tag = tag ?? ''),
              ),
            )
          else
            Field(
              label: 'Tag',
              forId: 'npc-tag',
              error: _problem,
              child: SuggestInput(
                id: 'npc-tag',
                value: _tag,
                suggestions: <FieldOption>[for (final String tag in component.known) FieldOption(tag)],
                onChanged: (String value) => setState(() {
                  _tag = value;
                  _problem = null;
                }),
                onSubmit: _submit,
              ),
            ),
        ]),
      ],
    );
  }
}

/// Move the picked NPCs to a folder, or to a new one typed as a path.
class NpcMoveDialog extends StatefulWidget {
  const NpcMoveDialog({required this.count, required this.folders, required this.onSubmit, required this.onClose, super.key});

  final int count;
  final List<String> folders;
  final void Function(String folder) onSubmit;
  final void Function() onClose;

  @override
  State<NpcMoveDialog> createState() => _NpcMoveDialogState();
}

class _NpcMoveDialogState extends State<NpcMoveDialog> {
  String _pick = rootFolder;
  String _typed = '';

  String get _target => _typed.trim().isNotEmpty ? normalizeNpcFolder(_typed) : folderOf(_pick);

  String? get _problem => _typed.trim().isEmpty ? null : npcFolderProblem(_target);

  void _submit() {
    if (_problem == null) component.onSubmit(_target);
  }

  @override
  Widget build(BuildContext context) => AppDialog(
        open: true,
        id: 'npc-move-dialog',
        title: 'Move ${npcCount(component.count)}',
        onClose: component.onClose,
        footer: DialogFooter(
          cancel: RowAction('Cancel', component.onClose),
          primary: RowAction('Move', _problem == null ? _submit : null),
        ),
        children: <Widget>[
          dialogFields(<Widget>[
            Field(
              label: 'Folder',
              forId: 'npc-move-folder',
              child: AnimusSelect.values(
                id: 'npc-move-folder',
                value: _pick,
                values: folderOptions(component.folders),
                labels: folderLabels(component.folders),
                disabled: _typed.trim().isNotEmpty,
                onChanged: (String? value) => setState(() => _pick = value ?? rootFolder),
              ),
            ),
            Field(
              label: 'Or a new folder',
              forId: 'npc-move-path',
              error: _problem,
              child: TextInput(
                id: 'npc-move-path',
                value: _typed,
                onChanged: (String value) => setState(() => _typed = value),
                onSubmitted: (String _) => _submit(),
              ),
            ),
          ]),
        ],
      );
}

/// What the Module dialog does to the picked module.
enum ModuleChange { on, off, remove }

/// Turn a module the picked NPCs have on or off, or remove it.
class NpcModuleDialog extends StatefulWidget {
  const NpcModuleDialog({required this.count, required this.modules, required this.onSubmit, required this.onClose, super.key});

  final int count;

  /// The modules the picked NPCs carry ([selectionModules]).
  final List<String> modules;
  final void Function(String module, ModuleChange change) onSubmit;
  final void Function() onClose;

  @override
  State<NpcModuleDialog> createState() => _NpcModuleDialogState();
}

class _NpcModuleDialogState extends State<NpcModuleDialog> {
  late String? _module = component.modules.firstOrNull;
  ModuleChange _change = ModuleChange.off;

  bool get _switchable => _module != null && toggleableModules.contains(_module);

  @override
  Widget build(BuildContext context) {
    final String? module = _module;
    final ModuleChange change = _switchable ? _change : ModuleChange.remove;
    return AppDialog(
      open: true,
      id: 'npc-module-dialog',
      title: 'Module for ${npcCount(component.count)}',
      onClose: component.onClose,
      footer: DialogFooter(
        cancel: RowAction('Cancel', component.onClose),
        primary: RowAction(
          switch (change) {
            ModuleChange.on => 'Turn on',
            ModuleChange.off => 'Turn off',
            ModuleChange.remove => 'Remove module',
          },
          module == null ? null : () => component.onSubmit(module, change),
          destructive: change == ModuleChange.remove,
        ),
      ),
      children: <Widget>[
        if (component.modules.isEmpty)
          const dom.p(<Widget>[Component.text('The picked NPCs have no modules.')])
        else
          dialogFields(<Widget>[
            Field(
              label: 'Module',
              forId: 'npc-module',
              child: AnimusSelect.values(id: 'npc-module', value: module, values: component.modules, onChanged: (String? value) => setState(() => _module = value)),
            ),
            if (_switchable)
              ToggleChoice<ModuleChange>(
                label: 'Change',
                options: const <ChoiceOption<ModuleChange>>[
                  ChoiceOption<ModuleChange>(ModuleChange.on, 'On'),
                  ChoiceOption<ModuleChange>(ModuleChange.off, 'Off'),
                  ChoiceOption<ModuleChange>(ModuleChange.remove, 'Remove'),
                ],
                value: _change,
                onChanged: (ModuleChange next) => setState(() => _change = next),
              ),
          ]),
      ],
    );
  }
}

/// Add the picked NPCs to a trait group, or take them out of one.
class NpcGroupDialog extends StatefulWidget {
  const NpcGroupDialog({required this.count, required this.groups, required this.onSubmit, required this.onClose, super.key});

  final int count;

  /// Group id to its name.
  final Map<String, String> groups;
  final void Function(String group, {required bool remove}) onSubmit;
  final void Function() onClose;

  @override
  State<NpcGroupDialog> createState() => _NpcGroupDialogState();
}

class _NpcGroupDialogState extends State<NpcGroupDialog> {
  String? _group;
  bool _remove = false;

  @override
  Widget build(BuildContext context) {
    final String? group = _group;
    return AppDialog(
      open: true,
      id: 'npc-group-dialog',
      title: 'Trait group for ${npcCount(component.count)}',
      onClose: component.onClose,
      footer: DialogFooter(
        cancel: RowAction('Cancel', component.onClose),
        primary: RowAction(_remove ? 'Remove from group' : 'Add to group', group == null ? null : () => component.onSubmit(group, remove: _remove)),
      ),
      children: <Widget>[
        dialogFields(<Widget>[
          ToggleChoice<bool>(
            label: 'Add or remove',
            options: const <ChoiceOption<bool>>[ChoiceOption<bool>(false, 'Add'), ChoiceOption<bool>(true, 'Remove')],
            value: _remove,
            onChanged: (bool remove) => setState(() => _remove = remove),
          ),
          Field(
            label: 'Trait group',
            forId: 'npc-group',
            child: AnimusSelect.values(
              id: 'npc-group',
              value: group,
              values: component.groups.keys.toList(),
              labels: component.groups,
              // Keeps the "Pick a group" row after a pick, so the list does
              // not shift under the library's selection mark.
              allowNone: true,
              noneLabel: 'Pick a group',
              onChanged: (String? value) => setState(() => _group = value),
            ),
          ),
        ]),
      ],
    );
  }
}
