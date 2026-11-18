/// The shop editor's words for offer kinds, offer groups, the tabs players
/// switch between and a chest shop's kinds. Sells and buys are always said
/// from the shop's side: a `buy` offer is one the shop sells, a `sell`
/// offer one the shop buys. The file keys stay as they are.
library;

/// Offer kinds by plain name, for the offer's Kind field.
const Map<String, String> offerKindLabels = <String, String>{
  'trade': 'Trade (items for items)',
  'buy': 'Sells (player pays money)',
  'sell': 'Buys (shop pays money)',
  'command': 'Command (runs commands)',
  'static': 'Decoration',
};

/// Kinds in a sentence, for "Add ..." entries.
const Map<String, String> offerKindNouns = <String, String>{'trade': 'trade', 'buy': 'item to sell', 'sell': 'item to buy', 'command': 'command offer', 'static': 'decoration'};

/// Short names for the board's kind tag.
const Map<String, String> offerKindTags = <String, String>{'trade': 'Trade', 'buy': 'Sells', 'sell': 'Buys', 'command': 'Command', 'static': 'Decoration'};

/// Grid names as offer group headings.
const Map<String, String> offerGridTitles = <String, String>{'buy': 'Sells', 'sell': 'Buys', 'trade': 'Trades'};

/// The tabs (modes) players switch between, by grid.
const Map<String, String> shopModeLabels = <String, String>{'buy': 'Sells', 'sell': 'Buys', 'trade': 'Trades'};

/// A chest shop's kinds, as its owner picks them in game.
const Map<String, String> shopKindLabels = <String, String>{'selling': 'Selling', 'buying': 'Buying', 'trading': 'Trading', 'book': 'Book'};

/// What each chest shop kind does, one line each.
const Map<String, String> shopKindLines = <String, String>{
  'selling': 'Players buy items from the chest.',
  'buying': 'Players sell items; the chest pays.',
  'trading': 'Players swap items with the chest.',
  'book': 'Players buy copies of books in the chest.',
};
