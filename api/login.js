module.exports = async function handler(req, res) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type");

  if (req.method === "OPTIONS") return res.status(200).end();
  if (req.method !== "POST") return res.status(405).json({ error: "Method not allowed" });

  const { password } = req.body || {};
  const correctPassword = process.env.PANEL_PASSWORD || "skybot123";

  if (!password || password !== correctPassword) {
    return res.status(401).json({ error: "Senha invalida" });
  }

  // Token simples sem JWT — base64 do timestamp + senha
  const token = Buffer.from(correctPassword + ":" + Date.now()).toString("base64");

  return res.status(200).json({ success: true, token });
};
