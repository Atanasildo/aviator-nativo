const ACCESS_COOKIE = "__Host-altus_access";
const REFRESH_COOKIE = "__Host-altus_refresh";
const REFRESH_MAX_AGE = 60 * 60 * 24 * 30;

function config() {
  const url = (process.env.SUPABASE_URL || process.env.SUPA_URL || "").replace(/\/$/, "");
  const key = process.env.SUPABASE_PUBLISHABLE_KEY || process.env.SUPABASE_ANON_KEY || "";
  if (!url || !key) throw new Error("Missing Supabase server configuration");
  return { url, key };
}

function cookies(req) {
  const raw = req.headers.cookie || "";
  return Object.fromEntries(raw.split(";").map(part => {
    const i = part.indexOf("=");
    if (i < 0) return ["", ""];
    return [part.slice(0, i).trim(), decodeURIComponent(part.slice(i + 1).trim())];
  }).filter(([k]) => k));
}

function setSessionCookies(res, access, refresh, expiresIn) {
  const secure = "; Secure";
  const common = "; Path=/; HttpOnly; SameSite=Strict" + secure;
  const accessAge = Math.max(60, Math.min(Number(expiresIn) || 3600, 3600));
  res.setHeader("Set-Cookie", [
    ACCESS_COOKIE + "=" + encodeURIComponent(access) + common + "; Max-Age=" + accessAge,
    REFRESH_COOKIE + "=" + encodeURIComponent(refresh) + common + "; Max-Age=" + REFRESH_MAX_AGE
  ]);
}

function clearSessionCookies(res) {
  const common = "; Path=/; HttpOnly; SameSite=Strict; Secure; Max-Age=0";
  res.setHeader("Set-Cookie", [
    ACCESS_COOKIE + "=" + common,
    REFRESH_COOKIE + "=" + common
  ]);
}

function sameOrigin(req) {
  const origin = req.headers.origin;
  const host = String(req.headers["x-forwarded-host"] || req.headers.host || "").split(",")[0].trim();
  if (!origin || !host) return false;
  try {
    const parsed = new URL(origin);
    return parsed.host.toLowerCase() === host.toLowerCase() &&
      (parsed.protocol === "https:" || host.startsWith("localhost:"));
  } catch (_) {
    return false;
  }
}

function isAdmin(user) {
  if (!user) return false;
  const app = user.app_metadata || {};
  if (app.role === "admin" || (Array.isArray(app.roles) && app.roles.includes("admin"))) return true;
  const allow = (process.env.ADMIN_EMAILS || "").split(",").map(x => x.trim().toLowerCase()).filter(Boolean);
  return !!user.email && allow.includes(String(user.email).toLowerCase());
}

async function authRequest(path, options = {}) {
  const { url, key } = config();
  return fetch(url + path, {
    ...options,
    headers: { apikey: key, ...(options.headers || {}) }
  });
}

async function fetchUser(access) {
  const r = await authRequest("/auth/v1/user", {
    headers: { Authorization: "Bearer " + access }
  });
  if (!r.ok) return null;
  return r.json();
}

async function refreshSession(refresh) {
  const r = await authRequest("/auth/v1/token?grant_type=refresh_token", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refresh_token: refresh })
  });
  if (!r.ok) return null;
  const data = await r.json();
  if (!data.access_token || !data.refresh_token || !data.user) return null;
  return data;
}

async function requireAdmin(req, res) {
  const jar = cookies(req);
  let access = jar[ACCESS_COOKIE];
  const refresh = jar[REFRESH_COOKIE];
  if (!access) return null;

  let user = await fetchUser(access);
  if (!user && refresh) {
    const renewed = await refreshSession(refresh);
    if (renewed) {
      access = renewed.access_token;
      user = renewed.user;
      setSessionCookies(res, renewed.access_token, renewed.refresh_token, renewed.expires_in);
    }
  }

  if (!user || !isAdmin(user)) {
    clearSessionCookies(res);
    return null;
  }
  return { access, user };
}

function readBody(req) {
  if (req.body && typeof req.body === "object") return req.body;
  if (typeof req.body === "string") {
    try { return JSON.parse(req.body); } catch (_) { return {}; }
  }
  return {};
}

function sendJson(res, status, body) {
  res.statusCode = status;
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  res.setHeader("Cache-Control", "no-store");
  res.end(JSON.stringify(body));
}

module.exports = {
  config, cookies, setSessionCookies, clearSessionCookies,
  sameOrigin, isAdmin, authRequest, fetchUser, refreshSession,
  requireAdmin, readBody, sendJson,
  ACCESS_COOKIE, REFRESH_COOKIE
};
