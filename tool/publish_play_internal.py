#!/usr/bin/env python3
"""Publish one AAB to Trailscape's Google Play test tracks.

Used for the phone bundle (internal test and closed test) and for the Wear OS
bundle (the Wear form-factor tracks). The file keeps its historical name.

Requires PLAY_SERVICE_ACCOUNT_JSON and the Android Publisher API. The service
account should only have the app-level permission to release to test tracks.

Each requested track gets its own edit and its own commit. A closed test that
Google rejects or that does not exist must not hold back the internal test,
which is the fast path to the developer's own device. The script still exits
non-zero if any track failed, so the workflow run shows it.
"""

import argparse
import json
import os
import sys
from pathlib import Path
from urllib.parse import quote

from google.auth.transport.requests import AuthorizedSession
from google.oauth2 import service_account


PACKAGE = "de.robinrehbein.trailscape"
API = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}"
UPLOAD_API = f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PACKAGE}"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"

# Phone: the internal test plus the default closed test ("alpha").
DEFAULT_TRACKS = "internal|qa,alpha"


def request(session, method, url, **kwargs):
    response = session.request(method, url, timeout=180, **kwargs)
    if not response.ok:
        raise RuntimeError(f"Google Play API {method} failed ({response.status_code}): {response.text[:1200]}")
    return response.json() if response.content else {}


def parse_tracks(spec):
    """"a|b,c" -> [["a", "b"], ["c"]]: targets, each with alternative names.

    Google documents the internal test as "qa" while the API has long accepted
    "internal"; form-factor tracks carry a prefix ("wear:"). Alternatives let
    the workflow name what it means and the script pick what the app has.
    """
    targets = []
    for part in spec.split(","):
        names = [name.strip() for name in part.split("|") if name.strip()]
        if names:
            targets.append(names)
    return targets


def pick_track(alternatives, existing):
    """Returns the app's own spelling of the first matching track.

    Custom track names keep the case they were created with in the Play
    Console (e.g. "wear:Alpha"), so the match ignores case.
    """
    by_lower = {name.lower(): name for name in existing}
    for name in alternatives:
        if name in existing:
            return name
        if name.lower() in by_lower:
            return by_lower[name.lower()]
    return None


# Wear OS codes are phone codes + 1000000 (wear/build.gradle.kts).
CODE_RANGE = 1_000_000


def track_codes(track, code_offset):
    """Version codes of this bundle's kind (phone or Wear) on the track.

    A track can list the other form factor's codes too (seen: phone "alpha"
    listing the Wear code 1002272), and those must not count as newer.
    """
    codes = [
        int(code)
        for release in track.get("releases", [])
        for code in release.get("versionCodes", [])
    ]
    return [code for code in codes if code_offset <= code < code_offset + CODE_RANGE]


