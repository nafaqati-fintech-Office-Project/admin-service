#!/usr/bin/env bash
# Gives one existing account the ADMIN role, so it can sign in to the admin panel. Run as root on the VPS:
#   bash make-admin.sh <username>
# Create the account first the normal way (sign up in the app, with a strong password). The role is read at the next sign-in.
# To take the role away again:  bash make-admin.sh <username> --revoke
set -euo pipefail
USERNAME="${1:?usage: make-admin.sh <username> [--revoke]}"
ROLE=ADMIN; [ "${2:-}" = "--revoke" ] && ROLE=USER
[[ "$USERNAME" =~ ^[A-Za-z0-9._-]{3,50}$ ]] || { echo "not a valid username"; exit 2; }
DIR="${FINTECH_DIR:-/opt/fintech}"
COMPOSE="${COMPOSE_CMD:-docker compose -f $DIR/compose.yaml -f $DIR/compose.apps.yaml}"

cd "$DIR"
$COMPOSE exec -T postgres sh -c "psql -v ON_ERROR_STOP=1 -U \"\$POSTGRES_USER\" -d fintech_auth -tA -c \"
  UPDATE users SET role_id = (SELECT id FROM roles WHERE name = '$ROLE') WHERE lower(username) = lower('$USERNAME')
  RETURNING 'role of ' || username || ' is now $ROLE'\"" | grep . || { echo "no account with that username"; exit 1; }
echo "Sign out and in again (or wait up to 15 minutes) for the new role to apply."
