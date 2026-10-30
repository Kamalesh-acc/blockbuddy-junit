package bb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Small HTTP helper for the BlockBuddy API tests.
 *
 * Works with no setup: by default it talks to the TEST copy of the app,
 * https://blockbuddy-test.vercel.app, which uses its own separate test database.
 * (Optional: set BB_BASE_URL to point at another test copy.)
 *
 * How sign-in works: the app reads the Supabase session from a cookie called
 * sb-[project-ref]-auth-token (value "base64-" + base64url(session JSON), split into
 * .0, .1, ... parts when longer than 3180 chars). It does NOT read an Authorization header.
 * The test copy has a helper, /api/test-support/user, that creates a confirmed test
 * account and returns its session. We turn that session into the cookie.
 */
public final class Api {

    /** The live project and site. Tests refuse to run against them. */
    private static final String LIVE_PROJECT_REF = "bbcycaysbhxgnoffyfyp";
    private static final String LIVE_SITE = "blockbuddy-app.vercel.app";
    private static final int MAX_CHUNK = 3180;

    public static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();

    public static final String BASE_URL = baseUrl();

    static {
        if (BASE_URL.contains(LIVE_SITE)) {
            throw new IllegalStateException("Refusing to run against the live app. Use the test copy.");
        }
    }

    private Api() {}

    private static String baseUrl() {
        String v = System.getenv("BB_BASE_URL");
        if (v == null || v.isBlank()) v = "https://blockbuddy-test.vercel.app";
        return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }

    // ---------- response ----------

    public record Res(int status, String body) {
        public JsonNode json() {
            try {
                return body == null || body.isBlank() ? JSON.nullNode() : JSON.readTree(body);
            } catch (Exception e) {
                throw new AssertionError("Not JSON (HTTP " + status + "): " + body, e);
            }
        }

        /** The "error" field of a failed call, e.g. "handle_taken". */
        public String error() {
            JsonNode e = json().get("error");
            return e == null ? null : e.asText();
        }

        @Override
        public String toString() {
            return "HTTP " + status + " " + body;
        }
    }

    // ---------- test user ----------

    /** A signed-in test account. userId is the app's users.id, filled after claim-handle. */
    public static final class TestUser {
        public final String email;
        public final String cookie;
        public String userId;
        public String handle;

        TestUser(String email, String cookie) {
            this.email = email;
            this.cookie = cookie;
        }
    }

