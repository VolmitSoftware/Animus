/// A line-for-line copy of core's `BehaviorLimits`: the behavior compiler's size limits checked on a generated
/// document. An omitted `else` counts as an empty sequence, as the compiler counts it.
library;

import '../citizens/java_json.dart';

const int behaviorMaximumRoutines = 32;
const int behaviorMaximumTriggers = 32;
const int _maximumBytes = 256 * 1024;
const int _maximumNodes = 2048;
const int _maximumDepth = 32;
const int _maximumSteps = 128;

String? behaviorLimitProblem(JObject document, String text) {
  if (javaUtf8(text).length > _maximumBytes) return 'exceeds 256 KiB';
  final JObject routines = document.object('routines')!;
  if (routines.length > behaviorMaximumRoutines) return 'has more than 32 routines';
  final JElement? triggers = document.get('triggers');
  if (triggers is JArray && triggers.length > behaviorMaximumTriggers) return 'has more than 32 triggers';
  final List<int> nodes = <int>[0];
  for (final JElement routine in routines.members.values) {
    final String? problem = _node(routine as JObject, 0, nodes);
    if (problem != null) return problem;
  }
  return null;
}

String? _node(JObject node, int depth, List<int> nodes) {
  if (depth > _maximumDepth) return 'nests deeper than 32';
  if (++nodes[0] > _maximumNodes) return 'has more than 2048 steps';
  final String type = node.get('type')!.asPrimitive.asString;
  if (type == 'sequence') {
    final JArray steps = node.get('steps')! as JArray;
    if (steps.length > _maximumSteps) return 'has a sequence longer than 128 steps';
    for (final JElement step in steps.elements) {
      final String? problem = _node(step as JObject, depth + 1, nodes);
      if (problem != null) return problem;
    }
  } else if (type == 'condition') {
    final String? then = _node(node.object('then')!, depth + 1, nodes);
    if (then != null) return then;
    if (node.has('else')) return _node(node.object('else')!, depth + 1, nodes);
    if (++nodes[0] > _maximumNodes) return 'has more than 2048 steps';
  } else if (type == 'repeat') {
    return _node(node.object('body')!, depth + 1, nodes);
  }
  return null;
}
