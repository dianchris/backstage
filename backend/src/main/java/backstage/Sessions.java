package backstage;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
class Sessions {
    private record Account(String role, String name) {}
    private record Session(Account account, Instant expires) {}
    private static final Map<String, Account> ACCOUNTS = Map.of(
        "maker", new Account("maker", "Backstage Maker"),
        "checker", new Account("checker", "Backstage Checker"),
        "product-manager", new Account("product-manager", "Product Manager")
    );
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    String role(String token) { return session(token).account().role(); }

    private Session session(String token) {
        var session = sessions.get(token);
        if (session == null || !session.expires().isAfter(Instant.now())) {
            sessions.remove(token);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Silakan masuk kembali.");
        }
        return session;
    }

    private static ResponseCookie cookie(String token, long age) {
        return ResponseCookie.from("backstage_session", token).httpOnly(true)
            .sameSite("Strict").path("/").maxAge(age).build();
    }

    @PostMapping("/api/auth/login")
    ResponseEntity<?> login(@RequestBody ObjectNode input,
            @CookieValue(value="backstage_session", defaultValue="") String previous) {
        String username = input.path("username").asText();
        var account = ACCOUNTS.get(username);
        if (account == null || !MessageDigest.isEqual(
                input.path("password").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "Backstage123!".getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).body(Map.of("error", "Username atau password salah."));
        }
        sessions.entrySet().removeIf(entry -> !entry.getValue().expires().isAfter(Instant.now()));
        sessions.remove(previous);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, new Session(account, Instant.now().plusSeconds(28800)));
        return ResponseEntity.ok().header("Set-Cookie", cookie(token, 28800).toString()).body(account);
    }

    @GetMapping("/api/auth/me")
    ResponseEntity<?> me(@CookieValue(value="backstage_session", defaultValue="") String token) {
        try { return ResponseEntity.ok(session(token).account()); }
        catch (ResponseStatusException error) {
            return ResponseEntity.status(401).body(Map.of("error", error.getReason()));
        }
    }

    @PostMapping("/api/auth/logout")
    ResponseEntity<?> logout(@CookieValue(value="backstage_session", defaultValue="") String token) {
        sessions.remove(token);
        return ResponseEntity.ok().header("Set-Cookie", cookie("", 0).toString()).body(Map.of("ok", true));
    }
}
