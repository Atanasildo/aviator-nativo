const https = require('https');
const http  = require('http');

function req(url) {
  return new Promise(resolve => {
    const lib = url.startsWith('https') ? https : http;
    const r = lib.get(url, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36',
        'Origin'    : 'https://games.elephantbet.co.ao',
        'Referer'   : 'https://games.elephantbet.co.ao/',
      },
      timeout: 8000,
    }, res => {
      let d = '';
      res.on('data', c => d += c);
      res.on('end', () => resolve({ status: res.statusCode, body: d.substring(0, 200) }));
    });
    r.on('error', e => resolve({ status: 0, body: e.message }));
    r.on('timeout', () => resolve({ status: 0, body: 'timeout' }));
  });
}

async function main() {
  const urls = [
    'https://aviaport.spribegaming.elephantbet.co.ao/socket.io/?EIO=4&transport=polling',
    'https://aviaport.spribegaming.elephantbet.co.ao/gs2c/getHistory?gameId=aviator&rounds=10',
    'https://games.elephantbet.co.ao/socket.io/?EIO=4&transport=polling',
    'https://aviaport.spribegaming.elephantbet.co.ao/api/history',
    'https://aviaport.spribegaming.elephantbet.co.ao/history',
  ];

  for (const url of urls) {
    const r = await req(url);
    console.log(`[${r.status}] ${url}`);
    if (r.body && r.status !== 403) console.log('  →', r.body.replace(/\n/g,' '));
  }

  // Keep alive para o Render não fechar
  http.createServer((req, res) => res.end('ok')).listen(process.env.PORT || 3000);
  console.log('Testes concluídos — servidor keep-alive activo');
}

main().catch(console.error);
