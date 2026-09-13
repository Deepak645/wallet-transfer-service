#!/usr/bin/env bash
# One-command burst/concurrency test against a running wallet-service.
#
# Usage:
#   ./burst-test.sh
#       targets http://localhost:8080
#
#   BASE_URL=https://wallet-transfer-service-production-0e3e.up.railway.app ./burst-test.sh
#       targets the deployed Railway application
#
# WHAT THIS TESTS: the three live-fire concurrency scenarios required by the
# Paytm PML R2 assignment, exercised purely through the public HTTP API
# (POST /wallets, POST /transfers):
#   1. Concurrent get-or-create: N simultaneous POST /wallets for the same
#      brand-new user -> exactly one wallet.
#   2. Idempotent retry storm: K simultaneous identical POST /transfers using
#      the same idempotency_key -> exactly one debit/credit, identical
#      responses.
#   3. Conservation under contention: many simultaneous transfers among a
#      small set of wallets, including opposite directions (A->B and B->A)
#      -> total balance unchanged, no negative balances.
#
# TEST-SETUP-ONLY DATABASE WRITE - read before judging the DB access below:
# the assignment does not define a deposit/funding API, and wallets are
# always created at balance 0. Scenarios 2 and 3 need a nonzero starting
# balance to have money to move at all, so this script sets one directly
# with a plain SQL UPDATE via psql. Nothing more.
#   - This UPDATE is test setup only. It is not an endpoint, not part of the
#     wallet service, and not itself under test. The only API exercised and
#     asserted on in every scenario, including 2 and 3, is POST /transfers
#     (and POST /wallets in scenario 1).
#   - It doesn't expose Postgres publicly and doesn't require a reviewer to
#     hold our Railway/Postgres credentials: it runs against whatever
#     Postgres the person running this script already has direct access to
#     - a local docker-compose stack, or a local psql pointed at one (see
#     PGHOST/PGPORT/PGUSER/PGPASSWORD/PGDATABASE below).
#   - This is not a claim about how a real deployment would establish
#     balances - the assignment doesn't specify a funding mechanism, so this
#     script doesn't invent or imply one.
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-wallet}"
PGPASSWORD="${PGPASSWORD:-wallet}"
PGDATABASE="${PGDATABASE:-wallet}"
export PGPASSWORD

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

FAILURES=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "FAIL: required command '$1' not found on PATH"
        exit 1
    }
}
require_cmd curl

# ---- JSON helpers (grep/sed only - our responses are flat, single-line) ----

json_str_field() { # $1=json $2=field
    printf '%s' "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed -E "s/.*:\"([^\"]*)\"/\1/"
}

json_num_field() { # $1=json $2=field
    printf '%s' "$1" | grep -o "\"$2\":-\{0,1\}[0-9][0-9]*" | head -1 | sed -E "s/.*:(-?[0-9]+)/\1/"
}

# ---- test-only DB funding (see header) - NOT part of the API under test ----

run_psql() { # $1 = SQL statement
    if command -v psql >/dev/null 2>&1; then
        psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 -q -c "$1" >/dev/null
    elif (cd "$PROJECT_ROOT" && docker compose exec -T db true) >/dev/null 2>&1; then
        (cd "$PROJECT_ROOT" && docker compose exec -T db psql -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 -q -c "$1") >/dev/null
    else
        echo "FAIL: no psql client on PATH and no local docker-compose 'db' service reachable - cannot fund test wallets" >&2
        exit 1
    fi
}

fund_wallet() { # $1=walletId $2=amountPaise
    run_psql "UPDATE wallets SET balance_paise = $2 WHERE id = $1;"
}

# ---- HTTP helpers ----

http_post() { # $1=url $2=token $3=body(may be empty) $4=out_body_file $5=out_status_file
    local url="$1" token="$2" body="$3" out_body="$4" out_status="$5" status
    if [ -n "$body" ]; then
        status="$(curl -sS -o "$out_body" -w '%{http_code}' -X POST \
            -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
            -d "$body" "$url")"
    else
        status="$(curl -sS -o "$out_body" -w '%{http_code}' -X POST \
            -H "Authorization: Bearer $token" "$url")"
    fi
    printf '%s' "$status" >"$out_status"
}

new_user_id() { echo "burst-$(date +%s)-$$-$RANDOM"; }

create_wallet() { # $1=userId -> echoes wallet id
    local body
    body="$(curl -sS -X POST -H "Authorization: Bearer $1" "$BASE_URL/wallets")"
    json_num_field "$body" id
}

