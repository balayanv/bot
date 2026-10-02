#!/bin/sh
# runner: builds then launches my workload in the simulator — copy and edit for yours.
# no keys needed, virtual clock runs days in seconds.
set -eu
cd "$(dirname "$0")"


./build.mac.sh

./bin/bot sim nexa zit buy BTCB2_USDC 30
