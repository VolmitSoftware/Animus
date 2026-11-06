/// The home page and offline landing: start a workspace (blank, with the
/// example packs, or from a data folder), manage the workspaces kept in
/// this browser, and pair a server for later.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/offline/starter_workspace.dart';
import '../../services/browser.dart';
import '../../services/server/server_profiles.dart';
import '../../state/editor_store.dart';
import '../../state/server_hub.dart';
import '../../state/workspace_repository.dart';
import '../../state/workspace_route.dart';
import '../brand_mark.dart';
import '../rig/rig_actions.dart';
import '../server/pair_page.dart';
import 'offline_fields.dart';

/// The main element's id, shared with the shell's skip link.
const String _mainId = 'animus-main';

class HomeScreen extends StatefulWidget {
  const HomeScreen({
    required this.store,
    required this.hub,
    required this.busy,
    required this.onOpenFolder,
    required this.onOpenZip,
    required this.onNavigate,
    super.key,
  });

  final EditorStore store;
  final ServerHub hub;
  final bool busy;
  final void Function() onOpenFolder;
  final void Function() onOpenZip;
  final void Function(EditorRoute route) onNavigate;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  static const String _renameInputId = 'animus-workspace-rename';

  String _name = '';
  bool _working = false;
  String? _renaming;
  String _renameTo = '';
  String? _deleting;

  String get _workspaceName => _name.trim().isEmpty ? 'My workspace' : _name.trim();

  String _rowButton(String action, String id) => 'animus-ws-$action-${id.replaceAll(RegExp(r'[^A-Za-z0-9_-]'), '_')}';

  Future<void> _run(String what, Future<void> Function() task) async {
    setState(() => _working = true);
    try {
      await task();
    } on Object catch (failure) {
      ArcaneSonner.error('Could not $what ($failure). Reload the page and try again; export a zip first if the workspace matters.');
    } finally {
      if (mounted) setState(() => _working = false);
    }
  }

  Future<void> _createBlank() => _run('create the workspace', () async {
        final WorkspaceSnapshot made = await component.store.createWorkspace(_workspaceName);
        ArcaneSonner.success('Created ${made.name}. Make an NPC to start.');
        component.onNavigate(NewDocumentRoute(made.id));
        focusMainHeading();
      });

  Future<void> _createStarter() => _run('create the workspace', () async {
        final WorkspaceSnapshot made = await component.store.openFiles(_name.trim().isEmpty ? 'Example packs' : _name.trim(), starterWorkspaceFiles());
        ArcaneSonner.success('Created ${made.name} with ${made.documents.length} documents from the example packs.');
        component.onNavigate(WorkspaceRoute(made.id));
        focusMainHeading();
      });

  Future<void> _export(String id) => _run('export the workspace', () async {
        final WorkspaceSnapshot? workspace = await component.store.loadWorkspace(id);
        if (workspace != null) exportWorkspace(workspace);
      });

  Future<void> _duplicate(WorkspaceSummary summary) => _run('copy ${summary.name}', () async {
        final WorkspaceSnapshot? copy = await component.store.duplicateWorkspace(summary.id, '${summary.name} copy');
        if (copy != null) ArcaneSonner.success('Copied ${summary.name} as ${copy.name}.');
      });

  void _startRename(WorkspaceSummary summary) {
    setState(() {
      _renaming = summary.id;
      _renameTo = summary.name;
      _deleting = null;
    });
    focusWhenShown(_renameInputId);
  }

  void _cancelRename(String id) {
    setState(() => _renaming = null);
    focusWhenShown(_rowButton('rename', id));
  }

  Future<void> _rename(String id) async {
    if (_renameTo.trim().isEmpty) return;
    await _run('rename the workspace', () async {
      await component.store.renameWorkspace(id, _renameTo);
      _renaming = null;
    });
    focusWhenShown(_rowButton('rename', id));
  }

  Future<void> _delete(WorkspaceSummary summary) => _run('delete ${summary.name}', () async {
        _deleting = null;
        await component.store.deleteWorkspace(summary.id);
        ArcaneSonner.info('Deleted ${summary.name}. Restore brings it back until you close the tab.');
        focusWhenShown('animus-workspaces-title');
      });

