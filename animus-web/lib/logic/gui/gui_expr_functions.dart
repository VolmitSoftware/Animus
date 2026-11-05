/// The expression standard library, copied from Gloss Web's
/// `preview_expr_functions.dart` without Gloss's text animation functions,
/// plus Animus's `len`, `contains`, `lower` and `upper` (docs/gui.md).
/// [previewStdFunction] returns null for an unknown name.
library;

import 'dart:math' as math;

import 'gui_expr.dart';

final BigInt _javaLongMax = BigInt.parse('9223372036854775807');
final BigInt _javaLongMin = BigInt.parse('-9223372036854775808');
const double _javaLongMaxAsDouble = 9223372036854775807.0;
const double _javaLongMinAsDouble = -9223372036854775808.0;
const double _maxSafeIntegerAsDouble = 9007199254740992.0;

/// Every pure function shared by authored text and container-preview
/// expressions. Contexts add `lang`, inventory functions, PAPI and metrics.
const List<String> previewStandardFunctionNames = <String>[
  'clamp',
  'lerp',
  'min',
  'max',
  'floor',
  'ceil',
  'round',
  'abs',
  'mod',
  'sin',
  'cos',
  'pow',
  'smoothstep',
  'rgb',
  'argb',
  'alpha',
  'mix',
  'palette',
  'select',
  'number',
  'bar',
  'hex',
  'str',
  'fixed',
  'plain',
  'readable',
  'align',
  'len',
  'contains',
  'lower',
  'upper',
];

Object? previewStdFunction(String name, List<Object?> args) {
  switch (name) {
    case 'clamp':
      _requireCount(name, args, 3);
      return math.min(
        math.max(_numArg(name, args, 0), _numArg(name, args, 1)),
        _numArg(name, args, 2),
      );
    case 'lerp':
      _requireCount(name, args, 3);
      final double a = _numArg(name, args, 0);
      final double b = _numArg(name, args, 1);
      return a + (b - a) * _numArg(name, args, 2);
    case 'min':
      _requireCount(name, args, 2);
      return math.min(_numArg(name, args, 0), _numArg(name, args, 1));
    case 'max':
      _requireCount(name, args, 2);
      return math.max(_numArg(name, args, 0), _numArg(name, args, 1));
    case 'floor':
      return _oneNumArg(name, args).floorToDouble();
    case 'ceil':
      return _oneNumArg(name, args).ceilToDouble();
    case 'round':
      return previewRound(_oneNumArg(name, args));
    case 'abs':
      return _oneNumArg(name, args).abs();
    case 'mod':
      return _mod(name, args);
    case 'sin':
      return math.sin(_oneNumArg(name, args));
    case 'cos':
      return math.cos(_oneNumArg(name, args));
    case 'pow':
      _requireCount(name, args, 2);
      final double power = math
          .pow(_numArg(name, args, 0), _numArg(name, args, 1))
          .toDouble();
      if (!power.isFinite) {
        throw const PExprException(
          'pow result must be finite',
          previewNoPosition,
        );
      }
      return power;
    case 'smoothstep':
      _requireCount(name, args, 3);
      final double lower = _numArg(name, args, 0);
      final double upper = _numArg(name, args, 1);
      // Edges are checked before argument 3 is read, exactly like
      // `ExprFunctions.smoothstep`, so a call that is wrong in both ways
      // reports the same failure in both engines.
      if (lower == upper) {
        throw const PExprException(
          'smoothstep edges must differ',
          previewNoPosition,
        );
      }
      final double value = _numArg(name, args, 2);
      final double t = math.min(
        1.0,
        math.max(0.0, (value - lower) / (upper - lower)),
      );
      return t * t * (3.0 - 2.0 * t);
    case 'rgb':
      _requireCount(name, args, 3);
      return _packArgb(
        0xFF,
        _clampChannel(_numArg(name, args, 0)),
        _clampChannel(_numArg(name, args, 1)),
        _clampChannel(_numArg(name, args, 2)),
      );
    case 'argb':
      _requireCount(name, args, 4);
      return _packArgb(
        _clampChannel(_numArg(name, args, 0)),
        _clampChannel(_numArg(name, args, 1)),
        _clampChannel(_numArg(name, args, 2)),
        _clampChannel(_numArg(name, args, 3)),
      );
    case 'alpha':
      return _alpha(name, args);
    case 'mix':
      return _mix(name, args);
    case 'palette':
      return _palette(name, args);
    case 'select':
      return _select(name, args);
    case 'number':
      return _number(name, args);
    case 'bar':
      return _bar(name, args);
    case 'hex':
      return _hex(name, args);
    case 'str':
      _requireCount(name, args, 1);
      return previewStringify(args[0]);
    case 'fixed':
      return _fixed(name, args);
    case 'plain':
      _requireCount(name, args, 1);
      return _strArg(name, args, 0).replaceAll(_legacyCode, '');
    case 'readable':
      _requireCount(name, args, 1);
      return previewReadable(_strArg(name, args, 0));
    case 'align':
      return _align(name, args);
    case 'len':
      _requireCount(name, args, 1);
      final Object? sized = args[0];
      if (sized is List<Object?>) return sized.length.toDouble();
      return _strArg(name, args, 0).length.toDouble();
    case 'contains':
      _requireCount(name, args, 2);
      final Object? haystack = args[0];
      if (haystack is List<Object?>) return haystack.contains(args[1]);
      return _strArg(name, args, 0).contains(previewStringify(args[1]));
    case 'lower':
      _requireCount(name, args, 1);
      return _strArg(name, args, 0).toLowerCase();
    case 'upper':
      _requireCount(name, args, 1);
      return _strArg(name, args, 0).toUpperCase();
    default:
      return null;
  }
}

