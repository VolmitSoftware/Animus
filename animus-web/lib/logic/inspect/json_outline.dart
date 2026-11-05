/// A tolerant JSON scan for the code view: the best-effort value of text
/// that may not parse yet, where every key and item starts (for mapping a
/// diagnostic path to a line), and what the caret is in (for completion).
library;

import '../gui/gui_json.dart';

/// What the caret is in when completion is asked for.
class CaretContext {
  const CaretContext({
    required this.container,
    required this.atKey,
    required this.prefix,
    required this.replaceStart,
    required this.replaceEnd,
    this.key,
    this.index,
    this.quoted = false,
    this.colonFollows = false,
  });

  /// The path of the object or array that holds the caret.
  final List<JsonStep> container;

  /// Whether a key is being typed (the container is an object).
  final bool atKey;

  /// The key whose value is being typed, in an object.
  final String? key;

  /// The item index whose value is being typed, in an array.
  final int? index;

  /// The text typed so far, without its opening quote.
  final String prefix;

  /// The range a chosen completion replaces.
  final int replaceStart;
  final int replaceEnd;

  /// Whether the caret is inside a string token.
  final bool quoted;

  /// For a key: whether a `:` already follows the key token.
  final bool colonFollows;

  /// The path of the value being typed, for value completion.
  List<JsonStep> get valuePath => <JsonStep>[...container, ?key, ?index];
}

/// The result of [scanJson].
class JsonOutline {
  JsonOutline._(this.value, this.starts, this._lineStarts, this.caret);

  /// The best-effort value: unfinished parts are left out.
  final Object? value;

  /// Where each path's key (or item, or the root value) starts, keyed by
  /// [formatPath].
  final Map<String, int> starts;
  final List<int> _lineStarts;

  /// The caret's context, when a caret offset was given and it is in a
  /// place completion understands.
  final CaretContext? caret;

  /// The 1-based line of [offset].
  int lineOf(int offset) {
    int low = 0, high = _lineStarts.length - 1;
    while (low < high) {
      final int middle = (low + high + 1) ~/ 2;
      if (_lineStarts[middle] <= offset) {
        low = middle;
      } else {
        high = middle - 1;
      }
    }
    return low + 1;
  }

  int get lineCount => _lineStarts.length;

  /// The text offset where 1-based [line] starts.
  int lineStart(int line) => _lineStarts[(line - 1).clamp(0, _lineStarts.length - 1)];

  /// The 1-based line of the closest key or item to [path] (its nearest
  /// existing ancestor when the exact one is missing), or null when [path]
  /// is not a JSON path.
  int? lineForPath(String path) {
    final List<JsonStep>? steps = parseJsonPath(path);
    if (steps == null) return null;
    for (int length = steps.length; length >= 0; length--) {
      final int? offset = starts[formatPath(steps.sublist(0, length))];
      if (offset != null) return lineOf(offset);
    }
    return 1;
  }
}

