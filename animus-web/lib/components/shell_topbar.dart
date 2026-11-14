/// The top bar: the brand, the switcher between paired servers and
/// workspaces, search, Undo and Redo, the Mode switch and one overflow menu
/// for the rest (theme, shortcuts, files). On phones the nav button comes
/// first and Undo, Redo and the theme move into the overflow menu; the Mode
/// switch sits at the top of the nav drawer.
///
/// The bar stays one line. When it does not fit, measured after layout, it
/// gives up in steps: the brand's name, Search's text, Undo and Redo (into
/// the overflow menu), the state badge's text, then the switcher's name is
/// shortened (the whole name stays its tooltip).
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../services/server/connection_status.dart';
import '../services/server/server_profiles.dart';
import '../state/server_hub.dart';
import '../state/workspace_repository.dart';
import '../state/workspace_route.dart';
import '../ui/badge.dart';
import '../ui/density.dart';
import '../ui/editor_mode.dart';
import '../ui/icon_button.dart';
import '../ui/menu.dart';
import '../ui/name_fit.dart';
import '../ui/tone.dart';
import '../ui/toggle_choice.dart';
import 'brand_mark.dart';
import 'shortcut_sheet.dart';

/// What the top bar's controls do. A null action shows its control disabled.
class TopbarActions {
  const TopbarActions({
    required this.onPalette,
    required this.onToggleTheme,
    required this.onShortcuts,
    required this.onMode,
    required this.onNavigate,
    this.onOpenFolder,
    this.onOpenZip,
    this.onUndo,
    this.onRedo,
    this.onImportBbmodel,
    this.onImportGloss,
    this.onExportZip,
    this.onExportGloss,
    this.onNav,
  });

  final void Function() onPalette;
  final void Function() onToggleTheme;
  final void Function() onShortcuts;
  final void Function(EditorMode mode) onMode;
  final void Function(EditorRoute route) onNavigate;
  final void Function()? onOpenFolder;
  final void Function()? onOpenZip;
  final void Function()? onUndo;
  final void Function()? onRedo;
  final void Function()? onImportBbmodel;
  final void Function()? onImportGloss;
  final void Function()? onExportZip;
  final void Function()? onExportGloss;

  /// Opens the nav drawer on phones.
  final void Function()? onNav;
}

/// How far the top bar folded to fit: each step keeps the ones before it.
enum TopbarFold { none, brandName, searchText, history, stateText, name }

class ShellTopbar extends StatefulWidget {
  const ShellTopbar({
    required this.actions,
    required this.dark,
    required this.compact,
    required this.mode,
    required this.hub,
    required this.workspaces,
    this.workspace,
    this.undoLabel,
    this.redoLabel,
    this.navOpen = false,
    this.elsewhere = false,
    super.key,
  });

  final TopbarActions actions;
  final bool dark;

  /// Phone widths (760 px and below).
  final bool compact;
  final EditorMode mode;
  final ServerHub hub;
  final List<WorkspaceSummary> workspaces;
  final WorkspaceSnapshot? workspace;

  /// What Undo and Redo would revert, for their tooltips.
  final String? undoLabel;
  final String? redoLabel;
  final bool navOpen;

  /// Live editing for the open server runs in another tab.
  final bool elsewhere;

  @override
  State<ShellTopbar> createState() => _ShellTopbarState();
}

class _ShellTopbarState extends State<ShellTopbar> {
  static const String _barId = 'ui-topbar';

  TopbarFold _fold = TopbarFold.none;

  /// The switcher name's length once [TopbarFold.name] is reached.
  int? _nameBudget;
  double _width = 0;
  String _title = '';
  web.ResizeObserver? _observer;

  ShellTopbar get bar => component;

  bool _past(TopbarFold step) => _fold.index >= step.index;

  String get _themeLabel => bar.dark ? 'Light theme' : 'Dark theme';

  /// Undo and Redo sit in the overflow menu on phones and once the bar folded them.
  bool get _historyInMenu => bar.compact || _past(TopbarFold.history);

