/// The shop's own fields: title with its coloured rendering, the window
/// style and layout GUI, who may open it, modes in switch order, purchase
/// switches, pages, and the owner and containers of a player's shop.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_validation.dart' show shopModes;
import '../../logic/gui/shop_entries.dart';
import '../../logic/suggest/completion_sources.dart';
import '../../logic/suggest/field_profile.dart';
import '../../logic/suggest/field_profiles.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import 'component_editor.dart' show withField;
import 'gui_fields.dart';
import 'shop_offer_editor.dart' show ShopWrite;

const Map<String, String> shopModeLabels = <String, String>{'buy': 'Buy', 'sell': 'Sell', 'trade': 'Trade'};

class ShopSettings extends StatelessWidget {
  const ShopSettings({required this.shop, required this.diagnostics, required this.layouts, required this.onWrite, super.key});

  final JsonMap shop;
  final List<Diagnostic> diagnostics;

  /// Chest GUI ids of the workspace.
  final List<String> layouts;
  final ShopWrite onWrite;

  void _set(String key, Object? value) => onWrite(<JsonStep>[key], value, coalesce: 'shop.$key');

  Widget _row(String label, String key, Widget Function(String id) child, {String? tip}) {
    final String path = key.split('.').fold(r'$', childPath);
    final String id = guiPathId(path);
    return GuiRow(label: label, forId: id, tip: tip, problems: diagnosticsAt(diagnostics, path, nested: true), child: child(id));
  }

  @override
  Widget build(BuildContext context) {
    final List<String> modes = stringList(shop['modes']);
    final List<String> ordered = modes.isEmpty ? shopModes : modes;
    void setModes(List<String> next) => _set('modes', next.isEmpty ? null : next);
    return dom.div(classes: 'animus-shop-settings-body', <Widget>[
      _row('Title', 'title', (String id) => GuiTextInput(id: id, value: asString(shop['title']) ?? '', mode: GuiTextMode.template, label: 'Shop title', placeholder: '<gold>Bakery', onChanged: (String v) => _set('title', v.isEmpty ? null : v)),
          tip: 'The window title. & codes and MiniMessage work; the line under it shows the colours.'),
      _row('Window', 'style', (String id) => GuiSelect(
            id: id,
            hiddenLabel: 'Window',
            value: asString(shop['style']),
            options: const <String>['native', 'chest'],
            labels: const <String, String>{'native': 'Trading screen', 'chest': 'Chest window'},
            allowNone: true,
            noneLabel: 'Server default',
            onChanged: (String? v) => _set('style', v),
          ), tip: 'Server default is [shops] style in config.toml, chest unless changed. An NPC can override it.'),
      _row('Layout', 'layout', (String id) => GuiSelect(
            id: id,
            hiddenLabel: 'Layout',
            references: DocumentKind.inventory,
            value: asString(shop['layout']),
            options: layouts,
            allowNone: true,
            noneLabel: "The shop's own pages",
            onChanged: (String? v) => _set('layout', v),
          ), tip: 'A chest GUI whose list area shows the offers. Chest window only.'),
      _row('Permission', 'permission', (String id) => GuiTextInput(id: id, value: asString(shop['permission']) ?? '', label: 'Shop permission', placeholder: 'town.shop.baker', onChanged: (String v) => _set('permission', v.trim().isEmpty ? null : v.trim())),
          tip: 'Needed to open the shop. Empty lets everyone in.'),
      GuiRow(
        label: 'Modes',
        tip: 'Which grids players can switch to, in order. None ticked means all three.',
        problems: diagnosticsAt(diagnostics, r'$.modes', nested: true),
        child: dom.div(classes: 'animus-gui-lines', <Widget>[
          for (int index = 0; index < ordered.length; index++)
            dom.div(classes: 'animus-gui-line', <Widget>[
              GuiSwitch(
                value: modes.contains(ordered[index]),
                label: shopModeLabels[ordered[index]]!,
                onChanged: (bool on) => setModes(on ? <String>[...modes, ordered[index]] : (<String>[...modes]..remove(ordered[index]))),
              ),
              if (modes.contains(ordered[index]))
                ...guiMoveButtons(
                  index: modes.indexOf(ordered[index]),
                  length: modes.length,
                  noun: '${shopModeLabels[ordered[index]]} mode',
                  onMove: (int to) => setModes(<String>[for (final Object? one in moveItem(modes, modes.indexOf(ordered[index]), to)) one! as String]),
                ),
            ]),
          for (final String mode in shopModes)
            if (!ordered.contains(mode))
              dom.div(classes: 'animus-gui-line', <Widget>[
                GuiSwitch(value: false, label: shopModeLabels[mode]!, onChanged: (bool on) => setModes(<String>[...modes, mode])),
              ]),
        ]),
      ),
      _row('First mode', 'defaultMode', (String id) => GuiSelect(id: id, hiddenLabel: 'First mode', value: asString(shop['defaultMode']), options: modes.isEmpty ? shopModes : modes, allowNone: true, noneLabel: 'First in modes', labels: shopModeLabels, onChanged: (String? v) => _set('defaultMode', v)),
          tip: 'The grid the chest window opens on.'),
      GuiRow(
        label: 'Purchases',
        tip: 'Confirm asks before each purchase. Log writes each one to logs/shops.',
        child: dom.div(classes: 'animus-row', <Widget>[
          GuiSwitch(value: shop['confirm'] == true, label: 'Confirm each', onChanged: (bool on) => _set('confirm', on ? true : null)),
          GuiSwitch(value: shop['closeAfterPurchase'] == true, label: 'Close after', onChanged: (bool on) => _set('closeAfterPurchase', on ? true : null)),
          GuiSwitch(value: shop['log'] == true, label: 'Log', onChanged: (bool on) => _set('log', on ? true : null)),
        ]),
      ),
      _row('Item matching', 'match', (String id) => GuiSelect(id: id, hiddenLabel: 'Item matching', value: asString(shop['match']), options: const <String>['strict', 'legacy'], allowNone: true, noneLabel: 'strict', onChanged: (String? v) => _set('match', v)),
          tip: 'Strict compares all item data. Legacy is for imported shops.'),
      _pages(),
      _owner(),
      _containers(),
    ]);
  }

