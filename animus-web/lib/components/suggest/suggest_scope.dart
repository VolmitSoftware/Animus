/// Hands completion sources down the tree: the document pane provides the
/// workspace, registry and server lists, and the behavior editor adds the
/// names its expressions can read. Fields read it through [SuggestScope.of].
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/jaspr.dart' show InheritedComponent;

import '../../logic/suggest/completion_sources.dart';
import '../../logic/suggest/field_profile.dart';

class SuggestScope extends InheritedComponent {
  const SuggestScope({required this.sources, required super.child, this.behavior, super.key});

  final CompletionSources sources;

  /// The behavior being edited, when there is one.
  final BehaviorNames? behavior;

  static SuggestScope? _find(BuildContext context) => context.dependOnInheritedComponentOfExactType<SuggestScope>();

  /// The env for a field under [context]; [behavior] overrides the scope's.
  static CompletionEnv of(BuildContext context, {BehaviorNames? behavior}) {
    final SuggestScope? scope = _find(context);
    return CompletionEnv(sources: scope?.sources ?? CompletionSources.empty, behavior: behavior ?? scope?.behavior);
  }

  /// The sources alone, for editors that build their own env.
  static CompletionSources sourcesOf(BuildContext context) => _find(context)?.sources ?? CompletionSources.empty;

  /// [child] under this scope with [behavior]'s names added.
  static Widget withBehavior(BuildContext context, BehaviorNames behavior, Widget child) =>
      SuggestScope(sources: sourcesOf(context), behavior: behavior, child: child);

  @override
  bool updateShouldNotify(SuggestScope oldComponent) => !identical(oldComponent.sources, sources) || !identical(oldComponent.behavior, behavior);
}
