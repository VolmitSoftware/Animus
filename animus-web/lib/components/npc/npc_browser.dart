/// The NPC browser: every NPC of the workspace, searchable, with its folder
/// (`npcs/<group>/`), tags and features, and bulk edits on a selection.
/// Each bulk edit is one workspace commit (one undo step) and, on a server
/// workspace, one change set at the next apply.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/gui/gui_text.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/npc/group_model.dart';
import '../../logic/npc/npc_bulk.dart';
import '../../logic/npc/npc_catalog.dart';
import '../../logic/npc/npc_paths.dart';
import '../../model/document_kind.dart';
import '../../services/skin_images.dart';
import '../../state/editor_store.dart';
import '../../state/live_session.dart';
import '../../state/workspace_repository.dart';
import '../../state/workspace_route.dart';
import '../gui/gui_fields.dart';
import 'npc_folders.dart';
import '../../logic/inspect/schema_fields.dart';
import '../select/suggest_input.dart';

/// A bulk action that waits for a second press.
enum _Confirm { none, delete, despawn }

class NpcBrowser extends StatefulWidget {
  const NpcBrowser({required this.store, required this.workspace, this.session, super.key});

  final EditorStore store;
  final WorkspaceSnapshot workspace;

  /// Live editing when the workspace belongs to a paired server.
  final LiveSession? session;

  @override
  State<NpcBrowser> createState() => _NpcBrowserState();
}

class _NpcBrowserState extends State<NpcBrowser> {
  String _search = '';
  final Set<String> _selected = <String>{};
  String _tag = '';
  String _folder = '';
  String? _module;
  _Confirm _confirm = _Confirm.none;
  bool _busy = false;

  /// The bulk move's picked folder (`/` is `npcs/`) and a typed new path,
  /// which wins when set.
  String _moveTarget = '/';

  /// The group assign form: which group, and which NPCs (`selection`,
  /// `folder` or `tag`, the server's `ids=`, `folder=` and `tag=`).
  String? _assignGroup;
  String _assignBy = 'selection';
  String? _assignFolder;
  String? _assignTag;

  List<NpcEntry> get _all => npcEntries(component.workspace.documents);

  /// Bumped when a filter (not typing) changes the search, so the field
  /// shows the new text.
  int _searchEpoch = 0;

  void _typeSearch(String value) => setState(() {
    _search = value;
    _confirm = _Confirm.none;
  });

  void _setSearch(String value) => setState(() {
    _search = value;
    _searchEpoch++;
    _confirm = _Confirm.none;
  });

