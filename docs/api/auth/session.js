const { requireAdmin, sendJson } = require("../../server/supabase-session");

module.exports = async function handler(req, res) {
  if (req.method !== "GET") return sendJson(res, 405, { error: "Method not allowed" });
  res.setHeader("Cache-Control", "no-store");
  try {
    const session = await requireAdmin(req, res);
    if (!session) return sendJson(res, 401, { authenticated: false });
    return sendJson(res, 200, { authenticated: true, email: session.user.email || "" });
  } catch (_) {
    return sendJson(res, 503, { authenticated: false, error: "Serviço de autenticação indisponível." });
  }
};
