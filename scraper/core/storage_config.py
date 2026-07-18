"""
Document storage configuration.
Defaults to local ./circular_documents for development.
In production, set STORAGE_BASE_PATH to the NAS mount point.

Environment Variables:
    STORAGE_BASE_PATH  — Root path for document storage (default: ./circular_documents)
    STORAGE_BACKEND    — Storage type: 'local' or 'nas' (default: local)
    STORAGE_NAS_HOST   — NAS hostname (for future NAS health checks)
    STORAGE_NAS_SHARE  — NAS share name
    STORAGE_NAS_USERNAME — NAS auth username
    STORAGE_NAS_PASSWORD — NAS auth password
"""
import os

STORAGE_CONFIG = {
    "base_path":    os.getenv("STORAGE_BASE_PATH", "./circular_documents"),
    "backend":      os.getenv("STORAGE_BACKEND", "local"),
    "nas_host":     os.getenv("STORAGE_NAS_HOST", ""),
    "nas_share":    os.getenv("STORAGE_NAS_SHARE", ""),
    "nas_username": os.getenv("STORAGE_NAS_USERNAME", ""),
    "nas_password": os.getenv("STORAGE_NAS_PASSWORD", ""),
}
