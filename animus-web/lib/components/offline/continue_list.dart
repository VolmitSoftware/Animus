/// The start page's Continue list: each paired server once (its state and
/// how many documents its workspace holds), then the workspaces kept in
/// this browser. A row is its name, one line and Open; Rename, Duplicate,
/// Export zip, Delete and Forget sit in its "..." menu and confirm first.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../services/server/server_profiles.dart';
import '../../state/editor_store.dart';
import '../../state/server_hub.dart';
import '../../state/workspace_repository.dart';
import '../../state/workspace_route.dart';
import '../../ui/action_row.dart';
import '../../ui/badge.dart';
import '../../ui/confirm_dialog.dart';
import '../../ui/dialog.dart';
import '../../ui/field.dart';
import '../../ui/menu.dart';
import '../../ui/tone.dart';
import '../rig/rig_actions.dart' show exportWorkspace;
import '../shell_topbar.dart' show serverState;
import 'page_parts.dart';
import 'start_rows.dart';
import '../../ui/stack.dart';
import '../../model/display_name.dart' show closing;

class ContinueList extends StatefulWidget {
  const ContinueList({required this.store, required this.hub, required this.busy, required this.onNavigate, super.key});

  final EditorStore store;
  final ServerHub hub;
  final bool busy;
  final void Function(EditorRoute route) onNavigate;

  @override
  State<ContinueList> createState() => _ContinueListState();
}

class _ContinueListState extends State<ContinueList> {
  static const String _renameId = 'continue-rename-name';

  bool _working = false;
  WorkspaceSummary? _renaming;
  String _renameTo = '';

  bool get _busy => component.busy || _working;

  static String _slug(String key) => key.replaceAll(RegExp(r'[^A-Za-z0-9_-]'), '_');

  Future<void> _run(String what, Future<void> Function() task) async {
    setState(() => _working = true);
    try {
      await task();
    } on Object catch (failure) {
      ArcaneSonner.error('Could not $what ($failure). Reload and try again.');
    } finally {
      if (mounted) setState(() => _working = false);
    }
  }

  void _open(ContinueRow row) {
    final ServerProfile? server = row.server;
    component.onNavigate(server != null ? ServerRoute(server.id, const <String>[]) : WorkspaceRoute(row.workspace!.id));
  }

  Future<void> _export(WorkspaceSummary summary) => _run('export ${summary.name}', () async {
        final WorkspaceSnapshot? workspace = await component.store.loadWorkspace(summary.id);
        if (workspace != null) exportWorkspace(workspace);
      });

  Future<void> _duplicate(WorkspaceSummary summary) => _run('copy ${summary.name}', () async {
        final WorkspaceSnapshot? copy = await component.store.duplicateWorkspace(summary.id, '${summary.name} copy');
        if (copy != null) ArcaneSonner.success('Copied ${summary.name} as ${copy.name}.');
      });

  static String _moreId(ContinueRow row) => 'continue-more-${_slug(row.key)}';

  Future<void> _delete(WorkspaceSummary summary) async {
    final bool yes = await askToConfirm(
      title: 'Delete ${summary.name}?',
      message: 'Removes its ${documentsLine(summary.documentCount)} and undo history from this browser.',
      confirmLabel: 'Delete workspace',
      destructive: true,
    );
    if (!yes) return;
    await _run('delete ${summary.name}', () => component.store.deleteWorkspace(summary.id));
  }

  Future<void> _forget(ServerProfile server) async {
    final bool yes = await askToConfirm(
      title: 'Forget ${closing(server.name, '?')}',
      message: component.hub.forgetPrompt(server.id),
      confirmLabel: 'Forget server',
      destructive: true,
    );
    if (!yes) return;
    await _run('forget ${server.name}', () async {
      final String? stillValid = await component.hub.forget(server.id);
      final String line = ServerHub.forgotLine(server.name, stillValid);
      stillValid == null ? ArcaneSonner.success(line) : ArcaneSonner.warning(line);
    });
  }

  void _startRename(WorkspaceSummary summary) {
    setState(() {
      _renaming = summary;
      _renameTo = summary.name;
    });
  }

