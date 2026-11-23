package backstage;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Transactional
class PasswordResets {
    private static final Logger LOG = LoggerFactory.getLogger(PasswordResets.class);
    private final Records records;
    private final Sessions sessions;
    private final InvitationDelivery registration;
    private final String portalUrl;
    private final String portalToken;
    private final String emailUrl;
    private final String emailToken;
    private final String templateCode;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    PasswordResets(Records records, Sessions sessions, InvitationDelivery registration,
            @Value("${PORTAL_PASSWORD_RESET_URL:}") String resetUrl,
            @Value("${PORTAL_REGISTRATION_URL:}") String registrationUrl,
            @Value("${PORTAL_REGISTRATION_TOKEN:local-backstage-registration}") String portalToken,
            @Value("${EMAIL_API_URL:}") String emailUrl,
            @Value("${EMAIL_API_TOKEN:}") String emailToken,
            @Value("${EMAIL_PASSWORD_RESET_TEMPLATE_CODE:API_PORTAL_PASSWORD_RESET}") String templateCode) {
        this.records = records;
        this.sessions = sessions;
        this.registration = registration;
        this.portalUrl = resetUrl.isBlank() ? registrationUrl.replaceAll("/registrations/?$", "/password-resets") : resetUrl.replaceAll("/+$", "");
        this.portalToken = portalToken;
        this.emailUrl = emailUrl;
        this.emailToken = emailToken;
        this.templateCode = templateCode;
    }

    private static void require(boolean condition, HttpStatus status, String message) {
        if (!condition) throw new ResponseStatusException(status, message);
    }

    private ObjectNode get(String id, String kind) {
        var record = records.get(id);
        require(record != null && record.path("kind").asText().equals(kind), HttpStatus.NOT_FOUND, "Request not found");
        return record;
    }

    private static String state(ObjectNode record) { return record.path("state").asText(); }

    private void partnerHistory(ObjectNode reset, String event, String actor, String note) {
        var partner = get(reset.path("organizationId").asText(), "partner");
        String now = Instant.now().toString();
        partner.withArray("history").addObject().put("state", "password_reset_" + event)
            .put("actor", actor).put("at", now).put("note", reset.path("email").asText() + " · " + note)
            .put("requestId", reset.path("id").asText());
        partner.put("updatedAt", now);
        records.save(partner);
    }

