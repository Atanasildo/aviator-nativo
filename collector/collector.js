/**
 * NEXUS — Collector de Velas do Aviator
 * Puppeteer + @sparticuz/chromium + puppeteer-extra-plugin-stealth
 */

const puppeteerExtra = require('puppeteer-extra');
const StealthPlugin  = require('puppeteer-extra-plugin-stealth');
const chromium       = require('@sparticuz/chromium');
const http           = require('http');
const https          = require('https');
const urlMod         = require('url');

puppeteerExtra.use(StealthPlugin());

// ── CONFIG ────────────────────────────────────────────────────────────
const CONFIG = {
  EB_PHONE    : process.env.EB_PHONE    || '',
  EB_PASSWORD : process.env.EB_PASSWORD || '',
  EB_COOKIES  : process.env.EB_COOKIES  || '',
  SUPA_URL    : process.env.SUPA_URL    || 'https://oulidkbxjfrddluoqsif.supabase.co',
  SUPA_KEY    : process.env.SUPA_KEY    || 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im91bGlka2J4amZyZGRsdW9xc2lmIiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc3ODk2NTk5MSwiZXhwIjoyMDk0NTQxOTkxfQ.qZssbb1Seov8ki5OtTgn0IDfHQ06q3tnLzizwmWoryg',
  EB_GAME_URL : 'https://www.elephantbet.co.ao/pt/casino/game-view/806666/aviator',
  PORT        : process.env.PORT || 3000,
  MAX_VELAS   : 300,
  APAGAR      : 100,
};

// ── ESTADO ────────────────────────────────────────────────────────────
let totalVelas     = 0;
let limpezaOk      = false;
let ultimoCrash    = 0;
let ultimoCrashMs  = 0;
let xAtual         = 0;
let emVoo          = false;
let browser        = null;
let aviatorPage    = null;
let reiniciando    = false;

function log(m) {
  const t = new Date().toLocaleString('pt-PT', { timeZone: 'Africa/Luanda' });
  console.log(`[${t}] ${m}`);
}

// ── SUPABASE ──────────────────────────────────────────────────────────
function httpReq(opts, body = null) {
  return new Promise((res, rej) => {
    const lib = opts.protocol === 'http:' ? http : https;
    const req = lib.request(opts, r => {
      let d = '';
      r.on('data', c => d += c);
      r.on('end', () => res({ status: r.statusCode, body: d }));
    });
    req.on('error', rej);
    req.setTimeout(15000, () => { req.destroy(); rej(new Error('timeout')); });
    if (body) req.write(body);
    req.end();
  });
}

async function guardarCrash(coef) {
  const body = JSON.stringify({ coeficiente: coef, timestamp: new Date().toISOString().slice(0,19) });
  try {
    const p = new urlMod.URL(CONFIG.SUPA_URL);
    const r = await httpReq({
      hostname: p.hostname, path: '/rest/v1/velas', method: 'POST',
      headers: { 'apikey': CONFIG.SUPA_KEY, 'Authorization': `Bearer ${CONFIG.SUPA_KEY}`,
                 'Content-Type': 'application/json', 'Prefer': 'return=minimal' }
    }, body);
    if (r.status >= 200 && r.status < 300) {
      totalVelas++;
      if (totalVelas >= CONFIG.MAX_VELAS && !limpezaOk) { limpezaOk = true; limparAntigas(); }
    } else log(`  ⚠ Supabase ${r.status}: ${r.body.slice(0,80)}`);
  } catch(e) { log(`  ✗ Supabase: ${e.message}`); }
}

