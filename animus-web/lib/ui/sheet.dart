/// A side sheet on shadcn's sheet: the Review panel from the right (full
/// screen on phones), the nav drawer from the left (280 px).
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import 'action_row.dart';
import 'modal_focus.dart';

int _nextSheetId = 0;

class Sheet extends StatefulWidget {
  const Sheet({required this.open, required this.title, required this.onClose, required this.child, this.footer, this.fromStart = false, this.id, super.key});

  final bool open;
  final String title;
  final void Function() onClose;
  final Widget child;

  /// The buttons at the bottom of the sheet.
  final DialogFooter? footer;

  /// From the left (the nav drawer) instead of the right.
  final bool fromStart;
  final String? id;

  @override
  State<Sheet> createState() => _SheetState();
}

class _SheetState extends State<Sheet> with ModalFocus<Sheet> {
  late final String _id = component.id ?? 'ui-sheet-${_nextSheetId++}';

  @override
  String get modalId => _id;

  @override
  bool get modalOpen => component.open;

  @override
  void modalClose() => component.onClose();

  @override
  bool get focusHeading => true;

  @override
  void didUpdateWidget(Sheet oldComponent) {
    super.didUpdateWidget(oldComponent);
    followOpen(oldComponent.open);
  }

  @override
  Widget build(BuildContext context) => dom.div(id: _id, classes: 'ui-sheet${component.fromStart ? ' is-start' : ''}', <Widget>[
        ArcaneSheet(
          isOpen: component.open,
          position: component.fromStart ? SheetPosition.start : SheetPosition.end,
          // The nav drawer is a 280 px panel over the dimmed page; Review is
          // 400 px, the whole screen on phones.
          size: component.fromStart ? SheetSize.sm : SheetSize.md,
          title: component.title,
          onClose: component.onClose,
          footer: component.footer,
          showDragHandle: false,
          child: dom.div(classes: 'ui-form-box', <Widget>[component.child]),
        ),
      ]);
}
