/// Holograms and holographic menus as stage scenes: text rows as text
/// displays, item, head and block rows as item and block displays, laid out
/// the way the server stacks them. Sample viewer values fill expressions.
library;

import 'dart:math' as math;

import '../../logic/gui/gui_expression.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_layout.dart';
import '../../logic/gui/gui_text.dart';
import '../../logic/gui/text_animation.dart';
import '../../logic/npc/npc_appearance.dart' show bodyLabel;
import '../assets/mc_display_pose.dart';
import '../npc/mc_text.dart';
import '../npc/npc_scene.dart';
import '../scene/mc_camera.dart';
import '../scene/mc_math.dart';
import '../scene/mc_scene.dart';
import 'hologram_style.dart';

/// Blocks one text row takes (a text display line at scale 1).
const double guiTextRowBlocks = 0.25;

/// Something the stage can pick and ring: a menu component by id.
final class GuiSceneTarget {
  const GuiSceneTarget({required this.key, required this.centre, required this.half});

  final String key;
  final McVec3 centre;

  /// Half its size in blocks, for the ring.
  final McVec3 half;
}

final class GuiScene {
  const GuiScene({required this.scene, required this.texts, required this.notes, this.targets = const <GuiSceneTarget>[]});

  final McScene scene;
  final List<NpcText> texts;

  /// What the preview does not draw, and why.
  final List<String> notes;

  /// What a click on the stage can select.
  final List<GuiSceneTarget> targets;
}

List<List<McTextRun>> _styled(String text) => guiDisplayRows(<String>[text]).single;

/// The text animations a preview plays: `|animation.<id>|` tokens in text
/// show the frame each animation shows at [nowMs], ids resolved from the
/// document [from] (`namespace:path`).
final class GuiAnimationClock {
  const GuiAnimationClock({required this.from, this.animations = const <String, JsonMap>{}, this.nowMs = 0});

  /// No animations: tokens stay as written.
  static const GuiAnimationClock none = GuiAnimationClock(from: '');

  final String from;
  final Map<String, JsonMap> animations;
  final int nowMs;

  String apply(String text) => renderAnimationTokens(text, from, animations, nowMs);

  /// Animations [text] shows that the workspace does not have.
  Iterable<String> missing(String text) => animationReferences(text, from).where((String id) => !animations.containsKey(id));
}

/// A text display for [raw] at [at], its animations at the clock's time.
NpcText _shownText(McVec3 at, String raw, GuiAnimationClock clock, Set<String> missing) {
  missing.addAll(clock.missing(raw));
  final String text = clock.apply(raw);
  return NpcText(anchor: at, lines: text.split('\n'), nameplate: false, styled: _styled(text));
}

/// Blocks per em of world text: 8 font pixels of 1/40 block.
const double _blocksPerEm = 0.2;

/// A menu text display for [raw] as the server places it (MenuSession):
/// its rows in one display centred on [at], with [style] over Gloss's menu
/// defaults (no wrap, clear background). Also the box it covers, at least
/// 0.3 wide like the server's click plane and grown up by wrapped rows.
(NpcText, McBounds) _menuText(McVec3 at, String raw, JsonMap style, GuiAnimationClock clock, Set<String> missing) {
  missing.addAll(clock.missing(raw));
  final String text = clock.apply(raw);
  final HologramTextStyle look = HologramTextStyle.of(style);
  final List<String> lines = text.split('\n');
  final double row = guiTextRowBlocks * look.scaleY;
  final McVec3 anchor = at - McVec3(0, row * lines.length / 2, 0);
  final double wrap = look.lineWidth * _blocksPerEm / 8 * look.scaleX;
  double widest = 0;
  int wrapped = 0;
  for (final String line in guiColorize(renderTemplate(text)).split('\n')) {
    final double width = mcTextLineWidthPx(line, _blocksPerEm) * look.scaleX;
    widest = math.max(widest, math.min(width, wrap));
    wrapped += math.max(1, (width / wrap - 1e-9).ceil());
  }
  final double half = math.max(0.3, widest) / 2;
  return (
    NpcText(
      anchor: anchor,
      lines: lines,
      nameplate: false,
      styled: _styled(text),
      lineWidth: look.lineWidth,
      background: look.background,
      shadow: look.shadow,
      alignment: look.alignment,
      opacity: look.opacity,
      scaleX: look.scaleX,
      scaleY: look.scaleY,
      brightness: look.brightness,
      // A menu's components stand where the menu puts them.
      placed: true,
    ),
    McBounds(anchor - McVec3(half, 0, 0), anchor + McVec3(half, row * wrapped, 0)),
  );
}

