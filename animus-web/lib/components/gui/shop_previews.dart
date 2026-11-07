/// Both windows a shop can open in, tabbed: the villager trading screen and
/// the chest window, drawn from the document as it is now. The chest
/// window shows the shop's own pages and modes, or the chest GUI its
/// `layout` names with the offers in its list area. Clicking an offer in
/// either picks it on the board. Two switches show offers as a player whose
/// limits ran out, or who lacks the offers' permissions, would see them.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_layout.dart';
import '../../logic/gui/shop_board.dart';
import '../../logic/gui/shop_checks.dart';
import '../../logic/gui/shop_layout.dart';
import '../../logic/gui/shop_offers.dart';
import '../../model/animus_document.dart';
import 'chest_window.dart';
import 'gui_fields.dart';
import 'merchant_window.dart';

const Map<String, String> _modeLabels = <String, String>{'buy': 'Buy', 'sell': 'Sell', 'trade': 'Trade'};

class ShopPreviews extends StatefulWidget {
  const ShopPreviews({required this.document, this.layouts = const <String, JsonMap>{}, this.selected, this.onSelect, super.key});

  final AnimusDocument document;

  /// The workspace's chest GUIs by id, for the `layout` the shop names.
  final Map<String, JsonMap> layouts;
  final OfferRef? selected;

  /// Picks an offer clicked in a window; null makes the windows read-only.
  final void Function(OfferRef ref)? onSelect;

  @override
  State<ShopPreviews> createState() => _ShopPreviewsState();
}

class _ShopPreviewsState extends State<ShopPreviews> {
  int? _tab;
  int _page = 0;
  String? _mode;
  int _listPage = 0;
  bool _usedUp = false;
  bool _noPermission = false;

  JsonMap get _shop => asMap(component.document.json);

  @override
  void didUpdateWidget(ShopPreviews oldComponent) {
    super.didUpdateWidget(oldComponent);
    final OfferRef? selected = component.selected;
    if (selected == null || selected == oldComponent.selected) return;
    // The chest window follows the board so the picked offer is in view.
    _page = selected.page;
    _mode = selected.grid;
    final int index = layoutOffers(_shop).indexWhere((BoardOffer offer) => offer.ref == selected);
    final JsonMap? gui = _layoutGui;
    if (gui != null && index >= 0) {
      final int size = layoutChest(gui, listEntries: 0).cells.where((ChestCell cell) => cell.maskChar == asString(asMap(gui['list'])['area'])).length;
      if (size > 0) _listPage = index ~/ size;
    }
  }

  /// The chest GUI the shop's layout names, when the workspace has it with
  /// a list area.
  JsonMap? get _layoutGui {
    final String? id = asString(_shop['layout']);
    final JsonMap? gui = id == null ? null : component.layouts[id];
    return gui != null && layoutHasList(gui) ? gui : null;
  }

  Set<String> get _crossed => <String>{
        for (final BoardOffer offer in boardOffers(_shop))
          if ((_usedUp && (asMap(offer.entry['stock']).isNotEmpty || asMap(offer.entry['limit']).isNotEmpty)) || (_noPermission && asString(offer.entry['permission']) != null)) offer.ref.key,
      };

  void _pick(OfferRef? ref) {
    if (ref != null) component.onSelect?.call(ref);
  }

  @override
  Widget build(BuildContext context) {
    final JsonMap shop = _shop;
    final String style = effectiveShopStyle(shop);
    final int tab = _tab ?? (style == 'native' ? 0 : 1);
    final String where = asString(shop['style']) == null ? 'Players get the chest window, the server default.' : (style == 'native' ? 'Players get the trading screen.' : 'Players get the chest window.');
    return dom.div(classes: 'animus-shop-previews', <Widget>[
      dom.div(classes: 'animus-shop-preview-head', <Widget>[
        GuiTabs(
          label: 'Shop windows',
          tabs: <String>['Trading screen${style == 'native' ? ' (in game)' : ''}', 'Chest window${style == 'chest' ? ' (in game)' : ''}'],
          selected: tab,
          onSelect: (int index) => setState(() => _tab = index),
        ),
      ]),
      dom.p(classes: 'animus-muted', <Widget>[Component.text(where)]),
      if (tab == 0) _trading(shop) else _chest(shop),
      dom.div(classes: 'animus-row', <Widget>[
        GuiSwitch(value: _usedUp, label: 'Limits used up', onChanged: (bool on) => setState(() => _usedUp = on)),
        GuiSwitch(value: _noPermission, label: 'No permissions', onChanged: (bool on) => setState(() => _noPermission = on)),
      ]),
    ]);
  }

