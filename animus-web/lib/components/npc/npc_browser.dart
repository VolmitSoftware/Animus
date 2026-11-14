/// The NPCs page: every NPC of the workspace in the kit's list, read from
/// the library's summaries (a paired server lists all of them before any is
/// read), with folder, body and world filters above it and New NPC. In
/// Advanced the rows show ids and tags, tag chips narrow the list, picked
/// rows get the bulk bar, and the "..." menu renames and moves folders.
/// Every bulk edit is one undo step and, on a server, one change at the
/// next apply.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/npc/npc_appearance.dart' show bodyLabel;
import '../../logic/npc/npc_faces.dart';
import '../../logic/npc/group_model.dart';
import '../../logic/npc/npc_bulk.dart';
import '../../logic/npc/npc_catalog.dart';
import '../../logic/npc/npc_paths.dart';
import '../../logic/offline/new_documents.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../model/server_binding.dart';
import '../../state/document_loader.dart';
import '../../state/editor_store.dart';
import '../../state/library.dart';
import '../../state/live_session.dart';
import '../../state/workspace_repository.dart';
import '../../state/workspace_route.dart';
import '../../ui/action_row.dart';
import '../../ui/confirm_dialog.dart';
import '../../ui/library_list.dart';
import '../../ui/library_query.dart';
import '../../ui/library_rows.dart';
import '../../ui/menu.dart';
import '../../ui/mode.dart';
import '../../ui/page_header.dart';
import '../../ui/toggle_choice.dart';
import 'new_npc_dialog.dart';
import 'npc_list_dialogs.dart';
import 'npc_list_folders.dart';
import 'npc_list_rows.dart';
import 'skin_face.dart';
import '../select/animus_select.dart';

