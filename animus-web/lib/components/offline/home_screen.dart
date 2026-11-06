/// The home page: start a workspace (blank or with the example packs), open
/// a data folder, manage the workspaces kept in this browser, and pair a
/// server for later.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/offline/starter_workspace.dart';
import '../../services/server/server_profiles.dart';
import '../../state/editor_store.dart';
import '../../state/server_hub.dart';
import '../../state/workspace_repository.dart';
import '../../state/workspace_route.dart';
import '../rig/rig_actions.dart';
import '../server/pair_page.dart';
import 'offline_fields.dart';

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
  String _name = '';
  bool _working = false;
  String? _renaming;
  String _renameTo = '';
  String? _deleting;

  String get _workspaceName => _name.trim().isEmpty ? 'My workspace' : _name.trim();

  Future<void> _run(Future<void> Function() task) async {
    setState(() => _working = true);
    try {
      await task();
    } on Object catch (failure) {
      ArcaneSonner.error('$failure');
    } finally {
      if (mounted) setState(() => _working = false);
    }
  }

  Future<void> _createBlank() => _run(() async {
        final WorkspaceSnapshot made = await component.store.createWorkspace(_workspaceName);
        ArcaneSonner.success('Created ${made.name}. Make an NPC to start.');
        component.onNavigate(NewDocumentRoute(made.id));
      });

  Future<void> _createStarter() => _run(() async {
        final WorkspaceSnapshot made = await component.store.openFiles(_name.trim().isEmpty ? 'Example packs' : _name.trim(), starterWorkspaceFiles());
        ArcaneSonner.success('Created ${made.name} with ${made.documents.length} documents from the example packs.');
        component.onNavigate(WorkspaceRoute(made.id));
      });

  Future<void> _export(String id) => _run(() async {
        final WorkspaceSnapshot? workspace = await component.store.loadWorkspace(id);
        if (workspace != null) exportWorkspace(workspace);
      });

  Future<void> _duplicate(WorkspaceSummary summary) => _run(() async {
        final WorkspaceSnapshot? copy = await component.store.duplicateWorkspace(summary.id, '${summary.name} copy');
        if (copy != null) ArcaneSonner.success('Copied ${summary.name} as ${copy.name}.');
      });

  Future<void> _rename(String id) => _run(() async {
        await component.store.renameWorkspace(id, _renameTo);
        _renaming = null;
      });

  Future<void> _delete(WorkspaceSummary summary) => _run(() async {
        _deleting = null;
        await component.store.deleteWorkspace(summary.id);
        ArcaneSonner.info('Deleted ${summary.name}. Restore brings it back while this tab is open.');
      });

  @override
  Widget build(BuildContext context) {
    final EditorStore store = component.store;
    final bool busy = component.busy || _working;
    final List<(String, String)> packs = starterPacks();
    return dom.main_(classes: 'animus-home', <Widget>[
      dom.section(classes: 'animus-panel animus-home-intro', attributes: const <String, String>{'aria-labelledby': 'animus-start-title'}, <Widget>[
        const dom.h1(id: 'animus-start-title', <Widget>[Component.text('Start a workspace')]),
        const dom.p(<Widget>[
          Component.text('Make NPCs, behaviors, shops, GUIs, holograms and rigs right here, with no server. '
              'Workspaces are kept in this browser, checked against the shared schemas as you go, and can be exported '
              'as a data folder zip or sent to a server once you pair one.'),
        ]),
        dom.div(classes: 'animus-start-row', <Widget>[
          OfflineTextField(label: 'Workspace name', value: _name, placeholder: 'My workspace', onChanged: (String value) => setState(() => _name = value)),
          dom.div(classes: 'animus-row', <Widget>[
            Button.primary(label: 'Create blank workspace', icon: ArcaneIcon.plus(), disabled: busy, onPressed: _createBlank),
            Button.secondary(label: 'Create with example packs', disabled: busy, onPressed: _createStarter),
          ]),
        ]),
        dom.p(classes: 'animus-muted animus-start-packs', <Widget>[
          Component.text('The ${packs.length} example packs come with their NPCs, behaviors, a shop and a chest GUI: '
              '${packs.map(((String, String) pack) => pack.$1).join(', ')}.'),
        ]),
      ]),
      _workspaces(store, busy),
      dom.section(classes: 'animus-panel', attributes: const <String, String>{'aria-labelledby': 'animus-open-title'}, <Widget>[
        const dom.h2(id: 'animus-open-title', <Widget>[Component.text('Open an Animus data folder')]),
        const dom.p(classes: 'animus-muted', <Widget>[
          Component.text('Pick plugins/Animus (config/animus on mod servers) or a zip of it. It becomes a workspace here; nothing is uploaded.'),
        ]),
        dom.div(classes: 'animus-row', <Widget>[
          Button.secondary(label: 'Open folder', icon: ArcaneIcon.folderOpen(), disabled: busy, onPressed: component.onOpenFolder),
          Button.secondary(label: 'Open zip', icon: ArcaneIcon.fileArchive(), disabled: busy, onPressed: component.onOpenZip),
          Button.ghost(
            label: 'Convert Citizens, Shopkeepers, dtlTraders or Denizen',
            id: 'animus-open-converter',
            icon: ArcaneIcon.arrowRightLeft(),
            disabled: busy,
            onPressed: () => component.onNavigate(const CitizensRoute()),
          ),
        ]),
      ]),
      ServersPanel(hub: component.hub, onNavigate: component.onNavigate),
    ]);
  }

  Widget _workspaces(EditorStore store, bool busy) {
    final WorkspaceSnapshot? deleted = store.deleted;
    return dom.section(classes: 'animus-panel', attributes: const <String, String>{'aria-labelledby': 'animus-workspaces-title'}, <Widget>[
      const dom.h2(id: 'animus-workspaces-title', <Widget>[Component.text('Workspaces in this browser')]),
      if (deleted != null && !store.workspaces.any((WorkspaceSummary summary) => summary.id == deleted.id))
        dom.div(classes: 'animus-row animus-restore', attributes: const <String, String>{'role': 'status'}, <Widget>[
          Component.text('Deleted ${deleted.name} (${deleted.documents.length} documents).'),
          Button.secondary(
            label: 'Restore ${deleted.name}',
            size: ButtonSize.small,
            disabled: busy,
            onPressed: () => _run(() async {
              await store.restoreDeleted();
            }),
          ),
        ]),
      if (store.workspaces.isEmpty)
        const dom.p(classes: 'animus-muted', <Widget>[Component.text('None yet. New, opened and pulled workspaces are kept here.')])
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
        onCancel: () => setState(() => _deleting = null),
      );
    }
    if (_renaming == summary.id) {
      return dom.div(classes: 'animus-row animus-rename', <Widget>[
        OfflineTextField(label: 'New name for ${summary.name}', value: _renameTo, onChanged: (String value) => setState(() => _renameTo = value)),
        Button.primary(label: 'Rename', size: ButtonSize.small, disabled: busy || _renameTo.trim().isEmpty, onPressed: () => _rename(summary.id)),
        Button.ghost(label: 'Cancel', size: ButtonSize.small, onPressed: () => setState(() => _renaming = null)),
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
      dom.div(classes: 'animus-row animus-workspace-actions', attributes: <String, String>{'role': 'group', 'aria-label': 'Manage ${summary.name}'}, <Widget>[
        Button.ghost(
          label: 'Rename',
          size: ButtonSize.small,
          attributes: <String, String>{'aria-label': 'Rename ${summary.name}'},
          onPressed: () => setState(() {
            _renaming = summary.id;
            _renameTo = summary.name;
            _deleting = null;
          }),
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
        Button.ghost(
          label: 'Delete',
          size: ButtonSize.small,
          attributes: <String, String>{'aria-label': 'Delete ${summary.name}'},
          onPressed: () => setState(() {
            _deleting = summary.id;
            _renaming = null;
          }),
        ),
      ]),
    ]);
  }
}
