package backstage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Transactional
class Api {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Records records;
    private final Sessions sessions;
    private final String portalToken;

    Api(Records records, Sessions sessions, @Value("${BACKSTAGE_TOKEN:local-backstage-portal}") String portalToken) {
        this.records = records;
        this.sessions = sessions;
        this.portalToken = portalToken;
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> problem(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error", error.getReason()));
    }

    private static void check(boolean condition, int status, String message) {
        if (!condition) throw new ResponseStatusException(HttpStatusCode.valueOf(status), message);
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(value))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private static String field(JsonNode input, String key, int max) {
        var value = input.path(key);
        check(value.isTextual() && !value.asText().isBlank() && value.asText().length() <= max, 400, "Invalid " + key);
        return value.asText().trim();
    }

    private String actor(String token) {
        return sessions.role(token);
    }

    private void authenticatePortal(String auth) {
        check(MessageDigest.isEqual(bytes("Bearer " + portalToken), bytes(auth)), 401, "Portal token required");
    }

    private ObjectNode get(String id) {
        var record = records.get(id);
        check(record != null, 404, "Request not found");
        return record;
    }

    private static String state(JsonNode record) { return record.path("state").asText(); }
    private static String kind(JsonNode record) { return record.path("kind").asText(); }

    @GetMapping("/health")
    Map<String, Boolean> health() { return Map.of("ok", true); }

    @GetMapping("/api/requests")
    List<ObjectNode> list(@CookieValue(value="backstage_session", defaultValue="") String auth) {
        actor(auth);
        return records.list();
    }

    @RequestMapping(path={"/api/partners", "/api/partners/{id}"}, method={RequestMethod.POST, RequestMethod.PATCH})
    ObjectNode partner(@CookieValue(value="backstage_session", defaultValue="") String auth,
                       @PathVariable(required=false) String id, @RequestBody ObjectNode input) {
        check(actor(auth).equals("maker"), 403, "Maker required");
        records.lockWrites();
        String name = field(input, "name", 200);
        var record = id == null ? records.create("partner", name).put("state", "draft") : get(id);
        check(kind(record).equals("partner") && Set.of("draft", "revision").contains(state(record)), 409, "Partner is not editable");
        record.put("name", name);
        if (input.path("details").isObject()) {
            ObjectNode details = (ObjectNode) input.path("details");
            var saved = records.object();
            for (var key : List.of("industry", "companyEmail", "website", "companyPhone",
                    "managerName", "managerEmail", "managerPhone", "supportName", "supportEmail", "supportPhone")) {
                String value = details.path(key).asText("").strip();
                check(value.length() <= 254, 400, "Invalid " + key);
                saved.put(key, value);
            }
            boolean same = details.path("sameContact").asBoolean(false);
            saved.put("sameContact", same);
            if (same) {
                for (var suffix : List.of("Name", "Email", "Phone")) {
                    saved.set("support" + suffix, saved.get("manager" + suffix));
                }
            }
            record.set("details", saved);
            record.putArray("emails").add(optionalEmail(input, "makerEmail")).add(optionalEmail(input, "checkerEmail"));
        } else {
            String maker = email(input, "makerEmail");
            String checker = email(input, "checkerEmail");
            check(!maker.equals(checker), 400, "Use two distinct emails");
            record.putArray("emails").add(maker).add(checker);
        }
        records.change(record, state(record), "maker", "");
        return record;
    }

    private static String optionalEmail(JsonNode input, String key) {
        return input.path(key).asText("").isBlank() ? "" : email(input, key);
    }

    private static void validatePartner(ObjectNode record) {
        var details = record.path("details");
        if (details.isMissingNode()) return;
        for (var key : List.of("industry", "companyPhone", "managerName", "managerPhone", "supportName", "supportPhone")) {
            field(details, key, 254);
        }
        for (var key : List.of("companyEmail", "managerEmail", "supportEmail")) email(details, key);
        String website = details.path("website").asText();
        check(website.isEmpty() || website.matches("https?://[^/\\s]+[^\\s]*"), 400, "Invalid website URL");
        var emails = record.path("emails");
        check(!emails.get(0).asText().isBlank() && !emails.get(1).asText().isBlank()
            && !emails.get(0).equals(emails.get(1)), 400, "Enter distinct Maker and Checker invitation emails");
    }

    private static String email(JsonNode input, String key) {
        String email = field(input, key, 254).toLowerCase(Locale.ROOT);
        check(email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"), 400, "Invalid email");
        return email;
    }

    @PostMapping("/api/requests/{id}/actions")
    ObjectNode action(@CookieValue(value="backstage_session", defaultValue="") String auth,
                       @PathVariable String id, @RequestBody ObjectNode input) {
        String actor = actor(auth);
        check(Set.of("maker", "checker").contains(actor), 403, "Read-only access");
        String action = field(input, "action", 20);
        records.lockWrites();
        var record = get(id);
        check(!kind(record).equals("password-reset"), 409, "Use the password-reset approval endpoint");
        check(input.path("version").asInt(-1) == record.withArray("history").size(), 409, "Refresh the request");
        boolean partner = kind(record).equals("partner");
        String state = state(record);
        if (actor.equals("maker")) {
            if (partner && state.equals("draft") && action.equals("delete")) {
                records.delete(id);
                return records.object().put("state", "deleted");
            }
            boolean canSubmit = partner && Set.of("draft", "revision").contains(state) && action.equals("submit");
            boolean canVerify = !partner && state.equals("verification") && action.equals("verify");
            check(canSubmit || canVerify, 403, "Maker action unavailable");
            if (canSubmit) validatePartner(record);
            records.change(record, "review", actor, "");
            return record;
        }
        check(state.equals("review") && Set.of("approve", "revise", "reject").contains(action), 409, "Checker action unavailable");
        if (!action.equals("approve")) {
            String note = field(input, "note", 200);
            if (!partner) {
                record.withArray("comments").addObject().put("author", "Backstage Operations")
                    .put("createdAt", Instant.now().toString()).put("text", note);
            }
            records.change(record, action.equals("revise") ? "revision" : "rejected", actor, note);
        } else if (partner) {
            records.change(record, "approved", actor, "");
            String managerEmail = record.path("details").path("managerEmail").asText("");
            if (!managerEmail.isBlank()) {
                byte[] random = new byte[32];
                RANDOM.nextBytes(random);
                String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
                var grant = records.object().put("organizationId", id).put("email", managerEmail)
                    .put("role", "project-manager");
                records.issue(digest(token), grant, Instant.now().plusSeconds(86400));
                records.queueInvitation(id, managerEmail, token, digest(token));
                record.putObject("invitationDelivery").put("recipient", managerEmail).put("attempts", 0);
                records.deliveryHistory(record, "queued", "Undangan API Portal menunggu pengiriman ke " + managerEmail);
            }
            // Retain the earlier partner role grants for existing development consumers.
            var result = record.deepCopy();
            var deliveries = result.putArray("invitations");
            for (int i = 0; i < 2; i++) {
                byte[] random = new byte[32];
                RANDOM.nextBytes(random);
                String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
                Instant expires = Instant.now().plusSeconds(86400);
                var grant = records.object().put("organizationId", id).put("email", record.path("emails").get(i).asText())
                    .put("role", i == 0 ? "partner-maker" : "partner-checker");
                records.issue(digest(token), grant, expires);
                deliveries.add(grant.deepCopy().put("token", token).put("expiresAt", expires.toString()));
            }
            return result;
        } else if (kind(record).equals("rotation") && record.path("mode").asText().equals("scheduled")) {
            check(Instant.parse(record.path("executeAt").asText()).isAfter(Instant.now()), 409, "Schedule elapsed");
            records.change(record, "scheduled", actor, "");
        } else {
            execute(record);
        }
        return record;
    }

    @PostMapping("/api/partners/{id}/invitation/retry")
    ObjectNode retryInvitation(@CookieValue(value="backstage_session", defaultValue="") String auth,
                              @PathVariable String id) {
        check(Set.of("maker", "checker").contains(actor(auth)), 403, "Read-only access");
        records.lockWrites();
        var record = get(id);
        check(kind(record).equals("partner") && state(record).equals("approved"), 409, "Approved partner required");
        check(records.retryDelivery(id), 409, "Invitation is not awaiting retry");
        records.deliveryHistory(record, "queued", "Pengiriman undangan dijadwalkan ulang");
        return record;
    }

    @PostMapping("/integration/v1/invitations/redeem")
    ObjectNode redeem(@RequestHeader(value="Authorization", defaultValue="") String auth, @RequestBody ObjectNode input) {
        authenticatePortal(auth);
        records.lockWrites();
        String tokenHash = digest(field(input, "token", 200));
        String email = email(input, "email");
        String receipt = field(input, "redemptionId", 200);
        var invite = records.invitation(tokenHash);
        check(invite != null && invite.grant().path("email").asText().equals(email)
            && (receipt.equals(invite.receipt()) || (invite.receipt() == null && invite.expires().isAfter(Instant.now()))),
            409, "Invitation unavailable");
        records.redeem(tokenHash, receipt);
        return invite.grant();
    }

    @PostMapping("/api/applications/{id}/rotations")
    ObjectNode rotation(@CookieValue(value="backstage_session", defaultValue="") String auth,
                        @PathVariable String id, @RequestBody ObjectNode input) {
        check(actor(auth).equals("maker"), 403, "Maker required");
        records.lockWrites();
        var app = get(id);
        check(kind(app).equals("application") && state(app).equals("active"), 409, "Active application required");
        String mode = field(input, "mode", 20);
        check(Set.of("immediate", "scheduled").contains(mode), 400, "Invalid rotation mode");
        var record = records.create("rotation", app.path("name").asText() + " rotation")
            .put("applicationId", id).put("organizationId", app.path("organizationId").asText()).put("mode", mode);
        if (mode.equals("scheduled")) {
            Instant due;
            try { due = java.time.OffsetDateTime.parse(field(input, "executeAt", 50)).toInstant(); }
            catch (java.time.format.DateTimeParseException e) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(400), "Use an ISO timestamp with a time zone");
            }
            check(due.isAfter(Instant.now()), 400, "Schedule must be in the future");
            record.put("executeAt", due.toString());
        }
        records.change(record, "verification", "maker", "");
        return record;
    }

