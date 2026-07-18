"""
Document Storage Module
=======================
Configurable storage backend for circular documents.
Stores files in a date-wise layout: {base_path}/{SOURCE_CODE}/{YYYY}/{MM}/{DD}/

Currently uses local filesystem. When NAS is available:
    1. Mount NAS at a local path (e.g. /mnt/nas/circulars)
    2. Set STORAGE_BASE_PATH to the mount point
    3. Set STORAGE_BACKEND=nas
    Zero code changes required.
"""
from __future__ import annotations

import json
import logging
import shutil
from abc import ABC, abstractmethod
from datetime import datetime
from pathlib import Path

from .storage_config import STORAGE_CONFIG

logger = logging.getLogger(__name__)


class StorageBackend(ABC):
    """
    Abstract document storage backend.
    Concrete implementations: LocalStorage (now), NASStorage (future).
    All store documents in a date-wise layout per source:
        {base_path}/{SOURCE_CODE}/{YYYY}/{MM}/{DD}/{filename}
    """

    @abstractmethod
    def save_json(self, source_code: str, data: list | dict,
                  date: datetime, filename: str) -> str:
        """Save JSON data. Returns the relative storage path."""
        ...

    @abstractmethod
    def save_file(self, source_code: str, content: bytes,
                  date: datetime, filename: str) -> str:
        """Save binary file (PDF, etc.). Returns the relative storage path."""
        ...

    def save_document(self, source_code: str, circular_id: str, content: bytes,
                      date: datetime, filename: str) -> str:
        """
        Save a circular document in the v3 circular-keyed layout:
            {SOURCE_CODE}/{YYYY}/{MM}/{circular_id}/{filename}

        This is the path recorded in raw_circular_document.nas_relative_path
        and resolved later by the circular-processor (AI prompt) and the
        backend (UI download).
        """
        raise NotImplementedError

    @abstractmethod
    def get_full_path(self, relative_path: str) -> str:
        """Resolve a relative path to the full absolute path."""
        ...

    @abstractmethod
    def exists(self, relative_path: str) -> bool:
        """Check if a file exists at the relative path."""
        ...

    @abstractmethod
    def list_files(self, source_code: str, date: datetime) -> list[str]:
        """List all files for a source on a given date."""
        ...

    def _build_date_path(self, source_code: str, date: datetime) -> str:
        """
        Build the date-wise directory path.
        Format: {SOURCE_CODE}/{YYYY}/{MM}/{DD}/
        """
        return (
            f"{source_code.upper()}"
            f"/{date.strftime('%Y')}"
            f"/{date.strftime('%m')}"
            f"/{date.strftime('%d')}"
        )


class LocalStorage(StorageBackend):
    """
    Stores documents on local filesystem.

    Layout:
        {base_path}/{SOURCE_CODE}/{YYYY}/{MM}/{DD}/{filename}

    Example:
        ./circular_documents/NSE/2026/05/25/nse_circulars_25052026_26052026.json
        ./circular_documents/BSE/2026/05/25/bse_circulars_25052026_26052026.json
    """

    def __init__(self, base_path: str | None = None):
        self.base_path = Path(base_path or STORAGE_CONFIG["base_path"])
        self.base_path.mkdir(parents=True, exist_ok=True)
        logger.info("[Storage] LocalStorage initialized at: %s", self.base_path.resolve())

    def save_json(self, source_code: str, data: list | dict,
                  date: datetime, filename: str) -> str:
        """
        Save JSON data to a date-wise directory.

        Args:
            source_code: Source identifier (e.g. "NSE").
            data:        JSON-serializable data.
            date:        Date for directory placement.
            filename:    Output filename.

        Returns:
            Relative storage path (e.g. "NSE/2026/05/25/nse_circulars_xxx.json").
        """
        date_dir = self._build_date_path(source_code, date)
        full_dir = self.base_path / date_dir
        full_dir.mkdir(parents=True, exist_ok=True)

        file_path = full_dir / filename
        try:
            with open(file_path, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2, ensure_ascii=False, default=str)

            relative_path = f"{date_dir}/{filename}"
            record_count = len(data) if isinstance(data, list) else 1
            logger.info(
                "[Storage] Saved JSON: %s (%d records, %.1f KB)",
                file_path, record_count, file_path.stat().st_size / 1024,
            )
            return relative_path

        except (OSError, IOError) as e:
            logger.error("[Storage] Failed to save JSON to %s: %s", file_path, e)
            raise

    def save_file(self, source_code: str, content: bytes,
                  date: datetime, filename: str) -> str:
        """
        Save binary file (PDF, ZIP, etc.) to a date-wise directory.

        Args:
            source_code: Source identifier.
            content:     Raw bytes to write.
            date:        Date for directory placement.
            filename:    Output filename.

        Returns:
            Relative storage path.
        """
        date_dir = self._build_date_path(source_code, date)
        full_dir = self.base_path / date_dir
        full_dir.mkdir(parents=True, exist_ok=True)

        file_path = full_dir / filename
        try:
            with open(file_path, "wb") as f:
                f.write(content)

            relative_path = f"{date_dir}/{filename}"
            logger.info(
                "[Storage] Saved file: %s (%.1f KB)",
                file_path, len(content) / 1024,
            )
            return relative_path

        except (OSError, IOError) as e:
            logger.error("[Storage] Failed to save file to %s: %s", file_path, e)
            raise

    def save_document(self, source_code: str, circular_id: str, content: bytes,
                      date: datetime, filename: str) -> str:
        """Save a document under {SOURCE}/{YYYY}/{MM}/{circular_id}/{filename}."""
        rel_dir = (
            f"{source_code.upper()}"
            f"/{date.strftime('%Y')}"
            f"/{date.strftime('%m')}"
            f"/{circular_id}"
        )
        full_dir = self.base_path / rel_dir
        full_dir.mkdir(parents=True, exist_ok=True)

        file_path = full_dir / filename
        try:
            with open(file_path, "wb") as f:
                f.write(content)
            relative_path = f"{rel_dir}/{filename}"
            logger.info(
                "[Storage] Saved document: %s (%.1f KB)",
                file_path, len(content) / 1024,
            )
            return relative_path
        except (OSError, IOError) as e:
            logger.error("[Storage] Failed to save document to %s: %s", file_path, e)
            raise

    def get_full_path(self, relative_path: str) -> str:
        """Resolve a relative path to the full absolute path."""
        return str((self.base_path / relative_path).resolve())

    def exists(self, relative_path: str) -> bool:
        """Check if a file exists at the relative path."""
        return (self.base_path / relative_path).exists()

    def list_files(self, source_code: str, date: datetime) -> list[str]:
        """
        List all files for a source on a given date.

        Returns:
            List of relative paths.
        """
        date_dir = self._build_date_path(source_code, date)
        full_dir = self.base_path / date_dir

        if not full_dir.exists():
            return []

        files = []
        for item in full_dir.iterdir():
            if item.is_file():
                files.append(f"{date_dir}/{item.name}")

        return sorted(files)

    def get_storage_info(self) -> dict:
        """Return storage usage information."""
        total_size = 0
        file_count = 0
        for f in self.base_path.rglob("*"):
            if f.is_file():
                total_size += f.stat().st_size
                file_count += 1

        return {
            "backend": "local",
            "base_path": str(self.base_path.resolve()),
            "total_files": file_count,
            "total_size_mb": round(total_size / (1024 * 1024), 2),
        }