  Future<void> _rename() async {
    final WorkspaceSummary? summary = _renaming;
    if (summary == null || _renameTo.trim().isEmpty) return;
    setState(() => _renaming = null);
    await _run('rename ${summary.name}', () => component.store.renameWorkspace(summary.id, _renameTo.trim()));
  }

  List<AppMenuEntry> _menu(ContinueRow row) {
    final WorkspaceSummary? workspace = row.workspace;
    final ServerProfile? server = row.server;
    final List<AppMenuEntry> keep = <AppMenuEntry>[
      if (server == null && workspace != null && !row.orphan) ...<AppMenuEntry>[
        AppMenuEntry('Rename', _busy ? null : () => _startRename(workspace)),
        AppMenuEntry('Duplicate', _busy ? null : () => _duplicate(workspace)),
      ],
      if (workspace != null) AppMenuEntry('Export zip', _busy ? null : () => _export(workspace)),
    ];
    final AppMenuEntry remove = server != null
        ? AppMenuEntry('Forget server', _busy ? null : () => _forget(server), destructive: true)
        : AppMenuEntry('Delete workspace', _busy ? null : () => _delete(workspace!), destructive: true);
    return <AppMenuEntry>[...keep, if (keep.isNotEmpty) const AppMenuEntry.separator(), remove];
  }

  Widget _meta(ContinueRow row) {
    final ServerProfile? server = row.server;
    final WorkspaceSummary? workspace = row.workspace;
    final List<String> words = <String>[
      if (workspace != null) documentsLine(workspace.documentCount),
      if (server == null) row.orphan ? 'Server no longer paired' : 'In this browser',
      if (server != null && workspace == null) 'Not opened yet',
    ];
    final (String, BadgeTone)? state = server == null ? null : serverState(component.hub.status(server.id));
    return Wrap(spacing: 8, runSpacing: 4, crossAxisAlignment: WrapCrossAlignment.center, children: <Widget>[
      if (state != null) StatusBadge(state.$1, tone: state.$2, dot: true, attributes: const <String, String>{'data-testid': 'server-state'}),
      mutedText(words.join(', ')),
    ]);
  }

  @override
  Widget build(BuildContext context) {
    final List<ContinueRow> rows = continueRows(component.hub.servers, component.store.workspaces);
    final WorkspaceSummary? renaming = _renaming;
    return dom.div(<Widget>[
      Card(
        variant: CardVariant.outlined,
        padding: const EdgeInsets.symmetric(horizontal: 16),
        child: rowList(label: 'Continue', separated: true, <Widget>[
          for (final ContinueRow row in rows)
            listItem(
              classes: 'ui-continue-row',
              attributes: <String, String>{'data-kind': row.isServer ? 'server' : 'workspace'},
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 10),
                child: splitRow(
                  stack(spacing: 2, <Widget>[
                    dom.span(classes: 'ui-continue-name', attributes: <String, String>{'title': row.name}, <Widget>[Text.truncated(row.name, weight: FontWeight.w500)]),
                    _meta(row),
                  ]),
                  ActionRow(
                    dense: true,
                    label: row.name,
                    moreId: _moreId(row),
                    more: _menu(row),
                    secondary: <RowAction>[
                      RowAction('Open', _busy ? null : () => _open(row), attributes: <String, String>{'aria-label': 'Open ${row.name}', 'data-testid': 'continue-open'}),
                    ],
                  ),
                ),
              ),
            ),
        ]),
      ),
      AppDialog(
        id: 'continue-rename',
        open: renaming != null,
        title: 'Rename workspace',
        onClose: () => setState(() => _renaming = null),
        footer: DialogFooter(
          cancel: RowAction('Cancel', () => setState(() => _renaming = null)),
          primary: RowAction('Rename', _renameTo.trim().isEmpty ? null : _rename),
        ),
        children: <Widget>[
          if (renaming != null)
            Field(
              label: 'Name',
              forId: _renameId,
              child: TextInput(
                id: _renameId,
                value: _renameTo,
                fullWidth: true,
                onChanged: (String value) => setState(() => _renameTo = value),
                onSubmitted: (String _) => _rename(),
              ),
            ),
        ],
      ),
    ]);
  }
}
