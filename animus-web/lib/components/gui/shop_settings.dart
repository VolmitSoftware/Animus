/// The shop's Settings tab. Simple: the title (codes editable, shown in
/// colour under the field), a chest shop's kind, its owner by name and its
/// chests, whether the shop is open or closed and why, and the NPCs that
/// open the shop. Advanced adds the window and layout, who may open it, the
/// modes in switch order, the purchase switches, item matching, pages, the
/// owner's UUID and name, and the chests of any shop.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../config/docs_links.dart';
import '../../logic/diagnostic_text.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_validation.dart' show shopModes;
import '../../logic/gui/shop_entries.dart';
import '../../logic/gui/shop_words.dart';
import '../../logic/plural.dart';
import '../../logic/suggest/completion_sources.dart';
import '../../logic/suggest/field_profile.dart';
import '../../logic/suggest/field_profiles.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/mode.dart';
import '../../ui/section.dart';
import '../conversation/conversation_attach.dart' show ConversationCommit;
import 'component_editor.dart' show withField;
import 'gui_fields.dart';
import '../../ui/menu.dart';
import 'gui_rows.dart';
import 'shop_links.dart';
import 'shop_offer_editor.dart' show ShopWrite;
import '../select/animus_select.dart';
import '../suggest/autocomplete_input.dart';
import '../../ui/field.dart';
import '../../ui/number_input.dart';
import 'gui_stack.dart';
import '../../ui/stack.dart';
import '../../ui/toggle_choice.dart';
import '../../logic/suggest/person_names.dart';
import '../select/person_picker.dart';
import '../suggest/suggest_scope.dart';

/// The reason a shop closed here keeps until its owner writes their own.
const String editorClosedReason = 'Closed from the web editor';

class ShopSettings extends StatefulWidget {
  const ShopSettings({
    required this.shop,
    required this.shopId,
    required this.diagnostics,
    required this.layouts,
    required this.onWrite,
    this.documents = const <AnimusDocument>[],
    this.onCommit,
    this.onOpen,
    super.key,
  });

  final JsonMap shop;
  final String shopId;
  final List<Diagnostic> diagnostics;

  /// Chest GUI ids of the workspace.
  final List<String> layouts;
  final ShopWrite onWrite;

  /// The workspace, for the NPCs that open the shop.
  final List<AnimusDocument> documents;

  /// Writes NPC documents as one undoable change; null hides attaching.
  final ConversationCommit? onCommit;
  final void Function(DocumentKind kind, String id)? onOpen;

  /// Whether this tab shows the problem at [path] at its field.
  static bool shows(String path) =>
      RegExp(r'^\$\.(title|style|layout|permission|modes|defaultMode|match|confirm|log|closeAfterPurchase|kind|closed|owner|containers|pages(\[\d+\]\.(name|rows|permission))?$)').hasMatch(path) ||
      RegExp(r'^\$\.(owner|containers)').hasMatch(path);

  @override
  State<ShopSettings> createState() => _ShopSettingsState();
}

class _ShopSettingsState extends State<ShopSettings> {
  int? _openPage;

  JsonMap get _shop => component.shop;
  List<Diagnostic> get _diagnostics => component.diagnostics;

  void _set(String key, Object? value) => component.onWrite(<JsonStep>[key], value, coalesce: 'shop.$key');

  Widget _row(String label, String key, Widget Function(String id) child, {String? tip, DocLink? docs, String? hint}) {
    final String path = key.split('.').fold(r'$', childPath);
    final String id = guiPathId(path);
    return Field(label: label, forId: id, tip: tip, docs: docs, hint: hint, problems: diagnosticsAt(_diagnostics, path, nested: true), child: child(id));
  }

