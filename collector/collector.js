/**
 * NEXUS — Collector de Velas do Aviator
 * Usa Puppeteer (Chrome headless) para passar o Cloudflare
 * e ligar ao WebSocket do Aviator via intercepção do browser
 */

const puppeteer = require('puppeteer-core');
const chromium  = require('@sparticuz/chromium');
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

// ── JS INJECTADO NO AVIATOR PARA INTERCEPTAR WS ───────────────────────
const JS_INTERCEPTOR = `
(function() {
  if (window.__nexusInjectado) return;
  window.__nexusInjectado = true;

  var crashPatterns = [
    /"crash_x"\\s*:\\s*([\\d.]+)/,
    /"crash_point"\\s*:\\s*([\\d.]+)/,
    /"cashout_coef"\\s*:\\s*([\\d.]+)/,
    /"finish_coef"\\s*:\\s*([\\d.]+)/,
    /"end_coef"\\s*:\\s*([\\d.]+)/,
  ];
  var tickPatterns = [
    /"coefficient"\\s*:\\s*([\\d.]+)/,
    /"coef"\\s*:\\s*([\\d.]+)/,
    /"multiplier"\\s*:\\s*([\\d.]+)/,
  ];

  function processMsg(msg) {
    if (!msg || msg.length < 3) return;
    var corpo = msg;
    if (msg.startsWith('a[')) {
      try { corpo = JSON.parse(msg.substring(1))[0]; } catch(e) {}
    } else if (msg.startsWith('42[')) {
      try { corpo = JSON.stringify(JSON.parse(msg.substring(2))[1]); } catch(e) {}
    }

    // Crash
    for (var i = 0; i < crashPatterns.length; i++) {
      var m = corpo.match(crashPatterns[i]);
      if (m && m[1]) { window.__nexusCrash(parseFloat(m[1])); return; }
    }
    if (corpo.match(/"game_state"\\s*:\\s*"(?:crashed|finished|end)"/)) {
      window.__nexusCrash(-1); return;
    }

    // Tick
    for (var j = 0; j < tickPatterns.length; j++) {
      var t = corpo.match(tickPatterns[j]);
      if (t && t[1]) { window.__nexusTick(parseFloat(t[1])); return; }
    }
  }

  // Interceptar WebSocket
  var WSOrig = window.WebSocket;
  window.WebSocket = function(url, p) {
    var ws = p ? new WSOrig(url, p) : new WSOrig(url);
    ws.addEventListener('message', function(e) {
      try {
        if (typeof e.data === 'string') processMsg(e.data);
      } catch(ex) {}
    });
    return ws;
  };
  window.WebSocket.prototype = WSOrig.prototype;
  window.WebSocket.CONNECTING = 0;
  window.WebSocket.OPEN = 1;
  window.WebSocket.CLOSING = 2;
  window.WebSocket.CLOSED = 3;

  console.log('[NEXUS] Interceptor WS activo');
})();
`;

