# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

@AGENTS.md

AGENTS.md (imported above) holds the code style rules, the configuration reference and the Quarkus/Lombok gotchas.
This file adds commands and architecture notes that AGENTS.md doesn't cover. Where the two disagree, trust this file.

## Commands

```bash
./mvnw test                                             # all tests (JUnit 5 + Mockito, no @QuarkusTest)
./mvnw test -Dtest=InvoiceProcessorTest                 # single test class
./mvnw test -Dtest=InvoiceProcessorTest#methodName      # single test method
./mvnw checkstyle:check                                 # style check (not bound to a lifecycle phase)
./mvnw clean package -DskipTests                        # what `make build` runs
./mvnw quarkus:dev                                      # dev mode; needs MAIL_*/SHEETS_* env vars (see .env.example)
```

Checkstyle (`src/main/resources/checkstyle.xml`) enforces `FinalParameters`, `NeedBraces`, a 120-character
`LineLength` limit and a trailing newline. Run it before you finish a change.

## Architecture

The app runs three independent `@Scheduled` jobs, all with `concurrentExecution = SKIP`:

1. **`EmailService.checkEmails`** (`app.check-interval`) runs the email pipeline:
   `EmailProcessor` → `EmailClient` (IMAP connect, date/size server-side search, client-side subject-term filter,
   attachment extraction to a temp file) → `InvoiceProcessor.processInvoice` for each invoice.
   - `EmailProcessor` dedupes messages across runs with a Caffeine cache keyed by Message-ID
     (`mail.message-cache-size`, `mail.message-cache-expire-seconds`).
   - Each message gets a random correlation ID in MDC via `com.amfalmeida.common.MdcSetter` (`%X{id}` in the log format).
   - The last check time is saved only when the check succeeds (`EmailProcessor` returns an empty `Optional` on
     connection or search failure). That way a failed run never moves the search window forward.
   - The search starts from the last check time, which `EmailService` keeps in a Caffeine cache that expires after
     `app.last-checked-cache-hours` (default 24). On the first run, or once that entry has expired, it falls back to
     `mail.days-older`.
2. **`InvoiceProcessor.syncConfigs`** (`app.config-sync-interval`) calls `SheetsService.refreshConfigurations`,
   which reloads the NIF/email → `InvoiceType` Caffeine caches from the Sheets `config` tab. Entries expire after
   `app.config-cache-hours` (default 1). Lookups read only from the caches. They reload from the sheet only when
   both caches are empty, so an unknown NIF does not cause a Sheets call. Email keys are lowercased.
3. **`RecurrentBillService`** (`app.recurrent-check-interval`) reads the `recurrent` tab and creates synthetic invoices.
   It uses the same rule as email invoices: Actual import and the processed mark happen only if the Sheets write didn't
   fail. Actual's `imported_id` is the ATCUD, or the raw QR string when the ATCUD is missing or `"0"`.

Health: `SchedulerHealthCheck` (liveness, used by the HA watchdog) only checks the scheduler.
`MailConnectionHealthCheck` (readiness) checks IMAP, which is DOWN until the first email check connects. Keep IMAP
checks out of liveness, or an IMAP outage will make Home Assistant restart the add-on.

`InvoiceProcessor.processInvoice` steps:
- `QrCodeParser` pulls QR strings from the PDF or image. For PDFs it first decodes the page's embedded image objects
  with ZXing, which is fast. Only if none contain a QR does it render every page in grayscale (250 → 400 DPI, with a
  contrast-stretch retry). Rendering is the expensive part on long PDFs. Configured PDF passwords are tried on
  encrypted files.
- Each string is parsed as a Portuguese AT QR code (`A:`…`*` fields) into `InvoiceContent`.
- Invoices already seen are skipped (keyed by raw QR hash).
- The `InvoiceType` is resolved: issuer NIF → sender email → `app.default-invoice-type`.
- The row is appended to Sheets. Only when the result is `APPENDED` or `ALREADY_EXISTS` does it mark the invoice
  processed and import it into Actual Budget.
- The temp attachment file is always deleted in `finally`.

Persistence layers:
- **Google Sheets** is the source of truth. It stores invoice rows, type configs and recurrent bills, and
  `SheetsService.checkExisting` does the final duplicate check.
- **`ProcessedInvoiceCache`** is a Caffeine cache of SHA-256(raw QR) → processed-at. Entries expire after
  `app.processed-invoice-cache-hours` (default 24), and the size limit is `app.processed-invoice-cache-size`.
  Recurrent bills use the key `RECURRENT:<REC-id>`. The cache is memory-only, so after expiry or a restart the
  Sheets duplicate check is the only safeguard.
- No database is used. The Hibernate/Panache/Flyway/SQLite stack was removed. The README and AGENTS.md sections
  on SQLite/MariaDB, `DB_*` vars, `@Transactional`/Panache and the dashboard are stale.

Package layout differs from the AGENTS.md tree:
- Models live in `email/model/`.
- REST DTOs live in `client/dto/`.
- `resource/DashboardResource` and the Qute dashboard template were removed.

## Deployment

The app ships as a Home Assistant add-on:
- `config.yaml` is the add-on manifest. Its `version` drives the Docker image tag and release in
  `.github/workflows/build.yaml` (triggered by `v*` tags).
- `run.sh` maps HA `/data/options.json` keys (e.g. `mail_imap_host`) to the env vars Quarkus reads (e.g. `MAIL_HOST`).
- When you add a config option, update `application.properties`, the matching `*Config` interface (with `@WithDefault`),
  `run.sh`, `config.yaml` (`options` + `schema`) and the README tables.
