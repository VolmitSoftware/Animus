/// A JSON tree that behaves like Gson's: insertion-ordered objects, Java
/// number kinds kept apart (an integer is never printed as a double and a
/// double always prints the way Java's `Double.toString` does), and Gson's
/// pretty printer with HTML escaping off. dart2js has one number type, so
/// numbers are explicit value classes and integers are [BigInt]s.
library;

import 'dart:collection';
import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';

sealed class JElement {
  const JElement();

  JElement deepCopy();

  bool get isObject => this is JObject;
  bool get isArray => this is JArray;
  bool get isPrimitive => this is JPrimitive;
  bool get isNull => this is JNull;

  JObject get asObject => this as JObject;
  JArray get asArray => this as JArray;
  JPrimitive get asPrimitive => this as JPrimitive;

  /// Gson `JsonElement.toString()`: compact JSON.
  @override
  String toString() {
    final StringBuffer out = StringBuffer();
    _writeCompact(this, out);
    return out.toString();
  }
}

final class JNull extends JElement {
  const JNull._();

  static const JNull instance = JNull._();

  @override
  JElement deepCopy() => this;
}

sealed class JPrimitive extends JElement {
  const JPrimitive();

  bool get isString => this is JString;
  bool get isBoolean => this is JBool;
  bool get isNumber => this is JNumber;

  /// Gson `getAsString()`: numbers use Java's `toString`.
  String get asString;

  /// Gson `getAsDouble()`; a string is parsed like `Double.parseDouble`.
  double get asDouble;

  /// Gson `getAsBoolean()`: a non-boolean is `Boolean.parseBoolean(getAsString())`.
  bool get asBoolean => this is JBool ? (this as JBool).value : asString.toLowerCase() == 'true';

  @override
  JElement deepCopy() => this;
}

final class JString extends JPrimitive {
  const JString(this.value);

  final String value;

  @override
  String get asString => value;

  @override
  double get asDouble => javaParseDouble(value);

  @override
  bool operator ==(Object other) => other is JString && other.value == value;

  @override
  int get hashCode => value.hashCode;
}

final class JBool extends JPrimitive {
  const JBool(this.value);

  final bool value;

  @override
  String get asString => value ? 'true' : 'false';

  @override
  double get asDouble => javaParseDouble(asString);

  @override
  bool operator ==(Object other) => other is JBool && other.value == value;

  @override
  int get hashCode => value.hashCode;
}

sealed class JNumber extends JPrimitive {
  const JNumber();

  /// Java `Number.intValue()`.
  int get asInt;

  /// The exact decimal value, as Gson's `getAsBigDecimal()` gives it.
  JavaDecimal get asDecimal;
}

/// `Integer`, `Long` or `BigInteger`: they print and compare alike.
final class JInt extends JNumber {
  const JInt(this.value);

  JInt.of(int value) : value = BigInt.from(value);

  final BigInt value;

  @override
  String get asString => value.toString();

  @override
  double get asDouble => value.toDouble();

  @override
  int get asInt => value.toSigned(32).toInt();

  @override
  JavaDecimal get asDecimal => JavaDecimal(value, 0);

  @override
  bool operator ==(Object other) => other is JInt && other.value == value;

  @override
  int get hashCode => value.hashCode;
}

/// `Double`.
final class JDouble extends JNumber {
  const JDouble(this.value);

  final double value;

  @override
  String get asString => javaDoubleToString(value);

  @override
  double get asDouble => value;

  @override
  int get asInt => javaDoubleToInt(value);

  @override
  JavaDecimal get asDecimal => JavaDecimal.parse(asString)!;
}

final class JArray extends JElement {
  JArray([List<JElement>? elements]) : elements = elements ?? <JElement>[];

  final List<JElement> elements;

  int get length => elements.length;
  bool get isEmpty => elements.isEmpty;
  bool get isNotEmpty => elements.isNotEmpty;
  JElement operator [](int index) => elements[index];

  void add(JElement? value) => elements.add(value ?? JNull.instance);
  void addString(String value) => elements.add(JString(value));
  void addDouble(double value) => elements.add(JDouble(value));

  bool contains(JElement value) => elements.contains(value);

  @override
  JArray deepCopy() => JArray(<JElement>[for (final JElement element in elements) element.deepCopy()]);
}

final class JObject extends JElement {
  JObject();

  final LinkedHashMap<String, JElement> members = LinkedHashMap<String, JElement>();

  int get length => members.length;
  bool get isEmpty => members.isEmpty;
  bool get isNotEmpty => members.isNotEmpty;
  Iterable<String> get keys => members.keys;
  Iterable<MapEntry<String, JElement>> get entries => members.entries;