  List<AppMenuEntry> get _overflow => <AppMenuEntry>[
        if (_historyInMenu && bar.workspace != null) ...<AppMenuEntry>[
          AppMenuEntry(bar.undoLabel == null ? 'Undo' : 'Undo: ${bar.undoLabel}', bar.actions.onUndo, hint: platformKeys('Mod+Z')),
          AppMenuEntry(bar.redoLabel == null ? 'Redo' : 'Redo: ${bar.redoLabel}', bar.actions.onRedo, hint: platformKeys('Mod+Shift+Z')),
          const AppMenuEntry.separator(),
        ],
        AppMenuEntry(_themeLabel, bar.actions.onToggleTheme),
        // A phone has no keyboard to list.
        if (!bar.compact) AppMenuEntry('Keyboard shortcuts', bar.actions.onShortcuts, hint: '?'),
        const AppMenuEntry.separator(),
        AppMenuEntry('Open folder', bar.actions.onOpenFolder),
        AppMenuEntry('Open zip', bar.actions.onOpenZip),
        if (bar.workspace != null) AppMenuEntry('Export zip', bar.actions.onExportZip),
        if (bar.workspace != null && bar.mode == EditorMode.advanced) ...<AppMenuEntry>[
          const AppMenuEntry.separator(),
          AppMenuEntry('Import Blockbench model', bar.actions.onImportBbmodel),
          AppMenuEntry('Import Gloss bundle', bar.actions.onImportGloss),
          AppMenuEntry('Export Gloss bundle', bar.actions.onExportGloss),
        ],
      ];

  @override
  void didUpdateWidget(ShellTopbar oldComponent) {
    super.didUpdateWidget(oldComponent);
    // Another layout (phone or not, a page with or without Undo) starts over.
    if (oldComponent.compact != bar.compact || (oldComponent.workspace == null) != (bar.workspace == null) || oldComponent.mode != bar.mode) _unfold();
  }

  @override
  void dispose() {
    _observer?.disconnect();
    super.dispose();
  }

  void _unfold() {
    _fold = TopbarFold.none;
    _nameBudget = null;
  }

  void _observe() {
    if (!mounted || _observer != null) return;
    final web.Element? row = web.document.getElementById(_barId);
    if (row == null) return;
    _observer = web.ResizeObserver(((JSArray<web.ResizeObserverEntry> _, web.ResizeObserver _) => _resized()).toJS);
    _observer!.observe(row);
  }

  void _resized() {
    final web.Element? row = web.document.getElementById(_barId);
    if (row == null || !mounted) return;
    final double width = row.getBoundingClientRect().width;
    final bool grew = width > _width + 1;
    _width = width;
    if (grew && _fold != TopbarFold.none) {
      setState(_unfold);
      return;
    }
    _measure();
  }

  /// How far the bar's content sticks out: past the bar itself, or out of
  /// the switcher, which shrinks before anything else.
  double _overflowPx() {
    final web.Element? row = web.document.getElementById(_barId);
    if (row == null) return 0;
    double over = (row.scrollWidth - row.clientWidth).toDouble();
    final web.Element? switcher = row.querySelector('.ui-context');
    if (switcher != null) {
      final double inner = (switcher.scrollWidth - switcher.clientWidth).toDouble();
      if (inner > over) over = inner;
    }
    return over;
  }

  void _measure() {
    if (!mounted) return;
    final double over = _overflowPx();
    if (over <= 1) return;
    if (_fold != TopbarFold.name) {
      TopbarFold next = TopbarFold.values[_fold.index + 1];
      // A phone already shows no brand name, no Search text, no Undo or
      // Redo and no state text.
      if (bar.compact && next.index < TopbarFold.name.index) next = TopbarFold.name;
      setState(() => _fold = next);
      return;
    }
    final int? budget = shorterBudget(_nameBudget ?? shortenName(_title, contextNameLimit).runes.length, over);
    if (budget != null && budget != _nameBudget) setState(() => _nameBudget = budget);
  }

