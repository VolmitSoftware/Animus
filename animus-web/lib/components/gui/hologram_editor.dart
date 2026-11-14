/// The hologram page: its lines (text, items, heads, blocks, entities) as a
/// reorderable list and where it hangs, with the hologram drawn live in
/// the preview. Advanced adds the style, the click box and its actions,
/// pages and variants; a Simple page counts those when they are set.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../config/docs_links.dart';
import '../../logic/diagnostic_text.dart';
import '../../logic/gui/gui_catalog.dart';
import '../../logic/gui/gui_expression.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_text.dart';
import '../../logic/gui/hologram_lines.dart';
import '../../logic/gui/text_animation.dart';
import '../../logic/suggest/completion_sources.dart';
import '../../logic/suggest/field_profile.dart';
import '../../logic/suggest/field_profiles.dart';
import '../../mc/gui/gui_scene.dart';
import '../../mc/npc/mc_text.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../ui/advanced_in_use.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/menu.dart';
import '../../ui/mode.dart';
import '../../ui/section.dart';
import '../../ui/tabs.dart';
import '../mc/item_picker.dart';
import 'actions_editor.dart';
import 'component_editor.dart';
import 'gui_fields.dart';
import 'gui_rows.dart';
import 'gui_split.dart';
import 'gui_stage_preview.dart';
import 'gui_targets.dart';
import 'presets_editor.dart';
import 'style_editor.dart';
import '../select/animus_select.dart';
import '../select/named_value_picker.dart';
import '../suggest/autocomplete_input.dart';
import '../../ui/field.dart';
import '../../ui/number_input.dart';
import 'gui_stack.dart';
import '../../ui/stack.dart';

GuiAnimationClock _clock(AnimusDocument document, Map<String, JsonMap> animations, int nowMs) => GuiAnimationClock(from: document.id, animations: animations, nowMs: nowMs);

const Map<String, String> _kindLabels = <String, String>{'text': 'Text', 'item': 'Item', 'head': 'Player head', 'block': 'Block', 'entity': 'Entity'};

/// Fields a Simple hologram page does not show, counted when set.
int hologramAdvancedInUse(JsonMap doc) => <bool>[
  asMap(doc['style']).isNotEmpty,
  asList(doc['actions']).isNotEmpty,
  asMap(doc['hitbox']).isNotEmpty,
  doc['refreshTicks'] != null || asMap(doc['refresh']).isNotEmpty,
  asList(doc['pages']).isNotEmpty,
  asList(doc['variants']).isNotEmpty,
].where((bool on) => on).length;

class HologramEditor extends StatefulWidget {
  const HologramEditor({required this.document, required this.onJson, this.animations = const <String, JsonMap>{}, this.presets, super.key});

  final AnimusDocument document;
  final JsonEdit onJson;

  /// The workspace's text animations by id.
  final Map<String, JsonMap> animations;

  /// The workspace's presets document, for the preset fix.
  final JsonMap? presets;

  @override
  State<HologramEditor> createState() => _HologramEditorState();
}

class _HologramEditorState extends State<HologramEditor> {
  int _tab = 0;

  /// Which lines are edited and shown: null for the base lines.
  int? _page;
  int? _variant;

  /// The line whose details are open.
  int? _open;

  /// Show on a Simple page: the advanced parts open without the mode.
  bool _reveal = false;

  JsonMap get _doc => asMap(component.document.json);
  List<Diagnostic> get _diagnostics => component.document.diagnostics;

  void _write(List<JsonStep> steps, Object? value, {String? coalesce}) => component.onJson(writeAt(_doc, steps, value), coalesce: coalesce ?? steps.join('.'));

  List<JsonStep> get _linesSteps {
    if (_page != null) return <JsonStep>['pages', _page!, 'lines'];
    if (_variant != null) return <JsonStep>['variants', _variant!, 'presentation', 'lines'];
    return const <JsonStep>['lines'];
  }

  ExpressionNames get _names => ExpressionNames(animations: animationCompletionIds(component.document.id, component.animations.keys));

