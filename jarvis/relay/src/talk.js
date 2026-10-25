// "Talk to Jarvis" for a phone: served by the relay over HTTPS, which browsers require before a page may use
// the microphone (the TV's own phone page is plain http on the home network, so it can't).
//
// Tap the orb and speak: the phone turns speech into text (or, where the browser can't, records a few
// seconds for the TV to transcribe) and Jarvis answers on the TV, or here when the Ambient screen is off.
// Follow-ups listen again after each answer, so a conversation needs one tap; "Listen for Jarvis" keeps
// listening while this page is open and acts on anything said after the word "Jarvis".

export const TALK_ICON = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512"><defs><radialGradient id="g" cx=".38" cy=".32" r=".75">' +
  '<stop offset="0" stop-color="#ffffff"/><stop offset=".45" stop-color="#c9ccd4"/><stop offset=".8" stop-color="#6b6f78"/><stop offset="1" stop-color="#2b2d33"/></radialGradient></defs>' +
  '<rect width="512" height="512" fill="#0b0b0c"/><circle cx="256" cy="256" r="170" fill="url(#g)"/></svg>';

export const TALK_MANIFEST = {
  name: 'Jarvis', short_name: 'Jarvis', start_url: '/talk', scope: '/talk', display: 'standalone',
  background_color: '#0b0b0c', theme_color: '#0b0b0c',
  icons: [{ src: '/talk/icon.svg', sizes: 'any', type: 'image/svg+xml', purpose: 'any' }],
};