// ── INICIAR BROWSER E FAZER LOGIN ─────────────────────────────────────
async function iniciarBrowser() {
  log('🌐 A iniciar Chrome headless...');

  // Usar @sparticuz/chromium no Render (serverless/Linux)
  const execPath = process.env.PUPPETEER_EXECUTABLE_PATH
                || await chromium.executablePath()
                || '/usr/bin/chromium'
                || '/usr/bin/chromium-browser';

  browser = await puppeteer.launch({
    headless       : chromium.headless,
    args           : [
      ...chromium.args,
      '--no-sandbox',
      '--disable-setuid-sandbox',
      '--disable-dev-shm-usage',
      '--disable-gpu',
      '--no-first-run',
      '--no-zygote',
      '--single-process',
    ],
    defaultViewport : chromium.defaultViewport,
    executablePath  : execPath,
  });

  const page = await browser.newPage();

  // User-agent realista
  await page.setUserAgent('Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36');
  await page.setViewport({ width: 390, height: 844 });

  // Bloquear imagens/fonts/css para ser mais rápido
  await page.setRequestInterception(true);
  page.on('request', req => {
    const t = req.resourceType();
    if (['image', 'font', 'stylesheet', 'media'].includes(t)) {
      req.abort();
    } else {
      req.continue();
    }
  });

  log('🔐 A carregar página do ElephantBet...');
  await page.goto(`${CONFIG.EB_BASE}/pt/sports/tournaments`, {
    waitUntil : 'networkidle2',
    timeout   : 40000,
  });
  await page.waitForTimeout(3000);

  log('🔐 A fazer login...');
  try {
    const username = CONFIG.EB_PHONE.startsWith('244') ? CONFIG.EB_PHONE : '244' + CONFIG.EB_PHONE;

    // PASSO 1: Clicar no botão "Entrar" que abre o formulário de login
    // Confirmado via DevTools: button.btn.s-small.sign-in[type="button"]
    log('  → A clicar botão sign-in...');
    await page.waitForSelector('button.sign-in', { visible: true, timeout: 15000 });
    await page.click('button.sign-in');
    await page.waitForTimeout(1500);

    // PASSO 2: Aguardar o formulário de login aparecer
    log('  → A aguardar formulário...');
    await page.waitForSelector('input[name="username"]', { visible: true, timeout: 10000 });

    // PASSO 3: Preencher username (com 244 prefixado)
    await page.click('input[name="username"]', { clickCount: 3 });
    await page.type('input[name="username"]', username, { delay: 80 });
    log(`  → username: ${username}`);

    // PASSO 4: Preencher password
    await page.click('input[name="password"]', { clickCount: 3 });
    await page.type('input[name="password"]', CONFIG.EB_PASSWORD, { delay: 80 });
    log('  → password preenchida');

    // PASSO 5: Clicar no botão submit "Entrar"
    // Confirmado: button.btn.a-color[type="submit"]
    await Promise.all([
      page.waitForNavigation({ waitUntil: 'domcontentloaded', timeout: 20000 }).catch(() => {}),
      page.click('button[type="submit"]'),
    ]);

    log('  ✅ Login submetido');
  } catch(e) {
    log(`  ⚠ Erro no login: ${e.message}`);
  }

  // Verificar se logou
  await page.waitForTimeout(3000);
  const url_atual = page.url();
  const cookies   = await page.cookies();
  const logado    = cookies.some(c => c.name === 'userid_log' || c.name === 'username_log');

  if (logado) {
    log('  ✅ Login confirmado via cookies');
  } else {
    log(`  ⚠ Login não confirmado (URL: ${url_atual}) — a tentar abrir Aviator na mesma`);
  }

  await page.close();
  return logado || true; // continuar mesmo sem confirmação
}