  Widget _pages() {
    final JsonList pages = asList(shop['pages']);
    void setAll(JsonList next) => onWrite(const <JsonStep>['pages'], next, coalesce: 'pages-structure');
    return GuiSection(title: 'Pages', count: pages.length, open: pages.length > 1, tip: 'The chest window turns pages with the bottom row. The trading screen lists every page.', children: <Widget>[
      for (final Diagnostic problem in diagnosticsAt(diagnostics, r'$.pages')) dom.p(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
      for (int index = 0; index < pages.length; index++) _page(pages, index, setAll),
      Button.ghost(
        label: '+ Add page',
        size: ButtonSize.small,
        disabled: pages.length >= 64,
        onPressed: () => setAll(<Object?>[...pages, <String, Object?>{'name': 'Page ${pages.length + 1}', 'rows': 3}]),
      ),
    ]);
  }

  Widget _page(JsonList pages, int index, void Function(JsonList next) setAll) {
    final JsonMap page = asMap(pages[index]);
    final String path = indexPath(r'$.pages', index);
    final int rows = asInt(page['rows']) ?? 3;
    final List<String> outside = shopEntriesOutside(page, rows);
    final String name = asString(page['name']) ?? 'Page ${index + 1}';
    void set(String key, Object? value) => onWrite(<JsonStep>['pages', index, key], value, coalesce: 'pages.$index.$key');
    return dom.div(classes: 'animus-shop-page', attributes: <String, String>{'data-path': path}, <Widget>[
      dom.div(classes: 'animus-row', <Widget>[
        GuiTextInput(value: asString(page['name']) ?? '', label: 'Page ${index + 1} name', placeholder: 'Page ${index + 1}', onChanged: (String v) => set('name', v.isEmpty ? null : v)),
        GuiSelect(
          value: '$rows',
          options: const <String>['1', '2', '3', '4', '5', '6'],
          labels: const <String, String>{'1': '1 row', '2': '2 rows', '3': '3 rows', '4': '4 rows', '5': '5 rows', '6': '6 rows'},
          hiddenLabel: '$name rows',
          onChanged: (String? v) => set('rows', v == null ? null : int.parse(v)),
        ),
        ...guiMoveButtons(index: index, length: pages.length, noun: name, onMove: (int to) => setAll(moveItem(pages, index, to))),
        GuiIconButton(
          icon: ArcaneIcon.trash(size: IconSize.sm),
          label: 'Remove $name',
          disabled: pages.length <= 1,
          onPressed: () => setAll(<Object?>[...pages]..removeAt(index)),
        ),
      ]),
      GuiRow(
        label: 'Permission',
        forId: guiPathId('$path.permission'),
        tip: 'Players without it skip this page.',
        problems: diagnosticsAt(diagnostics, '$path.permission'),
        child: GuiTextInput(id: guiPathId('$path.permission'), value: asString(page['permission']) ?? '', label: '$name permission', onChanged: (String v) => set('permission', v.trim().isEmpty ? null : v.trim())),
      ),
      if (outside.isNotEmpty)
        dom.p(classes: 'animus-gui-problem is-warning', attributes: const <String, String>{'role': 'alert'}, <Widget>[
          Component.text('${outside.length} ${outside.length == 1 ? 'offer is' : 'offers are'} outside $rows rows (${outside.join(', ')}). Add rows or move ${outside.length == 1 ? 'it' : 'them'}.'),
        ]),
    ]);
  }

  Widget _owner() {
    final JsonMap owner = asMap(shop['owner']);
    final bool container = isContainerShop(shop);
    void set(String key, String value) {
      final JsonMap next = withField(owner, key, value.trim());
      onWrite(const <JsonStep>['owner'], next.isEmpty ? null : next, coalesce: 'owner.$key');
    }

    return GuiSection(title: 'Owner', open: owner.isNotEmpty || container, tip: "A player's shop: they own the stock chests and get the takings. Required with containers.", children: <Widget>[
      _row('UUID', 'owner.uuid', (String id) => GuiTextInput(id: id, value: asString(owner['uuid']) ?? '', label: 'Owner UUID', placeholder: '00000000-0000-0000-0000-000000000000', onChanged: (String v) => set('uuid', v)),
          tip: 'Lowercase with dashes.'),
      _row('Name', 'owner.name', (String id) => GuiTextInput(id: id, value: asString(owner['name']) ?? '', label: 'Owner name', onChanged: (String v) => set('name', v))),
      for (final Diagnostic problem in diagnosticsAt(diagnostics, r'$.owner')) dom.p(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
    ]);
  }

  Widget _containers() {
    final JsonList containers = asList(shop['containers']);
    void setAll(JsonList next) => onWrite(const <JsonStep>['containers'], next.isEmpty ? null : next, coalesce: 'containers-structure');
    return GuiSection(title: 'Containers', count: containers.length, open: containers.isNotEmpty, tip: 'Chests that hold the stock. A shop with containers trades items for items only.', children: <Widget>[
      for (final Diagnostic problem in diagnosticsAt(diagnostics, r'$.containers')) dom.p(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
      for (int index = 0; index < containers.length; index++)
        dom.div(classes: 'animus-row animus-shop-goods', attributes: <String, String>{'data-path': '\$.containers[$index]'}, <Widget>[
          GuiTextInput(
            value: asString(asMap(containers[index])['world']) ?? '',
            label: 'Container ${index + 1} world',
            profile: idProfile(const ValueRef.registry(RegistryKind.world)),
            placeholder: 'world',
            onChanged: (String v) => onWrite(<JsonStep>['containers', index, 'world'], v.isEmpty ? null : v),
          ),
          for (final String axis in const <String>['x', 'y', 'z'])
            GuiNumberInput(
              value: asInt(asMap(containers[index])[axis]),
              label: 'Container ${index + 1} $axis',
              placeholder: axis,
              digits: 8,
              onChanged: (num? v) => onWrite(<JsonStep>['containers', index, axis], v),
            ),
          GuiIconButton(icon: ArcaneIcon.trash(size: IconSize.sm), label: 'Remove container ${index + 1}', onPressed: () => setAll(<Object?>[...containers]..removeAt(index))),
          for (final Diagnostic problem in diagnosticsAt(diagnostics, '\$.containers[$index]', nested: true))
            dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text('${problem.path} ${problem.message}')]),
        ]),
      Button.ghost(
        label: '+ Add container',
        size: ButtonSize.small,
        disabled: containers.length >= 8,
        onPressed: () => setAll(<Object?>[...containers, <String, Object?>{'world': 'world', 'x': 0, 'y': 64, 'z': 0}]),
      ),
    ]);
  }
}