  @override
  Widget build(BuildContext context) {
    final bool advanced = ModeScope.advanced(context);
    final bool container = isContainerShop(_shop);
    final Map<String, String> owners = SuggestScope.sourcesOf(context).players;
    return stack(spacing: 16, <Widget>[
      _row(
        'Title',
        'title',
        (String id) => AutocompleteInput(
          id: id,
          value: asString(_shop['title']) ?? '',
          profile: FieldProfile.guiText,
          preview: windowTitleSample,
          label: 'Shop title',
          placeholder: '&6Bakery',
          onChanged: (String v) => _set('title', v.isEmpty ? null : v),
        ),
        tip: 'The window title. Colour codes such as &6 work; the line under the field shows the result.',
      ),
      if (container || _shop.containsKey('kind')) _kind(),
      if (!advanced) _ownerPicker(owners),
      if (!advanced && container) _containers(),
      ..._status(),
      ShopLinks(shop: component.shopId, documents: component.documents, onCommit: component.onCommit, onOpen: component.onOpen),
      if (advanced) ...<Widget>[
        groupHeading('Window'),
        _row(
          'Opens as',
          'style',
          (String id) => AnimusSelect.values(
            id: id,
            value: asString(_shop['style']),
            values: const <String>['native', 'chest'],
            labels: const <String, String>{'native': 'Trading screen', 'chest': 'Chest window'},
            allowNone: true,
            noneLabel: 'Server default',
            onChanged: (String? v) => _set('style', v),
          ),
          tip: 'Server default is the chest window unless config.toml says otherwise. An NPC can override it.',
        ),
        _row(
          'Layout',
          'layout',
          (String id) => AnimusSelect.values(
            id: id,
            references: DocumentKind.inventory,
            value: asString(_shop['layout']),
            values: component.layouts,
            allowNone: true,
            noneLabel: "The shop's own pages",
            onChanged: (String? v) => _set('layout', v),
          ),
          tip: 'A chest GUI whose list area shows the offers. Chest window only.',
        ),
        _modes(),
        _row('First mode', 'defaultMode', (String id) {
          final List<String> modes = stringList(_shop['modes']);
          return AnimusSelect.values(
            id: id,
            value: asString(_shop['defaultMode']),
            values: modes.isEmpty ? shopModes : modes,
            allowNone: true,
            noneLabel: 'First in the list',
            labels: shopModeLabels,
            onChanged: (String? v) => _set('defaultMode', v),
          );
        }, tip: 'The mode the chest window opens on.'),
        groupHeading('Rules'),
        _row(
          'Permission',
          'permission',
          (String id) => AutocompleteInput(
            id: id,
            value: asString(_shop['permission']) ?? '',
            label: 'Shop permission',
            onChanged: (String v) => _set('permission', v.trim().isEmpty ? null : v.trim()),
            profile: FieldProfile.none,
          ),
          tip: 'Needed to open the shop. Empty lets everyone in.',
        ),
        Field(
          label: 'Purchases',
          tip: 'Confirm asks before each purchase. Log writes each one to logs/shops.',
          child: switchRow(<Widget>[
            ArcaneToggleSwitch(value: _shop['confirm'] == true, label: 'Confirm each', onChanged: (bool on) => _set('confirm', on ? true : null), size: ComponentSize.sm),
            ArcaneToggleSwitch(value: _shop['closeAfterPurchase'] == true, label: 'Close after', onChanged: (bool on) => _set('closeAfterPurchase', on ? true : null), size: ComponentSize.sm),
            ArcaneToggleSwitch(value: _shop['log'] == true, label: 'Log', onChanged: (bool on) => _set('log', on ? true : null), size: ComponentSize.sm),
          ]),
        ),
        _row(
          'Item matching',
          'match',
          (String id) => AnimusSelect.values(
            id: id,
            value: asString(_shop['match']),
            values: const <String>['strict', 'legacy'],
            labels: const <String, String>{'strict': 'Exact', 'legacy': 'Loose (imported shops)'},
            allowNone: true,
            noneLabel: 'Exact',
            onChanged: (String? v) => _set('match', v),
          ),
          tip: 'Exact compares all item data. Loose ignores data that imported shops never set.',
        ),
        _pages(),
        _owner(owners),
        _containers(),
      ],
    ]);
  }

  /// A chest shop's kind, as its owner picks it in game. Trades follow it:
  /// in a book shop they copy a book and give no items.
  Widget _kind() => _row(
        'Kind',
        'kind',
        (String id) => AnimusSelect.values(
          id: id,
          value: asString(_shop['kind']),
          values: shopKinds,
          labels: shopKindLabels,
          details: shopKindLines,
          allowNone: true,
          noneLabel: 'Not set',
          onChanged: (String? v) => component.onWrite(const <JsonStep>[], withShopKind(_shop, v), coalesce: 'shop.kind'),
        ),
        tip: 'What the shop does with its chest. Changing it rewrites the trades to fit.',
        docs: Docs.playerShops,
      );

