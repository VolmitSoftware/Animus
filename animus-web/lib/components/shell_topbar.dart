/// The top bar: the brand and workspace on the left, the workspace's own
/// controls (undo and redo, then the Open, Import and Export menus) in the
/// middle, and the app's (commands, theme) on the right. Phones keep the
/// brand, the connection pill, Documents and a More menu.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../state/live_session.dart';
import '../state/workspace_repository.dart';
import 'brand_mark.dart';
import 'menu_button.dart';
import 'server/connection_pill.dart';

/// What the top bar's controls do. A null action shows its control
/// disabled.
class TopbarActions {
  const TopbarActions({
    required this.onPalette,
    required this.onToggleTheme,
    required this.onOpenFolder,
    required this.onOpenZip,
    this.onUndo,
    this.onRedo,
    this.onImportBbmodel,
    this.onImportGloss,
    this.onExportZip,
    this.onExportGloss,
    this.onDocuments,
  });

  final void Function() onPalette;
  final void Function() onToggleTheme;
  final void Function()? onOpenFolder;
  final void Function()? onOpenZip;
  final void Function()? onUndo;
  final void Function()? onRedo;
  final void Function()? onImportBbmodel;
  final void Function()? onImportGloss;
  final void Function()? onExportZip;
  final void Function()? onExportGloss;

  /// Opens the documents drawer on phones.
  final void Function()? onDocuments;
}

class ShellTopbar extends StatelessWidget {
  const ShellTopbar({
    required this.actions,
    required this.dark,
    required this.compact,
    this.workspace,
    this.session,
    this.undoLabel,
    this.redoLabel,
    this.drawerOpen = false,
    super.key,
  });

  final TopbarActions actions;
  final bool dark;

  /// Phone widths (760 px and below).
  final bool compact;
  final WorkspaceSnapshot? workspace;
  final LiveSession? session;

  /// What Undo and Redo would revert, for their tooltips and the More menu.
  final String? undoLabel;
  final String? redoLabel;
  final bool drawerOpen;

  String get _themeLabel => dark ? 'Use light theme' : 'Use dark theme';

  List<MenuEntry> get _openEntries => <MenuEntry>[
        MenuEntry('Open folder', actions.onOpenFolder),
        MenuEntry('Open zip', actions.onOpenZip),
      ];

  List<MenuEntry> get _importEntries => <MenuEntry>[
        MenuEntry('Import a Blockbench model', actions.onImportBbmodel),
        MenuEntry('Import a Gloss bundle', actions.onImportGloss),
      ];

  List<MenuEntry> get _exportEntries => <MenuEntry>[
        MenuEntry('Export the data folder zip', actions.onExportZip),
        MenuEntry('Export a Gloss bundle', actions.onExportGloss),
      ];

  @override
  Widget build(BuildContext context) {
    final WorkspaceSnapshot? workspace = this.workspace;
    return dom.header(classes: 'animus-topbar${compact ? ' is-compact' : ''}', <Widget>[
      dom.a(
        href: '#/',
        classes: 'animus-brand',
        attributes: compact ? const <String, String>{'aria-label': 'Animus Editor, all workspaces'} : null,
        <Widget>[
          const BrandMark(size: 28),
          if (!compact) const dom.span(classes: 'animus-brand-name', <Widget>[Component.text('Animus Editor')]),
        ],
      ),
      if (workspace != null && !compact) dom.span(classes: 'animus-topbar-workspace', <Widget>[Component.text(workspace.name)]),
      // A phone's short top bar keeps its room for Documents and More.
      if (session != null && !compact) ConnectionPill(status: session!.connection.status, hasToken: session!.connection.token != null),
      if (compact) ..._compactControls(workspace) else ..._wideControls(workspace),
    ]);
  }

  List<Widget> _wideControls(WorkspaceSnapshot? workspace) => <Widget>[
        dom.div(classes: 'animus-topbar-actions', <Widget>[
          if (workspace != null) ...<Widget>[
            dom.div(classes: 'animus-toolgroup', attributes: const <String, String>{'role': 'group', 'aria-label': 'Edit history'}, <Widget>[
              Button.ghost(
                label: 'Undo',
                icon: ArcaneIcon.undo(),
                size: ButtonSize.small,
                disabled: actions.onUndo == null,
                attributes: <String, String>{
                  'aria-keyshortcuts': 'Control+Z Meta+Z',
                  'title': undoLabel == null ? 'Nothing to undo' : 'Undo: $undoLabel',
                },
                onPressed: actions.onUndo,
              ),
              Button.ghost(
                label: 'Redo',
                icon: ArcaneIcon.redo(),
                size: ButtonSize.small,
                disabled: actions.onRedo == null,
                attributes: <String, String>{
                  'aria-keyshortcuts': 'Control+Shift+Z Meta+Shift+Z Control+Y',
                  'title': redoLabel == null ? 'Nothing to redo' : 'Redo: $redoLabel',
                },
                onPressed: actions.onRedo,
              ),
            ]),
            dom.div(classes: 'animus-toolgroup', attributes: const <String, String>{'role': 'group', 'aria-label': 'Files'}, <Widget>[
              MenuButton(id: 'animus-menu-open', label: 'Open', icon: ArcaneIcon.folderOpen(), entries: _openEntries),
              MenuButton(id: 'animus-menu-import', label: 'Import', icon: ArcaneIcon.upload(), entries: _importEntries),
              MenuButton(id: 'animus-menu-export', label: 'Export', icon: ArcaneIcon.download(), emphasis: true, entries: _exportEntries),
            ]),
          ],
        ]),
        dom.div(classes: 'animus-topbar-app', <Widget>[
          Button.ghost(
            label: 'Commands',
            icon: ArcaneIcon.search(),
            size: ButtonSize.small,
            attributes: const <String, String>{'aria-keyshortcuts': 'Control+K Meta+K', 'title': 'Commands (Ctrl+K)'},
            onPressed: actions.onPalette,
          ),
          Button.ghost(
            icon: dark ? ArcaneIcon.sun() : ArcaneIcon.moon(),
            size: ButtonSize.small,
            attributes: <String, String>{'aria-label': _themeLabel, 'title': _themeLabel},
            onPressed: actions.onToggleTheme,
          ),
        ]),
      ];

  List<Widget> _compactControls(WorkspaceSnapshot? workspace) => <Widget>[
        dom.div(classes: 'animus-topbar-actions', <Widget>[
          if (workspace != null)
            Button.secondary(
              id: 'animus-documents-button',
              label: 'Documents',
              icon: ArcaneIcon.panelLeft(),
              size: ButtonSize.small,
              attributes: <String, String>{'aria-controls': 'animus-sidebar', 'aria-expanded': '$drawerOpen'},
              onPressed: actions.onDocuments,
            ),
          MenuButton(
            id: 'animus-menu-more',
            label: 'More',
            icon: ArcaneIcon.moreHorizontal(),
            entries: <MenuEntry>[
              if (workspace != null) ...<MenuEntry>[
                MenuEntry('Undo', actions.onUndo, hint: undoLabel),
                MenuEntry('Redo', actions.onRedo, hint: redoLabel),
              ],
              MenuEntry('Commands', actions.onPalette),
              ..._openEntries,
              if (workspace != null) ...<MenuEntry>[..._importEntries, ..._exportEntries],
              MenuEntry(_themeLabel, actions.onToggleTheme),
            ],
          ),
        ]),
      ];
}