String _align(String name, List<Object?> args) {
  _requireCount(name, args, 3);
  final String value = _strArg(name, args, 0);
  final double widthValue = _numArg(name, args, 1);
  if (widthValue != widthValue.roundToDouble() ||
      widthValue < 1 ||
      widthValue > 16384) {
    throw PExprException(
      '{function} argument 2 must be a whole number in [1, 16384]',
      previewNoPosition,
      <String, Object?>{'function': name},
    );
  }
  final String mode = _strArg(name, args, 2).toLowerCase();
  final int remaining = math
      .max(0, widthValue.toInt() - _alignmentVisibleCodePoints(value))
      .toInt();
  final int leading = switch (mode) {
    'left' => 0,
    'center' => remaining ~/ 2,
    'middle' => remaining ~/ 2,
    'right' => remaining,
    _ => throw PExprException(
      "{function} argument 3 must be 'left', 'center', 'middle', or 'right'",
      previewNoPosition,
      <String, Object?>{'function': name},
    ),
  };
  return ' ' * leading + value + ' ' * (remaining - leading);
}

int _alignmentVisibleCodePoints(String value) {
  int visible = 0;
  int index = 0;
  while (index < value.length) {
    final int formattingLength = _alignmentFormattingLength(value, index);
    if (formattingLength > 0) {
      index += formattingLength;
      continue;
    }
    final int first = value.codeUnitAt(index);
    if (first >= 0xD800 &&
        first <= 0xDBFF &&
        index + 1 < value.length &&
        value.codeUnitAt(index + 1) >= 0xDC00 &&
        value.codeUnitAt(index + 1) <= 0xDFFF) {
      index += 2;
    } else {
      index++;
    }
    visible++;
  }
  return visible;
}

int _alignmentFormattingLength(String value, int index) {
  final String marker = value[index];
  if ((marker == '&' || marker == '§') && index + 1 < value.length) {
    final String code = value[index + 1].toLowerCase();
    if ('0123456789abcdefklmnor'.contains(code)) return 2;
    if ((code == 'x' || value[index + 1] == '#') &&
        index + 8 <= value.length &&
        _alignmentHex(value, index + 2, 6)) {
      return 8;
    }
    if (code == 'x' && index + 14 <= value.length) {
      for (int offset = 2; offset < 14; offset += 2) {
        if (value[index + offset] != marker ||
            !_alignmentHex(value, index + offset + 1, 1)) {
          return 0;
        }
      }
      return 14;
    }
  }
  if (marker == '[' &&
      index + 8 <= value.length &&
      value[index + 7] == ']' &&
      _alignmentHex(value, index + 1, 6)) {
    return 8;
  }
  return 0;
}

