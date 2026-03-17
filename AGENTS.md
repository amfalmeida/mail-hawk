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

## Architecture

- `config/` - Configuration interfaces (SmallRye Config)
- `model/` - Data models (Invoice, QrCodeContent, etc.)
- `service/` - Business services (MailService, SheetsService, etc.)

## Key Dependencies

- Java 21
- Quarkus 3.19.x
- Jakarta Mail (IMAP)
- Google ZXing (QR codes)
- Apache PDFBox (PDF processing)
- Google Sheets API