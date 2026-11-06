/// How help text is split between an inline hint and an info tip.
library;

/// At most the first two sentences of [text], for a tooltip.
String tipText(String text) {
  final String trimmed = text.trim();
  final Iterable<RegExpMatch> ends = RegExp(r'[.!?](\s|$)').allMatches(trimmed);
  if (ends.length <= 2) return trimmed;
  return trimmed.substring(0, ends.elementAt(1).start + 1);
}

/// Hints of up to this many words stay under the control; longer help moves
/// into the info tip beside the label.
const int shortHintWords = 6;

/// [help] as an inline hint when it is short, otherwise as tip text.
({String? hint, String? tip}) splitHelp(String? help) {
  final String text = help?.trim() ?? '';
  if (text.isEmpty) return (hint: null, tip: null);
  return text.split(RegExp(r'\s+')).length <= shortHintWords ? (hint: text, tip: null) : (hint: null, tip: text);
}
