/*
 * Jarvis: a liquid chrome blob. One small WebGL quad; the shape is a disc whose edge is moved by a few
 * travelling waves, and its surface is shaded from a photo of chrome (a "matcap"), so it reads as metal
 * without lighting maths. The same shader runs natively on the TV (BlobView).
 *
 *   JarvisBlob.mount(canvas, matcapUrl)
 *   JarvisBlob.mode('idle' | 'listening' | 'thinking' | 'speaking')
 *   JarvisBlob.level(0..1)          voice loudness while speaking
 */
(function () {
  'use strict';
  var FRAG = [
    'precision mediump float;',
    'uniform sampler2D cap;',
    'uniform float t, speak, listen, think, lvl;',
    'varying vec2 uv;',
    'vec2 rot(vec2 p, float a) { float c = cos(a), s = sin(a); return vec2(c * p.x - s * p.y, s * p.x + c * p.y); }',
    'void main() {',
    '  vec2 p = uv;',
    '  float a = atan(p.y, p.x), d = length(p);',
    // The edge: slow idle waves; listening breathes; speaking adds fast ripples scaled by the voice.
    '  float r = 0.80',
    '    + 0.030 * sin(3.0 * a + t * 0.9) + 0.022 * sin(5.0 * a - t * 1.3) + 0.018 * sin(2.0 * a + t * 0.55)',
    '    + listen * 0.035 * sin(t * 3.2)',
    '    + think * 0.03 * sin(4.0 * a - t * 4.0)',
    '    + speak * lvl * (0.07 * sin(6.0 * a - t * 9.0) + 0.045 * sin(9.0 * a + t * 12.0) + 0.05);',
    '  float q = d / r;',
    '  if (q > 1.02) { gl_FragColor = vec4(0.0); return; }',
    '  float z = sqrt(max(0.0, 1.0 - min(q, 1.0) * min(q, 1.0)));',
    '  vec3 n = normalize(vec3(p / r, z * 0.9 + 0.1));',
    // Liquid flow under the surface: the normal is pushed around by moving waves, harder near the rim.
    '  float flow = 0.22 + 0.25 * think + 0.35 * speak * lvl;',
    '  vec2 w = vec2(sin(3.1 * p.y + t * 0.7 + sin(2.3 * p.x - t * 0.4)), cos(2.7 * p.x - t * 0.6 + sin(1.9 * p.y + t * 0.5)));',
    '  n.xy += w * flow * (0.35 + 0.65 * (1.0 - z));',
    '  n = normalize(n);',
    '  vec2 m = rot(n.xy, t * (0.08 + 0.5 * think)) * 0.43 + 0.5;',
    '  vec3 c = texture2D(cap, m).rgb;',
    '  c = mix(c, vec3(dot(c, vec3(0.333))), 0.6);',              // keep it silver
    '  c *= 0.82 + 0.28 * z;',                                    // a darker rim gives it volume
    '  c += pow(max(0.0, dot(n, normalize(vec3(-0.45, 0.55, 0.7)))), 24.0) * 0.55;',   // a hot highlight
    '  c += speak * lvl * 0.12;',
    '  float alpha = smoothstep(1.0, 0.965, q);',
    '  gl_FragColor = vec4(c * alpha, alpha);',
    '}'
  ].join('\n');
  var VERT = 'attribute vec2 pos; varying vec2 uv; void main() { uv = pos; gl_Position = vec4(pos, 0.0, 1.0); }';

  var gl, prog, u = {}, start = performance.now(), want = { speak: 0, listen: 0, think: 0 }, cur = { speak: 0, listen: 0, think: 0, lvl: 0 },
    target = 0, fallback = null;

  function mount(canvas, capUrl) {
    try {
      gl = canvas.getContext('webgl', { premultipliedAlpha: true, alpha: true, antialias: false }) ||
           canvas.getContext('experimental-webgl', { premultipliedAlpha: true, alpha: true });
    } catch (e) { gl = null; }
    if (!gl) { useFallback(canvas, capUrl); return; }
    function sh(type, src) { var s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s); return s; }
    prog = gl.createProgram();
    gl.attachShader(prog, sh(gl.VERTEX_SHADER, VERT));
    gl.attachShader(prog, sh(gl.FRAGMENT_SHADER, FRAG));
    gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) { useFallback(canvas, capUrl); return; }
    gl.useProgram(prog);
    var b = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, b);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
    var loc = gl.getAttribLocation(prog, 'pos');
    gl.enableVertexAttribArray(loc);
    gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
    ['t', 'speak', 'listen', 'think', 'lvl', 'cap'].forEach(function (k) { u[k] = gl.getUniformLocation(prog, k); });
    var tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array([200, 200, 200, 255]));
    var img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = function () {
      gl.bindTexture(gl.TEXTURE_2D, tex);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, img);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    };
    img.src = capUrl;
    gl.uniform1i(u.cap, 0);
    gl.clearColor(0, 0, 0, 0);
    requestAnimationFrame(frame);
  }

  function frame() {
    // Ease every state in and out, so the blob never jumps.
    ['speak', 'listen', 'think'].forEach(function (k) { cur[k] += (want[k] - cur[k]) * 0.06; });
    cur.lvl += (target - cur.lvl) * (target > cur.lvl ? 0.45 : 0.15);
    gl.viewport(0, 0, gl.drawingBufferWidth, gl.drawingBufferHeight);
    gl.clear(gl.COLOR_BUFFER_BIT);
    gl.uniform1f(u.t, (performance.now() - start) / 1000);
    gl.uniform1f(u.speak, cur.speak);
    gl.uniform1f(u.listen, cur.listen);
    gl.uniform1f(u.think, cur.think);
    gl.uniform1f(u.lvl, cur.lvl);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    requestAnimationFrame(frame);
  }

  // No WebGL: the chrome photo itself, wobbling with CSS.
  function useFallback(canvas, capUrl) {
    fallback = document.createElement('img');
    fallback.src = capUrl;
    fallback.className = 'blobImg';
    canvas.parentNode.replaceChild(fallback, canvas);
  }

  window.JarvisBlob = {
    mount: mount,
    mode: function (m) {
      want.speak = m === 'speaking' ? 1 : 0;
      want.listen = m === 'listening' ? 1 : 0;
      want.think = m === 'thinking' ? 1 : 0;
      if (fallback) fallback.setAttribute('data-mode', m);
      if (m !== 'speaking') target = 0;
    },
    level: function (v) { target = Math.max(0, Math.min(1, v || 0)); },
    FRAG: FRAG, VERT: VERT
  };
})();
