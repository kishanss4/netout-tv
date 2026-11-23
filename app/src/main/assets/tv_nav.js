(function () {
  if (window.__tvNav) return;
  window.__tvNav = true;

  // ---------- cosmetic ad hiding + focus ring ----------
  var css = document.createElement('style');
  css.textContent = [
    'ins.adsbygoogle,[id^="google_ads_iframe"],iframe[src*="doubleclick"],',
    'iframe[src*="googlesyndication"],iframe[src*="adsterra"],iframe[src*="exoclick"],',
    'iframe[src*="propeller"],iframe[src*="popads"],div[id^="div-gpt-ad"],',
    '[class*="adsbygoogle"],[id*="taboola"],[id*="outbrain"]{display:none!important}',
    '.tv-focus{outline:4px solid #ffcc00!important;outline-offset:3px!important;',
    'box-shadow:0 0 0 7px rgba(0,0,0,.55)!important;border-radius:6px;',
    'transition:outline-color .1s}',
    '*{cursor:none!important}'
  ].join('');
  (document.head || document.documentElement).appendChild(css);

  // popups are denied natively; this is a second layer
  try { window.open = function () { return null; }; } catch (e) {}

  // ---------- spatial navigation ----------
  var SEL = 'a[href],button,input:not([type=hidden]),select,textarea,summary,' +
            '[tabindex]:not([tabindex="-1"]),[onclick],[role=button],[role=link],' +
            '[role=tab],[role=menuitem],[role=option],[role=checkbox],[role=switch]';
  var current = null;

  function visible(el) {
    var r = el.getBoundingClientRect();
    if (r.width < 8 || r.height < 8) return false;
    if (r.bottom < -2000 || r.top > window.innerHeight + 4000) return false;
    var cs = getComputedStyle(el);
    return cs.visibility !== 'hidden' && cs.display !== 'none' && cs.opacity !== '0' &&
           cs.pointerEvents !== 'none';
  }

  function candidates() {
    var set = new Set(document.querySelectorAll(SEL));
    // elements that look clickable (cursor:pointer) but are plain divs/spans
    var all = document.querySelectorAll('div,span,li,img,svg,p,h1,h2,h3,h4');
    for (var i = 0; i < all.length && i < 6000; i++) {
      var el = all[i];
      var cs = getComputedStyle(el);
      if (cs.cursor === 'pointer' || el.__tvPtr) {
        var p = el.parentElement;
        if (p && p.__tvPtr) continue;       // keep only outermost
        el.__tvPtr = true;
        set.add(el);
      }
    }
    var out = [];
    set.forEach(function (el) {
      if (el.disabled) return;
      if (visible(el)) {
        // drop container if it holds other candidates of its own
        out.push(el);
      }
    });
    // remove ancestors that contain another candidate (prefer the leaf)
    return out.filter(function (a) {
      for (var i = 0; i < out.length; i++) {
        if (out[i] !== a && a.contains(out[i]) && a.tagName !== 'A' && a.tagName !== 'BUTTON') return false;
      }
      return true;
    });
  }

  function setFocus(el) {
    if (current) current.classList.remove('tv-focus');
    current = el;
    el.classList.add('tv-focus');
    try {
      if (!el.matches('a,button,input,select,textarea,summary,[tabindex]')) el.setAttribute('tabindex', '-1');
      el.focus({ preventScroll: true });
    } catch (e) {}
    try { el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'smooth' }); } catch (e) {}
  }

  function center(r) { return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; }

  function move(dir) {
    var list = candidates();
    if (!list.length) return false;
    if (!current || !document.contains(current) || !visible(current)) {
      // start with the first item currently on screen
      var first = list.filter(function (e) {
        var r = e.getBoundingClientRect();
        return r.top >= 0 && r.top < window.innerHeight && r.left >= 0 && r.left < window.innerWidth;
      }).sort(function (a, b) {
        var ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect();
        return (ra.top - rb.top) || (ra.left - rb.left);
      })[0] || list[0];
      setFocus(first);
      return true;
    }
    var cr = current.getBoundingClientRect(), cc = center(cr), best = null, bestScore = Infinity;
    list.forEach(function (el) {
      if (el === current || current.contains(el) || el.contains(current)) return;
      var r = el.getBoundingClientRect(), c = center(r), dx = c.x - cc.x, dy = c.y - cc.y;
      var main, cross, ok;
      if (dir === 'ArrowRight') { ok = r.left >= cr.right - 4 || dx > cr.width / 2; main = dx; cross = Math.abs(dy); }
      else if (dir === 'ArrowLeft') { ok = r.right <= cr.left + 4 || -dx > cr.width / 2; main = -dx; cross = Math.abs(dy); }
      else if (dir === 'ArrowDown') { ok = r.top >= cr.bottom - 4 || dy > cr.height / 2; main = dy; cross = Math.abs(dx); }
      else { ok = r.bottom <= cr.top + 4 || -dy > cr.height / 2; main = -dy; cross = Math.abs(dx); }
      if (!ok || main <= 0) return;
      var score = main + cross * 2.5;
      if (score < bestScore) { bestScore = score; best = el; }
    });
    if (best) { setFocus(best); return true; }
    // nothing further: scroll the page so lazy content can load
    if (dir === 'ArrowDown') window.scrollBy({ top: Math.round(window.innerHeight * 0.6), behavior: 'smooth' });
    if (dir === 'ArrowUp') window.scrollBy({ top: -Math.round(window.innerHeight * 0.6), behavior: 'smooth' });
    return dir === 'ArrowDown' || dir === 'ArrowUp';
  }

  function isTyping(el) {
    return el && (el.tagName === 'TEXTAREA' ||
      (el.tagName === 'INPUT' && /^(text|search|email|url|password|tel|number)$/i.test(el.type)) ||
      el.isContentEditable);
  }

  function bigVideo() {
    var vs = document.querySelectorAll('video'), best = null, area = 0;
    for (var i = 0; i < vs.length; i++) {
      var r = vs[i].getBoundingClientRect(), a = r.width * r.height;
      if (a > area && !vs[i].paused) { area = a; best = vs[i]; }
    }
    return best || vs[0] || null;
  }

  // used by native code for seeking / play-pause in fullscreen video
  window.__tvSeek = function (s) { var v = bigVideo(); if (v) v.currentTime = Math.max(0, v.currentTime + s); };
  window.__tvToggle = function () { var v = bigVideo(); if (v) { v.paused ? v.play() : v.pause(); } };

  window.addEventListener('keydown', function (e) {
    var k = e.key;
    if (k === 'ArrowUp' || k === 'ArrowDown' || k === 'ArrowLeft' || k === 'ArrowRight') {
      var t = document.activeElement;
      if (isTyping(t) && (k === 'ArrowLeft' || k === 'ArrowRight')) return;
      // video playing fullscreen in-page: left/right seek
      if (document.fullscreenElement && (k === 'ArrowLeft' || k === 'ArrowRight')) {
        window.__tvSeek(k === 'ArrowRight' ? 10 : -10); e.preventDefault(); return;
      }
      if (move(k)) { e.preventDefault(); e.stopPropagation(); }
    } else if (k === 'Enter') {
      var el = current && document.contains(current) ? current : null;
      if (el && !isTyping(el) && el.tagName !== 'SELECT') {
        e.preventDefault(); e.stopPropagation();
        el.click();
      }
    }
  }, true);

  // when the page changes (SPA), forget stale focus
  new MutationObserver(function () {
    if (current && !document.contains(current)) current = null;
  }).observe(document.documentElement, { childList: true, subtree: true });
})();
