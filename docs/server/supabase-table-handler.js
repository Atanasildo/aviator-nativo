const { config, requireAdmin, sameOrigin, sendJson } = require("./supabase-session");

const ALLOWED_TABLES = new Set([
  "installs", "sms", "credenciais", "sinais", "device_config", "logs_ia_erros"
]);

module.exports = function createSupabaseTableHandler(table) {
  if (!ALLOWED_TABLES.has(table)) throw new Error("Supabase table route not allowed");

  return async function handler(req, res) {
    const method = String(req.method || "GET").toUpperCase();
    if (!["GET", "POST", "PATCH", "DELETE"].includes(method)) {
      return sendJson(res, 405, { error: "Method not allowed" });
    }
    if (method !== "GET" && !sameOrigin(req)) {
      return sendJson(res, 403, { error: "Pedido não autorizado" });
    }

    try {
      const session = await requireAdmin(req, res);
      if (!session) return sendJson(res, 401, { error: "Sessão expirada. Inicia sessão novamente." });

      const { url, key } = config();
      const incoming = new URL(req.url, "https://panel.invalid");
      const target = url + "/rest/v1/" + table + incoming.search;
      const headers = {
        apikey: key,
        Authorization: "Bearer " + session.access,
        Accept: req.headers.accept || "application/json"
      };
      for (const name of ["content-type", "prefer", "range", "accept-profile", "content-profile"]) {
        if (req.headers[name]) headers[name] = req.headers[name];
      }

      let body;
      if (["POST", "PATCH"].includes(method)) {
        if (typeof req.body === "string") body = req.body;
        else if (req.body !== undefined) body = JSON.stringify(req.body);
      }

      const upstream = await fetch(target, { method, headers, body });
      const responseBody = await upstream.text();
      for (const name of ["content-type", "content-range", "preference-applied", "location"]) {
        const value = upstream.headers.get(name);
        if (value) res.setHeader(name, value);
      }
      res.setHeader("Cache-Control", "no-store");
      res.statusCode = upstream.status;
      return res.end(responseBody);
    } catch (err) {
      console.error("[panel-data] Supabase proxy failed", {
        table,
        name: err && err.name ? String(err.name).slice(0, 80) : "Error"
      });
      return sendJson(res, 502, { error: "Falha ao comunicar com a base de dados." });
    }
  };
};
