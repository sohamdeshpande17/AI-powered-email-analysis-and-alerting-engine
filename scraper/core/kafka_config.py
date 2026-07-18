"""
Kafka configuration.
All values default to localhost for local development.
Override via environment variables for UAT/PROD.

Environment Variables:
    KAFKA_BOOTSTRAP_SERVERS  — Comma-separated broker list (default: localhost:9092)
    KAFKA_SECURITY_PROTOCOL  — Security protocol (default: PLAINTEXT)
    KAFKA_SASL_MECHANISM     — SASL mechanism if using SASL auth (default: None)
    KAFKA_SASL_USERNAME      — SASL username (default: None)
    KAFKA_SASL_PASSWORD      — SASL password (default: None)
    KAFKA_DLQ_TOPIC          — Dead-letter queue topic (default: circular.cdc.dlq)
"""
import os

KAFKA_CONFIG = {
    "bootstrap_servers": os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
    "security_protocol": os.getenv("KAFKA_SECURITY_PROTOCOL", "PLAINTEXT"),
    "sasl_mechanism":    os.getenv("KAFKA_SASL_MECHANISM"),
    "sasl_username":     os.getenv("KAFKA_SASL_USERNAME"),
    "sasl_password":     os.getenv("KAFKA_SASL_PASSWORD"),
    "dlq_topic":         os.getenv("KAFKA_DLQ_TOPIC", "circular.cdc.dlq"),
}
