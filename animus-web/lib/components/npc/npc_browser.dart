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
import '../../logic/npc/npc_bulk.dart';
import '../../logic/npc/npc_catalog.dart';
import '../../logic/npc/tree_keys.dart';
import '../../model/document_kind.dart';
import '../../services/skin_images.dart';
import '../../state/editor_store.dart';
import '../../state/live_session.dart';
import '../../state/workspace_repository.dart';
import '../../state/workspace_route.dart';
import '../gui/gui_fields.dart';
import '../inspect/inspect_fields.dart';
import '../../logic/inspect/schema_fields.dart';

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

  /// The folder tree item that holds the tab stop: 0 is `npcs/`.
  int _treeFocus = 0;

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

  Future<void> _bulk(String action, NpcBulkResult result) async {
    if (result.changes.isEmpty) {
      ArcaneSonner.warning(describeBulk(action, result));
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
      final String message = describeBulk(action, result);
      if (result.skipped.isEmpty) {
        ArcaneSonner.success('$message Undo reverts it in one step.');
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
              placeholder: 'Name, id, tag:guard, folder:town, has:combat, is:despawned',
              onChanged: _typeSearch,
            ),
            if (_search.isNotEmpty) Button.ghost(label: 'Clear search', size: ButtonSize.small, onPressed: () => _setSearch('')),
          ]),
        ]),
        _filters(all),
        dom.div(classes: 'animus-npc-browser-body', <Widget>[
          _folders(all),
          dom.div(classes: 'animus-npc-browser-main', <Widget>[if (selected.isNotEmpty) _bulkBar(all, selected, keys), _list(shown, allShown)]),
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

  Widget _folders(List<NpcEntry> all) {
    final List<NpcFolder> folders = npcFolders(all);
    final List<(String, int)> tags = npcTags(all);
    String? folder;
    for (final NpcTerm term in NpcQuery.parse(_search).terms) {
      if (term.field == 'folder' && !term.negated) folder = term.value;
    }
    void open(String? name) {
      String next = _search;
      if (folder != null) next = toggleTerm(next, 'folder', folder);
      if (name != null) next = toggleTerm(next, 'folder', name);
      _setSearch(next);
    }

    // A flat tree: npcs/ at level 1, its folders at level 2. One tab stop;
    // arrows, Home and End move focus, Enter or Space opens the folder.
    final List<(String?, String, int)> items = <(String?, String, int)>[
      (null, 'npcs/', all.length),
      for (final NpcFolder entry in folders) (entry.name, '${entry.name}/', entry.count),
    ];
    final int focus = _treeFocus.clamp(0, items.length - 1);
    void choose(int index) {
      final String? name = items[index].$1;
      open(name == null || folder == name ? null : name);
    }

    return dom.nav(
      classes: 'animus-npc-folders',
      attributes: const <String, String>{'aria-label': 'Folders and tags'},
      <Widget>[
        const dom.h3(attributes: <String, String>{'id': 'animus-npc-folders-title'}, <Widget>[Component.text('Folders')]),
        dom.ul(
          classes: 'animus-npc-tree',
          attributes: const <String, String>{'role': 'tree', 'aria-labelledby': 'animus-npc-folders-title'},
          <Widget>[
            for (int index = 0; index < items.length; index++)
              _treeItem(
                index: index,
                label: items[index].$2,
                count: items[index].$3,
                selected: items[index].$1 == folder,
                focused: index == focus,
                total: items.length,
                onChoose: () => choose(index),
              ),
          ],
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

  Widget _treeItem({
    required int index,
    required String label,
    required int count,
    required bool selected,
    required bool focused,
    required int total,
    required void Function() onChoose,
  }) {
    final bool root = index == 0;
    return dom.li(
      key: ValueKey<String>('tree:$label'),
      classes: 'animus-npc-tree-item${selected ? ' is-selected' : ''}${root ? '' : ' is-child'}',
      attributes: <String, String>{
        'id': 'animus-npc-tree-$index',
        'role': 'treeitem',
        'aria-level': root ? '1' : '2',
        'aria-setsize': root ? '1' : '${total - 1}',
        'aria-posinset': root ? '1' : '$index',
        if (root) 'aria-expanded': 'true',
        'aria-selected': '$selected',
        'aria-label': '${root ? 'All folders' : 'Folder ${label.substring(0, label.length - 1)}'}, $count NPC${count == 1 ? '' : 's'}',
        'tabindex': focused ? '0' : '-1',
      },
      events: <String, void Function(web.Event)>{
        'click': (web.Event _) {
          setState(() => _treeFocus = index);
          onChoose();
        },
        'keydown': (web.Event event) {
          final String key = (event as web.KeyboardEvent).key;
          if (key == 'Enter' || key == ' ') {
            event.preventDefault();
            onChoose();
            return;
          }
          final int? next = treeMove(key, index, total);
          if (next == null) return;
          event.preventDefault();
          // The items keep their ids, so the next one can take focus at once.
          final web.Element? element = web.document.getElementById('animus-npc-tree-$next');
          if (element != null) (element as web.HTMLElement).focus();
          setState(() => _treeFocus = next);
        },
      },
      <Widget>[
        dom.span(<Widget>[Component.text(label)]),
        dom.span(classes: 'animus-count', attributes: const <String, String>{'aria-hidden': 'true'}, <Widget>[Component.text('$count')]),
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
        dom.div(classes: 'animus-npc-bulk-group', <Widget>[
          const dom.span(classes: 'animus-npc-bulk-label', <Widget>[Component.text('Folder')]),
          SuggestInput(
            value: _folder,
            label: 'Folder',
            placeholder: 'town',
            suggestions: <FieldOption>[for (final NpcFolder folder in npcFolders(all)) FieldOption(folder.name)],
            onChanged: (String value) => setState(() => _folder = value.trim().toLowerCase()),
          ),
          Button.secondary(
            label: 'Move to folder',
            size: ButtonSize.small,
            disabled: _busy || !npcFolderPattern.hasMatch(_folder),
            onPressed: () => _bulk('Moved', moveToFolder(selected, _folder, keys)),
          ),
        ]),
        dom.div(classes: 'animus-npc-bulk-group', <Widget>[
          const dom.span(classes: 'animus-npc-bulk-label', <Widget>[Component.text('Module')]),
          if (modules.isEmpty)
            const dom.span(classes: 'animus-muted', <Widget>[Component.text('The selection has no modules.')])
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
              Component.text('Spawning on the server needs the spawn scope; pair again with it to spawn and despawn from here.'),
            ]),
        ]),
        dom.div(classes: 'animus-npc-bulk-group', <Widget>[
          if (_confirm != _Confirm.delete)
            Button.ghost(label: 'Delete', size: ButtonSize.small, disabled: _busy, onPressed: () => setState(() => _confirm = _Confirm.delete))
          else ...<Widget>[
            Button.destructive(label: 'Delete $count', size: ButtonSize.small, disabled: _busy, onPressed: () => _bulk('Deleted', deleteNpcs(selected))),
            Button.ghost(label: 'Keep them', size: ButtonSize.small, onPressed: () => setState(() => _confirm = _Confirm.none)),
            const dom.span(classes: 'animus-muted', <Widget>[Component.text('Escape keeps them. Undo brings deleted NPCs back.')]),
          ],
        ]),
        if (_confirm == _Confirm.despawn)
          const dom.p(classes: 'animus-muted', <Widget>[Component.text('Despawning acts on the server at once and is not part of undo. Escape keeps them spawned.')]),
        if (session != null)
          const dom.p(classes: 'animus-muted', <Widget>[
            Component.text('Edits join the drafts; Apply sends them as one change set. Spawn and despawn act on the server at once.'),
          ]),
      ],
    );
  }

  Widget _list(List<NpcEntry> shown, bool allShown) {
    if (shown.isEmpty) {
      if (_all.isEmpty) {
        return dom.div(classes: 'animus-npc-empty', <Widget>[
          const dom.p(<Widget>[Component.text('This workspace has no NPCs yet.')]),
          dom.a(
            classes: 'animus-npc-empty-action',
            href: NewDocumentRoute(component.workspace.id, DocumentKind.npc).format(),
            <Widget>[const Component.text('Create an NPC')],
          ),
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
                'title': '${entry.errors} error${entry.errors == 1 ? '' : 's'} in this definition; open it to see them',
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