export const TALK_PAGE = String.raw`<!doctype html>
<html lang="en-GB">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#0b0b0c">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<link rel="manifest" href="/talk/manifest.webmanifest">
<link rel="icon" href="/talk/icon.svg">
<link rel="apple-touch-icon" href="/talk/icon.svg">
<title>Jarvis</title>
<style>
  :root { --fg: #f4f4f6; --dim: rgba(244,244,246,.6); --faint: rgba(244,244,246,.34); --hair: rgba(255,255,255,.1); --accent: #9fd6ff; }
  * { box-sizing: border-box; margin: 0; padding: 0; -webkit-tap-highlight-color: transparent; }
  html, body { height: 100%; background: #0b0b0c; color: var(--fg); font: 400 16px/1.4 -apple-system, system-ui, 'Segoe UI', Roboto, sans-serif; }
  body { display: flex; flex-direction: column; align-items: stretch; padding: calc(env(safe-area-inset-top) + 18px) 16px calc(env(safe-area-inset-bottom) + 18px);
    background: radial-gradient(120% 70% at 50% 0%, #1d1e22, #0b0b0c 70%); overflow-x: hidden; }
  header { width: 100%; max-width: 520px; margin: 0 auto; display: flex; justify-content: space-between; align-items: center; }
  header b { font-weight: 600; letter-spacing: .02em; font-size: 18px; }
  #status { font-size: 13px; color: var(--dim); }
  #orbWrap { position: relative; width: 210px; height: 210px; margin: 34px 0 18px; flex: none; }
  #halo { position: absolute; inset: -30px; border-radius: 50%; background: radial-gradient(circle, rgba(200,215,255,.22), transparent 65%); opacity: 0; transition: opacity .4s; }
  #orb { position: absolute; inset: 0; border: 0; cursor: pointer; transition: transform .08s linear; border-radius: 50% 48% 52% 49% / 49% 52% 48% 51%;
    background: radial-gradient(circle at 36% 30%, #fff 0, #dfe2e8 18%, #9a9ea8 46%, #4a4d55 72%, #1d1e22 100%);
    box-shadow: inset -14px -18px 30px rgba(0,0,0,.45), inset 10px 12px 24px rgba(255,255,255,.35), 0 20px 50px rgba(0,0,0,.5);
    animation: morph 7s ease-in-out infinite; }
  #orb::after { content: ''; position: absolute; left: 22%; top: 16%; width: 30%; height: 18%; border-radius: 50%; background: rgba(255,255,255,.55); filter: blur(6px); }
  @keyframes morph { 0%,100% { border-radius: 50% 48% 52% 49% / 49% 52% 48% 51%; transform: rotate(0); }
    33% { border-radius: 46% 54% 49% 51% / 54% 46% 53% 47%; transform: rotate(4deg); } 66% { border-radius: 53% 47% 55% 45% / 47% 55% 45% 53%; transform: rotate(-3deg); } }
  body.listening #orb { animation: morph 1.6s ease-in-out infinite; } body.listening #halo { opacity: 1; animation: pulse 1.4s ease-in-out infinite; }
  body.thinking #orb { animation: morph .9s linear infinite; filter: brightness(.9); }
  body.speaking #halo { opacity: .8; }
  @keyframes pulse { 50% { transform: scale(1.08); } }
  #rings { position: absolute; inset: 0; pointer-events: none; }
  #rings i { position: absolute; inset: 0; border-radius: 50%; border: 2px solid rgba(255,255,255,.45); opacity: 0; }
  body.listening #rings i { animation: ripple 1.8s ease-out infinite; } #rings i:nth-child(2) { animation-delay: .6s !important; } #rings i:nth-child(3) { animation-delay: 1.2s !important; }
  @keyframes ripple { from { transform: scale(1); opacity: .6; } to { transform: scale(1.5); opacity: 0; } }
  #hint { color: var(--dim); font-size: 15px; text-align: center; min-height: 22px; }
  #live { font-size: 22px; font-weight: 500; text-align: center; min-height: 31px; margin-top: 8px; max-width: 520px; }
  #log { width: 100%; max-width: 520px; margin-top: 22px; display: flex; flex-direction: column; gap: 10px; flex: 1; }
  .ex { border: 1px solid var(--hair); border-radius: 18px; padding: 12px 14px; background: linear-gradient(160deg, rgba(255,255,255,.08), rgba(255,255,255,.03)); }
  .ex .q { color: var(--dim); font-style: italic; font-size: 14px; }
  .ex .a { margin-top: 4px; font-size: 16px; }
  .ex.err .a { color: #ff9a9a; }
  .opts { width: 100%; max-width: 520px; margin-top: 20px; border: 1px solid var(--hair); border-radius: 18px; overflow: hidden; background: rgba(255,255,255,.04); }
  .opt { display: flex; align-items: center; gap: 12px; padding: 13px 14px; }
  .opt + .opt { border-top: 1px solid var(--hair); }
  .opt > span:first-child { flex: 1; min-width: 0; } .opt small { display: block; color: var(--faint); font-size: 12.5px; }
  .sw { position: relative; width: 50px; height: 30px; flex: none; }
  .sw input { opacity: 0; width: 0; height: 0; }
  .sw i { position: absolute; inset: 0; border-radius: 30px; background: rgba(255,255,255,.18); transition: background .2s; }
  .sw i::after { content: ''; position: absolute; left: 3px; top: 3px; width: 24px; height: 24px; border-radius: 50%; background: #fff; transition: transform .2s; }
  .sw input:checked + i { background: #34c759; } .sw input:checked + i::after { transform: translateX(20px); }
  form { width: 100%; max-width: 520px; display: flex; gap: 8px; margin-top: 14px; }
  form input { flex: 1; min-width: 0; height: 44px; border-radius: 22px; border: 1px solid var(--hair); background: rgba(255,255,255,.06); color: var(--fg); padding: 0 16px; font-size: 16px; outline: none; }
  form button { height: 44px; padding: 0 18px; border-radius: 22px; border: 0; background: #fff; color: #000; font-weight: 600; font-size: 15px; }
  .siri { width: 100%; margin-top: 14px; border: 1px solid var(--hair); border-radius: 18px; padding: 12px 14px; background: rgba(255,255,255,.04); font-size: 14px; color: var(--dim); }
  .siri summary { color: var(--fg); font-size: 15px; cursor: pointer; }
  .siri ol { margin: 10px 0 6px 18px; display: grid; gap: 8px; } .siri b { color: var(--fg); font-weight: 600; }
  .siri code { font-size: 12px; word-break: break-all; color: var(--accent); }
  .siri button { border: 0; border-radius: 10px; padding: 3px 9px; background: #fff; color: #000; font-weight: 600; font-size: 13px; }
  #unpaired { display: none; text-align: center; color: var(--dim); max-width: 380px; margin-top: 30px; }
  body.nokey #unpaired { display: block; } body.nokey #main { display: none; }
  #main { width: 100%; max-width: 520px; margin: 0 auto; display: flex; flex-direction: column; align-items: center; }
</style>
</head>
<body>
<header><b>Jarvis</b><span id="status"></span></header>
<p id="unpaired">This phone isn’t paired yet. On the TV’s phone page (the QR code on the Ambient screen), open Jarvis and tap <b>Talk to Jarvis</b>.</p>
<div id="main">
  <div id="orbWrap"><div id="halo"></div><div id="rings"><i></i><i></i><i></i></div><button id="orb" aria-label="Talk to Jarvis"></button></div>
  <div id="hint">Tap and speak</div>
  <div id="live"></div>
  <div class="opts">
    <label class="opt"><span>Follow-ups<small>Listens again after each answer, so you can keep talking</small></span><span class="sw"><input type="checkbox" id="optFollow" checked><i></i></span></label>
    <label class="opt"><span>Listen for “Jarvis”<small>While this page is open: say “Jarvis, …” any time, no tapping</small></span><span class="sw"><input type="checkbox" id="optWake"><i></i></span></label>
  </div>
  <details class="siri"><summary>Set up “Hey Siri, Jarvis” (hands-free on iPhone)</summary>
    <ol>
      <li>Open the <b>Shortcuts</b> app, tap <b>+</b>, and name the shortcut <b>Jarvis</b>.</li>
      <li>Add <b>Dictate Text</b> (Language: English (India), or English (UK)).</li>
      <li>Add <b>Get Contents of URL</b>: URL <code id="askUrl"></code>, Method <b>POST</b>,
        Headers: <b>Authorization</b> = <b>Bearer </b> followed by your key (<button type="button" id="copyKey">Copy key</button>), Request Body <b>JSON</b> with <b>text</b> = <i>Dictated Text</i>.</li>
      <li>Add <b>Get Dictionary Value</b>: Key <b>speech</b>.</li>
      <li>Add <b>Speak Text</b> (the dictionary value), or Show Result.</li>
    </ol>
    <p>Then say “Hey Siri, Jarvis”, and speak when Siri asks. Jarvis answers on the TV (or Siri reads it out).</p>
  </details>
  <form id="typeForm"><input id="typeBox" placeholder="Or type to Jarvis…" autocomplete="off" enterkeyhint="send"><button>Send</button></form>
  <div id="log"></div>
</div>
<script>
(function () {
  'use strict';
  var $ = function (id) { return document.getElementById(id); };
  $('askUrl').textContent = location.origin + '/talk/ask';

  // ---- The phone's key: arrives once in the link's #k=…, then kept here.
  var m = location.hash.match(/k=([A-Za-z0-9]+)/);
  if (m) { try { localStorage.setItem('jarvisKey', m[1]); } catch (e) {} history.replaceState(null, '', location.pathname); }
  var KEY = ''; try { KEY = localStorage.getItem('jarvisKey') || ''; } catch (e) {}
  if (!KEY) document.body.classList.add('nokey');

  // What happened, for the relay's live log (no audio, nothing stored): helps when "it doesn't hear me".
  function report(event, detail) {
    try {
      fetch('/talk/log', { method: 'POST', headers: { 'Authorization': 'Bearer ' + KEY, 'Content-Type': 'application/json' },
        body: JSON.stringify({ event: event, detail: detail || '', ua: navigator.userAgent, standalone: !!(navigator.standalone || matchMedia('(display-mode: standalone)').matches), sr: !!SR, recorder: useRecorder }) });
    } catch (e) {}
  }

  var SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  var standalone = !!(navigator.standalone || (window.matchMedia && matchMedia('(display-mode: standalone)').matches));
  // iPhone home-screen apps have the speech API but it fails there; record instead from the start.
  var useRecorder = !SR || (/iPhone|iPad|iPod/.test(navigator.userAgent) && standalone);
  var mode = 'idle', busy = false, followTimer = null, wakeRec = null, wakeLock = null, current = null;
  function setMode(m, hint) {
    mode = m; document.body.className = (KEY ? '' : 'nokey ') + m;
    if (hint != null) $('hint').textContent = hint;
  }
  function idleHint() { return $('optWake').checked ? 'Say “Jarvis, …”' : 'Tap and speak'; }
  function esc(t) { var d = document.createElement('div'); d.textContent = t; return d.innerHTML; }
  function logEx(q, a, err) {
    var div = document.createElement('div'); div.className = 'ex' + (err ? ' err' : '');
    div.innerHTML = (q ? '<div class="q">“' + esc(q) + '”</div>' : '') + '<div class="a">' + esc(a) + '</div>';
    $('log').insertBefore(div, $('log').firstChild);
    while ($('log').children.length > 12) $('log').removeChild($('log').lastChild);
  }
  // The orb swells with the voice, so you can see it hears you.
  function level(v) { $('orb').style.transform = 'scale(' + (1 + Math.min(0.18, v * 1.6)).toFixed(3) + ')'; }

  // ---- Sending: text, or a recording the TV transcribes.
  function send(payload) {
    busy = true; setMode('thinking', 'Jarvis is on it…'); level(0);
    if (payload.text) $('live').textContent = payload.text;
    return fetch('/talk/ask', { method: 'POST', headers: { 'Authorization': 'Bearer ' + KEY, 'Content-Type': 'application/json' }, body: JSON.stringify(payload) })
      .then(function (r) { return r.json(); })
      .then(function (j) {
        if (j.error) { logEx(payload.text || '', j.error, true); report('ask-error', j.error); done(0, true); return; }
        // Jarvis's own voice, picked up from the speaker: ignore it and stop listening, so it can't loop.
        if (j.ignored) { report('echo-ignored', j.heard || ''); done(0, true); return; }
        var heard = payload.text || j.heard || '';
        $('live').textContent = heard;
        logEx(heard, j.speech || '…');
        if (j.spoken) {   // the TV is saying it: wait about as long as it takes
          // The TV replies once Jarvis has finished speaking; a Bluetooth speaker plays it a little later still.
          setMode('speaking', 'Jarvis answered on the TV');
          done(1500);
        } else {
          speakHere(j.speech || '', function () { done(200); });
        }
      })
      .catch(function (e) { logEx(payload.text || '', 'Couldn’t reach Jarvis. Check your connection.', true); report('ask-failed', String(e)); done(0, true); });
  }
  function speakHere(text, then) {
    if (!('speechSynthesis' in window) || !text) { then(); return; }
    setMode('speaking', 'Jarvis');
    var u = new SpeechSynthesisUtterance(text); u.lang = 'en-GB';
    var vs = speechSynthesis.getVoices();
    var v = vs.filter(function (x) { return /en-GB/i.test(x.lang) && /male|daniel|arthur|george|oliver/i.test(x.name); })[0] || vs.filter(function (x) { return /en-GB/i.test(x.lang); })[0];
    if (v) u.voice = v;
    u.onend = u.onerror = then;
    speechSynthesis.speak(u);
  }
  // After an answer: listen again for a follow-up, or go back to waiting.
  function done(afterMs, noFollow) {
    setTimeout(function () {
      busy = false;
      if ($('optWake').checked) { setMode('idle', idleHint()); startWake(); return; }
      if ($('optFollow').checked && !noFollow) { listenOnce(true); return; }
      setMode('idle', idleHint());
    }, afterMs);
  }

  // ---- One utterance: the browser's own speech recognition, or a recording when that can't work here.
  function listenOnce(followUp) {
    if (busy) return;
    stopWake();
    if (useRecorder) { recordOnce(followUp); return; }
    var rec = new SR(), finalText = '', interimText = '', failed = '';
    current = { stop: function () { try { rec.stop(); } catch (e) {} } };
    rec.lang = 'en-GB'; rec.interimResults = true; rec.continuous = false; rec.maxAlternatives = 1;
    setMode('listening', followUp ? 'Anything else?' : 'Listening… speak now'); $('live').textContent = '';
    clearTimeout(followTimer);
    if (followUp) followTimer = setTimeout(function () { if (!finalText && !interimText) { try { rec.abort(); } catch (e) {} } }, 7000);
    rec.onaudiostart = function () { level(0.04); };
    rec.onspeechstart = function () { level(0.08); };
    rec.onresult = function (e) {
      interimText = '';
      for (var i = e.resultIndex; i < e.results.length; i++) {
        if (e.results[i].isFinal) finalText += e.results[i][0].transcript; else interimText += e.results[i][0].transcript;
      }
      $('live').textContent = (finalText + interimText).trim();
      level(0.05 + Math.random() * 0.06);
    };
    rec.onerror = function (e) { failed = e.error || 'error'; report('sr-error', failed); };
    rec.onend = function () {
      clearTimeout(followTimer); current = null; level(0);
      var t = (finalText || interimText).trim();
      if (t) { send({ text: t }); return; }
      if (failed && failed !== 'no-speech' && failed !== 'aborted') {
        // Speech recognition doesn't work in this browser: record instead, from now on.
        useRecorder = true;
        if (failed === 'not-allowed' && !followUp) { setMode('idle', 'Microphone blocked: allow it for this site in the browser’s settings, then tap again.'); return; }
        recordOnce(followUp); return;
      }
      if (!followUp) setMode('idle', failed === 'no-speech' ? 'I didn’t hear anything. Tap and try again.' : idleHint());
      else setMode('idle', idleHint());
      if ($('optWake').checked) startWake();
    };
    try { rec.start(); } catch (e) { report('sr-start-failed', String(e)); useRecorder = true; recordOnce(followUp); }
  }

  // ---- A recording, ended by a pause after speech (or a second tap), for the TV to transcribe.
  var stream = null;
  function recordOnce(followUp) {
    if (!navigator.mediaDevices || !window.MediaRecorder) { setMode('idle', 'This browser can’t use the microphone. Type instead.'); report('no-recorder'); return; }
    (stream ? Promise.resolve(stream) : navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } }))
      .then(function (s) {
        stream = s;
        var mime = ['audio/webm;codecs=opus', 'audio/mp4', 'audio/webm', 'audio/ogg'].filter(function (x) { return MediaRecorder.isTypeSupported && MediaRecorder.isTypeSupported(x); })[0] || '';
        var mr = new MediaRecorder(s, mime ? { mimeType: mime } : undefined), chunks = [];
        var ac = new (window.AudioContext || window.webkitAudioContext)(); if (ac.resume) ac.resume();
        var an = ac.createAnalyser(); an.fftSize = 1024; var data = new Uint8Array(an.fftSize);
        ac.createMediaStreamSource(s).connect(an);
        // The room's own noise, measured over the first moment; speech is anything well above it.
        var t0 = Date.now(), floor = 0, floorN = 0, spoke = false, quietSince = 0, peak = 0, stopped = false;
        setMode('listening', followUp ? 'Anything else?' : 'Listening… speak now, tap the orb when done'); $('live').textContent = '';
        current = { stop: function () { spoke = spoke || peak > 0.004; stop(); } };
        function stop() { if (stopped) return; stopped = true; current = null; level(0); try { mr.stop(); } catch (e) {} try { ac.close(); } catch (e) {} }
        (function watch() {
          if (stopped) return;
          an.getByteTimeDomainData(data);
          var sum = 0; for (var i = 0; i < data.length; i++) { var x = (data[i] - 128) / 128; sum += x * x; }
          var rms = Math.sqrt(sum / data.length), now = Date.now();
          peak = Math.max(peak, rms); level(rms * 4);
          if (now - t0 < 350) { floor += rms; floorN++; requestAnimationFrame(watch); return; }
          // A follow-up must be clearly louder than the room (where the TV may still be talking).
          var base = floorN ? floor / floorN : 0.004, loud = rms > (followUp ? Math.max(0.02, base * 4) : Math.max(0.006, base * 2.5));
          if (loud) { spoke = true; quietSince = 0; } else if (spoke && !quietSince) quietSince = now;
          if ((spoke && quietSince && now - quietSince > 1300) || now - t0 > 15000 || (!spoke && now - t0 > (followUp ? 7000 : 10000))) { stop(); return; }
          requestAnimationFrame(watch);
        })();
        mr.ondataavailable = function (e) { if (e.data && e.data.size) chunks.push(e.data); };
        mr.onstop = function () {
          report('recorded', 'peak=' + peak.toFixed(4) + ' spoke=' + spoke + ' bytes=' + chunks.reduce(function (a, c) { return a + c.size; }, 0) + ' mime=' + (mr.mimeType || mime));
          if (!spoke) { setMode('idle', followUp ? idleHint() : 'I didn’t hear anything (mic level ' + Math.round(peak * 1000) + '). Tap and try again, a little closer.'); return; }
          var blob = new Blob(chunks, { type: mr.mimeType || mime || 'audio/webm' });
          var fr = new FileReader();
          fr.onload = function () { send({ audio: String(fr.result).split(',')[1], mime: blob.type }); };
          fr.readAsDataURL(blob);
        };
        mr.start(250);
      })
      .catch(function (e) { report('mic-denied', String(e && e.name || e)); setMode('idle', 'Microphone blocked (' + (e && e.name || 'error') + '): allow it for this site in the browser’s settings.'); });
  }

  // ---- "Listen for Jarvis": keeps listening while the page is open; acts on what follows "Jarvis".
  function startWake() {
    if (!$('optWake').checked || busy || wakeRec || !SR || useRecorder) return;
    var rec = new SR(); wakeRec = rec;
    rec.lang = 'en-GB'; rec.continuous = true; rec.interimResults = false;
    rec.onresult = function (e) {
      for (var i = e.resultIndex; i < e.results.length; i++) {
        if (!e.results[i].isFinal) continue;
        var said = e.results[i][0].transcript, k = said.search(/\bjarvis\b/i);
        if (k < 0) continue;
        var rest = said.slice(k + 6).replace(/^[\s,.!?]+/, '').trim();
        stopWake();
        if (rest.length > 2) send({ text: rest }); else listenOnce(false);
        return;
      }
    };
    rec.onend = function () { if (wakeRec === rec) { wakeRec = null; setTimeout(startWake, 300); } };
    rec.onerror = function (e) {
      report('wake-error', e.error);
      if (e.error === 'not-allowed' || e.error === 'service-not-allowed') { $('optWake').checked = false; wakeRec = null; setMode('idle', 'This browser can’t keep listening; tap the orb instead.'); }
    };
    try { rec.start(); setMode('idle', 'Say “Jarvis, …”'); } catch (e) { wakeRec = null; }
  }
  function stopWake() { var r = wakeRec; wakeRec = null; if (r) { try { r.abort(); } catch (e) {} } }
  $('optWake').onchange = function () {
    try { localStorage.setItem('jarvisWake', this.checked ? '1' : ''); } catch (e) {}
    if (this.checked) {
      if (!SR || useRecorder) { this.checked = false; $('hint').textContent = 'This browser can’t keep listening; tap the orb instead.'; return; }
      if (navigator.wakeLock) navigator.wakeLock.request('screen').then(function (l) { wakeLock = l; }).catch(function () {});
      startWake();
    } else { stopWake(); if (wakeLock) { wakeLock.release(); wakeLock = null; } setMode('idle', 'Tap and speak'); }
  };
  $('optFollow').onchange = function () { try { localStorage.setItem('jarvisFollow', this.checked ? '1' : '0'); } catch (e) {} };
  try { if (localStorage.getItem('jarvisFollow') === '0') $('optFollow').checked = false; } catch (e) {}

  // ---- The orb: tap to talk; tap again while listening to finish (and send) now.
  $('orb').onclick = function () {
    if (!KEY) return;
    if (mode === 'listening' && current) { current.stop(); return; }
    if (busy) return;
    if ('speechSynthesis' in window) speechSynthesis.cancel();
    report('tap');
    listenOnce(false);
  };
  $('copyKey').onclick = function () {
    var b = this, done = function () { b.textContent = 'Copied'; setTimeout(function () { b.textContent = 'Copy key'; }, 1800); };
    if (navigator.clipboard) navigator.clipboard.writeText(KEY).then(done, function () { prompt('Your key:', KEY); });
    else prompt('Your key:', KEY);
  };
  $('typeForm').onsubmit = function (e) {
    e.preventDefault(); var t = $('typeBox').value.trim(); if (!t || busy) return;
    $('typeBox').value = ''; $('typeBox').blur(); stopWake(); send({ text: t });
  };
  document.addEventListener('visibilitychange', function () { if (document.hidden) stopWake(); else startWake(); });
  $('status').textContent = KEY ? (useRecorder ? 'Paired · recording mode' : 'Paired') : '';
  // Continuous listening needs the browser's speech recognition, which iPhone home-screen apps don't get.
  if (useRecorder) {
    $('optWake').checked = false; $('optWake').disabled = true;
    $('optWake').closest('.opt').style.opacity = '.45';
    $('optWake').closest('.opt').querySelector('small').textContent = /iPhone|iPad/.test(navigator.userAgent)
      ? 'Not possible in an iPhone home-screen app: Apple keeps continuous listening to Siri. Use “Hey Siri, Jarvis” instead.'
      : 'Not possible in this browser.';
  }
  if (KEY) report('open');
  try { if (localStorage.getItem('jarvisWake') === '1' && SR && !useRecorder) { $('optWake').checked = true; startWake(); } } catch (e) {}
})();
</script>
</body>
</html>`;
