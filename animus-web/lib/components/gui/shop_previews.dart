/// The shop's preview: the window players get (the villager trading screen
/// or the chest window), drawn from the document as it is now and fitted to
/// the frame's width and height. The chest window shows the shop's own pages
/// and modes, or the chest GUI its `layout` names with the offers in its
/// list area; the trading screen shows a page of offers at a time, as the
/// client does. Clicking an offer picks it in the form. Advanced switches to
/// the other window and shows offers as a player who is sold out or lacks
/// the offers' permissions sees them. There is no camera, so the header has
/// no Reset view or Fullscreen; the rarer choices are in its "..." menu.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_layout.dart';
import '../../logic/gui/shop_board.dart';
import '../../logic/gui/shop_checks.dart';
import '../../logic/gui/shop_layout.dart';
import '../../logic/gui/shop_offers.dart';
import '../../model/animus_document.dart';
import 'chest_window.dart';
import '../../ui/stage_frame.dart';
import '../../ui/mode.dart';
import '../../ui/toggle_choice.dart';
import '../../ui/action_row.dart';
import '../../ui/menu.dart';
import 'merchant_window.dart';
import '../select/animus_select.dart';
import 'gui_stack.dart';

const Map<String, String> _modeLabels = <String, String>{'buy': 'Buy', 'sell': 'Sell', 'trade': 'Trade'};

class ShopPreviews extends StatefulWidget {
  const ShopPreviews({required this.document, this.layouts = const <String, JsonMap>{}, this.selected, this.onSelect, this.band = false, super.key});

  final AnimusDocument document;

  /// The workspace's chest GUIs by id, for the `layout` the shop names.
  final Map<String, JsonMap> layouts;
  final OfferRef? selected;

  /// Picks an offer clicked in a window; null makes the windows read-only.
  final void Function(OfferRef ref)? onSelect;

  /// The preview sits in the band above the form.
  final bool band;

  @override
  State<ShopPreviews> createState() => _ShopPreviewsState();
}

class _ShopPreviewsState extends State<ShopPreviews> {
  String? _window;
  int _page = 0;
  String? _mode;
  int _listPage = 0;
  int _tradePage = 0;
  Set<String> _showAs = <String>{};

  int get _perPage => tradeOffersPerPage(band: component.band);

  JsonMap get _shop => asMap(component.document.json);

