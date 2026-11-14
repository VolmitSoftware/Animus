/// The bar at the bottom of every workspace page: one line of state and the
/// buttons it needs, the primary one last, on the kit's [ActionRow]. Always
/// on screen and always one row: the state text shortens first, then the
/// secondary buttons (Review) drop to icons; the primary (Apply) stays.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import 'action_row.dart';
import 'save_state.dart';

ArcaneGlyph _icon(SaveAction action) => switch (action) {
      SaveAction.apply => ArcaneIcon.upload(),
      SaveAction.review => ArcaneIcon.listChecks(),
      SaveAction.retry => ArcaneIcon.rotateCw(),
      SaveAction.reconnect => ArcaneIcon.plug(),
      SaveAction.saveAgain => ArcaneIcon.save(),
      SaveAction.exportZip => ArcaneIcon.download(),
      SaveAction.sendToServer => ArcaneIcon.send(),
      SaveAction.undoApply => ArcaneIcon.undo(),
      SaveAction.takeOver => ArcaneIcon.arrowRightLeft(),
      SaveAction.retryLoads || SaveAction.retryCatalog => ArcaneIcon.rotateCw(),
      SaveAction.pairAgain => ArcaneIcon.link(),
    };

class SaveBar extends StatefulWidget {
  const SaveBar({required this.state, required this.onAction, this.busyAction, super.key});

  final SaveBarState state;
  final void Function(SaveAction action) onAction;

  /// An action whose work is running; its button shows a spinner.
  final SaveAction? busyAction;

  @override
  State<SaveBar> createState() => _SaveBarState();
}

class _SaveBarState extends State<SaveBar> {
  /// The frame builds again on every keystroke; the bar only when what it
  /// shows changes. Its buttons call the newest [SaveBar.onAction].
  @override
  bool shouldRebuild(SaveBar newComponent) => newComponent.state != component.state || newComponent.busyAction != component.busyAction;

  RowAction _action(SaveAction action, {required bool primary}) => RowAction(
        action.label,
        () => component.onAction(action),
        icon: primary ? null : _icon(action),
        loading: component.busyAction == action,
        attributes: <String, String>{'data-save-action': action.name},
      );

  @override
  Widget build(BuildContext context) {
    final SaveBarState state = component.state;
    return dom.footer(
      classes: 'ui-save-bar is-${state.tone.name}',
      attributes: const <String, String>{'aria-label': 'Save'},
      <Widget>[
        ActionRow(
          leading: <Widget>[
            dom.p(
              classes: 'ui-save-state',
              attributes: <String, String>{'role': state.tone == SaveTone.failed ? 'alert' : 'status', 'data-tone': state.tone.name},
              <Widget>[
                const dom.span(classes: 'ui-save-dot', attributes: <String, String>{'aria-hidden': 'true'}, <Widget>[]),
                dom.span(classes: 'ui-save-message', <Widget>[Component.text(state.message)]),
              ],
            ),
          ],
          secondary: <RowAction>[for (final SaveAction action in state.secondary) _action(action, primary: false)],
          primary: state.primary == null ? null : _action(state.primary!, primary: true),
        ),
      ],
    );
  }
}
