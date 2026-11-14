/// The head of every page: breadcrumb, then one line with the title and its
/// status badge on the left and the page's [ActionRow] on the right: an
/// optional view switch, the "..." menu, secondary buttons and the primary
/// one last. The line never wraps: a long title ends in "...", and actions
/// that do not fit fold into the "..." menu. With [PageHeader.onRename] the
/// menu's Rename turns the title into a text box; Enter keeps the new text
/// and Escape drops it.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:web/web.dart' as web;

import '../services/browser.dart';
import 'action_row.dart';
import 'edit_scope.dart';
import 'menu.dart';
import 'name_fit.dart';
import 'problem_text.dart';
import '../model/animus_document.dart';

/// One step of the breadcrumb; the last one is the page itself.
class Crumb {
  const Crumb(this.label, {this.href});

  final String label;
  final String? href;
}

class PageHeader extends StatefulWidget {
  const PageHeader({
    required this.title,
    this.detail,
    this.crumbs = const <Crumb>[],
    this.onRename,
    this.renameLabel = 'Name',
    this.renameValue,
    this.status,
    this.actions = const <AppMenuEntry>[],
    this.viewSwitch,
    this.secondary = const <RowAction>[],
    this.primary,
    this.menuId = 'ui-page-actions',
    super.key,
  });

  final String title;

  /// A muted line under the title: a document's id in Advanced.
  final String? detail;
  final List<Crumb> crumbs;

  /// Saves a new title; returns a problem to show, or null. Null makes the
  /// title plain text.
  final Future<String?> Function(String value)? onRename;

  /// The text box's name while renaming.
  final String renameLabel;

  /// What the text box starts with, when it is not the shown title (a name
  /// with colour codes).
  final String? renameValue;

  /// A badge, or a button that looks like one (it can open the problems).
  final Widget? status;

  /// The page's actions menu; Rename comes first when [onRename] is set.
  final List<AppMenuEntry> actions;

  /// Form and Code (and Split), where a page has them.
  final Widget? viewSwitch;

  /// Page buttons after the menu (Preview on phones).
  final List<RowAction> secondary;

  /// The page's one filled button (New), last.
  final RowAction? primary;
  final String menuId;

  @override
  State<PageHeader> createState() => _PageHeaderState();
}

class _PageHeaderState extends State<PageHeader> {
  static const String _inputId = 'ui-title-input';

  String? _editing;
  String? _problem;

  void _start() {
    setState(() {
      _editing = component.renameValue ?? component.title;
      _problem = null;
    });
    focusWhenShown(_inputId);
  }

  void _cancel() {
    setState(() {
      _editing = null;
      _problem = null;
    });
    focusWhenShown(mainHeadingId);
  }

  Future<void> _save() async {
    final String? value = _editing?.trim();
    if (value == null) return;
    if (value.isEmpty || value == (component.renameValue ?? component.title)) {
      _cancel();
      return;
    }
    final String? problem = await component.onRename!(value);
    if (!mounted) return;
    setState(() {
      _problem = problem;
      if (problem == null) _editing = null;
    });
    if (problem == null) focusWhenShown(mainHeadingId);
  }

  Widget _title() {
    final String? editing = _editing;
    if (editing != null) {
      return Wrap(spacing: 8, runSpacing: 8, crossAxisAlignment: WrapCrossAlignment.center, children: <Widget>[
        dom.span(
          events: <String, void Function(web.Event)>{
            'keydown': (web.Event raw) {
              final web.KeyboardEvent event = raw as web.KeyboardEvent;
              if (event.key == 'Escape') {
                event.preventDefault();
                event.stopPropagation();
                _cancel();
              } else if (event.key == 'Enter') {
                event.preventDefault();
                _save();
              }
            },
          },
          <Widget>[
            TextInput(
              id: _inputId,
              value: editing,
              attributes: <String, String>{'aria-label': component.renameLabel, if (_problem != null) 'aria-invalid': 'true'},
              onChanged: (String value) => setState(() {
                _editing = value;
                _problem = null;
              }),
            ),
          ],
        ),
        ActionRow.footer(
          cancel: RowAction('Cancel', _cancel),
          primary: RowAction('Save', _save),
        ),
        if (_problem != null) problemLine(DiagnosticSeverity.error, _problem!, role: 'alert'),
      ]);
    }
    // A title too long for its line takes a second one before it is cut.
    return dom.h1(
      id: mainHeadingId,
      classes: 'ui-title',
      attributes: const <String, String>{'tabindex': '-1'},
      <Widget>[Text(component.title, maxLines: 2, overflow: TextOverflow.ellipsis)],
    );
  }

  @override
  Widget build(BuildContext context) {
    // While a pairing has ended the page's New is shown, but disabled.
    final RowAction? locked = EditScope.editableOf(context) ? null : component.primary;
    final List<AppMenuEntry> actions = <AppMenuEntry>[
      if (component.onRename != null) AppMenuEntry('Rename', _start),
      ...component.actions,
    ];
    return dom.header(classes: 'ui-page-header', <Widget>[
      Padding(
        padding: const EdgeInsets.only(bottom: 16),
        child: Column(spacing: 6, crossAxisAlignment: CrossAxisAlignment.stretch, mainAxisSize: MainAxisSize.min, children: <Widget>[
          if (component.crumbs.isNotEmpty)
            // A server named by a long MOTD would wrap the trail; its first words do.
            ArcaneBreadcrumbs(items: <BreadcrumbItem>[for (final Crumb crumb in component.crumbs) BreadcrumbItem(label: shortenName(crumb.label, crumbLimit), href: crumb.href)]),
          dom.div(classes: 'ui-page-head-row', <Widget>[
            Row(spacing: 8, style: const ArcaneStyleData(minWidth: '0'), children: <Widget>[
              _title(),
              if (_editing == null) ?component.status,
            ]),
            if (component.viewSwitch != null || actions.isNotEmpty || component.secondary.isNotEmpty || component.primary != null)
              ActionRow(
                label: 'Page actions',
                leading: <Widget>[?component.viewSwitch],
                more: actions,
                moreId: component.menuId,
                secondary: <RowAction>[
                  ...component.secondary,
                  if (locked != null) RowAction(locked.label, null, icon: locked.icon, attributes: locked.attributes),
                ],
                primary: locked == null ? component.primary : null,
              ),
          ]),
          // A long id (a skin's 64-character hash) ends in an ellipsis on phones.
          if (component.detail != null && _editing == null)
            Text.truncated(component.detail!, size: FontSize.sm, color: TextColor.mutedForeground),
        ]),
      ),
    ]);
  }
}