async function limparAntigas() {
  try {
    const p = new urlMod.URL(CONFIG.SUPA_URL);
    const r = await httpReq({
      hostname: p.hostname, path: `/rest/v1/velas?select=id&order=id.asc&limit=${CONFIG.APAGAR}`,
      method: 'GET', headers: { 'apikey': CONFIG.SUPA_KEY, 'Authorization': `Bearer ${CONFIG.SUPA_KEY}` }
    });
    const ids = JSON.parse(r.body).map(d => d.id).join(',');
    if (!ids) return;
    await httpReq({
      hostname: p.hostname, path: `/rest/v1/velas?id=in.(${ids})`, method: 'DELETE',
      headers: { 'apikey': CONFIG.SUPA_KEY, 'Authorization': `Bearer ${CONFIG.SUPA_KEY}`, 'Prefer': 'return=minimal' }
    });
    totalVelas -= CONFIG.APAGAR;
    if (totalVelas < 0) totalVelas = 0;
    log(`🧹 ${CONFIG.APAGAR} velas antigas apagadas`);
  } catch(e) { log(`  ✗ Limpeza: ${e.message}`); }
  finally { limpezaOk = false; }
}

// ── CRASH HANDLER ─────────────────────────────────────────────────────
function registarCrash(valor) {
  const agora = Date.now();
  if (valor === ultimoCrash && agora - ultimoCrashMs < 3000) return;
  ultimoCrash = valor; ultimoCrashMs = agora;
  xAtual = 0; emVoo = false;
  const e = valor >= 50 ? '🟣' : valor >= 10 ? '🩷' : valor >= 2 ? '⚪' : '🔵';
  log(`${e} Crash: ${valor.toFixed(2)}x  (total: ${totalVelas + 1})`);
  guardarCrash(valor);
}

// ── JS INTERCEPTOR (baseado no código real do app Android) ────────────
const JS_INTERCEPTOR = `
(function() {
  if (window._nexusDone) return;
  window._nexusDone = true;

  function enviarVela(num) {
    if (num < 1.0 || num > 200000.0) return;
    try { window.__nexusTick(num); } catch(e) {}
    try { top.__nexusTick(num); } catch(e) {}
  }

  function enviarCrash(num) {
    if (isNaN(num) || num < 1.01 || num > 200000) return;
    try { window.__nexusCrash(num); } catch(e) {}
    try { top.__nexusCrash(num); } catch(e) {}
  }

  // BLOCO 1: WebSocket intercept
  (function() {
    if (window._wsNexusOk) return;
    window._wsNexusOk = true;

    function lerBinario(buf) {
      try {
        var bytes = new Uint8Array(buf);
        for (var i = 0; i < bytes.length - 10; i++) {
          if (bytes[i] === 1 && bytes[i+1] === 120 && bytes[i+2] === 7) {
            var view = new DataView(buf, i + 3, 8);
            var num = view.getFloat64(0, false);
            if (num >= 1.0 && num <= 200000.0) enviarVela(num);
          }
        }
      } catch(e) {}
    }

    var WSOrig = window.WebSocket;
    window.WebSocket = function(url, p) {
      console.log('[NEXUS] WS: ' + url);
      var ws = p ? new WSOrig(url, p) : new WSOrig(url);
      try { ws.binaryType = 'arraybuffer'; } catch(e) {}
      ws.addEventListener('message', function(e) {
        try {
          if (e.data instanceof ArrayBuffer) { lerBinario(e.data); return; }
          if (e.data instanceof Blob) { e.data.arrayBuffer().then(lerBinario); return; }
          var d = typeof e.data === 'string' ? e.data : '';
          if (!d || d.length < 3) return;
          var corpo = d;
          if (d.charAt(0) === 'a') {
            try { corpo = JSON.parse(d.substring(1))[0]; }
            catch(ex) { corpo = d.substring(3, d.length-2).replace(/\\\\\"/g,'"'); }
          } else if (d.startsWith('42[')) {
            try { corpo = JSON.stringify(JSON.parse(d.substring(2))[1]); } catch(ex) {}
          }
          var cp = [/"crash_x"\s*:\s*([\d.]+)/,/"crash_point"\s*:\s*([\d.]+)/,
                    /"cashout_coef"\s*:\s*([\d.]+)/,/"finish_coef"\s*:\s*([\d.]+)/,
                    /"end_coef"\s*:\s*([\d.]+)/];
          for (var ci=0;ci<cp.length;ci++){var cm=corpo.match(cp[ci]);if(cm){enviarCrash(parseFloat(cm[1]));return;}}
          if (corpo.match(/"game_state"\s*:\s*"(?:crashed|finished|end)"/)){enviarCrash(-1);return;}
          var tp = [/"coefficient"\s*:\s*([\d.]+)/,/"coef"\s*:\s*([\d.]+)/,
                    /"multiplier"\s*:\s*([\d.]+)/,/"x"\s*:\s*([\d.]+)/];
          for (var ti=0;ti<tp.length;ti++){var tm=corpo.match(tp[ti]);if(tm){enviarVela(parseFloat(tm[1]));return;}}
        } catch(ex) {}
      });
      return ws;
    };
    window.WebSocket.prototype = WSOrig.prototype;
    window.WebSocket.CONNECTING=0;window.WebSocket.OPEN=1;
    window.WebSocket.CLOSING=2;window.WebSocket.CLOSED=3;
    console.log('[NEXUS] Interceptor WS activo');
  })();

  // BLOCO 2: DOM scan
  var _crashesDOM = {};
  function lerPayout(doc) {
    try {
      var els = doc.querySelectorAll('.payout,.payout-item,.payouts-item,[class*="payout"]');
      els.forEach(function(el) {
        var n = parseFloat(el.textContent.trim().replace(/x$/i,'').replace(',','.'));
        if (!isNaN(n) && n>=1.01 && n<=200000 && !_crashesDOM[n.toFixed(2)]) {
          _crashesDOM[n.toFixed(2)]=true; enviarCrash(n);
        }
      });
    } catch(e) {}
  }
  function scan() {
    lerPayout(document);
    try { document.querySelectorAll('iframe').forEach(function(f){
      try{var d=f.contentDocument||f.contentWindow.document;if(d)lerPayout(d);}catch(e){}
    });} catch(e){}
  }
  try { new MutationObserver(scan).observe(document.documentElement,{childList:true,subtree:true}); } catch(e){}
  setInterval(scan, 2000);
})();
`;

