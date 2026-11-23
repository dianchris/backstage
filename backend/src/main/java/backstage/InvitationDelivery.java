package backstage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class InvitationDelivery {
    private static final Logger LOG = LoggerFactory.getLogger(InvitationDelivery.class);
    private final Records records;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String templateCode;
    private final String emailUrl;
    private final String emailToken;
    private final String portalApi;
    private final String registrationToken;

    InvitationDelivery(Records records,
            @Value("${EMAIL_TEMPLATE_CODE:API_PORTAL_ONBOARDING_INVITATION}") String templateCode,
            @Value("${EMAIL_API_URL:}") String emailUrl,
            @Value("${EMAIL_API_TOKEN:}") String emailToken,
            @Value("${PORTAL_REGISTRATION_URL:}") String portalApi,
            @Value("${PORTAL_REGISTRATION_TOKEN:local-backstage-registration}") String registrationToken) {
        this.records = records;
        this.templateCode = templateCode;
        this.emailUrl = emailUrl;
        this.emailToken = emailToken;
        this.portalApi = portalApi;
        this.registrationToken = registrationToken;
    }

    @Scheduled(fixedDelay=3000)
    @Transactional
    public void sendQueued() {
        records.lockWrites();
        for (var delivery : records.queuedDeliveries()) {
            var record = records.get(delivery.partnerId());
            String phase = "registration";
            String status;
            String note;
            try {
                var registration = delivery.registration();
                if (registration == null) {
                    if (portalApi.isBlank()) throw new IllegalStateException("PORTAL_REGISTRATION_URL is not configured");
                    var input = records.object().put("referenceId", "partner-invitation-" + delivery.partnerId())
                        .put("organizationId", delivery.partnerId()).put("companyName", record.path("name").asText())
                        .put("email", delivery.recipient()).put("name", record.path("details").path("managerName").asText());
                    var request = HttpRequest.newBuilder(URI.create(portalApi)).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + registrationToken)
                        .POST(HttpRequest.BodyPublishers.ofString(input.toString())).build();
                    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalStateException("Portal registration returned " + response.statusCode());
                    }
                    registration = records.parse(response.body());
                    if (!registration.path("status").asText().equals("INVITED") ||
                        registration.path("userId").asText().isBlank() || registration.path("invitationUrl").asText().isBlank()) {
                        throw new IllegalStateException("Invalid Portal registration result");
                    }
                    records.registered(delivery.partnerId(), registration);
                    record.putObject("portalRegistration").put("status", "registered")
                        .put("userId", registration.path("userId").asText());
                    records.registrationHistory(record, "succeeded", "Project manager terdaftar di API Portal");
                }
                var current = progress(delivery.partnerId());
                if (!current.path("userId").asText().equals(registration.path("userId").asText())) {
                    throw new IllegalStateException("Portal registration identity changed");
                }
                if (current.path("status").asText().equals("ACTIVE")) {
                    record.with("portalRegistration").put("status", "active");
                    records.registrationHistory(record, "accepted", "Project manager telah mengaktifkan akun API Portal");
                    records.delivered(delivery.partnerId(), "sent");
                    records.deliveryHistory(record, "sent", "Akun API Portal sudah aktif; email tidak perlu dikirim ulang");
                    continue;
                }
                if (!Instant.parse(registration.path("expiresAt").asText()).isAfter(Instant.now())) {
                    throw new IllegalStateException("Portal invitation has expired");
                }
                if (!current.path("status").asText().equals("INVITED")) {
                    throw new IllegalStateException("Portal invitation is unavailable");
                }
                phase = "email";
                String link = registration.path("invitationUrl").asText();
                var payload = records.object().put("email", delivery.recipient())
                    .put("referenceId", "partner-invitation-" + delivery.partnerId())
                    .put("templateCode", templateCode)
                    .put("templateEmail", "Halo,\n\nPendaftaran " + record.path("name").asText()
                        + " telah disetujui. Anda diundang sebagai Project Manager untuk melanjutkan onboarding di API Portal.\n\n"
                        + link + "\n\nGunakan email " + delivery.recipient()
                        + " untuk menerima undangan. Tautan berlaku selama 24 jam.\n\nTim Production Operations");
                if (emailUrl.isBlank()) throw new IllegalStateException("EMAIL_API_URL is not configured");
                var request = HttpRequest.newBuilder(URI.create(emailUrl)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    // Stable across retries. The email service must honor this key to deduplicate
                    // ambiguous delivery failures (for example a lost success response).
                    .header("Idempotency-Key", "partner-invitation-" + delivery.partnerId());
                if (!emailToken.isBlank()) request.header("Authorization", "Bearer " + emailToken);
                var response = http.send(request.POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build(),
                    HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("Email API returned " + response.statusCode());
                }
                status = "sent";
                note = "Undangan API Portal diterima layanan email untuk " + delivery.recipient();
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                status = "failed";
                note = phase.equals("registration")
                    ? "Registrasi project manager di API Portal gagal. Email belum dikirim."
                    : "Pengiriman undangan ke " + delivery.recipient() + " gagal. Silakan coba lagi.";
                if (phase.equals("registration")) records.registrationHistory(record, "failed", note);
                LOG.warn("Invitation email submission failed for partner {} ({})",
                    delivery.partnerId(), error.getClass().getSimpleName());
            }
            records.delivered(delivery.partnerId(), status);
            record.with("invitationDelivery").put("attempts", delivery.attempts() + 1)
                .put("failureStep", status.equals("failed") ? phase : "");
            records.deliveryHistory(record, status, note);
        }
    }
    com.fasterxml.jackson.databind.node.ObjectNode progress(String partnerId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(portalApi.replaceAll("/+$", "")
                + "/partner-invitation-" + partnerId))
            .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + registrationToken).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Portal status is unavailable");
        return records.parse(response.body());
    }

    @Scheduled(fixedDelay=15000)
    @Transactional
    public void syncAcceptance() {
        if (portalApi.isBlank()) return;
        records.lockWrites();
        for (var partnerId : records.pendingActivations()) {
            records.checkedActivation(partnerId);
            try {
                var progress = progress(partnerId);
                String status = progress.path("status").asText();
                var record = records.get(partnerId);
                if (!progress.path("userId").asText().equals(record.path("portalRegistration").path("userId").asText())) {
                    throw new IllegalStateException("Portal registration identity changed");
                }
                if (status.equals("ACTIVE")) {
                    record.with("portalRegistration").put("status", "active")
                        .put("acceptedAt", progress.path("acceptedAt").asText());
                    records.registrationHistory(record, "accepted", "Project manager telah mengaktifkan akun API Portal");
                } else if (status.equals("EXPIRED")) {
                    record.with("portalRegistration").put("status", "expired");
                    records.registrationHistory(record, "expired", "Undangan API Portal kedaluwarsa; hubungi administrator");
                }
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                LOG.warn("Portal activation sync unavailable for partner {}", partnerId);
            }
        }
    }

}
