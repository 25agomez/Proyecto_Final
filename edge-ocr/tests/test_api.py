"""HTTP API tests (the service contract of the edge)."""

from __future__ import annotations


def test_healthz(client) -> None:
    """The liveness probe answers with the state of the models and the queue."""
    response = client.get("/healthz")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["service"] == "edge-ocr"
    assert "modelsAvailable" in body
    assert body["outboxPending"] == 0


def test_recognize_returns_the_event(client, sample_image_bytes) -> None:
    """An uploaded image produces the reading as JSON."""
    response = client.post(
        "/v1/recognize",
        files={"image": ("frame.png", sample_image_bytes, "image/png")},
    )
    assert response.status_code == 200
    body = response.json()
    assert len(body["events"]) == 1
    event = body["events"][0]
    assert event["kind"] == "CONTAINER"
    assert event["normalizedText"] == "CSQU3054383"
    assert event["isoValid"] is True
    assert event["corrected"] is False
    assert event["eventId"]
    assert body["latencyMs"] >= 0


def test_recognize_rejects_a_file_that_is_not_an_image(client) -> None:
    """A corrupt upload is answered with 400."""
    response = client.post(
        "/v1/recognize",
        files={"image": ("frame.png", b"not-an-image", "image/png")},
    )
    assert response.status_code == 400


def test_metrics_are_exposed(client, sample_image_bytes) -> None:
    """The Prometheus endpoint reports the processed frames."""
    client.post("/v1/recognize", files={"image": ("frame.png", sample_image_bytes, "image/png")})
    response = client.get("/metrics")
    assert response.status_code == 200
    assert "edge_ocr_frames_processed_total" in response.text
    assert "edge_ocr_events_published_total" in response.text


def test_stream_lifecycle(client) -> None:
    """A stream is registered, listed and removed."""
    response = client.post(
        "/v1/streams",
        json={"url": "invalid://camera", "gateId": "gate-1", "fps": 3},
    )
    assert response.status_code == 201
    stream_id = response.json()["streamId"]

    listing = client.get("/v1/streams")
    assert listing.status_code == 200
    assert any(item["streamId"] == stream_id for item in listing.json())

    deleted = client.delete(f"/v1/streams/{stream_id}")
    assert deleted.status_code == 204

    missing = client.delete(f"/v1/streams/{stream_id}")
    assert missing.status_code == 404
