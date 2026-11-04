/**
 * BoltUtil merchant integration demo (Node.js 22+).
 * Uses only POST /api/v1/order/create, not the WordPress /payments API.
 * Flow: create an order server-side -> save returned payment details -> open checkoutUrl -> verify the webhook -> record payment in a database transaction.
 * This protocol demo does not connect to your database or fulfill orders; see handleVerifiedEvent for the required production steps.
 * Read secrets only from server-side environment variables; never put them in frontend code, repositories, URLs or logs.
 */
const http = require('node:http');
const crypto = require('node:crypto');
const MAX_BODY = 64 * 1024; // Demo receiver limit, not a platform-wide BoltUtil API limit.
const SECRET = process.env.BOLT_WEBHOOK_SECRET || '';
const BASE = process.env.BOLT_API_BASE || 'https://api.boltutil.com';

function requireSecret() {
  if (!SECRET || /^(YOUR_|whsec_xxx)/.test(SECRET)) throw new Error('Set BOLT_WEBHOOK_SECRET');
}
// timestamp must be in milliseconds. Sign and send the same raw UTF-8 bytes without serializing again.
function signBody(body, timestamp) {
  return crypto.createHmac('sha256', SECRET).update(timestamp + '.').update(body).digest('hex');
}
function verifyWebhook(body, timestamp, signature) {
  if (typeof timestamp !== 'string' || !/^[0-9]{13}$(?![\s\S])/.test(timestamp)
      || typeof signature !== 'string' || !/^[a-fA-F0-9]{64}$(?![\s\S])/.test(signature)) return false;
  if (Math.abs(Date.now() - Number(timestamp)) > 300000) return false;
  return crypto.timingSafeEqual(Buffer.from(signBody(body, timestamp), 'hex'), Buffer.from(signature, 'hex'));
}
function orderInput(network, currency, amount) {
  network = network.toUpperCase(); currency = currency.toUpperCase();
  if (!['TRC20', 'ERC20', 'BEP20', 'POLYGON', 'SOLANA', 'BASE'].includes(network)
      || !['USDT', 'USDC'].includes(currency) || (network === 'TRC20' && currency === 'USDC')) {
    throw new Error('Unsupported network/currency pair (TRC20 supports USDT only)');
  }
  // Use decimal text, not floating-point arithmetic, exponent notation or negatives; minimum 1, up to 18 integer digits and 6 decimal places.
  if (!/^[1-9][0-9]{0,17}(\.[0-9]{1,6})?$(?![\s\S])/.test(amount)) throw new Error('Amount must be >= 1, max 6 decimals');
  const externalOrderId = process.env.BOLT_EXTERNAL_ORDER_ID || 'INV-' + crypto.randomUUID();
  if (!/^[A-Za-z0-9_-]{6,64}$(?![\s\S])/.test(externalOrderId)) throw new Error('Invalid externalOrderId');
  return { network, currency, amount, externalOrderId };
}
async function createOrder(network = 'TRC20', currency = 'USDT', amount = '1.00') {
  requireSecret();
  const key = process.env.BOLT_API_KEY || '';
  if (!key || /^(YOUR_|bt_live_xxx)/.test(key)) throw new Error('Set BOLT_API_KEY');
  const input = orderInput(network, currency, amount);
  const notifyUrl = process.env.BOLT_NOTIFY_URL || '';
  if (!notifyUrl.startsWith('https://')) throw new Error('Set a public HTTPS BOLT_NOTIFY_URL');
  const returnUrl = new URL(process.env.BOLT_RETURN_URL || 'https://merchant.example.com/orders/return');
  returnUrl.searchParams.set('order', input.externalOrderId); // Preserve the existing query parameters and fragment.
  const body = JSON.stringify({ ...input, notifyUrl, returnUrl: returnUrl.href,
    expiredMinutes: 30, orderDesc: 'BoltUtil demo order', metadata: { source: 'node-demo' } })
    // amount is strictly validated; emit an exact JSON number without a floating-point conversion through JS Number.
    .replace('"amount":"' + amount + '"', '"amount":' + amount);
  const timestamp = String(Date.now());
  console.log('externalOrderId:', input.externalOrderId); // After a timeout, query this same order ID rather than blindly creating another order.
  const response = await fetch(BASE.replace(/\/$/, '') + '/api/v1/order/create', {
    method: 'POST', redirect: 'error', signal: AbortSignal.timeout(20000),
    headers: { 'Content-Type': 'application/json', 'X-Bolt-Key': key,
      'X-Bolt-Timestamp': timestamp, 'X-Bolt-Signature': signBody(body, timestamp) }, body
  });
  const result = await response.json();
  if (!response.ok || result.code !== 200) throw new Error('Create failed: ' + JSON.stringify(result));
  // Save data.payment.amount/network/currency/address and the order ID; use the returned payable amount, which may include a matching offset.
  console.log(JSON.stringify(result, null, 2));
}
function handleVerifiedEvent(event) {
  if (!event || typeof event !== 'object' || Array.isArray(event)
      || typeof event.externalOrderId !== 'string' || typeof event.status !== 'string') return 400;
  // TODO for production: look up the order by authenticated merchant context + externalOrderId; match network, currency, receiving address and returned payable amount.
  // Compare amount/paidAmount with decimal arithmetic, not parseFloat; manually accepting an underpayment requires a separate business policy.
  // Process payment only for CONFIRMED / COMPLETED. CONFIRMED means chain confirmation; COMPLETED means webhook delivery.
  // eventId stays stable across retries (legacy events may omit it); use unique constraints and transactions/a durable queue to fulfill each order only once.
  // A browser returnUrl, client wallet report or valid signature alone does not prove fulfillment. Do not trust merchant identity supplied in metadata.
  // Return 2xx only after durable persistence, including already persisted duplicates; return 5xx on failure and send emails asynchronously outside the webhook handler.
  // No database handler is implemented here. Return 503 by default to avoid acknowledging an unrecorded payment; explicitly enable BOLT_DEMO_ACK only for local integration testing.
  console.log('Verified webhook received (no fulfillment performed)');
  return process.env.BOLT_DEMO_ACK === '1' ? 204 : 503;
}
function startWebhookServer() {
  requireSecret();
  const server = http.createServer((req, res) => {
    const reply = (status) => { if (!res.writableEnded) { res.writeHead(status); res.end(); } };
    if (req.method !== 'POST' || req.url !== '/webhooks/boltutil') { reply(404); req.resume(); return; }
    const chunks = []; let size = 0;
    req.on('data', chunk => {
      size += chunk.length;
      if (size > MAX_BODY) { reply(413); return; }
      chunks.push(chunk);
    });
    req.on('error', () => reply(400));
    req.on('end', () => {
      if (size > MAX_BODY) return;
      const body = Buffer.concat(chunks);
      if (!verifyWebhook(body, req.headers['x-bolt-webhook-timestamp'], req.headers['x-bolt-webhook-signature'])) {
        reply(401); return;
      }
      try { reply(handleVerifiedEvent(JSON.parse(body.toString('utf8')))); }
      catch { reply(400); } // Even signed but invalid JSON must not crash the server.
    });
  });
  server.requestTimeout = 15000; server.headersTimeout = 10000;
  const host = process.env.BOLT_LISTEN_HOST || '127.0.0.1';
  server.listen(Number(process.env.PORT || 3000), host, () => console.log('Webhook listener ready; use HTTPS reverse proxy'));
}
if (require.main === module) {
  const [command, network, currency, amount] = process.argv.slice(2);
  if (command === 'create') createOrder(network, currency, amount).catch(e => { console.error(e.message); process.exitCode = 1; });
  else if (command === 'webhook') startWebhookServer();
  else console.log('node boltutil-demo.js create POLYGON USDC 1.09 | webhook');
}
module.exports = { signBody, verifyWebhook, orderInput };
