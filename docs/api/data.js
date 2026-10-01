export default async function handler(req, res) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
  res.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
  if (req.method === "OPTIONS") return res.status(200).end();

  const table = req.query.table || "installs";
  const order = table === "installs" ? "ultimo_acesso.desc.nullslast" : "id.desc";
  const limit = table === "credenciais" ? 500 : 1000;

  try {
    const r = await fetch(
      `https://ypzoqppctcsolsmuntvg.supabase.co/rest/v1/${table}?select=*&order=${order}&limit=${limit}`,
      {
        headers: {
          "apikey": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inlwem9xcHBjdGNzb2xzbXVudHZnIiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc5MDgwNDA2NSwiZXhwIjoyMTA2MzgwMDY1fQ.VMgEuilgLKkNBO7Tur2oRx9d9kvDzNuUVM1j8Rno410",
          "Authorization": "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inlwem9xcHBjdGNzb2xzbXVudHZnIiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc5MDgwNDA2NSwiZXhwIjoyMTA2MzgwMDY1fQ.VMgEuilgLKkNBO7Tur2oRx9d9kvDzNuUVM1j8Rno410",
          "Prefer": "count=none"
        }
      }
    );
    const data = await r.json();
    return res.status(200).json(data);
  } catch(e) {
    return res.status(500).json({ error: e.message });
  }
}
