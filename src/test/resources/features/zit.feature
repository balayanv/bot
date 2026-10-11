Feature: zit plans each tick from what it observed
  plan is pure: the run's facts plus this tick's observation (now, available, our live
  orders, the book) in, the decision out. Here: buying with 100 USDT over 10 hourly
  cycles on a market taking 4 decimals and orders worth at least 1 USDT.

  Background:
    Given the state:
      """
      {"side": "buy", "total": 100, "escrow": 1.002, "serverPostOnly": false,
       "startTs": 1700000000, "endTs": 1700036000, "cycleSec": 3600, "cycles": 10,
       "qtyDecimals": 4, "minNotional": 1,
       "now": 1700000000, "available": 100, "orders": [],
       "book": {"bids": [{"price": 500, "qty": 1}], "asks": [{"price": 501, "qty": 1}]}}
      """

  Scenario: the first cycle gets an even share and joins the best bid
    When zit plans
    Then the result has:
      """
      {"newCycle": true, "window": 0, "spent": 0, "target": 10, "quota": 10,
       "action": "replace", "price": 500, "qty": 0.0199, "sleep": 15}
      """

  Scenario: our order already at the best bid for the quota is kept
    Given the state:
      """
      {"side": "buy", "total": 100, "escrow": 1.002, "serverPostOnly": false,
       "startTs": 1700000000, "endTs": 1700036000, "cycleSec": 3600, "cycles": 10,
       "qtyDecimals": 4, "minNotional": 1, "window": 0, "target": 10,
       "now": 1700000015, "available": 90.0301,
       "orders": [{"id": "a", "price": 500, "qty": 0.0199, "remaining": 0.0199}],
       "book": {"bids": [{"price": 500, "qty": 1}], "asks": [{"price": 501, "qty": 1}]}}
      """
    When zit plans
    Then the result has:
      """
      {"newCycle": false, "spent": 0, "quota": 10, "action": "keep"}
      """

  Scenario: progress shows how much of the goal is spent and how much of our order filled
    Given the state:
      """
      {"side": "buy", "total": 100, "escrow": 1.002, "serverPostOnly": false,
       "startTs": 1700000000, "endTs": 1700036000, "cycleSec": 3600, "cycles": 10,
       "qtyDecimals": 4, "minNotional": 1, "window": 0, "target": 10,
       "now": 1700000300, "available": 85.0, "orders": [{"id": "a", "price": 500, "qty": 0.02, "remaining": 0.005}],
       "book": {"bids": [{"price": 500, "qty": 1}], "asks": [{"price": 501, "qty": 1}]}}
      """
    When zit plans
    Then the result has:
      """
      {"spent": 12.495, "goalPct": 12.495, "orderPct": 75}
      """

  Scenario: without a live order there is no order progress
    When zit plans
    Then the result has:
      """
      {"goalPct": 0}
      """
    And the result has no orderPct

  Scenario: outbid, the order is moved up to the new best bid
    Given the state:
      """
      {"side": "buy", "total": 100, "escrow": 1.002, "serverPostOnly": false,
       "startTs": 1700000000, "endTs": 1700036000, "cycleSec": 3600, "cycles": 10,
       "qtyDecimals": 4, "minNotional": 1, "window": 0, "target": 10,
       "now": 1700000015, "available": 90.0301,
       "orders": [{"id": "a", "price": 499.5, "qty": 0.0199, "remaining": 0.0199}],
       "book": {"bids": [{"price": 500, "qty": 1}], "asks": [{"price": 501, "qty": 1}]}}
      """
    When zit plans
    Then the result has:
      """
      {"action": "replace", "price": 500}
      """

  Scenario: once the cycle's quota is spent zit sleeps until the next cycle
    Given the state:
      """
      {"side": "buy", "total": 100, "escrow": 1.002, "serverPostOnly": false,
       "startTs": 1700000000, "endTs": 1700036000, "cycleSec": 3600, "cycles": 10,
       "qtyDecimals": 4, "minNotional": 1, "window": 0, "target": 10,
       "now": 1700000100, "available": 90, "orders": [],
       "book": {"bids": [{"price": 500, "qty": 1}], "asks": [{"price": 501, "qty": 1}]}}
      """
    When zit plans
    Then the result has:
      """
      {"spent": 10, "quota": 0, "action": "idle", "sleep": 3500}
      """

  Scenario: a new cycle spreads what is unspent over the cycles left
    Given the state:
      """
      {"side": "buy", "total": 100, "escrow": 1.002, "serverPostOnly": false,
       "startTs": 1700000000, "endTs": 1700036000, "cycleSec": 3600, "cycles": 10,
       "qtyDecimals": 4, "minNotional": 1, "window": 0, "target": 10,
       "now": 1700003600, "available": 95, "orders": [],
       "book": {"bids": [{"price": 500, "qty": 1}], "asks": [{"price": 501, "qty": 1}]}}
      """
    When zit plans
    Then the result has:
      """
      {"newCycle": true, "window": 1, "spent": 5, "target": 15.555555555555555, "quota": 10.555555555555555}
      """

  Scenario: a quota below the exchange minimum still places the minimum order
    Given the state:
      """
      {"side": "buy", "total": 5, "escrow": 1.002, "serverPostOnly": false,
       "startTs": 1700000000, "endTs": 1700036000, "cycleSec": 3600, "cycles": 10,
       "qtyDecimals": 4, "minNotional": 1,
       "now": 1700000000, "available": 5, "orders": [],
       "book": {"bids": [{"price": 500, "qty": 1}], "asks": [{"price": 501, "qty": 1}]}}
      """
    When zit plans
    Then the result has:
      """
      {"quota": 0.5, "action": "replace", "qty": 0.002}
      """

  Scenario: without server post-only zit won't join a crossed book
    Given the state:
      """
      {"side": "buy", "total": 100, "escrow": 1.002, "serverPostOnly": false,
       "startTs": 1700000000, "endTs": 1700036000, "cycleSec": 3600, "cycles": 10,
       "qtyDecimals": 4, "minNotional": 1,
       "now": 1700000000, "available": 100, "orders": [],
       "book": {"bids": [{"price": 500, "qty": 1}], "asks": [{"price": 499, "qty": 1}]}}
      """
    When zit plans
    Then the result has:
      """
      {"action": "wait"}
      """

  Scenario: with server post-only a crossed book is the server's problem
    Given the state:
      """
      {"side": "buy", "total": 100, "escrow": 1.002, "serverPostOnly": true,
       "startTs": 1700000000, "endTs": 1700036000, "cycleSec": 3600, "cycles": 10,
       "qtyDecimals": 4, "minNotional": 1,
       "now": 1700000000, "available": 100, "orders": [],
       "book": {"bids": [{"price": 500, "qty": 1}], "asks": [{"price": 499, "qty": 1}]}}
      """
    When zit plans
    Then the result has:
      """
      {"action": "replace"}
      """

  Scenario: nothing to join on an empty side
    Given the state:
      """
      {"side": "buy", "total": 100, "escrow": 1.002, "serverPostOnly": false,
       "startTs": 1700000000, "endTs": 1700036000, "cycleSec": 3600, "cycles": 10,
       "qtyDecimals": 4, "minNotional": 1,
       "now": 1700000000, "available": 100, "orders": [],
       "book": {"bids": [], "asks": [{"price": 501, "qty": 1}]}}
      """
    When zit plans
    Then the result has:
      """
      {"action": "wait", "why": "empty bid side"}
      """

  Scenario: selling sizes in base and joins the best ask
    Given the state:
      """
      {"side": "sell", "total": 1, "escrow": 1.002, "serverPostOnly": false,
       "startTs": 1700000000, "endTs": 1700036000, "cycleSec": 3600, "cycles": 10,
       "qtyDecimals": 4, "minNotional": 1,
       "now": 1700000000, "available": 1, "orders": [],
       "book": {"bids": [{"price": 500, "qty": 1}], "asks": [{"price": 501, "qty": 1}]}}
      """
    When zit plans
    Then the result has:
      """
      {"quota": 0.1, "action": "replace", "price": 501, "qty": 0.1}
      """

  Scenario: the deadline stops the run
    Given the state:
      """
      {"side": "buy", "total": 100, "escrow": 1.002, "serverPostOnly": false,
       "startTs": 1700000000, "endTs": 1700036000, "cycleSec": 3600, "cycles": 10,
       "qtyDecimals": 4, "minNotional": 1,
       "now": 1700036000, "available": 40, "orders": [],
       "book": {"bids": [{"price": 500, "qty": 1}], "asks": [{"price": 501, "qty": 1}]}}
      """
    When zit plans
    Then the result has:
      """
      {"action": "stop", "why": "deadline reached"}
      """

  Scenario: a leftover too small for any order stops the run
    Given the state:
      """
      {"side": "buy", "total": 100, "escrow": 1.002, "serverPostOnly": false,
       "startTs": 1700000000, "endTs": 1700036000, "cycleSec": 3600, "cycles": 10,
       "qtyDecimals": 4, "minNotional": 1,
       "now": 1700030000, "available": 0.5, "orders": [],
       "book": {"bids": [{"price": 500, "qty": 1}], "asks": [{"price": 501, "qty": 1}]}}
      """
    When zit plans
    Then the result has:
      """
      {"action": "stop", "spent": 99.5}
      """
