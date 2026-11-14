/// Home or a list that holds nothing Simple shows, only documents Advanced
/// lists: how many, and Switch to Advanced landing on them.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../services/browser.dart' show navigate;
import '../state/workspace_route.dart';
import '../ui/editor_mode.dart';
import '../ui/empty_state.dart';
import '../ui/mode.dart';
import 'advanced_notice.dart';

/// [count] documents in Advanced, and Switch to Advanced landing on
/// [landing].
class InAdvancedState extends StatelessWidget {
  const InAdvancedState({required this.count, this.landing, super.key});

  final int count;
  final EditorRoute? landing;

  @override
  Widget build(BuildContext context) => EmptyState(
        message: hiddenDocumentsText(count),
        action: Button.primary(
          label: 'Switch to Advanced',
          attributes: const <String, String>{'data-testid': 'switch-to-advanced'},
          onPressed: () {
            ModeScope.maybeOf(context)?.onChanged(EditorMode.advanced);
            if (landing case final EditorRoute route) navigate(route.format());
          },
        ),
      );
}
