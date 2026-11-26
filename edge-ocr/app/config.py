"""Application settings (environment variables + optional YAML file)."""

from __future__ import annotations

import os
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime configuration. Every value can be overridden by an env var."""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore", case_sensitive=False)

    # Service
    service_name: str = "edge-ocr"
    log_level: str = "INFO"

    # Yard / gate identity
    yard_id: str = "11111111-1111-7111-8111-111111111111"
    gate_id: str = "33333333-3333-7333-8333-333333333331"

    # Backend / MQTT
    backend_base_url: str = "http://backend:8080"
    backend_api_key: str = ""
    edge_api_key: str = ""
    mqtt_host: str = "mqtt"
    mqtt_port: int = 1883
    mqtt_topic_template: str = "yard/{yard_id}/gate/{gate_id}/events"
    mqtt_enabled: bool = True

    # Delivery guarantees
    outbox_path: str = "/app/data/outbox.db"
    delivery_max_attempts: int = 0  # 0 = retry forever
    delivery_backoff_seconds: float = 2.0
    delivery_backoff_max_seconds: float = 300.0
    delivery_batch_size: int = 25

    # Models (mounted as a volume, never downloaded at runtime)
    models_dir: str = "/models"
    detector_weights: str = "detector.onnx"
    ocr_model_dir: str = "ocr"
    detector_confidence: float = 0.25
    ocr_min_confidence: float = 0.50
    recognize_confidence_threshold: float = 0.75

    # Preprocessing
    preprocess_clahe_clip_limit: float = 2.0
    preprocess_binarize: bool = True
    preprocess_sharpen: bool = True

    # Consensus / dedup
    consensus_window_seconds: float = 1.5
    consensus_min_agreement: float = 0.6
    dedup_window_seconds: float = 30.0

    # Streams
    stream_default_fps: int = 5
    stream_max_fps: int = 15

    # Evidence / privacy
    evidence_dir: str = "/evidence"
    evidence_retention_days: int = 7
    face_blur_enabled: bool = True
    evidence_max_bytes: int = 5 * 1024 * 1024

    # Upload limits
    max_upload_bytes: int = 12 * 1024 * 1024

    @classmethod
    def from_sources(cls, config_file: str | os.PathLike[str] | None = None) -> Settings:
        """Build settings from environment variables and an optional YAML file.

        Environment variables always win over the YAML file.
        """
        data: dict[str, object] = {}
        path = Path(config_file) if config_file else Path("config.yaml")
        if path.is_file():
            try:
                import yaml  # optional dependency, already in requirements.txt
            except ImportError:  # pragma: no cover - PyYAML is a declared dependency
                yaml = None
            if yaml is not None:
                loaded = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
                if isinstance(loaded, dict):
                    data.update(loaded)
        return cls(**data)  # type: ignore[arg-type]


_settings: Settings | None = None


def get_settings() -> Settings:
    """Return the process wide settings singleton."""
    global _settings
    if _settings is None:
        _settings = Settings.from_sources()
    return _settings


def reset_settings() -> None:
    """Drop the cached settings (used by the tests)."""
    global _settings
    _settings = None
