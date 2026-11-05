/// Holograms and holographic menus as stage scenes: text rows as text
/// displays, item, head and block rows as item and block displays, laid out
/// the way the server stacks them. Sample viewer values fill expressions.
library;

import '../../logic/gui/gui_expression.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_layout.dart';
import '../../logic/gui/gui_text.dart';
import '../assets/mc_display_pose.dart';
import '../npc/mc_text.dart';
import '../npc/npc_scene.dart';
import '../scene/mc_camera.dart';
import '../scene/mc_math.dart';
import '../scene/mc_scene.dart';

/// Blocks one text row takes (a text display line at scale 1).
const double guiTextRowBlocks = 0.25;

/// Blocks an item or block row takes.
const double guiItemRowBlocks = 0.55;

final class GuiScene {
  const GuiScene({required this.scene, required this.texts, required this.notes});

  final McScene scene;
  final List<NpcText> texts;

  /// What the preview does not draw, and why.
  final List<String> notes;
}

List<List<McTextRun>> _styled(String text) => <List<McTextRun>>[for (final String line in text.split('\n')) guiTextRuns(line)];

String? _bareItem(String? item) {
  if (item == null || item.contains('{{')) return null;
  final String trimmed = item.trim();
  final int colon = trimmed.indexOf(':');
  return colon < 0 ? trimmed : trimmed.substring(colon + 1);
}

/// The lines a hologram shows: a page's lines when [page] is given, a
/// variant's when [variant] replaces them, otherwise the base lines.
JsonList hologramLines(JsonMap doc, {int? page, int? variant}) {
  if (page != null) return asList(asMap(asList(doc['pages']).elementAtOrNull(page))['lines']);
  if (variant != null) {
    final JsonMap presentation = asMap(asMap(asList(doc['variants']).elementAtOrNull(variant))['presentation']);
    if (presentation.containsKey('lines')) return asList(presentation['lines']);
  }
  return asList(doc['lines']);
}

GuiScene hologramScene(JsonMap doc, {int? page, int? variant}) {
  final JsonList lines = hologramLines(doc, page: page, variant: variant);
  final List<McSceneNode> nodes = <McSceneNode>[];
  final List<NpcText> texts = <NpcText>[];
  final List<String> notes = <String>[];
  double height = 0;
  for (final Object? line in lines) {
    height += _rowHeight(line);
  }
  // Rows stack downward from the top; the anchor sits 1.2 blocks above the
  // grass so the stack reads at eye level.
  double top = 1.2 + height;
  for (int index = 0; index < lines.length; index++) {
    final Object? line = lines[index];
    final double row = _rowHeight(line);
    final double bottom = top - row;
    if (line == null || line is String) {
      final String text = line is String ? line : '';
      texts.add(NpcText(anchor: McVec3(0, bottom, 0), lines: <String>[text], nameplate: false, styled: _styled(text)));
    } else if (line is Map<String, Object?>) {
      final McVec3 centre = McVec3(0, bottom + row / 2, 0);
      if (!sampleCondition(line['show'])) {
        top = bottom;
        continue;
      }
      if (line['text'] is String) {
        final String text = line['text']! as String;
        texts.add(NpcText(anchor: McVec3(0, bottom, 0), lines: <String>[text], nameplate: false, styled: _styled(text)));
      } else if (line['item'] is Map<String, Object?>) {
        final String? item = _bareItem(asString(asMap(line['item'])['item']));
        if (item != null) {
          nodes.add(McItemNode(key: 'line-$index', itemId: item, pose: McDisplayPose.fixed, transform: McMat4.translation(centre.x, centre.y, centre.z)));
        } else {
          notes.add('Line ${index + 1}: the item comes from an expression, which needs the server.');
        }
      } else if (line['block'] is String) {
        final String? block = _bareItem(line['block']! as String);
        if (block != null) {
          nodes.add(McItemNode(
            key: 'line-$index',
            itemId: block,
            pose: McDisplayPose.none,
            transform: McMat4.translation(centre.x, centre.y, centre.z).multiply(McMat4.scale(0.5, 0.5, 0.5)),
          ));
        }
      } else if (line['head'] is String) {
        nodes.add(McItemNode(key: 'line-$index', itemId: 'player_head', pose: McDisplayPose.fixed, transform: McMat4.translation(centre.x, centre.y, centre.z)));
        notes.add('Line ${index + 1}: heads show the default head; the server draws ${line['head']}\'s skin.');
      } else if (line['entity'] is String) {
        notes.add('Line ${index + 1}: entity ${line['entity']} is not drawn in the preview.');
      }
    }
    top = bottom;
  }
  return GuiScene(
    scene: McScene(nodes, frame: _corners(McBounds(const McVec3(-1, 0.8, -0.5), McVec3(1, 1.4 + height, 0.5)))),
    texts: texts,
    notes: notes,
  );
}

double _rowHeight(Object? line) {
  if (line == null) return guiTextRowBlocks;
  if (line is String) return guiTextRowBlocks * line.split('\n').length;
  if (line is Map<String, Object?> && line['text'] is String) return guiTextRowBlocks * (line['text']! as String).split('\n').length;
  return line is Map<String, Object?> ? (asDouble(line['scale']) ?? guiItemRowBlocks) : guiItemRowBlocks;
}

