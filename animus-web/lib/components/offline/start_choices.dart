/// The ways to start: pair a server (first, with the field for its link),
/// try the examples, open a copy of the data folder. On a first visit they
/// are three cards with Blank workspace and Import under them; once there is
/// something to continue every way in is a row with its line.
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
import '../../services/browser.dart';
import '../server/pair_copy.dart';
import '../server/pair_link_form.dart';
import 'page_parts.dart';
import 'start_rows.dart';
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

/// The ways in as rows ([startWays]): under the first visit's cards a blank
/// workspace and Import; on a returning visit every way in, each with its
/// line and labelled buttons. On a phone a row's buttons go under its text.
class StartList extends StatefulWidget {
  const StartList({required this.actions, this.returning = false, super.key});

  final StartActions actions;
  final bool returning;

  @override
  State<StartList> createState() => _StartListState();
}

class _StartListState extends State<StartList> {
  static const String _nameId = 'start-workspace-name';

  bool _blankOpen = false;
  bool _pairOpen = false;
  String _name = '';
  bool _phone = false;
  void Function()? _stopPhone;

  @override
  void initState() {
    super.initState();
    final (bool phone, void Function() stop) = watchMedia('(max-width: 760px)', (bool phone) {
      if (mounted) setState(() => _phone = phone);
    });
    _phone = phone;
    _stopPhone = stop;
  }

  @override
  void dispose() {
    _stopPhone?.call();
    super.dispose();
  }

  void _create() {
    final String name = _name.trim().isEmpty ? 'My workspace' : _name.trim();
    setState(() => _blankOpen = false);
    component.actions.onBlank(name);
  }

  void Function()? _run(String button) {
    final StartActions actions = component.actions;
    if (actions.busy && button != 'Pair a server') return null;
    return switch (button) {
      'Pair a server' => () => setState(() => _pairOpen = true),
      'Open examples' => actions.onExamples,
      'Open folder' => actions.onOpenFolder,
      'Open zip' => actions.onOpenZip,
      'Blank workspace' => () => setState(() => _blankOpen = true),
      _ => actions.import,
    };
  }

  Widget _row(StartWay way) {
    final Widget text = stack(spacing: 2, <Widget>[
      Text(way.title, weight: FontWeight.w500),
      mutedText(way.line),
    ]);
    final Widget buttons = ActionRow.footer(secondary: <RowAction>[for (final String button in way.buttons) RowAction(button, _run(button))]);
    return listItem(
      attributes: <String, String>{'data-way': way.action.name},
      Padding(padding: const EdgeInsets.symmetric(vertical: 10), child: _phone ? stack(spacing: 8, <Widget>[text, buttons]) : splitRow(text, buttons)),
    );
  }

  @override
  Widget build(BuildContext context) {
    final StartActions actions = component.actions;
    return dom.div(<Widget>[
      Card(
        variant: CardVariant.outlined,
        padding: const EdgeInsets.symmetric(horizontal: 16),
        child: rowList(label: component.returning ? 'Start' : 'More ways to start', separated: true, <Widget>[for (final StartWay way in startWays(returning: component.returning)) _row(way)]),
      ),
      AppDialog(
        id: 'start-blank',
        open: _blankOpen,
        title: 'Blank workspace',
        onClose: () => setState(() => _blankOpen = false),
        footer: DialogFooter(cancel: RowAction('Cancel', () => setState(() => _blankOpen = false)), primary: RowAction('Create', actions.busy ? null : _create)),
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
