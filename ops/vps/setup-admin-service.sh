#!/usr/bin/env bash
# One-time preparation of the VPS for admin-service (the admin panel's backend). Run as root on the VPS:
#
#   bash setup-admin-service.sh https://<your-admin-panel>.vercel.app
#
# What it does (each step is skipped when already done, so running it twice is safe):
#   1. creates the database admin_db in the existing Postgres container
#   2. creates /opt/fintech/admin-service with its Dockerfile (deploy.sh builds from it; the source arrives with the first deploy)
#   3. adds an admin-service block to compose.apps.yaml: NO published port, internal network only, reached via the gateway.
#      The database user, password and internal token are copied from billing-service's block, so no secret is typed here.
#   4. points the gateway at admin-service (ADMIN_SERVICE_URI) and allows the panel's Vercel address (CORS_ALLOWED_ORIGINS)
# Every file it changes is backed up first, and compose.apps.yaml is validated; on any error the backup is put back.
# It does NOT restart anything. At the end it prints the two commands to run when a short API pause (~40 s) is acceptable.
set -euo pipefail

ORIGIN="${1:-}"
DIR="${FINTECH_DIR:-/opt/fintech}"   # override only for a dry run on a copy
APPS="$DIR/compose.apps.yaml"
ENVF="$DIR/.env"
COMPOSE="${COMPOSE_CMD:-docker compose -f $DIR/compose.yaml -f $DIR/compose.apps.yaml}"
STAMP="$(date +%Y%m%d%H%M%S)"