  /// The owner picked by name; Simple never shows their UUID.
  Widget _ownerPicker(Map<String, String> players) => Field(
        label: 'Owner',
        forId: 'animus-shop-owner',
        tip: 'The player who runs this shop in game and whose chests hold the stock. None for a server shop.',
        docs: Docs.playerShops,
        problems: diagnosticsAt(_diagnostics, r'$.owner', nested: true),
        child: _ownerControl('animus-shop-owner', players),
      );

  Widget _ownerControl(String id, Map<String, String> players) {
    final JsonMap owner = asMap(_shop['owner']);
    final String? uuid = asString(owner['uuid']);
    return PersonPicker(
      id: id,
      kind: PersonKind.player,
      label: 'Owner',
      value: uuid,
      knownName: asString(owner['name']),
      onChanged: (String? picked) {
        // The name rides along for the shop's messages; a UUID typed in keeps none.
        final String? name = picked == null ? null : players[picked] ?? (picked == uuid ? asString(owner['name']) : null);
        component.onWrite(const <JsonStep>['owner'], picked == null ? null : <String, Object?>{'uuid': picked, 'name': ?name}, coalesce: 'owner.uuid');
      },
    );
  }

  /// Open or closed, on any shop; a closed shop keeps its reason.
  List<Widget> _status() {
    final bool closed = _shop.containsKey('closed');
    return <Widget>[
      Field(
        label: 'Status',
        tip: 'A closed shop trades nothing, and players who open it are told it is closed.',
        docs: Docs.playerShops,
        child: ToggleChoice<bool>(
          label: 'Status',
          value: closed,
          options: const <ChoiceOption<bool>>[ChoiceOption<bool>(false, 'Open'), ChoiceOption<bool>(true, 'Closed')],
          onChanged: (bool close) {
            if (close != closed) component.onWrite(const <JsonStep>['closed'], close ? editorClosedReason : null, coalesce: 'shop.status');
          },
        ),
      ),
      if (closed)
        _row(
          'Reason',
          'closed',
          (String id) => AutocompleteInput(id: id, value: asString(_shop['closed']) ?? '', label: 'Closed reason', onChanged: (String v) => _set('closed', v), profile: FieldProfile.none),
          tip: 'Why the shop is closed, for its owner. Opening the shop clears it.',
        ),
    ];
  }

  /// The modes players switch between, in order: on modes first, by drag
  /// or the row menu; a switch turns one on or off.
  Widget _modes() {
    final List<String> modes = stringList(_shop['modes']);
    final List<String> on = modes.isEmpty ? shopModes : modes;
    final List<String> rows = <String>[
      ...on,
      for (final String mode in shopModes)
        if (!on.contains(mode)) mode,
    ];
    void setModes(List<String> next) => _set('modes', next.isEmpty ? null : next);
    return Field(
      label: 'Modes',
      tip: 'Which grids players can switch to, in this order.',
      problems: diagnosticsAt(_diagnostics, r'$.modes', nested: true),
      child: OrderedRows(
        label: 'Modes',
        rows: <OrderedRow>[
          for (final String mode in rows)
            OrderedRow(
              key: mode,
              name: '${shopModeLabels[mode]} mode',
              line: ArcaneToggleSwitch(value: on.contains(mode), label: shopModeLabels[mode]!, onChanged: (bool turnOn) => setModes(turnOn ? <String>[...on, mode] : (<String>[...on]..remove(mode))), size: ComponentSize.sm),
            ),
        ],
        onMove: (int from, int to) {
          if (from >= on.length || to >= on.length) return;
          setModes(<String>[for (final Object? one in moveItem(on, from, to)) one! as String]);
        },
      ),
    );
  }

