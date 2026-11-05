/// The few `java.lang` and `java.util` behaviors the Citizens converter's output depends on.
library;

import 'dart:convert';

import 'package:crypto/crypto.dart';

import 'java_json.dart';

/// `Character.isWhitespace(int)`.
bool javaIsWhitespace(int point) {
  if (point >= 0x09 && point <= 0x0D) return true;
  if (point >= 0x1C && point <= 0x1F) return true;
  if (point == 0x20 || point == 0x1680 || point == 0x205F || point == 0x3000 || point == 0x2028 || point == 0x2029) return true;
  return point >= 0x2000 && point <= 0x200A && point != 0x2007;
}

/// `String.isBlank()`.
bool javaIsBlank(String text) => text.runes.every(javaIsWhitespace);

/// `String.strip()`: leading and trailing `Character.isWhitespace` removed. Every whitespace character is in the BMP.
String javaStrip(String text) {
  int start = 0;
  int end = text.length;
  while (start < end && javaIsWhitespace(text.codeUnitAt(start))) {
    start++;
  }
  while (end > start && javaIsWhitespace(text.codeUnitAt(end - 1))) {
    end--;
  }
  return text.substring(start, end);
}

/// `String.trim()`: leading and trailing characters up to U+0020 removed.
String javaTrim(String text) {
  int start = 0;
  int end = text.length;
  while (start < end && text.codeUnitAt(start) <= 0x20) {
    start++;
  }
  while (end > start && text.codeUnitAt(end - 1) <= 0x20) {
    end--;
  }
  return text.substring(start, end);
}

/// `Character.isISOControl(int)`.
bool javaIsIsoControl(int point) => point <= 0x1F || (point >= 0x7F && point <= 0x9F);

/// `String.split(regex)` for a literal [separator]: trailing empty strings are dropped.
List<String> javaSplit(String text, String separator) {
  if (!text.contains(separator)) return <String>[text];
  final List<String> parts = text.split(separator);
  while (parts.isNotEmpty && parts.last.isEmpty) {
    parts.removeLast();
  }
  return parts;
}

final RegExp _canonicalUuid = RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');

/// `UUID.fromString(text).toString().equals(text)`.
bool canonicalUuid(String? text) => text != null && _canonicalUuid.hasMatch(text);

final BigInt _mask16 = BigInt.from(0xFFFF);
final BigInt _mask32 = BigInt.parse('FFFFFFFF', radix: 16);
final BigInt _mask48 = BigInt.parse('FFFFFFFFFFFF', radix: 16);

/// Java's lenient `UUID.fromString`, as the 128-bit value; null where it throws.
BigInt? javaUuidValue(String name) {
  final int length = name.length;
  if (length > 36) return null;
  final int dash1 = name.indexOf('-');
  final int dash2 = name.indexOf('-', dash1 + 1);
  final int dash3 = name.indexOf('-', dash2 + 1);
  final int dash4 = name.indexOf('-', dash3 + 1);
  final int dash5 = name.indexOf('-', dash4 + 1);
  if (dash4 < 0 || dash5 >= 0) return null;
  final BigInt? a = _parseHexLong(name, 0, dash1);
  final BigInt? b = _parseHexLong(name, dash1 + 1, dash2);
  final BigInt? c = _parseHexLong(name, dash2 + 1, dash3);
  final BigInt? d = _parseHexLong(name, dash3 + 1, dash4);
  final BigInt? e = _parseHexLong(name, dash4 + 1, length);
  if (a == null || b == null || c == null || d == null || e == null) return null;
  final BigInt most = ((a & _mask32) << 32) | ((b & _mask16) << 16) | (c & _mask16);
  final BigInt least = ((d & _mask16) << 48) | (e & _mask48);
  return (most << 64) | least;
}

/// `Long.parseLong(text, start, end, 16)` for a range without `-`.
BigInt? _parseHexLong(String text, int start, int end) {
  if (start < 0 || end > text.length || start >= end) return null;
  String digits = text.substring(start, end);
  if (digits.startsWith('+')) digits = digits.substring(1);
  if (digits.isEmpty || !RegExp(r'^[0-9a-fA-F]+$').hasMatch(digits)) return null;
  final BigInt value = BigInt.parse(digits, radix: 16);
  return value > javaLongMax ? null : value;
}

/// `new UUID(most, least).toString()` for two signed longs.
String uuidOf(BigInt most, BigInt least) {
  final String hex = most.toUnsigned(64).toRadixString(16).padLeft(16, '0') + least.toUnsigned(64).toRadixString(16).padLeft(16, '0');
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
}

/// `UUID.nameUUIDFromBytes(name.getBytes(UTF_8)).toString()`.
String nameUuid(String name) {
  final List<int> digest = List<int>.of(md5.convert(utf8.encode(name)).bytes);
  digest[6] = (digest[6] & 0x0f) | 0x30;
  digest[8] = (digest[8] & 0x3f) | 0x80;
  final String hex = <String>[for (final int byte in digest) byte.toRadixString(16).padLeft(2, '0')].join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
}

/// Java `String.toLowerCase(Locale.ROOT)`. Dart's mapping agrees for the text a saves file holds.
String javaLower(String text) => text.toLowerCase();

/// Java `String.toUpperCase(Locale.ROOT)`.
String javaUpper(String text) => text.toUpperCase();

/// Java `Math.toDegrees`.
double javaToDegrees(double radians) => radians * 57.29577951308232;
