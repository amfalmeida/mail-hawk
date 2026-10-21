# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

@AGENTS.md

AGENTS.md (imported above) holds the code style rules, the package layout, the configuration reference and the
Quarkus/Lombok gotchas. This file adds commands and the architecture notes that AGENTS.md doesn't cover.

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

**`InvoiceResource`** (`POST /api/invoices`, package `api`) is the REST entry point. It takes JSON (`InvoiceRequest`:
`date`, `entityName`, `raw`), parses `raw` with `QrCodeParser.parseQrCodeString` and calls
`InvoiceProcessor.storeInvoice(invoice, entityName)`. A non-blank `entityName` replaces the resolved type's name. The
request `date` is the invoice's `date`, and the invoice date when the QR has no `F` field. The API key (`X-API-Key`
header) is checked by `ApiKeyFilter`, a `ContainerRequestFilter` bound through the `@ApiKeyRequired` name binding.
Put that annotation on any new API resource. Endpoints answer 403 until `app.api-enabled` is true and `app.api-key` is set. The filter is
`@ApplicationScoped` so it is built lazily: JAX-RS providers are otherwise created during static init, when
`AppConfig` can't be injected yet (`SRCFG00027`).

Ports: `/q/health/*` runs on the Quarkus management interface (`quarkus.management.port=8080`, fixed because the HA
watchdog URL is `[PORT:8080]/q/health/live`). The REST API runs on the main HTTP server, `quarkus.http.port` =
`app.api-port` (default 8081, add-on option `api_port`). `run.sh` falls back to 8081 if `api_port` is set to 8080.

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
  processed and import it into Actual Budget. The already-seen check, type resolution and this step live in
  `storeInvoice`, which the REST API also uses. It returns an `InvoiceResult`.
- The temp attachment file is always deleted in `finally`.

Persistence layers:
- **Google Sheets** is the source of truth. It stores invoice rows, type configs and recurrent bills, and
  `SheetsService.checkExisting` does the final duplicate check.
- **`ProcessedInvoiceCache`** is a Caffeine cache of SHA-256(raw QR) → processed-at. Entries expire after
  `app.processed-invoice-cache-hours` (default 24), and the size limit is `app.processed-invoice-cache-size`.
  Recurrent bills use the key `RECURRENT:<REC-id>`. The cache is memory-only, so after expiry or a restart the
  Sheets duplicate check is the only safeguard.
- There is no database and no web UI. Only `/q/health/*` (port 8080) and `POST /api/invoices` (`app.api-port`) are served.

## Deployment

The app ships as a Home Assistant add-on:
- `config.yaml` is the add-on manifest. Its `version` drives the Docker image tag and release in
  `.github/workflows/build.yaml` (triggered by `v*` tags).
- `run.sh` maps HA `/data/options.json` keys (e.g. `mail_imap_host`) to the env vars Quarkus reads (e.g. `MAIL_HOST`).
- When you add a config option, update `application.properties`, the matching `*Config` interface (with `@WithDefault`),
  `run.sh`, `config.yaml` (`options` + `schema`) and the README tables.
