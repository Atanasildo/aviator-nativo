/**
 * NEXUS — Collector de Velas do Aviator
 * Usa Puppeteer (Chrome headless) para passar o Cloudflare
 * e ligar ao WebSocket do Aviator via intercepção do browser
 */

const puppeteerExtra = require('puppeteer-extra');
const StealthPlugin  = require('puppeteer-extra-plugin-stealth');
const chromium       = require('@sparticuz/chromium');

puppeteerExtra.use(StealthPlugin());
const puppeteer = puppeteerExtra;
const http      = require('http');
const https     = require('https');
const url       = require('url');

// ── CONFIGURAÇÃO ──────────────────────────────────────────────────────
const CONFIG = {
  EB_PHONE    : process.env.EB_PHONE    || '',
  EB_PASSWORD : process.env.EB_PASSWORD || '',
  SUPA_URL    : process.env.SUPA_URL    || 'https://oulidkbxjfrddluoqsif.supabase.co',
  SUPA_KEY    : process.env.SUPA_KEY    || 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im91bGlka2J4amZyZGRsdW9xc2lmIiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc3ODk2NTk5MSwiZXhwIjoyMDk0NTQxOTkxfQ.qZssbb1Seov8ki5OtTgn0IDfHQ06q3tnLzizwmWoryg',
  EB_BASE     : 'https://www.elephantbet.co.ao',
  EB_GAME_URL : 'https://www.elephantbet.co.ao/pt/casino/game-view/806666/aviator',
  HEARTBEAT_PORT : process.env.PORT || 3000,
  MAX_VELAS_SUPABASE : 300,
  VELAS_A_APAGAR     : 100,
};

// ── ESTADO ────────────────────────────────────────────────────────────
let totalVelas     = 0;
let limpezaEmCurso = false;
let ultimoCrash    = 0;
let ultimoCrashMs  = 0;
let xAtual         = 0;
let emVoo          = false;
let browser        = null;
let aviatorPage    = null;
let a_correr       = true;

function log(msg) {
  const t = new Date().toLocaleString('pt-PT', { timeZone: 'Africa/Luanda' });
  console.log(`[${t}] ${msg}`);
}

// ── HTTP helper para Supabase ─────────────────────────────────────────
function httpReq(options, body = null) {
  return new Promise((resolve, reject) => {
    const lib = options.protocol === 'http:' ? http : https;
    const req = lib.request(options, res => {
      let data = '';
      res.on('data', c => data += c);
      res.on('end', () => resolve({ status: res.statusCode, body: data }));
    });
    req.on('error', reject);
    req.setTimeout(15000, () => { req.destroy(); reject(new Error('timeout')); });
    if (body) req.write(body);
    req.end();
  });
}

// ── GUARDAR CRASH NO SUPABASE ─────────────────────────────────────────
async function guardarCrash(coef) {
  const body = JSON.stringify({
    coeficiente : coef,
    timestamp   : new Date().toISOString().slice(0, 19),
  });
  try {
    const parsed = new url.URL(CONFIG.SUPA_URL);
    const res = await httpReq({
      hostname : parsed.hostname,
      path     : '/rest/v1/velas',
      method   : 'POST',
      headers  : {
        'apikey'        : CONFIG.SUPA_KEY,
        'Authorization' : `Bearer ${CONFIG.SUPA_KEY}`,
        'Content-Type'  : 'application/json',
        'Prefer'        : 'return=minimal',
      }
    }, body);

    if (res.status >= 200 && res.status < 300) {
      totalVelas++;
      if (totalVelas >= CONFIG.MAX_VELAS_SUPABASE && !limpezaEmCurso) {
        limpezaEmCurso = true;
        limparAntigas();
      }
    } else {
      log(`  ⚠ Supabase ${res.status}: ${res.body.slice(0, 80)}`);
    }
  } catch(e) {
    log(`  ✗ Supabase: ${e.message}`);
  }
}

async function limparAntigas() {
  try {
    const parsed = new url.URL(CONFIG.SUPA_URL);
    const r = await httpReq({
      hostname : parsed.hostname,
      path     : `/rest/v1/velas?select=id&order=id.asc&limit=${CONFIG.VELAS_A_APAGAR}`,
      method   : 'GET',
      headers  : { 'apikey': CONFIG.SUPA_KEY, 'Authorization': `Bearer ${CONFIG.SUPA_KEY}` }
    });
    const ids = JSON.parse(r.body).map(d => d.id).join(',');
    if (!ids) return;
    await httpReq({
      hostname : parsed.hostname,
      path     : `/rest/v1/velas?id=in.(${ids})`,
      method   : 'DELETE',
      headers  : { 'apikey': CONFIG.SUPA_KEY, 'Authorization': `Bearer ${CONFIG.SUPA_KEY}`, 'Prefer': 'return=minimal' }
    });
    totalVelas -= CONFIG.VELAS_A_APAGAR;
    if (totalVelas < 0) totalVelas = 0;
    log(`🧹 ${CONFIG.VELAS_A_APAGAR} velas antigas apagadas`);
  } catch(e) {
    log(`  ✗ Limpeza: ${e.message}`);
  } finally {
    limpezaEmCurso = false;
  }
}

// ── PROCESSAR CRASH DETECTADO ─────────────────────────────────────────
function registarCrash(valor) {
  const agora = Date.now();
  if (valor === ultimoCrash && agora - ultimoCrashMs < 3000) return;
  ultimoCrash   = valor;
  ultimoCrashMs = agora;
  xAtual = 0;
  emVoo  = false;

  const emoji = valor >= 50 ? '🟣' : valor >= 10 ? '🩷' : valor >= 2 ? '⚪' : '🔵';
  log(`${emoji} Crash: ${valor.toFixed(2)}x  (total: ${totalVelas + 1})`);
  guardarCrash(valor);
}