// ── BROWSER ───────────────────────────────────────────────────────────
async function iniciarBrowser() {
  log('🌐 A iniciar Chrome headless...');
  const execPath = process.env.PUPPETEER_EXECUTABLE_PATH
                || await chromium.executablePath()
                || '/usr/bin/chromium';

  browser = await puppeteerExtra.launch({
    headless        : chromium.headless,
    args            : [...chromium.args, '--no-sandbox', '--disable-setuid-sandbox',
                       '--disable-dev-shm-usage', '--disable-gpu', '--no-first-run',
                       '--no-zygote', '--single-process'],
    defaultViewport : chromium.defaultViewport,
    executablePath  : execPath,
  });


  // Injectar cookies de sessão
  const page = await browser.newPage();
  await page.setUserAgent('Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36');
  log('🔐 A injectar cookies de sessão...');

  // Navegar para o domínio primeiro — necessário para o browser aceitar os cookies
  await page.goto('https://www.elephantbet.co.ao', {
    waitUntil: 'domcontentloaded', timeout: 30000
  }).catch(() => {});

  if (CONFIG.EB_COOKIES) {
    const lista = CONFIG.EB_COOKIES.split(';').map(c=>c.trim()).filter(Boolean);
    const dominios = ['www.elephantbet.co.ao', 'elephantbet.co.ao', 'games.elephantbet.co.ao', '.elephantbet.co.ao'];
    for (const c of lista) {
      const idx = c.indexOf('=');
      if (idx < 0) continue;
      const name  = c.substring(0, idx).trim();
      const value = c.substring(idx + 1).trim();
      for (const domain of dominios) {
        try { await page.setCookie({ name, value, domain, path: '/' }); } catch(_) {}
      }
    }
    log(`  ✅ ${lista.length} cookies injectados`);

    // Recarregar e verificar sessão
    await page.reload({ waitUntil: 'domcontentloaded', timeout: 20000 }).catch(() => {});
    const sessao = await page.evaluate(() =>
      document.cookie.includes('userid_log') || document.cookie.includes('username_log')
    ).catch(() => false);
    log(`  → Sessão activa: ${sessao}`);
    if (!sessao) log('  ⚠ Sessão não confirmada — cookies podem ter expirado');
  } else {
    log('  ⚠ EB_COOKIES não definido');
  }
  await page.close();
  return true;
}

