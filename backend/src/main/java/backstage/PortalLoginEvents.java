package backstage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
class PortalLoginEvents {
    private final Records records;
    private final InvitationDelivery portal;
    private final ObjectMapper json;
    private final String token;

    PortalLoginEvents(Records records, InvitationDelivery portal, ObjectMapper json,
                      @Value("${BACKSTAGE_TOKEN:local-backstage-portal}") String token) {
        this.records = records;
        this.portal = portal;
        this.json = json;
        this.token = token;
    }

    record LoginEvent(@NotNull UUID eventId,
                      @NotBlank @Pattern(regexp="PORTAL_LOGIN_SUCCEEDED") String type,
                      @NotBlank @Size(max=200) String referenceId,
                      @NotNull UUID userId,
                      @NotBlank @Size(max=200) String organizationId,
                      @NotBlank @Email @Size(max=254) String email,
                      @NotNull Instant occurredAt) {}

    private static void require(boolean condition, HttpStatus status, String message) {
        if (!condition) throw new ResponseStatusException(status, message);
    }

    @PostMapping(path="/integration/v1/portal-login-events", consumes="application/json")
    @Transactional
    Map<String, String> receive(@RequestHeader(value="Authorization", defaultValue="") String authorization,
                               @Valid @RequestBody LoginEvent input) {
        require(MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8),
            authorization.getBytes(StandardCharsets.UTF_8)), HttpStatus.UNAUTHORIZED, "Portal service credentials required");
        var event = new LoginEvent(input.eventId(), input.type(), input.referenceId(), input.userId(),
            input.organizationId(), input.email().strip().toLowerCase(Locale.ROOT), input.occurredAt());
        ObjectNode document = json.valueToTree(event);
        records.lockWrites();
        var previous = records.loginEvent(event.eventId());
        if (previous != null) {
            require(previous.equals(document), HttpStatus.CONFLICT, "Event ID was reused with a different payload");
            return Map.of("status", "recorded");
        }
        var partner = records.get(event.organizationId());
        require(partner != null && partner.path("kind").asText().equals("partner")
            && Set.of("approved", "active").contains(partner.path("state").asText()),
            HttpStatus.CONFLICT, "Approved partner required");
        require(event.referenceId().equals("partner-invitation-" + event.organizationId()),
            HttpStatus.CONFLICT, "Registration reference does not match partner");
        require(event.email().equals(partner.path("details").path("managerEmail").asText().strip().toLowerCase(Locale.ROOT)),
            HttpStatus.CONFLICT, "Login email does not match project manager");
        String storedUser = partner.path("portalRegistration").path("userId").asText();
        if (!storedUser.isBlank()) {
            require(storedUser.equals(event.userId().toString()), HttpStatus.CONFLICT, "Portal user does not match registration");
        } else {
            // Imported accounts can predate the cached registration. Resolve identity
            // through authenticated Portal progress for the expected reference.
            ObjectNode progress;
            try { progress = portal.progress(event.organizationId()); }
            catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Portal registration verification unavailable");
            }
            require(progress.path("userId").asText().equals(event.userId().toString())
                && progress.path("status").asText().equals("ACTIVE"),
                HttpStatus.CONFLICT, "Portal registration does not match an active user");
            partner.with("portalRegistration").put("userId", event.userId().toString()).put("status", "registered");
            if (!progress.path("acceptedAt").asText().isBlank()) {
                Instant accepted;
                try { accepted = Instant.parse(progress.path("acceptedAt").asText()); }
                catch (java.time.format.DateTimeParseException error) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Invalid Portal activation timestamp");
                }
                boolean hasActivation = false;
                for (var entry : partner.withArray("history")) {
                    if (entry.path("state").asText().equals("portal_registration_accepted")) hasActivation = true;
                }
                if (!hasActivation) partner.withArray("history").addObject()
                    .put("state", "portal_registration_accepted").put("actor", "portal")
                    .put("note", "Project manager telah mengaktifkan akun API Portal")
                    .put("at", accepted.toString());
                partner.with("portalRegistration").put("status", "active").put("acceptedAt", accepted.toString());
            }
        }
        partner.withArray("history").addObject().put("state", "portal_login_succeeded")
            .put("actor", "portal").put("note", event.email()).put("at", event.occurredAt().toString())
            .put("eventId", event.eventId().toString());
        var registration = partner.with("portalRegistration");
        String last = registration.path("lastLoginAt").asText();
        if (last.isBlank() || event.occurredAt().isAfter(Instant.parse(last))) {
            registration.put("lastLoginAt", event.occurredAt().toString());
        }
        String updated = partner.path("updatedAt").asText();
        if (updated.isBlank() || event.occurredAt().isAfter(Instant.parse(updated))) {
            partner.put("updatedAt", event.occurredAt().toString());
        }
        records.save(partner);
        records.loginEvent(event.eventId(), document);
        return Map.of("status", "recorded");
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> problem(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error", error.getReason()));
    }
}