  Widget _trading(JsonMap shop) {
    final List<ShopOffer> offers = nativeShopOffers(shop);
    return MerchantWindow(
      title: asString(shop['title']) ?? asString(shop['id']) ?? component.document.id,
      offers: offers,
      label: 'Trading screen ${component.document.id}',
      selected: component.selected?.key,
      crossed: _crossed,
      onOffer: component.onSelect == null ? null : (String key) => _pick(OfferRef.parse(key)),
    );
  }

  Widget _chest(JsonMap shop) {
    final JsonMap? gui = _layoutGui;
    final OfferRef? selected = component.selected;
    if (gui != null) {
      final ({ChestLayout layout, int pages}) drawn = layoutShopInGui(gui, shop, page: _listPage);
      final int page = _listPage.clamp(0, drawn.pages - 1);
      final int? slot = selected == null ? null : drawn.layout.cells.where((ChestCell cell) => offerAtCell(cell) == selected).map((ChestCell cell) => cell.slot).firstOrNull;
      return dom.div(classes: 'animus-shop-chest', <Widget>[
        dom.div(classes: 'animus-row', <Widget>[
          dom.span(classes: 'animus-muted', <Widget>[Component.text('In ${asString(shop['layout'])}. Page ${page + 1} of ${drawn.pages}.')]),
          if (drawn.pages > 1) ...<Widget>[
            Button.ghost(label: 'Previous page', size: ButtonSize.small, disabled: page == 0, onPressed: () => setState(() => _listPage = page - 1)),
            Button.ghost(label: 'Next page', size: ButtonSize.small, disabled: page >= drawn.pages - 1, onPressed: () => setState(() => _listPage = page + 1)),
          ],
        ]),
        ChestWindow(
          layout: drawn.layout,
          label: 'Chest window ${component.document.id}',
          selected: slot,
          onSlot: component.onSelect == null ? null : (int at) => _pick(offerAtCell(drawn.layout.cell(at))),
        ),
      ]);
    }
    final JsonList pages = asList(shop['pages']);
    final int page = pages.isEmpty ? 0 : _page.clamp(0, pages.length - 1);
    final List<String> modes = enabledShopModes(shop);
    final String mode = _mode != null && modes.contains(_mode) ? _mode! : (asString(shop['defaultMode']) != null && modes.contains(asString(shop['defaultMode'])) ? asString(shop['defaultMode'])! : modes.first);
    final ChestLayout layout = layoutShop(shop, page: page, mode: mode);
    final int? slot = selected != null && selected.page == page && selected.grid == mode ? selected.slot : null;
    return dom.div(classes: 'animus-shop-chest', <Widget>[
      if (asString(shop['layout']) != null)
        dom.p(classes: 'animus-gui-problem is-info', <Widget>[Component.text('${asString(shop['layout'])} is not a chest GUI with a list area here, so this is the shop\'s own pages.')]),
      dom.div(classes: 'animus-gui-toolbar', <Widget>[
        if (pages.length > 1)
          GuiTabs(
            label: 'Pages',
            tabs: <String>[for (int index = 0; index < pages.length; index++) asString(asMap(pages[index])['name']) ?? 'Page ${index + 1}'],
            selected: page,
            onSelect: (int index) => setState(() => _page = index),
          ),
        if (modes.length > 1)
          GuiTabs(label: 'Modes', tabs: <String>[for (final String one in modes) _modeLabels[one] ?? one], selected: modes.indexOf(mode), onSelect: (int index) => setState(() => _mode = modes[index])),
      ]),
      ChestWindow(
        layout: layout,
        label: 'Chest window ${component.document.id}',
        selected: slot,
        onSlot: component.onSelect == null ? null : (int at) => _pick(offerAtCell(layout.cell(at))),
      ),
    ]);
  }
}

/// The Preview view of a shop: both windows, read-only.
class ShopPreview extends StatelessWidget {
  const ShopPreview({required this.document, this.layouts = const <String, JsonMap>{}, super.key});

  final AnimusDocument document;
  final Map<String, JsonMap> layouts;

  @override
  Widget build(BuildContext context) => dom.section(classes: 'animus-panel animus-gui-preview', <Widget>[
        ShopPreviews(document: document, layouts: layouts),
      ]);
}
