"""
Database configuration.
All values default to localhost for local development.
Override via environment variables for UAT/PROD.

Environment Variables:
    CIRCULAR_DB_HOST     — PostgreSQL host (default: localhost)
    CIRCULAR_DB_PORT     — PostgreSQL port (default: 5432)
    CIRCULAR_DB_NAME     — Database name (default: circular_analyzer)
    CIRCULAR_DB_USER     — Database user (default: postgres)
    CIRCULAR_DB_PASSWORD — Database password (default: postgres)
"""
import os

DB_CONFIG = {
    "host":     os.getenv("CIRCULAR_DB_HOST", "localhost"),
    "port":     int(os.getenv("CIRCULAR_DB_PORT", "5432")),
    "database": os.getenv("CIRCULAR_DB_NAME", "circular_analyser"),
    "user":     os.getenv("CIRCULAR_DB_USER", "postgres"),
    "password": os.getenv("CIRCULAR_DB_PASSWORD", "postgres"),
}
