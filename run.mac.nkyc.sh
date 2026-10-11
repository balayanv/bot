#!/bin/sh
# runner: builds then launches my workload — copy and edit for yours.
set -eu
cd "$(dirname "$0")"


./build.sh

# export your keys before invoking, or prefix them on the command line:
# export NOKYC_API_KEY=your_key
# export NOKYC_API_SECRET=your_secret

./bin/bot nkyc zit buy BTCB2/USDT 2 20