  Widget _pages() {
    final JsonList pages = asList(_shop['pages']);
    void setAll(JsonList next) => component.onWrite(const <JsonStep>['pages'], next, coalesce: 'pages-structure');
    return AppSection(
      title: 'Pages',
      count: pages.length,
      open: pages.length > 1,
      children: <Widget>[
        stack(<Widget>[
          for (final Diagnostic problem in diagnosticsAt(_diagnostics, r'$.pages')) AppAlert(tone: AlertTone.warning, message: diagnosticSentence(problem)),
          OrderedRows(
            label: 'Pages',
            deleteNoun: 'page',
            rows: <OrderedRow>[for (int index = 0; index < pages.length; index++) _page(pages, index)],
            onMove: (int from, int to) => setAll(moveItem(pages, from, to)),
            onDelete: pages.length <= 1 ? null : (int index) => setAll(<Object?>[...pages]..removeAt(index)),
          ),
          ActionRow(
            dense: true,
            label: 'Pages',
            secondary: <RowAction>[
              RowAction(
                'Add page',
                pages.length >= 64
                    ? null
                    : () => setAll(<Object?>[
                        ...pages,
                        <String, Object?>{'name': 'Page ${pages.length + 1}', 'rows': 3},
                      ]),
                icon: ArcaneIcon.plus(),
              ),
            ],
          ),
        ]),
      ],
    );
  }

  OrderedRow _page(JsonList pages, int index) {
    final JsonMap page = asMap(pages[index]);
    final String path = indexPath(r'$.pages', index);
    final int rows = asInt(page['rows']) ?? 3;
    final List<String> outside = shopEntriesOutside(page, rows);
    final String name = asString(page['name']) ?? 'Page ${index + 1}';
    final bool open = _openPage == index;
    void set(String key, Object? value) => component.onWrite(<JsonStep>['pages', index, key], value, coalesce: 'pages.$index.$key');
    return OrderedRow(
      key: '$index',
      name: name,
      open: open,
      attributes: <String, String>{'data-path': path},
      line: RowOpener(
        open: open,
        label: '$name, ${countOf(rows, 'row')}',
        onToggle: () => setState(() => _openPage = open ? null : index),
        children: <Widget>[
          truncatedText(name),
          mutedText(countOf(rows, 'row')),
        ],
        body: stack(spacing: 16, <Widget>[
          Field(
            label: 'Name',
            forId: guiPathId('$path.name'),
            child: AutocompleteInput(
              id: guiPathId('$path.name'),
              value: asString(page['name']) ?? '',
              label: 'Page ${index + 1} name',
              placeholder: 'Page ${index + 1}',
              onChanged: (String v) => set('name', v.isEmpty ? null : v),
              profile: FieldProfile.none,
            ),
          ),
          Field(
            label: 'Rows',
            forId: guiPathId('$path.rows'),
            child: AnimusSelect.values(
              id: guiPathId('$path.rows'),
              value: '$rows',
              values: const <String>['1', '2', '3', '4', '5', '6'],
              labels: const <String, String>{'1': '1 row', '2': '2 rows', '3': '3 rows', '4': '4 rows', '5': '5 rows', '6': '6 rows'},
              onChanged: (String? v) => set('rows', v == null ? null : int.parse(v)),
            ),
          ),
          Field(
            label: 'Permission',
            forId: guiPathId('$path.permission'),
            tip: 'Players without it skip this page.',
            problems: diagnosticsAt(_diagnostics, '$path.permission'),
            child: AutocompleteInput(
              id: guiPathId('$path.permission'),
              value: asString(page['permission']) ?? '',
              label: '$name permission',
              onChanged: (String v) => set('permission', v.trim().isEmpty ? null : v.trim()),
              profile: FieldProfile.none,
            ),
          ),
          if (outside.isNotEmpty)
            AppAlert(
              tone: AlertTone.warning,
              message: '${outside.length} ${outside.length == 1 ? 'offer is' : 'offers are'} below row $rows (${outside.join(', ')}). Add rows or move ${outside.length == 1 ? 'it' : 'them'}.',
            ),
        ]),
      ),
      entries: <AppMenuEntry>[AppMenuEntry(open ? 'Close' : 'Edit', () => setState(() => _openPage = open ? null : index))],
    );
  }

