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
- Use Lombok `@Builder` for model classes
- Use `@Transactional` on methods that modify database (must be non-private)
- Follow standard Java naming conventions
- Keep methods focused and under 50 lines
- Use meaningful variable names
- Prefer immutable objects where possible
- Use `@RequiredArgsConstructor(onConstructor_ = @Inject)` for dependency injection

## Architecture

```
src/main/java/com/amfalmeida/mailhawk/
├── client/            # REST clients
│   ├── ActualBudgetClient.java  # Actual Budget HTTP API client
│   └── JsonLoggingFilter.java   # JSON logging/serialization filter
├── config/            # Configuration interfaces (SmallRye Config)
│   ├── ActualConfig.java        # Actual Budget configuration
│   ├── AppConfig.java          # Application configuration
│   ├── MailConfig.java         # Mail configuration (pdfPasswords as List<String>)
│   └── SheetsConfig.java       # Google Sheets configuration
├── dto/               # Data transfer objects
│   ├── TransactionDto.java     # Transaction for Actual Budget
│   └── TransactionImportRequest.java
├── model/             # Data models (use @Builder)
│   ├── Invoice.java            # Invoice with InvoiceContent
│   ├── InvoiceContent.java      # Invoice data (renamed from QrCodeContent)
│   ├── InvoiceType.java        # Invoice type configuration
│   ├── RecurrentBill.java      # Recurrent bill model
│   └── SheetsResult.java
├── service/           # Business services
│   ├── MailService.java         # IMAP email fetching, subject filtering
│   ├── SearchTermBuilder.java   # IMAP search term construction
│   ├── InvoiceProcessor.java    # Invoice processing pipeline
│   ├── RecurrentBillService.java # Recurrent bill processing (scheduled)
│   ├── SheetsService.java      # Google Sheets integration
│   ├── DatabaseService.java    # SQLite/MariaDB operations
│   ├── ActualBudgetService.java # Actual Budget transaction import
│   └── QrCodeParser.java       # QR code and PDF parsing
├── db/                # Panache entities
│   ├── ProcessedInvoice.java
│   └── InvoiceConfig.java
└── health/            # Health checks (SchedulerHealthCheck)
```

## Key Dependencies

- Java 21
- Quarkus 3.19.x
- Jakarta Mail (IMAP)
- Google ZXing (QR codes)
- Apache PDFBox (PDF processing, supports encrypted PDFs with passwords)
- Google Sheets API
- Actual Budget HTTP API
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

### Minimum Attachment Size

Server-side filtering to skip small messages (likely without attachments):

```properties
# application.properties
mail.min-attachment-size=10240

# .env
MAIL_MIN_ATTACHMENT_SIZE=10240
```

**How it works:**
1. Server-side: SizeTerm filter combined with date filter (messages >= size)
2. Set to 0 to disable (default)
3. Client-side `hasAttachments()` check still runs as safety net
4. Useful for IMAP servers that support SIZE search criterion

### PDF Passwords

PDF passwords are configured as comma-separated values (parsed automatically by Quarkus):

```properties
# application.properties
mail.pdf-passwords=password1,password2,password3

# .env
MAIL_PDF_PASSWORDS=password1,password2,password3
```

**How it works:**
1. Try opening PDF without password
2. If `InvalidPasswordException` is thrown, try each configured password
3. Log outcome without exposing passwords

### Recurrent Bills

Recurrent bills are read from a Google Sheets tab (configurable):

```properties
# application.properties
sheets.recurrent-sheet=recurrent
app.recurrent-check-interval=360

# .env
SHEETS_RECURRENT_SHEET=recurrent
APP_RECURRENT_CHECK_INTERVAL=360
```

**Sheet columns:** Type, Local, Entity Email, Entity Name, NIF, Customer NIF, Value, Tax (ignored), Payment day, Until (optional), Comments

**Processing:**
1. Scheduled job runs at configurable interval (default: 360 seconds)
2. Checks if payment day matches current day
3. Validates "Until" date (optional, empty = forever)
4. Creates invoice with ID: `REC-{entityName}-{YYYY-MM}`
5. Stores in same `processed_invoices` table as regular invoices

### Database

- SQLite (default): `DB_TYPE=sqlite`, `DB_URL=jdbc:sqlite:/data/mail_hawk.db`

### Actual Budget Integration

Requires [ha-actual-http-api](https://github.com/amfalmeida/ha-actual-http-api) running alongside Actual Budget.

```properties
# Enable integration
ACTUAL_ENABLED=true
ACTUAL_URL=http://your-server:5007
ACTUAL_API_KEY=your-api-key
ACTUAL_BUDGET_SYNC_ID=your-budget-sync-id
ACTUAL_ACCOUNT_ID=your-account-id
```

**Transaction fields:**
- `account` - from config
- `date` - invoice date from InvoiceContent
- `amount` - total * 100 (negative)
- `payee_name` - invoice type/entity
- `notes` - invoice filename
- `imported_id` - ATCUD for deduplication
- `cleared` - true

### Google Sheets

Sheet names are configurable and support spaces:

```properties
sheets.sheet-name=Bills values
sheets.config-sheet=config
sheets.recurrent-sheet=recurrent
```

## Model Classes

All model classes use Lombok `@Builder` and `@Data`:

```java
Invoice invoice = Invoice.builder()
    .id(invoiceId)
    .subject("Recurrent: " + entityName)
    .date(LocalDate.now())
    .invoiceContent(invoiceContent)
    .invoiceType(invoiceType)
    .build();

InvoiceContent content = InvoiceContent.builder()
    .invoiceId(id)
    .issuerTin(tin)
    .total(amount)
    .build();
```

## Docker

Full stack with docker-compose.yml includes:
- mail-hawk (port 8080)
- actual-http-api (port 5007)
- actual-budget (port 5006)

```bash
docker-compose up -d
```

## Important Notes

1. **Lombok + Quarkus**: Use `@RequiredArgsConstructor(onConstructor_ = @Inject)` for constructor injection
2. **ConfigMapping defaults**: All fields need `@WithDefault("")` or app fails without env vars
3. **Panache entities**: Use `@Transactional` on **non-private** methods that modify data
4. **IMAP search**: Date filter works reliably; size filter (`SizeTerm`) is available but may not work on all servers; filter subjects in Java
5. **REST Client JSON**: Uses `JsonLoggingFilter` with Jackson ObjectMapper for proper serialization
6. **InvoiceContent**: Previously named `QrCodeContent`, renamed to better reflect its purpose
7. **Model naming**: Use `invoiceContent` variable name (not `qrCode` or `qr`)