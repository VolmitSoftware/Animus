/// A native shop drawn as the villager trading screen at GUI scale 3 and
/// fitted to its frame's width and height ([WindowFit]): the title and the
/// offers given (one page of them, as the client lists a few at a time), each
/// offer as cost A, the optional cost B, an arrow and
/// the result. Money shows as a gold nugget with its price beside it. An
/// offer that ran out or that the player may not take is crossed out, as
/// the client draws it. With [MerchantWindow.onOffer] each offer is a
/// button that picks it.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/gui/shop_offers.dart';
import '../mc/mc_item_icons.dart';
import 'gui_fields.dart';
import 'window_fit.dart';

class MerchantWindow extends StatefulWidget {
  const MerchantWindow({
    required this.title,
    required this.offers,
    this.label = 'Villager trading screen',
    this.selected,
    this.crossed = const <String>{},
    this.onOffer,
    this.band = false,
    super.key,
  });

  /// The frame sits in the short band above a form.
  final bool band;

  final String title;
  final List<ShopOffer> offers;
  final String label;

  /// The [ShopOffer.key] of the offer shown as picked.
  final String? selected;

  /// Keys of offers drawn crossed out.
  final Set<String> crossed;

  /// Called with the key of a clicked offer; null draws plain rows.
  final void Function(String key)? onOffer;

  @override
  State<MerchantWindow> createState() => _MerchantWindowState();
}

class _MerchantWindowState extends State<MerchantWindow> {
  @override
  void initState() {
    super.initState();
    McItemIcons.addListener(_refresh);
  }

  @override
  void dispose() {
    McItemIcons.removeListener(_refresh);
    super.dispose();
  }

  void _refresh() {
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    return WindowFit(
      band: component.band,
      child: dom.div(classes: 'animus-merchant-screen', attributes: <String, String>{'role': 'group', 'aria-label': component.label}, <Widget>[
        dom.div(classes: 'animus-chest-title', <Widget>[
          GuiStyledText(component.title, defaultColor: mcInventoryTitleColor),
        ]),
        if (component.offers.isEmpty)
          const dom.p(classes: 'animus-merchant-empty', <Widget>[Component.text('No trades to show.')])
        else
          dom.ol(classes: 'animus-merchant-offers', <Widget>[for (final ShopOffer offer in component.offers) _offer(offer)]),
      ]),
    );
  }

  Widget _offer(ShopOffer offer) {
    final ShopGoods? costB = offer.costB;
    final bool crossed = component.crossed.contains(offer.key);
    final String said = '${_say(offer.costA)}${costB == null ? '' : ' and ${_say(costB)}'} for ${_say(offer.result)}${crossed ? ', unavailable' : ''}';
    final List<Widget> cells = <Widget>[
      _goods(offer.costA),
      if (costB != null) _goods(costB) else const dom.span(classes: 'animus-merchant-goods is-blank', <Widget>[]),
      dom.span(classes: 'animus-merchant-arrow', attributes: const <String, String>{'aria-hidden': 'true'}, <Widget>[Component.text(crossed ? '✕' : '→')]),
      _goods(offer.result),
    ];
    final String classes = 'animus-merchant-offer${crossed ? ' is-crossed' : ''}${offer.key == component.selected ? ' is-selected' : ''}';
    final void Function(String key)? onOffer = component.onOffer;
    if (onOffer == null) {
      return dom.li(classes: classes, attributes: <String, String>{'aria-label': said, 'data-offer': offer.entryId, 'data-key': offer.key}, cells);
    }
    return dom.li(classes: 'animus-merchant-item', <Widget>[
      dom.button(
        classes: classes,
        attributes: <String, String>{
          'type': 'button',
          'aria-label': said,
          'aria-pressed': '${offer.key == component.selected}',
          'data-offer': offer.entryId,
          'data-key': offer.key,
        },
        events: <String, void Function(web.Event)>{'click': (web.Event _) => onOffer(offer.key)},
        cells,
      ),
    ]);
  }

  Widget _goods(ShopGoods goods) {
    final String? key = McItemIcons.keyOf(goods.item);
    final String? url = key == null ? null : McItemIcons.url(key);
    return dom.span(classes: 'animus-merchant-goods', attributes: const <String, String>{'aria-hidden': 'true'}, <Widget>[
      dom.span(classes: 'animus-chest-slot', <Widget>[
        dom.span(classes: 'animus-chest-face', <Widget>[
          if (url != null)
            dom.img(src: url, alt: '', attributes: const <String, String>{'draggable': 'false'})
          else
            dom.span(classes: 'animus-chest-missing', <Widget>[Component.text(goods.item.substring(goods.item.indexOf(':') + 1).replaceAll('_', ' '))]),
          if (goods.count > 1) dom.span(classes: 'animus-chest-count', <Widget>[Component.text('${goods.count}')]),
        ]),
      ]),
      if (goods.label != null) dom.span(classes: 'animus-merchant-price', <Widget>[Component.text(goods.label!)]),
    ]);
  }

  static String _say(ShopGoods goods) {
    final String? label = goods.label;
    if (label != null) return label == 'Free' || label.startsWith('Pays') ? label.toLowerCase() : 'price $label';
    return '${goods.count} ${goods.item.substring(goods.item.indexOf(':') + 1).replaceAll('_', ' ')}';
  }
}