  /// Every control in the bar is dense (32 px).
  @override
  Widget build(BuildContext context) {
    web.window.requestAnimationFrame(((JSNumber _) {
      _observe();
      _measure();
    }).toJS);
    final bool compact = bar.compact;
    final TopbarActions actions = bar.actions;
    _title = contextTitle(bar.hub, bar.workspace);
    return DensityScope.dense(child: dom.header(id: _barId, classes: 'ui-topbar', <Widget>[
        if (compact && bar.workspace != null)
          AppIconButton(
            id: 'ui-nav-button',
            icon: ArcaneIcon.menu(),
            label: 'Menu',
            expanded: bar.navOpen,
            attributes: const <String, String>{'aria-controls': 'ui-nav-drawer'},
            onPressed: actions.onNav,
          ),
        dom.a(
          href: '#/',
          classes: 'ui-brand',
          attributes: const <String, String>{'aria-label': 'Animus, all workspaces'},
          <Widget>[
            const BrandMark(size: 24),
            if (!compact && !_past(TopbarFold.brandName)) const dom.span(classes: 'ui-brand-name', <Widget>[Component.text('Animus')]),
          ],
        ),
        ContextSwitcher(
          hub: bar.hub,
          workspaces: bar.workspaces,
          workspace: bar.workspace,
          elsewhere: bar.elsewhere,
          onNavigate: actions.onNavigate,
          nameBudget: _past(TopbarFold.name) ? _nameBudget : null,
          dotOnly: compact || _past(TopbarFold.stateText),
        ),
        const Spacer(),
        if (compact || _past(TopbarFold.searchText))
          AppIconButton(
            icon: ArcaneIcon.search(),
            label: 'Search (${platformKeys('Mod+K')})',
            attributes: <String, String>{'aria-keyshortcuts': platformAriaKeys(const <String>['Mod+K'])},
            onPressed: actions.onPalette,
          )
        else
          Button.outline(
            label: 'Search',
            icon: ArcaneIcon.search(size: controlIconSize),
            size: ButtonSize.sm,
            attributes: <String, String>{'aria-keyshortcuts': platformAriaKeys(const <String>['Mod+K']), 'data-shortcut': platformKeys('Mod+K')},
            onPressed: actions.onPalette,
          ),
        if (!_historyInMenu && bar.workspace != null)
          dom.div(classes: 'ui-topbar-history', attributes: const <String, String>{'role': 'group', 'aria-label': 'Edit history'}, <Widget>[
            AppIconButton(
              icon: ArcaneIcon.undo(),
              label: bar.undoLabel == null ? 'Nothing to undo' : 'Undo: ${bar.undoLabel} (${platformKeys('Mod+Z')})',
              attributes: <String, String>{'aria-keyshortcuts': platformAriaKeys(const <String>['Mod+Z'])},
              onPressed: actions.onUndo,
            ),
            AppIconButton(
              icon: ArcaneIcon.redo(),
              label: bar.redoLabel == null ? 'Nothing to redo' : 'Redo: ${bar.redoLabel} (${platformKeys('Mod+Shift+Z')})',
              attributes: <String, String>{'aria-keyshortcuts': platformAriaKeys(const <String>['Mod+Shift+Z', 'Mod+Y'])},
              onPressed: actions.onRedo,
            ),
          ]),
        if (!compact) ModeSwitch(mode: bar.mode, onChanged: actions.onMode),
        AppMenu(id: 'ui-overflow', icon: ArcaneIcon.ellipsis(), entries: _overflow, alignEnd: true),
      ]));
  }
}

/// Mode: Simple or Advanced.
class ModeSwitch extends StatelessWidget {
  const ModeSwitch({required this.mode, required this.onChanged, super.key});

  final EditorMode mode;
  final void Function(EditorMode mode) onChanged;

  @override
  Widget build(BuildContext context) => dom.div(classes: 'ui-mode-switch', <Widget>[
        ToggleChoice<EditorMode>(
          label: 'Mode',
          options: <ChoiceOption<EditorMode>>[for (final EditorMode each in EditorMode.values) ChoiceOption<EditorMode>(each, each.label)],
          value: mode,
          onChanged: onChanged,
        ),
      ]);
}

/// The open server or workspace, and a popover to switch: paired servers
/// with their state, workspaces, Pair a server and All workspaces.
class ContextSwitcher extends StatefulWidget {
  const ContextSwitcher({
    required this.hub,
    required this.workspaces,
    required this.onNavigate,
    this.workspace,
    this.elsewhere = false,
    this.nameBudget,
    this.dotOnly = false,
    super.key,
  });

  final ServerHub hub;

  /// The name's length when the bar had to shorten it.
  final int? nameBudget;

  /// The state badge shows its dot; its text is read, not shown.
  final bool dotOnly;

  /// Live editing for the open server runs in another tab.
  final bool elsewhere;
  final List<WorkspaceSummary> workspaces;
  final WorkspaceSnapshot? workspace;
  final void Function(EditorRoute route) onNavigate;

  @override
  State<ContextSwitcher> createState() => _ContextSwitcherState();
}

/// The switcher's name: the open server's (its MOTD), the workspace's, or
/// All workspaces.
String contextTitle(ServerHub hub, WorkspaceSnapshot? workspace) {
  if (workspace == null) return 'All workspaces';
  final String? serverId = workspace.server?.serverId;
  final ServerProfile? server = serverId == null ? null : hub.server(serverId);
  return server?.name ?? workspace.name;
}

/// A server's state in two or three words: a live connection's, or a saved
/// server's reachability check (Online, Checking, Not reachable).
(String, BadgeTone) serverState(ConnectionStatus status) => switch (status.phase) {
      ConnectionPhase.online => ('Online', BadgeTone.ok),
      ConnectionPhase.connecting => (status.probed ? 'Checking' : 'Connecting', BadgeTone.info),
      ConnectionPhase.reconnecting => ('Reconnecting', BadgeTone.warning),
      ConnectionPhase.needsPairing => ('Pairing ended', BadgeTone.error),
      ConnectionPhase.refused => ('Refused', BadgeTone.error),
      ConnectionPhase.offline => (status.probed ? 'Not reachable' : 'Offline', BadgeTone.neutral),
    };

