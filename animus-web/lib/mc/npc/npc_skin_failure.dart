/// What the preview says when a skin PNG does not load: the cause in plain
/// terms and what to do next.
library;

final RegExp _status = RegExp(r'HTTP (\d{3})');

String skinFailureText(Object failure) {
  if (failure is FormatException) {
    final String message = failure.message;
    if (message.startsWith('A skin is')) return '$message Pick a 64x64 Minecraft skin PNG.';
    return '$message Paste the full address of the PNG.';
  }
  final RegExpMatch? status = _status.firstMatch('$failure');
  if (status != null) {
    final String code = status.group(1)!;
    return code == '404'
        ? 'Nothing is at that address (HTTP 404). Check the URL, or save the PNG and use Load skin PNG.'
        : 'The address answered with HTTP $code. Try again later, or save the PNG and use Load skin PNG.';
  }
  return 'The skin could not be read: the file is not a PNG, or the site does not let other pages read its images. '
      'Save the PNG and use Load skin PNG instead.';
}
