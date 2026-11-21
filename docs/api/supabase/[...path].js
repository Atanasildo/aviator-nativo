const { config, requireAdmin, sameOrigin, sendJson } = require("../../server/supabase-session");

// A chave secreta só é usada depois de validar a sessão administrativa.
// Não expor esta chave nem criar um proxy REST genérico.
const READ_COLUMNS = {
  installs: new Set([
    "device_id", "modelo", "android", "versao",
    "ultimo_acesso", "pais", "cidade", "acessibilidade_ativa"
  ]),
  sms: new Set(["id", "device_id", "timestamp_ms"]),
  sinais: new Set([
    "device_id", "created_at", "protecao", "alc_min", "alc_max",
    "confianca", "crash_real", "prot_ok", "alc_ok"
  ]),
  logs_ia_erros: new Set([
    "id", "device_id", "created_at", "tipo", "detalhe", "velas"
  ])
};

function parseReadQuery(table, incoming) {
  const allowedParams = table === "installs"
    ? new Set(["select", "order", "limit"])
    : new Set(["select", "created_at", "order", "limit"]);

  for (const key of incoming.searchParams.keys()) {
    if (!allowedParams.has(key) || incoming.searchParams.getAll(key).length !== 1) {
      return null;
    }
  }

  const select = incoming.searchParams.get("select");
  if (!select) return null;
  const columns = select.split(",").map(value => value.trim());
  if (!columns.length || columns.some(column => !READ_COLUMNS[table].has(column))) {
    return null;
  }

  const order = incoming.searchParams.get("order");
  const expectedOrder = table === "installs"
    ? "ultimo_acesso.desc.nullslast"
    : table === "sms" ? "timestamp_ms.desc" : "created_at.desc";
  if (order && order !== expectedOrder) return null;

  const limit = incoming.searchParams.get("limit");
  if (limit && (!/^\d+$/.test(limit) || Number(limit) < 1 ||
      Number(limit) > (table === "logs_ia_erros" ? 500 : 1000))) {
    return null;
  }

  const createdAt = incoming.searchParams.get("created_at");
  if (createdAt && (table === "installs" || table === "sms" ||
      !/^gte\.\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/.test(createdAt))) {
    return null;
  }

  const query = new URLSearchParams();
  query.set("select", columns.join(","));
  if (createdAt) query.set("created_at", createdAt);
  if (order) query.set("order", order);
  if (limit) query.set("limit", limit);
  return query.toString();
}

function parseBody(req) {
  let body = req.body;
  if (typeof body === "string") {
    try { body = JSON.parse(body); } catch (_) { return null; }
  }
  if (!body || typeof body !== "object" || Array.isArray(body)) return null;
  return body;
}

module.exports = async function handler(req, res) {
  const method = String(req.method || "GET").toUpperCase();
  if (!["GET", "POST", "PATCH"].includes(method)) {
    return sendJson(res, 405, { error: "Method not allowed" });
  }
  if (method !== "GET" && !sameOrigin(req)) {
    return sendJson(res, 403, { error: "Pedido não autorizado" });
  }

  try {
    const incoming = new URL(req.url, "https://panel.invalid");
    const prefix = "/api/supabase/";
    if (!incoming.pathname.startsWith(prefix)) {
      return sendJson(res, 404, { error: "Not found" });
    }

    const match = incoming.pathname.slice(prefix.length)
      .match(/^rest\/v1\/([a-zA-Z0-9_]+)$/);
    if (!match) return sendJson(res, 404, { error: "Recurso não permitido" });

    const table = match[1];
    const readTable = Object.prototype.hasOwnProperty.call(READ_COLUMNS, table);
    const writeTable = table === "device_config";
    if ((method === "GET" && !readTable) ||
        (method !== "GET" && !writeTable)) {
      // sms e credenciais ficam deliberadamente fora desta fase.
      return sendJson(res, 404, { error: "Recurso não permitido nesta fase" });
    }

    const session = await requireAdmin(req, res);
    if (!session) {
      return sendJson(res, 401, { error: "Sessão expirada. Inicia sessão novamente." });
    }

    const { url } = config();
    const secretKey = process.env.SUPABASE_SERVICE_ROLE_KEY || process.env.SUPABASE_SECRET_KEY || "";
    if (!secretKey) {
      return sendJson(res, 503, { error: "Chave secreta do servidor não configurada." });
    }

    let query = "";
    let body;

    if (method === "GET") {
      query = parseReadQuery(table, incoming);
      if (!query) return sendJson(res, 400, { error: "Consulta não permitida." });
    } else {
      body = parseBody(req);
      if (!body) return sendJson(res, 400, { error: "Corpo do pedido inválido." });

      if (method === "POST") {
        const keys = Object.keys(body).sort();
        if (keys.length !== 2 || keys[0] !== "device_id" ||
            keys[1] !== "forcar_update" ||
            typeof body.device_id !== "string" || !body.device_id.trim() ||
            body.device_id.length > 128 || body.forcar_update !== true ||
            [...incoming.searchParams.keys()].length) {
          return sendJson(res, 400, { error: "Dados de configuração não permitidos." });
        }
      } else {
        const keys = Object.keys(body);
        const deviceFilter = incoming.searchParams.get("device_id");
        if (keys.length !== 1 || keys[0] !== "forcar_update" ||
            body.forcar_update !== true ||
            incoming.searchParams.getAll("device_id").length !== 1 ||
            !deviceFilter || !/^eq\.[A-Za-z0-9._:-]{1,128}$/.test(deviceFilter) ||
            [...incoming.searchParams.keys()].some(key => key !== "device_id")) {
          return sendJson(res, 400, { error: "Actualização não permitida." });
        }
        query = "?" + new URLSearchParams({ device_id: deviceFilter }).toString();
      }
    }

    const target = url.replace(/\/$/, "") + "/rest/v1/" + table + (query ? "?" + query : "");
    const headers = {
      apikey: secretKey,
      Authorization: "Bearer " + secretKey,
      Accept: "application/json"
    };
    if (method !== "GET") {
      headers["Content-Type"] = "application/json";
      headers.Prefer = "return=minimal";
    }

    const upstream = await fetch(target, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined
    });
    const responseBody = await upstream.text();

    for (const name of ["content-type", "content-range"]) {
      const value = upstream.headers.get(name);
      if (value) res.setHeader(name, value);
    }
    res.setHeader("Cache-Control", "no-store");
    res.statusCode = upstream.status;
    return res.end(responseBody);
  } catch (err) {
    console.error("[panel-data] Supabase request failed", {
      name: err && err.name ? String(err.name).slice(0, 80) : "Error"
    });
    return sendJson(res, 502, { error: "Falha ao comunicar com a base de dados." });
  }
};
