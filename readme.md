# Utility for various tasks you might need to do as a pleb

`bot` is a general purpose utility, it will do many things over time but for now its job is to help you 
gradually convert spamcoin or fed stablecoins into bitcoin.

The binaries are not provided deliberately, you should build your own binary from source and preferably review/contribute to it.

I chose kotlin native as an experiment partially to keep the binary very minimal, 
but to also try clojure style while keeping the code readable for humanoids. 

I use/maintain mac versions of the repo, so all the testing is most solid for mac, linux users should have no issues either.
If you're using microslop products i cannot provide any guarantees (i feel for you).

There is a `build.$platform.sh` for each OS that will guide you to build your binary.
There is a `run.$platform.sh` which has the hardcoded parameters that you are encouraged to modify and use for yourself.

## Zero Impact Trade (zit)

Since recent attacks on bitcoin we are limited to very few exchanges where we can trade actual bitcoin.
Liquidity is low so you do not want to execute anything in one shot on either neoxa or nonkyc.

ZIT is an algorithmic execution type that minimizes your market impact, trading fees and gets your trade done over long period of time.

I strongly advise to give it very long execution horizon and let it run.
It is fine if the command fails because of some interruption, internet/power.
It's designed to act correctly when you retry exact same command (it will cancel any orders on chosen market but nothing else and correctly resume).

Here is an example command

`bot nexa zit buy BTCB2_USDC 30`

This means you order the `bot` to use `zit` on `neoxa` to buy Bitcoin at the expense of all USDC gradually until 30 days from now.
It will not touch anything else, only that market.

When you run this you should see something like this:

```text
vach@mini bot % ./run.mac.sh
built: /Users/vach/shared/work/profile/agents/trader/code/bot/bin/bot
[14:20:57] [cycle 0] DEBUG fetching starting balances
[14:20:58] [cycle 0] DEBUG available: 952.30254489 USDC, 0.19019001 BTCB2
[14:20:58] [cycle 0] INFO  gradually buying 'BTCB2' with 952.30254489 USDC on BTCB2_USDC until 2026-10-15 14:00:00 UTC
[14:20:58] [cycle 0] DEBUG fetching active orders on BTCB2_USDC to cancel preexisting ones
[14:20:58] [cycle 0] DEBUG no preexisting orders on BTCB2_USDC
[14:20:58] [cycle 0] DEBUG window 0 elapsed, moving to next hour window
[14:20:59] [cycle 0] DEBUG window 0/96h: spent 0 of 952.30254489, unfilled 952.30254489 USDC
[14:20:59] [cycle 0] INFO  schedule 952.30254489 USDC over 96h = 9.91981818/h (even redistribution)
[14:20:59] [cycle 0] DEBUG fetching orderbook for BTCB2_USDC
[14:20:59] [cycle 0] DEBUG best bid 832.1 x 0.30375966 (50 bids / 50 asks)
[14:20:59] [cycle 0] DEBUG fetching our active orders on BTCB2_USDC
[14:20:59] [cycle 0] DEBUG 0 live order(s) with remaining qty
[14:20:59] [cycle 0] DEBUG placing post-only buy 0.01189763 BTCB2 @ 832.1 (budget 9.91981818 USDC)
[14:20:59] [cycle 0] INFO  placed: bid 0.01189763 BTCB2 @ 832.1 (budget 9.91981818 USDC, ORD-MV3WV3JW-6F1AAFBF)
[14:20:59] [cycle 0] DEBUG order resting, sleeping 30s before refresh

[14:21:29] [cycle 0] DEBUG refreshing order state on BTCB2_USDC
[14:21:30] [cycle 0] DEBUG order ORD-MV3WV3JW-6F1AAFBF partially active: 0/0.01189763 filled, remaining budget 9.90001792
[14:21:30] [cycle 1] DEBUG fetching orderbook for BTCB2_USDC
[14:21:30] [cycle 1] DEBUG best bid 832.1 x 0.31565729 (50 bids / 50 asks)
[14:21:30] [cycle 1] DEBUG fetching our active orders on BTCB2_USDC
[14:21:30] [cycle 1] DEBUG 1 live order(s) with remaining qty
[14:21:30] [cycle 1] DEBUG resting ORD-MV3WV3JW-6F1AAFBF already at best price with budget covered, keeping it
[14:21:30] [cycle 1] DEBUG order resting, sleeping 30s before refresh

[14:22:00] [cycle 1] DEBUG refreshing order state on BTCB2_USDC
[14:22:00] [cycle 1] DEBUG order ORD-MV3WV3JW-6F1AAFBF partially active: 0/0.01189763 filled, remaining budget 9.90001792
[14:22:00] [cycle 2] DEBUG fetching orderbook for BTCB2_USDC
[14:22:00] [cycle 2] DEBUG best bid 834.98 x 0.024398 (50 bids / 50 asks)
[14:22:00] [cycle 2] DEBUG fetching our active orders on BTCB2_USDC
[14:22:01] [cycle 2] DEBUG 1 live order(s) with remaining qty
[14:22:01] [cycle 2] DEBUG cancelling ORD-MV3WV3JW-6F1AAFBF @ 832.1 (repricing)
[14:22:01] [cycle 2] DEBUG placing post-only buy 0.01183292 BTCB2 @ 834.98 (budget 9.90001792 USDC)
[14:22:01] [cycle 2] INFO  repositioned: cancelled ORD-MV3WV3JW-6F1AAFBF, bid 0.01183292 BTCB2 @ 834.98 (budget 9.90001792 USDC, ORD-MV3WWFA6-A35274E7)
[14:22:01] [cycle 2] DEBUG order resting, sleeping 30s before refresh
```

