const { authRequest, isAdmin, setSessionCookies, sameOrigin, readBody, sendJson } = require("../../server/supabase-session");

module.exports = async function handler(req, res) {
  if (req.method !== "POST") return sendJson(res, 405, { error: "Method not allowed" });
  if (!sameOrigin(req)) return sendJson(res, 403, { error: "Pedido não autorizado" });

  const { email, password } = readBody(req);
  if (typeof email !== "string" || typeof password !== "string" || !email.trim() || !password) {
    return sendJson(res, 400, { error: "Preenche email e senha." });
  }

  try {
    const r = await authRequest("/auth/v1/token?grant_type=password", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: email.trim(), password })
    });
    const data = await r.json().catch(() => ({}));
    if (!r.ok || !data.access_token || !data.refresh_token || !data.user) {
      const providerCode = String(data.error_code || data.code || data.error || "unknown").slice(0, 100);
      console.error("[panel-auth] Supabase password grant rejected", {
        status: r.status,
        providerCode,
        responseComplete: !!(data.access_token && data.refresh_token && data.user)
      });
      return sendJson(res, 401, {
        error: "Credenciais inválidas.",
        diagnostic: { providerStatus: r.status, providerCode }
      });
    }
    if (!isAdmin(data.user)) {
      await authRequest("/auth/v1/logout", {
        method: "POST",
        headers: { Authorization: "Bearer " + data.access_token }
      }).catch(() => {});
      return sendJson(res, 403, { error: "Esta conta não tem autorização de administrador." });
    }

    setSessionCookies(res, data.access_token, data.refresh_token, data.expires_in);
    return sendJson(res, 200, { authenticated: true, email: data.user.email || "" });
  } catch (err) {
    console.error("[panel-auth] Authentication request failed", {
      name: err && err.name ? String(err.name).slice(0, 80) : "Error"
    });
    return sendJson(res, 500, { error: "Não foi possível validar o acesso neste momento." });
  }
};
