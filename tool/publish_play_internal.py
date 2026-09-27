#!/usr/bin/env python3
"""Upload one phone AAB to Trailscape's Google Play internal test track.

Requires PLAY_SERVICE_ACCOUNT_JSON and the Android Publisher API. The service
account should only have the app-level permission to release to test tracks.
"""

import argparse
import json
import os
from pathlib import Path

from google.auth.transport.requests import AuthorizedSession
from google.oauth2 import service_account


PACKAGE = "de.robinrehbein.trailscape"
API = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}"
UPLOAD_API = f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PACKAGE}"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"


def request(session, method, url, **kwargs):
    response = session.request(method, url, timeout=180, **kwargs)
    if not response.ok:
        raise RuntimeError(f"Google Play API {method} failed ({response.status_code}): {response.text[:1200]}")
    return response.json() if response.content else {}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--run-number", type=int, required=True)
    parser.add_argument("--notes", required=True)
    args = parser.parse_args()

    if not args.bundle.is_file():
        parser.error(f"Bundle missing: {args.bundle}")
    if args.run_number <= 0:
        parser.error("--run-number must be positive")
    raw_credentials = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "")
    if not raw_credentials:
        parser.error("PLAY_SERVICE_ACCOUNT_JSON is missing")

    credentials = service_account.Credentials.from_service_account_info(
        json.loads(raw_credentials), scopes=[SCOPE]
    )
    session = AuthorizedSession(credentials)
    expected_code = args.run_number + 2000
    edit = request(session, "POST", f"{API}/edits", json={})
    edit_id = edit["id"]

    try:
        current = request(session, "GET", f"{API}/edits/{edit_id}/tracks/internal")
        current_codes = [
            int(code)
            for release in current.get("releases", [])
            for code in release.get("versionCodes", [])
        ]
        if current_codes and max(current_codes) >= expected_code:
            print(f"Internal track already has versionCode {max(current_codes)}; skipping {expected_code}.")
            return

        with args.bundle.open("rb") as bundle:
            uploaded = request(
                session,
                "POST",
                f"{UPLOAD_API}/edits/{edit_id}/bundles?uploadType=media",
                data=bundle,
                headers={"Content-Type": "application/octet-stream"},
            )
        actual_code = int(uploaded["versionCode"])
        if actual_code != expected_code:
            raise RuntimeError(f"Bundle versionCode {actual_code} differs from expected {expected_code}")

        release = {
            "track": "internal",
            "releases": [{
                "name": f"Trailscape 2.0.{args.run_number}",
                "versionCodes": [str(actual_code)],
                "status": "completed",
                "releaseNotes": [{"language": "de-DE", "text": args.notes[:500]}],
            }],
        }
        request(session, "PUT", f"{API}/edits/{edit_id}/tracks/internal", json=release)
        request(
            session,
            "POST",
            f"{API}/edits/{edit_id}:commit",
            params={"changesInReviewBehavior": "ERROR_IF_IN_REVIEW"},
        )
        print(f"Published Trailscape 2.0.{args.run_number} ({actual_code}) to Play internal testing.")
    finally:
        # A committed edit no longer exists; a skipped or failed edit should
        # not remain as a pending draft.
        try:
            session.delete(f"{API}/edits/{edit_id}", timeout=30)
        except Exception as exc:
            print(f"Warning: could not clean up Play edit {edit_id}: {exc}")


if __name__ == "__main__":
    main()