  @override
  Widget build(BuildContext context) {
    final JsonMap doc = _doc;
    final bool advanced = ModeScope.advanced(context) || _reveal;
    final JsonList pages = asList(doc['pages']);
    final JsonList variants = asList(doc['variants']);
    if (_page != null && _page! >= pages.length) _page = pages.isEmpty ? null : pages.length - 1;
    if (_variant != null && _variant! >= variants.length) _variant = variants.isEmpty ? null : variants.length - 1;
    // With pages the base lines never show: edit the first page instead.
    if (_page == null && _variant == null && pages.isNotEmpty) _page = 0;
    final List<String> tabs = <String>[
      'Lines',
      'Where',
      if (advanced) ...<String>['Style', 'Clicks'],
    ];
    final int tab = _tab < tabs.length ? _tab : 0;
    final int hidden = advanced ? 0 : hologramAdvancedInUse(doc);
    final String which = _page != null ? 'page:$_page' : (_variant != null ? 'variant:$_variant' : 'base');
    return GuiSplit(
      label: 'Hologram',
      form: <Widget>[
        PresetFix(document: component.document, presets: component.presets, kind: DocumentKind.hologram, onJson: component.onJson),
        AppTabs(label: 'Hologram', tabs: tabs, selected: tab, onSelect: (int index) => setState(() => _tab = index)),
        switch (tab) {
          0 => _linesTab(doc, pages, variants, advanced),
          1 => _whereTab(doc, advanced),
          2 => _styleTab(doc),
          _ => _clicksTab(doc),
        },
        if (hidden > 0) AdvancedInUse(count: hidden, onShow: () => setState(() => _reveal = true)),
      ],
      preview: (bool band) => GuiStagePreview(
        scene: hologramScene(doc, page: _page, variant: _variant, clock: _clock(component.document, component.animations, DateTime.now().millisecondsSinceEpoch)),
        sceneAt: (int nowMs) => hologramScene(doc, page: _page, variant: _variant, clock: _clock(component.document, component.animations, nowMs)),
        tickMs: animationTickMs(component.document.source, component.document.id, component.animations),
        signature: '${component.document.source}|$which',
        label: 'Hologram ${component.document.id}',
        band: band,
      ),
    );
  }

  // ---- Lines ----

  Widget _linesTab(JsonMap doc, JsonList pages, JsonList variants, bool advanced) {
    final String which = _page != null ? 'page:$_page' : (_variant != null ? 'variant:$_variant' : 'base');
    final JsonList lines = asList(readIn(doc, _linesSteps));
    return stack(spacing: 16, <Widget>[
      if (pages.isNotEmpty || variants.isNotEmpty)
        Field(
          label: 'Lines of',
          forId: 'animus-holo-which',
          tip: 'Each page and variant has its own lines. The preview shows the lines you edit.',
          child: AnimusSelect.values(
            id: 'animus-holo-which',
            value: which == 'base' ? null : which,
            allowNone: pages.isEmpty,
            noneLabel: 'Hologram',
            values: <String>[for (int i = 0; i < pages.length; i++) 'page:$i', for (int i = 0; i < variants.length; i++) 'variant:$i'],
            labels: <String, String>{
              for (int i = 0; i < pages.length; i++) 'page:$i': 'Page ${asString(asMap(pages[i])['id']) ?? i + 1}',
              for (int i = 0; i < variants.length; i++) 'variant:$i': 'Variant ${asString(asMap(variants[i])['id']) ?? i + 1}',
            },
            onChanged: (String? value) => setState(() {
              _page = value != null && value.startsWith('page:') ? int.parse(value.substring(5)) : null;
              _variant = value != null && value.startsWith('variant:') ? int.parse(value.substring(8)) : null;
              _open = null;
            }),
          ),
        ),
      _lines(lines, advanced),
      if (advanced) ...<Widget>[
        AppSection(
          title: 'Pages',
          count: pages.length,
          open: pages.isNotEmpty,
          tip: 'Each viewer sees one page and can turn pages with a click action.',
          docs: Docs.holograms,
          children: <Widget>[stack(_pages(pages))],
        ),
        AppSection(
          title: 'Variants',
          count: variants.length,
          open: variants.isNotEmpty,
          tip: 'Other lines or style while a condition holds. The highest priority wins.',
          docs: Docs.holograms,
          children: <Widget>[stack(_variants(variants))],
        ),
      ],
    ]);
  }

