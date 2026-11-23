# Backstage

Internal Prod Ops workspace. This Docker project uses the same stack as the sibling API Portal: Next.js and React, Spring Boot on Java 21, and PostgreSQL 17. The Postgres database and volume belong to Backstage. API Portal accesses applications via the HTTP endpoints defined in its [partner contract](../api-portal/CONTRACT.md).

## Start locally

Start Docker Desktop and run:

```sh
cd "/Users/dian/Documents/ChatGPT/Backstage/backstage"
docker compose up --build -d --wait
docker compose ps
```

Open [Backstage](http://127.0.0.1:3110) and sign in with a sample account. Usernames are `maker`, `checker`, and `product-manager`. All three use `Backstage123!`. Maker creates and verifies requests. Checker approves, rejects, or requests revisions. Product Manager can view all records and history. Backend permissions enforce read-only access for Product Manager. Sessions last eight hours, use an HttpOnly cookie, and end on logout or backend restart. These fixed credentials are intended for the local sample workspace. The API is available on [port 8091](http://127.0.0.1:8091/health). Set `BACKSTAGE_UI_PORT` or `BACKSTAGE_API_PORT` if either port is in use. For status and logs, use `docker compose ps` and `docker compose logs -f backend`. Stop with `docker compose down`. The PostgreSQL volume retains records.

Run `node scripts/smoke.mjs` to exercise the running containers through both exposed ports. This creates a fictional partner and application, verifies role and organization boundaries and retries, then provisions and rotates the mock credential.

Select **Tambah Partner** as Backstage Maker. Fill **Detail perusahaan** and **Project manager & tim support**, or save a partial draft once the company has a name. Use **Sama dengan project manager** to copy contact details to Tim Support. Enter distinct Partner Maker and Checker invitation emails before submitting. Approve as Backstage Checker. Copy the demo invitation codes when approval displays them. Application requests arrive through the Portal-compatible API. The Maker verifies a request before the Checker can decide it. For an active application, the Maker can open an immediate or scheduled local test rotation and route it through the same two review steps.

## Connect the sibling Portal when ready

Set Portal's `BACKSTAGE_URL=http://host.docker.internal:8091` and match its `BACKSTAGE_TOKEN` with Backstage. The Backstage development default is `local-backstage-portal`. Start Portal without the `demo` profile to use this backend. If the host port was overridden, use that port in the URL.

The Partner API implements organization-scoped create, list, and detail. It requires a server-side token and idempotency keys for creation. Portal receives public status and Checker feedback. Backstage keeps contacts, credential inputs, internal history and gateway metadata on the Ops side.

## Draft scope and decisions

- Partner Maker/Checker here refers to roles issued in invitations. Backstage demo roles represent the bank users. Partner identity and approval belong to Portal. The current Portal contract provides application intake, with authenticated organization scope delegated to Portal, plus status queries. It does not provide organization membership validation, invitation redemption wiring, rotation intake or application resubmission. The local rotation form lets Ops exercise the execution path.
- Public application intake enters bank verification. Rejection and change requests are visible through the Portal API. Confirm how the partner sends revisions back and where partner-side approval gates intake.
- Invitations are short-lived, role-scoped and redeemable once through the token-protected Backstage endpoint. The local UI displays them on approval. SSO, production invitation delivery and credential custody remain blueprint decisions.
- Local gateway provisioning and key rotation update mock metadata. A real gateway, VA key management, revocation policy and retry/reconciliation are required for production.
- This is a fresh PostgreSQL database. The earlier SQLite prototype and its data remain in the original workspace. Decide which records to migrate before using this stack with existing operational data.

## Project manager invitation email

Approving a newly submitted partner first registers its project manager through the configured Portal registration API. Only a successful registration response permits the email POST. Backstage stores the registration result and reuses it when email sending is retried. Approval stores a delivery job for the registered project manager email. The worker POSTs JSON to `EMAIL_API_URL` with optional bearer authentication from `EMAIL_API_TOKEN`. Configure these through a local `.env` using `.env.example`, then run `docker compose up -d --force-recreate backend`.

The sample body is:

```json
{
  "email": "projectmanager@example.test",
  "templateEmail": "Halo, pendaftaran perusahaan telah disetujui. Lanjutkan onboarding melalui tautan undangan API Portal.",
  "referenceId": "partner-invitation-<partner-id>",
  "templateCode": "API_PORTAL_ONBOARDING_INVITATION"
}
```

The actual content includes the company name, invitation URL and a 24-hour validity notice. `templateEmail` is plain text in this sample contract. `EMAIL_TEMPLATE_CODE` controls the template code. HTTP 2xx means the email service accepted the request. Recipient delivery and bounce reporting require the service's eventual contract.

Approval, queued delivery, acceptance and failure appear in Riwayat onboarding. If the POST fails, Maker and Checker can select **Coba Kirim Ulang**. Product Manager can view the delivery result. Approval stays approved if email delivery fails. The worker reloads queued jobs after a restart. Retries reuse the same token, reference ID and Idempotency-Key. The email service should deduplicate by that key to handle a lost response safely. The endpoint has a 10-second request timeout. Missing configuration is reported as failed delivery and can be retried after configuration is added.

Portal owns invitation tokens, account activation and the invitation URL. Its registration API is implemented in the API Portal project. Configure `PORTAL_REGISTRATION_URL` and `PORTAL_REGISTRATION_TOKEN` once available. The default local target is `http://host.docker.internal:8092/internal/v1/registrations`. A missing or failing Portal endpoint prevents email sending and appears as a registration failure with a retry button.

The proposed registration POST uses bearer service authentication and this body:

```json
{
  "referenceId": "partner-invitation-<partner-id>",
  "organizationId": "<partner-id>",
  "companyName": "Example Company",
  "email": "projectmanager@example.test",
  "name": "Project Manager"
}
```

The expected HTTP 2xx response includes `userId`, `status: "INVITED"`, `invitationUrl`, and `expiresAt`. Portal should return the same registration for repeated reference IDs and reject changed identity fields with 409. Backstage persists the response before attempting email, so email failure does not create another Portal account on retry.

Riwayat onboarding records Portal registration success or failure followed by email delivery. On registration failure the retry button reads **Coba Registrasi Ulang**. On email failure it reads **Coba Kirim Ulang**. Portal account creation, token acceptance, and activation belong to the separate API Portal project.

The original local Maker and Checker grant fixtures remain available for the earlier development walkthrough. The new delivery worker emails only the registered project manager.


Backstage also reads `GET <PORTAL_REGISTRATION_URL>/<referenceId>` with the registration bearer token. The Portal returns `userId`, `status` (`INVITED`, `ACTIVE`, or `EXPIRED`), `expiresAt`, and `acceptedAt`. Backstage verifies the returned user ID before an email retry. An expired or unavailable link blocks sending. A registration that has become active is recorded in history and skips resending. A worker polls pending registrations every 15 seconds and records activation or expiry in Riwayat onboarding. The separate Portal Project Manager may view and submit Sandbox applications after activation. The Backstage Product Manager retains read-only access.

## Portal login status in Riwayat

Portal sends `POST /integration/v1/portal-login-events` using the shared Backstage bearer token. The JSON body contains `eventId` (UUID), `type: "PORTAL_LOGIN_SUCCEEDED"`, `referenceId`, `userId` (UUID), `organizationId`, normalized `email`, and server-generated UTC `occurredAt`.

Backstage records the receipt and partner history atomically before returning 200. Replayed event IDs with the same payload return 200, while changed payloads return 409. It checks the approved partner, expected registration reference, registered project manager email and stored Portal user ID. Imported accounts without a stored user ID are verified through Portal's authenticated registration progress endpoint. Verification outages return 503 so Portal can retry.

Riwayat shows **Project manager berhasil login ke API Portal**, the project manager email, and the event time. Account activation stays a separate event. History is ordered by event time and `portalRegistration.lastLoginAt` never moves backward when an older notification arrives late. Each distinct actual sign-in has its own event ID. The detail page refreshes while open.

The receiver verification script `node scripts/portal-login-events-smoke.mjs <fictional-event.json>` tests authentication, identity boundaries, event conflicts, concurrent replay and delayed delivery against the running containers. It adds the supplied event and one older fictional event to that test partner's history.

## Checker-approved password reset

As Maker, open an approved partner and select **Ajukan Reset Password**. The request targets the registered project manager email and appears in Checker’s **My tasks**. Checker may approve or reject it. Approval persists a job that asks Portal for a reset link, then sends the link through the configured email REST API. Product Manager can view the request and history.

The current password remains valid until the user finishes Portal’s reset page. Portal owns reset tokens and password changes. The proposed reset expiry is one hour. Backstage stores the reset URL only in its private job table, never in dashboard responses or Riwayat. The user-facing email recipient is fixed from the partner record and confirmed by Portal.

Set `PORTAL_PASSWORD_RESET_URL` to override the reset issuance endpoint. By default it derives `/internal/v1/password-resets` from the registration endpoint. The worker uses the registration service bearer token. Issuance POST carries `referenceId` (password-reset-<request-id>), `registrationReference` (partner-invitation-<partner-id>), `organizationId`, and `email`. It expects `resetId`, `userId`, `status: "PENDING"`, `resetUrl`, `expiresAt`, and `email`. The email payload uses `email`, `templateEmail`, `referenceId`, and `templateCode`, defaulting to `API_PORTAL_PASSWORD_RESET`.

A failed approved request can be retried by Maker or Checker. Retries reuse the saved Portal response and email idempotency key. Before sending and on later polling, Backstage checks Portal’s `GET /internal/v1/password-resets/{referenceId}`. Completed and expired links are not resent. Completion or expiry appears separately in Riwayat. A new reset request requires a new Checker approval. An existing open request blocks duplicates for that partner.
