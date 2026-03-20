#!/bin/bash

echo "Loading configuration..."

get_config() {
    local key="$1"
    local env_var="$2"
    
    if [ -f /data/options.json ]; then
        local value
        value=$(jq -r ".${key} // empty" /data/options.json 2>/dev/null)
        if [ -n "$value" ] && [ "$value" != "null" ]; then
            echo "$value"
            return
        fi
    fi
    
    echo "${!env_var}"
}

export MAIL_HOST="$(get_config 'mail_imap_host' 'MAIL_HOST')"
export MAIL_PORT="$(get_config 'mail_imap_port' 'MAIL_PORT')"
export MAIL_USERNAME="$(get_config 'mail_imap_username' 'MAIL_USERNAME')"
export MAIL_PASSWORD="$(get_config 'mail_imap_password' 'MAIL_PASSWORD')"
export MAIL_FOLDER="$(get_config 'mail_imap_folder' 'MAIL_FOLDER')"
export MAIL_DAYS_OLDER="$(get_config 'mail_imap_days_older' 'MAIL_DAYS_OLDER')"
export MAIL_SUBJECT_TERMS="$(get_config 'mail_subject_terms' 'MAIL_SUBJECT_TERMS')"
export MAIL_ONLY_ATTACHMENTS="$(get_config 'mail_listener_only_attachments' 'MAIL_ONLY_ATTACHMENTS')"
export MAIL_MAX_EMAILS="$(get_config 'mail_listener_max_emails' 'MAIL_MAX_EMAILS')"
export MAIL_PDF_PASSWORDS="$(get_config 'pdf_passwords' 'MAIL_PDF_PASSWORDS')"
export APP_CHECK_INTERVAL="$(get_config 'check_interval' 'APP_CHECK_INTERVAL')"
export APP_CONFIG_SYNC_INTERVAL="$(get_config 'config_sync_interval' 'APP_CONFIG_SYNC_INTERVAL')"
export APP_DEFAULT_INVOICE_TYPE="$(get_config 'invoice_type_default' 'APP_DEFAULT_INVOICE_TYPE')"
export SHEETS_ID="$(get_config 'spreadsheet_id' 'SHEETS_ID')"
export SHEETS_SHEET_NAME="$(get_config 'spreadsheet_sheet' 'SHEETS_SHEET_NAME')"
export SHEETS_CONFIG_SHEET="$(get_config 'spreadsheet_sheet_db' 'SHEETS_CONFIG_SHEET')"
export SHEETS_ENCODED_CREDENTIALS="$(get_config 'google_auth_encoded' 'SHEETS_ENCODED_CREDENTIALS')"

# Database Configuration
export DB_TYPE="$(get_config 'db_type' 'DB_TYPE')"
export DB_URL="$(get_config 'db_url' 'DB_URL')"
export DB_USERNAME="$(get_config 'db_username' 'DB_USERNAME')"
export DB_PASSWORD="$(get_config 'db_password' 'DB_PASSWORD')"

# SQLite-specific: ensure directory exists
if [ "$DB_TYPE" = "sqlite" ] || [ -z "$DB_TYPE" ]; then
    DB_PATH="${DB_URL#jdbc:sqlite:}"
    DB_DIR=$(dirname "$DB_PATH")
    if [ ! -d "$DB_DIR" ]; then
        echo "Creating database directory: $DB_DIR"
        mkdir -p "$DB_DIR"
    fi
fi

echo "Starting Mail Hawk..."
exec java -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 \
    -XX:+UseStringDeduplication -XX:+UseCompressedOops \
    -jar /app/quarkus-run.jar