  Widget _lines(JsonList lines, bool advanced) {
    final String base = formatPath(_linesSteps);
    void setLines(JsonList next) => _write(_linesSteps, next, coalesce: '$base-structure');
    void add(String kind) {
      setLines(<Object?>[...lines, newHologramLine(kind)]);
      if (kind != 'text') setState(() => _open = lines.length);
    }

    final int? open = _open != null && _open! < lines.length ? _open : null;
    final bool full = lines.length >= 128;
    // Every text row shares one display, so a line's sample carries the style
    // of the lines shown above it, as the preview draws them for the sample
    // viewer. A hidden line is drawn where it stands without passing it on.
    final List<String> shown = <String>[
      for (final Object? line in lines)
        if (hologramLineKind(line) == 'text' && (line is! Map<String, Object?> || sampleCondition(line['show']))) _lineText(line) else '',
    ];
    final List<List<List<McTextRun>>> rows = guiDisplayRows(shown);
    List<List<McTextRun>> rowsOf(int index, String text) => text == shown[index] ? rows[index] : guiDisplayRows(<String>[...shown.take(index), text])[index];
    return stack(<Widget>[
      for (final Diagnostic problem in diagnosticsAt(_diagnostics, base)) AppAlert(tone: AlertTone.warning, message: diagnosticSentence(problem)),
      if (lines.isEmpty)
        mutedText('No lines yet.')
      else
        OrderedRows(
          label: 'Lines',
          dense: false,
          rows: <OrderedRow>[for (int index = 0; index < lines.length; index++) _line(lines, index, '$base[$index]', open == index, (String text) => rowsOf(index, text))],
          onMove: (int from, int to) {
            setLines(moveItem(lines, from, to));
            if (open == from) setState(() => _open = to);
          },
          onDuplicate: full ? null : (int index) => setLines(<Object?>[...lines]..insert(index + 1, deepCopy(lines[index]))),
          onDelete: (int index) {
            if (open == index) setState(() => _open = null);
            setLines(<Object?>[...lines]..removeAt(index));
          },
        ),
      ActionRow(
        label: 'Add line',
        more: <AppMenuEntry>[
          for (final String kind in hologramLineKinds)
            if (kind != 'text' && (advanced || kind == 'item')) AppMenuEntry('Add ${_kindLabels[kind]!.toLowerCase()}', full ? null : () => add(kind)),
        ],
        primary: RowAction('Add line', full ? null : () => add('text'), icon: ArcaneIcon.plus(), id: 'animus-holo-add'),
      ),
    ]);
  }

  static String _lineText(Object? line) => line is String ? line : (asString(asMap(line)['text']) ?? '');

  /// A text line's formatted sample: its rows, styled on from the lines
  /// above, when it has codes or holes or carries a style.
  static Widget? _sample(String text, List<List<McTextRun>> rows) {
    final bool styled = rows.any((List<McTextRun> row) => row.any((McTextRun run) =>
        run.color != mcDefaultTextColor || run.bold || run.italic || run.underlined || run.strikethrough || run.obfuscated));
    if (!styled && templateSample(text) == null) return null;
    return GuiStyledText.runs(<McTextRun>[
      for (int index = 0; index < rows.length; index++) ...<McTextRun>[if (index > 0) const McTextRun('\n'), ...rows[index]],
    ]);
  }

