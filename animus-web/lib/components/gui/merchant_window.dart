/// A native shop drawn as the villager trading screen: the title and the
/// offer column, each offer as cost A, the optional cost B, an arrow and
/// the result. Money shows as a gold nugget with its price beside it.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/shop_offers.dart';
import '../mc/mc_item_icons.dart';
import 'chest_window.dart';
import 'gui_fields.dart';

class MerchantWindow extends StatefulWidget {
  const MerchantWindow({required this.title, required this.offers, this.label = 'Villager trading screen', super.key});

  final String title;
  final List<ShopOffer> offers;
  final String label;

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
    return dom.div(classes: 'animus-merchant-screen', attributes: <String, String>{'role': 'group', 'aria-label': component.label}, <Widget>[
      dom.div(classes: 'animus-chest-title', <Widget>[GuiStyledText(component.title, defaultColor: mcInventoryTitleColor)]),
      if (component.offers.isEmpty)
        const dom.p(classes: 'animus-merchant-empty', <Widget>[Component.text('No offers: every entry is static or its mode is off.')])
      else
        dom.ol(classes: 'animus-merchant-offers', <Widget>[for (final ShopOffer offer in component.offers) _offer(offer)]),
    ]);
  }

  Widget _offer(ShopOffer offer) {
    final ShopGoods? costB = offer.costB;
    final String said = '${_say(offer.costA)}${costB == null ? '' : ' and ${_say(costB)}'} for ${_say(offer.result)}';
    return dom.li(classes: 'animus-merchant-offer', attributes: <String, String>{'aria-label': said, 'data-offer': offer.entryId}, <Widget>[
      _goods(offer.costA),
      if (costB != null) _goods(costB) else const dom.span(classes: 'animus-merchant-goods is-blank', <Widget>[]),
      const dom.span(classes: 'animus-merchant-arrow', attributes: <String, String>{'aria-hidden': 'true'}, <Widget>[Component.text('→')]),
      _goods(offer.result),
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
