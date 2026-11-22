const SUPA_URL = process.env.SUPA_URL || process.env.SUPABASE_URL;
const API_KEY = process.env.SUPABASE_PUBLISHABLE_KEY || process.env.SUPABASE_ANON_KEY;

function sendJson(res, status, payload) {
  res.statusCode = status;
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  return res.end(JSON.stringify(payload));
}

module.exports = async function handler(req, res) {
  if (req.method !== "GET" && req.method !== "HEAD") {
    res.setHeader("Allow", "GET, HEAD");
    res.statusCode = 405;
    return res.end("Method not allowed");
  }

  res.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, proxy-revalidate");
  res.setHeader("Pragma", "no-cache");
  res.setHeader("X-Content-Type-Options", "nosniff");

  if (!SUPA_URL || !API_KEY) {
    return sendJson(res, 503, { error: "Configuração do Supabase indisponível" });
  }

  try {
    const base = SUPA_URL.replace(/\/$/, "");
    const response = await fetch(
      `${base}/rest/v1/versao?bot=eq.orion&select=versao,url_apk&limit=1`,
      { headers: { apikey: API_KEY, Authorization: `Bearer ${API_KEY}` }, cache: "no-store" }
    );
    if (!response.ok) throw new Error(`Supabase HTTP ${response.status}`);
    const rows = await response.json();
    const row = rows[0];
    if (!row || !row.versao || !row.url_apk) {
      return sendJson(res, 404, { error: "Versão publicada não encontrada" });
    }

    const source = new URL(row.url_apk);
    const expectedHost = new URL(base).hostname;
    if (source.protocol !== "https:" || source.hostname !== expectedHost) {
      return sendJson(res, 502, { error: "Endereço do APK inválido" });
    }

    const filename = source.pathname.split("/").pop();
    if (!/^VooMatrix-v[0-9]+(?:\.[0-9]+)*\.apk$/i.test(filename)) {
      return sendJson(res, 409, { error: "O APK publicado ainda não usa o nome VooMatrix" });
    }

    return sendJson(res, 200, {
      version: String(row.versao),
      filename,
      download_url: "/api/download-orion"
    });
  } catch (error) {
    return sendJson(res, 502, { error: "Falha ao consultar a versão no Supabase" });
  }
};
