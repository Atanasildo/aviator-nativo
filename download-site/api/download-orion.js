const SUPA_URL = process.env.SUPA_URL || process.env.SUPABASE_URL;
const API_KEY = process.env.SUPABASE_PUBLISHABLE_KEY || process.env.SUPABASE_ANON_KEY;

module.exports = async function handler(req, res) {
  if (req.method !== "GET" && req.method !== "HEAD") {
    res.setHeader("Allow", "GET, HEAD");
    res.statusCode = 405;
    return res.end("Method not allowed");
  }

  if (!SUPA_URL || !API_KEY) {
    res.statusCode = 503;
    return res.end("Download temporariamente indisponível");
  }

  try {
    const base = SUPA_URL.replace(/\/$/, "");
    const metadataResponse = await fetch(
      `${base}/rest/v1/versao?bot=eq.orion&select=versao,url_apk&limit=1`,
      { headers: { apikey: API_KEY, Authorization: `Bearer ${API_KEY}` }, cache: "no-store" }
    );
    if (!metadataResponse.ok) throw new Error("Não foi possível consultar a versão publicada");

    const rows = await metadataResponse.json();
    const row = rows[0];
    if (!row || !row.url_apk) {
      res.statusCode = 404;
      return res.end("APK não encontrado");
    }

    const source = new URL(row.url_apk);
    if (source.protocol !== "https:" || source.hostname !== new URL(base).hostname) {
      res.statusCode = 502;
      return res.end("Endereço do APK inválido");
    }

    const filename = source.pathname.split("/").pop();
    if (!/^VooMatrix-v[0-9]+(?:\.[0-9]+)*\.apk$/i.test(filename)) {
      res.statusCode = 409;
      return res.end("A versão publicada ainda não está pronta para download");
    }

    const upstream = await fetch(source.toString(), { method: req.method, redirect: "error", cache: "no-store" });
    if (!upstream.ok) {
      res.statusCode = upstream.status;
      return res.end("Não foi possível obter o ficheiro APK");
    }

    res.setHeader("Content-Type", "application/vnd.android.package-archive");
    res.setHeader("Content-Disposition", `attachment; filename="${filename}"`);
    res.setHeader("Cache-Control", "private, no-store, max-age=0");
    res.setHeader("X-Content-Type-Options", "nosniff");

    const length = upstream.headers.get("content-length");
    if (length) res.setHeader("Content-Length", length);

    if (req.method === "HEAD") {
      res.statusCode = 200;
      return res.end();
    }

    const bytes = Buffer.from(await upstream.arrayBuffer());
    res.statusCode = 200;
    res.setHeader("Content-Length", String(bytes.length));
    return res.end(bytes);
  } catch (error) {
    res.statusCode = 502;
    return res.end("Falha ao preparar o download");
  }
};