  @override
  Widget build(BuildContext context) {
    final EditorStore store = component.store;
    final bool busy = component.busy || _working;
    final List<(String, String)> packs = starterPacks();
    return dom.main_(id: _mainId, classes: 'animus-home', attributes: const <String, String>{'tabindex': '-1'}, <Widget>[
      dom.section(classes: 'animus-start', attributes: const <String, String>{'aria-labelledby': mainHeadingId}, <Widget>[
        const dom.div(classes: 'animus-start-head', <Widget>[
          BrandMark(size: 64, classes: 'animus-start-mark'),
          dom.div(<Widget>[
            dom.h1(id: mainHeadingId, attributes: <String, String>{'tabindex': '-1'}, <Widget>[Component.text('Start a workspace')]),
            dom.p(classes: 'animus-start-lede', <Widget>[
              Component.text('Make NPCs, behaviors, shops, GUIs, holograms and rigs here, with no server. '
                  'Workspaces stay in this browser and are checked against the shared schemas as you edit.'),
            ]),
          ]),
        ]),
        dom.ol(classes: 'animus-start-ways', <Widget>[
          dom.li(classes: 'animus-start-way is-first', <Widget>[
            const dom.div(classes: 'animus-start-copy', <Widget>[
              dom.h2(<Widget>[Component.text('Blank')]),
              dom.p(classes: 'animus-muted', <Widget>[Component.text('An empty workspace with its own name. The New page walks you through the first NPC.')]),
            ]),
            dom.div(classes: 'animus-start-row', <Widget>[
              OfflineTextField(
                label: 'Workspace name',
                value: _name,
                placeholder: 'My workspace',
                onChanged: (String value) => setState(() => _name = value),
                onSubmit: busy ? null : _createBlank,
              ),
              Button.primary(label: 'Create blank workspace', icon: ArcaneIcon.plus(), disabled: busy, onPressed: _createBlank),
            ]),
          ]),
          dom.li(classes: 'animus-start-way', <Widget>[
            dom.div(classes: 'animus-start-copy', <Widget>[
              const dom.h2(<Widget>[Component.text('From the example packs')]),
              dom.p(classes: 'animus-muted', <Widget>[
                Component.text('${packs.length} packs with their NPCs, behaviors, a shop and a chest GUI: '
                    '${packs.map(((String, String) pack) => pack.$1).join(', ')}. Uses the name above when you typed one.'),
              ]),
            ]),
            Button.secondary(label: 'Create with example packs', disabled: busy, onPressed: _createStarter),
          ]),
          dom.li(classes: 'animus-start-way', <Widget>[
            const dom.div(classes: 'animus-start-copy', <Widget>[
              dom.h2(<Widget>[Component.text('From a data folder')]),
              dom.p(classes: 'animus-muted', <Widget>[
                Component.text('Pick plugins/Animus (config/animus on mod servers) or a zip of it. It is copied into a new workspace: '
                    'nothing is uploaded, and edits do not go back into the folder. Export a zip to take them back.'),
              ]),
            ]),
            dom.div(classes: 'animus-row', <Widget>[
              Button.secondary(label: 'Open folder', icon: ArcaneIcon.folderOpen(), disabled: busy, onPressed: component.onOpenFolder),
              Button.secondary(label: 'Open zip', icon: ArcaneIcon.fileArchive(), disabled: busy, onPressed: component.onOpenZip),
              Button.ghost(
                label: 'Convert a Citizens, Shopkeepers, dtlTraders or Denizen save',
                icon: ArcaneIcon.arrowRightLeft(),
                disabled: busy,
                onPressed: () => component.onNavigate(const CitizensRoute()),
              ),
            ]),
          ]),
        ]),
      ]),
      _workspaces(store, busy),
      ServersPanel(hub: component.hub, onNavigate: component.onNavigate),
    ]);
  }