  bool has(String key) => members.containsKey(key);
  JElement? get(String key) => members[key];

  /// Gson `add`: a replaced key keeps its place.
  void add(String key, JElement? value) => members[key] = value ?? JNull.instance;
  void addString(String key, String value) => members[key] = JString(value);
  void addBool(String key, bool value) => members[key] = JBool(value);
  void addInt(String key, int value) => members[key] = JInt.of(value);
  void addBig(String key, BigInt value) => members[key] = JInt(value);
  void addDouble(String key, double value) => members[key] = JDouble(value);
  JElement? remove(String key) => members.remove(key);

  JObject? object(String key) {
    final JElement? value = members[key];
    return value is JObject ? value : null;
  }

  @override
  JObject deepCopy() {
    final JObject copy = JObject();
    for (final MapEntry<String, JElement> entry in members.entries) {
      copy.members[entry.key] = entry.value.deepCopy();
    }
    return copy;
  }
}

/// `JsonDocuments.write`: Gson pretty printing with nulls, no HTML escaping, and a final newline.
String writeJson(JElement value) {
  final StringBuffer out = StringBuffer();
  _writePretty(value, out, '');
  out.write('\n');
  return out.toString();
}

void _writePretty(JElement value, StringBuffer out, String indent) {
  switch (value) {
    case JObject(:final LinkedHashMap<String, JElement> members):
      if (members.isEmpty) {
        out.write('{}');
        return;
      }
      final String inner = '$indent  ';
      out.write('{\n');
      bool first = true;
      for (final MapEntry<String, JElement> entry in members.entries) {
        if (!first) out.write(',\n');
        first = false;
        out
          ..write(inner)
          ..write(quoteJson(entry.key))
          ..write(': ');
        _writePretty(entry.value, out, inner);
      }
      out
        ..write('\n')
        ..write(indent)
        ..write('}');
    case JArray(:final List<JElement> elements):
      if (elements.isEmpty) {
        out.write('[]');
        return;
      }
      final String inner = '$indent  ';
      out.write('[\n');
      for (int index = 0; index < elements.length; index++) {
        if (index > 0) out.write(',\n');
        out.write(inner);
        _writePretty(elements[index], out, inner);
      }
      out
        ..write('\n')
        ..write(indent)
        ..write(']');
    default:
      _writeScalar(value, out);
  }
}

void _writeCompact(JElement value, StringBuffer out) {
  switch (value) {
    case JObject(:final LinkedHashMap<String, JElement> members):
      out.write('{');
      bool first = true;
      for (final MapEntry<String, JElement> entry in members.entries) {
        if (!first) out.write(',');
        first = false;
        out
          ..write(quoteJson(entry.key))
          ..write(':');
        _writeCompact(entry.value, out);
      }
      out.write('}');
    case JArray(:final List<JElement> elements):
      out.write('[');
      for (int index = 0; index < elements.length; index++) {
        if (index > 0) out.write(',');
        _writeCompact(elements[index], out);
      }
      out.write(']');
    default:
      _writeScalar(value, out);
  }
}

void _writeScalar(JElement value, StringBuffer out) {
  switch (value) {
    case JNull():
      out.write('null');
    case JString(:final String value):
      out.write(quoteJson(value));
    case JPrimitive():
      out.write(value.asString);
    default:
      throw StateError('not a scalar');
  }
}

/// Gson `JsonWriter.string` with HTML escaping off.
String quoteJson(String text) {
  final StringBuffer out = StringBuffer('"');
  for (int index = 0; index < text.length; index++) {
    final int unit = text.codeUnitAt(index);
    switch (unit) {
      case 0x22:
        out.write(r'\"');
      case 0x5C:
        out.write(r'\\');
      case 0x09:
        out.write(r'\t');
      case 0x08:
        out.write(r'\b');
      case 0x0A:
        out.write(r'\n');
      case 0x0D:
        out.write(r'\r');
      case 0x0C:
        out.write(r'\f');
      case 0x2028:
        out.write(r'\u2028');
      case 0x2029:
        out.write(r'\u2029');
      default:
        if (unit < 0x20) {
          out
            ..write(r'\u')
            ..write(unit.toRadixString(16).padLeft(4, '0'));
        } else {
          out.writeCharCode(unit);
        }
    }
  }
  out.write('"');
  return out.toString();
}