  Future<void> _bulk(String action, NpcBulkResult result, {String? summary}) async {
    if (result.changes.isEmpty) {
      ArcaneSonner.warning(summary ?? describeBulk(action, result));
      return;
    }
    setState(() => _busy = true);
    try {
      await component.store.commit(documents: result.changes);
      final Set<String> removed = <String>{
        for (final DocumentChange change in result.changes)
          if (change.source == null) change.id,
      };
      final Set<String> written = <String>{
        for (final DocumentChange change in result.changes)
          if (change.source != null) change.id,
      };
      final String message = summary ?? describeBulk(action, result);
      if (result.skipped.isEmpty) {
        ArcaneSonner.success(message);
      } else {
        ArcaneSonner.warning(message);
      }
      if (mounted) {
        setState(() {
          _selected
            ..removeAll(removed)
            ..addAll(written);
          _confirm = _Confirm.none;
        });
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _spawnOnServer(List<NpcEntry> selected, bool spawn) async {
    final LiveSession? session = component.session;
    if (session == null) return;
    setState(() {
      _busy = true;
      _confirm = _Confirm.none;
    });
    try {
      await session.spawnNpcs(<String>[for (final NpcEntry entry in selected) entry.key], spawn: spawn);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final List<NpcEntry> all = _all;
    final Set<String> keys = <String>{for (final NpcEntry entry in all) entry.key};
    _selected.retainAll(keys);
    final List<NpcEntry> shown = NpcQuery.parse(_search).filter(all);
    final List<NpcEntry> selected = <NpcEntry>[
      for (final NpcEntry entry in all)
        if (_selected.contains(entry.key)) entry,
    ];
    final bool allShown = shown.isNotEmpty && shown.every((NpcEntry entry) => _selected.contains(entry.key));
    return dom.section(
      classes: 'animus-panel animus-npc-browser',
      attributes: const <String, String>{'aria-label': 'NPC browser'},
      <Widget>[
        dom.header(classes: 'animus-npc-browser-head', <Widget>[
          dom.h2(<Widget>[
            const Component.text('NPCs'),
            dom.span(classes: 'animus-count', <Widget>[Component.text(shown.length == all.length ? '${all.length}' : '${shown.length} of ${all.length}')]),
          ]),
          dom.div(classes: 'animus-npc-search', <Widget>[
            GuiTextInput(
              key: ValueKey<String>('npc-search:$_searchEpoch'),
              value: _search,
              label: 'Search NPCs',
              placeholder: 'Name, id, tag:guard, has:combat',
              onChanged: _typeSearch,
            ),
            if (_search.isNotEmpty) Button.ghost(label: 'Clear search', size: ButtonSize.small, onPressed: () => _setSearch('')),
          ]),
        ]),
        _filters(all),
        dom.div(classes: 'animus-npc-browser-body', <Widget>[
          _folders(all),
          dom.div(classes: 'animus-npc-browser-main', <Widget>[
            _groupAssign(all, selected),
            if (selected.isNotEmpty) _bulkBar(all, selected, keys),
            _list(shown, allShown),
          ]),
        ]),
      ],
    );
  }

  Widget _chip(String label, bool active, void Function() onPressed, {String? count}) => Button(
    label: count == null ? label : '$label $count',
    size: ButtonSize.small,
    variant: active ? ButtonVariant.primary : ButtonVariant.ghost,
    attributes: <String, String>{'aria-pressed': '$active'},
    onPressed: onPressed,
  );

  Widget _filters(List<NpcEntry> all) {
    final List<String> types = npcValues(all, (NpcEntry entry) => entry.type);
    final List<String> worlds = npcValues(all, (NpcEntry entry) => entry.world);
    String? current(String field) {
      for (final NpcTerm term in NpcQuery.parse(_search).terms) {
        if (term.field == field && !term.negated) return term.value;
      }
      return null;
    }

    void pick(String field, String? value) {
      final String? old = current(field);
      String next = _search;
      if (old != null) next = toggleTerm(next, field, old);
      if (value != null) next = toggleTerm(next, field, value);
      _setSearch(next);
    }

    return dom.div(
      classes: 'animus-npc-filters',
      attributes: const <String, String>{'role': 'group', 'aria-label': 'Filters'},
      <Widget>[
        for (final NpcFeature feature in NpcFeature.values)
          _chip(
            feature.label,
            hasTerm(_search, 'has', feature.key),
            () => _setSearch(toggleTerm(_search, 'has', feature.key)),
            count: '${all.where((NpcEntry entry) => entry.has(feature)).length}',
          ),
        for (final String state in const <String>['spawned', 'despawned', 'invalid'])
          _chip(state[0].toUpperCase() + state.substring(1), hasTerm(_search, 'is', state), () => _setSearch(toggleTerm(_search, 'is', state))),
        if (types.length > 1)
          dom.label(classes: 'animus-npc-filter-select', <Widget>[
            const dom.span(<Widget>[Component.text('Type')]),
            GuiSelect(value: current('type'), options: types, allowNone: true, noneLabel: 'Any type', onChanged: (String? value) => pick('type', value)),
          ]),
        if (worlds.length > 1)
          dom.label(classes: 'animus-npc-filter-select', <Widget>[
            const dom.span(<Widget>[Component.text('World')]),
            GuiSelect(value: current('world'), options: worlds, allowNone: true, noneLabel: 'Any world', onChanged: (String? value) => pick('world', value)),
          ]),
      ],
    );
  }

  /// The folder the search narrows to with `folder:` (subtree), or `''`.
  String get _selectedFolder {
    String folder = '';
    for (final NpcTerm term in NpcQuery.parse(_search).terms) {
      if (term.field == 'folder' && !term.negated && !term.value.startsWith('=')) folder = term.value;
    }
    return folder;
  }

  /// Filters the list to [folder] and its subtree, replacing any folder term.
  void _openFolder(String folder) {
    String next = _search;
    for (final NpcTerm term in NpcQuery.parse(_search).terms) {
      if (term.field == 'folder' && !term.negated) next = toggleTerm(next, 'folder', term.value);
    }
    if (folder.isNotEmpty) next = toggleTerm(next, 'folder', folder);
    _setSearch(next);
  }

  Widget _folders(List<NpcEntry> all) {
    final List<(String, int)> tags = npcTags(all);
    return dom.nav(
      classes: 'animus-npc-folders',
      attributes: const <String, String>{'aria-label': 'Folders and tags'},
      <Widget>[
        NpcFolderPanel(
          key: ValueKey<String>('npc-folders:${component.workspace.id}'),
          store: component.store,
          workspaceId: component.workspace.id,
          entries: all,
          selected: _selectedFolder,
          onSelect: _openFolder,
        ),
        const dom.h3(<Widget>[Component.text('Tags')]),
        if (tags.isEmpty)
          const dom.p(classes: 'animus-muted', <Widget>[Component.text('No tags yet. Select NPCs and add one.')])
        else
          dom.div(classes: 'animus-npc-tag-cloud', <Widget>[
            for (final (String tag, int count) in tags)
              _chip(tag, hasTerm(_search, 'tag', tag), () => _setSearch(toggleTerm(_search, 'tag', tag)), count: '$count'),
          ]),
      ],
    );
  }

  /// Assigns or unassigns a trait group for the selection, a folder or a
  /// tag, as one commit (one undo step, one change set at the next apply).
  Future<void> _changeGroup(List<NpcEntry> all, String group, NpcGroupTarget target, {required bool assign}) async {
    final NpcBulkResult result = changeGroup(all, workspaceGroups(component.workspace.documents), group, target, assign: assign);
    final String summary = describeGroupChange(group, result, assign: assign);
    if (result.changes.isEmpty) {
      ArcaneSonner.warning(summary);
      return;
    }
    setState(() => _busy = true);
    try {
      await component.store.commit(documents: result.changes, label: '${assign ? 'assign' : 'unassign'} group $group');
      ArcaneSonner.success(summary);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// The trait group form: a group, who gets it (the selection, one folder
  /// exactly, or every NPC whose effective tags include a tag), and Assign
  /// or Unassign. Shown once the workspace has a group.
  Widget _groupAssign(List<NpcEntry> all, List<NpcEntry> selected) {
    final Map<String, JsonMap> groups = workspaceGroups(component.workspace.documents);
    if (groups.isEmpty) return const dom.div(<Widget>[]);
    final String? group = _assignGroup != null && groups.containsKey(_assignGroup) ? _assignGroup : null;
    final List<String> folders = <String>[for (final NpcFolderNode node in npcFolderTree(all).all) node.path];
    final List<String> tags = <String>{
      for (final NpcEntry entry in all) ...jsonStrings((effectiveNpc(entry.json, groups)?.json ?? entry.json)['tags']),
    }.toList()
      ..sort();
    final NpcGroupTarget? target = switch (_assignBy) {
      'folder' when _assignFolder != null && folders.contains(_assignFolder) => NpcGroupTarget.folder(_assignFolder!),
      'tag' when _assignTag != null && tags.contains(_assignTag) => NpcGroupTarget.tag(_assignTag!),
      'selection' when selected.isNotEmpty => NpcGroupTarget.ids(<String>{for (final NpcEntry entry in selected) entry.key}),
      _ => null,
    };
    final int reached = target == null ? 0 : all.where((NpcEntry entry) => target.matches(entry, groups)).length;
    final bool ready = !_busy && group != null && target != null;
    String folderLabel(String path) => path.isEmpty ? 'npcs/' : 'npcs/$path/';
    return dom.section(
      classes: 'animus-npc-group-assign',
      attributes: const <String, String>{'aria-label': 'Trait groups', 'data-npc': 'group-assign'},
      <Widget>[
        const dom.h3(<Widget>[Component.text('Trait groups')]),
        dom.div(classes: 'animus-npc-group-assign-fields', <Widget>[
          dom.label(classes: 'animus-npc-filter-select', <Widget>[
            const dom.span(<Widget>[Component.text('Group')]),
            GuiSelect(
              references: DocumentKind.group,
              id: 'animus-group-assign-group',
              value: group,
              options: groups.keys.toList(),
              noneLabel: 'Pick a group',
              onChanged: (String? value) => setState(() => _assignGroup = value),
            ),
          ]),
          dom.label(classes: 'animus-npc-filter-select', <Widget>[
            const dom.span(<Widget>[Component.text('For')]),
            GuiSelect(
              id: 'animus-group-assign-by',
              value: _assignBy,
              options: const <String>['selection', 'folder', 'tag'],
              labels: <String, String>{
                'selection': 'Selected NPCs (${selected.length})',
                'folder': 'One folder',
                'tag': 'NPCs with a tag',
              },
              onChanged: (String? value) => setState(() => _assignBy = value ?? 'selection'),
            ),
          ]),
          if (_assignBy == 'folder')
            dom.label(classes: 'animus-npc-filter-select', <Widget>[
              const dom.span(<Widget>[Component.text('Folder')]),
              GuiSelect(
                id: 'animus-group-assign-folder',
                value: _assignFolder != null && folders.contains(_assignFolder) ? _assignFolder : null,
                options: folders,
                labels: <String, String>{for (final String path in folders) path: folderLabel(path)},
                noneLabel: 'Pick a folder',
                onChanged: (String? value) => setState(() => _assignFolder = value),
              ),
            ]),
          if (_assignBy == 'tag')
            dom.label(classes: 'animus-npc-filter-select', <Widget>[
              const dom.span(<Widget>[Component.text('Tag')]),
              GuiSelect(
                id: 'animus-group-assign-tag',
                value: _assignTag != null && tags.contains(_assignTag) ? _assignTag : null,
                options: tags,
                noneLabel: tags.isEmpty ? 'No tags yet' : 'Pick a tag',
                onChanged: (String? value) => setState(() => _assignTag = value),
              ),
            ]),
        ]),
        dom.div(classes: 'animus-row', <Widget>[
          Button.secondary(
            id: 'animus-group-assign',
            label: 'Assign',
            size: ButtonSize.small,
            disabled: !ready,
            onPressed: () => _changeGroup(all, group!, target!, assign: true),
          ),
          Button.ghost(
            id: 'animus-group-unassign',
            label: 'Unassign',
            size: ButtonSize.small,
            disabled: !ready,
            onPressed: () => _changeGroup(all, group!, target!, assign: false),
          ),
          dom.span(classes: 'animus-muted', attributes: const <String, String>{'role': 'status', 'data-npc': 'group-assign-reach'}, <Widget>[
            Component.text(switch ((group, target)) {
              (null, _) => 'Pick a group.',
              (_, null) => _assignBy == 'selection' ? 'Select NPCs in the list.' : 'Pick the ${_assignBy == 'folder' ? 'folder' : 'tag'}.',
              _ => '$reached NPC${reached == 1 ? '' : 's'} affected.',
            }),
          ]),
        ]),
      ],
    );
  }

  Widget _bulkBar(List<NpcEntry> all, List<NpcEntry> selected, Set<String> keys) {
    final List<String> selectedTags = npcTags(selected).map(((String, int) tag) => tag.$1).toList();
    final List<String> modules = moduleKeys(selected);
    final String? module = _module != null && modules.contains(_module) ? _module : null;
    final LiveSession? session = component.session;
    final bool canSpawn = session?.connection.token?.has('spawn') ?? false;
    final String count = '${selected.length} NPC${selected.length == 1 ? '' : 's'}';
    return dom.div(
      classes: 'animus-npc-bulk',
      attributes: const <String, String>{'role': 'group', 'aria-label': 'Bulk edit'},
      events: <String, void Function(web.Event)>{
        'keydown': (web.Event event) {
          if ((event as web.KeyboardEvent).key == 'Escape' && _confirm != _Confirm.none) {
            event.preventDefault();
            setState(() => _confirm = _Confirm.none);
          }
        },
      },
      <Widget>[
        dom.div(classes: 'animus-npc-bulk-head', <Widget>[
          dom.strong(<Widget>[Component.text('$count selected')]),
          Button.ghost(label: 'Clear selection', size: ButtonSize.small, onPressed: () => setState(() => _selected.clear())),
        ]),
        dom.div(classes: 'animus-npc-bulk-group', <Widget>[
          const dom.span(classes: 'animus-npc-bulk-label', <Widget>[Component.text('Tags')]),
          SuggestInput(
            value: _tag,
            label: 'Tag',
            placeholder: 'guard',
            suggestions: <FieldOption>[for (final (String tag, int _) in npcTags(all)) FieldOption(tag)],
            onChanged: (String value) => setState(() => _tag = value.trim().toLowerCase()),
          ),
          Button.secondary(
            label: 'Add tag',
            size: ButtonSize.small,
            disabled: _busy || !npcTagPattern.hasMatch(_tag),
            onPressed: () => _bulk('Tagged', addTag(selected, _tag)),
          ),
          if (selectedTags.isNotEmpty)
            GuiSelect(
              value: null,
              options: selectedTags,
              label: 'Remove a tag',
              noneLabel: 'Pick a tag to remove',
              onChanged: (String? tag) {
                if (tag != null) _bulk('Untagged', removeTag(selected, tag));
              },
            ),
        ]),
        _moveGroup(all, selected, keys),
        dom.div(classes: 'animus-npc-bulk-group', <Widget>[
          const dom.span(classes: 'animus-npc-bulk-label', <Widget>[Component.text('Module')]),
          if (modules.isEmpty)
            const dom.span(classes: 'animus-muted', <Widget>[Component.text('No modules.')])
          else
            GuiSelect(value: module, options: modules, label: 'Module to change', noneLabel: 'Pick a module', onChanged: (String? value) => setState(() => _module = value)),
          Button.secondary(
            label: 'Enable',
            size: ButtonSize.small,
            disabled: _busy || module == null || !toggleableModules.contains(module),
            onPressed: () => _bulk('Enabled $module on', setModuleEnabled(selected, module!, true)),
          ),
          Button.secondary(
            label: 'Disable',
            size: ButtonSize.small,
            disabled: _busy || module == null || !toggleableModules.contains(module),
            onPressed: () => _bulk('Disabled $module on', setModuleEnabled(selected, module!, false)),
          ),
          Button.ghost(
            label: 'Remove module',
            size: ButtonSize.small,
            disabled: _busy || module == null,
            onPressed: () => _bulk('Removed $module from', removeModule(selected, module!)),
          ),
        ]),
        dom.div(classes: 'animus-npc-bulk-group', <Widget>[
          const dom.span(classes: 'animus-npc-bulk-label', <Widget>[Component.text('Presence')]),
          Button.secondary(label: 'Set spawned', size: ButtonSize.small, disabled: _busy, onPressed: () => _bulk('Set spawned on', setSpawned(selected, true))),
          Button.secondary(
            label: 'Set despawned',
            size: ButtonSize.small,
            disabled: _busy,
            onPressed: () => _bulk('Set despawned on', setSpawned(selected, false)),
          ),
          if (session != null && canSpawn) ...<Widget>[
            Button.secondary(
              label: 'Spawn on server',
              size: ButtonSize.small,
              disabled: _busy || !session.online,
              onPressed: () => _spawnOnServer(selected, true),
            ),
            if (_confirm != _Confirm.despawn)
              Button.secondary(
                label: 'Despawn on server',
                size: ButtonSize.small,
                disabled: _busy || !session.online,
                onPressed: () => setState(() => _confirm = _Confirm.despawn),
              )
            else ...<Widget>[
              Button.destructive(label: 'Despawn $count now', size: ButtonSize.small, disabled: _busy || !session.online, onPressed: () => _spawnOnServer(selected, false)),
              Button.ghost(label: 'Keep them spawned', size: ButtonSize.small, onPressed: () => setState(() => _confirm = _Confirm.none)),
            ],
          ],
          if (session != null && !canSpawn)
            const dom.span(classes: 'animus-muted', <Widget>[
              Component.text('Spawning needs the spawn scope. Pair again with it.'),
            ]),
        ]),
        dom.div(classes: 'animus-npc-bulk-group', <Widget>[
          if (_confirm != _Confirm.delete)
            Button.ghost(label: 'Delete', size: ButtonSize.small, disabled: _busy, onPressed: () => setState(() => _confirm = _Confirm.delete))
          else ...<Widget>[
            Button.destructive(label: 'Delete $count', size: ButtonSize.small, disabled: _busy, onPressed: () => _bulk('Deleted', deleteNpcs(selected))),
            Button.ghost(label: 'Keep them', size: ButtonSize.small, onPressed: () => setState(() => _confirm = _Confirm.none)),
            const dom.span(classes: 'animus-muted', <Widget>[Component.text('Undo restores them.')]),
          ],
        ]),
        if (_confirm == _Confirm.despawn)
          const dom.p(classes: 'animus-muted', <Widget>[Component.text('Acts on the server now. Undo cannot revert it.')]),
        if (session != null)
          const dom.p(classes: 'animus-muted', <Widget>[
            Component.text('Apply sends edits. Spawn and despawn act now.'),
          ]),
      ],
    );
  }

  /// Move to folder: the tree as a picker, or a typed path for a new folder.
  Widget _moveGroup(List<NpcEntry> all, List<NpcEntry> selected, Set<String> keys) {
    final String typed = normalizeNpcFolder(_folder);
    final String target = _folder.trim().isNotEmpty ? typed : (_moveTarget == '/' ? '' : _moveTarget);
    final String? problem = _folder.trim().isEmpty ? null : npcFolderProblem(typed);
    return dom.div(classes: 'animus-npc-bulk-group', <Widget>[
      const dom.span(classes: 'animus-npc-bulk-label', <Widget>[Component.text('Folder')]),
      NpcFolderPicker(
        root: npcFolderTree(all, extra: NpcFolderMemory.created(component.workspace.id)),
        value: _moveTarget,
        label: 'Move to',
        onChanged: (String value) => setState(() => _moveTarget = value),
      ),
      const dom.label(classes: 'animus-npc-folder-label', htmlFor: 'animus-npc-move-path', <Widget>[Component.text('Or a new path')]),
      GuiTextInput(
        id: 'animus-npc-move-path',
        value: _folder,
        label: 'Or a new path',
        placeholder: 'town/market',
        onChanged: (String value) => setState(() => _folder = value),
      ),
      Button.secondary(
        label: 'Move to npcs/${target.isEmpty ? '' : '$target/'}',
        size: ButtonSize.small,
        disabled: _busy || problem != null,
        attributes: const <String, String>{'data-testid': 'bulk-move'},
        onPressed: () => _bulk('Moved', moveToFolder(selected, target, keys)),
      ),
      if (problem != null) dom.span(classes: 'animus-error', attributes: const <String, String>{'role': 'alert'}, <Widget>[Component.text(problem)]),
    ]);
  }

  Widget _list(List<NpcEntry> shown, bool allShown) {
    if (shown.isEmpty) {
      if (_all.isEmpty) {
        return dom.div(classes: 'animus-npc-empty', <Widget>[
          const dom.p(<Widget>[Component.text('No NPCs yet.')]),
          dom.a(
            classes: 'animus-npc-empty-action',
            href: NewDocumentRoute(component.workspace.id, DocumentKind.npc).format(),
            <Widget>[const Component.text('Create an NPC')],
          ),
        ]);
      }
      final String folder = _selectedFolder;
      if (folder.isNotEmpty && NpcQuery.parse(_search).terms.length == 1) {
        return dom.div(classes: 'animus-npc-empty', <Widget>[
          dom.p(<Widget>[Component.text('npcs/$folder/ is empty.')]),
          dom.a(
            classes: 'animus-npc-empty-action',
            href: NewDocumentRoute.inFolder(component.workspace.id, DocumentKind.npc, folder).format(),
            <Widget>[Component.text('New NPC in npcs/$folder/')],
          ),
          Button.secondary(label: 'Show every NPC', size: ButtonSize.small, onPressed: () => _setSearch('')),
        ]);
      }
      return dom.div(classes: 'animus-npc-empty', <Widget>[
        dom.p(<Widget>[Component.text('No NPC matches "${_search.trim()}".')]),
        Button.secondary(label: 'Clear search', size: ButtonSize.small, onPressed: () => _setSearch('')),
      ]);
    }
    return dom.div(
      classes: 'animus-npc-list',
      attributes: const <String, String>{'role': 'list', 'aria-label': 'NPCs'},
      <Widget>[
        dom.div(classes: 'animus-npc-row is-head', <Widget>[
          dom.label(classes: 'animus-npc-check', <Widget>[
          dom.input(
            type: dom.InputType.checkbox,
            checked: allShown,
            attributes: const <String, String>{'aria-label': 'Select every NPC shown'},
            events: <String, void Function(web.Event)>{
              'change': (web.Event event) {
                final bool on = (event.target! as web.HTMLInputElement).checked;
                setState(() {
                  for (final NpcEntry entry in shown) {
                    if (on) {
                      _selected.add(entry.key);
                    } else {
                      _selected.remove(entry.key);
                    }
                  }
                  _confirm = _Confirm.none;
                });
              },
            },
          ),
          ]),
          const dom.span(<Widget>[Component.text('Name')]),
          const dom.span(classes: 'animus-npc-col-type', <Widget>[Component.text('Type')]),
          const dom.span(classes: 'animus-npc-col-world', <Widget>[Component.text('World')]),
          const dom.span(<Widget>[Component.text('Tags and features')]),
        ]),
        for (final NpcEntry entry in shown) _row(entry),
      ],
    );
  }

  Widget _row(NpcEntry entry) {
    final bool selected = _selected.contains(entry.key);
    return dom.div(
      key: ValueKey<String>('npc-row:${entry.key}'),
      classes: 'animus-npc-row${selected ? ' is-selected' : ''}',
      attributes: <String, String>{'role': 'listitem', 'data-npc': entry.key},
      <Widget>[
        dom.label(classes: 'animus-npc-check', <Widget>[
        dom.input(
          type: dom.InputType.checkbox,
          checked: selected,
          attributes: <String, String>{'aria-label': 'Select ${entry.key}'},
          events: <String, void Function(web.Event)>{
            'change': (web.Event event) {
              final bool on = (event.target! as web.HTMLInputElement).checked;
              setState(() {
                if (on) {
                  _selected.add(entry.key);
                } else {
                  _selected.remove(entry.key);
                }
                _confirm = _Confirm.none;
              });
            },
          },
        ),
        ]),
        dom.div(classes: 'animus-npc-name', <Widget>[
          _face(entry),
          dom.div(classes: 'animus-npc-name-text', <Widget>[
            dom.a(href: DocumentRoute(component.workspace.id, DocumentKind.npc, entry.key).format(), <Widget>[Component.text(guiPlainText(entry.name))]),
            dom.code(classes: 'animus-muted', <Widget>[Component.text(entry.key)]),
          ]),
        ]),
        dom.span(classes: 'animus-npc-col-type', <Widget>[Component.text(entry.type.startsWith('minecraft:') ? entry.type.substring(10) : entry.type)]),
        dom.span(classes: 'animus-npc-col-world', <Widget>[Component.text(entry.world)]),
        dom.div(classes: 'animus-npc-badges', <Widget>[
          for (final String tag in entry.tags)
            dom.span(classes: 'animus-npc-tag', attributes: <String, String>{'data-tag': tag}, <Widget>[Component.text(tag)]),
          for (final NpcFeature feature in entry.features)
            if (feature != NpcFeature.behaviors && feature != NpcFeature.skin)
              dom.span(classes: 'animus-tag', attributes: <String, String>{'data-feature': feature.key}, <Widget>[Component.text(feature.label)]),
          if (!entry.spawned) const dom.span(classes: 'animus-tag is-muted', <Widget>[Component.text('despawned')]),
          if (!entry.valid)
            dom.span(
              classes: 'animus-badge is-error',
              attributes: <String, String>{
                'title': '${entry.errors} error${entry.errors == 1 ? '' : 's'}',
                'aria-label': '${entry.errors} error${entry.errors == 1 ? '' : 's'}',
              },
              <Widget>[Component.text('${entry.errors}')],
            ),
        ]),
      ],
    );
  }

  /// The skin's face when its PNG is loaded, else a plain placeholder head.
  Widget _face(NpcEntry entry) {
    final Object? skin = entry.json['skin'];
    final LoadedSkin? loaded = skin is String ? SkinImages.forKey(skin) : null;
    if (loaded == null) return const dom.span(classes: 'animus-skin-face is-blank is-small', attributes: <String, String>{'aria-hidden': 'true'}, <Widget>[]);
    return dom.span(
      classes: 'animus-skin-face is-small',
      attributes: const <String, String>{'aria-hidden': 'true'},
      styles: dom.Styles(raw: <String, String>{'background-image': 'url("${loaded.dataUrl}")'}),
      const <Widget>[],
    );
  }
}