/// Splits `$.a.b[0]["c d"]` into steps; null when [path] does not start
/// with `$`.
List<JsonStep>? parseJsonPath(String path) {
  if (!path.startsWith(r'$')) return null;
  final List<JsonStep> steps = <JsonStep>[];
  int at = 1;
  while (at < path.length) {
    final String char = path[at];
    if (char == '.') {
      int end = at + 1;
      while (end < path.length && path[end] != '.' && path[end] != '[') {
        end++;
      }
      if (end > at + 1) steps.add(path.substring(at + 1, end));
      at = end;
    } else if (char == '[') {
      final int close = path.indexOf(']', at);
      if (close < 0) return steps;
      final String inner = path.substring(at + 1, close);
      if (inner.length >= 2 && (inner.startsWith('"') || inner.startsWith("'"))) {
        // A quoted key may hold `]`; find the matching quote first.
        final String quote = inner[0];
        int end = at + 2;
        final StringBuffer key = StringBuffer();
        while (end < path.length && path[end] != quote) {
          if (path[end] == r'\' && end + 1 < path.length) end++;
          key.write(path[end]);
          end++;
        }
        steps.add(key.toString());
        final int after = path.indexOf(']', end);
        at = after < 0 ? path.length : after + 1;
      } else {
        final int? index = int.tryParse(inner.trim());
        steps.add(index ?? inner);
        at = close + 1;
      }
    } else {
      at++;
    }
  }
  return steps;
}

/// Scans [text]; with [caret], also works out what the caret is in.
JsonOutline scanJson(String text, {int? caret}) {
  final _Scanner scanner = _Scanner(text, caret);
  scanner.skipSpace();
  final int start = scanner.at;
  scanner.starts[r'$'] = start;
  if (caret != null && caret <= start && scanner.caret == null) {
    scanner.caret = CaretContext(container: const <JsonStep>[], atKey: false, prefix: '', replaceStart: caret, replaceEnd: caret);
  }
  final Object? value = scanner.value(const <JsonStep>[]);
  final List<int> lines = <int>[0];
  for (int index = 0; index < text.length; index++) {
    if (text.codeUnitAt(index) == 0x0A) lines.add(index + 1);
  }
  return JsonOutline._(value, scanner.starts, lines, scanner.caret);
}

class _Scanner {
  _Scanner(this.text, this.caretOffset);

  final String text;
  final int? caretOffset;
  final Map<String, int> starts = <String, int>{};
  CaretContext? caret;
  int at = 0;
  int _depth = 0;

  static const int _depthLimit = 512;

  bool get done => at >= text.length;

  void skipSpace() {
    while (at < text.length) {
      final int unit = text.codeUnitAt(at);
      if (unit == 0x20 || unit == 0x0A || unit == 0x0D || unit == 0x09) {
        at++;
      } else {
        break;
      }
    }
  }

  bool _caretIn(int from, int to) {
    final int? offset = caretOffset;
    return caret == null && offset != null && offset >= from && offset <= to;
  }

  Object? value(List<JsonStep> path, {String? key, int? index, List<JsonStep>? container}) {
    skipSpace();
    if (done || _depth > _depthLimit) return null;
    final int start = at;
    final String char = text[at];
    if (char == '{') return _object(path);
    if (char == '[') return _array(path);
    if (char == '"') {
      final (String content, int end, bool closed) = _string();
      final int? offset = caretOffset;
      if (offset != null && caret == null && offset > start && (offset < end || (!closed && offset <= end))) {
        caret = CaretContext(
          container: container ?? path,
          atKey: false,
          key: key,
          index: index,
          prefix: text.substring(start + 1, offset.clamp(start + 1, closed ? end - 1 : end)),
          replaceStart: start,
          replaceEnd: end,
          quoted: true,
        );
      }
      return content;
    }
    int end = at;
    while (end < text.length && RegExp(r'[A-Za-z0-9.+\-_]').hasMatch(text[end])) {
      end++;
    }
    if (end == at) {
      at++;
      return null;
    }
    final String word = text.substring(at, end);
    at = end;
    if (container != null && _caretIn(start + 1, end)) {
      caret = CaretContext(
        container: container,
        atKey: false,
        key: key,
        index: index,
        prefix: text.substring(start, caretOffset!),
        replaceStart: start,
        replaceEnd: end,
      );
    }
    return switch (word) {
      'true' => true,
      'false' => false,
      'null' => null,
      _ => num.tryParse(word),
    };
  }

  /// Reads a string at [at]; returns its content, the offset after it and
  /// whether it was closed. An unclosed string stops at the line's end.
  (String, int, bool) _string() {
    final StringBuffer out = StringBuffer();
    int index = at + 1;
    while (index < text.length) {
      final String char = text[index];
      if (char == '"') {
        at = index + 1;
        return (out.toString(), at, true);
      }
      if (char == '\n') break;
      if (char == r'\' && index + 1 < text.length) {
        final String next = text[index + 1];
        switch (next) {
          case 'n':
            out.write('\n');
          case 't':
            out.write('\t');
          case 'r':
            out.write('\r');
          case 'b':
            out.write('\b');
          case 'f':
            out.write('\f');
          case 'u':
            final int? code = index + 5 < text.length ? int.tryParse(text.substring(index + 2, index + 6), radix: 16) : null;
            if (code != null) {
              out.writeCharCode(code);
              index += 4;
            }
          default:
            out.write(next);
        }
        index += 2;
        continue;
      }
      out.write(char);
      index++;
    }
    at = index;
    return (out.toString(), at, false);
  }

  Map<String, Object?> _object(List<JsonStep> path) {
    final Map<String, Object?> out = <String, Object?>{};
    _depth++;
    at++;
    while (true) {
      final int gap = at;
      skipSpace();
      if (done) {
        _keyCaret(path, gap, at);
        break;
      }
      final String char = text[at];
      if (char == '}') {
        _keyCaret(path, gap, at);
        at++;
        break;
      }
      if (char == ',') {
        _keyCaret(path, gap, at);
        at++;
        continue;
      }
      if (char == ']') break;
      final int keyStart = at;
      _keyCaret(path, gap, keyStart);
      String key;
      int keyEnd;
      bool closed = true;
      if (char == '"') {
        (key, keyEnd, closed) = _string();
      } else {
        int end = at;
        while (end < text.length && RegExp(r'[A-Za-z0-9_\-:]').hasMatch(text[end])) {
          end++;
        }
        if (end == at) end = at + 1;
        key = text.substring(at, end);
        keyEnd = end;
        at = end;
      }
      final List<JsonStep> keyPath = <JsonStep>[...path, key];
      starts.putIfAbsent(formatPath(keyPath), () => keyStart);
      final int afterKey = at;
      skipSpace();
      final bool colon = !done && text[at] == ':';
      final int? offset = caretOffset;
      if (caret == null && offset != null && offset > keyStart && (offset < keyEnd || (!closed && offset <= keyEnd) || (char != '"' && offset <= keyEnd))) {
        final bool quoted = char == '"';
        caret = CaretContext(
          container: path,
          atKey: true,
          prefix: text.substring(quoted ? keyStart + 1 : keyStart, offset.clamp(quoted ? keyStart + 1 : keyStart, quoted && closed ? keyEnd - 1 : keyEnd)),
          replaceStart: keyStart,
          replaceEnd: keyEnd,
          quoted: quoted,
          colonFollows: colon,
        );
      }
      if (!colon) {
        at = afterKey;
        continue;
      }
      at++;
      final int valueGap = at;
      skipSpace();
      if (_caretIn(valueGap, at) && (done || !'{["'.contains(text[at]))) {
        final bool bare = !done && RegExp(r'[A-Za-z0-9.+\-_]').hasMatch(text[at]);
        if (!bare || caretOffset! < at) {
          caret = CaretContext(container: path, atKey: false, key: key, prefix: '', replaceStart: caretOffset!, replaceEnd: caretOffset!);
        }
      }
      out[key] = value(keyPath, key: key, container: path);
    }
    _depth--;
    return out;
  }

  void _keyCaret(List<JsonStep> path, int from, int to) {
    if (_caretIn(from, to)) {
      caret = CaretContext(container: path, atKey: true, prefix: '', replaceStart: caretOffset!, replaceEnd: caretOffset!);
    }
  }

  List<Object?> _array(List<JsonStep> path) {
    final List<Object?> out = <Object?>[];
    _depth++;
    at++;
    int index = 0;
    while (true) {
      final int gap = at;
      skipSpace();
      if (done) {
        _itemCaret(path, index, gap, at);
        break;
      }
      final String char = text[at];
      if (char == ']') {
        _itemCaret(path, index, gap, at);
        at++;
        break;
      }
      if (char == ',') {
        _itemCaret(path, index, gap, at);
        at++;
        index++;
        continue;
      }
      if (char == '}') break;
      _itemCaret(path, index, gap, at);
      final List<JsonStep> itemPath = <JsonStep>[...path, index];
      starts.putIfAbsent(formatPath(itemPath), () => at);
      final int before = at;
      final Object? item = value(itemPath, index: index, container: path);
      while (out.length < index) {
        out.add(null);
      }
      if (out.length == index) out.add(item);
      if (at == before) at++;
      skipSpace();
      if (!done && text[at] != ',' && text[at] != ']') index++;
    }
    _depth--;
    return out;
  }

  void _itemCaret(List<JsonStep> path, int index, int from, int to) {
    if (_caretIn(from, to)) {
      caret = CaretContext(container: path, atKey: false, index: index, prefix: '', replaceStart: caretOffset!, replaceEnd: caretOffset!);
    }
  }
}