  Widget _workspaces(EditorStore store, bool busy) {
    final WorkspaceSnapshot? deleted = store.deleted;
    final String? listProblem = store.listProblem;
    return dom.section(classes: 'animus-panel', attributes: const <String, String>{'aria-labelledby': 'animus-workspaces-title'}, <Widget>[
      const dom.h2(id: 'animus-workspaces-title', attributes: <String, String>{'tabindex': '-1'}, <Widget>[Component.text('Workspaces in this browser')]),
      if (deleted != null && !store.workspaces.any((WorkspaceSummary summary) => summary.id == deleted.id))
        dom.div(classes: 'animus-row animus-restore', attributes: const <String, String>{'role': 'status'}, <Widget>[
          Component.text('Deleted ${deleted.name} (${deleted.documents.length} documents). You can restore it until you close the tab.'),
          Button.secondary(
            label: 'Restore ${deleted.name}',
            size: ButtonSize.small,
            disabled: busy,
            onPressed: () => _run('restore ${deleted.name}', () async {
              await store.restoreDeleted();
            }),
          ),
        ]),
      if (listProblem != null)
        dom.p(classes: 'animus-error', attributes: const <String, String>{'role': 'alert'}, <Widget>[Component.text(listProblem)])
      else if (!store.workspacesListed)
        const dom.p(classes: 'animus-muted', attributes: <String, String>{'role': 'status'}, <Widget>[Component.text('Reading the workspaces kept in this browser...')])
      else if (store.workspaces.isEmpty)
        const dom.p(classes: 'animus-muted', <Widget>[
          Component.text('None yet. A workspace you start above, open from a folder or pull from a server is listed here.'),
        ])
      else
        dom.ul(classes: 'animus-workspace-list', <Widget>[
          for (final WorkspaceSummary summary in store.workspaces) dom.li(classes: 'animus-workspace-row', <Widget>[_workspaceRow(summary, busy)]),
        ]),
    ]);
  }

  Widget _workspaceRow(WorkspaceSummary summary, bool busy) {
    final ServerProfile? server = summary.serverId == null ? null : component.hub.server(summary.serverId!);
    final String href = server != null ? ServerRoute(summary.serverId!, const <String>[]).format() : WorkspaceRoute(summary.id).format();
    if (_deleting == summary.id) {
      return ConfirmRow(
        question: 'Delete ${summary.name}?',
        detail: 'Its ${summary.documentCount} documents and undo history leave this browser. Export a zip first to keep a copy.',
        confirmLabel: 'Delete workspace',
        onConfirm: () => _delete(summary),
        onCancel: () {
          setState(() => _deleting = null);
          focusWhenShown(_rowButton('delete', summary.id));
        },
      );
    }
    if (_renaming == summary.id) {
      return dom.div(classes: 'animus-row animus-rename', <Widget>[
        OfflineTextField(
          id: _renameInputId,
          label: 'New name for ${summary.name}',
          value: _renameTo,
          onChanged: (String value) => setState(() => _renameTo = value),
          onSubmit: () => _rename(summary.id),
          onCancel: () => _cancelRename(summary.id),
        ),
        Button.primary(label: 'Rename', size: ButtonSize.small, disabled: busy || _renameTo.trim().isEmpty, onPressed: () => _rename(summary.id)),
        Button.ghost(label: 'Cancel', size: ButtonSize.small, onPressed: () => _cancelRename(summary.id)),
      ]);
    }
    return dom.div(classes: 'animus-workspace-entry', <Widget>[
      dom.div(classes: 'animus-workspace-name', <Widget>[
        dom.a(href: href, <Widget>[Component.text(summary.name)]),
        dom.span(classes: 'animus-muted', <Widget>[
          Component.text('${summary.documentCount} document${summary.documentCount == 1 ? '' : 's'}, '
              '${summary.serverId == null ? 'local' : 'live on ${server?.name ?? 'a server no longer paired'}'}'),
        ]),
      ]),
      dom.div(classes: 'animus-workspace-actions', attributes: <String, String>{'role': 'group', 'aria-label': 'Manage ${summary.name}'}, <Widget>[
        Button.ghost(
          id: _rowButton('rename', summary.id),
          label: 'Rename',
          size: ButtonSize.small,
          attributes: <String, String>{'aria-label': 'Rename ${summary.name}'},
          onPressed: () => _startRename(summary),
        ),
        Button.ghost(
          label: 'Duplicate',
          size: ButtonSize.small,
          disabled: busy,
          attributes: <String, String>{'aria-label': 'Duplicate ${summary.name}'},
          onPressed: () => _duplicate(summary),
        ),
        Button.ghost(
          label: 'Export zip',
          size: ButtonSize.small,
          disabled: busy,
          attributes: <String, String>{'aria-label': 'Export ${summary.name} as a zip'},
          onPressed: () => _export(summary.id),
        ),
        dom.span(classes: 'animus-quiet-danger', <Widget>[
          Button.ghost(
            id: _rowButton('delete', summary.id),
            label: 'Delete',
            size: ButtonSize.small,
            attributes: <String, String>{'aria-label': 'Delete ${summary.name}'},
            onPressed: () => setState(() {
              _deleting = summary.id;
              _renaming = null;
            }),
          ),
        ]),
      ]),
    ]);
  }
}
