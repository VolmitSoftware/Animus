/// The ways to start: pair a server (first, with the field for its link),
/// try the examples, open a copy of the data folder. On a first visit they
/// are three cards with Blank workspace and Import under them; once there is
/// something to continue they fold into one row of buttons.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../state/workspace_route.dart';
import '../../ui/action_row.dart';
import '../../ui/dialog.dart';
import '../../ui/field.dart';
import '../../ui/info_tip.dart';
import '../../ui/menu.dart';
import '../../ui/mode.dart';
import '../server/pair_copy.dart';
import '../server/pair_link_form.dart';
import 'page_parts.dart';
import '../../ui/stack.dart';

/// What the start choices do; the start page owns the work.
class StartActions {
  const StartActions({required this.busy, required this.onExamples, required this.onOpenFolder, required this.onOpenZip, required this.onBlank, required this.onNavigate});

  final bool busy;
  final void Function() onExamples;
  final void Function() onOpenFolder;
  final void Function() onOpenZip;

  /// Makes an empty workspace with this name.
  final void Function(String name) onBlank;
  final void Function(EditorRoute route) onNavigate;

  void pair(String code) => onNavigate(PairRoute(code));
  void import() => onNavigate(const CitizensRoute());
}

/// The in-game step, one line, with the menu button in its tip.
const String pairStep = 'Run $pairCommand in game, then open the link or paste it here.';
const String _pairTip = 'The Web editor button in the /animus menu does the same. A link works once, for 10 minutes.';

/// First visit: three cards, the pairing one first.
class StartCards extends StatelessWidget {
  const StartCards({required this.actions, super.key});

  final StartActions actions;

  /// A library card: its text on top, its [footer] at the bottom, so cards
  /// that share a row line their buttons up.
  static Widget _card(String testId, List<Widget> text, Widget footer) => Card(
    classes: 'ui-start-card',
    attributes: <String, String>{'data-testid': testId},
    children: <Widget>[
      Column(mainAxisAlignment: MainAxisAlignment.spaceBetween, crossAxisAlignment: CrossAxisAlignment.stretch, spacing: 12, children: <Widget>[stack(spacing: 12, text), footer]),
    ],
  );

  @override
  Widget build(BuildContext context) => stack(spacing: 16, <Widget>[
    _card('start-pair', <Widget>[
      sectionHeading('Pair a server', tip: _pairTip, docs: Docs.pairing),
      mutedText(pairStep),
    ], PairLinkForm(id: 'start-pair-link', onCode: actions.pair)),
    // Pairing spans the row as the main way in; the other two share the next.
    Carpet(minItemWidth: 280, children: <Widget>[
      _card('start-examples', <Widget>[
        sectionHeading('Try the examples'),
        mutedText('Example NPCs, shops and behaviors to change. Nothing reaches a server.'),
      ], FormFooter(secondary: <RowAction>[RowAction('Open examples', actions.busy ? null : actions.onExamples)])),
      _card('start-files', <Widget>[
        sectionHeading(
          'Open my files',
          tip: 'A copy of plugins/Animus (config/animus on mod servers). Edits stay in this browser until you export or send them.',
          docs: Docs.editor,
        ),
        mutedText('A copy of your Animus folder, as a folder or a zip.'),
      ], FormFooter(secondary: <RowAction>[RowAction('Open zip', actions.busy ? null : actions.onOpenZip), RowAction('Open folder', actions.busy ? null : actions.onOpenFolder)])),
    ]),
  ]);
}

/// The other ways in, under the cards: a blank workspace and Import.
class StartMore extends StatefulWidget {
  const StartMore({required this.actions, this.compact = false, this.heading, super.key});

  final StartActions actions;

  /// One row for every way in, for a returning visit.
  final bool compact;

  /// The section heading at the start of that row.
  final Widget? heading;

  @override
  State<StartMore> createState() => _StartMoreState();
}

class _StartMoreState extends State<StartMore> {
  static const String _nameId = 'start-workspace-name';

  bool _blankOpen = false;
  bool _pairOpen = false;
  String _name = '';

  void _create() {
    final String name = _name.trim().isEmpty ? 'My workspace' : _name.trim();
    setState(() => _blankOpen = false);
    component.actions.onBlank(name);
  }

  @override
  Widget build(BuildContext context) {
    final StartActions actions = component.actions;
    final bool busy = actions.busy;
    final bool advanced = ModeScope.advanced(context);
    final RowAction blank = RowAction('Blank workspace', busy ? null : () => setState(() => _blankOpen = true));
    final RowAction import = RowAction('Import', busy ? null : actions.import);
    return dom.div(<Widget>[
      if (component.compact)
        // The heading and every way in on one line; what does not fit folds
        // into the "..." menu.
        ActionRow(
          label: 'Start',
          moreId: 'start-more',
          leading: <Widget>[?component.heading],
          more: <AppMenuEntry>[
            AppMenuEntry('Open zip', busy ? null : actions.onOpenZip),
            if (advanced) ...<AppMenuEntry>[
              const AppMenuEntry.separator(),
              AppMenuEntry('Blank workspace', blank.onPressed),
              AppMenuEntry('Import', import.onPressed),
            ],
          ],
          secondary: <RowAction>[
            RowAction('Open examples', busy ? null : actions.onExamples),
            RowAction('Open folder', busy ? null : actions.onOpenFolder),
            RowAction('Pair a server', () => setState(() => _pairOpen = true)),
          ],
        )
      // Importing and blank workspaces are Advanced; Simple starts from files, examples or a server.
      else if (advanced)
        ActionRow(label: 'Other ways to start', secondary: <RowAction>[import, blank]),
      AppDialog(
        id: 'start-blank',
        open: _blankOpen,
        title: 'Blank workspace',
        onClose: () => setState(() => _blankOpen = false),
        footer: DialogFooter(cancel: RowAction('Cancel', () => setState(() => _blankOpen = false)), primary: RowAction('Create', busy ? null : _create)),
        children: <Widget>[
          Field(
            label: 'Name',
            forId: _nameId,
            child: TextInput(id: _nameId, value: _name, placeholder: 'My workspace', fullWidth: true, onChanged: (String value) => setState(() => _name = value), onSubmitted: (String _) => _create()),
          ),
        ],
      ),
      AppDialog(
        id: 'start-pair',
        open: _pairOpen,
        title: 'Pair a server',
        onClose: () => setState(() => _pairOpen = false),
        children: <Widget>[
          const dom.p(<Widget>[Component.text(pairStep), InfoButton(tip: _pairTip, docs: Docs.pairing)]),
          if (_pairOpen) PairLinkForm(id: 'start-pair-dialog-link', onCode: actions.pair),
        ],
      ),
    ]);
  }
}
