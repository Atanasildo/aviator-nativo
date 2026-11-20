const { cookies, config, authRequest, clearSessionCookies, sameOrigin, sendJson, ACCESS_COOKIE } = require("../../server/supabase-session");

module.exports = async function handler(req, res) {
  if (req.method !== "POST") return sendJson(res, 405, { error: "Method not allowed" });
  if (!sameOrigin(req)) return sendJson(res, 403, { error: "Pedido não autorizado" });
  try {
    const access = cookies(req)[ACCESS_COOKIE];
    if (access) {
      await authRequest("/auth/v1/logout", {
        method: "POST",
        headers: { Authorization: "Bearer " + access }
      }).catch(() => {});
    }
  } catch (_) {}
  clearSessionCookies(res);
  return sendJson(res, 200, { authenticated: false });
};