  OrderedRow _line(JsonList lines, int index, String path, bool open, List<List<McTextRun>> Function(String text) rowsOf) {
    final Object? line = lines[index];
    final JsonMap object = asMap(line);
    final String kind = hologramLineKind(line);
    final List<JsonStep> steps = <JsonStep>[..._linesSteps, index];
    void set(Object? next) => _write(steps, next, coalesce: path);
    final String label = 'Line ${index + 1}';
    final Widget value = switch (kind) {
      'text' => AutocompleteInput(
        value: _lineText(line),
        profile: FieldProfile.guiText,
        preview: (String text) => _sample(text, rowsOf(text)),
        gui: _names,
        label: label,
        onChanged: (String v) => set(withHologramLineValue(line, v)),
      ),
      'item' => ItemPicker(
        value: asString(asMap(object['item'])['item']) ?? '',
        label: '$label item',
        placeholder: 'minecraft:diamond',
        onChanged: (String v) => set(withHologramLineValue(line, withField(asMap(object['item']), 'item', v))),
      ),
      // Simple names blocks and entities; Advanced types the id.
      'block' when !ModeScope.advanced(context) => ItemPicker(
        value: asString(object['block']) ?? '',
        label: '$label block',
        onChanged: (String v) => set(withHologramLineValue(line, v)),
      ),
      'entity' when !ModeScope.advanced(context) => NamedValuePicker(
          refs: const <ValueRef>[ValueRef.registry(RegistryKind.entity)],
          value: asString(object['entity']),
          label: '$label entity',
          onChanged: (String? v) {
            if (v != null) set(withHologramLineValue(line, v));
          },
        ),
      _ => AutocompleteInput(
        value: asString(object[kind]) ?? '',
        profile: guiTextProfile(kind == 'head' ? GuiTextMode.template : GuiTextMode.plain),
        preview: (kind == 'head' ? GuiTextMode.template : GuiTextMode.plain) == GuiTextMode.template ? templateSample : null,
        gui: _names,
        label: '$label ${_kindLabels[kind]!.toLowerCase()}',
        placeholder: kind == 'head' ? 'Player name' : 'minecraft:$kind id',
        onChanged: (String v) => set(withHologramLineValue(line, v)),
      ),
    };
    final List<Diagnostic> problems = diagnosticsAt(_diagnostics, path, nested: true);
    final Object? show = object['show'];
    return OrderedRow(
      key: '$index',
      name: 'line ${index + 1}',
      open: open || problems.isNotEmpty,
      attributes: <String, String>{'data-path': path},
      line: fieldLine(<Widget>[
        value,
        if (kind != 'text') mutedText(_kindLabels[kind]!),
      ]),
      entries: <AppMenuEntry>[AppMenuEntry(open ? 'Hide details' : 'Details', () => setState(() => _open = open ? null : index))],
      body: open || problems.isNotEmpty
          ? stack(<Widget>[
              for (final Diagnostic problem in problems) AppAlert(tone: problem.severity == DiagnosticSeverity.error ? AlertTone.error : AlertTone.warning, message: diagnosticSentence(problem)),
              if (open) ...<Widget>[
                Field(
                  label: 'Kind',
                  forId: guiPathId('$path-kind'),
                  child: AnimusSelect.values(
                    id: guiPathId('$path-kind'),
                    value: kind,
                    values: hologramLineKinds,
                    labels: _kindLabels,
                    onChanged: (String? next) {
                      if (next != null && next != kind) set(withHologramLineKind(line, next));
                    },
                  ),
                ),
                if (kind == 'item') ..._itemFields(asMap(object['item']), path, index, (JsonMap next) => set(withHologramLineValue(line, next))),
                MoreFields(
                  open: show != null || object['scale'] != null,
                  children: <Widget>[
                    Field(
                      label: 'Show when',
                      forId: guiPathId('$path.show'),
                      help: showWhenHelp,
                      tip: showWhenTip,
                      problems: diagnosticsAt(_diagnostics, '$path.show'),
                      child: AutocompleteInput(
                        id: guiPathId('$path.show'),
                        value: conditionText(show),
                        profile: FieldProfile.guiExpression,
                        gui: _names,
                        label: '$label show when',
                        onChanged: (String v) => set(withHologramLineField(line, 'show', conditionValue(v))),
                      ),
                    ),
                    _number('Scale', '$path.scale', asDouble(object['scale']), (num? v) => set(withHologramLineField(line, 'scale', v)), help: 'Default 1', integer: false),
                  ],
                ),
              ],
            ])
          : null,
    );
  }