    /** Short random lowercase text, safe inside a handle. */
    public static String rand(int n) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, n);
    }

    /** A handle that is valid and almost surely unused: "jt" + 10 hex chars = 12 chars. */
    public static String newHandle() {
        return "jt" + rand(10);
    }

    /** A test email. Must start with "bbtest." (the test helper only accepts these). */
    public static String testEmail(String domain) {
        return "bbtest." + rand(10) + "@" + domain;
    }

    /** Creates a confirmed test account on the test copy and signs it in. No email is sent. */
    public static TestUser createUser(String email) throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(BASE_URL + "/api/test-support/user"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("email", email))))
                .build());
        if (r.statusCode() != 200) {
            throw new IllegalStateException("Could not create test user: " + r.statusCode() + " " + r.body());
        }
        JsonNode j = JSON.readTree(r.body());
        String supabaseUrl = j.get("supabaseUrl").asText();
        if (supabaseUrl.contains(LIVE_PROJECT_REF)) {
            throw new IllegalStateException("Refusing to run: this server uses the live database.");
        }
        return new TestUser(email, sessionCookie(supabaseUrl, JSON.writeValueAsString(j.get("session"))));
    }

    /** Creates a user and claims a handle for it, so it can use every API. */
    public static TestUser createReadyUser(String emailDomain) throws Exception {
        TestUser u = createUser(testEmail(emailDomain));
        String handle = newHandle();
        Res r = claimHandle(u, handle, "JUnit " + handle);
        if (r.status() != 200) throw new IllegalStateException("claim-handle failed: " + r);
        u.userId = r.json().get("user").get("id").asText();
        u.handle = handle;
        return u;
    }

    /** Builds the exact cookie header @supabase/ssr 0.12 reads (base64url, chunked). */
    static String sessionCookie(String supabaseUrl, String sessionJson) {
        String ref = URI.create(supabaseUrl).getHost().split("\\.")[0];
        String name = "sb-" + ref + "-auth-token";
        String value = "base64-" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sessionJson.getBytes(StandardCharsets.UTF_8));
        if (value.length() <= MAX_CHUNK) return name + "=" + value;
        List<String> parts = new ArrayList<>();
        for (int i = 0, n = 0; i < value.length(); i += MAX_CHUNK, n++) {
            parts.add(name + "." + n + "=" + value.substring(i, Math.min(value.length(), i + MAX_CHUNK)));
        }
        return String.join("; ", parts);
    }

    /** Deletes everything the user made (the app's own account delete), then the auth account. */
    public static void deleteUser(TestUser u) {
        if (u == null) return;
        try {
            call("POST", "/api/profile/delete", null, u); // cascades requests, friendships, etc.
        } catch (Exception ignored) {
            // fall through
        }
        try {
            // Finishes the job when profile/delete fails or the account never claimed a handle.
            call("DELETE", "/api/test-support/user", Map.of("email", u.email), null);
        } catch (Exception ignored) {
            // already gone
        }
    }

    // ---------- app API ----------

    /** Any app API call. body may be null. user may be null (signed out). */
    public static Res call(String method, String path, Object body, TestUser user) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json");
        if (user != null) b.header("Cookie", user.cookie);
        HttpRequest.BodyPublisher pub = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
        HttpResponse<String> r = send(b.method(method, pub).build());
        return new Res(r.statusCode(), r.body());
    }

    /** Sends a request. If the connection drops (weak Wi-Fi), waits and tries again, up to 4 times. */
    static HttpResponse<String> send(HttpRequest req) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (java.io.IOException e) {
                if (attempt == 4) throw e;
                Thread.sleep(1500L * attempt);
            }
        }
    }

    public static Res claimHandle(TestUser u, String handle, String name) throws Exception {
        return call("POST", "/api/profile/claim-handle", Map.of("handle", handle, "name", name), u);
    }

    public static Res profile(TestUser u) throws Exception {
        return call("GET", "/api/profile", null, u);
    }

    /** Makes a and b buddies (a sends, b accepts). Needed: only the audience can accept. */
    public static void makeBuddies(TestUser a, TestUser b) throws Exception {
        Res sent = call("POST", "/api/friends/request", Map.of("handle", b.handle), a);
        if (sent.status() != 200) throw new IllegalStateException("friend request failed: " + sent);
        Res ok = call("POST", "/api/friends/respond", Map.of("requesterId", a.userId, "accept", true), b);
        if (ok.status() != 200) throw new IllegalStateException("friend accept failed: " + ok);
    }

    /** Posts an item request visible to the given buddies. Returns the request id. */
    public static String postRequest(TestUser requester, List<String> audienceFriendIds) throws Exception {
        Res r = call("POST", "/api/requests", Map.of(
                "kind", "item",
                "what", "JUnit test item " + rand(4),
                "audienceGroupIds", List.of(),
                "audienceFriendIds", audienceFriendIds,
                "taggedFriendIds", List.of()), requester);
        if (r.status() != 200) throw new IllegalStateException("post request failed: " + r);
        return r.json().get("requestId").asText();
    }

    public static Res accept(TestUser u, String requestId) throws Exception {
        return call("POST", "/api/requests/" + requestId + "/accept", null, u);
    }

    /** "Got it": only the REQUESTER can call this. Moves accepted to done and sends thanks. */
    public static Res delivered(TestUser u, String requestId) throws Exception {
        return call("POST", "/api/requests/" + requestId + "/delivered", Map.of("emoji", "🙏"), u);
    }

    /** "Handed over" ping: only the BUDDY who accepted can call this. Status stays accepted. */
    public static Res handedOver(TestUser u, String requestId) throws Exception {
        return call("POST", "/api/requests/" + requestId + "/handed-over", null, u);
    }

    public static Res detail(TestUser u, String requestId) throws Exception {
        return call("GET", "/api/requests/" + requestId, null, u);
    }
}
