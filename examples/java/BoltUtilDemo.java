import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.OutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BoltUtil merchant integration demo (Java 11+, Maven).
 * Uses POST /api/v1/order/create, not the WordPress /payments API.
 * Flow: create an order, save payment details, open checkoutUrl, verify the webhook,
 * then record the payment in a database transaction.
 * Gson handles JSON escaping and parsing; BigDecimal preserves monetary precision.
 * No database or fulfillment is implemented; see handleVerifiedEvent for production requirements.
 * Read secrets only from server-side environment variables.
 */
public class BoltUtilDemo {
    private static final int MAX_BODY = 65536; // Demo request-body limit, not a platform-wide limit.
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final String SECRET = env("BOLT_WEBHOOK_SECRET", "");
    private static String env(String key, String fallback) {
        String value = System.getenv(key); return value == null || value.isEmpty() ? fallback : value;
    }
    private static void requireSecret() {
        if (SECRET.isEmpty() || SECRET.matches("^(YOUR_|whsec_xxx).*")) throw new IllegalArgumentException("Set BOLT_WEBHOOK_SECRET");
    }
    // Sign the millisecond timestamp, a dot, and the raw UTF-8 body; send those same bytes without reserializing.
    public static String signBody(byte[] body, String timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : mac.doFinal(body)) hex.append(String.format("%02x", b));
        return hex.toString();
    }
    public static boolean verifyWebhook(byte[] body, String timestamp, String signature) throws Exception {
        if (timestamp == null || !timestamp.matches("[0-9]{13}") || signature == null || !signature.matches("[a-fA-F0-9]{64}")) return false;
        long ts = Long.parseLong(timestamp), now = System.currentTimeMillis();
        if (ts < now - 300000 || ts > now + 300000) return false;
        return MessageDigest.isEqual(signBody(body, timestamp).getBytes(StandardCharsets.US_ASCII),
                signature.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }
    private static void createOrder(String network, String currency, String amount) throws Exception {
        requireSecret(); String key = env("BOLT_API_KEY", "");
        if (key.isEmpty() || key.matches("^(YOUR_|bt_live_xxx).*")) throw new IllegalArgumentException("Set BOLT_API_KEY");
        network = network.toUpperCase(java.util.Locale.ROOT); currency = currency.toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("TRC20", "ERC20", "BEP20", "POLYGON", "SOLANA", "BASE").contains(network)
                || !Set.of("USDT", "USDC").contains(currency) || (network.equals("TRC20") && currency.equals("USDC"))) {
            throw new IllegalArgumentException("Unsupported network/currency pair");
        }
        // Minimum 1, with up to 18 integer digits and 6 decimal places; never convert through double.
        if (!amount.matches("[1-9][0-9]{0,17}(\\.[0-9]{1,6})?")) throw new IllegalArgumentException("Amount must be >= 1, max 6 decimals");
        String id = env("BOLT_EXTERNAL_ORDER_ID", "INV-" + UUID.randomUUID());
        if (!id.matches("[A-Za-z0-9_-]{6,64}")) throw new IllegalArgumentException("Invalid externalOrderId");
        String notify = env("BOLT_NOTIFY_URL", "");
        if (!notify.startsWith("https://")) throw new IllegalArgumentException("Set a public HTTPS BOLT_NOTIFY_URL");
        String[] parts = env("BOLT_RETURN_URL", "https://merchant.example.com/orders/return").split("#", 2);
        String returnUrl = parts[0] + (parts[0].contains("?") ? "&" : "?") + "order=" + URLEncoder.encode(id, StandardCharsets.UTF_8)
                + (parts.length > 1 ? "#" + parts[1] : "");
        byte[] body = JSON.toJson(Map.of("amount", new BigDecimal(amount), "externalOrderId", id, "network", network,
                "currency", currency, "notifyUrl", notify, "returnUrl", returnUrl, "expiredMinutes", 30,
                "orderDesc", "BoltUtil demo order", "metadata", Map.of("source", "java-demo"))).getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(System.currentTimeMillis());
        HttpRequest request = HttpRequest.newBuilder(URI.create(env("BOLT_API_BASE", "https://api.boltutil.com").replaceAll("/$", "") + "/api/v1/order/create"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .header("X-Bolt-Key", key).header("X-Bolt-Timestamp", timestamp).header("X-Bolt-Signature", signBody(body, timestamp))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        // Never forward credentials through redirects. After a timeout, query the same externalOrderId before retrying.
        System.out.println("externalOrderId: " + id);
        HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonObject result = JSON.fromJson(response.body(), JsonObject.class);
        if (response.statusCode() < 200 || response.statusCode() >= 300 || result == null || !result.has("code") || result.get("code").getAsInt() != 200) {
            throw new IOException("Create failed: HTTP " + response.statusCode() + ": " + response.body());
        }
        // Save data.payment.amount/network/currency/address; display the returned amount, which may include a matching offset.
        System.out.println(response.body());
    }
    private static int handleVerifiedEvent(JsonObject event) {
        if (event == null || !event.has("externalOrderId") || !event.get("externalOrderId").isJsonPrimitive()
                || !event.getAsJsonPrimitive("externalOrderId").isString() || !event.has("status")
                || !event.get("status").isJsonPrimitive() || !event.getAsJsonPrimitive("status").isString()) return 400;
        // TODO: Find the local order by the authenticated merchant and externalOrderId; match currency,
        // network, receiving address, and the exact payable amount returned when the order was created.
        // Compare amount/paidAmount with BigDecimal; accepting an underpayment requires explicit business authorization.
        // Only handle CONFIRMED/COMPLETED payments: chain confirmation and webhook delivery are not separate payments.
        // eventId stays stable across retries (legacy events may omit it). Use unique constraints with a transaction
        // or durable queue to ensure each order is fulfilled only once.
        // returnUrl and metadata are not payment proof. Acknowledge only after persistence; return 5xx on failure
        // and handle email asynchronously.
        // No database is implemented here. Default to 503 to avoid losing notifications; BOLT_DEMO_ACK=1 is for local testing only.
        System.out.println("Verified webhook received (no fulfillment performed)");
        return env("BOLT_DEMO_ACK", "").equals("1") ? 204 : 503;
    }
    private static void handleWebhook(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST") || !exchange.getRequestURI().toString().equals("/webhooks/boltutil")) { reply(exchange, 404); return; }
        // Read at most the limit plus one byte, not unbounded readAllBytes. Production also needs proxy connection limits and read timeouts.
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) { reply(exchange, 413); return; }
        try {
            if (!verifyWebhook(body, exchange.getRequestHeaders().getFirst("X-Bolt-Webhook-Timestamp"),
                    exchange.getRequestHeaders().getFirst("X-Bolt-Webhook-Signature"))) { reply(exchange, 401); return; }
            // Parse only after signature verification. Strict JSON rejects malformed or concatenated documents; do not log personal data in full.
            reply(exchange, handleVerifiedEvent(JSON.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class)));
        } catch (com.google.gson.JsonParseException | IllegalStateException e) { reply(exchange, 400); }
        catch (Exception e) { reply(exchange, 503); }
    }
    private static void reply(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1); exchange.close();
    }
    public static void main(String[] args) throws Exception {
        String command = args.length > 0 ? args[0] : "";
        if (command.equals("create")) createOrder(args.length > 1 ? args[1] : "TRC20", args.length > 2 ? args[2] : "USDT", args.length > 3 ? args[3] : "1.00");
        else if (command.equals("webhook")) {
            requireSecret();
            HttpServer server = HttpServer.create(new InetSocketAddress(env("BOLT_LISTEN_HOST", "127.0.0.1"), Integer.parseInt(env("PORT", "3000"))), 16);
            server.createContext("/webhooks/boltutil", BoltUtilDemo::handleWebhook); server.start();
            System.out.println("Webhook listener ready; use HTTPS reverse proxy");
        } else System.out.println("java -cp 'target/classes:target/dependency/*' BoltUtilDemo create POLYGON USDC 1.09 | webhook");
    }
}
