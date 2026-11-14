/// Plain parts the start, Home, Review and Import pages share, built on the
/// library's type and layout: a section heading, a muted line, and a list of
/// rows without bullets.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:arcane_jaspr/html.dart' show ArcaneHeading, HeadingLevel;
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../ui/info_tip.dart';
import '../../ui/stack.dart';

const ArcaneStyleData _headingStyle = ArcaneStyleData(fontSize: FontSize.base, fontWeight: FontWeight.w600);

/// A section's heading, with its (i) tip when given.
Widget sectionHeading(String text, {String? id, String? tip, DocLink? docs, HeadingLevel level = HeadingLevel.h2}) => ArcaneHeading(
      level: level,
      id: id,
      styles: _headingStyle,
      child: tip == null ? Component.text(text) : TipLabel(label: Component.text(text), tip: tip, docs: docs),
    );

/// One row of a [rowList]; [classes] and [attributes] are its hooks.
Widget listItem(Widget child, {String? classes, Map<String, String> attributes = const <String, String>{}}) =>
    dom.div(classes: classes, attributes: <String, String>{'role': 'listitem', ...attributes}, <Widget>[child]);

/// Rows of [listItem]s without bullets; [separated] draws a line between
/// them, otherwise they sit [spacing] apart.
Widget rowList(List<Widget> items, {String? label, String? classes, Map<String, String> attributes = const <String, String>{}, bool separated = false, double spacing = 4}) => dom.div(
      classes: classes,
      attributes: <String, String>{'role': 'list', 'aria-label': ?label, ...attributes},
      <Widget>[
        stack(spacing: separated ? 0 : spacing, <Widget>[
          for (int index = 0; index < items.length; index++) ...<Widget>[
            if (separated && index > 0) const ArcaneSeparator(),
            items[index],
          ],
        ]),
      ],
    );

/// A row of a list: [main] takes the room, [end] keeps its width.
Widget splitRow(Widget main, Widget? end, {double spacing = 16}) => Row(
      spacing: spacing,
      children: <Widget>[Expanded(child: main), ?end],
    );