  /// An item line's other fields, keeping keys it does not edit.
  List<Widget> _itemFields(JsonMap icon, String path, int index, void Function(JsonMap) onChanged) {
    final String itemPath = '$path.item';
    void field(String key, Object? value) => onChanged(withField(icon, key, value));
    return <Widget>[
      _number('Count', '$itemPath.count', asInt(icon['count']), (num? v) => field('count', v), help: '1 to 99'),
      guiFieldRow(
        'Name',
        '$itemPath.name',
        _diagnostics,
        (String id) => AutocompleteInput(id: id, value: asString(icon['name']) ?? '', profile: FieldProfile.guiText, preview: templateSample, gui: _names, label: 'Line ${index + 1} name', onChanged: (String v) => field('name', v)),
      ),
      Field(
        label: 'Lore',
        problems: diagnosticsAt(_diagnostics, '$itemPath.lore', nested: true),
        child: GuiLines(label: 'Lore', lines: stringList(icon['lore']), names: _names, onChanged: (List<String> lines) => field('lore', lines)),
      ),
      if (ModeScope.advanced(context)) _number('Custom model', '$itemPath.customModelValue', asInt(icon['customModelValue']), (num? v) => field('customModelValue', v)),
      if (icon.containsKey('style'))
        AppAlert(
          tone: AlertTone.warning,
          message: 'A line has no style of its own; the hologram style applies.',
          action: Button.outline(label: 'Remove it', size: ButtonSize.sm, onPressed: () => field('style', null)),
        ),
    ];
  }

  Widget _number(String label, String path, num? value, void Function(num?) onChanged, {String? help, bool integer = true}) => guiFieldRow(
    label,
    path,
    _diagnostics,
    help: help,
    (String id) => NumberInput(id: id, value: value, integer: integer, label: label, onChanged: onChanged),
  );

  // ---- Where ----

