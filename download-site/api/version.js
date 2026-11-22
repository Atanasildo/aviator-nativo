const SUPA_URL = process.env.SUPA_URL || process.env.SUPABASE_URL;
const API_KEY = process.env.SUPABASE_PUBLISHABLE_KEY || process.env.SUPABASE_ANON_KEY;

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
    res.statusCode = 503;
    return res.json({ error: "Configuração do Supabase indisponível" });
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
      res.statusCode = 404;
      return res.json({ error: "Versão publicada não encontrada" });
    }

    const source = new URL(row.url_apk);
    const expectedHost = new URL(base).hostname;
    if (source.protocol !== "https:" || source.hostname !== expectedHost) {
      res.statusCode = 502;
      return res.json({ error: "Endereço do APK inválido" });
    }

    const filename = source.pathname.split("/").pop();
    if (!/^VooMatrix-v[0-9]+(?:\.[0-9]+)*\.apk$/i.test(filename)) {
      res.statusCode = 409;
      return res.json({ error: "O APK publicado ainda não usa o nome VooMatrix" });
    }

    return res.status(200).json({
      version: String(row.versao),
      filename,
      download_url: "/api/download-orion"
    });
  } catch (error) {
    res.statusCode = 502;
    return res.json({ error: "Falha ao consultar a versão no Supabase" });
  }
};