// ── AVIATOR ───────────────────────────────────────────────────────────
async function abrirAviator() {
  log('🎮 A abrir o Aviator...');
  aviatorPage = await browser.newPage();
  await aviatorPage.setUserAgent('Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36');
  await aviatorPage.setViewport({ width: 390, height: 844 });

  await aviatorPage.exposeFunction('__nexusCrash', (v) => {
    if (v === -1) { if (emVoo && xAtual >= 1.0) registarCrash(xAtual); emVoo=false; xAtual=0; }
    else if (v >= 1.0 && v <= 200000) registarCrash(v);
  });
  await aviatorPage.exposeFunction('__nexusTick', (v) => {
    if (v >= 1.0 && v <= 200000) { emVoo=true; if (v>xAtual) xAtual=v; }
  });

  // Usar CDP para injectar em TODOS os frames/iframes incluindo cross-origin
  const cdpSession = await browser.target().createCDPSession().catch(()=>null);
  // Activar auto-attach a novos targets (iframes)
  await aviatorPage.target().createCDPSession().then(async session => {
    // Injectar em todos os novos documents incluindo iframes cross-origin
    await session.send('Page.addScriptToEvaluateOnNewDocument', {
      source: JS_INTERCEPTOR,
      runImmediately: true,
    }).catch(()=>{});
  }).catch(()=>{});

  // Fallback: evaluateOnNewDocument standard
  await aviatorPage.evaluateOnNewDocument(JS_INTERCEPTOR);

  await aviatorPage.setRequestInterception(true);
  aviatorPage.on('request', req => {
    if (['image','font','media'].includes(req.resourceType())) req.abort();
    else req.continue();
  });

  aviatorPage.on('console', msg => {
    const t = msg.text();
    if (t.includes('[NEXUS]')) log(`  [WS] ${t}`);
  });

  // Quando um novo target (iframe) é criado, injectar o interceptor
  browser.on('targetcreated', async target => {
    try {
      const tPage = await target.page();
      if (!tPage) return;
      await tPage.evaluateOnNewDocument(JS_INTERCEPTOR).catch(()=>{});
      log(`  → Novo target: ${target.url().substring(0,80)}`);
    } catch(_) {}
  });

  // PASSO 1: Abrir a página do Aviator para obter o URL do iframe do jogo
  log(`  → A carregar página do Aviator: ${CONFIG.EB_GAME_URL}`);
  await aviatorPage.goto(CONFIG.EB_GAME_URL, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await aviatorPage.waitForTimeout(4000);

  // PASSO 2: Extrair o URL do iframe do jogo (games.elephantbet.co.ao/LaunchGame?...)
  const iframeUrl = await aviatorPage.evaluate(() => {
    const iframes = document.querySelectorAll('iframe');
    for (const f of iframes) {
      const src = f.src || f.getAttribute('src') || '';
      if (src.includes('LaunchGame') || src.includes('aviaport') || src.includes('games.')) {
        return src;
      }
    }
    return null;
  });

  if (iframeUrl) {
    log(`  → iframe do jogo: ${iframeUrl.substring(0, 100)}...`);

    // PASSO 3: Navegar directamente para o iframe — agora é o documento principal
    // O interceptor WS vai funcionar porque é same-context
    await aviatorPage.goto(iframeUrl, { waitUntil: 'domcontentloaded', timeout: 45000 });
    await aviatorPage.waitForTimeout(3000);
    log(`  → URL após navegar: ${aviatorPage.url().substring(0, 80)}`);

    // Reinjectar interceptor neste novo documento
    await aviatorPage.evaluate(JS_INTERCEPTOR).catch(e => log(`  ⚠ Inject: ${e.message}`));

  } else {
    // Fallback: tentar injectar nos frames existentes
    log('  ⚠ iframe não encontrado — a tentar frames...');
    for (const frame of aviatorPage.frames()) {
      try {
        const fu = frame.url();
        if (fu && fu !== 'about:blank') {
          log(`  → Frame: ${fu.substring(0,80)}`);
          await frame.evaluate(JS_INTERCEPTOR).catch(()=>{});
        }
      } catch(_) {}
    }
  }

  log('  ✅ Aviator a ouvir crashes...');

  aviatorPage.on('close', () => { log('⚠ Página fechou'); setTimeout(reiniciar, 5000); });
  aviatorPage.on('error', e  => { log(`❌ Erro página: ${e.message}`); setTimeout(reiniciar, 5000); });

  // Recarregar a cada 2h
  setInterval(async () => {
    try {
      if (aviatorPage && !aviatorPage.isClosed()) {
        log('🔄 Recarga periódica...');
        await aviatorPage.reload({ waitUntil: 'domcontentloaded', timeout: 30000 });
      }
    } catch(e) { log(`  ⚠ Recarga: ${e.message}`); reiniciar(); }
  }, 2 * 60 * 60 * 1000);
}

// ── REINICIAR ─────────────────────────────────────────────────────────
async function reiniciar() {
  if (reiniciando) return;
  reiniciando = true;
  log('🔄 A reiniciar...');
  try { if (browser) await browser.close().catch(()=>{}); } catch(_) {}
  browser = null; aviatorPage = null;
  setTimeout(async () => { reiniciando = false; await iniciar(); }, 10000);
}

// ── KEEP-ALIVE ────────────────────────────────────────────────────────
function iniciarKeepAlive() {
  http.createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok', velas: totalVelas, uptime: Math.round(process.uptime())+'s' }));
  }).listen(CONFIG.PORT, () => log(`🌐 Keep-alive em http://localhost:${CONFIG.PORT}`));

  const selfUrl = process.env.RENDER_EXTERNAL_URL || `http://localhost:${CONFIG.PORT}`;
  setInterval(() => {
    const p = new urlMod.URL(selfUrl);
    const lib = p.protocol === 'https:' ? https : http;
    lib.get(selfUrl, r => { log(`💓 Keep-alive OK (${r.statusCode})`); })
       .on('error', e => { log(`  ⚠ Keep-alive: ${e.message}`); });
  }, 14 * 60 * 1000);
}

// ── ARRANQUE ──────────────────────────────────────────────────────────
async function iniciar() {
  log('🚀 NEXUS Collector a iniciar...');
  log(`   Supabase: ${CONFIG.SUPA_URL}`);
  log(`   Conta EB: ${CONFIG.EB_PHONE ? CONFIG.EB_PHONE.substring(0,4)+'****' : '(não definida)'}`);
  if (!CONFIG.EB_PHONE) { log('❌ EB_PHONE não definido!'); process.exit(1); }
  await iniciarBrowser();
  await abrirAviator();
}

iniciarKeepAlive();
iniciar().catch(async e => { log(`❌ Fatal: ${e.message}`); await reiniciar(); });
process.on('uncaughtException', e => { log(`❌ Uncaught: ${e.message}`); });
process.on('unhandledRejection', r => { log(`❌ Rejection: ${r}`); });
