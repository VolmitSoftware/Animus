/// The sidebar's collapsible sections: which are collapsed (kept per
/// workspace), and the folder tree of the NPC section.
library;

import 'dart:convert';

/// The key of a document kind's section.
String sectionKey(String wireName) => 'kind:$wireName';

/// The key of a folder inside a kind's section.
String folderKey(String wireName, String path) => 'folder:$wireName:$path';

/// The localStorage key of [workspaceId]'s collapsed sections.
String collapsedStorageKey(String workspaceId) => 'animus.sidebar.collapsed.$workspaceId';

/// The collapsed keys stored as [stored]; unreadable data collapses nothing.
Set<String> decodeCollapsed(String? stored) {
  if (stored == null) return <String>{};
  try {
    final Object? decoded = jsonDecode(stored);
    return <String>{
      for (final Object? key in decoded is List<Object?> ? decoded : const <Object?>[])
        if (key is String) key,
    };
  } on FormatException {
    return <String>{};
  }
}

String encodeCollapsed(Set<String> keys) => jsonEncode(keys.toList()..sort());

/// One entry of a section's tree: a document or a folder of them.
sealed class TreeNode<T> {
  const TreeNode();
}

class TreeLeaf<T> extends TreeNode<T> {
  const TreeLeaf(this.item);

  final T item;
}

class TreeFolder<T> extends TreeNode<T> {
  TreeFolder(this.path);

  /// The folder's path from the section root, such as `town/market`.
  final String path;
  final List<TreeNode<T>> children = <TreeNode<T>>[];

  String get name => path.substring(path.lastIndexOf('/') + 1);

  /// Documents in this folder and every folder under it.
  int get count => children.fold(0, (int total, TreeNode<T> child) => total + (child is TreeFolder<T> ? child.count : 1));
}

/// [items] as a tree by the folder part of each [idOf] (everything before
/// the last `/`). Folders and documents keep the order they first appear
/// in, so a sorted list reads the same top to bottom as before.
List<TreeNode<T>> folderTree<T>(List<T> items, String Function(T item) idOf) {
  final List<TreeNode<T>> roots = <TreeNode<T>>[];
  final Map<String, TreeFolder<T>> folders = <String, TreeFolder<T>>{};
  TreeFolder<T> folderAt(String path) {
    final TreeFolder<T>? known = folders[path];
    if (known != null) return known;
    final TreeFolder<T> made = TreeFolder<T>(path);
    folders[path] = made;
    final int slash = path.lastIndexOf('/');
    (slash < 0 ? roots : folderAt(path.substring(0, slash)).children).add(made);
    return made;
  }

  for (final T item in items) {
    final String id = idOf(item);
    final int slash = id.lastIndexOf('/');
    (slash <= 0 ? roots : folderAt(id.substring(0, slash)).children).add(TreeLeaf<T>(item));
  }
  return roots;
}