  @override
  void didUpdateWidget(ShopPreviews oldComponent) {
    super.didUpdateWidget(oldComponent);
    final OfferRef? selected = component.selected;
    if (selected == null || selected == oldComponent.selected) return;
    // The windows follow the form so the picked offer is in view.
    _page = selected.page;
    _mode = selected.grid;
    _tradePage = tradePageOf(nativeShopOffers(_shop), selected.key, _perPage) ?? _tradePage;
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

  Set<String> get _crossed {
    final bool soldOut = _showAs.contains('sold-out');
    final bool noPermission = _showAs.contains('no-permission');
    return <String>{
      for (final BoardOffer offer in boardOffers(_shop))
        if ((soldOut && (asMap(offer.entry['stock']).isNotEmpty || asMap(offer.entry['limit']).isNotEmpty)) || (noPermission && asString(offer.entry['permission']) != null)) offer.ref.key,
    };
  }

  void _pick(OfferRef? ref) {
    if (ref != null) component.onSelect?.call(ref);
  }

  /// The mode the chest window shows: the picked one, else the shop's
  /// default, else the first it offers.
  String _shownMode(JsonMap shop, List<String> modes) {
    if (_mode != null && modes.contains(_mode)) return _mode!;
    final String? first = asString(shop['defaultMode']);
    return first != null && modes.contains(first) ? first : modes.first;
  }

  @override
  Widget build(BuildContext context) {
    final JsonMap shop = _shop;
    final bool advanced = ModeScope.advanced(context);
    final String style = effectiveShopStyle(shop);
    final String window = advanced ? (_window ?? style) : style;
    final String where = style == 'native' ? 'Players get the trading screen.' : 'Players get the chest window.';
    // The shop's own modes, in the header, when the chest window draws them.
    final JsonMap? gui = window == 'native' ? null : _layoutGui;
    final List<String> modes = window == 'native' || gui != null ? const <String>[] : enabledShopModes(shop);
    final String mode = modes.isEmpty ? '' : _shownMode(shop, modes);
    final JsonList pages = window == 'native' || gui != null ? const <Object?>[] : asList(shop['pages']);
    final int page = pages.isEmpty ? 0 : _page.clamp(0, pages.length - 1);
    final ({ChestLayout layout, int pages})? drawn = gui == null ? null : layoutShopInGui(gui, shop, page: _listPage);
    final ({List<ShopOffer> offers, int page, int pages})? trades = window == 'native' ? tradePage(nativeShopOffers(shop), page: _tradePage, size: _perPage) : null;
    // One pager for whichever window has more than one page of offers.
    final int windowPages = trades?.pages ?? drawn?.pages ?? 1;
    final int shown = trades?.page ?? _listPage.clamp(0, windowPages - 1);
    void turn(int to) => setState(() {
          if (trades != null) {
            _tradePage = to;
          } else {
            _listPage = to;
          }
        });
    final String status = window == style ? where : 'Players get the ${style == 'native' ? 'trading screen' : 'chest window'}, not this one.';
    return StageFrame(
      title: 'Preview',
      free: true,
      fullscreen: false,
      collapsible: component.band,
      toggles: <Widget>[
        if (modes.length > 1)
          ToggleChoice<String>(
            label: 'Mode shown',
            options: <ChoiceOption<String>>[for (final String one in modes) ChoiceOption<String>(one, _modeLabels[one] ?? one)],
            value: mode,
            onChanged: (String next) => setState(() => _mode = next),
          ),
        if (pages.length > 1)
          AnimusSelect.values(
            value: '$page',
            hiddenLabel: 'Page shown',
            values: <String>[for (int index = 0; index < pages.length; index++) '$index'],
            labels: <String, String>{for (int index = 0; index < pages.length; index++) '$index': asString(asMap(pages[index])['name']) ?? 'Page ${index + 1}'},
            onChanged: (String? next) => setState(() => _page = int.tryParse(next ?? '') ?? 0),
          ),
      ],
      // The other window and what a player in a special case sees: rarer,
      // so in the header's menu, which keeps the header one line on phones.
      // A check marks what is shown.
      more: <AppMenuEntry>[
        if (advanced) ...<AppMenuEntry>[
          _choice('Trading screen', window == 'native', () => setState(() => _window = 'native')),
          _choice('Chest window', window == 'chest', () => setState(() => _window = 'chest')),
          const AppMenuEntry.separator(),
          _choice('Sold out', _showAs.contains('sold-out'), () => _flip('sold-out'), description: 'Offers with stock or a limit show used up'),
          _choice('Missing permission', _showAs.contains('no-permission'), () => _flip('no-permission'), description: 'Offers that need one show locked'),
        ],
      ],
      actions: <RowAction>[
        if (windowPages > 1) ...<RowAction>[
          RowAction('Previous page', shown == 0 ? null : () => turn(shown - 1), icon: ArcaneIcon.chevronLeft(), iconOnly: true),
          RowAction('Next page', shown >= windowPages - 1 ? null : () => turn(shown + 1), icon: ArcaneIcon.chevronRight(), iconOnly: true),
        ],
      ],
      status: windowPages > 1 ? 'Page ${shown + 1} of $windowPages. $status' : status,
      statusTip: asString(shop['style']) == null ? 'The server default, unless config.toml says otherwise. An NPC can override it.' : 'Set in Settings, Opens as.',
      child: previewPane(<Widget>[
        if (trades != null)
          _trading(shop, trades.offers)
        else if (drawn != null)
          _layoutWindow(_shownAs(drawn.layout))
        else
          _chest(shop, page, mode),
      ]),
    );
  }

  /// A menu row for a view that is on or off, checked while on.
  static AppMenuEntry _choice(String label, bool on, void Function() pick, {String? description}) =>
      AppMenuEntry(label, pick, description: description, icon: on ? ArcaneIcon.check() : null);

  /// [layout] as the player the menu picked sees it.
  ChestLayout _shownAs(ChestLayout layout) => shopShownAs(layout, _shop, soldOut: _showAs.contains('sold-out'), noPermission: _showAs.contains('no-permission'));

  /// Shows offers as a player who is sold out or lacks their permission.
  void _flip(String showAs) => setState(() {
        final Set<String> next = <String>{..._showAs};
        if (!next.remove(showAs)) next.add(showAs);
        _showAs = next;
      });

  Widget _trading(JsonMap shop, List<ShopOffer> offers) {
    return MerchantWindow(
      title: asString(shop['title']) ?? asString(shop['id']) ?? component.document.id,
      offers: offers,
      label: 'Trading screen ${component.document.id}',
      selected: component.selected?.key,
      crossed: _crossed,
      onOffer: component.onSelect == null ? null : (String key) => _pick(OfferRef.parse(key)),
      band: component.band,
    );
  }

  /// The chest GUI the layout names, the offers in its list area.
  Widget _layoutWindow(ChestLayout layout) {
    final OfferRef? selected = component.selected;
    final int? slot = selected == null ? null : layout.cells.where((ChestCell cell) => offerAtCell(cell) == selected).map((ChestCell cell) => cell.slot).firstOrNull;
    return ChestWindow(
      layout: layout,
      label: 'Chest window ${component.document.id}',
      selected: slot,
      onSlot: component.onSelect == null ? null : (int at) => _pick(offerAtCell(layout.cell(at))),
      band: component.band,
    );
  }

  Widget _chest(JsonMap shop, int page, String mode) {
    final OfferRef? selected = component.selected;
    final ChestLayout layout = _shownAs(layoutShop(shop, page: page, mode: mode));
    final int? slot = selected != null && selected.page == page && selected.grid == mode ? selected.slot : null;
    return ChestWindow(
      layout: layout,
      label: 'Chest window ${component.document.id}',
      selected: slot,
      onSlot: component.onSelect == null ? null : (int at) => _pick(offerAtCell(layout.cell(at))),
      band: component.band,
    );
  }
}