[[ "$ORIGIN" =~ ^https://[a-zA-Z0-9.-]+$ ]] || { echo "usage: $0 https://<panel>.vercel.app   (https, no path, no trailing slash)"; exit 2; }
[ -f "$APPS" ] && [ -f "$DIR/compose.yaml" ] || { echo "no compose files in $DIR"; exit 2; }
cd "$DIR"

say() { printf '\n==> %s\n' "$*"; }

# ---------------------------------------------------------------- 0. backups
cp "$APPS" "$APPS.bak-$STAMP"
[ -f "$ENVF" ] && cp "$ENVF" "$ENVF.bak-$STAMP"
restore() { echo "!! restoring compose.apps.yaml and .env from the backup ($STAMP)"; cp "$APPS.bak-$STAMP" "$APPS"; [ -f "$ENVF.bak-$STAMP" ] && cp "$ENVF.bak-$STAMP" "$ENVF"; }
trap 'restore' ERR
say "backups: $APPS.bak-$STAMP${ENVF:+ and $ENVF.bak-$STAMP}"

# block <service>: prints that service's lines from compose.apps.yaml (from "  name:" to the next service)
block() { awk -v s="  $1:" '$0==s{on=1; print; next} on && /^  [a-zA-Z0-9_-]+:[[:space:]]*$/{exit} on && /^[^ #]/{exit} on{print}' "$APPS"; }

# ---------------------------------------------------------------- 1. database
say "database admin_db"
$COMPOSE exec -T postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '"'"'admin_db'"'"'" | grep -q 1 \
  && echo "already exists" \
  || { psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "CREATE DATABASE admin_db OWNER \"$POSTGRES_USER\"" && echo "created"; }'

# ---------------------------------------------------------------- 2. folder and Dockerfile
say "folder $DIR/admin-service"
mkdir -p "$DIR/admin-service"
if [ -f "$DIR/admin-service/Dockerfile" ]; then
  echo "Dockerfile already there (left as it is)"
else
  cat > "$DIR/admin-service/Dockerfile" <<'DOCKERFILE'
# Builds admin-service from source on the server (needs no local Maven), then runs it on a small JRE as a non-root user.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package && cp target/admin-service-*.jar /build/app.jar

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /build/app.jar /app/app.jar
USER app
EXPOSE 8088
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60.0", "-jar", "/app/app.jar"]
DOCKERFILE
  echo "Dockerfile written"
fi

# ---------------------------------------------------------------- 3. compose block
say "compose.apps.yaml: admin-service"
if grep -qE '^  admin-service:[[:space:]]*$' "$APPS"; then
  echo "admin-service is already defined (left as it is). Compare it with the block in this script if the panel misbehaves."
else
  BILLING="$(block billing-service)"
  [ -n "$BILLING" ] || { echo "billing-service is not in compose.apps.yaml: cannot copy its database settings"; false; }
  pick() { printf '%s\n' "$BILLING" | grep -E "^[[:space:]]+$1:" | head -1 | sed -E "s/^[[:space:]]+$1:[[:space:]]*//"; }
  DB_USER="$(pick DB_USERNAME)"; DB_PASS="$(pick DB_PASSWORD)"; TOKEN="$(pick INTERNAL_API_TOKEN)"
  NETWORKS="$(printf '%s\n' "$BILLING" | grep -E '^    networks:' | head -1 || true)"
  for v in DB_USER DB_PASS TOKEN; do [ -n "${!v}" ] || { echo "billing-service has no ${v/DB_USER/DB_USERNAME}: add admin-service by hand"; false; }; done
  if printf '%s\n' "$BILLING" | grep -q 'INTERNAL_CALLER_SECRET'; then
    echo "NOTE: services use per-caller secrets (INTERNAL_CALLER_SECRETS). admin-service must be given its own secret and be"
    echo "      listed on user-, billing-, notification-, family- and transaction-service; see docs/admin-panel-deploy.md."
  fi
  TMP="$(mktemp)"
  cat > "$TMP" <<BLOCK

  admin-service:
    # Admin panel backend. No published port: only the api-gateway (route /api/admin/**, ADMIN role) reaches it.
    image: fintech-admin-service:latest
    restart: unless-stopped
    environment:
      JAVA_TOOL_OPTIONS: -XX:+UseSerialGC -Xss512k
      INTERNAL_API_TOKEN: $TOKEN
      SERVER_ADDRESS: 0.0.0.0
      DB_URL: jdbc:postgresql://postgres:5432/admin_db
      DB_USERNAME: $DB_USER
      DB_PASSWORD: $DB_PASS
      USERS_SERVICE_URL: http://user-service:8082
      BILLING_SERVICE_URL: http://billing-service:8093
      FAMILY_SERVICE_URL: http://family-service:8086
      TRANSACTIONS_SERVICE_URL: http://transaction-service:8083
      AUTH_SERVICE_URL: http://auth-service:8081
      NOTIFICATION_SERVICE_URL: http://notification-service:8084
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      ADMIN_HEALTH_TARGETS: >-
        api-gateway=http://api-gateway:8080,auth-service=http://auth-service:8081,user-service=http://user-service:8082,
        transaction-service=http://transaction-service:8083,notification-service=http://notification-service:8084,
        family-service=http://family-service:8086,reports-analytics-service=http://reports-analytics-service:8089,
        budget-service=http://budget-service:8090,subscription-service=http://subscription-service:8091,
        goal-service=http://goal-service:8092,billing-service=http://billing-service:8093
    depends_on:
      postgres:
        condition: service_healthy
${NETWORKS}
    mem_limit: 384m
BLOCK
  # insert before the first top-level key after "services:" (for example "networks:"), or at the end of the file
  awk -v blockfile="$TMP" '
    function dump(   l) { while ((getline l < blockfile) > 0) print l; done=1 }
    /^services:/ { in_s=1; print; next }
    in_s && !done && /^[a-zA-Z]/ { dump() }
    { print }
    END { if (!done) dump() }' "$APPS.bak-$STAMP" > "$APPS"
  rm -f "$TMP"
  echo "admin-service block added (database user/password and token copied from billing-service)"
fi

# ---------------------------------------------------------------- 4. gateway: route target and CORS
say "api-gateway: ADMIN_SERVICE_URI"
GATEWAY="$(block api-gateway)"
[ -n "$GATEWAY" ] || { echo "api-gateway is not in compose.apps.yaml"; false; }
if printf '%s\n' "$GATEWAY" | grep -q 'ADMIN_SERVICE_URI'; then
  echo "already set: $(printf '%s\n' "$GATEWAY" | grep 'ADMIN_SERVICE_URI' | xargs)"
else
  # add right below "environment:" inside the api-gateway block only
  awk '$0=="  api-gateway:"{g=1} g && /^  [a-zA-Z0-9_-]+:[[:space:]]*$/ && $0!="  api-gateway:"{g=0}
       {print} g && /^    environment:[[:space:]]*$/ && !added {print "      ADMIN_SERVICE_URI: http://admin-service:8088"; added=1}' \
    "$APPS" > "$APPS.tmp" && mv "$APPS.tmp" "$APPS"
  grep -q 'ADMIN_SERVICE_URI: http://admin-service:8088' "$APPS" || { echo "could not add ADMIN_SERVICE_URI (api-gateway has no 'environment:' map)"; false; }
  echo "added ADMIN_SERVICE_URI: http://admin-service:8088"
fi

say "api-gateway: CORS_ALLOWED_ORIGINS += $ORIGIN"
if printf '%s\n' "$GATEWAY" | grep -qE 'CORS_ALLOWED_ORIGINS:[[:space:]]*\$\{CORS_ALLOWED_ORIGINS'; then
  [ -f "$ENVF" ] || { echo "the gateway reads CORS_ALLOWED_ORIGINS from $ENVF, which does not exist"; false; }
  CURRENT="$(grep -E '^CORS_ALLOWED_ORIGINS=' "$ENVF" | tail -1 | cut -d= -f2- | tr -d '"'"'"'')"
  if printf '%s' ",$CURRENT," | grep -qF ",$ORIGIN,"; then
    echo "already allowed"
  elif [ -z "$CURRENT" ]; then
    echo "CORS_ALLOWED_ORIGINS=$ORIGIN" >> "$ENVF"; echo "set in .env"
  else
    sed -i -E "s#^CORS_ALLOWED_ORIGINS=.*#CORS_ALLOWED_ORIGINS=$CURRENT,$ORIGIN#" "$ENVF"; echo "appended in .env"
  fi
else
  echo "NOTE: the gateway does not take CORS_ALLOWED_ORIGINS from .env here. Add $ORIGIN to its CORS_ALLOWED_ORIGINS by hand."
fi

# ---------------------------------------------------------------- 5. validate
say "validating the compose files"
$COMPOSE config --quiet
trap - ERR
echo "compose OK"

say "memory (admin-service needs up to 384 MB)"
free -m | sed -n '1,2p'

cat <<NEXT

Done. Nothing was restarted. Next:
  1. On your PC, push the admin-service repository: its first deploy builds and starts admin-service here.
  2. When a short API pause is acceptable (~40 s), apply the gateway changes:
       cd $DIR && $COMPOSE up -d --no-deps api-gateway
  3. Make your admin account (once):  bash $DIR/make-admin.sh <username>

To undo this script:  cp $APPS.bak-$STAMP $APPS${ENVF:+ && cp $ENVF.bak-$STAMP $ENVF}
NEXT