void _missingNotes(Set<String> missing, List<String> notes) {
  for (final String id in missing) {
    notes.add('Text animation $id is not in this workspace; token shown as written.');
  }
}

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

/// A hologram laid out as the server lays it out (HologramRender): every
/// text row in one text display whose bottom sits on the anchor and which
/// carries the hologram's style, object lines in blank rows of their own
/// height, a hidden text row keeping its space only while there are object
/// lines.
GuiScene hologramScene(JsonMap doc, {int? page, int? variant, GuiAnimationClock clock = GuiAnimationClock.none}) {
  final Set<String> missing = <String>{};
  final JsonList lines = hologramLines(doc, page: page, variant: variant);
  final List<McSceneNode> nodes = <McSceneNode>[];
  final List<NpcText> texts = <NpcText>[];
  final List<String> notes = <String>[];
  final HologramTextStyle style = HologramTextStyle.of(hologramStyle(doc, variant: variant));
  final double rowHeight = guiTextRowBlocks * style.scaleY;
  bool isText(Object? line) => line is! Map<String, Object?> || line['text'] is String;
  final bool objects = lines.any((Object? line) => !isText(line));
  final List<String> rows = <String>[];
  final List<String> coloured = <String>[];
  final List<({int index, JsonMap line, int first, int count})> placed = <({int index, JsonMap line, int first, int count})>[];
  for (int index = 0; index < lines.length; index++) {
    final Object? line = lines[index];
    final bool shown = line is! Map<String, Object?> || sampleCondition(line['show']);
    if (!shown) notes.add('Row ${index + 1} hidden for the sample viewer.');
    if (isText(line)) {
      final String raw = line is String ? line : (line is Map<String, Object?> ? line['text']! as String : '');
      missing.addAll(clock.missing(raw));
      final String text = clock.apply(raw);
      final List<String> parts = text.split('\n');
      final List<String> styledParts = guiColorize(renderTemplate(text)).split('\n');
      if (shown) {
        rows.addAll(parts);
        coloured.addAll(styledParts);
      } else if (objects) {
        rows.addAll(<String>[for (final String _ in parts) '']);
        coloured.addAll(<String>[for (final String _ in parts) '']);
      }
      continue;
    }
    final JsonMap object = line! as JsonMap;
    final int count = math.max(1, ((asDouble(object['scale']) ?? 1) / rowHeight - 1e-9).ceil());
    if (shown) placed.add((index: index, line: object, first: rows.length, count: count));
    for (int row = 0; row < count; row++) {
      rows.add(' ');
      coloured.add(' ');
    }
  }
  // The anchor sits 1.2 blocks above the grass so the stack reads at eye
  // level. Yaw and pitch turn each display about its own position, as the
  // server sends them; a centre or horizontal billboard keeps text upright.
  const double base = 1.2;
  final double total = rows.length * rowHeight;
  final McMat4 turn = McMat4.rotationY(-(asDouble(doc['yaw']) ?? 0)).multiply(McMat4.rotationX(asDouble(doc['pitch']) ?? 0));
  final List<List<McTextRun>> styled = <List<McTextRun>>[for (final List<List<McTextRun>> line in guiDisplayRows(coloured, sample: false)) ...line];
  // The text block's real size: its widest row (formatting carried from the
  // rows above it, codes taking no room) up to the wrap width, and the rows
  // that wrapping adds.
  final double wrap = style.lineWidth * _blocksPerEm / 8 * style.scaleX;
  double widest = 0;
  int drawnRows = 0;
  for (final List<McTextRun> row in styled) {
    final double width = _rowBlocks(row) * style.scaleX;
    widest = math.max(widest, math.min(width, wrap));
    drawnRows += math.max(1, (width / wrap - 1e-9).ceil());
  }
  if (rows.isNotEmpty) {
    McVec3 anchor = const McVec3(0, base, 0);
    if (style.tilts) {
      final McVec3 middle = turn.transformPoint(McVec3(0, total / 2, 0));
      anchor = McVec3(middle.x, base + middle.y - total / 2, middle.z);
    }
    texts.add(NpcText(
      anchor: anchor,
      lines: rows,
      nameplate: false,
      styled: styled,
      lineWidth: style.lineWidth,
      background: style.background,
      shadow: style.shadow,
      alignment: style.alignment,
      opacity: style.opacity,
      scaleX: style.scaleX,
      scaleY: style.scaleY,
      brightness: style.brightness,
    ));
  }
  McMat4 at(McVec3 centre, double scale) => McMat4.translation(centre.x, centre.y, centre.z).multiply(turn).multiply(McMat4.scale(scale, scale, scale));
  for (final ({int index, JsonMap line, int first, int count}) object in placed) {
    final JsonMap line = object.line;
    final int row = object.index + 1;
    final McVec3 centre = McVec3(0, base + total - (object.first + object.count / 2) * rowHeight, 0);
    final double scale = asDouble(line['scale']) ?? 1;
    if (line['item'] is Map<String, Object?>) {
      final String? item = _bareItem(asString(asMap(line['item'])['item']));
      if (item != null) {
        nodes.add(McItemNode(key: 'line-${object.index}', itemId: item, pose: McDisplayPose.fixed, transform: at(centre, scale)));
      } else {
        notes.add('Row $row: item expression resolves on the server.');
      }
    } else if (line['block'] is String) {
      final String? block = _bareItem(line['block']! as String);
      if (block != null) nodes.add(McItemNode(key: 'line-${object.index}', itemId: block, pose: McDisplayPose.none, transform: at(centre, 0.5 * scale)));
    } else if (line['head'] is String) {
      nodes.add(McItemNode(key: 'line-${object.index}', itemId: 'player_head', pose: McDisplayPose.fixed, transform: at(centre, scale)));
      notes.add('Row $row: head drawn without ${line['head']}\'s skin.');
    } else if (line['entity'] is String) {
      final String entity = line['entity']! as String;
      nodes.add(McItemNode(key: 'line-${object.index}', itemId: 'glass', pose: McDisplayPose.none, transform: at(centre, 0.45 * scale)));
      texts.add(_shownText(centre + const McVec3(0, -0.05, 0), '&7${bodyLabel(entity)}', clock, missing));
      notes.add('Row $row: entity drawn as a labelled box.');
    }
  }
  notes.addAll(style.notes);
  _missingNotes(missing, notes);
  // Two blocks across at least; a wider block is framed with room for the
  // text layer's font steps, which can draw it up to a sixth wider than its
  // world size.
  final double half = math.max(1, widest * _fontStepSlack / 2);
  final double tall = math.max(total, drawnRows * rowHeight);
  return GuiScene(
    scene: McScene(nodes, frame: _corners(McBounds(McVec3(-half, 0.8, -0.5), McVec3(half, 1.4 + tall, 0.5)))),
    texts: texts,
    notes: notes,
  );
}

