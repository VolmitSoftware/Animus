/// The Mode plumbing: [ModeScope] hands the current [EditorMode] down the
/// tree, [AdvancedOnly] shows its child in Advanced only, and [MoreFields]
/// keeps rare fields behind a "More" disclosure in both modes. Hidden never
/// means lost: forms write JSON paths, so keys they do not show round-trip.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/jaspr.dart' show Component, InheritedComponent;
import 'package:web/web.dart' as web;

import 'editor_mode.dart';
import 'section.dart';

/// The mode kept in this browser, Simple when none is (or storage is off).
EditorMode loadMode() {
  try {
    return parseMode(web.window.localStorage.getItem(modeStorageKey));
  } on Object {
    return EditorMode.simple;
  }
}

/// Keeps [mode] for the next visit; blocked storage keeps it for this page.
void saveMode(EditorMode mode) {
  try {
    web.window.localStorage.setItem(modeStorageKey, mode.name);
  } on Object {
    // Private mode or storage switched off.
  }
}

class ModeScope extends InheritedComponent {
  const ModeScope({required this.mode, required this.onChanged, required super.child, super.key});

  final EditorMode mode;

  /// Switches the whole editor (the Mode toggle calls it).
  final void Function(EditorMode mode) onChanged;

  static ModeScope? maybeOf(BuildContext context) => context.dependOnInheritedComponentOfExactType<ModeScope>();

  /// The current mode; Simple outside a scope.
  static EditorMode of(BuildContext context) => maybeOf(context)?.mode ?? EditorMode.simple;

  static bool advanced(BuildContext context) => of(context) == EditorMode.advanced;

  @override
  bool updateShouldNotify(ModeScope oldComponent) => oldComponent.mode != mode;
}

/// [child] in Advanced, [simple] (or nothing) in Simple.
class AdvancedOnly extends StatelessWidget {
  const AdvancedOnly({required this.child, this.simple, super.key});

  final Widget child;
  final Widget? simple;

  @override
  Widget build(BuildContext context) => ModeScope.advanced(context) ? child : (simple ?? const Component.fragment(<Widget>[]));
}

/// Rare fields behind one "More" disclosure at the end of a tab, in both
/// modes. Its open state is its own, so a rebuild keeps it as left.
class MoreFields extends StatelessWidget {
  const MoreFields({required this.children, this.label = 'More', this.open = false, super.key});

  final List<Widget> children;
  final String label;
  final bool open;

  @override
  Widget build(BuildContext context) => AppSection(title: label, open: open, classes: 'ui-more', children: children);
}
