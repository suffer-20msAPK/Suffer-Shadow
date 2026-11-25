// SUFFER SHADOW – promosyon kodu doğrulama sunucusu (referans uygulama, bağımlılık yok).
// Yalnızca SUFFER SHADOW'un kendi kodları içindir; PUBG UC / oyun içi kodlarla ilgisi yoktur.
// TLS'i ters vekil (nginx / Caddy / bulut yük dengeleyici) sonlandırmalıdır: uygulama yalnızca https:// adresini kabul eder.
'use strict';
const http = require('http');
const fs = require('fs');
const crypto = require('crypto');

const PORT = parseInt(process.env.PORT || '8080', 10);
const ADMIN_TOKEN = process.env.ADMIN_TOKEN || '';
const PEPPER = process.env.CODE_PEPPER || '';
const DATA_FILE = process.env.DATA_FILE || './codes.json';
const SUPPORTED = new Set(['session_minutes']);

if (ADMIN_TOKEN.length < 24 || PEPPER.length < 16) {
  console.error('ADMIN_TOKEN (>=24 karakter) ve CODE_PEPPER (>=16 karakter) ortam değişkenleri zorunludur.');
  process.exit(1);
}

// ---- depolama: kodlar düz metin değil, HMAC-SHA256 özeti olarak saklanır ----
let db = { codes: {} };
try { db = JSON.parse(fs.readFileSync(DATA_FILE, 'utf8')); } catch (_) { /* ilk çalıştırma */ }
function save() {
  const tmp = DATA_FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(db));
  fs.renameSync(tmp, DATA_FILE);
}
const hashCode = (c) => crypto.createHmac('sha256', PEPPER).update(c).digest('hex');
const safeEq = (a, b) => { const x = Buffer.from(a), y = Buffer.from(b); return x.length === y.length && crypto.timingSafeEqual(x, y); };

// ---- hız sınırı (bellek içi; çok örnekli kurulumda Redis kullanın) ----
const hits = new Map(); // key -> [timestamps]
function limited(key, max, windowMs) {
  const now = Date.now();
  const arr = (hits.get(key) || []).filter((t) => now - t < windowMs);
  arr.push(now);
  hits.set(key, arr);
  return arr.length > max;
}
setInterval(() => { const now = Date.now(); for (const [k, v] of hits) if (!v.some((t) => now - t < 3600e3)) hits.delete(k); }, 600e3).unref();

function send(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  res.end(body);
}
function readJson(req) {
  return new Promise((resolve, reject) => {
    let n = 0; const chunks = [];
    req.on('data', (c) => { n += c.length; if (n > 4096) { reject(new Error('too large')); req.destroy(); } else chunks.push(c); });
    req.on('end', () => { try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); } catch (e) { reject(e); } });
    req.on('error', reject);
  });
}

async function handleRedeem(req, res) {
  const ip = req.headers['x-forwarded-for'] ? String(req.headers['x-forwarded-for']).split(',')[0].trim() : req.socket.remoteAddress;
  if (limited('ip:' + ip, 30, 60e3)) return send(res, 429, { status: 'rate_limited' });
  let b;
  try { b = await readJson(req); } catch (_) { return send(res, 400, { status: 'bad_request' }); }
  const nonce = typeof b.nonce === 'string' && b.nonce.length <= 64 ? b.nonce : null;
  const code = typeof b.code === 'string' ? b.code.trim().toUpperCase() : '';
  const device = typeof b.deviceId === 'string' && /^[A-Za-z0-9-]{8,64}$/.test(b.deviceId) ? b.deviceId : null;
  if (!nonce || !device) return send(res, 400, { status: 'bad_request' });
  const reply = (status, extra) => send(res, status === 'rate_limited' ? 429 : 200, Object.assign({ status, nonce }, extra || {}));

  // Yanlış kod denemeleri cihaz başına sıkı sınırlanır (kaba kuvvet koruması)
  if (limited('dev:' + device, 20, 600e3)) return reply('rate_limited');
  if (!/^[A-Z0-9-]{4,32}$/.test(code)) return reply('invalid');

  const entry = db.codes[hashCode(code)];
  if (!entry) { if (limited('fail:' + device, 5, 600e3)) return reply('rate_limited'); return reply('invalid'); }
  if (entry.expiresAt && Date.now() > entry.expiresAt) return reply('expired');
  if (entry.uses.length >= entry.maxUses || entry.uses.some((u) => u.deviceId === device)) return reply('used');
  const supports = Array.isArray(b.supports) ? b.supports : [];
  if (!SUPPORTED.has(entry.reward.type) || !supports.includes(entry.reward.type)) return reply('unsupported'); // tüketmeden reddet

  entry.uses.push({ deviceId: device, at: Date.now() }); // tek iş parçacığı: kontrol+tüketme atomiktir
  save();
  return reply('ok', { reward: entry.reward });
}

async function handleAdminCreate(req, res) {
  const auth = String(req.headers['authorization'] || '');
  if (!safeEq(auth, 'Bearer ' + ADMIN_TOKEN)) return send(res, 401, { error: 'unauthorized' });
  let b;
  try { b = await readJson(req); } catch (_) { return send(res, 400, { error: 'bad_request' }); }
  const type = b.rewardType || 'session_minutes';
  const value = parseInt(b.value, 10);
  const maxUses = parseInt(b.maxUses || 1, 10);
  const expiresAt = b.expiresAt ? Date.parse(b.expiresAt) : null;
  if (!SUPPORTED.has(type) || !(value >= 1 && value <= 1440) || !(maxUses >= 1 && maxUses <= 100000) || (b.expiresAt && isNaN(expiresAt))) {
    return send(res, 400, { error: 'invalid_parameters' });
  }
  const code = (b.code ? String(b.code) : crypto.randomBytes(8).toString('hex').toUpperCase().replace(/(.{4})/g, '$1-').slice(0, -1)).toUpperCase();
  if (!/^[A-Z0-9-]{4,32}$/.test(code)) return send(res, 400, { error: 'invalid_code_format' });
  const h = hashCode(code);
  if (db.codes[h]) return send(res, 409, { error: 'exists' });
  db.codes[h] = { reward: { type, value }, expiresAt, maxUses, uses: [], createdAt: Date.now() };
  save();
  return send(res, 201, { code, reward: { type, value }, expiresAt, maxUses }); // düz metin kod yalnızca burada bir kez görünür
}

const server = http.createServer((req, res) => {
  const url = req.url.split('?')[0];
  if (req.method === 'GET' && url === '/healthz') return send(res, 200, { ok: true });
  if (req.method === 'POST' && url === '/v1/redeem') return handleRedeem(req, res).catch(() => send(res, 500, { status: 'error' }));
  if (req.method === 'POST' && url === '/v1/admin/codes') return handleAdminCreate(req, res).catch(() => send(res, 500, { error: 'error' }));
  send(res, 404, { error: 'not_found' });
});
server.listen(PORT, () => console.log('redeem server :' + PORT));