bool _alignmentHex(String value, int offset, int length) {
  for (int index = offset; index < offset + length; index++) {
    final int unit = value.codeUnitAt(index);
    if (!(unit >= 0x30 && unit <= 0x39 ||
        unit >= 0x41 && unit <= 0x46 ||
        unit >= 0x61 && unit <= 0x66)) {
      return false;
    }
  }
  return true;
}

// ---------------------------------------------------------------------------
// Math
// ---------------------------------------------------------------------------

/// `floorMod` semantics on doubles: `a - floor(a / b) * b`. A zero divisor
/// throws exactly like the `%` operator rather than producing NaN, so the two
/// remainder operations agree and both stay pinnable in the vector file.
double _mod(String name, List<Object?> args) {
  _requireCount(name, args, 2);
  final double a = _numArg(name, args, 0);
  final double b = _numArg(name, args, 1);
  if (b == 0.0) {
    throw const PExprException('division by zero', previewNoPosition);
  }
  return a - (a / b).floorToDouble() * b;
}

// ---------------------------------------------------------------------------
// Colour
// ---------------------------------------------------------------------------

double _alpha(String name, List<Object?> args) {
  _requireCount(name, args, 2);
  final int color = previewNarrowArgb(_numArg(name, args, 0));
  final int a = _clampChannel(_numArg(name, args, 1));
  return ((color % 0x1000000) + a * 0x1000000).toDouble();
}

/// Per-channel linear blend (including alpha): `round(a + (b - a) * t)` with
/// `t` clamped to [0, 1].
double _mix(String name, List<Object?> args) {
  _requireCount(name, args, 3);
  final int c1 = previewNarrowArgb(_numArg(name, args, 0));
  final int c2 = previewNarrowArgb(_numArg(name, args, 1));
  final double t = math.min(1.0, math.max(0.0, _numArg(name, args, 2)));
  return _packArgb(
    _mixChannel(_channel(c1, 0x1000000), _channel(c2, 0x1000000), t),
    _mixChannel(_channel(c1, 0x10000), _channel(c2, 0x10000), t),
    _mixChannel(_channel(c1, 0x100), _channel(c2, 0x100), t),
    _mixChannel(_channel(c1, 1), _channel(c2, 1), t),
  );
}

int _mixChannel(int a, int b, double t) =>
    previewRound(a + (b - a) * t).toInt();

/// One byte of an already-narrowed ARGB value; [divisor] is `1 << shift`.
int _channel(int argb, int divisor) => (argb ~/ divisor) % 256;

int _clampChannel(double value) =>
    previewRound(math.min(255.0, math.max(0.0, value))).toInt();

double _packArgb(int a, int r, int g, int b) =>
    (a * 0x1000000 + r * 0x10000 + g * 0x100 + b).toDouble();

// ---------------------------------------------------------------------------
// Lists / strings
// ---------------------------------------------------------------------------

/// Index is `(long) floor(index)` — a saturating cast in Java — wrapped into
/// range with `floorMod`.
double _palette(String name, List<Object?> args) {
  _requireCount(name, args, 2);
  final Object? listArg = args[0];
  if (listArg is! List<Object?>) {
    throw PExprException(
      '{function} argument 1 must be a list',
      previewNoPosition,
      <String, Object?>{'function': name},
    );
  }
  if (listArg.isEmpty) {
    throw PExprException(
      '{function} list must not be empty',
      previewNoPosition,
      <String, Object?>{'function': name},
    );
  }
  final int index = _floorModSignedLong(_numArg(name, args, 1), listArg.length);
  final Object? item = listArg[index];
  if (item is! double) {
    throw PExprException(
      '{function} list entries must be numbers',
      previewNoPosition,
      <String, Object?>{'function': name},
    );
  }
  return item;
}

Object? _select(String name, List<Object?> args) {
  _requireCount(name, args, 2);
  final Object? listArg = args[0];
  if (listArg is! List<Object?>) {
    throw PExprException(
      '{function} argument 1 must be a list',
      previewNoPosition,
      <String, Object?>{'function': name},
    );
  }
  if (listArg.isEmpty) {
    throw PExprException(
      '{function} list must not be empty',
      previewNoPosition,
      <String, Object?>{'function': name},
    );
  }
  final int index = _floorModSignedLong(_numArg(name, args, 1), listArg.length);
  return listArg[index];
}

