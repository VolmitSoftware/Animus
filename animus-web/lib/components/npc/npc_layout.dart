/// The layout of the NPC, trait group, pack, combat, rig and movement forms
/// beside the kit's `stack` and the GUI forms' `gui_stack.dart`: a tab's
/// parts and a group's fields as grids (a flex column would take the
/// inputs' row flex basis as their height), named controls, wrapping lines,
/// headings with an id, plain lists and legends.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../ui/stack.dart';

/// A tab's parts, one under another.
Widget tabStack(List<Widget> children, {Key? key}) => dom.div(key: key, styles: stackStyles(gap: Gap.md), children);

/// The fields of one group, closer together.
Widget fieldStack(List<Widget> children, {Key? key}) => dom.div(key: key, styles: stackStyles(), children);

/// One column of the full width. A grid, not a flex column: the editor's
/// inputs carry a flex basis for rows (16rem) that a flex column would take
/// as their height.
ArcaneStyleData _stack(Gap gap) => ArcaneStyleData(display: Display.grid, gridColumnsCustom: 'minmax(0, 1fr)', gap: gap, minWidth: '0');

/// The stacks' layout for an element that keeps its own tag, class or
/// attributes (a section, a list, a test hook). Built once per gap: a
/// keystroke rebuilds the whole tab.
dom.Styles stackStyles({Gap gap = Gap.sm}) => _stacks[gap] ??= _stack(gap).toStyles();

final Map<Gap, dom.Styles> _stacks = <Gap, dom.Styles>{};

/// One tab of the NPC form, its parts one under another; the class is the
/// tests' hook.
Widget tabBody(List<Widget> children) => dom.div(classes: 'animus-npc-tab-body', styles: stackStyles(), children);

/// A control named above it in small muted text (an axis of x, y and z, a
/// field of a numbered place); with [forId] the name is its `<label>`.
Widget namedAbove(String name, Widget control, {String? forId}) => dom.div(styles: stackStyles(gap: Gap.xs), <Widget>[
      if (forId == null) mutedText(name) else dom.label(htmlFor: forId, <Widget>[mutedText(name)]),
      control,
    ]);

/// Controls side by side that wrap onto more lines where the box is narrow,
/// each at least [basis] wide and sharing what is left.
Widget wrapLine(List<Widget> children, {String basis = '5.5rem', Gap spacing = Gap.sm}) => Wrap(spacing: spacing.value, runSpacing: Gap.sm.value, crossAxisAlignment: WrapCrossAlignment.end, children: <Widget>[
      for (final Widget child in children) wrapItem('1 1 $basis', child),
    ]);

/// A box in a row or wrapping line with the CSS [flex] it takes (grow,
/// shrink, basis), never narrower than [minWidth].
Widget wrapItem(String flex, Widget child, {String minWidth = '0'}) =>
    dom.div(styles: _items['$flex|$minWidth'] ??= ArcaneStyleData(flexCustom: flex, minWidth: minWidth).toStyles(), <Widget>[child]);

final Map<String, dom.Styles> _items = <String, dom.Styles>{};

/// [mutedText] as its own paragraph.
Widget mutedLine(String text) => Text(text, element: 'p', size: FontSize.sm, color: TextColor.muted, cssStyle: const ArcaneStyleData(margin: MarginPreset.none));

/// [groupHeading]'s look for a heading element of its own (with an id, a tip).
const dom.Styles groupHeadingStyles = dom.Styles(raw: <String, String>{'margin': '0', 'font-size': '0.875rem', 'font-weight': '600'});

/// A list without bullets or indent, its items one under another.
const dom.Styles plainListStyles = dom.Styles(raw: <String, String>{'display': 'grid', 'gap': '0.25rem', 'margin': '0', 'padding': '0', 'list-style': 'none'});

/// Small muted lines one under another: a map's legend, its notes.
final dom.Styles legendStyles = const ArcaneStyleData(display: Display.grid, gap: Gap.xs, fontSize: FontSize.xs, textColor: TextColor.muted).toStyles();

/// A value as code text that wraps anywhere (ids, UUIDs, JSON).
Widget npcCode(String text) => Text(text, element: 'code', cssStyle: const ArcaneStyleData(wordBreak: WordBreak.breakWord));
