const jwt = require("jsonwebtoken");

// Função para validar token
function verificarToken(req) {
  const token = req.headers.authorization?.replace("Bearer ", "");
  if (!token) return null;

  try {
    const secretKey = process.env.PANEL_SECRET_KEY || "dev_secret_key";
    const decoded = jwt.verify(token, secretKey);
    return decoded;
  } catch (err) {
    return null;
  }
}

module.exports = async function handler(req, res) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
  if (req.method === "OPTIONS") return res.status(200).end();

  // ✅ VALIDAR TOKEN ANTES DE RETORNAR DADOS
  const tokenValido = verificarToken(req);
  if (!tokenValido) {
    return res.status(401).json({ error: "Autenticação necessária. Faça login primeiro." });
  }

  const table = req.query.table || "installs";
  const order = table === "installs" ? "ultimo_acesso.desc.nullslast" : "created_at.desc";

  const supabaseUrl = process.env.SUPABASE_URL || "https://ypzoqppctcsolsmuntvg.supabase.co";
  const supabaseKey = process.env.SUPABASE_API_KEY || "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inlwem9xcHBjdGNzb2xzbXVudHZnIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTA4MDQwNjUsImV4cCI6MjEwNjM4MDA2NX0.e1srtRC2BpIcG4F9q6FY4-i9Ys1E2buLdkD9osF---Y";

  const r = await fetch(`${supabaseUrl}/rest/v1/${table}?select=*&order=${order}`, {
    headers: {
      "apikey": supabaseKey,
      "Authorization": `Bearer ${supabaseKey}`
    }
  });
  const data = await r.json();
  return res.status(r.status).json(data);
};
