<?php
/**
 * BoltUtil merchant integration demo (64-bit PHP 8.2+, cURL).
 * Uses POST /api/v1/order/create, not the WordPress /payments API.
 * Flow: create an order, save payment details, open checkoutUrl, verify the webhook,
 * then record the payment in a database transaction.
 * No database or fulfillment is implemented; see handle_verified_event for production requirements.
 * Keep secrets only in server-side environment variables.
 */
const MAX_BODY = 65536; // Demo request-body limit, not a platform-wide API limit.
function secret(): string { return getenv('BOLT_WEBHOOK_SECRET') ?: ''; }
function require_secret(): void {
    if (secret() === '' || preg_match('/^(YOUR_|whsec_xxx)/', secret())) {
        throw new RuntimeException('Set BOLT_WEBHOOK_SECRET');
    }
}
function sign_body(string $body, string $timestamp): string {
    // Sign the millisecond timestamp, a dot, and the raw UTF-8 body. Do not decode and re-encode before verification.
    return hash_hmac('sha256', $timestamp . '.' . $body, secret());
}
function verify_webhook(string $body, string $timestamp, string $signature): bool {
    if (!preg_match('/^[0-9]{13}$/D', $timestamp) || !preg_match('/^[a-fA-F0-9]{64}$/D', $signature)) return false;
    if (abs((int) floor(microtime(true) * 1000) - (int) $timestamp) > 300000) return false;
    return hash_equals(sign_body($body, $timestamp), strtolower($signature));
}
function create_order(string $network = 'TRC20', string $currency = 'USDT', string $amount = '1.00'): void {
    require_secret();
    $key = getenv('BOLT_API_KEY') ?: '';
    if ($key === '' || preg_match('/^(YOUR_|bt_live_xxx)/', $key)) throw new RuntimeException('Set BOLT_API_KEY');
    $network = strtoupper($network); $currency = strtoupper($currency);
    if (!in_array($network, ['TRC20', 'ERC20', 'BEP20', 'POLYGON', 'SOLANA', 'BASE'], true)
        || !in_array($currency, ['USDT', 'USDC'], true) || ($network === 'TRC20' && $currency === 'USDC')) {
        throw new RuntimeException('Unsupported network/currency pair');
    }
    // Preserve decimal text without converting to float: minimum 1, up to 18 integer digits and 6 decimal places.
    if (!preg_match('/^[1-9][0-9]{0,17}(\.[0-9]{1,6})?$/D', $amount)) throw new RuntimeException('Amount must be >= 1, max 6 decimals');
    $id = getenv('BOLT_EXTERNAL_ORDER_ID') ?: 'INV-' . bin2hex(random_bytes(16));
    if (!preg_match('/^[A-Za-z0-9_-]{6,64}$/D', $id)) throw new RuntimeException('Invalid externalOrderId');
    $notify = getenv('BOLT_NOTIFY_URL') ?: '';
    if (strpos($notify, 'https://') !== 0) throw new RuntimeException('Set a public HTTPS BOLT_NOTIFY_URL');
    $returnUrl = getenv('BOLT_RETURN_URL') ?: 'https://merchant.example.com/orders/return';
    // Add the parameter before the fragment, preserving existing query parameters; applications may instead use a fixed returnUrl and query their orders.
    $parts = explode('#', $returnUrl, 2);
    $returnUrl = $parts[0] . (strpos($parts[0], '?') === false ? '?' : '&') . 'order=' . rawurlencode($id)
        . (isset($parts[1]) ? '#' . $parts[1] : '');
    $payload = ['amount' => $amount, 'externalOrderId' => $id, 'network' => $network, 'currency' => $currency,
        'notifyUrl' => $notify, 'returnUrl' => $returnUrl, 'expiredMinutes' => 30,
        'orderDesc' => 'BoltUtil demo order', 'metadata' => ['source' => 'php-demo']];
    $body = json_encode($payload, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_THROW_ON_ERROR);
    // Replace only the strictly validated amount with a JSON number; the JSON library safely escapes all other strings.
    $body = str_replace('"amount":"' . $amount . '"', '"amount":' . $amount, $body);
    $timestamp = (string) (int) floor(microtime(true) * 1000);
    $ch = curl_init(rtrim(getenv('BOLT_API_BASE') ?: 'https://api.boltutil.com', '/') . '/api/v1/order/create');
    curl_setopt_array($ch, [CURLOPT_POST => true, CURLOPT_RETURNTRANSFER => true,
        CURLOPT_CONNECTTIMEOUT => 10, CURLOPT_TIMEOUT => 20, CURLOPT_FOLLOWLOCATION => false,
        CURLOPT_HTTPHEADER => ['Content-Type: application/json', 'X-Bolt-Key: ' . $key,
            'X-Bolt-Timestamp: ' . $timestamp, 'X-Bolt-Signature: ' . sign_body($body, $timestamp)], CURLOPT_POSTFIELDS => $body]);
    echo 'externalOrderId: ' . $id . PHP_EOL; // After a timeout, query this same order ID before creating another order.
    $response = curl_exec($ch); $status = curl_getinfo($ch, CURLINFO_HTTP_CODE); $error = curl_error($ch); curl_close($ch);
    if ($response === false) throw new RuntimeException($error);
    $result = json_decode($response, true, 512, JSON_THROW_ON_ERROR);
    if ($status < 200 || $status >= 300 || ($result['code'] ?? 0) !== 200) throw new RuntimeException('Create failed: ' . $response);
    // Save data.payment.amount/network/currency/address; display the returned amount, which may include a matching offset.
    echo $response . PHP_EOL;
}
function handle_verified_event($event): int {
    if (!is_object($event) || !is_string($event->externalOrderId ?? null) || !is_string($event->status ?? null)) return 400;
    // TODO: Find the local order by the authenticated merchant and externalOrderId; match network,
    // currency, receiving address, and the payable amount returned when the order was created.
    // Use BCMath or a decimal library for amounts, never float; accepting an underpayment requires explicit business authorization.
    // Only handle CONFIRMED/COMPLETED payments: chain confirmation and webhook delivery are not separate payments.
    // Use eventId (legacy events may omit it) with database unique constraints and a transaction to fulfill each order only once.
    // Browser returnUrl and metadata are not payment proof. Acknowledge only after persistence; return 5xx on failure and send email asynchronously.
    // Default to 503 to avoid acknowledging unrecorded payments. BOLT_DEMO_ACK=1 is for local testing only; no production fulfillment is implemented.
    error_log('Verified webhook received (no fulfillment performed)');
    return getenv('BOLT_DEMO_ACK') === '1' ? 204 : 503;
}
function handle_webhook_request(): void {
    require_secret();
    // Reject an oversized declared body early; the bounded read below also applies when Content-Length is absent.
    $declared = $_SERVER['CONTENT_LENGTH'] ?? '';
    if (ctype_digit((string) $declared) && (strlen((string) $declared) > 9 || (int) $declared > MAX_BODY)) {
        http_response_code(413); return;
    }
    // Read at most the limit plus one byte instead of relying only on the client's Content-Length.
    $body = file_get_contents('php://input', false, null, 0, MAX_BODY + 1);
    if ($body === false) { http_response_code(400); return; }
    if (strlen($body) > MAX_BODY) { http_response_code(413); return; }
    if (!verify_webhook($body, $_SERVER['HTTP_X_BOLT_WEBHOOK_TIMESTAMP'] ?? '', $_SERVER['HTTP_X_BOLT_WEBHOOK_SIGNATURE'] ?? '')) {
        http_response_code(401); return;
    }
    try { $event = json_decode($body, false, 512, JSON_THROW_ON_ERROR); }
    catch (JsonException $e) { http_response_code(400); return; }
    http_response_code(handle_verified_event($event));
}
// Let unit tests load functions without starting CLI or HTTP handling.
if (!defined('BOLT_DEMO_LIBRARY')) {
    try {
        if (PHP_SAPI !== 'cli') {
            if (($_SERVER['REQUEST_METHOD'] ?? '') === 'POST' && ($_SERVER['REQUEST_URI'] ?? '') === '/webhooks/boltutil') handle_webhook_request();
            else http_response_code(404);
        } elseif (($argv[1] ?? '') === 'create') {
            create_order($argv[2] ?? 'TRC20', $argv[3] ?? 'USDT', $argv[4] ?? '1.00');
        } else {
            echo "php boltutil_demo.php create POLYGON USDC 1.09\nphp -S 127.0.0.1:3000 boltutil_demo.php\n";
        }
    } catch (Throwable $e) {
        error_log($e->getMessage());
        if (PHP_SAPI === 'cli') exit(1);
        http_response_code(503);
    }
}