double _number(String name, List<Object?> args) {
  _requireCount(name, args, 1);
  final Object? value = args[0];
  if (value is double) return value;
  if (value is! String) {
    throw PExprException(
      '{function} argument 1 must be a number or string',
      previewNoPosition,
      <String, Object?>{'function': name},
    );
  }
  final String plain = value
      .replaceAll(RegExp(r'[&§][0-9A-Fa-fK-Ok-oRr]'), '')
      .replaceAll(',', '');
  final Match? match = RegExp(
    r'[-+]?(?:\d+(?:\.\d*)?|\.\d+)',
  ).firstMatch(plain);
  if (match == null) {
    throw PExprException(
      '{function} could not find a number',
      previewNoPosition,
      <String, Object?>{'function': name},
    );
  }
  return double.parse(match.group(0)!);
}

String _bar(String name, List<Object?> args) {
  _requireCount(name, args, 5);
  final double value = _numArg(name, args, 0);
  final double maximum = _numArg(name, args, 1);
  final double widthValue = _numArg(name, args, 2);
  if (maximum <= 0) {
    throw PExprException(
      '{function} argument 2 must be greater than zero',
      previewNoPosition,
      <String, Object?>{'function': name},
    );
  }
  if (widthValue != widthValue.roundToDouble() ||
      widthValue < 1 ||
      widthValue > 64) {
    throw PExprException(
      '{function} argument 3 must be a whole number in [1, 64]',
      previewNoPosition,
      <String, Object?>{'function': name},
    );
  }
  final int width = widthValue.toInt();
  final int count = previewRound(
    math.min(1.0, math.max(0.0, value / maximum)) * width,
  ).toInt();
  return _strArg(name, args, 3) * count +
      _strArg(name, args, 4) * (width - count);
}

String _hex(String name, List<Object?> args) {
  _requireCount(name, args, 1);
  final int color = previewNarrowArgb(_numArg(name, args, 0)) & 0xFFFFFF;
  return '[${color.toRadixString(16).padLeft(6, '0').toUpperCase()}]';
}

String _fixed(String name, List<Object?> args) {
  _requireCount(name, args, 2);
  final double x = _numArg(name, args, 0);
  final double digitsArg = _numArg(name, args, 1);
  // digits must be a whole number in [0, 20]: negative/fractional precision is
  // meaningless to String.format, and >20 is a RangeError in Dart's
  // toStringAsFixed, so the shared range keeps both engines identical.
  if (digitsArg != digitsArg.roundToDouble() ||
      digitsArg < 0 ||
      digitsArg > 20) {
    throw PExprException(
      '{function} argument 2 (digits) must be a whole number in [0, 20]',
      previewNoPosition,
      <String, Object?>{'function': name},
    );
  }
  final int digits = digitsArg.toInt();
  if (!x.isFinite) {
    // Java's %f writes "NaN" / "Infinity" / "-Infinity", same as Dart's
    // toString, which is what toStringAsFixed falls back to anyway.
    return x.toString();
  }
  return _javaFixed(x, digits);
}