class NASStorage(LocalStorage):
    """
    Stores documents on NAS mount.
    Inherits all storage logic from LocalStorage — the NAS is just a mounted path.
    Only difference: mount validation and health checks.

    When NAS is ready:
        1. Mount NAS at /mnt/nas/circulars (Linux) or N:\\circulars (Windows)
        2. Set STORAGE_BASE_PATH=/mnt/nas/circulars
        3. Set STORAGE_BACKEND=nas
        That's it. Same code, same layout, different mount point.
    """

    def __init__(self, base_path: str | None = None):
        resolved_path = Path(base_path or STORAGE_CONFIG["base_path"])
        self._validate_mount(resolved_path)
        super().__init__(str(resolved_path))
        logger.info("[Storage] NASStorage initialized at: %s", resolved_path.resolve())

    def _validate_mount(self, path: Path) -> None:
        """
        Verify NAS is mounted and writable.

        Raises:
            RuntimeError: If mount is missing or not writable.
        """
        if not path.exists():
            raise RuntimeError(
                f"NAS mount not found at: {path}. "
                f"Ensure the NAS share is mounted before starting."
            )

        # Writability check
        test_file = path / ".storage_write_test"
        try:
            test_file.write_text("ok", encoding="utf-8")
            test_file.unlink()
        except OSError as e:
            raise RuntimeError(
                f"NAS mount at {path} is not writable: {e}. "
                f"Check mount permissions."
            )

        logger.info("[Storage] NAS mount validated: %s (writable)", path)

    def get_storage_info(self) -> dict:
        """Return storage usage information with NAS details."""
        info = super().get_storage_info()
        info["backend"] = "nas"

        # Add disk usage info if available
        try:
            usage = shutil.disk_usage(str(self.base_path))
            info["disk_total_gb"] = round(usage.total / (1024 ** 3), 2)
            info["disk_used_gb"] = round(usage.used / (1024 ** 3), 2)
            info["disk_free_gb"] = round(usage.free / (1024 ** 3), 2)
            info["disk_usage_pct"] = round(usage.used / usage.total * 100, 1)
        except Exception:
            pass

        return info


def get_storage_backend(config: dict | None = None) -> StorageBackend:
    """
    Factory: returns the correct storage backend based on config.
    Switching from local to NAS = change one env var.

    Args:
        config: Override storage config. Uses STORAGE_CONFIG if None.

    Returns:
        Configured StorageBackend instance.
    """
    cfg = config or STORAGE_CONFIG
    backend_type = cfg.get("backend", "local").lower()

    if backend_type == "nas":
        logger.info("[Storage] Using NAS storage backend")
        return NASStorage(cfg.get("base_path"))
    else:
        logger.info("[Storage] Using local storage backend")
        return LocalStorage(cfg.get("base_path"))
