package backstage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class Records {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    Records(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @PostConstruct
    void initialize() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS requests (
                id text PRIMARY KEY, document jsonb NOT NULL
            );
            CREATE TABLE IF NOT EXISTS submissions (
                organization text NOT NULL, key text NOT NULL, fingerprint text NOT NULL,
                request_id text NOT NULL REFERENCES requests(id), PRIMARY KEY (organization, key)
            );
            CREATE TABLE IF NOT EXISTS invitation_delivery (
                partner_id text PRIMARY KEY REFERENCES requests(id),
                recipient text NOT NULL, token text NOT NULL, digest text NOT NULL,
                status text NOT NULL, attempts integer NOT NULL DEFAULT 0
            );
            ALTER TABLE invitation_delivery ADD COLUMN IF NOT EXISTS registration jsonb;
            ALTER TABLE invitation_delivery ADD COLUMN IF NOT EXISTS activation_checked_at timestamptz
                NOT NULL DEFAULT '1970-01-01T00:00:00Z';
            CREATE TABLE IF NOT EXISTS password_reset_jobs (
                request_id text PRIMARY KEY REFERENCES requests(id),
                status text NOT NULL, portal_result jsonb
            );
            ALTER TABLE password_reset_jobs ADD COLUMN IF NOT EXISTS checked_at timestamptz
                NOT NULL DEFAULT '1970-01-01T00:00:00Z';
            CREATE TABLE IF NOT EXISTS portal_login_events (
                event_id uuid PRIMARY KEY, document jsonb NOT NULL
            );
            CREATE TABLE IF NOT EXISTS invitations (
                digest text PRIMARY KEY, grant_data jsonb NOT NULL, expires timestamptz NOT NULL, receipt text
            );
            """);
    }

    void queuePasswordReset(String id) {
        jdbc.update("INSERT INTO password_reset_jobs(request_id,status,portal_result) VALUES (?,'queued',NULL)", id);
    }

    record ResetJob(String id, ObjectNode result) {}

    List<ResetJob> resetJobs(String status) {
        return jdbc.query("""
            SELECT request_id,portal_result FROM password_reset_jobs WHERE status=?
            ORDER BY checked_at,request_id LIMIT 20 FOR UPDATE SKIP LOCKED
            """, (row, n) -> new ResetJob(row.getString(1),
                row.getString(2) == null ? null : parse(row.getString(2))), status);
    }

    void checkedReset(String id) {
        jdbc.update("UPDATE password_reset_jobs SET checked_at=now() WHERE request_id=?", id);
    }

    void resetJobStatus(String id, String status) {
        jdbc.update("UPDATE password_reset_jobs SET status=? WHERE request_id=?", status, id);
    }

    void resetJobResult(String id, ObjectNode result) {
        jdbc.update("UPDATE password_reset_jobs SET portal_result=?::jsonb WHERE request_id=?", result.toString(), id);
    }

    boolean retryReset(String id) {
        return jdbc.update("UPDATE password_reset_jobs SET status='queued' WHERE request_id=? AND status='failed'", id) == 1;
    }

    ObjectNode loginEvent(java.util.UUID eventId) {
        return jdbc.query("SELECT document FROM portal_login_events WHERE event_id=?",
            (row, n) -> parse(row.getString(1)), eventId).stream().findFirst().orElse(null);
    }

    void loginEvent(java.util.UUID eventId, ObjectNode event) {
        jdbc.update("INSERT INTO portal_login_events VALUES (?,?::jsonb)", eventId, event.toString());
    }

    void lockWrites() {
        jdbc.execute("SELECT pg_advisory_xact_lock(8813110)");
    }

    ObjectNode object() { return json.createObjectNode(); }

    ObjectNode parse(String source) {
        try { return (ObjectNode) json.readTree(source); }
        catch (Exception error) { throw new IllegalStateException("Invalid stored document", error); }
    }

    ObjectNode create(String kind, String name) {
        var record = object().put("id", UUID.randomUUID().toString()).put("kind", kind).put("name", name);
        record.putArray("history");
        record.putArray("comments");
        return record;
    }

    ObjectNode get(String id) {
        return jdbc.query("SELECT document FROM requests WHERE id=?", (row, n) -> parse(row.getString(1)), id)
            .stream().findFirst().orElse(null);
    }

    List<ObjectNode> list() {
        return jdbc.query("SELECT document FROM requests ORDER BY document->>'updatedAt' DESC",
            (row, n) -> parse(row.getString(1)));
    }

    void save(ObjectNode record) {
        jdbc.update("""
            INSERT INTO requests VALUES (?, ?::jsonb)
            ON CONFLICT(id) DO UPDATE SET document=EXCLUDED.document
            """, record.path("id").asText(), record.toString());
    }

    void delete(String id) { jdbc.update("DELETE FROM requests WHERE id=?", id); }

    void change(ObjectNode record, String state, String actor, String note) {
        String now = Instant.now().toString();
        record.put("state", state).put("updatedAt", now);
        record.withArray("history").addObject().put("state", state).put("actor", actor).put("note", note).put("at", now);
        save(record);
    }

    void deliveryHistory(ObjectNode record, String status, String note) {
        String now = Instant.now().toString();
        record.put("updatedAt", now);
        record.withArray("history").addObject().put("state", "invitation_" + status)
            .put("actor", "email").put("note", note).put("at", now);
        record.with("invitationDelivery").put("status", status).put("updatedAt", now);
        save(record);
    }

    void queueInvitation(String partnerId, String recipient, String token, String digest) {
        jdbc.update("INSERT INTO invitation_delivery(partner_id,recipient,token,digest,status) VALUES (?,?,?,?,'queued')",
            partnerId, recipient, token, digest);
    }

    record Delivery(String partnerId, String recipient, String token, String digest, int attempts, ObjectNode registration) {}

    List<Delivery> queuedDeliveries() {
        return jdbc.query("""
            SELECT partner_id,recipient,token,digest,attempts,registration FROM invitation_delivery
            WHERE status='queued' ORDER BY partner_id LIMIT 10 FOR UPDATE SKIP LOCKED
            """, (row, n) -> new Delivery(row.getString(1), row.getString(2), row.getString(3), row.getString(4), row.getInt(5), row.getString(6) == null ? null : parse(row.getString(6))));
    }

    List<String> pendingActivations() {
        return jdbc.query("""
            SELECT d.partner_id FROM invitation_delivery d JOIN requests r ON r.id=d.partner_id
            WHERE d.registration IS NOT NULL AND r.document->'portalRegistration'->>'status'='registered'
            ORDER BY d.activation_checked_at,d.partner_id LIMIT 20
            """, (row, n) -> row.getString(1));
    }

    void checkedActivation(String partnerId) {
        jdbc.update("UPDATE invitation_delivery SET activation_checked_at=now() WHERE partner_id=?", partnerId);
    }

    void registered(String partnerId, ObjectNode registration) {
        jdbc.update("UPDATE invitation_delivery SET registration=?::jsonb WHERE partner_id=?",
            registration.toString(), partnerId);
    }

    void registrationHistory(ObjectNode record, String status, String note) {
        record.withArray("history").addObject().put("state", "portal_registration_" + status)
            .put("actor", "portal").put("note", note).put("at", Instant.now().toString());
        save(record);
    }

    void delivered(String partnerId, String status) {
        jdbc.update("UPDATE invitation_delivery SET status=?, attempts=attempts+1 WHERE partner_id=?", status, partnerId);
    }

    boolean retryDelivery(String partnerId) {
        return jdbc.update("UPDATE invitation_delivery SET status='queued' WHERE partner_id=? AND status='failed'", partnerId) == 1;
    }

    void renewInvitation(String digest, Instant expires) {
        jdbc.update("UPDATE invitations SET expires=? WHERE digest=? AND receipt IS NULL",
            java.sql.Timestamp.from(expires), digest);
    }

    record Receipt(String fingerprint, String id) {}

    Receipt submission(String org, String key) {
        return jdbc.query("SELECT fingerprint, request_id FROM submissions WHERE organization=? AND key=?",
            (row, n) -> new Receipt(row.getString(1), row.getString(2)), org, key).stream().findFirst().orElse(null);
    }

    void submission(String org, String key, String fingerprint, String id) {
        jdbc.update("INSERT INTO submissions VALUES (?,?,?,?)", org, key, fingerprint, id);
    }

    record Invite(ObjectNode grant, Instant expires, String receipt) {}

    Invite invitation(String digest) {
        return jdbc.query("SELECT grant_data, expires, receipt FROM invitations WHERE digest=?",
            (row, n) -> new Invite(parse(row.getString(1)), row.getTimestamp(2).toInstant(), row.getString(3)),
            digest).stream().findFirst().orElse(null);
    }

    void issue(String digest, JsonNode grant, Instant expires) {
        jdbc.update("INSERT INTO invitations VALUES (?,?::jsonb,?,NULL)", digest, grant.toString(), java.sql.Timestamp.from(expires));
    }

    void redeem(String digest, String receipt) {
        jdbc.update("UPDATE invitations SET receipt=? WHERE digest=?", receipt, digest);
    }
}
