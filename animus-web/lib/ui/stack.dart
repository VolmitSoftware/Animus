/// Plain page structure on the library's own primitives: children stacked
/// full width, and a muted line of text.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

/// [children] one under another, each as wide as the stack, [spacing] px
/// apart.
Widget stack(List<Widget> children, {double spacing = 8, Key? key}) =>
    Column(key: key, crossAxisAlignment: CrossAxisAlignment.stretch, mainAxisSize: MainAxisSize.min, spacing: spacing, children: children);

/// A count, a hint, an empty list or the line under a heading; [small] for
/// a detail.
Widget mutedText(String text, {bool small = false}) => Text(text, size: small ? FontSize.xs : FontSize.sm, color: TextColor.muted);
