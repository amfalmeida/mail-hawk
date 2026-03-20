# Mail Hawk

<p align="center">
  <img src="logo.png" width="100">
</p>

Monitor email for invoice attachments, parse QR codes and store data in Google Sheets + Home Assistant.

**Java 21 + Quarkus Implementation** - Optimized for performance and memory efficiency.

## Features

- **Email Monitoring**: Monitors email via IMAP for invoice attachments
- **QR Code Parsing**: Parses QR codes from PDF and image files (Portuguese ATCUD format)
- **Google Sheets Integration**: Stores invoice data in Google Spreadsheet
- **SQLite Database**: Local database for tracking processed invoices
- **Home Assistant Integration**: SQL sensor for dashboard + add-on support
- **Performance Optimized**: Low memory footprint with Quarkus native compilation support
- **Modern Java**: Uses Java 21 features (records, pattern matching)

## Tech Stack

- **Java 21** - Latest LTS with modern features
- **Quarkus 3.19.x** - Supersonic subatomic Java framework
- **Lombok** - Reduce boilerplate code
- **Jakarta Mail** - IMAP email client
- **ZXing** - QR code processing
- **Apache PDFBox** - PDF rendering
- **Google Sheets API** - Spreadsheet integration
- **SQLite** - Local database

## Quick Start

### Prerequisites

- Java 21+ (JDK 21 or higher)
- Docker (for containerized deployment)

### Build

```bash
# Using Maven wrapper
./mvnw clean package

# Run tests
./mvnw test

# Run in development mode
./mvnw quarkus:dev
```

### Run Locally

```bash
# Set environment variables
export MAIL_HOST="imap.gmail.com"
export MAIL_PORT="993"
export MAIL_USERNAME="your-email@gmail.com"
export MAIL_PASSWORD="your-app-password"
export SHEETS_ID="your-spreadsheet-id"
export SHEETS_ENCODED_CREDENTIALS="base64-encoded-credentials"

# Run with Maven wrapper
./mvnw quarkus:dev
```

## Makefile Commands

```bash
make help              # Show all available commands

# Development
make install           # Install Java dependencies
make build             # Build the project
make run               # Run in development mode
make test              # Run tests
make clean             # Clean build artifacts

# Docker
make docker-build      # Build Docker image
make docker-up         # Run Docker container
make docker-logs       # View Docker logs
make docker-down       # Stop Docker container
make docker-clean      # Remove Docker image and volumes

# Home Assistant Add-on
make addon-build       # Build add-on image
make addon-run         # Build and run add-on
make addon-logs        # View add-on logs

# Setup
make setup             # Create .env from .env.example
```

## Configuration

### Environment Variables

#### Mail Configuration

| Variable | Description | Default |
|----------|-------------|---------|
| `MAIL_HOST` | IMAP server host | `imap.gmail.com` |
| `MAIL_PORT` | IMAP server port | `993` |
| `MAIL_USERNAME` | Email username | - |
| `MAIL_PASSWORD` | Email password or app password | - |
| `MAIL_FOLDER` | Folder to watch | `INBOX` |
| `MAIL_DAYS_OLDER` | Days to look back | `30` |
| `MAIL_SUBJECT_TERMS` | Comma-separated subject search terms | `fatura,factura,extracto,recibo` |
| `MAIL_ONLY_ATTACHMENTS` | Only process emails with attachments | `true` |
| `MAIL_MAX_EMAILS` | Max emails per check (0 = unlimited) | `0` |

#### Google Sheets Configuration

| Variable | Description | Default |
|----------|-------------|---------|
| `SHEETS_ID` | Google Sheets ID | - |
| `SHEETS_SHEET_NAME` | Sheet name | `values` |
| `SHEETS_CONFIG_SHEET` | Config sheet name | `config` |
| `SHEETS_ENCODED_CREDENTIALS` | Base64 encoded service account | - |

#### Application Configuration

| Variable | Description | Default |
|----------|-------------|---------|
| `APP_CHECK_INTERVAL` | Email check interval in seconds | `60` |
| `APP_CONFIG_SYNC_INTERVAL` | Config sync interval in seconds | `300` |
| `APP_DEFAULT_INVOICE_TYPE` | Default invoice type | `other` |

### Docker

```bash
# Build
make docker-build

# Run
make docker-up
```

## Home Assistant Add-on

### Installation

1. Add this repository to your Home Assistant supervisor
2. Install the "Mail Hawk" add-on
3. Configure via the add-on UI
4. Start the add-on

### Configuration via UI

| Option | Description |
|--------|-------------|
| `mail_imap_host` | IMAP server |
| `mail_imap_username` | Email address |
| `mail_imap_password` | App password |
| `mail_subject_terms` | Comma-separated subject filter terms |
| `spreadsheet_id` | Google Sheets ID |
| `google_auth_encoded` | Base64 credentials |

### Home Assistant SQL Sensor

Create a sensor to query the SQLite database:

```yaml
sensor:
  - name: "Invoices This Month"
    platform: sql
    db_url: "sqlite:////share/mail_hawk/mail_hawk.db"
    query: "SELECT COUNT(*) as count FROM processed_invoices WHERE strftime('%Y-%m', invoice_date) = strftime('%Y-%m', 'now')"
    column: "count"
```

## Google Sheets Setup

1. Create a Google Cloud Project
2. Enable the Google Sheets API
3. Create service account credentials
4. Share your spreadsheet with the service account email (as Editor)
5. Base64 encode the credentials:

```bash
base64 -w0 credentials.json
```

Set the result as `SHEETS_ENCODED_CREDENTIALS`.

### Spreadsheet Columns

| Column | Field |
|--------|-------|
| A | Type |
| B | To email |
| C | From email |
| D | Entity |
| E | Invoice Id |
| F | Issuer NIF |
| G | Customer NIF |
| H | Invoice Date |
| I | Invoice Total |
| J | Country |
| K | Invoice type |
| L | Total non taxable |
| M | Stamp duty |
| N | Total Taxes |
| O | Withholding tax |
| P | ATCUD |
| Q | Taxable type |
| R | Tax country region |
| ... | (additional tax columns) |
| AH | Email subject |
| AI | Processed at |

## Project Structure

```
mail-hawk-java/
├── pom.xml                    # Maven build config
├── Makefile                   # Build commands
├── Dockerfile                 # Docker image
├── docker-compose.yml         # Docker compose
├── config.yaml               # Home Assistant add-on config
├── repository.yaml           # HA repository config
├── run.sh                    # Startup script
├── src/main/java/com/amfalmeida/mailhawk/
│   ├── config/                # Configuration interfaces
│   ├── model/                 # Data models
│   ├── service/               # Business services
│   ├── db/                    # Database entities
│   └── health/                # Health checks
└── src/main/resources/
    └── application.properties
```

## License

MIT