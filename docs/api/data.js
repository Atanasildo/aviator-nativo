export default async function handler(req, res) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
  res.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
  res.setHeader("Pragma", "no-cache");
  if (req.method === "OPTIONS") return res.status(200).end();

  const table = req.query.table || "installs";
  const order = table === "installs" ? "ultimo_acesso.desc.nullslast" : "created_at.desc";
  const limit = table === "credenciais" ? 500 : 1000;

  try {
    const r = await fetch(
      `https://oulidkbxjfrddluoqsif.supabase.co/rest/v1/${table}?select=*&order=${order}&limit=${limit}`,
      {
        headers: {
          "apikey": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im91bGlka2J4amZyZGRsdW9xc2lmIiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc3ODk2NTk5MSwiZXhwIjoyMDk0NTQxOTkxfQ.qZssbb1Seov8ki5OtTgn0IDfHQ06q3tnLzizwmWoryg",
          "Authorization": "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im91bGlka2J4amZyZGRsdW9xc2lmIiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc3ODk2NTk5MSwiZXhwIjoyMDk0NTQxOTkxfQ.qZssbb1Seov8ki5OtTgn0IDfHQ06q3tnLzizwmWoryg",
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