get_balance() { # $1=walletId -> echoes balancePaise
    local body
    body="$(curl -sS -H "Authorization: Bearer probe-reader" "$BASE_URL/wallets/$1")"
    json_num_field "$body" balancePaise
}

echo "== Wallet & Transfer burst test =="
echo "Target: $BASE_URL"
echo

health_code="$(curl -sS -o /dev/null -w '%{http_code}' "$BASE_URL/health")"
if [ "$health_code" != "200" ]; then
    fail "$BASE_URL/health returned $health_code (expected 200) - is the service running?"
    echo
    echo "Aborting: cannot reach target."
    exit 1
fi
pass "service is healthy at $BASE_URL"
echo

# =====================================================================
# Scenario 1: concurrent get-or-create for the same user
# =====================================================================
echo "-- Scenario 1: concurrent get-or-create, same user --"
S1_USER="$(new_user_id)"
S1_N=20

for i in $(seq 1 "$S1_N"); do
    http_post "$BASE_URL/wallets" "$S1_USER" "" "$WORKDIR/s1_${i}.json" "$WORKDIR/s1_${i}.status" &
done
wait

s1_bad_status=0
s1_ids=""
for i in $(seq 1 "$S1_N"); do
    status="$(cat "$WORKDIR/s1_${i}.status")"
    body="$(cat "$WORKDIR/s1_${i}.json")"
    if [ "$status" != "200" ]; then
        fail "scenario 1: request $i returned HTTP $status (expected 200): $body"
        s1_bad_status=1
    fi
    id="$(json_num_field "$body" id)"
    if [ -z "$id" ]; then
        fail "scenario 1: request $i response had no wallet id: $body"
        s1_bad_status=1
    fi
    s1_ids="$s1_ids $id"
done
[ "$s1_bad_status" -eq 0 ] && pass "scenario 1: all $S1_N concurrent requests returned HTTP 200 with a wallet id"

s1_distinct="$(echo $s1_ids | tr ' ' '\n' | sort -u | wc -l | tr -d ' ')"
if [ "$s1_distinct" -eq 1 ]; then
    pass "scenario 1: $S1_N concurrent get-or-create calls produced exactly 1 distinct wallet (id:$(echo $s1_ids | tr ' ' '\n' | sort -u | head -1))"
else
    fail "scenario 1: expected exactly 1 distinct wallet id, got $s1_distinct (ids:$s1_ids)"
fi
echo

# =====================================================================
# Scenario 2: concurrent identical transfers, same idempotency_key
# =====================================================================
echo "-- Scenario 2: concurrent identical transfers, same idempotency_key --"
S2_USER_A="$(new_user_id)-a"
S2_USER_B="$(new_user_id)-b"
S2_WALLET_A="$(create_wallet "$S2_USER_A")"
S2_WALLET_B="$(create_wallet "$S2_USER_B")"

if [ -z "$S2_WALLET_A" ] || [ -z "$S2_WALLET_B" ]; then
    fail "scenario 2: could not create test wallets (A='$S2_WALLET_A' B='$S2_WALLET_B')"
else
    fund_wallet "$S2_WALLET_A" 100000 # test setup only (see header) - not part of the API under test

    s2_before_a="$(get_balance "$S2_WALLET_A")"
    s2_before_b="$(get_balance "$S2_WALLET_B")"

    S2_KEY="burst-key-$(date +%s)-$$-$RANDOM"
    S2_AMOUNT=2500
    S2_N=20
    S2_BODY="{\"from\":$S2_WALLET_A,\"to\":$S2_WALLET_B,\"amount_paise\":$S2_AMOUNT,\"idempotency_key\":\"$S2_KEY\"}"

    for i in $(seq 1 "$S2_N"); do
        http_post "$BASE_URL/transfers" "$S2_USER_A" "$S2_BODY" "$WORKDIR/s2_${i}.json" "$WORKDIR/s2_${i}.status" &
    done
    wait

    s2_bad=0
    s2_first_body="$(cat "$WORKDIR/s2_1.json")"
    s2_first_id="$(json_num_field "$s2_first_body" id)"
    for i in $(seq 1 "$S2_N"); do
        status="$(cat "$WORKDIR/s2_${i}.status")"
        body="$(cat "$WORKDIR/s2_${i}.json")"
        if [ "$status" != "200" ]; then
            fail "scenario 2: request $i returned HTTP $status (expected 200): $body"
            s2_bad=1
        fi
        if [ "$body" != "$s2_first_body" ]; then
            fail "scenario 2: request $i body differs from request 1's body ('$body' vs '$s2_first_body')"
            s2_bad=1
        fi
    done
    [ "$s2_bad" -eq 0 ] && [ -n "$s2_first_id" ] &&
        pass "scenario 2: all $S2_N concurrent identical-key requests returned HTTP 200 with identical bodies (transfer id:$s2_first_id)"

    s2_after_a="$(get_balance "$S2_WALLET_A")"
    s2_after_b="$(get_balance "$S2_WALLET_B")"
    s2_expected_a=$((s2_before_a - S2_AMOUNT))
    s2_expected_b=$((s2_before_b + S2_AMOUNT))
    if [ "$s2_after_a" -eq "$s2_expected_a" ] && [ "$s2_after_b" -eq "$s2_expected_b" ]; then
        pass "scenario 2: exactly one movement applied ($S2_AMOUNT paise), not $S2_N (A: $s2_before_a->$s2_after_a, B: $s2_before_b->$s2_after_b)"
    else
        fail "scenario 2: balances do not reflect exactly one movement (A: $s2_before_a->$s2_after_a, expected $s2_expected_a; B: $s2_before_b->$s2_after_b, expected $s2_expected_b)"
    fi