// ── ABRIR O AVIATOR E INJECTAR INTERCEPTOR ────────────────────────────
async function abrirAviator() {
  log('🎮 A abrir o Aviator...');

  aviatorPage = await browser.newPage();

  await aviatorPage.setUserAgent('Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36');
  await aviatorPage.setViewport({ width: 390, height: 844 });

  // Expor funções Kotlin→JS equivalentes no contexto do browser
  await aviatorPage.exposeFunction('__nexusCrash', (valor) => {
    if (valor === -1) {
      // game_state crashed sem coeficiente — usar xAtual
      if (emVoo && xAtual >= 1.0) registarCrash(xAtual);
      emVoo = false; xAtual = 0;
    } else if (valor >= 1.0 && valor <= 200000) {
      registarCrash(valor);
    }
  });

  await aviatorPage.exposeFunction('__nexusTick', (valor) => {
    if (valor >= 1.0 && valor <= 200000) {
      emVoo = true;
      if (valor > xAtual) xAtual = valor;
    }
  });

  // Injectar interceptor antes de qualquer script da página
  await aviatorPage.evaluateOnNewDocument(JS_INTERCEPTOR);

  // Bloquear recursos pesados
  await aviatorPage.setRequestInterception(true);
  aviatorPage.on('request', req => {
    const t = req.resourceType();
    if (['image', 'font', 'stylesheet', 'media'].includes(t)) {
      req.abort();
    } else {
      req.continue();
    }
  });

  log(`  → A navegar para: ${CONFIG.EB_GAME_URL}`);
  await aviatorPage.goto(CONFIG.EB_GAME_URL, {
    waitUntil : 'domcontentloaded',
    timeout   : 45000,
  });

  log('  ✅ Aviator carregado — a ouvir crashes...');

  // Vigiar se a página fechar ou crashar
  aviatorPage.on('close', () => {
    log('⚠ Página do Aviator fechou — a reiniciar...');
    setTimeout(reiniciar, 5000);
  });
  aviatorPage.on('error', (err) => {
    log(`❌ Erro na página: ${err.message}`);
    setTimeout(reiniciar, 5000);
  });

  // Manter a página viva — recarregar a cada 2 horas se necessário
  setInterval(async () => {
    try {
      if (aviatorPage && !aviatorPage.isClosed()) {
        log('🔄 Recarga periódica da página do Aviator...');
        await aviatorPage.reload({ waitUntil: 'domcontentloaded', timeout: 30000 });
        await aviatorPage.evaluateOnNewDocument(JS_INTERCEPTOR);
      }
    } catch(e) {
      log(`  ⚠ Erro na recarga: ${e.message}`);
      reiniciar();
    }
  }, 2 * 60 * 60 * 1000);
}

// ── REINICIAR TUDO ────────────────────────────────────────────────────
let reiniciando = false;
async function reiniciar() {
  if (reiniciando) return;
  reiniciando = true;
  log('🔄 A reiniciar browser...');
  try {
    if (browser) await browser.close().catch(() => {});
  } catch(e) {}
  browser = null; aviatorPage = null;
  setTimeout(async () => {
    reiniciando = false;
    await iniciar();
  }, 10000);
}

// ── KEEP-ALIVE HTTP ───────────────────────────────────────────────────
function iniciarKeepAlive() {
  http.createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      status  : 'ok',
      velas   : totalVelas,
      uptime  : Math.round(process.uptime()) + 's',
    }));
  }).listen(CONFIG.HEARTBEAT_PORT, () => {
    log(`🌐 Keep-alive em http://localhost:${CONFIG.HEARTBEAT_PORT}`);
  });

  // Auto-ping a cada 14 min para não adormecer
  const selfUrl = process.env.RENDER_EXTERNAL_URL || `http://localhost:${CONFIG.HEARTBEAT_PORT}`;
  setInterval(() => {
    const p   = new url.URL(selfUrl);
    const lib = p.protocol === 'https:' ? https : http;
    lib.get(selfUrl, r => { log(`💓 Keep-alive OK (${r.statusCode})`); })
       .on('error', e => { log(`  ⚠ Keep-alive: ${e.message}`); });
  }, 14 * 60 * 1000);
}

// ── ARRANQUE PRINCIPAL ────────────────────────────────────────────────
async function iniciar() {
  log('🚀 NEXUS Collector a iniciar...');
  log(`   Supabase: ${CONFIG.SUPA_URL}`);
  log(`   Conta EB: ${CONFIG.EB_PHONE ? CONFIG.EB_PHONE.substring(0, 4) + '****' : '(não definida)'}`);

  if (!CONFIG.EB_PHONE || !CONFIG.EB_PASSWORD) {
    log('❌ EB_PHONE e EB_PASSWORD não definidos!');
    process.exit(1);
  }

  await iniciarBrowser();
  await abrirAviator();
}

iniciarKeepAlive();
iniciar().catch(async (e) => {
  log(`❌ Erro fatal: ${e.message}`);
  await reiniciar();
});

process.on('uncaughtException', (e) => { log(`❌ Uncaught: ${e.message}`); });
process.on('unhandledRejection', (r) => { log(`❌ Rejection: ${r}`); });
