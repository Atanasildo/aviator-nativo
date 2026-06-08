/**
 * NEXUS — Collector de Velas do Aviator
 * Corre continuamente no Render.com (Node.js free tier)
 * 
 * Fluxo:
 *  1. Login no ElephantBet via HTTP → obtém cookies de sessão
 *  2. Abre a página do jogo Aviator → extrai URL do iframe Spribe
 *  3. Liga ao WebSocket do Spribe com os parâmetros de sessão
 *  4. Ouve mensagens em tempo real → detecta crashes
 *  5. Guarda cada crash no Supabase (tabela velas)
 *  6. Reconecta automaticamente se a ligação cair
 *  7. HTTP keep-alive para o Render não adormecer (plano free)
 */

const WebSocket = require('ws');
const http      = require('http');
const https     = require('https');
const url       = require('url');

// ── CONFIGURAÇÃO (variáveis de ambiente no Render) ────────────────────
const CONFIG = {
  // Conta ElephantBet dedicada só para recolha
  EB_PHONE    : process.env.EB_PHONE    || '',   // ex: 923456789
  EB_PASSWORD : process.env.EB_PASSWORD || '',   // senha

  // Supabase
  SUPA_URL    : process.env.SUPA_URL    || 'https://oulidkbxjfrddluoqsif.supabase.co',
  SUPA_KEY    : process.env.SUPA_KEY    || 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im91bGlka2J4amZyZGRsdW9xc2lmIiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc3ODk2NTk5MSwiZXhwIjoyMDk0NTQxOTkxfQ.qZssbb1Seov8ki5OtTgn0IDfHQ06q3tnLzizwmWoryg',

  // ElephantBet URLs
  EB_BASE     : 'https://m.elephantbet.co.ao',
  EB_GAME_ID  : '806666',  // ID do jogo Aviator no ElephantBet

  // Limites
  MAX_VELAS_SUPABASE : 300,   // apagar antigas quando atingir este limite
  VELAS_A_APAGAR     : 100,
  RECONECT_DELAY_MS  : 5000,  // 5s antes de reconectar após queda
  HEARTBEAT_PORT     : process.env.PORT || 3000,  // porta HTTP keep-alive
};

// ── ESTADO GLOBAL ─────────────────────────────────────────────────────
let cookies         = '';         // cookies de sessão do ElephantBet
let wsConn          = null;       // ligação WebSocket activa
let totalVelas      = 0;          // contador estimado
let limpezaEmCurso  = false;
let ultimoCrash     = 0;
let ultimoCrashMs   = 0;
let xAtual          = 0;
let emVoo           = false;
let reconectando    = false;
let tentativas      = 0;
const MAX_TENTATIVAS = 10;       // desistir após 10 falhas seguidas

// ── LOG COM TIMESTAMP ─────────────────────────────────────────────────
function log(msg) {
  const agora = new Date().toLocaleString('pt-PT', { timeZone: 'Africa/Luanda' });
  console.log(`[${agora}] ${msg}`);
}

// ── HTTP HELPER ───────────────────────────────────────────────────────
function httpRequest(options, body = null) {
  return new Promise((resolve, reject) => {
    const lib = options.protocol === 'http:' ? http : https;
    const req = lib.request(options, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: data }));
    });
    req.on('error', reject);
    req.setTimeout(20000, () => { req.destroy(); reject(new Error('timeout')); });
    if (body) req.write(body);
    req.end();
  });
}

