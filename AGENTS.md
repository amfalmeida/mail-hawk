# AGENTS.md

## Build & Test Commands

```bash
# Build the project
./mvnw clean package

# Run in development mode
./mvnw quarkus:dev

# Run tests
./mvnw test

# Build Docker image
docker build -t mail-hawk-java .
```

## Code Style

- Use Java 21 features (records, pattern matching)
- Follow standard Java naming conventions
- Keep methods focused and under 50 lines
- Use meaningful variable names
- Prefer immutable objects where possible
- Use Lombok with `@RequiredArgsConstructor(onConstructor_ = @Inject)` for dependency injection

## Architecture

```
src/main/java/com/amfalmeida/mailhawk/
├── config/          # Configuration interfaces (SmallRye Config)
├── model/            # Data models (Invoice, QrCodeContent, etc.)
├── service/          # Business services
│   ├── MailService.java        # IMAP email fetching, subject filtering
│   ├── SearchTermBuilder.java  # IMAP search term construction
│   ├── InvoiceProcessor.java   # Invoice processing pipeline
│   ├── SheetsService.java      # Google Sheets integration
│   ├── DatabaseService.java    # SQLite/MariaDB operations
│   └── QrCodeParser.java        # QR code parsing
├── db/               # Panache entities (ProcessedInvoice, InvoiceConfig)
└── health/           # Health checks (SchedulerHealthCheck)
```

## Key Dependencies

- Java 21
- Quarkus 3.19.x
- Jakarta Mail (IMAP)
- Google ZXing (QR codes)
- Apache PDFBox (PDF processing)
- Google Sheets API
- Lombok

## Configuration

### Subject Filter

Email subject filtering uses **comma-separated terms** (not regex):

```properties
# application.properties
mail.subject-terms=fatura,factura,extracto,recibo

# .env
MAIL_SUBJECT_TERMS=fatura,factura,extracto,recibo
```

**How it works:**
1. Server-side: Date filter (ReceivedDateTerm)
2. Client-side: Case-insensitive contains check for each term

### Database

- SQLite (default): `DB_TYPE=sqlite`, `DB_URL=jdbc:sqlite:/data/mail_hawk.db`

## Docker

Single container with Java runtime:

```bash
make docker-build
make docker-up
```

**Ports:**
- 8080: Quarkus application

## Important Notes

1. **Lombok + Quarkus**: Use `@RequiredArgsConstructor(onConstructor_ = @Inject)` for constructor injection
2. **ConfigMapping defaults**: All fields need `@WithDefault("")` or app fails without env vars
3. **Panache entities**: Use `@Transactional` on methods that modify data
4. **IMAP search**: Only date filter works reliably on all servers; filter subjects in Java