/// Java `Double.toString` (JDK 19+): the shortest decimal that rounds to [value],
/// with two digits considered when one would do, in Java's plain or `E` notation.
String javaDoubleToString(double value) {
  if (value.isNaN) return 'NaN';
  if (value.isInfinite) return value > 0 ? 'Infinity' : '-Infinity';
  if (value == 0) return (1 / value) < 0 ? '-0.0' : '0.0';
  final bool negative = value < 0;
  final double magnitude = value.abs();
  (String, int) parts = _shortest(magnitude);
  if (parts.$1.length == 1) {
    final (String, int) two = _exponential(magnitude.toStringAsExponential(1));
    if (two.$1 != '${parts.$1}0' && double.parse('${two.$1[0]}.${two.$1.substring(1)}e${two.$2}') == magnitude) parts = two;
  }
  String digits = parts.$1;
  while (digits.length > 1 && digits.endsWith('0')) {
    digits = digits.substring(0, digits.length - 1);
  }
  final int exponent = parts.$2;
  final StringBuffer out = StringBuffer(negative ? '-' : '');
  if (magnitude >= 1e-3 && magnitude < 1e7) {
    if (exponent >= 0) {
      final String whole = digits.length > exponent + 1 ? digits.substring(0, exponent + 1) : digits.padRight(exponent + 1, '0');
      final String fraction = digits.length > exponent + 1 ? digits.substring(exponent + 1) : '0';
      out
        ..write(whole)
        ..write('.')
        ..write(fraction);
    } else {
      out
        ..write('0.')
        ..write(''.padLeft(-exponent - 1, '0'))
        ..write(digits);
    }
  } else {
    out
      ..write(digits[0])
      ..write('.')
      ..write(digits.length > 1 ? digits.substring(1) : '0')
      ..write('E')
      ..write(exponent);
  }
  return out.toString();
}

(String, int) _shortest(double magnitude) => _exponential(magnitude.toStringAsExponential());

/// `d.ddde+x` as (digits, exponent).
(String, int) _exponential(String text) {
  final int e = text.indexOf('e');
  final String mantissa = text.substring(0, e).replaceAll('.', '');
  return (mantissa, int.parse(text.substring(e + 1)));
}

/// Java's `(int)` cast of a double.
int javaDoubleToInt(double value) {
  if (value.isNaN) return 0;
  if (value >= 2147483647) return 2147483647;
  if (value <= -2147483648) return -2147483648;
  return value.truncate();
}

/// Java's `(long)` cast of a double.
BigInt javaDoubleToLong(double value) {
  if (value.isNaN) return BigInt.zero;
  if (value >= 9223372036854775807.0) return javaLongMax;
  if (value <= -9223372036854775808.0) return javaLongMin;
  return BigInt.from(value.truncateToDouble());
}

final BigInt javaLongMax = BigInt.parse('9223372036854775807');
final BigInt javaLongMin = BigInt.parse('-9223372036854775808');

/// Java `Math.round(double)`: floor(x + 1/2) computed exactly, saturating to long.
BigInt javaRound(double value) {
  if (value.isNaN) return BigInt.zero;
  final double floor = value.floorToDouble();
  final double rounded = value - floor >= 0.5 ? floor + 1 : floor;
  return javaDoubleToLong(rounded);
}

/// Java `Double.parseDouble`. Throws [FormatException] where Java throws `NumberFormatException`.
double javaParseDouble(String text) {
  int start = 0;
  int end = text.length;
  while (start < end && text.codeUnitAt(start) <= 0x20) {
    start++;
  }
  while (end > start && text.codeUnitAt(end - 1) <= 0x20) {
    end--;
  }
  final String trimmed = text.substring(start, end);
  final RegExpMatch? decimal = _javaDecimalDouble.firstMatch(trimmed);
  if (decimal != null) {
    final String sign = decimal.group(1) ?? '';
    final String body = decimal.group(2)!;
    if (body == 'NaN') return double.nan;
    if (body == 'Infinity') return sign == '-' ? double.negativeInfinity : double.infinity;
    String whole = decimal.group(3) ?? '';
    String fraction = decimal.group(4) ?? decimal.group(5) ?? '';
    if (whole.isEmpty) whole = '0';
    if (fraction.isEmpty) fraction = '0';
    final String exponent = decimal.group(6) ?? '';
    return double.parse('$sign$whole.$fraction$exponent');
  }
  final RegExpMatch? hex = _javaHexDouble.firstMatch(trimmed);
  if (hex != null) {
    final bool negative = hex.group(1) == '-';
    final String whole = hex.group(2) ?? '';
    final String fraction = hex.group(3) ?? hex.group(4) ?? '';
    final int binaryExponent = int.parse(hex.group(5)!);
    final BigInt mantissa = BigInt.parse('0$whole$fraction', radix: 16);
    double result = mantissa.toDouble();
    int shift = binaryExponent - 4 * fraction.length;
    while (shift > 0) {
      final int step = shift > 512 ? 512 : shift;
      result *= _power2(step);
      shift -= step;
    }
    while (shift < 0) {
      final int step = shift < -512 ? 512 : -shift;
      result /= _power2(step);
      shift += step;
    }
    return negative ? -result : result;
  }
  throw FormatException('For input string: "$text"');
}