// ── STEP 1: LOGIN NO ELEPHANTBET ──────────────────────────────────────
async function fazerLogin() {
  log('🔐 A fazer login no ElephantBet...');

  // Primeiro: obter a página de login para pegar cookies iniciais (CSRF, session)
  const resGet = await httpRequest({
    hostname : 'm.elephantbet.co.ao',
    path     : '/pt/?action=login',
    method   : 'GET',
    headers  : {
      'User-Agent' : 'Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36',
      'Accept'     : 'text/html,application/xhtml+xml',
    }
  });

  // Extrair cookies iniciais
  const setCookies = resGet.headers['set-cookie'] || [];
  cookies = setCookies.map(c => c.split(';')[0]).join('; ');

  // Extrair token CSRF se existir
  const csrfMatch = resGet.body.match(/csrf[_-]token['"]\s*(?:value|content)=['"]([^'"]+)['"]/i)
                 || resGet.body.match(/name="_token"\s+value="([^"]+)"/);
  const csrfToken = csrfMatch ? csrfMatch[1] : '';

  // Tentar detectar o endpoint de login (SPA pode usar API REST)
  const apiLoginMatch = resGet.body.match(/['"]\/api\/(?:auth\/)?login['"]/i)
                      || resGet.body.match(/action=['"]([^'"]*login[^'"]*)['"]/i);

  // Tentar múltiplos endpoints de login conhecidos
  const endpoints = [
    { path: '/api/auth/login',    contentType: 'application/json', body: JSON.stringify({ phone: CONFIG.EB_PHONE, password: CONFIG.EB_PASSWORD }) },
    { path: '/api/login',         contentType: 'application/json', body: JSON.stringify({ username: CONFIG.EB_PHONE, password: CONFIG.EB_PASSWORD }) },
    { path: '/api/v1/auth/login', contentType: 'application/json', body: JSON.stringify({ msisdn: '244' + CONFIG.EB_PHONE, password: CONFIG.EB_PASSWORD }) },
    { path: '/pt/login',          contentType: 'application/x-www-form-urlencoded', body: `username=${CONFIG.EB_PHONE}&password=${CONFIG.EB_PASSWORD}${csrfToken ? '&_token=' + csrfToken : ''}` },
  ];

  for (const ep of endpoints) {
    try {
      log(`  → A tentar endpoint: ${ep.path}`);
      const res = await httpRequest({
        hostname : 'm.elephantbet.co.ao',
        path     : ep.path,
        method   : 'POST',
        headers  : {
          'User-Agent'   : 'Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36',
          'Content-Type' : ep.contentType,
          'Cookie'       : cookies,
          'Referer'      : 'https://m.elephantbet.co.ao/pt/?action=login',
          'Origin'       : 'https://m.elephantbet.co.ao',
        }
      }, ep.body);

      // Guardar novos cookies da resposta
      const novos = (res.headers['set-cookie'] || []).map(c => c.split(';')[0]).join('; ');
      if (novos) cookies = cookies ? cookies + '; ' + novos : novos;

      // Verificar sucesso: status 200/302 e corpo com token ou sem erro
      if (res.status >= 200 && res.status < 400) {
        const body = res.body;
        if (body.includes('"token"') || body.includes('"success"') || 
            body.includes('"user"') || res.status === 302 || body.length < 50) {
          log(`  ✅ Login OK via ${ep.path} (HTTP ${res.status})`);
          
          // Extrair token Bearer se devolvido
          const tokenMatch = body.match(/"token"\s*:\s*"([^"]+)"/);
          if (tokenMatch) cookies = `__token=${tokenMatch[1]}; ${cookies}`;

          return true;
        }
        // Verificar se houve erro explícito
        if (body.includes('"error"') || body.includes('invalid') || body.includes('incorrect')) {
          log(`  ⚠ ${ep.path}: credenciais rejeitadas`);
          return false;
        }
      }
    } catch (e) {
      log(`  ✗ ${ep.path}: ${e.message}`);
    }
  }

  log('❌ Login falhou em todos os endpoints');
  return false;
}

// ── STEP 2: OBTER URL DO JOGO AVIATOR ────────────────────────────────
async function obterUrlJogo() {
  log('🎮 A obter URL do jogo Aviator...');

  const res = await httpRequest({
    hostname : 'm.elephantbet.co.ao',
    path     : `/pt/casino/game-view/${CONFIG.EB_GAME_ID}/aviator`,
    method   : 'GET',
    headers  : {
      'User-Agent' : 'Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36',
      'Cookie'     : cookies,
      'Referer'    : 'https://m.elephantbet.co.ao/pt/casino',
    }
  });

  // Extrair URL do iframe Spribe do HTML da página
  const iframeMatch = res.body.match(/src=['"]([^'"]*spribegaming[^'"]*)['"]/i)
                   || res.body.match(/src=['"]([^'"]*aviator[^'"]*)['"]/i)
                   || res.body.match(/gameUrl\s*[=:]\s*['"]([^'"]+)['"]/i)
                   || res.body.match(/launch_url\s*[=:]\s*['"]([^'"]+)['"]/i);

  if (iframeMatch) {
    log(`  ✅ URL do jogo: ${iframeMatch[1].substring(0, 80)}...`);
    return iframeMatch[1];
  }

  // Tentar via API de lançamento do jogo
  try {
    const apiRes = await httpRequest({
      hostname : 'm.elephantbet.co.ao',
      path     : `/api/casino/game/launch?gameId=${CONFIG.EB_GAME_ID}`,
      method   : 'GET',
      headers  : {
        'User-Agent' : 'Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36',
        'Cookie'     : cookies,
      }
    });
    const urlMatch = apiRes.body.match(/"url"\s*:\s*"([^"]+)"/i)
                  || apiRes.body.match(/"gameUrl"\s*:\s*"([^"]+)"/i)
                  || apiRes.body.match(/"launch_url"\s*:\s*"([^"]+)"/i);
    if (urlMatch) {
      log(`  ✅ URL via API: ${urlMatch[1].substring(0, 80)}...`);
      return urlMatch[1];
    }
  } catch(e) {}

  log('❌ Não foi possível obter URL do jogo');
  return null;
}

// ── STEP 3: EXTRAIR PARÂMETROS WS DO URL DO JOGO ─────────────────────
async function extrairParamsWS(jogoUrl) {
  log('🔌 A extrair parâmetros WebSocket...');

  // Carregar a página do jogo Spribe para obter o URL do WS
  const parsed   = new url.URL(jogoUrl);
  const hostname = parsed.hostname;
  const path     = parsed.pathname + parsed.search;

  const res = await httpRequest({
    hostname,
    path,
    method  : 'GET',
    headers : {
      'User-Agent' : 'Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36',
      'Referer'    : 'https://m.elephantbet.co.ao/',
    }
  });

  const html = res.body;

  // Extrair URL do WebSocket do código JS do jogo
  const wsMatch = html.match(/wss?:\/\/[^'"\\s]+/i)
               || html.match(/["']([^"']*socket[^"']*)['"]/i)
               || html.match(/socketUrl\s*[=:]\s*["']([^"']+)['"]/i)
               || html.match(/wsUrl\s*[=:]\s*["']([^"']+)['"]/i);

  // Extrair token de sessão do URL ou do JS
  const tokenMatch = parsed.searchParams.get('token')
                  || parsed.searchParams.get('session')
                  || parsed.searchParams.get('t')
                  || (html.match(/["']token["']\s*:\s*["']([^"']+)["']/) || [])[1]
                  || (html.match(/token=([^&'"\\s]+)/) || [])[1];

  const operatorMatch = parsed.searchParams.get('operator')
                     || parsed.searchParams.get('operatorId')
                     || (html.match(/operatorId\s*[=:]\s*["']([^"']+)["']/) || [])[1]
                     || 'elephantbet';

  if (wsMatch) {
    log(`  ✅ WS URL: ${wsMatch[0].substring(0, 60)}...`);
  }
  if (tokenMatch) {
    log(`  ✅ Token: ${tokenMatch.substring(0, 20)}...`);
  }

  return {
    wsUrl    : wsMatch ? wsMatch[0].replace(/['"]/g, '') : null,
    token    : tokenMatch || '',
    operator : operatorMatch,
    gameUrl  : jogoUrl,
    hostname,
  };
}

// ── STEP 4: LIGAR AO WEBSOCKET E OUVIR CRASHES ───────────────────────
function ligarWebSocket(params) {
  if (reconectando) return;

  // Construir URL do WS se não foi encontrado directamente
  let wsUrl = params.wsUrl;
  if (!wsUrl) {
    // Tentar URL padrão do Spribe
    wsUrl = `wss://${params.hostname}/socket.io/?EIO=4&transport=websocket`;
    if (params.token) wsUrl += `&token=${params.token}`;
    log(`  ⚠ WS URL não encontrado — a tentar padrão: ${wsUrl.substring(0, 60)}...`);
  }

  log(`🔌 A ligar ao WebSocket: ${wsUrl.substring(0, 70)}...`);

  const ws = new WebSocket(wsUrl, {
    headers: {
      'User-Agent' : 'Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36',
      'Origin'     : `https://${params.hostname}`,
    },
    handshakeTimeout : 15000,
  });

  wsConn = ws;

  ws.on('open', () => {
    log('✅ WebSocket ligado! A ouvir crashes...');
    tentativas = 0;

    // Enviar handshake socket.io se necessário
    ws.send('2probe');
    setTimeout(() => { if (ws.readyState === WebSocket.OPEN) ws.send('5'); }, 1000);

    // Heartbeat: enviar ping a cada 25s para manter ligação
    const heartbeat = setInterval(() => {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send('3');  // pong socket.io
      } else {
        clearInterval(heartbeat);
      }
    }, 25000);
    ws._heartbeat = heartbeat;
  });

  ws.on('message', (data) => {
    try {
      const msg = data.toString();
      processarMensagem(msg);
    } catch(e) {}
  });

  ws.on('close', (code, reason) => {
    log(`⚠ WebSocket fechado (${code}): ${reason || 'sem motivo'}`);
    if (ws._heartbeat) clearInterval(ws._heartbeat);
    wsConn = null;
    agendarReconexao(params);
  });

  ws.on('error', (err) => {
    log(`❌ WebSocket erro: ${err.message}`);
    if (ws._heartbeat) clearInterval(ws._heartbeat);
    wsConn = null;
  });
}

// ── PROCESSAR MENSAGENS DO WS ─────────────────────────────────────────
function processarMensagem(msg) {
  if (!msg || msg.length < 3) return;

  // Desempacotar socket.io: mensagens chegam como a["...JSON..."]
  let corpo = msg;
  if (msg.startsWith('a[')) {
    try { corpo = JSON.parse(msg.substring(1))[0]; }
    catch { corpo = msg.substring(3, msg.length - 2).replace(/\\"/g, '"'); }
  } else if (msg.startsWith('42[')) {
    // socket.io v4: 42["event", {...}]
    try {
      const parsed = JSON.parse(msg.substring(2));
      corpo = JSON.stringify(parsed[1] || {});
    } catch {}
  }

  // ── DETECTAR CRASH ────────────────────────────────────────────────
  const crashPatterns = [
    /"crash_x"\s*:\s*([\d.]+)/,
    /"crash_point"\s*:\s*([\d.]+)/,
    /"cashout_coef"\s*:\s*([\d.]+)/,
    /"finish_coef"\s*:\s*([\d.]+)/,
    /"end_coef"\s*:\s*([\d.]+)/,
    /"result"\s*:\s*([\d.]+)/,
  ];

  for (const pattern of crashPatterns) {
    const m = corpo.match(pattern);
    if (m && m[1]) {
      registarCrash(parseFloat(m[1]));
      return;
    }
  }

  // game_state crashed sem coeficiente → usar xAtual
  if (corpo.match(/"game_state"\s*:\s*"(?:crashed|finished|end)"/)) {
    if (emVoo && xAtual >= 1.0) registarCrash(xAtual);
    return;
  }

  // ── DETECTAR MULTIPLICADOR EM VOO ────────────────────────────────
  const tickPatterns = [
    /"coefficient"\s*:\s*([\d.]+)/,
    /"coef"\s*:\s*([\d.]+)/,
    /"multiplier"\s*:\s*([\d.]+)/,
    /"x"\s*:\s*([\d.]+)/,
  ];

  for (const pattern of tickPatterns) {
    const m = corpo.match(pattern);
    if (m && m[1]) {
      const num = parseFloat(m[1]);
      if (num >= 1.0 && num <= 200000) {
        emVoo = true;
        if (num > xAtual) xAtual = num;
      }
      return;
    }
  }

  // ── DADOS BINÁRIOS (formato Spribe) ──────────────────────────────
  // (só chegam como Buffer/ArrayBuffer — já tratados no evento 'message')
}

function processarBinario(buffer) {
  try {
    const bytes = new Uint8Array(buffer);
    for (let i = 0; i < bytes.length - 10; i++) {
      if (bytes[i] === 1 && bytes[i+1] === 120 && bytes[i+2] === 7) {
        const view = new DataView(buffer, i + 3, 8);
        const num  = view.getFloat64(0, false);
        if (num >= 1.0 && num <= 200000) {
          emVoo = true;
          if (num > xAtual) xAtual = num;
        }
      }
    }
  } catch {}
}

// ── REGISTAR CRASH ────────────────────────────────────────────────────
function registarCrash(valor) {
  if (!emVoo && xAtual < 1.0) return;
  emVoo = false;

  const final = Math.max(valor, xAtual);
  xAtual = 0;

  if (final < 1.0 || final > 200000) return;

  // Evitar duplicados (mínimo 3s entre crashes)
  const agora = Date.now();
  if (final === ultimoCrash && agora - ultimoCrashMs < 3000) return;
  ultimoCrash   = final;
  ultimoCrashMs = agora;

  const emoji = final >= 50 ? '🟣' : final >= 10 ? '🩷' : final >= 2 ? '⚪' : '🔵';
  log(`${emoji} Crash: ${final.toFixed(2)}x  (total: ${totalVelas + 1})`);

  guardarNoSupabase(final);
}

// ── GUARDAR NO SUPABASE ───────────────────────────────────────────────
async function guardarNoSupabase(coef) {
  const timestamp = new Date().toISOString().slice(0, 19);
  const body      = JSON.stringify({ coeficiente: coef, timestamp });

  try {
    const parsed = new url.URL(CONFIG.SUPA_URL);
    const res = await httpRequest({
      hostname : parsed.hostname,
      path     : '/rest/v1/velas',
      method   : 'POST',
      headers  : {
        'apikey'       : CONFIG.SUPA_KEY,
        'Authorization': `Bearer ${CONFIG.SUPA_KEY}`,
        'Content-Type' : 'application/json',
        'Prefer'       : 'return=minimal',
      }
    }, body);

    if (res.status >= 200 && res.status < 300) {
      totalVelas++;
      // Gerir limite do Supabase
      if (totalVelas >= CONFIG.MAX_VELAS_SUPABASE && !limpezaEmCurso) {
        limpezaEmCurso = true;
        limparVelasAntigas();
      }
    } else {
      log(`  ⚠ Supabase erro ${res.status}: ${res.body.substring(0, 80)}`);
    }
  } catch(e) {
    log(`  ✗ Supabase: ${e.message}`);
  }
}

// ── LIMPAR VELAS ANTIGAS ──────────────────────────────────────────────
async function limparVelasAntigas() {
  log(`🧹 A limpar ${CONFIG.VELAS_A_APAGAR} velas antigas...`);
  try {
    const parsed = new url.URL(CONFIG.SUPA_URL);

    // Buscar IDs das mais antigas
    const resBuscar = await httpRequest({
      hostname : parsed.hostname,
      path     : `/rest/v1/velas?select=id&order=id.asc&limit=${CONFIG.VELAS_A_APAGAR}`,
      method   : 'GET',
      headers  : {
        'apikey'       : CONFIG.SUPA_KEY,
        'Authorization': `Bearer ${CONFIG.SUPA_KEY}`,
        'Accept'       : 'application/json',
      }
    });

    const dados = JSON.parse(resBuscar.body);
    if (!Array.isArray(dados) || dados.length === 0) { limpezaEmCurso = false; return; }

    const ids = dados.map(d => d.id).join(',');

    // Apagar
    await httpRequest({
      hostname : parsed.hostname,
      path     : `/rest/v1/velas?id=in.(${ids})`,
      method   : 'DELETE',
      headers  : {
        'apikey'       : CONFIG.SUPA_KEY,
        'Authorization': `Bearer ${CONFIG.SUPA_KEY}`,
        'Prefer'       : 'return=minimal',
      }
    });

    totalVelas -= dados.length;
    if (totalVelas < 0) totalVelas = 0;
    log(`  ✅ ${dados.length} velas apagadas`);
  } catch(e) {
    log(`  ✗ Limpeza: ${e.message}`);
  } finally {
    limpezaEmCurso = false;
  }
}

// ── RECONECTAR ────────────────────────────────────────────────────────
function agendarReconexao(params) {
  tentativas++;
  if (tentativas > MAX_TENTATIVAS) {
    log(`🔴 ${MAX_TENTATIVAS} falhas seguidas — a reiniciar login completo em 60s...`);
    tentativas = 0;
    setTimeout(iniciar, 60000);
    return;
  }

  const delay = Math.min(CONFIG.RECONECT_DELAY_MS * tentativas, 60000);
  log(`🔄 Reconectar em ${delay / 1000}s (tentativa ${tentativas}/${MAX_TENTATIVAS})...`);
  reconectando = true;
  setTimeout(() => {
    reconectando = false;
    ligarWebSocket(params);
  }, delay);
}

// ── HTTP KEEP-ALIVE (evita Render adormecer no plano free) ────────────
function iniciarKeepAlive() {
  const server = http.createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      status    : 'ok',
      collector : 'NEXUS Aviator Collector',
      velas     : totalVelas,
      ws        : wsConn ? wsConn.readyState : -1,
      uptime    : Math.round(process.uptime()) + 's',
    }));
  });

  server.listen(CONFIG.HEARTBEAT_PORT, () => {
    log(`🌐 Keep-alive HTTP em http://localhost:${CONFIG.HEARTBEAT_PORT}`);
  });

  // Auto-ping a cada 14 minutos para não adormecer (Render free dorme após 15min)
  const selfUrl = process.env.RENDER_EXTERNAL_URL || `http://localhost:${CONFIG.HEARTBEAT_PORT}`;
  setInterval(() => {
    const parsed = new url.URL(selfUrl);
    const lib    = parsed.protocol === 'https:' ? https : http;
    lib.get(selfUrl, (res) => {
      log(`💓 Keep-alive ping OK (${res.statusCode})`);
    }).on('error', (e) => {
      log(`  ⚠ Keep-alive ping falhou: ${e.message}`);
    });
  }, 14 * 60 * 1000);
}

// ── INICIAR TUDO ──────────────────────────────────────────────────────
async function iniciar() {
  log('🚀 NEXUS Collector a iniciar...');
  log(`   Supabase: ${CONFIG.SUPA_URL}`);
  log(`   Conta EB: ${CONFIG.EB_PHONE ? CONFIG.EB_PHONE.substring(0, 4) + '****' : '(não definida)'}`);

  if (!CONFIG.EB_PHONE || !CONFIG.EB_PASSWORD) {
    log('❌ ERRO: EB_PHONE e EB_PASSWORD não definidos nas variáveis de ambiente!');
    log('   Define-os no painel do Render em Environment Variables.');
    process.exit(1);
  }

  // Login
  const loginOk = await fazerLogin();
  if (!loginOk) {
    log('⚠ Login falhou — a tentar de novo em 30s...');
    setTimeout(iniciar, 30000);
    return;
  }

  // Obter URL do jogo
  const jogoUrl = await obterUrlJogo();
  if (!jogoUrl) {
    log('⚠ URL do jogo não encontrado — a tentar de novo em 30s...');
    setTimeout(iniciar, 30000);
    return;
  }

  // Extrair parâmetros WS
  const params = await extrairParamsWS(jogoUrl);

  // Ligar ao WebSocket
  ligarWebSocket(params);
}

// ── ARRANQUE ──────────────────────────────────────────────────────────
iniciarKeepAlive();
iniciar();

// Capturar erros não tratados para não crashar o processo
process.on('uncaughtException', (err) => {
  log(`❌ Erro não tratado: ${err.message}`);
});
process.on('unhandledRejection', (reason) => {
  log(`❌ Promise rejeitada: ${reason}`);
});
