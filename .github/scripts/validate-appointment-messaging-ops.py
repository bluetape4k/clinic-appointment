#!/usr/bin/env python3
"""Validate the bounded appointment messaging alert/runbook contract."""

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[2]
ALERTS = ROOT / "docs/alerts/appointment-messaging-rules.yml"
RUNBOOK = ROOT / "docs/runbooks/appointment-messaging-operations.md"
REPLAY_RUNBOOK = ROOT / "docs/operations/appointment-consumer-replay-runbook.md"


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    if not ALERTS.is_file():
        fail(f"missing alert rules: {ALERTS}")
    if not RUNBOOK.is_file():
        fail(f"missing runbook: {RUNBOOK}")
    if not REPLAY_RUNBOOK.is_file():
        fail(f"missing replay runbook: {REPLAY_RUNBOOK}")

    alert_text = ALERTS.read_text(encoding="utf-8")
    runbook_text = RUNBOOK.read_text(encoding="utf-8")
    replay_runbook_text = REPLAY_RUNBOOK.read_text(encoding="utf-8")
    allowed_runbooks = {RUNBOOK, REPLAY_RUNBOOK}
    alert_blocks = re.split(r"\n\s{6}- alert:\s*", alert_text)[1:]
    if not alert_blocks:
        fail("no appointment messaging alerts found")

    for block in alert_blocks:
        name = block.splitlines()[0].strip()
        if "runbook:" not in block:
            fail(f"{name} has no runbook reference")
        if "owner:" not in block or "escalation:" not in block:
            fail(f"{name} must declare owner and escalation")
        match = re.search(r"runbook:\s*([^\s#]+)", block)
        if match is None:
            fail(f"{name} has an invalid runbook reference")
        referenced = ROOT / match.group(1)
        if referenced not in allowed_runbooks:
            fail(f"{name} references unexpected runbook: {match.group(1)}")

    required_markers = (
        "Hold and recovery",
        "Redrive and rollback",
        "appointment_outbox_pending",
        "Retry-After",
    )
    missing = [marker for marker in required_markers if marker not in runbook_text]
    if missing:
        fail(f"runbook is missing required markers: {', '.join(missing)}")
    replay_markers = ("AppointmentReplayRequest", "REQUESTED", "retention")
    missing_replay = [marker for marker in replay_markers if marker not in replay_runbook_text]
    if missing_replay:
        fail(f"replay runbook is missing required markers: {', '.join(missing_replay)}")

    print(
        f"appointment messaging ops contract: {len(alert_blocks)} alerts -> "
        f"{RUNBOOK}, {REPLAY_RUNBOOK}"
    )


if __name__ == "__main__":
    main()
