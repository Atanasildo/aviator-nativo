const SOURCE_URL = process.env.ORION_APK_SOURCE_URL;

module.exports = async function handler(req, res) {
  if (req.method !== "GET" && req.method !== "HEAD") {
    res.setHeader("Allow", "GET, HEAD");
    res.statusCode = 405;
    return res.end("Method not allowed");
  }

  if (!SOURCE_URL) {
    res.statusCode = 503;
    return res.end("Download temporariamente indisponível");
  }

  try {
    const upstream = await fetch(SOURCE_URL, { method: req.method, redirect: "error" });
    if (!upstream.ok) {
      res.statusCode = upstream.status;
      return res.end("Não foi possível obter o ficheiro");
    }

    res.setHeader("Content-Type", "application/vnd.android.package-archive");
    res.setHeader("Content-Disposition", 'attachment; filename="ORION-v3.0.apk"');
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
  } catch (_) {
    res.statusCode = 502;
    return res.end("Falha ao preparar o download");
  }
};
