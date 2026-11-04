"""BoltUtil merchant integration demo (Python 3.10+).
Uses only POST /api/v1/order/create, not the WordPress /payments API.
Flow: create an order server-side -> save payment details -> open checkoutUrl -> verify the webhook -> record payment in a database transaction.
This demo does not connect to a database or fulfill orders; implement the steps in handle_verified_event before production use.
Keep secrets only in server-side environment variables, never in browser code or Git.
"""
import hashlib
import hmac
import json
import os
import re
import sys
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import Request, build_opener, HTTPRedirectHandler
from urllib.parse import urlsplit, urlunsplit, parse_qsl, urlencode

SECRET = os.getenv('BOLT_WEBHOOK_SECRET', '')
MAX_BODY = 64 * 1024  # Demo receiver limit; adjust for your application. This is not a platform-wide limit.


def require_secret():
    if not SECRET or SECRET.startswith(('YOUR_', 'whsec_xxx')):
        raise ValueError('Set BOLT_WEBHOOK_SECRET')


def sign_body(body: bytes, timestamp: str) -> str:
    # Sign the millisecond timestamp + a dot + the raw UTF-8 bytes. Send that exact body after signing.
    return hmac.new(SECRET.encode(), timestamp.encode() + b'.' + body, hashlib.sha256).hexdigest()


def verify_webhook(body: bytes, timestamp: str, signature: str) -> bool:
    if not re.fullmatch(r'[0-9]{13}', timestamp) or not re.fullmatch(r'[a-fA-F0-9]{64}', signature):
        return False
    if abs(int(time.time() * 1000) - int(timestamp)) > 300000:
        return False
    return hmac.compare_digest(sign_body(body, timestamp), signature.lower())


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Do not forward signatures or credentials to redirect targets.


def create_order(network='TRC20', currency='USDT', amount='1.00'):
    require_secret()
    key = os.getenv('BOLT_API_KEY', '')
    if not key or key.startswith(('YOUR_', 'bt_live_xxx')):
        raise ValueError('Set BOLT_API_KEY')
    network, currency = network.upper(), currency.upper()
    if network not in ('TRC20', 'ERC20', 'BEP20', 'POLYGON', 'SOLANA', 'BASE') or currency not in ('USDT', 'USDC') or (network == 'TRC20' and currency == 'USDC'):
        raise ValueError('Unsupported network/currency pair')
    # Keep amounts as decimal text, not float; minimum 1, up to 18 integer digits and 6 decimal places.
    if not re.fullmatch(r'[1-9][0-9]{0,17}(\.[0-9]{1,6})?', amount):
        raise ValueError('Amount must be >= 1, max 6 decimals')
    external_id = os.getenv('BOLT_EXTERNAL_ORDER_ID') or 'INV-' + str(uuid.uuid4())
    if not re.fullmatch(r'[A-Za-z0-9_-]{6,64}', external_id):
        raise ValueError('Invalid externalOrderId')
    notify = os.getenv('BOLT_NOTIFY_URL', '')
    if not notify.startswith('https://'):
        raise ValueError('Set a public HTTPS BOLT_NOTIFY_URL')
    url = urlsplit(os.getenv('BOLT_RETURN_URL', 'https://merchant.example.com/orders/return'))
    query = dict(parse_qsl(url.query, keep_blank_values=True))
    query['order'] = external_id  # Preserve existing URL query parameters and the fragment.
    payload = dict(externalOrderId=external_id, network=network, currency=currency,
                   notifyUrl=notify, returnUrl=urlunsplit(url._replace(query=urlencode(query))),
                   expiredMinutes=30, orderDesc='BoltUtil demo order', metadata={'source': 'python-demo'})
    # After strict regex validation, insert amount as a JSON number; JSON-escape all other string values.
    # This avoids precision loss through float and preserves the documented amount:number format.
    body = ('{"amount":' + amount + ',' + json.dumps(payload, ensure_ascii=False, separators=(',', ':'))[1:]).encode()
    timestamp = str(int(time.time() * 1000))
    request = Request(os.getenv('BOLT_API_BASE', 'https://api.boltutil.com').rstrip('/') + '/api/v1/order/create',
                      data=body, method='POST', headers={'Content-Type': 'application/json',
                      'X-Bolt-Key': key, 'X-Bolt-Timestamp': timestamp, 'X-Bolt-Signature': sign_body(body, timestamp)})
    print('externalOrderId:', external_id, flush=True)  # After a timeout, query this same order ID rather than blindly creating another order.
    with build_opener(NoRedirect()).open(request, timeout=20) as response:
        result = json.load(response)
    if result.get('code') != 200:
        raise ValueError('Create failed: ' + json.dumps(result))
    # Save amount/network/currency/address from data.payment; the payable amount may include a matching offset.
    print(json.dumps(result, ensure_ascii=False, indent=2))


