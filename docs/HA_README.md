# Mail Hawk - Home Assistant Add-on

Monitor email for invoice attachments, parse QR codes and store in database.

## Installation

1. Add the repository URL to Home Assistant Supervisor
2. Click "Install" on the Mail Hawk Java add-on
3. Configure the add-on options
4. Start the add-on

## Configuration

### Required Settings

| Option | Description |
|--------|-------------|
| `mail_imap_username` | Your email address |
| `mail_imap_password` | App password (for Gmail, create one in Account Settings) |
| `spreadsheet_id` | Google Sheets ID (from the URL) |
| `google_auth_encoded` | Base64-encoded service account JSON |

### Optional Settings

| Option | Description | Default |
|--------|-------------|---------|
| `mail_imap_host` | IMAP server | `imap.gmail.com` |
| `mail_imap_port` | IMAP port | `993` |
| `mail_imap_folder` | Email folder to monitor | `INBOX` |
| `mail_imap_days_older` | Days to look back | `30` |
| `check_interval` | Check interval in seconds | `60` |
| `invoice_type_default` | Default invoice type | `other` |
| `pdf_passwords` | PDF passwords (comma-separated) | - |

## Gmail Setup

1. Enable 2-Factor Authentication on your Google account
2. Go to Account Settings > Security > App Passwords
3. Generate a new app password
4. Use this password in `mail_imap_password`

## Google Sheets Setup

1. Create a Google Cloud Project
2. Enable Google Sheets API
3. Create a Service Account
4. Download the JSON credentials
5. Base64 encode: `base64 -w0 credentials.json`
6. Paste the result in `google_auth_encoded`
7. Share your spreadsheet with the service account email (Editor access)

## Support

For issues and feature requests, please use the GitHub issue tracker.