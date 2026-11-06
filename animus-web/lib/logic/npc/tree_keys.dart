/// The keyboard model of a one-level tree (a root with its children, as the
/// NPC browser's folder tree): Up and Down step, Home and End jump, Left
/// returns to the root at index 0 and Right enters the first child.
library;

/// The item to focus after [key] from [current] of [count] items, or null
/// when the key does not move focus.
int? treeMove(String key, int current, int count) {
  if (count <= 0) return null;
  final int last = count - 1;
  return switch (key) {
    'ArrowDown' => current < last ? current + 1 : last,
    'ArrowUp' => current > 0 ? current - 1 : 0,
    'Home' => 0,
    'End' => last,
    'ArrowLeft' => 0,
    'ArrowRight' => current == 0 && count > 1 ? 1 : current,
    _ => null,
  };
}