    private void execute(ObjectNode record) {
        if (kind(record).equals("application")) {
            record.putObject("gateway").put("clientId", UUID.randomUUID().toString())
                .put("route", "/mock/" + record.path("id").asText()).put("keyVersion", 1);
            records.change(record, "active", "gateway", "");
        } else {
            var application = get(record.path("applicationId").asText());
            var gateway = (ObjectNode) application.path("gateway");
            gateway.put("keyVersion", gateway.path("keyVersion").asInt() + 1);
            records.save(application);
            record.set("gateway", gateway.deepCopy());
            records.change(record, "executed", "gateway", "");
        }
    }

    @Scheduled(fixedDelay=1000)
    public void executeDue() {
        records.lockWrites();
        for (var record : records.list()) {
            if (state(record).equals("scheduled") && !Instant.parse(record.path("executeAt").asText()).isAfter(Instant.now())) {
                execute(record);
            }
        }
    }

    private static final String PARTNER = "/partner/v1/organizations/{organization}/requests";

    @PostMapping(PARTNER)
    ResponseEntity<ObjectNode> intake(@RequestHeader(value="Authorization", defaultValue="") String auth,
          @RequestHeader("Idempotency-Key") String key, @PathVariable String organization, @RequestBody ObjectNode input) {
        authenticatePortal(auth);
        try { check(UUID.fromString(key).toString().equalsIgnoreCase(key), 400, "Idempotency-Key must be a UUID"); }
        catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "Idempotency-Key must be a UUID");
        }
        String name = field(input, "applicationName", 200);
        String description = field(input, "description", 4000);
        String environment = field(input, "environment", 20);
        String method = field(input, "credentialMethod", 30);
        check(Set.of("SANDBOX", "PRODUCTION").contains(environment), 400, "Invalid environment");
        check(Set.of("CLIENT_SECRET", "PUBLIC_KEY").contains(method), 400, "Invalid credential method");
        check(input.path("scopes").isArray() && !input.path("scopes").isEmpty(), 400, "Select a scope");
        var sorted = new java.util.TreeMap<String, JsonNode>();
        input.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), entry.getValue()));
        var normalized = records.object();
        sorted.forEach((field, value) -> normalized.set(field, value));
        String fingerprint = digest(normalized.toString());
        records.lockWrites();
        var previous = records.submission(organization, key);
        if (previous != null) {
            check(previous.fingerprint().equals(fingerprint), 409, "Idempotency key already used");
            return ResponseEntity.ok(partnerView(get(previous.id())));
        }
        var record = records.create("application", name).put("organizationId", organization)
            .put("description", description).put("environment", environment).put("credentialMethod", method);
        record.set("scopes", input.path("scopes"));
        var details = record.putObject("details");
        for (var field : List.of("contactName", "contactEmail", "useCase", "callbackUrl", "publicKey")) {
            if (input.path(field).isTextual()) details.set(field, input.path(field));
        }
        records.change(record, "verification", "portal", "");
        records.submission(organization, key, fingerprint, record.path("id").asText());
        return ResponseEntity.status(201).body(partnerView(record));
    }

    @GetMapping(PARTNER)
    List<ObjectNode> partnerList(@RequestHeader(value="Authorization", defaultValue="") String auth,
         @PathVariable String organization, @RequestParam String environment) {
        authenticatePortal(auth);
        return records.list().stream().filter(record -> isApplication(record, organization)
            && record.path("environment").asText().equals(environment)).map(this::partnerView).toList();
    }

    @GetMapping(PARTNER + "/{id}")
    ObjectNode partnerDetail(@RequestHeader(value="Authorization", defaultValue="") String auth,
         @PathVariable String organization, @PathVariable String id) {
        authenticatePortal(auth);
        var record = get(id);
        check(isApplication(record, organization), 404, "Request not found");
        return partnerView(record);
    }

    private static boolean isApplication(ObjectNode record, String organization) {
        return kind(record).equals("application") && record.path("organizationId").asText().equals(organization);
    }

    private ObjectNode partnerView(ObjectNode record) {
        String status = switch (state(record)) {
            case "verification" -> "SUBMITTED";
            case "review" -> "IN_REVIEW";
            case "revision" -> "CHANGES_REQUESTED";
            case "rejected" -> "REJECTED";
            case "active" -> "ACTIVE";
            default -> throw new IllegalStateException("Unknown partner status");
        };
        var view = records.object().put("applicationName", record.path("name").asText()).put("status", status);
        for (var field : List.of("id", "description", "environment", "scopes", "credentialMethod", "updatedAt", "comments")) {
            view.set(field, record.path(field));
        }
        return view;
    }
}