double _power2(int exponent) {
  double result = 1;
  for (int i = 0; i < exponent; i++) {
    result *= 2;
  }
  return result;
}

final RegExp _javaDecimalDouble =
    RegExp(r'^([+-]?)(NaN|Infinity|(?:([0-9]+)\.?([0-9]*)|\.([0-9]+))([eE][+-]?[0-9]+)?[fFdD]?)$');
final RegExp _javaHexDouble = RegExp(r'^([+-]?)0[xX](?:([0-9a-fA-F]+)\.?([0-9a-fA-F]*)|\.([0-9a-fA-F]+))[pP]([+-]?[0-9]+)[fFdD]?$');

/// A `BigDecimal`: [unscaled] × 10^-[scale].
class JavaDecimal {
  const JavaDecimal(this.unscaled, this.scale);

  final BigInt unscaled;
  final int scale;

  /// `new BigDecimal(String)`; null where Java throws `NumberFormatException`.
  static JavaDecimal? parse(String text) {
    final RegExpMatch? match = _decimal.firstMatch(text);
    if (match == null) return null;
    final String sign = match.group(1) ?? '';
    final String whole = match.group(2) ?? match.group(4) ?? '';
    final String fraction = match.group(3) ?? match.group(5) ?? '';
    final String? exponentText = match.group(6);
    if (whole.isEmpty && fraction.isEmpty) return null;
    int exponent = 0;
    if (exponentText != null) {
      final BigInt big = BigInt.parse(exponentText);
      if (big.bitLength > 31) return null;
      exponent = big.toInt();
    }
    final BigInt digits = BigInt.parse('0$whole$fraction');
    final int scale = fraction.length - exponent;
    return JavaDecimal(sign == '-' ? -digits : digits, scale);
  }

  /// `intValueExact()` / `longValueExact()`: null where Java throws `ArithmeticException`.
  BigInt? exactInteger(BigInt minimum, BigInt maximum) {
    BigInt value;
    if (unscaled == BigInt.zero) return BigInt.zero;
    if (scale <= 0) {
      if (-scale > 64) return null;
      value = unscaled * BigInt.from(10).pow(-scale);
    } else {
      if (scale > 400) return null;
      final BigInt divisor = BigInt.from(10).pow(scale);
      if (unscaled.remainder(divisor) != BigInt.zero) return null;
      value = unscaled ~/ divisor;
    }
    if (value < minimum || value > maximum) return null;
    return value;
  }

  int? intValueExact() => exactInteger(BigInt.from(-2147483648), BigInt.from(2147483647))?.toInt();
  BigInt? longValueExact() => exactInteger(javaLongMin, javaLongMax);

  static final RegExp _decimal = RegExp(r'^([+-]?)(?:([0-9]+)(?:\.([0-9]*))?|()\.([0-9]+))(?:[eE]([+-]?[0-9]+))?$');
}

/// Java `String.getBytes(UTF_8)`: an unpaired surrogate becomes `?`.
Uint8List javaUtf8(String text) {
  bool lone = false;
  for (int index = 0; index < text.length; index++) {
    final int unit = text.codeUnitAt(index);
    if (unit >= 0xD800 && unit <= 0xDBFF) {
      if (index + 1 < text.length) {
        final int next = text.codeUnitAt(index + 1);
        if (next >= 0xDC00 && next <= 0xDFFF) {
          index++;
          continue;
        }
      }
      lone = true;
      break;
    } else if (unit >= 0xDC00 && unit <= 0xDFFF) {
      lone = true;
      break;
    }
  }
  if (!lone) return utf8.encode(text);
  final StringBuffer cleaned = StringBuffer();
  for (int index = 0; index < text.length; index++) {
    final int unit = text.codeUnitAt(index);
    if (unit >= 0xD800 && unit <= 0xDBFF && index + 1 < text.length) {
      final int next = text.codeUnitAt(index + 1);
      if (next >= 0xDC00 && next <= 0xDFFF) {
        cleaned
          ..writeCharCode(unit)
          ..writeCharCode(next);
        index++;
        continue;
      }
    }
    cleaned.writeCharCode(unit >= 0xD800 && unit <= 0xDFFF ? 0x3F : unit);
  }
  return utf8.encode(cleaned.toString());
}

/// `JsonDocuments.hash`: SHA-256 of the UTF-8 text, lowercase hex.
String sha256Hex(String text) => sha256.convert(javaUtf8(text)).toString();

String sha256Bytes(List<int> bytes) => sha256.convert(bytes).toString();
