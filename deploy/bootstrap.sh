#!/usr/bin/env bash
#
# Text2SQL demo — one-shot server bootstrap.
#
# Takes a fresh Linux host from "nothing installed" to "the whole stack is running behind port 80".
# Idempotent: re-running is safe and is the normal case, because a preemptible instance loses
# everything when it is reclaimed and comes back blank.
#
# Usage, from the repository root:
#   cp deploy/.env.example deploy/.env   # then fill it in
#   sudo bash deploy/bootstrap.sh
#
# The script refuses to invent secrets: it stops with a readable message instead.

set -Eeuo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$REPO_DIR/deploy/.env"
COMPOSE_FILE="$REPO_DIR/deploy/docker-compose.prod.yml"

log()  { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m    %s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31m!!! %s\033[0m\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || fail "Run as root: sudo bash deploy/bootstrap.sh"
[[ -f "$COMPOSE_FILE" ]] || fail "Cannot find $COMPOSE_FILE — run this from the repository."

# --- 1. Base packages ---------------------------------------------------------------------------
log "Installing base packages"
if command -v apt-get >/dev/null 2>&1; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -y
    apt-get install -y ca-certificates curl gnupg git
elif command -v dnf >/dev/null 2>&1; then
    dnf install -y ca-certificates curl git
elif command -v yum >/dev/null 2>&1; then
    yum install -y ca-certificates curl git
else
    fail "Unsupported distribution: none of apt-get/dnf/yum is available."
fi

# --- 2. Docker Engine + the compose plugin ------------------------------------------------------
# Installed from the distribution's own packages, not from https://get.docker.com: that script is
# unreachable on some networks (registry/Docker endpoints are blocked in mainland China), and
# Ubuntu 24.04+ ships Docker 29 with the compose v2 CLI plugin anyway.
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    log "Docker already installed: $(docker --version)"
else
    log "Installing Docker from the distribution packages"
    if command -v apt-get >/dev/null 2>&1; then
        apt-get install -y docker.io || warn "docker.io did not install cleanly from apt."
        # Ubuntu names the plugin package docker-compose-v2; other distributions differ.
        apt-get install -y docker-compose-v2 || apt-get install -y docker-compose-plugin || true
    elif command -v dnf >/dev/null 2>&1; then
        dnf install -y docker docker-compose-plugin || true
    elif command -v yum >/dev/null 2>&1; then
        yum install -y docker docker-compose-plugin || true
    fi
fi

command -v docker >/dev/null 2>&1 || fail "Docker is not available after the install step."
docker compose version >/dev/null 2>&1 \
    || fail "The 'docker compose' plugin is missing (Ubuntu package: docker-compose-v2)."

systemctl enable --now docker >/dev/null 2>&1 || warn "Could not enable the docker service via systemd."

# --- 3. Harden the daemon -----------------------------------------------------------------------
log "Configuring the Docker daemon"
mkdir -p /etc/docker
cat >/etc/docker/daemon.json <<'JSON'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" }
}
JSON
# Deliberately no "hosts" entry: the daemon must never listen on TCP (2375/2376). Only the local
# unix socket, which is what docker compose uses.
if ! systemctl restart docker; then
    warn "Could not restart docker via systemd; if this host does not use systemd, start it manually."
fi

# --- 4. Host firewall ---------------------------------------------------------------------------
log "Allowing SSH (22), HTTP (80) and MinIO S3 (9000) through the host firewall"
warn "If SSH is not on port 22 on this host, add that port BEFORE enabling the firewall,"
warn "otherwise ufw's default-deny policy will lock you out."
if command -v ufw >/dev/null 2>&1; then
    ufw allow 22/tcp >/dev/null 2>&1 || true
    ufw allow 80/tcp >/dev/null 2>&1 || true
    # 9000 serves presigned original-document URLs opened by the browser. The bucket stays private.
    ufw allow 9000/tcp >/dev/null 2>&1 || true
    ufw --force enable >/dev/null 2>&1 || true
else
    warn "ufw is not installed; make sure the cloud security group is the only thing filtering traffic."
fi
warn "Also check the cloud security group: it must allow 22, 80 and 9000 only."
warn "MySQL, pgvector, the MinIO console and the observability ports are bound to 127.0.0.1 on purpose. Reach them with:"
warn "  ssh -L 3000:127.0.0.1:3000 -L 3306:127.0.0.1:3306 -L 5433:127.0.0.1:5433 -L 9001:127.0.0.1:9001 -L 9090:127.0.0.1:9090 <user>@<host>"

# --- 5. Configuration ---------------------------------------------------------------------------
log "Checking $ENV_FILE"
[[ -f "$ENV_FILE" ]] || fail "Missing $ENV_FILE. Copy deploy/.env.example to deploy/.env and fill it in."

missing=()
for key in MYSQL_ROOT_PASSWORD TEXT2SQL_JWT_SECRET KNOWLEDGE_PGVECTOR_PASSWORD MINIO_ROOT_PASSWORD; do
    if ! grep -Eq "^${key}=.+" "$ENV_FILE"; then
        missing+=("$key")
    fi
done
if [[ ${#missing[@]} -gt 0 ]]; then
    fail "These keys are empty in $ENV_FILE: ${missing[*]}"
fi

secret_len=$(sed -n 's/^TEXT2SQL_JWT_SECRET=//p' "$ENV_FILE" | head -n 1 | tr -d '\r' | wc -c)
if [[ "$secret_len" -lt 33 ]]; then
    fail "TEXT2SQL_JWT_SECRET must be at least 32 characters (HS256). Generate one with: openssl rand -base64 48"
fi

# --- 6. Build and start -------------------------------------------------------------------------
log "Building and starting the stack (first build downloads Maven/Node dependencies; be patient)"
cd "$REPO_DIR"
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d --build --remove-orphans

# --- 6b. Migrate an existing volume ------------------------------------------------------------
# MySQL only runs the init scripts on an empty data directory, so a container recreated on an old
# volume would keep its old columns. Both scripts are idempotent and safe to run every time.
# 03-app-migrate.sql covers the pre-existing column additions; 04-access-control.sql adds
# app_user.enabled/token_version, the seven access_* tables, indexes and the built-in seed/backfill.
log "Applying idempotent application-database migrations"
# The exec below fails while MySQL is still initialising, and `set -e` would abort the deploy.
mysql_ready=0
for _ in $(seq 1 60); do
    if docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" exec -T mysql \
        sh -c 'mysqladmin ping -h 127.0.0.1 -uroot -p"$MYSQL_ROOT_PASSWORD" --silent' >/dev/null 2>&1; then
        mysql_ready=1
        break
    fi
    sleep 2
done
[[ "$mysql_ready" -eq 1 ]] || fail "MySQL did not become ready in time; inspect: docker compose -f $COMPOSE_FILE logs mysql"

for script in 03-app-migrate.sql 04-access-control.sql; do
    docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" exec -T mysql \
        sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" --default-character-set=utf8mb4 \"\$MYSQL_DATABASE\" < /docker-entrypoint-initdb.d/$script"
done

# --- 7. Wait for it to serve --------------------------------------------------------------------
log "Waiting for the backend to answer"
healthy=0
for _ in $(seq 1 60); do
    if curl -fsS -o /dev/null --max-time 5 "http://127.0.0.1/api/text2sql/health"; then
        healthy=1
        break
    fi
    sleep 5
done
if [[ "$healthy" -ne 1 ]]; then
    fail "The stack did not become healthy within about 10 minutes. Inspect:
  docker compose -f deploy/docker-compose.prod.yml logs --tail=200 backend"
fi

# --- 8. Report ----------------------------------------------------------------------------------
PUBLIC_IP="$(curl -fsS --max-time 5 https://api.ipify.org || echo '<server-ip>')"
log "Done"
cat <<TXT
  Frontend : http://${PUBLIC_IP}/
  Accounts : admin / admin123              (admin, all permissions)
             student01 / student123        (student, own rows only)
             student02 / student123        (student, own rows only)
             student03 / student123        (student, own rows only)
             counselor01 / counselor123    (counselor, managed classes)
  Access   : admin -> 权限管理 (/admin/access-control): roles, permissions, data scopes,
             users, datasource grants, audit
  Status   : docker compose -f deploy/docker-compose.prod.yml ps
  Logs     : docker compose -f deploy/docker-compose.prod.yml logs -f backend
  Grafana  : ssh -L 3000:127.0.0.1:3000 root@${PUBLIC_IP}  then http://localhost:3000
TXT
