// Pre-paint theme stamp, so there is no light/dark flash before the Dart
// bundle starts. A file rather than an inline script: the CSP allows only
// scripts from this origin.
(function () {
  var mode = 'dark';
  try {
    var stored = window.localStorage.getItem('animus.theme');
    if (stored === 'light' || stored === 'dark') { mode = stored; }
  } catch (e) { /* private mode */ }
  var root = document.documentElement;
  root.classList.remove('light', 'dark');
  root.classList.add(mode);
  var color = document.getElementById('animus-theme-color');
  if (color) { color.setAttribute('content', mode === 'light' ? '#f5f3f4' : '#0d0d0f'); }
})();