class _ContextSwitcherState extends State<ContextSwitcher> {
  bool _open = false;

  void _go(EditorRoute route) {
    setState(() => _open = false);
    component.onNavigate(route);
  }

  /// One row of the list: the name (cut to fit, whole in its tooltip) and,
  /// for a server, its state as a dot whose words are read out.
  Widget _row(String label, EditorRoute route, {Widget? badge, bool current = false, ArcaneGlyph? icon}) => Row(spacing: 8, children: <Widget>[
        Expanded(
          child: Button(
            label: shortenName(label, _rowNameLimit),
            icon: icon?.sized(controlIconSize),
            size: ButtonSize.sm,
            variant: current ? ButtonVariant.secondary : ButtonVariant.ghost,
            fullWidth: true,
            // Rows start at the left edge, as menu items do.
            styles: const ArcaneStyleData(justifyContent: JustifyContent.flexStart),
            attributes: <String, String>{if (current) 'aria-current': 'true', if (label.length > _rowNameLimit) 'aria-label': label},
            onPressed: () => _go(route),
          ),
        ),
        ?badge,
      ]);

  static const int _rowNameLimit = 30;

  /// The open server's state (the save bar holds what to do about it); a
  /// narrow bar shows only the dot and keeps the words for screen readers
  /// and the tooltip.
  Widget _state(String label, BadgeTone tone) {
    const Map<String, String> test = <String, String>{'data-testid': 'context-state'};
    return component.dotOnly ? StatusBadge.dotOnly(label, tone: tone, attributes: test) : StatusBadge(label, tone: tone, dot: true, attributes: test);
  }

  @override
  Widget build(BuildContext context) {
    final WorkspaceSnapshot? workspace = component.workspace;
    final String? serverId = workspace?.server?.serverId;
    final String title = contextTitle(component.hub, workspace);
    final (String, BadgeTone)? state = serverId == null ? null : (component.elsewhere ? ('In another tab', BadgeTone.neutral) : serverState(component.hub.status(serverId)));
    final List<WorkspaceSummary> local = <WorkspaceSummary>[for (final WorkspaceSummary each in component.workspaces) if (each.serverId == null) each];
    return dom.div(classes: 'ui-context', <Widget>[
      ArcanePopover(
        id: 'ui-context-popover',
        isOpen: _open,
        onOpenChange: (bool open) {
          // Saved servers are checked once each time the list shows.
          if (open && !_open) component.hub.probeServers();
          setState(() => _open = open);
        },
        position: FloatingPosition.bottomStart,
        showArrow: false,
        offset: 6,
        trigger: Button.ghost(
          // A server named by a long MOTD keeps the bar to one line; the
          // whole name is the tooltip.
          label: shortenName(title, component.nameBudget ?? contextNameLimit),
          icon: ArcaneIcon.chevronDown(size: IconSize.sm),
          iconPosition: ButtonIconPosition.trailing,
          size: ButtonSize.sm,
          attributes: <String, String>{
            'id': 'ui-context-button',
            'title': title,
            'aria-haspopup': 'dialog',
            'aria-expanded': '$_open',
            'data-state': ?state?.$1,
          },
          onPressed: () {},
        ),
        // The list scrolls past a screenful of servers.
        content: ArcaneScrollArea(
          className: 'ui-context-list',
          width: 'min(20rem, calc(100vw - 2rem))',
          maxHeight: 'min(28rem, 70dvh)',
          child: Column(spacing: 4, crossAxisAlignment: CrossAxisAlignment.stretch, mainAxisSize: MainAxisSize.min, children: <Widget>[
          if (component.hub.servers.isNotEmpty) ...<Widget>[
            const Text('Servers', size: FontSize.xs, weight: FontWeight.w500, color: TextColor.mutedForeground, element: 'h2'),
            for (final ServerProfile each in component.hub.servers)
              _row(
                each.name,
                ServerRoute(each.id, const <String>[]),
                current: each.id == serverId,
                badge: StatusBadge.dotOnly(serverState(component.hub.status(each.id)).$1, tone: serverState(component.hub.status(each.id)).$2),
              ),
          ],
          if (local.isNotEmpty) ...<Widget>[
            const Text('Workspaces', size: FontSize.xs, weight: FontWeight.w500, color: TextColor.mutedForeground, element: 'h2'),
            for (final WorkspaceSummary each in local.take(8))
              _row(each.name, WorkspaceRoute(each.id), current: each.id == workspace?.id),
          ],
          const ArcaneSeparator(),
          _row('Pair a server', const PairRoute(), icon: ArcaneIcon.plus()),
          _row('All workspaces', const HomeRoute(), icon: ArcaneIcon.layoutGrid()),
        ]),
        ),
      ),
      if (state != null) _state(state.$1, state.$2),
    ]);
  }
}
