(function () {
  if (window.__tvNav) return;
  window.__tvNav = true;

  var TV_WIDTH = 1536;
  var VIEWPORT = 'width=' + TV_WIDTH + ', user-scalable=no';

  // 1. Force TV Desktop Viewport
  function fixViewport() {
    try {
      var head = document.head || document.documentElement;
      if (!head) return;
      var m = document.querySelector('meta[name="viewport"]');
      if (!m) {
        m = document.createElement('meta');
        m.name = 'viewport';
        head.appendChild(m);
      }
      if (m.getAttribute('content') !== VIEWPORT) m.setAttribute('content', VIEWPORT);
    } catch (e) {}
  }
  fixViewport();
  try {
    new MutationObserver(fixViewport).observe(document.documentElement, {
      childList: true, subtree: true, attributes: true, attributeFilter: ['content', 'name']
    });
  } catch (e) {}

  // 2. CSS: hide ads, clean outline, focus ring style
  var css = document.createElement('style');
  css.textContent = [
    'ins.adsbygoogle,[id^="google_ads_iframe"],iframe[src*="doubleclick"],',
    'iframe[src*="googlesyndication"],iframe[src*="adsterra"],iframe[src*="exoclick"],',
    'iframe[src*="propeller"],iframe[src*="popads"],div[id^="div-gpt-ad"],',
    '[class*="adsbygoogle"],[id*="taboola"],[id*="outbrain"]{display:none!important}',
    ':focus{outline:none!important}',
    '*{-webkit-tap-highlight-color:transparent}',
    '#__tv_ring{position:fixed;left:0;top:0;z-index:2147483647;pointer-events:none;',
    'box-sizing:border-box;border:4px solid #ffd400;border-radius:10px;display:none;',
    'box-shadow:0 0 0 2px rgba(0,0,0,.85),0 0 24px 6px rgba(255,212,0,.65);',
    'transition:transform .08s ease-out, width .08s ease-out, height .08s ease-out;will-change:transform}'
  ].join('');
  (document.head || document.documentElement).appendChild(css);

  try { window.open = function () { return null; }; } catch (e) {}

  // 3. Focus Ring
  var ring = document.createElement('div');
  ring.id = '__tv_ring';
  document.documentElement.appendChild(ring);

  var current = null;
  var lastMove = 0;
  var typing = false;
  var raf = window.requestAnimationFrame || function (f) { return setTimeout(f, 16); };

  function log(msg) {
    console.log('[TV-NAV] ' + msg);
    try {
      if (window.TvBridge && window.TvBridge.log) {
        window.TvBridge.log(String(msg));
      }
    } catch (e) {}
  }

  function placeRing() {
    if (!current || !current.isConnected) { ring.style.display = 'none'; return; }
    var r = current.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) { ring.style.display = 'none'; return; }
    ring.style.display = 'block';
    ring.style.width = (r.width + 10) + 'px';
    ring.style.height = (r.height + 10) + 'px';
    ring.style.transform = 'translate3d(' + (r.left - 5) + 'px,' + (r.top - 5) + 'px,0)';
  }

  function queueRing() {
    raf(placeRing);
  }
  window.addEventListener('scroll', queueRing, { capture: true, passive: true });
  window.addEventListener('resize', queueRing, { passive: true });

  // 4. Actionable Selectors - Specific to NetOut and TV interactions
  var SEL = [
    'a[href]',
    'button',
    'input:not([type=hidden])',
    'select',
    'textarea',
    'summary',
    '[tabindex]:not([tabindex="-1"])',
    '[role=button]',
    '[role=link]',
    '[role=tab]',
    '.card',
    '.tmdb-movie-card',
    '.cw-item',
    '.movie-card',
    '.result-item',
    '.result-poster',
    '.select-prof-btn',
    '.userIt-hv',
    '.gms-action-button',
    '.play-button',
    '.play-button2',
    '.hp-btn',
    '.hp-media',
    '.nav-tab',
    '.episode-tab',
    '.collection-tab',
    '.content-card',
    '.profile-screen button'
  ].join(',');

  function isVisible(el) {
    if (!el || !el.isConnected) return false;
    if (el.disabled || el.id === '__tv_ring') return false;
    var cs = window.getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden' || cs.opacity === '0') return false;
    var r = el.getBoundingClientRect();
    if (r.width < 6 || r.height < 6) return false;
    return true;
  }

  function getFocusables() {
    var nodes = document.querySelectorAll(SEL);
    var list = [];
    var vw = window.innerWidth;
    var vh = window.innerHeight;

    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (!isVisible(el)) continue;

      // Don't include elements deeply off-screen vertically (keep within +/- 2 screens)
      var r = el.getBoundingClientRect();
      if (r.bottom < -vh * 1.5 || r.top > vh * 2.5) continue;

      list.push({ el: el, r: r });
    }
    return list;
  }

  function center(r) {
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  }

  function reveal(el) {
    try {
      el.scrollIntoView({
        block: 'nearest',
        inline: 'center',
        behavior: 'smooth'
      });
    } catch (e) {}
  }

  function setFocus(el) {
    if (!el) return;
    if (current === el) {
      placeRing();
      return;
    }

    // Leave old card
    if (current) {
      try {
        current.dispatchEvent(new MouseEvent('mouseout', { bubbles: true, cancelable: true }));
      } catch (e) {}
    }

    current = el;
    log('Focused: ' + (el.className || el.tagName) + ' text=' + (el.innerText || '').slice(0, 20));

    try {
      if (!el.matches('a,button,input,select,textarea')) {
        el.setAttribute('tabindex', '-1');
      }
      el.focus({ preventScroll: true });
    } catch (e) {}

    // Dispatch mouseover so Netout shows hover preview / triggers actions
    try {
      el.dispatchEvent(new MouseEvent('mouseover', { bubbles: true, cancelable: true }));
    } catch (e) {}

    reveal(el);
    placeRing();
    setTimeout(placeRing, 100);
    setTimeout(placeRing, 300);
  }

  // Find scrollable row parent
  function findScrollRow(el) {
    var p = el ? el.parentElement : null;
    while (p && p !== document.body && p !== document.documentElement) {
      if (p.scrollWidth > p.clientWidth + 20) {
        return p;
      }
      p = p.parentElement;
    }
    return null;
  }

  function move(dir, isRepeat) {
    var list = getFocusables();
    if (!list.length) return false;

    // Initial focus if nothing focused
    if (!current || !current.isConnected || !isVisible(current)) {
      var vh = window.innerHeight;
      var vw = window.innerWidth;
      // Pick first element currently visible on screen
      var onscreen = list.filter(function (o) {
        return o.r.top >= 0 && o.r.top < vh && o.r.left >= 0 && o.r.left < vw;
      }).sort(function (a, b) {
        return (a.r.top - b.r.top) || (a.r.left - b.r.left);
      });

      var initial = onscreen.length ? onscreen[0].el : list[0].el;
      setFocus(initial);
      return true;
    }

    var cr = current.getBoundingClientRect();
    var cc = center(cr);
    var scored = [];

    for (var i = 0; i < list.length; i++) {
      var o = list[i];
      var el = o.el;
      if (el === current || current.contains(el) || el.contains(current)) continue;

      var r = o.r;
      var c = center(r);
      var dx = c.x - cc.x;
      var dy = c.y - cc.y;
      var ok = false;
      var main = 0;
      var cross = 0;
      var overlap = false;

      if (dir === 'ArrowRight') {
        ok = r.left >= cr.right - 8 || dx > 15;
        main = dx;
        cross = Math.abs(dy);
        overlap = r.top < cr.bottom - 4 && r.bottom > cr.top + 4;
      } else if (dir === 'ArrowLeft') {
        ok = r.right <= cr.left + 8 || -dx > 15;
        main = -dx;
        cross = Math.abs(dy);
        overlap = r.top < cr.bottom - 4 && r.bottom > cr.top + 4;
      } else if (dir === 'ArrowDown') {
        ok = r.top >= cr.bottom - 8 || dy > 15;
        main = dy;
        cross = Math.abs(dx);
        overlap = r.left < cr.right - 4 && r.right > cr.left + 4;
      } else if (dir === 'ArrowUp') {
        ok = r.bottom <= cr.top + 8 || -dy > 15;
        main = -dy;
        cross = Math.abs(dx);
        overlap = r.left < cr.right - 4 && r.right > cr.left + 4;
      }

      if (!ok || main <= 0) continue;

      // Primary score: penalize cross-axis distance heavily unless overlapping
      var score = main + cross * (overlap ? 0.8 : 3.5);
      scored.push({ el: el, score: score });
    }

    if (scored.length) {
      scored.sort(function (a, b) { return a.score - b.score; });
      setFocus(scored[0].el);
      return true;
    }

    // Nothing found in that direction:
    // If moving horizontally (Left/Right), try scrolling the container row
    if (dir === 'ArrowRight' || dir === 'ArrowLeft') {
      var row = findScrollRow(current);
      if (row) {
        var scrollDelta = Math.round(row.clientWidth * 0.7) * (dir === 'ArrowRight' ? 1 : -1);
        row.scrollBy({ left: scrollDelta, behavior: 'smooth' });
        log('Scrolled row: ' + scrollDelta + 'px');
        setTimeout(function () {
          // After scrolling, try moving focus to newly visible card
          var nextList = getFocusables();
          for (var j = 0; j < nextList.length; j++) {
            if (dir === 'ArrowRight' && nextList[j].r.left > cr.left + 20) {
              setFocus(nextList[j].el);
              break;
            }
          }
        }, 150);
        return true;
      }
    }

    // If moving vertically (Up/Down) at edge, scroll page
    if (dir === 'ArrowDown') {
      window.scrollBy({ top: Math.round(window.innerHeight * 0.6), behavior: 'smooth' });
      queueRing();
      return true;
    } else if (dir === 'ArrowUp') {
      window.scrollBy({ top: -Math.round(window.innerHeight * 0.6), behavior: 'smooth' });
      queueRing();
      return true;
    }

    return true;
  }

  function handleKey(k, isRepeat) {
    log('handleKey: ' + k + ' repeat=' + isRepeat);

    if (k === 'ArrowUp' || k === 'ArrowDown' || k === 'ArrowLeft' || k === 'ArrowRight') {
      if (typing && (k === 'ArrowLeft' || k === 'ArrowRight')) return false;

      var now = Date.now();
      if (isRepeat && now - lastMove < 80) return true;
      lastMove = now;

      return move(k, isRepeat);
    } else if (k === 'Enter') {
      var el = current && current.isConnected ? current : null;
      if (!el) {
        // If nothing focused, focus first element on Enter
        move('ArrowDown', false);
        return true;
      }

      log('Enter pressed on: ' + (el.className || el.tagName));
      if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
        if (typing) { typing = false; return false; }
        typing = true;
        try { el.focus(); } catch (x) {}
        try { window.TvBridge && TvBridge.showKeyboard(); } catch (x) {}
        return true;
      }

      if (!isRepeat) {
        try {
          el.click();
        } catch (e) {
          log('Click error: ' + e);
        }
      }
      return true;
    }
    return false;
  }

  // Global hooks for Native Android / Web Remote Debugger
  window.__tvKey = function (key, isRepeat) {
    return handleKey(key, isRepeat);
  };

  window.__tvFocus = function (selector) {
    var el = document.querySelector(selector);
    if (el) setFocus(el);
  };

  window.__tvGetInfo = function () {
    return {
      url: window.location.href,
      current: current ? (current.className || current.tagName) : null,
      focusablesCount: getFocusables().length
    };
  };

  // Keyboard listener fallback
  window.addEventListener('keydown', function (e) {
    var k = e.key;
    if (k === 'ArrowUp' || k === 'ArrowDown' || k === 'ArrowLeft' || k === 'ArrowRight' || k === 'Enter') {
      if (handleKey(k, e.repeat)) {
        e.preventDefault();
        e.stopPropagation();
      }
    }
  }, true);

  // Auto focus first visible item on load
  setTimeout(function () {
    if (!current) move('ArrowDown', false);
  }, 1000);

  setInterval(function () {
    if (current) placeRing();
  }, 350);

  log('tv_nav.js loaded successfully');
})();
