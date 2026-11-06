/// The command palette (Ctrl+K or Cmd+K, or Commands in the top bar): type
/// to filter, arrows to move, Enter to run, Escape to close.
///
/// arcane_jaspr's ArcaneCommand filters and moves through its list with the
/// arcane runtime script, which this site does not load (the CSP allows no
/// inline script and the app hydrates its own callbacks), so the palette is
/// built here from plain elements with the same roles.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

/// One command: what the list shows, the group it sits under and what runs.
class PaletteCommand {
  const PaletteCommand(this.group, this.label, this.run, {this.hint, this.keywords = const <String>[]});

  final String group;
  final String label;
  final void Function() run;

  /// A short note on the right, such as the shortcut or a document's kind.
  final String? hint;
  final List<String> keywords;
}

/// The commands matching [query]: every word must appear in the label, the
/// hint or a keyword. Order is kept.
List<PaletteCommand> filterCommands(List<PaletteCommand> commands, String query) {
  final List<String> words = query.toLowerCase().split(RegExp(r'\s+')).where((String word) => word.isNotEmpty).toList();
  if (words.isEmpty) return commands;
  return <PaletteCommand>[
    for (final PaletteCommand command in commands)
      if (words.every((String word) =>
          command.label.toLowerCase().contains(word) ||
          (command.hint?.toLowerCase().contains(word) ?? false) ||
          command.keywords.any((String keyword) => keyword.toLowerCase().contains(word))))
        command,
  ];
}

class CommandPalette extends StatefulWidget {
  const CommandPalette({required this.commands, required this.onClose, super.key});

  final List<PaletteCommand> commands;
  final void Function() onClose;

  @override
  State<CommandPalette> createState() => _CommandPaletteState();
}

class _CommandPaletteState extends State<CommandPalette> {
  static const String _inputId = 'animus-palette-input';
  static const String _listId = 'animus-palette-list';
  static const int _shown = 60;

  String _query = '';
  int _active = 0;
  web.HTMLElement? _returnTo;

  @override
  void initState() {
    super.initState();
    final web.Element? active = web.document.activeElement;
    if (active != null && active.isA<web.HTMLElement>()) _returnTo = active as web.HTMLElement;
    web.window.setTimeout((() => (web.document.getElementById(_inputId) as web.HTMLElement?)?.focus()).toJS, 0.toJS);
  }

  void _close() {
    final web.HTMLElement? back = _returnTo;
    component.onClose();
    if (back != null && back.isConnected) back.focus();
  }

  void _run(PaletteCommand command) {
    component.onClose();
    command.run();
  }

  void _keys(web.Event raw, List<PaletteCommand> matches) {
    final web.KeyboardEvent event = raw as web.KeyboardEvent;
    switch (event.key) {
      case 'Escape':
        event.preventDefault();
        event.stopPropagation();
        _close();
      case 'ArrowDown':
        event.preventDefault();
        if (matches.isNotEmpty) setState(() => _active = (_active + 1) % matches.length);
        _reveal();
      case 'ArrowUp':
        event.preventDefault();
        if (matches.isNotEmpty) setState(() => _active = (_active - 1 + matches.length) % matches.length);
        _reveal();
      case 'Enter':
        event.preventDefault();
        if (matches.isNotEmpty) _run(matches[_active.clamp(0, matches.length - 1)]);
      case 'Tab':
        // The search box is the only stop inside the dialog.
        event.preventDefault();
    }
  }

  void _reveal() {
    web.window.setTimeout((() {
      web.document.getElementById('animus-palette-option-$_active')?.scrollIntoView(web.ScrollIntoViewOptions(block: 'nearest'));
    }).toJS, 0.toJS);
  }

  @override
  Widget build(BuildContext context) {
    final List<PaletteCommand> matches = filterCommands(component.commands, _query).take(_shown).toList();
    final int active = matches.isEmpty ? -1 : _active.clamp(0, matches.length - 1);
    final List<Widget> rows = <Widget>[];
    String? group;
    for (int index = 0; index < matches.length; index++) {
      final PaletteCommand command = matches[index];
      if (command.group != group) {
        group = command.group;
        rows.add(dom.li(classes: 'animus-palette-group', attributes: const <String, String>{'role': 'presentation'}, <Widget>[Component.text(group)]));
      }
      rows.add(dom.li(
        id: 'animus-palette-option-$index',
        classes: 'animus-palette-option${index == active ? ' is-active' : ''}',
        attributes: <String, String>{'role': 'option', 'aria-selected': '${index == active}'},
        events: <String, void Function(web.Event)>{
          'click': (web.Event _) => _run(command),
          'pointermove': (web.Event _) {
            if (_active != index) setState(() => _active = index);
          },
        },
        <Widget>[
          dom.span(<Widget>[Component.text(command.label)]),
          if (command.hint != null) dom.span(classes: 'animus-palette-hint', <Widget>[Component.text(command.hint!)]),
        ],
      ));
    }
    return dom.div(classes: 'animus-palette-layer', <Widget>[
      dom.div(
        classes: 'animus-palette-scrim',
        attributes: const <String, String>{'aria-hidden': 'true'},
        events: <String, void Function(web.Event)>{'click': (web.Event _) => _close()},
        const <Widget>[],
      ),
      dom.div(
        classes: 'animus-palette',
        attributes: const <String, String>{'role': 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'animus-palette-title'},
        events: <String, void Function(web.Event)>{'keydown': (web.Event event) => _keys(event, matches)},
        <Widget>[
          const dom.h2(id: 'animus-palette-title', classes: 'animus-sr-only', <Widget>[Component.text('Commands')]),
          const dom.label(attributes: <String, String>{'for': _inputId}, classes: 'animus-sr-only', <Widget>[Component.text('Search commands and documents')]),
          dom.input(
            id: _inputId,
            classes: 'animus-palette-input',
            attributes: <String, String>{
              'type': 'text',
              'role': 'combobox',
              'aria-expanded': 'true',
              'aria-controls': _listId,
              'aria-autocomplete': 'list',
              if (active >= 0) 'aria-activedescendant': 'animus-palette-option-$active',
              'autocomplete': 'off',
              'spellcheck': 'false',
              'placeholder': 'Open a document, switch workspace or run a command',
            },
            value: _query,
            events: <String, void Function(web.Event)>{
              'input': (web.Event event) => setState(() {
                    _query = (event.target! as web.HTMLInputElement).value;
                    _active = 0;
                  }),
            },
          ),
          if (matches.isEmpty)
            dom.p(classes: 'animus-palette-empty', attributes: const <String, String>{'role': 'status'}, <Widget>[
              Component.text('Nothing matches "$_query". Try part of a document id or name, or a word like export or theme.'),
            ])
          else
            dom.ul(id: _listId, classes: 'animus-palette-list', attributes: const <String, String>{'role': 'listbox', 'aria-label': 'Commands'}, rows),
          const dom.p(classes: 'animus-palette-keys', attributes: <String, String>{'aria-hidden': 'true'}, <Widget>[
            Component.text('Up and Down to move, Enter to run, Esc to close'),
          ]),
        ],
      ),
    ]);
  }
}
