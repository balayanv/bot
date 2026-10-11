#!/usr/bin/env bash
# Export this account's trades on neoxa.exchange to CSV (newest first), merging into
# the existing file so repeated runs build up the full history.
#
#   NEOXA_API_KEY=... NEOXA_API_SECRET=... ./nexa-trades.sh [out.csv] [page_size] [pause_seconds]
#
# Pages backwards through /trades/my while the server hands out a cursor
# (has_more/next_before, as the web UI uses). With API keys the server currently
# returns only the newest 500 trades and no cursor, so run this at least every few
# hours (cron) to not lose older trades.
#
# Rate limits: sleeps between pages, waits for the window to reset when the
# ratelimit-remaining budget runs low, and backs off on 429/5xx (honours retry-after).
set -euo pipefail

OUT=${1:-nexa-trades.csv}
LIMIT=${2:-500}   # server max 500
PAUSE=${3:-2}     # seconds between pages; the server allows 600 requests/min
URL=https://neoxa.exchange/api/exchange/trades/my
COLS='trade_id,executed_at,pair,side,price,quantity,total,fee,fee_token,role,is_amm'

: "${NEOXA_API_KEY:?set NEOXA_API_KEY}" "${NEOXA_API_SECRET:?set NEOXA_API_SECRET}"

new=$(mktemp) hdr=$(mktemp) merged=$(mktemp "${OUT}.XXXX")
trap 'rm -f "$new" "$hdr" "$merged"' EXIT

before= page=0 rows=0
while :; do
   q="limit=$LIMIT"
   [ -n "$before" ] && q="$q&before=$(jq -rn --arg v "$before" '$v|@uri')"

   status=
   for attempt in 1 2 3 4 5 6; do
      body=$(curl -sS -m 30 -A nexa-trades -D "$hdr" \
         -H "X-API-Key: $NEOXA_API_KEY" -H "X-API-Secret: $NEOXA_API_SECRET" \
         "$URL?$q") || body=
      status=$(awk 'NR==1{print $2}' "$hdr")
      [ "$status" = 200 ] && break
      wait=$(awk -F': *' 'tolower($1)=="retry-after"{print $2+0}' "$hdr")
      wait=${wait:-$((PAUSE * 2 ** attempt))}
      echo "page $((page + 1)): HTTP ${status:-?}, retry $attempt in ${wait}s" >&2
      sleep "$wait"
   done
   [ "$status" = 200 ] || { echo "giving up on page $((page + 1)): $body" >&2; exit 1; }

   n=$(jq '.trades | length' <<<"$body")
   jq -r --arg cols "$COLS" '.trades[] | [($cols | split(","))[] as $c | .[$c]] | @csv' <<<"$body" >>"$new"
   page=$((page + 1)) rows=$((rows + n))
   echo "page $page: $n trades" >&2

   [ "$(jq -r '.has_more // false' <<<"$body")" = true ] || break
   next=$(jq -r '.next_before // empty' <<<"$body")
   [ -n "$next" ] && [ "$next" != "$before" ] || break
   before=$next

   # stay well under the budget: if few requests are left, wait for the window to reset
   left=$(awk -F': *' 'tolower($1)=="ratelimit-remaining"{print $2+0}' "$hdr")
   reset=$(awk -F': *' 'tolower($1)=="ratelimit-reset"{print $2+0}' "$hdr")
   if [ -n "$left" ] && [ "$left" -lt 50 ]; then sleep "${reset:-60}"; else sleep "$PAUSE"; fi
done

# merge with what earlier runs saved: one row per trade_id, newest first (column 2 is ISO time)
before_count=0
[ -f "$OUT" ] && before_count=$(($(wc -l <"$OUT") - 1))
echo "$COLS" >"$merged"
{ [ -f "$OUT" ] && tail -n +2 "$OUT"; cat "$new"; } |
   awk -F, '!seen[$1]++' | sort -t, -k2,2r >>"$merged"
mv "$merged" "$OUT"
total=$(($(wc -l <"$OUT") - 1))
echo "fetched $rows, $((total - before_count)) new, $total trades in $OUT" >&2
