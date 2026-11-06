/// The NPC browser's folder tree: `npcs/` and its nested folders with their
/// NPC counts, expand and collapse kept per workspace in this browser, and
/// the folder operations (new, rename, move, delete when empty). Renames and
/// moves write every NPC of the subtree in one commit, so one Undo reverts
/// them and, on a server workspace, the next apply sends them as moves.
library;

import 'dart:convert';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/npc/npc_bulk.dart';
import '../../logic/npc/npc_catalog.dart';
import '../../logic/npc/npc_paths.dart';
import '../../logic/npc/tree_keys.dart';
import '../../model/document_kind.dart';
import '../../state/editor_store.dart';
import '../../state/workspace_route.dart';
import '../gui/gui_fields.dart';

/// Folder state that outlives the browser component but not the page:
/// folders made here that hold no NPC yet. A folder exists only through its
/// NPCs, so these last until the page reloads or an NPC lands in them.
class NpcFolderMemory {
  NpcFolderMemory._();

  static final Map<String, Set<String>> _created = <String, Set<String>>{};

  /// The empty folders made in [workspaceId] since the page loaded.
  static Set<String> created(String workspaceId) => _created.putIfAbsent(workspaceId, () => <String>{});

  static String _key(String workspaceId) => 'animus.npc-folders.collapsed.$workspaceId';

  /// The collapsed folders of [workspaceId], kept in localStorage.
  static Set<String> collapsed(String workspaceId) {
    try {
      final String? stored = web.window.localStorage.getItem(_key(workspaceId));
      if (stored == null) return <String>{};
      final Object? decoded = jsonDecode(stored);
      return <String>{
        for (final Object? path in decoded is List<Object?> ? decoded : const <Object?>[])
          if (path is String) path,
      };
    } on Object {
      // Storage blocked or the entry unreadable: start with everything open.
      return <String>{};
    }
  }

  static void saveCollapsed(String workspaceId, Set<String> paths) {
    try {
      web.window.localStorage.setItem(_key(workspaceId), jsonEncode(paths.toList()..sort()));
    } on Object {
      // Storage blocked: the tree still works for this page.
    }
  }
}

/// [paths] with folder [from] and the folders below it renamed to [to].
Set<String> renameFolderPaths(Set<String> paths, String from, String to) => <String>{
      for (final String path in paths) npcFolderWithin(path, from) && from.isNotEmpty ? '$to${path.substring(from.length)}' : path,
    };

/// Which folder operation the panel shows its form for.
enum _Operation { none, create, rename, move, delete }

/// The folder tree beside the NPC list.
class NpcFolderPanel extends StatefulWidget {
  const NpcFolderPanel({
    required this.store,
    required this.workspaceId,
    required this.entries,
    required this.selected,
    required this.onSelect,
    super.key,
  });

  final EditorStore store;
  final String workspaceId;

  /// Every NPC of the workspace.
  final List<NpcEntry> entries;

  /// The folder the list is filtered to, `''` for none (`npcs/` and below).
  final String selected;

  /// Filters the list to a folder and its subtree; `''` shows every NPC.
  final void Function(String folder) onSelect;

  @override
  State<NpcFolderPanel> createState() => _NpcFolderPanelState();
}

class _NpcFolderPanelState extends State<NpcFolderPanel> {
  late Set<String> _collapsed = NpcFolderMemory.collapsed(component.workspaceId);

  /// The row holding the tab stop.
  int _focus = 0;

  _Operation _operation = _Operation.none;
  String _text = '';
  String _target = _rootValue;
  String? _problem;
  bool _busy = false;

  static const String _nameField = 'animus-npc-folder-name';

  /// The picker's value for `npcs/` itself; GuiSelect keeps '' for none.
  static const String _rootValue = '/';

  Set<String> get _created => NpcFolderMemory.created(component.workspaceId);

  void _setCollapsed(String path, bool collapsed) {
    setState(() {
      if (collapsed) {
        _collapsed.add(path);
      } else {
        _collapsed.remove(path);
      }
    });
    NpcFolderMemory.saveCollapsed(component.workspaceId, _collapsed);
  }

  void _start(_Operation operation, NpcFolderNode folder) => setState(() {
        _operation = operation;
        _problem = null;
        _text = operation == _Operation.rename ? folder.name : '';
        _target = _rootValue;
        if (operation != _Operation.none) _focusSoon('animus-npc-folder-op');
      });

  void _focusSoon(String id) => Future<void>.delayed(Duration.zero, () {
        final web.Element? element = web.document.getElementById(id);
        if (element == null) return;
        final web.Element target = element.querySelector('input, select, button') ?? element;
        (target as web.HTMLElement).focus();
      });