def publish_to_track(session, bundle, track_name, expected_code, code_offset, release_name, notes):
    """Returns a one-line result. Raises on failure."""
    edit_id = request(session, "POST", f"{API}/edits", json={})["id"]
    track_url = f"{API}/edits/{edit_id}/tracks/{quote(track_name, safe='')}"
    try:
        current_codes = track_codes(request(session, "GET", track_url), code_offset)
        if current_codes and max(current_codes) >= expected_code:
            return f"{track_name}: already has versionCode {max(current_codes)}; skipped {expected_code}."

        # The bundle is uploaded once per app, not once per track: after the
        # first track's commit it is known to Play and only referenced here.
        known = request(session, "GET", f"{API}/edits/{edit_id}/bundles")
        known_codes = {int(item["versionCode"]) for item in known.get("bundles", [])}
        if expected_code not in known_codes:
            with bundle.open("rb") as handle:
                uploaded = request(
                    session,
                    "POST",
                    f"{UPLOAD_API}/edits/{edit_id}/bundles?uploadType=media",
                    data=handle,
                    headers={"Content-Type": "application/octet-stream"},
                )
            actual_code = int(uploaded["versionCode"])
            if actual_code != expected_code:
                raise RuntimeError(f"Bundle versionCode {actual_code} differs from expected {expected_code}")

        release = {
            "track": track_name,
            "releases": [{
                "name": release_name,
                "versionCodes": [str(expected_code)],
                "status": "completed",
                "releaseNotes": [{"language": "de-DE", "text": notes[:500]}],
            }],
        }
        request(session, "PUT", track_url, json=release)
        # No changesInReviewBehavior: the default cancels a review that is
        # still running and submits everything. With ERROR_IF_IN_REVIEW the
        # next merge would fail while Google still reviews the closed test of
        # the previous one.
        request(session, "POST", f"{API}/edits/{edit_id}:commit")
        return f"{track_name}: published {release_name} ({expected_code})."
    finally:
        # A committed edit no longer exists; a skipped or failed edit should
        # not remain as a pending draft.
        try:
            session.delete(f"{API}/edits/{edit_id}", timeout=30)
        except Exception as exc:
            print(f"Warning: could not clean up Play edit {edit_id}: {exc}")


def list_tracks(session):
    edit_id = request(session, "POST", f"{API}/edits", json={})["id"]
    try:
        listed = request(session, "GET", f"{API}/edits/{edit_id}/tracks")
        return [item["track"] for item in listed.get("tracks", [])]
    finally:
        try:
            session.delete(f"{API}/edits/{edit_id}", timeout=30)
        except Exception as exc:
            print(f"Warning: could not clean up Play edit {edit_id}: {exc}")


def publish(session, bundle, targets, expected_code, code_offset, release_name, notes):
    """Publishes to every target; returns the number of failed targets."""
    existing = list_tracks(session)
    print(f"Tracks of this app: {', '.join(existing) or '(none)'}")
    failures = 0
    for alternatives in targets:
        wanted = " or ".join(alternatives)
        track_name = pick_track(alternatives, existing)
        if track_name is None:
            failures += 1
            print(f"ERROR {wanted}: no such track. Create it in the Play Console or adjust --tracks.")
            continue
        try:
            print(publish_to_track(session, bundle, track_name, expected_code, code_offset, release_name, notes))
        except Exception as exc:
            failures += 1
            print(f"ERROR {track_name}: {exc}")
    return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--run-number", type=int, required=True)
    parser.add_argument("--notes", required=True)
    parser.add_argument(
        "--tracks",
        default=DEFAULT_TRACKS,
        help='Comma-separated targets, alternatives with "|". Default: %(default)s',
    )
    parser.add_argument(
        "--code-offset",
        type=int,
        default=0,
        help="Added to 2000 + run number; the Wear OS bundle uses 1000000 (wear/build.gradle.kts).",
    )
    parser.add_argument("--label", default="Trailscape", help="Prefix of the release name.")
    args = parser.parse_args()

    if not args.bundle.is_file():
        parser.error(f"Bundle missing: {args.bundle}")
    if args.run_number <= 0:
        parser.error("--run-number must be positive")
    targets = parse_tracks(args.tracks)
    if not targets:
        parser.error("--tracks names no track")
    raw_credentials = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "")
    if not raw_credentials:
        parser.error("PLAY_SERVICE_ACCOUNT_JSON is missing")

    credentials = service_account.Credentials.from_service_account_info(
        json.loads(raw_credentials), scopes=[SCOPE]
    )
    session = AuthorizedSession(credentials)
    failures = publish(
        session,
        args.bundle,
        targets,
        expected_code=args.run_number + 2000 + args.code_offset,
        code_offset=args.code_offset,
        release_name=f"{args.label} 2.0.{args.run_number}",
        notes=args.notes,
    )
    if failures:
        sys.exit(f"{failures} of {len(targets)} Play tracks failed.")


if __name__ == "__main__":
    main()
