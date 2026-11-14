import 'package:web/web.dart' as web;

/// The origin of the page the editor runs in, such as
/// `https://animus.volmitsoftware.com`.
String? currentPageOrigin() => web.window.location.origin;
