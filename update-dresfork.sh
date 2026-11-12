#!/usr/bin/env bash
#
# Updates this DRES clone to the latest master, builds the distribution and installs it to $DEST/dres-dist.
#
#   1. switches the clone to master and pulls the latest version from origin
#   2. builds the distribution with `./gradlew clean distZip`
#   3. copies the current $DEST/dres-dist to $DEST/dres-dist-YYYYMMDD (backup)
#   4. deletes $DEST/dres-dist/lib and unzips the new build into $DEST
#
# Stop DRES before running this script. Your config.json and data folder are not touched.
#
# Usage: ./update-dresfork.sh            (install to /home/itec/klschoef/dresfork)
#        DEST=/some/dir ./update-dresfork.sh
#
set -euo pipefail

DEST="${DEST:-/home/itec/klschoef/dresfork}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ZIP="$REPO/backend/build/distributions/dres-dist.zip"

# The whole script runs inside main(), which bash reads completely before running it. That matters
# because "git pull" below may update this very file while it runs.

log() { printf '\n==> %s\n' "$*"; }
die() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

main() {
    command -v unzip >/dev/null || die "'unzip' is not installed."
    [ -d "$DEST" ] || die "Destination folder $DEST does not exist."

    # Refuse to replace the jars of a DRES instance that is still running from the destination.
    if pgrep -f "$DEST/dres-dist/lib" >/dev/null 2>&1; then
        die "DRES is still running from $DEST/dres-dist. Stop it first, then run this script again."
    fi

    cd "$REPO"

    log "Switching to master and pulling the latest version"
    if ! git diff --quiet || ! git diff --cached --quiet; then
        die "The clone at $REPO has uncommitted changes. Commit or discard them first (see 'git status')."
    fi
    git fetch origin
    git checkout master
    git pull --ff-only origin master
    git log --oneline -1

    log "Building the distribution (./gradlew clean distZip)"
    ./gradlew clean distZip
    [ -f "$ZIP" ] || die "Build finished but $ZIP was not found."

    if [ -d "$DEST/dres-dist" ]; then
        BACKUP="$DEST/dres-dist-$(date +%Y%m%d)"
        # If the script runs more than once on the same day, keep every backup.
        [ -e "$BACKUP" ] && BACKUP="$BACKUP-$(date +%H%M%S)"
        log "Backing up $DEST/dres-dist to $BACKUP"
        cp -a "$DEST/dres-dist" "$BACKUP"

        log "Removing old libraries in $DEST/dres-dist/lib"
        rm -rf "$DEST/dres-dist/lib"
    fi

    log "Unpacking the new build into $DEST"
    unzip -q -o "$ZIP" -d "$DEST"
    chmod +x "$DEST/dres-dist/bin/"* 2>/dev/null || true

    log "Done. Installed version: $(git log --oneline -1)"
    echo "Start DRES with: $DEST/dres-dist/bin/backend <path to your config.json>"
}

main "$@"
exit