  Widget _whereTab(JsonMap doc, bool advanced) {
    final JsonMap anchor = asMap(doc['anchor']);
    final JsonList position = asList(anchor['position']);
    double axis(int i) => asDouble(position.elementAtOrNull(i)) ?? 0;
    void setAxis(int i, num? value) => _write(const <JsonStep>['anchor', 'position'], <double>[axis(0), axis(1), axis(2)]..[i] = (value ?? 0).toDouble(), coalesce: 'anchor.position.$i');
    final List<NpcPlace> npcs = GuiTargets.of(context)?.npcPlaces() ?? const <NpcPlace>[];

    final String worldId = guiPathId(r'$.anchor.world');
    return stack(spacing: 16, <Widget>[
      if (npcs.isNotEmpty)
        Field(
          label: 'Above NPC',
          forId: 'animus-holo-npc',
          tip: 'Moves the hologram over the NPC once. It stays here if the NPC walks away; for text that follows an NPC, use the NPC\'s Look tab.',
          child: AnimusSelect.values(
            id: 'animus-holo-npc',
            value: null,
            allowNone: true,
            noneLabel: 'Pick an NPC',
            values: <String>[for (final NpcPlace npc in npcs) npc.id],
            labels: <String, String>{for (final NpcPlace npc in npcs) npc.id: npc.name},
            details: advanced ? <String, String>{for (final NpcPlace npc in npcs) npc.id: npc.id} : const <String, String>{},
            onChanged: (String? id) {
              final NpcPlace? npc = npcs.where((NpcPlace one) => one.id == id).firstOrNull;
              if (npc == null) return;
              _write(
                const <JsonStep>['anchor'],
                <String, Object?>{
                  ...anchor,
                  'world': npc.world,
                  'position': <double>[npc.x, npc.y + 2.5, npc.z],
                },
              );
            },
          ),
        ),
      Field(
        label: 'World',
        forId: worldId,
        problems: diagnosticsAt(_diagnostics, r'$.anchor.world'),
        child: AutocompleteInput(
          id: worldId,
          value: asString(anchor['world']) ?? '',
          label: 'World',
          profile: idProfile(const ValueRef.registry(RegistryKind.world)),
          onChanged: (String v) => _write(const <JsonStep>['anchor', 'world'], v),
        ),
      ),
      Field(
        label: 'Position',
        tip: advanced ? 'The anchor: the block position the hologram hangs from.' : null,
        problems: diagnosticsAt(_diagnostics, r'$.anchor.position', nested: true),
        child: axisLine(label: 'Position', id: 'hologram-position', values: <num?>[for (int i = 0; i < 3; i++) shownCoordinate(axis(i))], onChanged: setAxis),
      ),
      MoreFields(
        open: doc['yaw'] != null || doc['pitch'] != null || doc['viewDistance'] != null || doc['show'] != null,
        children: <Widget>[
          _number('Turn', r'$.yaw', shownCoordinate(asDouble(doc['yaw'])), (num? v) => _write(const <JsonStep>['yaw'], v), help: '-180 to 180 degrees', integer: false),
          _number('Tilt', r'$.pitch', shownCoordinate(asDouble(doc['pitch'])), (num? v) => _write(const <JsonStep>['pitch'], v), help: '-90 to 90 degrees', integer: false),
          _number('Seen from', r'$.viewDistance', asDouble(doc['viewDistance']), (num? v) => _write(const <JsonStep>['viewDistance'], v), help: 'Blocks, default 48', integer: false),
          guiFieldRow(
            'Show when',
            r'$.show',
            _diagnostics,
            help: showWhenHelp,
            tip: showWhenTip,
            (String id) => AutocompleteInput(
              id: id,
              value: conditionText(doc['show']),
              profile: FieldProfile.guiExpression,
              gui: _names,
              label: 'Show when',
              onChanged: (String v) => _write(const <JsonStep>['show'], conditionValue(v)),
            ),
          ),
        ],
      ),
      if (advanced) _refresh(doc),
    ]);
  }

  Widget _refresh(JsonMap doc) {
    final JsonMap refresh = asMap(doc['refresh']);
    void setRefresh(String key, num? value) {
      final JsonMap next = withField(refresh, key, value);
      _write(const <JsonStep>['refresh'], next.isEmpty ? null : next, coalesce: 'refresh.$key');
    }

    return AppSection(
      title: 'Refresh',
      open: doc['refreshTicks'] != null || refresh.isNotEmpty,
      tip: 'How often viewers get a redrawn hologram, in ticks.',
      docs: Docs.holograms,
      children: <Widget>[
        _number('Redraw', r'$.refreshTicks', asInt(doc['refreshTicks']), (num? v) => _write(const <JsonStep>['refreshTicks'], v), help: '1 to 200, default 10'),
        _number('Content', r'$.refresh.contentTicks', asInt(refresh['contentTicks']), (num? v) => setRefresh('contentTicks', v), help: 'Overrides Redraw'),
        _number('Visibility', r'$.refresh.visibilityTicks', asInt(refresh['visibilityTicks']), (num? v) => setRefresh('visibilityTicks', v), help: 'Between Show when checks'),
        _number('Motion', r'$.refresh.motionTicks', asInt(refresh['motionTicks']), (num? v) => setRefresh('motionTicks', v), help: 'Not used yet'),
      ],
    );
  }

  // ---- Style and clicks ----

  /// The style is the whole tab: its groups on the page, not one folded row.
  Widget _styleTab(JsonMap doc) => DisplayStyleEditor(
    style: asMap(doc['style']),
    path: r'$.style',
    diagnostics: _diagnostics,
    note: 'Applies to every line. Default: billboard center, see-through.',
    whole: true,
    onChanged: (JsonMap? next, String field) => _write(const <JsonStep>['style'], next, coalesce: 'style.$field'),
  );