enum _Open { none, newNpc, tags, folder, module, group, renameFolder, moveFolder }

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
  String? _folder;
  String? _body;
  String? _world;
  Set<String> _tags = <String>{};
  _Open _open = _Open.none;

  /// The NPCs an open bulk dialog acts on, read when it opened.
  List<NpcEntry> _picked = const <NpcEntry>[];

  // The rows, built again only when the library, the skins or the
  // workspace's PNGs change.
  List<LibraryEntry>? _entriesFrom;
  List<LibraryEntry>? _skinsFrom;
  Object? _resourcesFrom;
  List<NpcListItem> _items = const <NpcListItem>[];
  List<(String, int)> _folders = const <(String, int)>[];
  List<String> _bodies = const <String>[];
  List<String> _worlds = const <String>[];
  List<(String, int)> _topTags = const <(String, int)>[];
  Set<String> _taken = const <String>{};
  final NpcRowCache _rows = NpcRowCache();

  EditorStore get _store => component.store;

  LiveSession? get _session => component.session;

  List<NpcListItem> _itemsNow() {
    final List<LibraryEntry> entries = _store.library(DocumentKind.npc);
    final List<LibraryEntry> skins = _store.library(DocumentKind.skin);
    final Object resources = component.workspace.resources;
    if (identical(entries, _entriesFrom) && identical(skins, _skinsFrom) && identical(resources, _resourcesFrom)) return _items;
    _entriesFrom = entries;
    _skinsFrom = skins;
    _resourcesFrom = resources;
    _items = npcListItems(component.workspace.id, entries, face: _face);
    _folders = npcFolderCounts(_items);
    _bodies = npcBodies(_items);
    _worlds = npcWorlds(_items);
    _topTags = npcTopTags(_items);
    _taken = <String>{for (final NpcListItem item in _items) item.id};
    return _items;
  }

  /// An NPC's face as the stage draws it: its skin (the workspace's PNG,
  /// else the texture its skin's summary names) or its placeholder, or its
  /// body's icon. Drawn only for rows in view.
  NpcFace _face(LibraryEntry entry) {
    final String? skin = entry.summary.skin;
    return NpcFace(
      source: npcFaceSource(entry.id, type: entry.summary.type, skin: skin),
      texture: skin == null ? null : _store.libraryEntry(DocumentKind.skin, skin)?.summary.texture,
      png: skin == null ? null : component.workspace.resources[skinImagePath(skin)],
    );
  }

  NpcListFilter get _filter => NpcListFilter(folder: _folder, body: _body, world: _world, tags: _tags);

  List<String> get _folderPaths => <String>[for (final (String folder, int _) in _folders) folder];

  void _close() => setState(() => _open = _Open.none);

  // ---- Reading and writing NPCs ---------------------------------------------

  static List<String> _ids(List<LibraryRow> rows) => <String>[
        for (final LibraryRow row in rows)
          if (parseDocumentKey(row.key) case (DocumentKind.npc, final String id)) id,
      ];

  /// The NPC documents [ids], read from the server first when some are not
  /// here yet; null (and a toast) when some could not be read.
  Future<List<NpcEntry>?> _load(Iterable<String> ids, {Iterable<DocumentRef> also = const <DocumentRef>[]}) async {
    final LiveSession? session = _session;
    if (session != null) {
      final LoadResult result = await session.ensureLoaded(<DocumentRef>[for (final String id in ids) DocumentRef(DocumentKind.npc, id), ...also]);
      if (!result.complete) {
        ArcaneSonner.error('Could not read ${npcCount(result.failed.length)} from the server. ${result.reason?.text ?? 'Try again.'}');
        return null;
      }
    }
    return <NpcEntry>[
      for (final String id in ids)
        if (_store.document(DocumentKind.npc, id) case final AnimusDocument document) NpcEntry.of(document),
    ];
  }

  /// Commits [result] as one undo step and says what happened.
  Future<void> _apply(NpcBulkResult result, String label, String message) async {
    if (mounted) _close();
    if (result.changes.isEmpty) {
      ArcaneSonner.warning(message);
      return;
    }
    await _store.commit(documents: result.changes, label: label);
    result.skipped.isEmpty ? ArcaneSonner.success(message) : ArcaneSonner.warning(message);
  }

  Future<void> _bulk(NpcBulkResult Function(List<NpcEntry> entries) edit, String action, {List<NpcEntry>? entries}) async {
    final List<NpcEntry>? picked = entries ?? await _load(<String>[for (final NpcEntry entry in _picked) entry.key]);
    if (picked == null) return;
    final NpcBulkResult result = edit(picked);
    await _apply(result, '${action.toLowerCase()} ${npcCount(result.changed)}', describeBulk(action, result));
  }

  /// Opens a bulk dialog once the picked NPCs are read.
  Future<void> _openBulk(_Open which, List<LibraryRow> rows) async {
    final List<NpcEntry>? entries = await _load(_ids(rows));
    if (entries == null || !mounted) return;
    setState(() {
      _picked = entries;
      _open = which;
    });
  }

  /// Spawn and Despawn: on the server now when the pairing may spawn,
  /// otherwise in the definitions, sent at the next apply.
  Future<void> _spawn(List<LibraryRow> rows, {required bool spawn}) async {
    final List<String> ids = _ids(rows);
    final LiveSession? session = _session;
    if (session != null && (session.connection.token?.has('spawn') ?? false)) {
      if (!spawn &&
          !await askToConfirm(
            title: 'Despawn ${npcCount(ids.length)} now?',
            message: 'They leave the server now. Spawn brings them back.',
            confirmLabel: 'Despawn',
            destructive: true,
          )) {
        return;
      }
      if (!session.online) {
        ArcaneSonner.warning('The server is offline. Try again once it is back.');
        return;
      }
      await session.spawnNpcs(ids, spawn: spawn);
      return;
    }
    final List<NpcEntry>? entries = await _load(ids);
    if (entries == null) return;
    await _bulk((List<NpcEntry> picked) => setSpawned(picked, spawn), spawn ? 'Spawned' : 'Despawned', entries: entries);
  }

  Future<void> _delete(List<LibraryRow> rows) async {
    final List<String> ids = _ids(rows);
    if (!await askToConfirm(title: 'Delete ${npcCount(ids.length)}?', message: 'Undo brings them back.', confirmLabel: 'Delete', destructive: true)) return;
    final List<NpcEntry>? entries = await _load(ids);
    if (entries == null) return;
    await _bulk(deleteNpcs, 'Deleted', entries: entries);
  }

  Future<void> _changeGroup(String group, {required bool remove}) async {
    final List<NpcEntry>? entries = await _load(<String>[for (final NpcEntry entry in _picked) entry.key], also: <DocumentRef>[DocumentRef(DocumentKind.group, group)]);
    if (entries == null) return;
    final NpcBulkResult result = changeGroup(
      entries,
      workspaceGroups(_store.workspace?.documents ?? const <AnimusDocument>[]),
      group,
      NpcGroupTarget.ids(<String>{for (final NpcEntry entry in entries) entry.key}),
      assign: !remove,
    );
    await _apply(result, '${remove ? 'unassign' : 'assign'} group $group', describeGroupChange(group, result, assign: !remove));
  }

  /// Moves folder [from] to [to] with every NPC in and below it.
  Future<String?> _moveFolder(String from, String to) async {
    final List<NpcEntry>? inside = await _load(<String>[
      for (final NpcListItem item in _items)
        if (npcFolderWithin(item.folder, from)) item.id,
    ]);
    if (inside == null) return 'Could not read its NPCs from the server.';
    final NpcFolderEdit edit = renameNpcFolder(inside, from, to, _taken);
    if (edit.problem != null) return edit.problem;
    await _store.commit(documents: edit.changes, label: 'move folder $from to $to');
    if (!mounted) return null;
    setState(() {
      _open = _Open.none;
      if (_folder != null && npcFolderWithin(_folder!, from)) _folder = '$to${_folder!.substring(from.length)}';
    });
    ArcaneSonner.success('Moved ${folderLabel(from)} to ${folderLabel(to)} with ${npcCount(edit.moved)}.');
    return null;
  }

  // ---- The page -------------------------------------------------------------

  List<LibraryBulkAction> get _bulkActions => <LibraryBulkAction>[
        LibraryBulkAction('Spawn', (List<LibraryRow> rows) => _spawn(rows, spawn: true)),
        LibraryBulkAction('Despawn', (List<LibraryRow> rows) => _spawn(rows, spawn: false)),
        LibraryBulkAction('Tags', (List<LibraryRow> rows) => _openBulk(_Open.tags, rows)),
        LibraryBulkAction('Folder', (List<LibraryRow> rows) => _openBulk(_Open.folder, rows)),
        LibraryBulkAction('Module', (List<LibraryRow> rows) => _openBulk(_Open.module, rows)),
        if (_store.library(DocumentKind.group).isNotEmpty) LibraryBulkAction('Trait group', (List<LibraryRow> rows) => _openBulk(_Open.group, rows)),
        LibraryBulkAction('Delete', _delete, destructive: true),
      ];

  /// Folder, Body and World, one row above the list; tag chips under them
  /// in Advanced. Only the filters that can narrow the list show.
  /// The folder, body and world dropdowns and, in Advanced, the tag chips:
  /// they go at the start of the list's own bar, beside its search and
  /// chips, so every way to narrow the list is in one row of its card.
  List<Widget> _filterBar(List<NpcListItem> items, bool advanced) {
    final bool folders = _folders.isNotEmpty && (_folders.length > 1 || _folders.single.$2 < items.length);
    final bool bodies = _bodies.length > 1;
    final bool worlds = _worlds.length > 1;
    final bool tags = advanced && _topTags.isNotEmpty;
    return <Widget>[
      if (folders || bodies || worlds)
        Row(gapSize: Gap.sm, children: <Widget>[
          if (folders)
            Flexible(child: AnimusSelect.values(
                id: 'npc-filter-folder',
                hiddenLabel: 'Folder',
                value: _folder,
                allowNone: true,
                noneLabel: 'All folders',
                values: _folderPaths,
                labels: <String, String>{for (final (String folder, int count) in _folders) folder: '${folderLabel(folder)} ($count)'},
                onChanged: (String? value) => setState(() => _folder = value),
            )),
          if (bodies)
            Flexible(child: AnimusSelect.values(
                id: 'npc-filter-body',
                hiddenLabel: 'Body',
                value: _body,
                allowNone: true,
                noneLabel: 'Any body',
                values: _bodies,
                labels: <String, String>{for (final String body in _bodies) body: bodyLabel(body)},
                onChanged: (String? value) => setState(() => _body = value),
            )),
          if (worlds)
            Flexible(child: AnimusSelect.values(
                id: 'npc-filter-world',
                hiddenLabel: 'World',
                value: _world,
                allowNone: true,
                noneLabel: 'Any world',
                values: _worlds,
                onChanged: (String? value) => setState(() => _world = value),
            )),
        ]),
      if (tags)
        ToggleChips<String>(
          label: 'Tags',
          options: <ChoiceOption<String>>[for (final (String tag, int _) in _topTags) ChoiceOption<String>(tag, tag)],
          values: _tags,
          onChanged: (Set<String> next) => setState(() => _tags = next),
        ),
    ];
  }


  Widget? _dialog(bool advanced) {
    final int count = _picked.length;
    return switch (_open) {
      _Open.none => null,
      _Open.newNpc => NewNpcDialog(
          store: _store,
          workspaceId: component.workspace.id,
          folders: _folderPaths,
          folder: _folder ?? 'local',
          taken: _taken,
          world: mostUsedWorld(_items),
          advanced: advanced,
          owner: _session?.connection.token?.admin,
          onClose: _close,
        ),
      _Open.tags => NpcTagsDialog(
          count: count,
          known: <String>[for (final (String tag, int _) in npcTopTags(_items, limit: 200)) tag],
          carried: <String>[for (final (String tag, int _) in npcTags(_picked)) tag],
          onSubmit: (String tag, {required bool remove}) =>
              _bulk((List<NpcEntry> entries) => remove ? removeTag(entries, tag) : addTag(entries, tag), remove ? 'Untagged' : 'Tagged'),
          onClose: _close,
        ),
      _Open.folder => NpcMoveDialog(
          count: count,
          folders: _folderPaths,
          onSubmit: (String folder) => _bulk((List<NpcEntry> entries) => moveToFolder(entries, folder, _taken), 'Moved'),
          onClose: _close,
        ),
      _Open.module => NpcModuleDialog(
          count: count,
          modules: selectionModules(_picked),
          onSubmit: (String module, ModuleChange change) => switch (change) {
            ModuleChange.on => _bulk((List<NpcEntry> entries) => setModuleEnabled(entries, module, true), 'Turned on $module for'),
            ModuleChange.off => _bulk((List<NpcEntry> entries) => setModuleEnabled(entries, module, false), 'Turned off $module for'),
            ModuleChange.remove => _bulk((List<NpcEntry> entries) => removeModule(entries, module), 'Removed $module from'),
          },
          onClose: _close,
        ),
      _Open.group => NpcGroupDialog(
          count: count,
          groups: <String, String>{for (final LibraryEntry group in _store.library(DocumentKind.group)) group.id: group.name},
          onSubmit: _changeGroup,
          onClose: _close,
        ),
      _Open.renameFolder || _Open.moveFolder => NpcFolderDialog(
          action: _open == _Open.renameFolder ? FolderAction.rename : FolderAction.move,
          folders: _folderPaths,
          initial: _folder,
          onSubmit: _moveFolder,
          onClose: _close,
        ),
    };
  }

  @override
  Widget build(BuildContext context) {
    final bool advanced = ModeScope.advanced(context);
    final List<NpcListItem> items = _itemsNow();
    final NpcListFilter filter = _filter;
    final List<LibraryRow> rows = _rows.rows(items, filter);
    final LiveSession? session = _session;
    final bool waiting = session != null && !session.catalogLoaded && items.isEmpty;
    final bool narrowedAway = rows.isEmpty && items.isNotEmpty;
    void create() => setState(() => _open = _Open.newNpc);
    return dom.div(classes: 'ui-list-page', <Widget>[
      PageHeader(
        title: 'NPCs',
        crumbs: <Crumb>[Crumb(component.workspace.name, href: WorkspaceRoute(component.workspace.id).format()), const Crumb('NPCs')],
        actions: <AppMenuEntry>[
          if (advanced && _folders.isNotEmpty) ...<AppMenuEntry>[
            AppMenuEntry('Rename folder', () => setState(() => _open = _Open.renameFolder)),
            AppMenuEntry('Move folder', () => setState(() => _open = _Open.moveFolder)),
          ],
        ],
        primary: RowAction('New NPC', create, icon: ArcaneIcon.plus(), attributes: const <String, String>{'data-testid': 'npc-new'}),
      ),
      LibraryList(
        toolbar: _filterBar(items, advanced),
        key: ValueKey<String>('npcs:${component.workspace.id}'),
        id: 'npc-list',
        label: 'NPCs',
        noun: 'NPC',
        rows: rows,
        filters: const <LibraryFilter>[spawnedFilter, despawnedFilter, problemsFilter],
        // The list keeps the picks of rows that are gone; an empty list gets no bulk bar.
        selectable: advanced && rows.isNotEmpty,
        bulkActions: advanced ? _bulkActions : const <LibraryBulkAction>[],
        loading: waiting && session.catalogProblem == null,
        error: waiting ? session.catalogProblem?.text : null,
        onRetry: session?.loadCatalog,
        emptyMessage: narrowedAway ? 'No NPCs match these filters.' : 'No NPCs yet.',
        emptyAction: narrowedAway
            ? Button.outline(
                label: 'Clear filters',
                onPressed: () => setState(() {
                  _folder = null;
                  _body = null;
                  _world = null;
                  _tags = <String>{};
                }),
              )
            : Button.primary(label: 'New NPC', onPressed: create),
      ),
      ?_dialog(advanced),
    ]);
  }
}