    @PostMapping("/api/partners/{id}/password-resets")
    ObjectNode create(@CookieValue(value="backstage_session", defaultValue="") String cookie,
                      @PathVariable String id, @RequestBody ObjectNode input) {
        require(sessions.role(cookie).equals("maker"), HttpStatus.FORBIDDEN, "Maker required");
        records.lockWrites();
        var partner = get(id, "partner");
        require(Set.of("approved", "active").contains(state(partner)), HttpStatus.CONFLICT, "Approved partner required");
        String email = partner.path("details").path("managerEmail").asText().strip().toLowerCase(java.util.Locale.ROOT);
        require(!email.isBlank(), HttpStatus.CONFLICT, "Registered project manager email required");
        String reason = input.path("reason").asText("").strip();
        require(reason.length() <= 500, HttpStatus.BAD_REQUEST, "Reason must fit within 500 characters");
        for (var prior : records.list()) {
            require(!(prior.path("kind").asText().equals("password-reset")
                && prior.path("organizationId").asText().equals(id)
                && !Set.of("completed", "expired", "rejected").contains(state(prior))),
                HttpStatus.CONFLICT, "A password-reset request is already open for this partner");
        }
        ObjectNode progress;
        try { progress = registration.progress(id); }
        catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Portal registration verification unavailable");
        }
        require(progress.path("status").asText().equals("ACTIVE"), HttpStatus.CONFLICT, "Portal account must be active");
        String userId = progress.path("userId").asText();
        require(!userId.isBlank(), HttpStatus.CONFLICT, "Portal account identity unavailable");
        var reset = records.create("password-reset", partner.path("name").asText() + " · Reset password")
            .put("organizationId", id).put("email", email).put("portalUserId", userId)
            .put("reason", reason);
        records.change(reset, "review", "maker", reason);
        partnerHistory(reset, "requested", "maker", "Permintaan reset password menunggu persetujuan Checker");
        return reset;
    }

    @PostMapping("/api/password-resets/{id}/actions")
    ObjectNode action(@CookieValue(value="backstage_session", defaultValue="") String cookie,
                      @PathVariable String id, @RequestBody ObjectNode input) {
        require(sessions.role(cookie).equals("checker"), HttpStatus.FORBIDDEN, "Checker required");
        records.lockWrites();
        var reset = get(id, "password-reset");
        require(state(reset).equals("review") && input.path("version").asInt(-1) == reset.withArray("history").size(),
            HttpStatus.CONFLICT, "Refresh the pending reset request");
        String action = input.path("action").asText();
        require(Set.of("approve", "reject").contains(action), HttpStatus.BAD_REQUEST, "Choose approve or reject");
        if (action.equals("reject")) {
            String note = input.path("note").asText("").strip();
            require(!note.isBlank() && note.length() <= 200, HttpStatus.BAD_REQUEST, "Rejection note required");
            records.change(reset, "rejected", "checker", note);
            partnerHistory(reset, "rejected", "checker", note);
        } else {
            var partner = get(reset.path("organizationId").asText(), "partner");
            require(partner.path("details").path("managerEmail").asText().strip().equalsIgnoreCase(reset.path("email").asText()),
                HttpStatus.CONFLICT, "Registered email changed. Review the target account.");
            reset.put("approvedAt", Instant.now().toString());
            reset.putObject("resetDelivery").put("status", "queued").put("attempts", 0);
            records.change(reset, "approved", "checker", "");
            records.queuePasswordReset(id);
            partnerHistory(reset, "approved", "checker", "Reset password disetujui; menunggu pembuatan tautan dan pengiriman email");
        }
        return reset;
    }

    @PostMapping("/api/password-resets/{id}/retry")
    ObjectNode retry(@CookieValue(value="backstage_session", defaultValue="") String cookie, @PathVariable String id) {
        require(Set.of("maker", "checker").contains(sessions.role(cookie)), HttpStatus.FORBIDDEN, "Read-only access");
        records.lockWrites();
        var reset = get(id, "password-reset");
        require(!reset.path("approvedAt").asText().isBlank() && records.retryReset(id),
            HttpStatus.CONFLICT, "Approved failed reset required");
        reset.with("resetDelivery").put("status", "queued");
        records.change(reset, "approved", sessions.role(cookie), "Pengiriman reset password dijadwalkan ulang");
        partnerHistory(reset, "queued", sessions.role(cookie), "Proses reset password dijadwalkan ulang");
        return reset;
    }

    private ObjectNode portal(String suffix, ObjectNode body) throws Exception {
        require(!portalUrl.isBlank(), HttpStatus.SERVICE_UNAVAILABLE, "Portal reset endpoint is not configured");
        var request = HttpRequest.newBuilder(URI.create(portalUrl + suffix)).timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + portalToken).header("Content-Type", "application/json");
        if (body == null) request.GET();
        else request.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("Portal reset API returned " + response.statusCode());
        return records.parse(response.body());
    }

    private static String reference(String id) { return "password-reset-" + id; }

    private void finish(ObjectNode reset, String status, String note) {
        reset.with("resetDelivery").put("status", status);
        records.resetJobStatus(reset.path("id").asText(), status);
        records.change(reset, status, "portal", note);
        partnerHistory(reset, status, "portal", note);
    }

    @Scheduled(fixedDelay=3000)
    public void sendApproved() {
        records.lockWrites();
        for (var job : records.resetJobs("queued")) {
            var reset = get(job.id(), "password-reset");
            String phase = "portal";
            try {
                var issued = job.result();
                if (issued == null) {
                    issued = portal("", records.object().put("referenceId", reference(job.id()))
                        .put("registrationReference", "partner-invitation-" + reset.path("organizationId").asText())
                        .put("organizationId", reset.path("organizationId").asText()).put("email", reset.path("email").asText()));
                    require(issued.path("userId").asText().equals(reset.path("portalUserId").asText())
                        && issued.path("email").asText().equalsIgnoreCase(reset.path("email").asText())
                        && !issued.path("resetUrl").asText().isBlank() && issued.path("status").asText().equals("PENDING"),
                        HttpStatus.CONFLICT, "Portal reset identity mismatch");
                    records.resetJobResult(job.id(), issued);
                    reset.with("resetDelivery").put("expiresAt", issued.path("expiresAt").asText());
                    partnerHistory(reset, "issued", "portal", "Tautan reset password telah dibuat");
                }
                var progress = portal("/" + reference(job.id()), null);
                require(progress.path("userId").asText().equals(reset.path("portalUserId").asText()),
                    HttpStatus.CONFLICT, "Portal reset identity changed");
                if (progress.path("status").asText().equals("COMPLETED")) {
                    finish(reset, "completed", "Password akun API Portal berhasil diperbarui");
                    continue;
                }
                if (!Instant.parse(issued.path("expiresAt").asText()).isAfter(Instant.now()) ||
                    progress.path("status").asText().equals("EXPIRED")) {
                    finish(reset, "expired", "Tautan reset password kedaluwarsa; ajukan permintaan baru");
                    continue;
                }
                require(progress.path("status").asText().equals("PENDING"), HttpStatus.CONFLICT, "Reset is unavailable");
                phase = "email";
                require(!emailUrl.isBlank(), HttpStatus.SERVICE_UNAVAILABLE, "Email API is not configured");
                var payload = records.object().put("email", reset.path("email").asText())
                    .put("referenceId", reference(job.id())).put("templateCode", templateCode)
                    .put("templateEmail", "Permintaan reset password API Portal telah disetujui.\n\n"
                        + issued.path("resetUrl").asText() + "\n\nTautan berlaku sampai " + issued.path("expiresAt").asText()
                        + ". Gunakan tautan ini untuk membuat password baru.");
                var request = HttpRequest.newBuilder(URI.create(emailUrl)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").header("Idempotency-Key", reference(job.id()));
                if (!emailToken.isBlank()) request.header("Authorization", "Bearer " + emailToken);
                var response = http.send(request.POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build(),
                    HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("Email API rejected reset");
                reset.with("resetDelivery").put("attempts", reset.path("resetDelivery").path("attempts").asInt() + 1);
                finish(reset, "sent", "Email reset password diterima layanan email");
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                reset.with("resetDelivery").put("failureStep", phase)
                    .put("attempts", reset.path("resetDelivery").path("attempts").asInt() + 1);
                finish(reset, "failed", phase.equals("portal") ? "Pembuatan tautan reset gagal; silakan coba lagi"
                    : "Pengiriman email reset gagal; silakan coba lagi");
                LOG.warn("Password reset processing failed for request {} at {}", job.id(), phase);
            }
        }
    }

    @Scheduled(fixedDelay=15000)
    public void syncCompletion() {
        records.lockWrites();
        for (String state : new String[] { "sent", "failed" }) {
            for (var job : records.resetJobs(state)) {
                records.checkedReset(job.id());
                if (job.result() == null) continue;
                try {
                    var reset = get(job.id(), "password-reset");
                    var progress = portal("/" + reference(job.id()), null);
                    if (!progress.path("userId").asText().equals(reset.path("portalUserId").asText())) continue;
                    if (progress.path("status").asText().equals("COMPLETED")) finish(reset, "completed", "Password akun API Portal berhasil diperbarui");
                    else if (progress.path("status").asText().equals("EXPIRED") ||
                        !Instant.parse(job.result().path("expiresAt").asText()).isAfter(Instant.now())) {
                        finish(reset, "expired", "Tautan reset password kedaluwarsa; ajukan permintaan baru");
                    }
                } catch (Exception error) {
                    if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                    LOG.warn("Password reset progress unavailable for request {}", job.id());
                }
            }
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> problem(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error", error.getReason()));
    }
}