  Widget _clicksTab(JsonMap doc) {
    final JsonMap hitbox = asMap(doc['hitbox']);
    void setHitbox(String key, Object? value) {
      final JsonMap next = withField(hitbox, key, value);
      _write(const <JsonStep>['hitbox'], next.isEmpty ? null : next, coalesce: 'hitbox.$key');
    }

    return stack(spacing: 16, <Widget>[
      ActionsEditor(
        actions: asList(doc['actions']),
        path: r'$.actions',
        diagnostics: _diagnostics,
        unavailable: hologramRefusedActions,
        onChanged: (JsonList next) => _write(const <JsonStep>['actions'], next.isEmpty ? null : next),
      ),
      AppSection(
        title: 'Click box',
        open: hitbox.isNotEmpty,
        tip: 'The box a viewer clicks. Navigate with mode page and target next, prev or a page id turns pages.',
        docs: Docs.holograms,
        children: <Widget>[
          _number('Width', r'$.hitbox.width', asDouble(hitbox['width']), (num? v) => setHitbox('width', v), help: 'Blocks, default 1.2', integer: false),
          _number('Line height', r'$.hitbox.height', asDouble(hitbox['height']), (num? v) => setHitbox('height', v), help: 'Blocks, default 0.35', integer: false),
          Field(
            label: 'Per line',
            child: ArcaneToggleSwitch(value: hitbox['perLine'] == true, label: 'One box per line', onChanged: (bool on) => setHitbox('perLine', on ? true : null), size: ComponentSize.sm),
          ),
        ],
      ),
    ]);
  }

  // ---- Pages and variants ----

  List<Widget> _pages(JsonList pages) => <Widget>[
    if (pages.isNotEmpty)
      OrderedRows(
        label: 'Pages',
        deleteNoun: 'page',
        rows: <OrderedRow>[
          for (int index = 0; index < pages.length; index++)
            OrderedRow(
              key: '$index',
              name: 'page ${index + 1}',
              open: _page == index,
              line: RowOpener(
                open: _page == index,
                onToggle: () => setState(() {
                  _page = index;
                  _variant = null;
                  _open = null;
                }),
                children: <Widget>[
                  truncatedText('Page ${asString(asMap(pages[index])['id']) ?? index + 1}'),
                ],
                body: _page == index
                    ? stack(<Widget>[
                        guiFieldRow(
                          'Name',
                          '\$.pages[$index].id',
                          _diagnostics,
                          (String id) => AutocompleteInput(
                            id: id,
                            value: asString(asMap(pages[index])['id']) ?? '',
                            label: 'Page name',
                            onChanged: (String v) => _write(<JsonStep>['pages', index, 'id'], v.isEmpty ? null : v),
                            profile: FieldProfile.none,
                          ),
                        ),
                        guiFieldRow(
                          'Show when',
                          '\$.pages[$index].show',
                          _diagnostics,
                          help: 'Hidden pages are skipped.',
                          (String id) => AutocompleteInput(
                            id: id,
                            value: conditionText(asMap(pages[index])['show']),
                            profile: FieldProfile.guiExpression,
                            label: 'Page show when',
                            onChanged: (String v) => _write(<JsonStep>['pages', index, 'show'], conditionValue(v)),
                          ),
                        ),
                      ])
                    : null,
              ),
            ),
        ],
        onMove: (int from, int to) {
          _write(const <JsonStep>['pages'], moveItem(pages, from, to));
          if (_page == from) setState(() => _page = to);
        },
        onDelete: (int index) {
          final int remaining = pages.length - 1;
          setState(() => _page = remaining == 0 ? null : (index >= remaining ? remaining - 1 : index));
          _write(const <JsonStep>['pages'], remaining == 0 ? null : (<Object?>[...pages]..removeAt(index)));
        },
      ),
    ActionRow(
      dense: true,
      label: 'Pages',
      secondary: <RowAction>[
        RowAction(
          'Add page',
          pages.length >= 64
              ? null
              : () {
                  final JsonList base = asList(_doc['lines']);
                  Object? next = writeIn(
                    _doc,
                    const <JsonStep>['pages'],
                    <Object?>[
                      ...pages,
                      <String, Object?>{
                        'id': 'page${pages.length + 1}',
                        'lines': pages.isEmpty && base.isNotEmpty ? deepCopy(base) : <Object?>['&fPage ${pages.length + 1}'],
                      },
                    ],
                  );
                  // Core refuses lines and pages together; the first page takes the lines.
                  if (pages.isEmpty) next = writeIn(next, const <JsonStep>['lines'], null);
                  component.onJson(writeAt(_doc, const <JsonStep>[], next));
                  setState(() {
                    _page = pages.length;
                    _variant = null;
                  });
                },
          icon: ArcaneIcon.plus(),
        ),
      ],
    ),
  ];