The execution will compute how much USDC it should use every hour and will make sure you have the best bid,
at all times until that hours quota is filled.

The program will be explaining itself as it runs, what its trying to do and what it sees.
You can think of DEBUG logs as comments/reasoning what the program is trying to do.
INFO logs are actual important events when something of importance happens.
TRACE is for cases where you want to see absolutely everything, including http calls with the body etc...

## Example commands

Keys are read from environment variables, prefix them on the command line:

```
NEOXA_API_KEY=your_key NEOXA_API_SECRET=your_secret ./bin/bot nexa balances
```

or export them once in your shell:

```
export NEOXA_API_KEY=your_key
export NEOXA_API_SECRET=your_secret
```

exchange commands:

```
./bin/bot nexa balances
./bin/bot nexa orders
./bin/bot nexa orders partial BTCB2_USDC
./bin/bot nexa history 20
./bin/bot nexa order <order_id>
./bin/bot nexa place BTCB2_USDC buy 0.1 400 postonly
./bin/bot nexa cancel <order_id>
```

zero impact trades:

```
./bin/bot nexa zit buy BTCB2_USDC 30
./bin/bot nexa zit buy BTCB2_USDC 0.5
./bin/bot nexa zit buy BTCB2_USDC 30/10/2026
./bin/bot nexa zit sell BTCB2_USDC 7
```

simulation, no keys needed, virtual clock runs days in seconds (this is used for testing):

```
./bin/bot sim nexa zit buy BTCB2_USDC 30
./bin/bot sim nexa balances
```

`run.$platform` is my hardcoded launcher — build then one workload, copy and edit for yours:

```
./run.mac.sh
./run.linux.sh
run.microslop.bat
```

## Where to start

Ideally use a clanker (an LLM) of your choice and pick the right `run.$platform` file for your system and fix any missing dependencies until it runs.
It would be reckless to use api keys without IP whitelisting, if your'e going to run this on a machine that is live for long time, 
your IP is going to be relatively sticky. Find out whats your ip and whitelist the api key.

If you have issues go to : https://discord.gg/QD7MGhF4z and tag me `@vach`, i will be actively improving and maintaining this and adding new features.

## How to get latest updates

Only pull changes from master branch, every new commit there is a tested/consolidated unit of work.
All you need to do is `git pull` and rebuild your binary.
I will try to make this more accessible for non programmers over time.

## Coming next

- support for nonkyc
- support for zit to run for multiple pairs at the same time and trade on the most efficient one at a time
- ruthless cleanup of code and proper deterministic testing
