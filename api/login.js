/**
 * POST /api/login
 * Valida senha e retorna token JWT
 * Body: { password: "sua_senha" }
 */

const jwt = require("jsonwebtoken");

module.exports = async function handler(req, res) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type");

  if (req.method === "OPTIONS") return res.status(200).end();

  if (req.method !== "POST") {
    return res.status(405).json({ error: "Method not allowed" });
  }

  const { password } = req.body;
  const correctPassword = process.env.PANEL_PASSWORD || "skybot123";
  const secretKey = process.env.PANEL_SECRET_KEY || "dev_secret_key";

  // Validar senha
  if (!password || password !== correctPassword) {
    return res.status(401).json({ error: "Senha inválida" });
  }

  // Gerar token JWT válido por 7 dias
  const token = jwt.sign({ auth: true, iat: Math.floor(Date.now() / 1000) }, secretKey, {
    expiresIn: "7d",
  });

  return res.status(200).json({ 
    success: true, 
    token,
    message: "Login realizado com sucesso"
  });
};