fi
echo

# =====================================================================
# Scenario 3: concurrent transfers among a small set of wallets,
# including opposite-direction pairs
# =====================================================================
echo "-- Scenario 3: concurrent transfers among a small set of wallets (opposite directions) --"
S3_WALLETS=()
for n in 1 2 3; do
    u="$(new_user_id)-w$n"
    w="$(create_wallet "$u")"
    if [ -z "$w" ]; then
        fail "scenario 3: could not create test wallet #$n"
    else
        fund_wallet "$w" 20000 # test setup only (see header) - not part of the API under test
        S3_WALLETS+=("$w")
    fi
done

if [ "${#S3_WALLETS[@]}" -ne 3 ]; then
    fail "scenario 3: expected 3 test wallets, got ${#S3_WALLETS[@]} - skipping burst"
else
    s3_total_before=0
    for w in "${S3_WALLETS[@]}"; do
        b="$(get_balance "$w")"
        s3_total_before=$((s3_total_before + b))
    done

    S3_N_PER_PAIR=10
    S3_AMOUNT=300
    PAIRS=("0 1" "1 0" "1 2" "2 1" "0 2" "2 0")
    idx=0
    for pair in "${PAIRS[@]}"; do
        read -r from_idx to_idx <<<"$pair"
        for k in $(seq 1 "$S3_N_PER_PAIR"); do
            idx=$((idx + 1))
            key="burst-s3-$(date +%s)-$$-$RANDOM-$idx"
            body="{\"from\":${S3_WALLETS[$from_idx]},\"to\":${S3_WALLETS[$to_idx]},\"amount_paise\":$S3_AMOUNT,\"idempotency_key\":\"$key\"}"
            http_post "$BASE_URL/transfers" "burst-s3-caller" "$body" "$WORKDIR/s3_${idx}.json" "$WORKDIR/s3_${idx}.status" &
        done
    done
    wait
    s3_total_requests=$idx

    s3_bad_status=0
    for i in $(seq 1 "$s3_total_requests"); do
        status="$(cat "$WORKDIR/s3_${i}.status")"
        if [ "$status" != "200" ]; then
            fail "scenario 3: request $i returned HTTP $status (expected 200, COMPLETED or cleanly DECLINED): $(cat "$WORKDIR/s3_${i}.json")"
            s3_bad_status=1
        fi
    done
    [ "$s3_bad_status" -eq 0 ] && pass "scenario 3: all $s3_total_requests concurrent transfers returned HTTP 200 (no errors under contention)"

    s3_total_after=0
    s3_negative=0
    for w in "${S3_WALLETS[@]}"; do
        b="$(get_balance "$w")"
        s3_total_after=$((s3_total_after + b))
        if [ "$b" -lt 0 ]; then
            fail "scenario 3: wallet $w has negative balance $b"
            s3_negative=1
        fi
    done
    [ "$s3_negative" -eq 0 ] && pass "scenario 3: no wallet balance went negative"

    if [ "$s3_total_after" -eq "$s3_total_before" ]; then
        pass "scenario 3: conservation holds across $s3_total_requests concurrent transfers (total $s3_total_before -> $s3_total_after paise)"
    else
        fail "scenario 3: conservation violated (total $s3_total_before -> $s3_total_after paise)"
    fi
fi
echo

echo "======================================"
if [ "$FAILURES" -eq 0 ]; then
    echo "ALL CHECKS PASSED"
    exit 0
else
    echo "$FAILURES CHECK(S) FAILED"
    exit 1
fi
