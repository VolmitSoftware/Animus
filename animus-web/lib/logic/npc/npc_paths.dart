/// Where NPC definition files may live, as the server's `NpcPaths` says:
/// `npcs/<id>.json` directly, or under up to [npcMaxFolders] folders. Every
/// folder and the file name are one segment of 1-64 lowercase letters,
/// digits, `_` or `-`, starting with a letter or digit. The folder path is the
/// NPC's `group` (empty for `npcs/` itself) and the file name is its id.
///
/// A workspace NPC id is the path under `npcs/` without `.json`, such as
/// `town/market/baker` or `baker`.
library;

/// One folder or file name.
final RegExp npcSegmentPattern = RegExp(r'^[a-z0-9][a-z0-9_-]{0,63}$');

/// The most folders an NPC file may sit in below `npcs/`.
const int npcMaxFolders = 8;

/// A non-empty folder path below `npcs/`.
final RegExp npcFolderPattern = RegExp(r'^[a-z0-9][a-z0-9_-]{0,63}(?:/[a-z0-9][a-z0-9_-]{0,63}){0,7}$');

/// The folder of a workspace NPC id: empty for `npcs/` itself.
String npcFolderOf(String id) {
  final int slash = id.lastIndexOf('/');
  return slash < 0 ? '' : id.substring(0, slash);
}

/// The file name of a workspace NPC id, which is the NPC's `id` field.
String npcLocalIdOf(String id) {
  final int slash = id.lastIndexOf('/');
  return slash < 0 ? id : id.substring(slash + 1);
}

/// The workspace id of NPC [localId] in [folder].
String npcIdIn(String folder, String localId) => folder.isEmpty ? localId : '$folder/$localId';

/// The last segment of [folder], or `npcs` for the root.
String npcFolderName(String folder) => folder.isEmpty ? 'npcs' : folder.substring(folder.lastIndexOf('/') + 1);

/// The folder above [folder]; empty for a top-level folder and the root.
String npcParentFolder(String folder) => npcFolderOf(folder);

/// Whether [folder] is [ancestor] or sits below it. Every folder is inside
/// the root.
bool npcFolderWithin(String folder, String ancestor) => ancestor.isEmpty || folder == ancestor || folder.startsWith('$ancestor/');

/// The server's refusal of a `group` that is not an NPC folder
/// (`NpcDefinition`), word for word.
const String npcFolderRule = 'expected the folder under npcs/: empty, or up to $npcMaxFolders names of 1–64 lowercase letters, digits, _ or - joined by /';

/// Why [folder] cannot hold NPCs, in the server's words, or null when it
/// can. The empty folder is `npcs/` itself.
String? npcFolderProblem(String folder) {
  if (folder.isEmpty || npcFolderPattern.hasMatch(folder)) return null;
  return 'npcs/$folder/: $npcFolderRule.';
}

/// [folder] typed by a person: trimmed, lowercased, without a leading
/// `npcs/` or surrounding slashes.
String normalizeNpcFolder(String folder) {
  String text = folder.trim().toLowerCase();
  if (text == 'npcs' || text == 'npcs/') return '';
  if (text.startsWith('npcs/')) text = text.substring(5);
  while (text.startsWith('/')) {
    text = text.substring(1);
  }
  while (text.endsWith('/')) {
    text = text.substring(0, text.length - 1);
  }
  return text;
}

/// Orders folders as the tree lists them: segment by segment, a folder
/// before the folders inside it.
int compareNpcFolders(String a, String b) {
  if (a == b) return 0;
  if (a.isEmpty) return -1;
  if (b.isEmpty) return 1;
  final List<String> left = a.split('/');
  final List<String> right = b.split('/');
  for (int index = 0; index < left.length && index < right.length; index++) {
    final int order = left[index].compareTo(right[index]);
    if (order != 0) return order;
  }
  return left.length.compareTo(right.length);
}