  List<Widget> _variants(JsonList variants) => <Widget>[
    if (variants.isNotEmpty)
      OrderedRows(
        label: 'Variants',
        deleteNoun: 'variant',
        rows: <OrderedRow>[
          for (int index = 0; index < variants.length; index++)
            OrderedRow(
              key: '$index',
              name: 'variant ${index + 1}',
              open: _variant == index,
              line: RowOpener(
                open: _variant == index,
                onToggle: () => setState(() {
                  _variant = _variant == index ? null : index;
                  _page = null;
                  _open = null;
                }),
                children: <Widget>[
                  truncatedText('Variant ${asString(asMap(variants[index])['id']) ?? index + 1}'),
                ],
                body: _variant == index ? _variantBody(variants, index) : null,
              ),
            ),
        ],
        onDelete: (int index) {
          final int remaining = variants.length - 1;
          setState(() => _variant = null);
          _write(const <JsonStep>['variants'], remaining == 0 ? null : (<Object?>[...variants]..removeAt(index)));
        },
      ),
    ActionRow(
      dense: true,
      label: 'Variants',
      secondary: <RowAction>[
        RowAction(
          'Add variant',
          variants.length >= 64
              ? null
              : () {
                  _write(
                    const <JsonStep>['variants'],
                    <Object?>[
                      ...variants,
                      <String, Object?>{
                        'id': 'variant${variants.length + 1}',
                        'priority': 0,
                        'when': 'time.hour >= 20',
                        'presentation': <String, Object?>{'lines': deepCopy(asList(_doc['lines']))},
                      },
                    ],
                  );
                  setState(() {
                    _variant = variants.length;
                    _page = null;
                  });
                },
          icon: ArcaneIcon.plus(),
        ),
      ],
    ),
  ];

  Widget _variantBody(JsonList variants, int index) {
    final JsonMap variant = asMap(variants[index]);
    final String path = '\$.variants[$index]';
    return stack(<Widget>[
      guiFieldRow(
        'Name',
        '$path.id',
        _diagnostics,
        (String id) => AutocompleteInput(id: id, value: asString(variant['id']) ?? '', label: 'Variant name', onChanged: (String v) => _write(<JsonStep>['variants', index, 'id'], v), profile: FieldProfile.none),
      ),
      guiFieldRow(
        'When',
        '$path.when',
        _diagnostics,
        (String id) =>
            AutocompleteInput(id: id, value: asString(variant['when']) ?? '', profile: FieldProfile.guiExpression, label: 'When', onChanged: (String v) => _write(<JsonStep>['variants', index, 'when'], v)),
      ),
      _number('Priority', '$path.priority', asInt(variant['priority']), (num? v) => _write(<JsonStep>['variants', index, 'priority'], v), help: 'Higher wins'),
      DisplayStyleEditor(
        style: asMap(asMap(variant['presentation'])['style']),
        path: '$path.presentation.style',
        diagnostics: _diagnostics,
        note: 'Replaces the hologram style while shown.',
        onChanged: (JsonMap? next, String field) => _write(<JsonStep>['variants', index, 'presentation', 'style'], next, coalesce: 'variants.$index.style.$field'),
      ),
    ]);
  }
}
