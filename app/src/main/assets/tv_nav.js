(function () {
  if (window.__tvNav) return;
  window.__tvNav = true;

  // Layout width the site is rendered at. 1536 CSS px is above every common
  // "desktop" breakpoint (so no phone/tablet layout, no bottom bar) and is
  // scaled up to fill the TV. Raise it for smaller UI, lower it for bigger UI.
  var TV_WIDTH = 1536;

  // ---------------------------------------------------------------
  // 1. Viewport: force a desktop-sized layout so nothing looks zoomed
  // ---------------------------------------------------------------
  var VIEWPORT = 'width=' + TV_WIDTH + ', user-scalable=no';
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

  // ---------------------------------------------------------------
  // 2. CSS: hide ads, drop native focus outlines (we draw our own ring)
  // ---------------------------------------------------------------
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
    'box-shadow:0 0 0 2px rgba(0,0,0,.8),0 0 24px 4px rgba(255,212,0,.55);',
    'transition:transform .09s ease-out;will-change:transform}'
  ].join('');
  (document.head || document.documentElement).appendChild(css);

  try { window.open = function () { return null; }; } catch (e) {}

  // ---------------------------------------------------------------
  // 3. Focus ring: a fixed overlay, so it is never clipped by
  //    overflow:hidden carousels and is always clearly visible
  // ---------------------------------------------------------------
  var ring = document.createElement('div');
  ring.id = '__tv_ring';
  document.documentElement.appendChild(ring);

  var current = null;
  var raf = window.requestAnimationFrame || function (f) { return setTimeout(f, 16); };

  function placeRing() {
    if (!current || !current.isConnected) { ring.style.display = 'none'; return; }
    var r = current.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) { ring.style.display = 'none'; return; }
    ring.style.display = 'block';
    ring.style.width = (r.width + 8) + 'px';
    ring.style.height = (r.height + 8) + 'px';
    ring.style.transform = 'translate3d(' + (r.left - 4) + 'px,' + (r.top - 4) + 'px,0)';
  }
  function trackRing(ms) {
    var end = Date.now() + ms;
    (function f() { placeRing(); if (Date.now() < end) raf(f); })();
  }
  var ringQueued = false;
  function queueRing() {
    if (ringQueued) return;
    ringQueued = true;
    raf(function () { ringQueued = false; placeRing(); });
  }
  window.addEventListener('scroll', queueRing, { capture: true, passive: true });
  window.addEventListener('resize', queueRing, { passive: true });

  // ---------------------------------------------------------------
  // 4. Candidate tracking (incremental, cached - NOT rescanned per key)
  // ---------------------------------------------------------------
  var SEL = 'a[href],button,input:not([type=hidden]),select,textarea,summary,' +
            '[tabindex]:not([tabindex="-1"]),[onclick],[role=button],[role=link],' +
            '[role=tab],[role=menuitem],[role=option],[role=checkbox],[role=switch]';
  var PTR_TAGS = { div: 1, span: 1, li: 1, img: 1, svg: 1, p: 1, h1: 1, h2: 1, h3: 1, h4: 1, figure: 1, article: 1, section: 1 };

  var cand = new Set();
  var ptrCache = new WeakMap();
  var work = [];          // elements waiting to be classified
  var pumpTimer = null;

  function classify(el) {
    if (el.nodeType !== 1 || el.id === '__tv_ring') return;
    var tag = el.localName;
    try {
      if (el.matches(SEL)) { cand.add(el); return; }
      if (!PTR_TAGS[tag]) return;
      var v = ptrCache.get(el);
      if (v === undefined) {
        v = false;
        if (getComputedStyle(el).cursor === 'pointer') {
          var p = el.parentElement;
          // keep only the OUTERMOST pointer element (children inherit the cursor)
          v = !(p && getComputedStyle(p).cursor === 'pointer');
        }
        ptrCache.set(el, v);
      }
      if (v) cand.add(el);
    } catch (e) {}
  }

  function enqueue(root) {
    if (!root || root.nodeType !== 1) return;
    work.push(root);
    var all = root.getElementsByTagName('*');
    for (var i = 0; i < all.length; i++) work.push(all[i]);
    schedulePump();
  }

  function pump(limit) {
    var n = 0;
    while (work.length && n < limit) { classify(work.pop()); n++; }
    pumpTimer = null;
    if (work.length) schedulePump();
  }
  function schedulePump() {
    if (pumpTimer) return;
    pumpTimer = setTimeout(function () { pump(300); }, 30);
  }

  enqueue(document.documentElement);

  var pendingAdds = [];
  var addTimer = null;
  new MutationObserver(function (recs) {
    for (var i = 0; i < recs.length; i++) {
      var a = recs[i].addedNodes;
      for (var j = 0; j < a.length; j++) if (a[j].nodeType === 1) pendingAdds.push(a[j]);
    }
    if (current && !current.isConnected) current = null;
    if (!addTimer && pendingAdds.length) {
      addTimer = setTimeout(function () {
        addTimer = null;
        var list = pendingAdds; pendingAdds = [];
        for (var k = 0; k < list.length; k++) enqueue(list[k]);
        queueRing();
      }, 120);
    }
  }).observe(document.documentElement, { childList: true, subtree: true });

  // ---------------------------------------------------------------
  // 5. Spatial navigation
  // ---------------------------------------------------------------
  function visibleList() {
    pump(2500);                               // finish any unscanned DOM first
    var vw = window.innerWidth, vh = window.innerHeight, out = [];
    cand.forEach(function (el) {
      if (!el.isConnected) { cand.delete(el); return; }
      if (el.disabled || el.id === '__tv_ring') return;
      var r = el.getBoundingClientRect();
      if (r.width < 8 || r.height < 8) return;
      if (r.bottom < -vh || r.top > vh * 3 || r.right < -vw || r.left > vw * 2) return;
      out.push({ el: el, r: r });
    });
    return out;
  }

  function reachable(el, r) {
    var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    if (cx < 0 || cy < 0 || cx > window.innerWidth || cy > window.innerHeight) return true;
    var cs = getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.display === 'none' || cs.opacity === '0') return false;
    var t = document.elementFromPoint(cx, cy);
    return !t || el.contains(t) || t.contains(el);   // false if a modal/overlay covers it
  }

  function inFixedArea(el) {
    for (var n = el; n && n !== document.documentElement; n = n.parentElement) {
      var p = getComputedStyle(n).position;
      if (p === 'fixed' || p === 'sticky') return true;
    }
    return false;
  }

  function reveal(el, fast) {
    var r = el.getBoundingClientRect(), vh = window.innerHeight, vw = window.innerWidth;
    var mV = Math.round(vh * 0.16), mH = Math.round(vw * 0.06);
    var needV = r.top < mV || r.bottom > vh - mV;
    var needH = r.left < mH || r.right > vw - mH;
    if (!needV && !needH) return;
    if (needV && inFixedArea(el)) needV = false;      // don't scroll page for header/nav items
    if (!needV && !needH) return;
    var tall = r.height > vh * 0.6;
    try {
      el.scrollIntoView({
        block: needV ? (tall ? 'nearest' : 'center') : 'nearest',
        inline: needH ? 'center' : 'nearest',
        behavior: fast ? 'auto' : 'smooth'
      });
    } catch (e) {}
  }

  function setFocus(el, fast) {
    var wasTyping = typing;
    current = el;
    typing = false;
    if (wasTyping) { try { window.TvBridge && TvBridge.hideKeyboard(); } catch (e) {} }
    try {
      if (!el.matches('a,button,input,select,textarea,summary,[tabindex]')) el.setAttribute('tabindex', '-1');
      el.focus({ preventScroll: true });
    } catch (e) {}
    reveal(el, fast);
    placeRing();
    trackRing(fast ? 120 : 450);
  }

  function center(r) { return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; }

  function move(dir, fast) {
    var list = visibleList();
    if (!list.length) return false;

    if (!current || !current.isConnected) {
      var vh = window.innerHeight, vw = window.innerWidth;
      var onscreen = list.filter(function (o) {
        return o.r.top >= 0 && o.r.top < vh && o.r.left >= 0 && o.r.left < vw && reachable(o.el, o.r);
      }).sort(function (a, b) { return (a.r.top - b.r.top) || (a.r.left - b.r.left); });
      setFocus((onscreen[0] || list[0]).el, fast);
      return true;
    }

    var cr = current.getBoundingClientRect(), cc = center(cr), scored = [];
    for (var i = 0; i < list.length; i++) {
      var o = list[i], el = o.el, r = o.r;
      if (el === current || current.contains(el) || el.contains(current)) continue;
      var c = center(r), dx = c.x - cc.x, dy = c.y - cc.y, main, cross, ok, overlap;
      if (dir === 'ArrowRight')      { ok = r.left >= cr.right - 4 || dx > cr.width / 2;  main = dx;  cross = Math.abs(dy); overlap = r.top < cr.bottom && r.bottom > cr.top; }
      else if (dir === 'ArrowLeft')  { ok = r.right <= cr.left + 4 || -dx > cr.width / 2; main = -dx; cross = Math.abs(dy); overlap = r.top < cr.bottom && r.bottom > cr.top; }
      else if (dir === 'ArrowDown')  { ok = r.top >= cr.bottom - 4 || dy > cr.height / 2; main = dy;  cross = Math.abs(dx); overlap = r.left < cr.right && r.right > cr.left; }
      else                           { ok = r.bottom <= cr.top + 4 || -dy > cr.height / 2; main = -dy; cross = Math.abs(dx); overlap = r.left < cr.right && r.right > cr.left; }
      if (!ok || main <= 0) continue;
      scored.push({ o: o, s: main + cross * (overlap ? 1.2 : 4) });
    }
    scored.sort(function (a, b) { return a.s - b.s; });
    for (var k = 0; k < scored.length && k < 6; k++) {
      var cnd = scored[k].o;
      if (reachable(cnd.el, cnd.r)) { setFocus(cnd.el, fast); return true; }
    }
    // nothing further that way: scroll the page so lazy rows load
    if (dir === 'ArrowDown') { window.scrollBy({ top: Math.round(window.innerHeight * 0.6), behavior: fast ? 'auto' : 'smooth' }); queueRing(); return true; }
    if (dir === 'ArrowUp')   { window.scrollBy({ top: -Math.round(window.innerHeight * 0.6), behavior: fast ? 'auto' : 'smooth' }); queueRing(); return true; }
    return true; // swallow Left/Right at the edge so focus can't escape the page
  }

  // ---------------------------------------------------------------
  // 6. Keys
  // ---------------------------------------------------------------
  var typing = false;       // true once Enter has opened the keyboard on an input
  var lastMove = 0;

  function isTextInput(el) {
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
  window.__tvSeek = function (s) { var v = bigVideo(); if (v) v.currentTime = Math.max(0, v.currentTime + s); };
  window.__tvToggle = function () { var v = bigVideo(); if (v) { v.paused ? v.play() : v.pause(); } };

  // ---------------------------------------------------------------
  // 6a. Core key handler (shared by both native bridge and keydown)
  // ---------------------------------------------------------------
  function handleKey(k, isRepeat) {
    if (k === 'ArrowUp' || k === 'ArrowDown' || k === 'ArrowLeft' || k === 'ArrowRight') {
      // caret movement while actually typing
      if (typing && (k === 'ArrowLeft' || k === 'ArrowRight')) return false;
      // fullscreen in-page video: left/right seek
      if (document.fullscreenElement && (k === 'ArrowLeft' || k === 'ArrowRight')) {
        window.__tvSeek(k === 'ArrowRight' ? 10 : -10); return true;
      }
      var now = Date.now();
      if (isRepeat && now - lastMove < 90) return true;  // throttle held keys
      lastMove = now;
      return move(k, isRepeat);
    } else if (k === 'Enter') {
      var el = current && current.isConnected ? current : null;
      if (!el) return false;
      if (isTextInput(el)) {
        if (typing) { typing = false; return false; }     // 2nd Enter = submit (page handles it)
        typing = true;
        try { el.focus(); } catch (x) {}
        try { window.TvBridge && TvBridge.showKeyboard(); } catch (x) {}
        return true;
      }
      if (el.tagName === 'SELECT') return false;
      if (!isRepeat) el.click();
      return true;
    }
    return false;
  }

  // ---------------------------------------------------------------
  // 6b. Native bridge: called by MainActivity.dispatchKeyEvent()
  //     This is the PRIMARY input path on Android TV boxes (like
  //     Tata Play Binge) where the system eats D-pad keydown events
  //     before they reach the browser's JavaScript engine.
  // ---------------------------------------------------------------
  window.__tvKey = function (key, isRepeat) {
    handleKey(key, isRepeat);
  };

  // ---------------------------------------------------------------
  // 6c. Fallback keydown listener (still fires on some devices /
  //     when native bridge is not in use, e.g. Chrome on desktop)
  // ---------------------------------------------------------------
  window.addEventListener('keydown', function (e) {
    var k = e.key;
    if (k === 'ArrowUp' || k === 'ArrowDown' || k === 'ArrowLeft' || k === 'ArrowRight' || k === 'Enter') {
      if (handleKey(k, e.repeat)) { e.preventDefault(); e.stopPropagation(); }
    }
  }, true);

  setInterval(function () { if (current) placeRing(); }, 400);
})();