/// Java `String.format("%.Nf", x)`.
///
/// Java rounds the *shortest* decimal representation of the double half-up, not
/// the exact binary value: `fixed(1.005, 2)` is `1.01` there even though the
/// double really is 1.00499999999999989. Dart's [double.toStringAsFixed] rounds
/// the exact value and answers `1.00`, and above 1e21 it gives up and returns
/// `1e+21`, so neither end of the range can use it.
String _javaFixed(double value, int digits) {
  final double magnitude = value.abs();
  // Shortest significant digits, positioned so that magnitude is
  // `0.<sig> * 10^decExp`.
  final String exponential = magnitude.toStringAsExponential();
  final int marker = exponential.indexOf('e');
  final String sig = exponential.substring(0, marker).replaceFirst('.', '');
  final int decExp = int.parse(exponential.substring(marker + 1)) + 1;

  final int keep = decExp + digits;
  final String roundedSig;
  final int roundedExp;
  if (keep >= sig.length) {
    roundedSig = sig;
    roundedExp = decExp;
  } else if (keep < 0) {
    roundedSig = '0';
    roundedExp = 1;
  } else if (keep == 0) {
    // Every significant digit falls outside the precision: the value either
    // rounds up into the last kept place or vanishes.
    roundedSig = sig.codeUnitAt(0) >= _digitFive ? '1' : '0';
    roundedExp = sig.codeUnitAt(0) >= _digitFive ? decExp + 1 : 1;
  } else if (sig.codeUnitAt(keep) < _digitFive) {
    roundedSig = sig.substring(0, keep);
    roundedExp = decExp;
  } else {
    final List<int> units = sig.substring(0, keep).codeUnits.toList();
    int carry = keep - 1;
    while (carry >= 0 && units[carry] == _digitNine) {
      units[carry] = _digitZero;
      carry--;
    }
    if (carry < 0) {
      // All nines: 9.99 at one digit becomes 10.0, one place wider.
      roundedSig = '1';
      roundedExp = decExp + 1;
    } else {
      units[carry]++;
      roundedSig = String.fromCharCodes(units);
      roundedExp = decExp;
    }
  }

  final String integer;
  String fraction;
  if (roundedExp <= 0) {
    integer = '0';
    fraction = '${'0' * -roundedExp}$roundedSig';
  } else if (roundedExp >= roundedSig.length) {
    integer = '$roundedSig${'0' * (roundedExp - roundedSig.length)}';
    fraction = '';
  } else {
    integer = roundedSig.substring(0, roundedExp);
    fraction = roundedSig.substring(roundedExp);
  }
  if (fraction.length < digits) {
    fraction = fraction.padRight(digits, '0');
  }
  final String sign = value.isNegative ? '-' : '';
  return digits == 0 ? '$sign$integer' : '$sign$integer.$fraction';
}

const int _digitZero = 0x30;
const int _digitFive = 0x35;
const int _digitNine = 0x39;

/// Legacy colour/format codes: `&` followed by a colour digit, a format letter,
/// or reset. Everything else beginning with `&` is left alone.
final RegExp _legacyCode = RegExp(r'&[0-9A-Fa-fK-Ok-oRr]');

/// Turns an enum-style id into display text: `IRON_ORE` becomes `Iron Ore`.
///
/// Registry namespaces are omitted and separators collapse to one space.
/// Joining words stay lowercase after the first word.
String previewReadable(String value) {
  final String trimmed = value.trim();
  final String bare = trimmed.substring(trimmed.indexOf(':') + 1).toLowerCase();
  const Set<String> joining = <String>{'a', 'an', 'and', 'in', 'o', 'of', 'on', 'the', 'with'};
  final StringBuffer result = StringBuffer();
  for (final String word in bare.split(RegExp(r'[_\s-]+')).where((String word) => word.isNotEmpty)) {
    final bool first = result.isEmpty;
    if (!first) result.write(' ');
    result.write(!first && joining.contains(word) ? word : '${word[0].toUpperCase()}${word.substring(1)}');
  }
  return result.toString();
}



// ---------------------------------------------------------------------------
// Argument helpers
// ---------------------------------------------------------------------------

double _oneNumArg(String name, List<Object?> args) {
  _requireCount(name, args, 1);
  return _numArg(name, args, 0);
}

void _requireCount(String name, List<Object?> args, int count) {
  if (args.length != count) {
    throw PExprException(
      'Required argument count for {function}: {count}; received: {actual}',
      previewNoPosition,
      <String, Object?>{
        'function': name,
        'count': count,
        'actual': args.length,
      },
    );
  }
}

double _numArg(String name, List<Object?> args, int index) {
  final Object? value = args[index];
  if (value is double) {
    return value;
  }
  throw PExprException(
    '{function} argument {index} must be a number',
    previewNoPosition,
    <String, Object?>{'function': name, 'index': index + 1},
  );
}

String _strArg(String name, List<Object?> args, int index) {
  final Object? value = args[index];
  if (value is String) {
    return value;
  }
  throw PExprException(
    '{function} argument {index} must be a string',
    previewNoPosition,
    <String, Object?>{'function': name, 'index': index + 1},
  );
}

int _floorModSignedLong(double value, int divisor) {
  if (value.isNaN) return 0;
  final double floored = value.floorToDouble();
  if (floored.abs() < _maxSafeIntegerAsDouble) {
    return floored.toInt() % divisor;
  }
  final BigInt narrowed = floored >= _javaLongMaxAsDouble
      ? _javaLongMax
      : floored <= _javaLongMinAsDouble
          ? _javaLongMin
          : BigInt.from(floored);
  return (narrowed % BigInt.from(divisor)).toInt();
}