  void _cancel() {
    setState(() {
      _operation = _Operation.none;
      _problem = null;
    });
    _focusSoon('animus-npc-tree-$_focus');
  }

  void _create(NpcFolderNode parent, Set<String> existing) {
    final String name = _text.trim().toLowerCase();
    final String path = npcIdIn(parent.path, normalizeNpcFolder(name));
    final String? problem = name.isEmpty ? 'Give the folder a name.' : npcFolderProblem(path);
    if (problem != null || existing.contains(path)) {
      setState(() => _problem = problem ?? 'npcs/$path/ already exists.');
      return;
    }
    _created.add(path);
    if (parent.path.isNotEmpty || _collapsed.contains('')) _setCollapsed(parent.path, false);
    setState(() {
      _operation = _Operation.none;
      _problem = null;
    });
    component.onSelect(path);
    ArcaneSonner.success('Made npcs/$path/. Add an NPC to keep it.');
  }

  Future<void> _relocate(NpcFolderNode folder, String to, String verb) async {
    final NpcFolderEdit edit = renameNpcFolder(component.entries, folder.path, to);
    final String? problem = edit.problem;
    if (problem != null) {
      setState(() => _problem = problem);
      return;
    }
    setState(() => _busy = true);
    try {
      if (edit.changes.isNotEmpty) {
        await component.store.commit(documents: edit.changes, label: '$verb folder ${folder.path} to $to');
      }
      final Set<String> created = renameFolderPaths(_created, folder.path, to);
      _created
        ..clear()
        ..addAll(created);
      if (edit.changes.isEmpty) _created.add(to);
      _collapsed = renameFolderPaths(_collapsed, folder.path, to);
      NpcFolderMemory.saveCollapsed(component.workspaceId, _collapsed);
      if (mounted) {
        setState(() {
          _operation = _Operation.none;
          _problem = null;
        });
      }
      component.onSelect(to);
      final String moved = edit.moved == 0 ? '' : ' Moved ${edit.moved} NPC${edit.moved == 1 ? '' : 's'}.';
      ArcaneSonner.success('${verb == 'rename' ? 'Renamed' : 'Moved'} npcs/${folder.path}/ to npcs/$to/.$moved');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _delete(NpcFolderNode folder) {
    if (folder.total > 0) {
      setState(() => _problem = 'npcs/${folder.path}/ holds ${folder.total} NPC${folder.total == 1 ? '' : 's'}; move or delete them first.');
      return;
    }
    _created.removeWhere((String path) => npcFolderWithin(path, folder.path));
    setState(() {
      _operation = _Operation.none;
      _problem = null;
    });
    component.onSelect(npcParentFolder(folder.path));
    ArcaneSonner.success('Removed the empty folder npcs/${folder.path}/.');
  }

  @override
  Widget build(BuildContext context) {
    final Set<String> existingNpcFolders = <String>{for (final NpcEntry entry in component.entries) entry.folder};
    // A made folder is kept only while it is still empty.
    _created.removeWhere((String path) => existingNpcFolders.any((String folder) => npcFolderWithin(folder, path)));
    final NpcFolderNode root = npcFolderTree(component.entries, extra: _created);
    final Map<String, NpcFolderNode> byPath = <String, NpcFolderNode>{for (final NpcFolderNode node in root.all) node.path: node};
    final List<NpcFolderRow> rows = npcFolderRows(root, _collapsed);
    final int focus = _focus.clamp(0, rows.length - 1);
    final NpcFolderNode current = byPath[component.selected] ?? root;
    return dom.div(classes: 'animus-npc-folder-panel', <Widget>[
      const dom.h3(attributes: <String, String>{'id': 'animus-npc-folders-title'}, <Widget>[Component.text('Folders')]),
      dom.ul(
        classes: 'animus-npc-tree',
        attributes: const <String, String>{'role': 'tree', 'aria-labelledby': 'animus-npc-folders-title'},
        <Widget>[for (int index = 0; index < rows.length; index++) _item(rows, index, focus)],
      ),
      _actions(current, byPath.keys.toSet(), root),
    ]);
  }

  Widget _item(List<NpcFolderRow> rows, int index, int focus) {
    final NpcFolderRow row = rows[index];
    final NpcFolderNode node = row.node;
    final bool selected = node.path == component.selected;
    final int count = row.expandable && !row.expanded ? node.total : node.own;
    final List<int> siblings = <int>[
      for (int other = 0; other < rows.length; other++)
        if (rows[other].parent == row.parent) other,
    ];
    final String where = node.path.isEmpty ? 'npcs/' : 'npcs/${node.path}/';
    final String countLine = row.expandable && !row.expanded
        ? '$count NPC${count == 1 ? '' : 's'} with its folders'
        : '$count NPC${count == 1 ? '' : 's'} here';
    void choose() {
      setState(() => _focus = index);
      component.onSelect(selected && node.path.isNotEmpty ? '' : node.path);
    }

    void moveTo(int next) {
      final web.Element? element = web.document.getElementById('animus-npc-tree-$next');
      if (element != null) (element as web.HTMLElement).focus();
      setState(() => _focus = next);
    }

    return dom.li(
      key: ValueKey<String>('tree:${node.path}'),
      classes: 'animus-npc-tree-item${selected ? ' is-selected' : ''}',
      styles: dom.Styles(raw: <String, String>{'--animus-tree-level': '${row.level - 1}'}),
      attributes: <String, String>{
        'id': 'animus-npc-tree-$index',
        'role': 'treeitem',
        'data-folder': node.path,
        'aria-level': '${row.level}',
        'aria-setsize': '${siblings.length}',
        'aria-posinset': '${siblings.indexOf(index) + 1}',
        if (row.expandable) 'aria-expanded': '${row.expanded}',
        'aria-selected': '$selected',
        'aria-label': '$where, $countLine',
        'tabindex': index == focus ? '0' : '-1',
      },
      events: <String, void Function(web.Event)>{
        'click': (web.Event _) => choose(),
        'keydown': (web.Event event) {
          final String key = (event as web.KeyboardEvent).key;
          if (key == 'Enter' || key == ' ') {
            event.preventDefault();
            choose();
            return;
          }
          final (TreeStep, int)? step = treeKey(key, index, <TreeRow>[
            for (final NpcFolderRow each in rows) TreeRow(parent: each.parent, expandable: each.expandable, expanded: each.expanded),
          ]);
          if (step == null) return;
          event.preventDefault();
          switch (step.$1) {
            case TreeStep.focus:
              moveTo(step.$2);
            case TreeStep.expand:
              _setCollapsed(rows[step.$2].node.path, false);
            case TreeStep.collapse:
              _setCollapsed(rows[step.$2].node.path, true);
          }
        },
      },
      <Widget>[
        if (row.expandable)
          dom.span(
            classes: 'animus-npc-tree-toggle',
            attributes: const <String, String>{'aria-hidden': 'true', 'data-testid': 'tree-toggle'},
            events: <String, void Function(web.Event)>{
              'click': (web.Event event) {
                event.stopPropagation();
                _setCollapsed(node.path, row.expanded);
              },
            },
            <Widget>[Component.text(row.expanded ? '▾' : '▸')],
          )
        else
          const dom.span(classes: 'animus-npc-tree-toggle is-leaf', attributes: <String, String>{'aria-hidden': 'true'}, <Widget>[]),
        dom.span(classes: 'animus-npc-tree-name', <Widget>[Component.text(node.path.isEmpty ? 'npcs/' : '${node.name}/')]),
        dom.span(classes: 'animus-count', attributes: const <String, String>{'aria-hidden': 'true'}, <Widget>[Component.text('$count')]),
      ],
    );
  }

  Widget _actions(NpcFolderNode current, Set<String> existing, NpcFolderNode root) {
    final String where = current.path.isEmpty ? 'npcs/' : 'npcs/${current.path}/';
    final bool rootSelected = current.path.isEmpty;
    final List<Widget> form = switch (_operation) {
      _Operation.none => const <Widget>[],
      _Operation.create => <Widget>[
          dom.label(classes: 'animus-npc-folder-label', htmlFor: _nameField, <Widget>[Component.text('New folder in $where')]),
          GuiTextInput(
            id: _nameField,
            value: _text,
            label: 'New folder in $where',
            placeholder: 'market',
            onChanged: (String value) => setState(() => _text = value),
          ),
          dom.div(classes: 'animus-row', <Widget>[
            Button.primary(label: 'Make folder', size: ButtonSize.small, disabled: _busy, onPressed: () => _create(current, existing)),
            Button.ghost(label: 'Cancel', size: ButtonSize.small, onPressed: _cancel),
          ]),
        ],
      _Operation.rename => <Widget>[
          dom.label(classes: 'animus-npc-folder-label', htmlFor: _nameField, <Widget>[Component.text('New name for $where')]),
          GuiTextInput(
            id: _nameField,
            value: _text,
            label: 'New name for $where',
            placeholder: current.name,
            onChanged: (String value) => setState(() => _text = value),
          ),
          dom.div(classes: 'animus-row', <Widget>[
            Button.primary(
              label: 'Rename folder',
              size: ButtonSize.small,
              disabled: _busy,
              onPressed: () => _relocate(current, npcIdIn(npcParentFolder(current.path), normalizeNpcFolder(_text)), 'rename'),
            ),
            Button.ghost(label: 'Cancel', size: ButtonSize.small, onPressed: _cancel),
          ]),
        ],
      _Operation.move => <Widget>[
          NpcFolderPicker(
            root: root,
            value: _target,
            label: 'Move $where into',
            exclude: current.path,
            onChanged: (String value) => setState(() => _target = value),
          ),
          dom.div(classes: 'animus-row', <Widget>[
            Button.primary(
              label: 'Move folder',
              size: ButtonSize.small,
              disabled: _busy,
              onPressed: () => _relocate(current, npcIdIn(_target == _rootValue ? '' : _target, current.name), 'move'),
            ),
            Button.ghost(label: 'Cancel', size: ButtonSize.small, onPressed: _cancel),
          ]),
        ],
      _Operation.delete => <Widget>[
          dom.p(<Widget>[
            Component.text(current.total == 0
                ? 'Remove the empty folder $where?'
                : '$where holds ${current.total} NPC${current.total == 1 ? '' : 's'}; move or delete them first.'),
          ]),
          dom.div(classes: 'animus-row', <Widget>[
            if (current.total == 0) Button.destructive(label: 'Remove folder', size: ButtonSize.small, onPressed: () => _delete(current)),
            Button.ghost(label: current.total == 0 ? 'Keep it' : 'Back', size: ButtonSize.small, onPressed: _cancel),
          ]),
        ],
    };
    return dom.div(
      classes: 'animus-npc-folder-actions',
      attributes: const <String, String>{'role': 'group', 'aria-label': 'Folder actions'},
      events: <String, void Function(web.Event)>{
        'keydown': (web.Event event) {
          if ((event as web.KeyboardEvent).key == 'Escape' && _operation != _Operation.none) {
            event.preventDefault();
            _cancel();
          }
        },
      },
      <Widget>[
        dom.p(classes: 'animus-muted animus-npc-folder-where', <Widget>[Component.text('Selected: $where')]),
        dom.div(classes: 'animus-npc-folder-buttons', <Widget>[
          Button.secondary(label: 'New folder', size: ButtonSize.small, disabled: _busy, onPressed: () => _start(_Operation.create, current)),
          if (!rootSelected) ...<Widget>[
            Button.ghost(label: 'Rename', size: ButtonSize.small, disabled: _busy, onPressed: () => _start(_Operation.rename, current)),
            Button.ghost(label: 'Move', size: ButtonSize.small, disabled: _busy, onPressed: () => _start(_Operation.move, current)),
            Button.ghost(label: 'Delete', size: ButtonSize.small, disabled: _busy, onPressed: () => _start(_Operation.delete, current)),
          ],
          dom.a(
            classes: 'animus-npc-folder-new-npc',
            href: NewDocumentRoute.inFolder(component.workspaceId, DocumentKind.npc, current.path).format(),
            <Widget>[Component.text('New NPC in $where')],
          ),
        ]),
        if (form.isNotEmpty) dom.div(classes: 'animus-npc-folder-form', attributes: const <String, String>{'id': 'animus-npc-folder-op'}, form),
        if (_problem != null) dom.p(classes: 'animus-error', attributes: const <String, String>{'role': 'alert', 'data-testid': 'folder-problem'}, <Widget>[Component.text(_problem!)]),
      ],
    );
  }
}

/// A folder picker that shows the tree, indented, with `npcs/` itself as
/// `/`; [exclude] and its subtree are left out (a folder cannot move into
/// itself).
class NpcFolderPicker extends StatelessWidget {
  const NpcFolderPicker({required this.root, required this.value, required this.label, required this.onChanged, this.exclude, super.key});

  final NpcFolderNode root;

  /// A folder path, or `/` for `npcs/`.
  final String value;
  final String label;
  final void Function(String value) onChanged;
  final String? exclude;

  @override
  Widget build(BuildContext context) {
    final List<NpcFolderRow> rows = npcFolderRows(root, const <String>{});
    final List<String> options = <String>[
      for (final NpcFolderRow row in rows)
        if (exclude == null || exclude!.isEmpty || !npcFolderWithin(row.node.path, exclude!)) row.node.path.isEmpty ? '/' : row.node.path,
    ];
    return GuiSelect(
      value: options.contains(value) ? value : '/',
      options: options,
      label: label,
      labels: <String, String>{
        for (final NpcFolderRow row in rows) row.node.path.isEmpty ? '/' : row.node.path: row.node.path.isEmpty ? 'npcs/' : '${'  ' * (row.level - 1)}${row.node.name}/',
      },
      onChanged: (String? next) => onChanged(next ?? '/'),
    );
  }
}