/// How much wider than its world size the text layer may draw a row: its
/// font goes in 8 px steps from 16 px, so a row framed at 12 to 20 px is
/// drawn at the next step.
const double _fontStepSlack = 1.16;

/// Blocks [row] takes at world size: each glyph advances 6 font pixels, 7
/// when bold, 8 to the em.
double _rowBlocks(List<McTextRun> row) {
  double units = 0;
  for (final McTextRun run in row) {
    units += run.text.length * (run.bold ? 7 : 6);
  }
  return units / 8 * _blocksPerEm;
}

/// The style a hologram draws with: its own, with a shown variant's
/// presentation style laid over it.
JsonMap hologramStyle(JsonMap doc, {int? variant}) {
  final JsonMap own = asMap(doc['style']);
  if (variant == null) return own;
  final JsonMap over = asMap(asMap(asMap(asList(doc['variants']).elementAtOrNull(variant))['presentation'])['style']);
  return <String, Object?>{...own, ...over};
}

/// A holographic menu in front of a viewer: the menu centre is [offset] from
/// the viewer's feet, and each component sits at its own offset from there.
GuiScene menuScene(JsonMap doc, {String? selected, GuiAnimationClock clock = GuiAnimationClock.none}) {
  final Set<String> missing = <String>{};
  final List<McSceneNode> nodes = <McSceneNode>[];
  final List<NpcText> texts = <NpcText>[];
  final List<String> notes = <String>[];
  final List<GuiSceneTarget> targets = <GuiSceneTarget>[];
  final JsonList offset = asList(doc['offset']);
  final McVec3 centre = McVec3(
    asDouble(offset.elementAtOrNull(0)) ?? 0,
    (asDouble(offset.elementAtOrNull(1)) ?? 1.5).clamp(-64, 64).toDouble(),
    asDouble(offset.elementAtOrNull(2)) ?? 0,
  );
  McBounds? bounds;
  McBounds? componentBounds;
  void grow(McBounds here) {
    bounds = bounds == null ? here : bounds!.union(here);
    componentBounds = componentBounds == null ? here : componentBounds!.union(here);
  }

  void addText(McVec3 at, String text, Object? style) {
    final (NpcText shown, McBounds box) = _menuText(at, text, asMap(style), clock, missing);
    texts.add(shown);
    grow(box);
  }

  void place(String key, JsonMap data, McVec3 at, String label, {GuiSampleScope? scope}) {
    final McBounds around = McBounds(at - const McVec3(0.3, 0.3, 0.3), at + const McVec3(0.3, 0.3, 0.3));
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
      if (count == 0) grow(around);
      if (source is! List<Object?>) notes.add('$label: list source resolves on the server; showing three samples.');
      return;
    }
    if (type == 'slider') {
      addText(at, renderTemplate(asString(data['label']) ?? '${asString(data['var']) ?? 'value'}: ?', scope: scope), data['style']);
      return;
    }
    if (type == 'tabs') {
      final JsonList tabs = asList(data['tabs']);
      final double spacing = asDouble(data['spacing']) ?? 0.6;
      for (int index = 0; index < tabs.length; index++) {
        final JsonMap tab = asMap(tabs[index]);
        final String text = asString(tab['label']) ?? asString(tab['id']) ?? '';
        addText(at + McVec3((index - (tabs.length - 1) / 2) * spacing, 0, 0), text, data['style']);
      }
      return;
    }
    final JsonMap icon = componentIcon(data, scope: scope);
    if (asString(icon['type']) == 'text') {
      addText(at, renderTemplate(asString(icon['text']) ?? '', scope: scope), icon['style']);
      return;
    }
    grow(around);
    switch (asString(icon['type'])) {
      case 'item':
        final String? item = _bareItem(asString(icon['item']));
        if (item == null) {
          notes.add('$label: item expression resolves on the server.');
        } else {
          nodes.add(McItemNode(key: key, itemId: item, pose: McDisplayPose.fixed, transform: McMat4.translation(at.x, at.y, at.z)));
        }
        final String? name = asString(icon['name']);
        if (name != null && name.trim().isNotEmpty && key == selected) {
          texts.add(_shownText(at + const McVec3(0, 0.3, 0), name, clock, missing));
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
        notes.add('$label: head drawn without the player\'s skin.');
      case 'entity':
        final double width = asDouble(icon['width']) ?? 1;
        final double height = asDouble(icon['height']) ?? 1;
        nodes.add(McItemNode(key: key, itemId: 'glass', pose: McDisplayPose.none, transform: McMat4.translation(at.x, at.y, at.z).multiply(McMat4.scale(width * 0.5, height * 0.5, width * 0.5))));
        texts.add(_shownText(at, '&7${bodyLabel(asString(icon['entity']) ?? 'minecraft:pig')}', clock, missing));
        notes.add('$label: entity drawn as a labelled box.');
      default:
        break;
    }
  }

  final JsonList components = asList(doc['components']);
  for (int index = 0; index < components.length; index++) {
    final JsonMap component = asMap(components[index]);
    final String id = asString(component['id']) ?? 'component-$index';
    if (!sampleCondition(component['show'])) {
      notes.add('$id hidden for the sample viewer.');
      continue;
    }
    final JsonList at = asList(component['offset']);
    final McVec3 position = centre + McVec3(asDouble(at.elementAtOrNull(0)) ?? 0, asDouble(at.elementAtOrNull(1)) ?? 0, asDouble(at.elementAtOrNull(2)) ?? 0);
    componentBounds = null;
    place(id, asMap(component['data']), position, id);
    final McBounds? box = componentBounds;
    if (box != null) targets.add(GuiSceneTarget(key: id, centre: box.centre, half: (box.max - box.min) * 0.5));
  }
  _missingNotes(missing, notes);
  return GuiScene(
    // A menu floats in front of the viewer: no grass patch under it.
    scene: McScene(nodes, frame: _corners(bounds ?? const McBounds(McVec3(-1, 0.5, -0.5), McVec3(1, 2.5, 0.5))), ground: false),
    texts: texts,
    notes: notes,
    targets: targets,
  );
}

/// The eight corners of [box], the points the stage camera frames.
List<McVec3> _corners(McBounds box) => <McVec3>[
      for (final double x in <double>[box.min.x, box.max.x])
        for (final double y in <double>[box.min.y, box.max.y])
          for (final double z in <double>[box.min.z, box.max.z]) McVec3(x, y, z),
    ];
