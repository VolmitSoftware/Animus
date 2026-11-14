/// Whether pages may start new work. A server whose pairing ended keeps its
/// changes but cannot take new ones until it is paired again, so page
/// headers turn their primary action into a disabled outline button and
/// the save bar's Pair again stays the one primary.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

class EditScope extends InheritedWidget {
  const EditScope({required this.editable, required super.child, super.key});

  final bool editable;

  static bool editableOf(BuildContext context) => context.dependOnInheritedComponentOfExactType<EditScope>()?.editable ?? true;

  @override
  bool updateShouldNotify(EditScope oldComponent) => oldComponent.editable != editable;
}