def handle_verified_event(event):
    if not isinstance(event, dict) or not isinstance(event.get('externalOrderId'), str) or not isinstance(event.get('status'), str):
        return 400
    # TODO for production: look up the local order by authenticated merchant context + externalOrderId; match currency, network, address and exact returned amount.
    # Compare amount/paidAmount with decimal.Decimal; a manually accepted underpayment must not automatically count as full payment.
    # Process only CONFIRMED/COMPLETED: chain confirmation and webhook delivery respectively, not two separate payments.
    # eventId stays stable across retries (legacy events may omit it). Use database unique constraints and transactions/a durable queue to fulfill each order only once.
    # Do not treat returnUrl or an identity in metadata as payment proof; do not send emails synchronously in the webhook handler.
    # Return 2xx after durable persistence or for already persisted duplicates, and 5xx on failure; default to 503 here to avoid losing notifications.
    print('Verified webhook received (no fulfillment performed)', flush=True)
    return 204 if os.getenv('BOLT_DEMO_ACK') == '1' else 503  # Enable explicitly for local integration testing only.


class BoltUtilWebhookHandler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        self.connection.settimeout(15)  # Prevent slow requests from holding a thread indefinitely.

    def reply(self, status):
        self.send_response(status)
        self.send_header('Content-Length', '0')
        self.end_headers()

    def do_POST(self):
        if self.path != '/webhooks/boltutil':
            self.reply(404)
            return
        # This standard-library demo does not support chunked bodies; normalize them to Content-Length at the reverse proxy.
        length = self.headers.get('Content-Length', '')
        if self.headers.get('Transfer-Encoding') or not re.fullmatch(r'[0-9]{1,9}', length):
            self.reply(400)
            return
        if int(length) > MAX_BODY:
            self.reply(413)
            return
        try:
            body = self.rfile.read(int(length))
            if len(body) != int(length):
                self.reply(400)
                return
            if not verify_webhook(body, self.headers.get('X-Bolt-Webhook-Timestamp', ''), self.headers.get('X-Bolt-Webhook-Signature', '')):
                self.reply(401)
                return
            self.reply(handle_verified_event(json.loads(body.decode('utf-8'))))
        except (ValueError, UnicodeError, OSError):
            self.reply(400)


def start_webhook_server():
    require_secret()
    server = ThreadingHTTPServer((os.getenv('BOLT_LISTEN_HOST', '127.0.0.1'), int(os.getenv('PORT', '3000'))), BoltUtilWebhookHandler)
    print('Webhook listener ready; use HTTPS reverse proxy', flush=True)
    server.serve_forever()


if __name__ == '__main__':
    try:
        command = sys.argv[1] if len(sys.argv) > 1 else ''
        if command == 'create':
            create_order(*sys.argv[2:5])
        elif command == 'webhook':
            start_webhook_server()
        else:
            print('python3 boltutil_demo.py create POLYGON USDC 1.09 | webhook')
    except Exception as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
