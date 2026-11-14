// Runs before paint: stamps the theme so there is no light/dark flash, and
// watches the boot splash. A file rather than an inline script: the CSP
// allows only scripts from this origin.
(function () {
  var mode = null;
  try {
    var stored = window.localStorage.getItem('animus.theme');
    if (stored === 'light' || stored === 'dark') { mode = stored; }
  } catch (e) { /* private mode */ }
  // Midnight (dark) until the light theme is picked in the overflow menu.
  if (mode === null) { mode = 'dark'; }
  var root = document.documentElement;
  root.classList.remove('light', 'dark');
  root.classList.add(mode);
  var color = document.getElementById('animus-theme-color');
  if (color) { color.setAttribute('content', mode === 'light' ? '#ffffff' : '#0a0a0a'); }

  // main.client.dart removes #loading once the app runs. If the bundle fails
  // to load, or has not started after 20 seconds, the splash says so.
  function failed() {
    var splash = document.getElementById('loading');
    if (!splash || splash.classList.contains('is-failed')) { return; }
    splash.classList.add('is-failed');
    var message = document.getElementById('animus-boot-message');
    if (message) { message.textContent = 'The editor did not load. Reload the page, or check your connection.'; }
  }
  window.addEventListener('error', function (event) {
    var target = event.target;
    if (target && target.tagName === 'SCRIPT' && /main\.client\.dart\.js/.test(target.src || '')) { failed(); }
  }, true);
  window.setTimeout(failed, 20000);
})();