// ── JS INJECTADO NO AVIATOR (baseado no código real do app Android) ─────────
const JS_INTERCEPTOR = `
(function() {
  if (window._nexusDone) return;
  window._nexusDone = true;

  // Callbacks expostos pelo Puppeteer via exposeFunction
  function enviarVela(num) {
    if (num < 1.0 || num > 200000.0) return;
    try { window.__nexusTick && window.__nexusTick(num); } catch(e) {}
    try { top.__nexusTick && top.__nexusTick(num); } catch(e) {}
  }

  function enviarCrash(num) {
    if (isNaN(num) || num < 1.01 || num > 200000) return;
    try { window.__nexusCrash && window.__nexusCrash(num); } catch(e) {}
    try { top.__nexusCrash && top.__nexusCrash(num); } catch(e) {}
  }

  // ── BLOCO 1: WEBSOCKET ──────────────────────────────────────────────
  (function() {
    if (window._wsNexusOk) return;
    window._wsNexusOk = true;

    function lerBinario(buf) {
      try {
        var bytes = new Uint8Array(buf);
        for (var i = 0; i < bytes.length - 10; i++) {
          if (bytes[i] === 1 && bytes[i+1] === 120 && bytes[i+2] === 7) {
            var view = new DataView(buf, i + 3, 8);
            var num  = view.getFloat64(0, false);
            if (num >= 1.0 && num <= 200000.0) enviarVela(num);
          }
        }
      } catch(e) {}
    }

    var WSOrig = window.WebSocket;
    window.WebSocket = function(url, p) {
      console.log('[NEXUS] WS aberto: ' + url);
      var ws = p ? new WSOrig(url, p) : new WSOrig(url);
      try { ws.binaryType = 'arraybuffer'; } catch(e) {}
      ws.addEventListener('message', function(e) {
        try {
          if (e.data instanceof ArrayBuffer) { lerBinario(e.data); return; }
          if (e.data instanceof Blob) { e.data.arrayBuffer().then(lerBinario); return; }
          var d = typeof e.data === 'string' ? e.data : '';
          if (!d || d.length < 3) return;

          // Desempacotar socket.io: a["...JSON..."]
          var corpo = d;
          if (d.charAt(0) === 'a') {
            try { corpo = JSON.parse(d.substring(1))[0]; } catch(ex) {
              corpo = d.substring(3, d.length - 2).replace(/\\"/g, '"');
            }
          } else if (d.startsWith('42[')) {
            try { corpo = JSON.stringify(JSON.parse(d.substring(2))[1]); } catch(ex) {}
          }

          // Padrões de crash
          var crashPats = [
            /"crash_x"\s*:\s*([\d.]+)/,
            /"crash_point"\s*:\s*([\d.]+)/,
            /"cashout_coef"\s*:\s*([\d.]+)/,
            /"finish_coef"\s*:\s*([\d.]+)/,
            /"end_coef"\s*:\s*([\d.]+)/,
          ];
          for (var ci = 0; ci < crashPats.length; ci++) {
            var cm = corpo.match(crashPats[ci]);
            if (cm) { enviarCrash(parseFloat(cm[1])); return; }
          }
          if (corpo.match(/"game_state"\s*:\s*"(?:crashed|finished|end)"/)) {
            enviarCrash(-1); return;
          }

          // Padrões de tick (voo)
          var tickPats = [
            /"coefficient"\s*:\s*([\d.]+)/,
            /"coef"\s*:\s*([\d.]+)/,
            /"multiplier"\s*:\s*([\d.]+)/,
            /"x"\s*:\s*([\d.]+)/,
          ];
          for (var ti = 0; ti < tickPats.length; ti++) {
            var tm = corpo.match(tickPats[ti]);
            if (tm) { enviarVela(parseFloat(tm[1])); return; }
          }
        } catch(ex) {}
      });
      return ws;
    };
    window.WebSocket.prototype = WSOrig.prototype;
    window.WebSocket.CONNECTING = 0; window.WebSocket.OPEN = 1;
    window.WebSocket.CLOSING = 2;   window.WebSocket.CLOSED = 3;
    console.log('[NEXUS] Interceptor WS activo');
  })();

  // ── BLOCO 2: DOM SCAN (detecta crashes pelos elementos visuais) ─────
  var crashesEnviados = {};
  var ultimoPayoutTopo = '';

  function lerPayout(doc) {
    try {
      var payouts = doc.querySelectorAll('.payout, .payout-item, .payouts-item, [class*="payout"]');
      if (payouts.length > 0) {
        payouts.forEach(function(el) {
          var txt = el.textContent.trim().replace(/x$/i,'').replace(',','.');
          var n = parseFloat(txt);
          if (!isNaN(n) && n >= 1.01 && n <= 200000 && !crashesEnviados[n.toFixed(2)]) {
            crashesEnviados[n.toFixed(2)] = true;
            enviarCrash(n);
          }
        });
        return true;
      }
    } catch(e) {}
    return false;
  }

  function tentarCapturar() {
    lerPayout(document);
    try {
      document.querySelectorAll('iframe').forEach(function(f) {
        try {
          var d = f.contentDocument || f.contentWindow.document;
          if (d) lerPayout(d);
        } catch(e) {}
      });
    } catch(e) {}
  }

  try {
    new MutationObserver(function() { tentarCapturar(); })
      .observe(document.documentElement, {childList: true, subtree: true});
  } catch(e) {}

  setInterval(tentarCapturar, 2000);
})();
`;


