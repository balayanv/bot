#!/bin/sh
# runner: builds then launches my workload — copy and edit for yours.
set -eu
cd "$(dirname "$0")"


./build.sh

# export your keys before invoking, or prefix them on the command line:
# export NEOXA_API_KEY=your_key
# export NEOXA_API_SECRET=your_secret

./bin/bot nexa zit buy BTCB2_USDC 4
