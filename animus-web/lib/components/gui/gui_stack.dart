/// The layout of the GUI, shop, hologram, menu, text animation and quest
/// forms, on the library's flex primitives and spacing: a tab's parts and a
/// group's fields one under another, a field with the controls after it,
/// equal tracks, x, y and z boxes named by their letters, wrapping
/// switches, and the muted line for counts, hints and empty lists.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../ui/number_input.dart';
import '../../ui/stack.dart';

/// A field and the controls after it: the first takes the room left.
Widget fieldLine(List<Widget> children) => Row(gapSize: Gap.sm, children: <Widget>[
      if (children.isNotEmpty) Expanded(child: children.first),
      ...children.skip(1),
    ]);

/// Controls sharing the line as tracks: equal ones (x, y and z), or in
/// the proportions of [flex].
Widget equalLine(List<Widget> children, {List<int> flex = const <int>[]}) => Row(gapSize: Gap.sm, children: <Widget>[
      for (int index = 0; index < children.length; index++) Expanded(flex: index < flex.length ? flex[index] : 1, child: children[index]),
    ]);

/// Three numbers on equal tracks, each with its axis letter above it, so
/// a box reads as x, y or z with a value in it too. [label] names each box
/// for assistive technology ("Opens at x"); [id] prefixes their ids.
Widget axisLine({required String label, required String id, required List<num?> values, required void Function(int axis, num? value) onChanged}) => equalLine(<Widget>[
      for (int axis = 0; axis < values.length && axis < 3; axis++)
        stack(spacing: 4, <Widget>[
          dom.label(htmlFor: '$id-${'xyz'[axis]}', <Widget>[mutedText('XYZ'[axis])]),
          NumberInput(id: '$id-${'xyz'[axis]}', value: values[axis], integer: false, label: '$label ${'xyz'[axis]}', onChanged: (num? value) => onChanged(axis, value)),
        ]),
    ]);

/// Switches side by side, wrapping where the form is narrow.
Widget switchRow(List<Widget> children) =>
    Wrap(spacing: Gap.md.value, runSpacing: Gap.sm.value, crossAxisAlignment: WrapCrossAlignment.center, children: children);

/// A text that ends in "..." where its track is too narrow; in a row it
/// takes the room left.
Widget truncatedText(String text, {bool muted = false}) => Expanded(child: blockTruncated(text, muted: muted));

/// One line that ends in "...", as a block so it sits on its row's centre
/// line (the library's inline-block adds a descender gap under it).
Widget blockTruncated(String text, {bool muted = false}) => Text.truncated(
      text,
      size: muted ? FontSize.sm : null,
      color: muted ? TextColor.muted : null,
      cssStyle: const ArcaneStyleData(display: Display.block),
    );

/// A group's heading inside a tab, with badges after it.
Widget groupHeading(String text, {List<Widget> after = const <Widget>[]}) => Row(gapSize: Gap.sm, children: <Widget>[
      Text(text, element: 'h3', size: FontSize.sm, weight: FontWeight.w600, cssStyle: const ArcaneStyleData(margin: MarginPreset.none)),
      ...after,
    ]);

/// What a preview frame holds around a game window: the window and a
/// legend, a gap apart and inset from the frame's edge.
Widget previewPane(List<Widget> children) => Padding(padding: EdgeInsets.all(Gap.md.value), child: stack(children, spacing: 16));