/// A holographic menu in front of a viewer: the menu centre is [offset] from
/// the viewer's feet, and each component sits at its own offset from there.
GuiScene menuScene(JsonMap doc, {String? selected}) {
  final List<McSceneNode> nodes = <McSceneNode>[];
  final List<NpcText> texts = <NpcText>[];
  final List<String> notes = <String>[];
  final JsonList offset = asList(doc['offset']);
  final double centreY = (asDouble(offset.elementAtOrNull(1)) ?? 1.5).clamp(0.2, 4).toDouble();
  McBounds? bounds;
  void place(String key, JsonMap data, McVec3 at, String label, {GuiSampleScope? scope}) {
    final McBounds here = McBounds(at - const McVec3(0.3, 0.3, 0.3), at + const McVec3(0.3, 0.3, 0.3));
    bounds = bounds == null ? here : bounds!.union(here);
    final String type = asString(data['type']) ?? '';
    if (type == 'list') {
      final JsonMap flow = asMap(data['flow']);
      final int columns = asInt(flow['columns']) ?? 3;
      final double dx = asDouble(flow['spacingX']) ?? 0.5;
      final double dy = asDouble(flow['spacingY']) ?? 0.5;
      final Object? source = data['source'] is String ? sampleValue(data['source']! as String) : null;
      final List<Object?> entries = source is List<Object?> ? source : const <Object?>[null, null, null];
      final int count = entries.length.clamp(0, asInt(data['pageSize']) ?? 64);
      final String variable = asString(data['var']) ?? 'entry';
      for (int index = 0; index < count; index++) {
        final Object? entry = entries[index];
        final McVec3 cell = at + McVec3((index % columns) * dx, -(index ~/ columns) * dy, 0);
        place('$key-$index', asMap(data['template']), cell, '$label entry ${index + 1}',
            scope: GuiSampleScope(extra: <String, Object?>{if (entry != null) variable: entry is num ? entry.toDouble() : entry}));
      }
      if (source is! List<Object?>) notes.add('$label: the list source needs the server; three sample entries are shown.');
      return;
    }
    if (type == 'slider') {
      final String text = renderTemplate(asString(data['label']) ?? '${asString(data['var']) ?? 'value'}: ?', scope: scope);
      texts.add(NpcText(anchor: at, lines: text.split('\n'), nameplate: false, styled: _styled(text)));
      return;
    }
    if (type == 'tabs') {
      final JsonList tabs = asList(data['tabs']);
      final double spacing = asDouble(data['spacing']) ?? 0.6;
      for (int index = 0; index < tabs.length; index++) {
        final JsonMap tab = asMap(tabs[index]);
        final String text = asString(tab['label']) ?? asString(tab['id']) ?? '';
        texts.add(NpcText(anchor: at + McVec3((index - (tabs.length - 1) / 2) * spacing, 0, 0), lines: <String>[text], nameplate: false, styled: _styled(text)));
      }
      return;
    }
    final JsonMap icon = componentIcon(data, scope: scope);
    switch (asString(icon['type'])) {
      case 'text':
        final String text = renderTemplate(asString(icon['text']) ?? '', scope: scope);
        texts.add(NpcText(anchor: at, lines: text.split('\n'), nameplate: false, styled: _styled(text)));
      case 'item':
        final String? item = _bareItem(asString(icon['item']));
        if (item == null) {
          notes.add('$label: the item comes from an expression, which needs the server.');
        } else {
          nodes.add(McItemNode(key: key, itemId: item, pose: McDisplayPose.fixed, transform: McMat4.translation(at.x, at.y, at.z)));
        }
        final String? name = asString(icon['name']);
        if (name != null && name.trim().isNotEmpty && key == selected) {
          texts.add(NpcText(anchor: at + const McVec3(0, 0.3, 0), lines: <String>[name], nameplate: false, styled: _styled(name)));
        }
      case 'block':
        final String? block = _bareItem(asString(icon['block']));
        if (block != null) {
          nodes.add(McItemNode(
            key: key,
            itemId: block,
            pose: McDisplayPose.none,
            transform: McMat4.translation(at.x, at.y, at.z).multiply(McMat4.scale(0.375, 0.375, 0.375)),
          ));
        }
      case 'playerHead':
        nodes.add(McItemNode(key: key, itemId: 'player_head', pose: McDisplayPose.fixed, transform: McMat4.translation(at.x, at.y, at.z)));
        notes.add('$label: heads show the default head; the server draws the player\'s skin.');
      case 'entity':
        notes.add('$label: entity ${icon['entity']} is not drawn in the preview.');
      default:
        break;
    }
  }

  final JsonList components = asList(doc['components']);
  for (int index = 0; index < components.length; index++) {
    final JsonMap component = asMap(components[index]);
    final String id = asString(component['id']) ?? 'component-$index';
    if (!sampleCondition(component['show'])) {
      notes.add('$id is hidden for the sample viewer (show is false).');
      continue;
    }
    final JsonList at = asList(component['offset']);
    final McVec3 position = McVec3(asDouble(at.elementAtOrNull(0)) ?? 0, centreY + (asDouble(at.elementAtOrNull(1)) ?? 0), asDouble(at.elementAtOrNull(2)) ?? 0);
    place(id, asMap(component['data']), position, id);
  }
  return GuiScene(
    scene: McScene(nodes, frame: _corners(bounds ?? const McBounds(McVec3(-1, 0.5, -0.5), McVec3(1, 2.5, 0.5)))),
    texts: texts,
    notes: notes,
  );
}

/// The eight corners of [box], the points the stage camera frames.
List<McVec3> _corners(McBounds box) => <McVec3>[
      for (final double x in <double>[box.min.x, box.max.x])
        for (final double y in <double>[box.min.y, box.max.y])
          for (final double z in <double>[box.min.z, box.max.z]) McVec3(x, y, z),
    ];