  Widget _owner(Map<String, String> owners) {
    final JsonMap owner = asMap(_shop['owner']);
    final bool container = isContainerShop(_shop);
    void set(String key, String value) {
      final JsonMap next = withField(owner, key, value.trim());
      component.onWrite(const <JsonStep>['owner'], next.isEmpty ? null : next, coalesce: 'owner.$key');
    }

    return AppSection(
      title: 'Owner',
      open: owner.isNotEmpty || container,
      tip: 'The player who owns the chests and gets the earnings. A chest shop needs one.',
      children: <Widget>[
        _row('Player', 'owner.uuid', (String id) => _ownerControl(id, owners), tip: 'Picked by name from the players the server has seen, or a UUID typed in.'),
        _row('Name', 'owner.name', (String id) => AutocompleteInput(id: id, value: asString(owner['name']) ?? '', label: 'Owner name', onChanged: (String v) => set('name', v), profile: FieldProfile.none)),
        for (final Diagnostic problem in diagnosticsAt(_diagnostics, r'$.owner')) AppAlert(tone: AlertTone.warning, message: diagnosticSentence(problem)),
      ],
    );
  }

  /// The chests that hold a chest shop's stock, each with its world and
  /// block position labelled. The first chest makes a shop a chest shop.
  Widget _containers() {
    final JsonList containers = asList(_shop['containers']);
    void setAll(JsonList next) => component.onWrite(const <JsonStep>[], withChests(_shop, next), coalesce: 'containers-structure');
    return AppSection(
      title: 'Chests',
      count: containers.length,
      open: containers.isNotEmpty,
      tip: 'The chests that hold the stock and take the earnings. With a chest, offers trade items for items only.',
      docs: Docs.chestShops,
      children: <Widget>[
        stack(<Widget>[
          for (final Diagnostic problem in diagnosticsAt(_diagnostics, r'$.containers')) AppAlert(tone: AlertTone.warning, message: diagnosticSentence(problem)),
          OrderedRows(
            label: 'Chests',
            deleteNoun: 'chest',
            rows: <OrderedRow>[
              for (int index = 0; index < containers.length; index++)
                OrderedRow(
                  key: '$index',
                  name: 'chest ${index + 1}',
                  attributes: <String, String>{'data-path': '\$.containers[$index]'},
                  line: chestLine(
                    id: 'animus-shop-chest-$index',
                    label: 'Chest ${index + 1}',
                    chest: asMap(containers[index]),
                    onChanged: (String key, Object? value) => component.onWrite(<JsonStep>['containers', index, key], value),
                  ),
                  body: null,
                ),
            ],
            onDelete: (int index) => setAll(<Object?>[...containers]..removeAt(index)),
          ),
          for (int index = 0; index < containers.length; index++)
            for (final Diagnostic problem in diagnosticsAt(_diagnostics, '\$.containers[$index]', nested: true))
              AppAlert(tone: AlertTone.warning, message: 'Chest ${index + 1}: ${diagnosticSentence(problem)}'),
          ActionRow(
            dense: true,
            label: 'Chests',
            secondary: <RowAction>[
              RowAction(
                'Add chest',
                containers.length >= 8
                    ? null
                    : () => setAll(<Object?>[
                        ...containers,
                        <String, Object?>{'world': 'world', 'x': 0, 'y': 64, 'z': 0},
                      ]),
                icon: ArcaneIcon.plus(),
              ),
            ],
          ),
        ]),
      ],
    );
  }
}

/// A chest's world and block position as one line of labelled fields:
/// World, X, Y and Z, each with its label above it. [label] names the
/// fields for assistive technology ("Chest 1 world"); [id] prefixes their
/// ids.
Widget chestLine({required String id, required String label, required JsonMap chest, required void Function(String key, Object? value) onChanged}) => equalLine(
      <Widget>[
        stack(spacing: 4, <Widget>[
          dom.label(htmlFor: '$id-world', <Widget>[mutedText('World')]),
          AutocompleteInput(
            id: '$id-world',
            value: asString(chest['world']) ?? '',
            label: '$label world',
            profile: idProfile(const ValueRef.registry(RegistryKind.world)),
            placeholder: 'world',
            onChanged: (String v) => onChanged('world', v.isEmpty ? null : v),
          ),
        ]),
        for (final String axis in const <String>['x', 'y', 'z'])
          stack(spacing: 4, <Widget>[
            dom.label(htmlFor: '$id-$axis', <Widget>[mutedText(axis.toUpperCase())]),
            NumberInput(id: '$id-$axis', value: asInt(chest[axis]), label: '$label $axis', digits: 8, onChanged: (num? v) => onChanged(axis, v?.toInt())),
          ]),
      ],
      flex: const <int>[3, 2, 2, 2],
    );
