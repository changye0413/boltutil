using System.Globalization;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

/// <summary>
/// BoltUtil merchant integration demo (.NET 8).
/// Uses POST /api/v1/order/create, not the WordPress /payments API.
/// Flow: create an order, save payment details, open checkoutUrl, verify the webhook,
/// then record the payment in a database transaction.
/// No database or fulfillment is implemented; see HandleVerifiedEvent for production requirements.
/// Keep secrets only in server-side environment variables.
/// </summary>
public static class BoltUtilDemo
{
    private const int MaxBody = 65536; // Demo request-body limit, not a platform-wide limit.
    private static readonly string Secret = Env("BOLT_WEBHOOK_SECRET", "");
    private static string Env(string key, string fallback) => Environment.GetEnvironmentVariable(key) is { Length: > 0 } v ? v : fallback;
    private static void RequireSecret()
    {
        if (Secret.Length == 0 || Regex.IsMatch(Secret, "^(YOUR_|whsec_xxx)")) throw new Exception("Set BOLT_WEBHOOK_SECRET");
    }
    // Sign the millisecond timestamp, a dot, and the raw UTF-8 body; send those same bytes without reserializing.
    public static string SignBody(byte[] body, string timestamp)
    {
        using var hmac = new HMACSHA256(Encoding.UTF8.GetBytes(Secret));
        var prefix = Encoding.UTF8.GetBytes(timestamp + ".");
        return Convert.ToHexString(hmac.ComputeHash(prefix.Concat(body).ToArray())).ToLowerInvariant();
    }
    public static bool VerifyWebhook(byte[] body, string timestamp, string signature)
    {
        if (!Regex.IsMatch(timestamp, "\\A[0-9]{13}\\z") || !Regex.IsMatch(signature, "\\A[a-fA-F0-9]{64}\\z")) return false;
        var ts = long.Parse(timestamp, CultureInfo.InvariantCulture);
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        if (ts < now - 300000 || ts > now + 300000) return false;
        return CryptographicOperations.FixedTimeEquals(Convert.FromHexString(SignBody(body, timestamp)), Convert.FromHexString(signature));
    }
    private static async Task CreateOrder(string network, string currency, string amount)
    {
        RequireSecret();
        var key = Env("BOLT_API_KEY", "");
        if (key.Length == 0 || Regex.IsMatch(key, "^(YOUR_|bt_live_xxx)")) throw new Exception("Set BOLT_API_KEY");
        network = network.ToUpperInvariant(); currency = currency.ToUpperInvariant();
        if (!new[] { "TRC20", "ERC20", "BEP20", "POLYGON", "SOLANA", "BASE" }.Contains(network)
            || !new[] { "USDT", "USDC" }.Contains(currency) || (network == "TRC20" && currency == "USDC")) throw new Exception("Unsupported network/currency pair");
        // Use decimal, not double: minimum 1, up to 18 integer digits and 6 decimal places, serialized as an exact JSON number.
        if (!Regex.IsMatch(amount, "\\A[1-9][0-9]{0,17}(\\.[0-9]{1,6})?\\z")) throw new Exception("Amount must be >= 1, max 6 decimals");
        var id = Env("BOLT_EXTERNAL_ORDER_ID", "INV-" + Guid.NewGuid());
        if (!Regex.IsMatch(id, "\\A[A-Za-z0-9_-]{6,64}\\z")) throw new Exception("Invalid externalOrderId");
        var notify = Env("BOLT_NOTIFY_URL", "");
        if (!notify.StartsWith("https://", StringComparison.Ordinal)) throw new Exception("Set a public HTTPS BOLT_NOTIFY_URL");
        var parts = Env("BOLT_RETURN_URL", "https://merchant.example.com/orders/return").Split('#', 2);
        var returnUrl = parts[0] + (parts[0].Contains('?') ? "&" : "?") + "order=" + Uri.EscapeDataString(id) + (parts.Length > 1 ? "#" + parts[1] : "");
        var body = JsonSerializer.SerializeToUtf8Bytes(new { amount = decimal.Parse(amount, CultureInfo.InvariantCulture), externalOrderId = id,
            network, currency, notifyUrl = notify, returnUrl, expiredMinutes = 30, orderDesc = "BoltUtil demo order", metadata = new { source = "csharp-demo" } });
        var timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds().ToString(CultureInfo.InvariantCulture);
        using var handler = new HttpClientHandler { AllowAutoRedirect = false, UseCookies = false }; // Never forward credentials to redirect targets.
        using var client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(20) };
        using var request = new HttpRequestMessage(HttpMethod.Post, Env("BOLT_API_BASE", "https://api.boltutil.com").TrimEnd('/') + "/api/v1/order/create");
        request.Content = new ByteArrayContent(body); request.Content.Headers.ContentType = new("application/json");
        request.Headers.Add("X-Bolt-Key", key); request.Headers.Add("X-Bolt-Timestamp", timestamp); request.Headers.Add("X-Bolt-Signature", SignBody(body, timestamp));
        Console.WriteLine("externalOrderId: " + id); // After a timeout, query this same order ID before creating another order.
        using var response = await client.SendAsync(request);
        var text = await response.Content.ReadAsStringAsync();
        using var result = JsonDocument.Parse(text);
        if (!response.IsSuccessStatusCode || !result.RootElement.TryGetProperty("code", out var code) || code.GetInt32() != 200) throw new Exception("Create failed: " + text);
        // Save data.payment.amount/network/currency/address; display the returned amount, which may include a matching offset.
        Console.WriteLine(text);
    }
    private static int HandleVerifiedEvent(JsonElement item)
    {
        if (item.ValueKind != JsonValueKind.Object || !item.TryGetProperty("externalOrderId", out var id) || id.ValueKind != JsonValueKind.String
            || !item.TryGetProperty("status", out var status) || status.ValueKind != JsonValueKind.String) return 400;
        // TODO: Find the local order by the authenticated merchant and externalOrderId; match network,
        // currency, receiving address, and the returned payable amount using decimal arithmetic.
        // Accepting an underpayment requires explicit business authorization. Only handle CONFIRMED/COMPLETED:
        // chain confirmation and webhook delivery are not separate payments.
        // eventId stays stable across retries (legacy events may omit it). Use database unique constraints
        // with a transaction or durable queue to fulfill each order only once.
        // returnUrl and metadata are not payment proof. Return 2xx only after persistence, 5xx on failure; send email asynchronously.
        // No database is implemented here. Default to 503; BOLT_DEMO_ACK=1 is for local testing, not production fulfillment.
        Console.WriteLine("Verified webhook received (no fulfillment performed)");
        return Env("BOLT_DEMO_ACK", "") == "1" ? 204 : 503;
    }
    private static async Task HandleWebhook(HttpListenerContext ctx)
    {
        int status;
        try
        {
            if (ctx.Request.HttpMethod != "POST" || ctx.Request.RawUrl != "/webhooks/boltutil") { Reply(ctx, 404); return; }
            using var body = new MemoryStream(); var buffer = new byte[4096];
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(15));
            int count;
            while ((count = await ctx.Request.InputStream.ReadAsync(buffer.AsMemory(), timeout.Token)) > 0)
            {
                if (body.Length + count > MaxBody) { Reply(ctx, 413); return; }
                body.Write(buffer, 0, count);
            }
            var bytes = body.ToArray();
            if (!VerifyWebhook(bytes, ctx.Request.Headers["X-Bolt-Webhook-Timestamp"] ?? "", ctx.Request.Headers["X-Bolt-Webhook-Signature"] ?? "")) { Reply(ctx, 401); return; }
            using var item = JsonDocument.Parse(bytes); // Parse only after signature verification; do not log personal data in full.
            status = HandleVerifiedEvent(item.RootElement);
        }
        catch (JsonException) { status = 400; }
        catch (Exception) { status = 503; }
        Reply(ctx, status);
    }
    private static void Reply(HttpListenerContext ctx, int status)
    {
        ctx.Response.StatusCode = status; ctx.Response.ContentLength64 = 0; ctx.Response.Close();
    }
    public static async Task Main(string[] args)
    {
        try
        {
            if (args.FirstOrDefault() == "create") await CreateOrder(args.ElementAtOrDefault(1) ?? "TRC20", args.ElementAtOrDefault(2) ?? "USDT", args.ElementAtOrDefault(3) ?? "1.00");
            else if (args.FirstOrDefault() == "webhook")
            {
                RequireSecret(); using var listener = new HttpListener();
                // Listen on the root prefix and match the exact webhook path, avoiding routing failures from extra slashes in the prefix.
                listener.Prefixes.Add($"http://{Env("BOLT_LISTEN_HOST", "127.0.0.1")}:{Env("PORT", "3000")}/"); listener.Start();
                Console.WriteLine("Webhook listener ready; use HTTPS reverse proxy");
                while (true) { var ctx = await listener.GetContextAsync(); _ = HandleWebhook(ctx); }
            }
            else Console.WriteLine("dotnet run -- create POLYGON USDC 1.09 | webhook");
        }
        catch (Exception e) { Console.Error.WriteLine(e.Message); Environment.ExitCode = 1; }
    }
}
