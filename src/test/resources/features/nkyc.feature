Feature: nonkyc.io venue
  Each call is two pure functions: an input map becomes a request map, a response map
  becomes normalized data. Responses below are captured from the live api.

  Background:
    Given the input:
      """
      {"creds": {"NOKYC_API_KEY": "test-key", "NOKYC_API_SECRET": "test-secret"},
       "nonce": 1700000000000, "pair": "BTCB2/USDT"}
      """

  # ---- requests

  Scenario: listing our orders is a signed GET with the pair url-encoded
    When nkyc builds the orders request
    Then the result is:
      """
      {"method": "GET",
       "url": "https://api.nonkyc.io/api/v2/account/orders?status=active&symbol=BTCB2%2FUSDT",
       "headers": {"X-API-KEY": "test-key",
                   "X-API-NONCE": "1700000000000",
                   "X-API-SIGN": "b070fe757f062957ca395aa42ceb90c06098d7da4e580965ce7e023fe28fbd79"}}
      """

  Scenario: placing signs the exact json body it sends, with 8dp decimal strings
    Given the input:
      """
      {"creds": {"NOKYC_API_KEY": "test-key", "NOKYC_API_SECRET": "test-secret"},
       "nonce": 1700000000001, "pair": "BTCB2/USDT", "side": "buy", "qty": 0.0015, "price": 696.8}
      """
    When nkyc builds the place request
    Then the result is:
      """
      {"method": "POST",
       "url": "https://api.nonkyc.io/api/v2/createorder",
       "headers": {"X-API-KEY": "test-key",
                   "X-API-NONCE": "1700000000001",
                   "X-API-SIGN": "288214c9fff7bd0b51a9e55028c75577393dc251c0835882fc72639e08858dbb"},
       "body": "{\"symbol\":\"BTCB2/USDT\",\"side\":\"buy\",\"type\":\"limit\",\"quantity\":\"0.00150000\",\"price\":\"696.80000000\"}"}
      """

  Scenario: cancelling posts the order id
    Given the input:
      """
      {"creds": {"NOKYC_API_KEY": "test-key", "NOKYC_API_SECRET": "test-secret"},
       "nonce": 1700000000002, "id": "6ad0f0af0c60c2cf65fa27dd"}
      """
    When nkyc builds the cancel request
    Then the result has:
      """
      {"method": "POST", "url": "https://api.nonkyc.io/api/v2/cancelorder",
       "body": "{\"id\":\"6ad0f0af0c60c2cf65fa27dd\"}"}
      """

  Scenario: public market data is unsigned
    When nkyc builds the book request
    Then the result is:
      """
      {"method": "GET", "url": "https://api.nonkyc.io/api/v2/market/orderbook?symbol=BTCB2%2FUSDT&limit=1", "headers": {}}
      """

  Scenario: market rules are looked up by the underscore symbol
    When nkyc builds the market request
    Then the result has:
      """
      {"url": "https://api.nonkyc.io/api/v2/market/getbysymbol/BTCB2_USDT"}
      """

  # ---- responses

  Scenario: market rules give quantity precision and minimum order value
    Given a 200 response:
      """
      {"symbol": "BTCB2/USDT", "priceDecimals": 2, "quantityDecimals": 4, "isMinQuoteActive": true,
       "minQuote": 1, "minimumQuantity": 0, "bestBid": "696.80", "bestAsk": "697.99"}
      """
    When nkyc parses it as market
    Then the result is:
      """
      {"qtyDecimals": 4, "minNotional": 1}
      """

  Scenario: an inactive minimum means no minimum
    Given a 200 response:
      """
      {"symbol": "X/USDT", "quantityDecimals": 2, "isMinQuoteActive": false, "minQuote": 5}
      """
    When nkyc parses it as market
    Then the result is:
      """
      {"qtyDecimals": 2, "minNotional": 0}
      """

  Scenario: balances become asset -> available, string numbers parsed
    Given a 200 response:
      """
      [{"asset": "BTC", "name": "Bitcoin", "available": "0.00000000", "pending": "0.00000000", "held": "0.00000000", "assetid": "642adb1f638a16999e589a20"},
       {"asset": "BTCB2", "name": "XBT (BTCB2 / Blake2b fork)", "available": "0.55660000", "pending": "0.00000000", "held": "0.00000000", "assetid": "6a9ac7b05eba1fc0c907c964"},
       {"asset": "USDT", "name": "Tether USDT", "available": "109.39079626", "pending": "0.00000000", "held": "1.04729040", "assetid": "64367cb8fff6d79a1a316869"}]
      """
    When nkyc parses it as balances
    Then the result is:
      """
      {"BTC": 0, "BTCB2": 0.5566, "USDT": 109.39079626}
      """

  Scenario: the order list has no remainQuantity, so remaining = quantity - executed
    Given a 200 response:
      """
      [{"id": "6ad0f0af0c60c2cf65fa27dd", "userProvidedId": "6ea9c1ef-7545-48af-92ce-b7aaca87cb7c", "symbol": "BTCB2/USDT",
        "side": "BUY", "price": "696.8", "quantity": "0.0015", "executedQuantity": "0.0005", "status": "Partly Filled",
        "isActive": true, "timestamp": 1792077999889, "updatedAt": 1792077999949}]
      """
    When nkyc parses it as orders
    Then the result is:
      """
      [{"id": "6ad0f0af0c60c2cf65fa27dd", "price": 696.8, "qty": 0.0015, "remaining": 0.001}]
      """

  Scenario: finished orders returned despite status=active are dropped
    Given a 200 response:
      """
      [{"id": "a1", "symbol": "BTCB2/USDT", "side": "BUY", "price": "696.8", "quantity": "0.0015", "executedQuantity": "0", "status": "Active", "isActive": true},
       {"id": "a2", "symbol": "BTCB2/USDT", "side": "BUY", "price": "696.7", "quantity": "0.0015", "executedQuantity": "0", "status": "Cancelled", "isActive": false},
       {"id": "a3", "symbol": "BTCB2/USDT", "side": "BUY", "price": "696.6", "quantity": "0.0015", "executedQuantity": "0.0015", "status": "Filled"}]
      """
    When nkyc parses it as orders
    Then the result is:
      """
      [{"id": "a1", "price": 696.8, "qty": 0.0015, "remaining": 0.0015}]
      """

  Scenario: a freshly placed order has status New but is live
    Given a 200 response:
      """
      {"userProvidedId": "f1c7c6e0-53c1-4e13-ae31-1ce316cc9e19", "market": "6a9b94a0009228f648513624", "side": "buy", "type": "limit",
       "price": "696.80", "numberprice": 696.8, "quantity": "0.0015", "executedQuantity": "0.00", "remainQuantity": "0.0015",
       "remainTotal": "1.0452", "remainTotalWithFee": "1.0472904", "lastTradeAt": 0, "status": "New", "source": "api",
       "isActive": true, "isNew": true, "feeRate": 0.2, "createdAt": 1792077923395, "updatedAt": 1792077923395,
       "id": "6ad0f063faf202f6ce6b29ac"}
      """
    When nkyc parses it as place
    Then the result is:
      """
      {"id": "6ad0f063faf202f6ce6b29ac", "price": 696.8, "qty": 0.0015, "remaining": 0.0015}
      """

  Scenario: the book keeps price and quantity of each level, best first
    Given a 200 response:
      """
      {"marketid": "6a9b94a0009228f648513624", "symbol": "BTCB2/USDT", "timestamp": 1792078314198, "sequence": "45268",
       "bids": [{"price": "696.8", "quantity": "0.0024"}], "asks": [{"price": "697.99", "quantity": "0.0099"}]}
      """
    When nkyc parses it as book
    Then the result is:
      """
      {"bids": [{"price": 696.8, "qty": 0.0024}], "asks": [{"price": 697.99, "qty": 0.0099}]}
      """

  Scenario: cancelling an order that is already gone counts as cancelled
    Given a 400 response:
      """
      {"error": {"code": 20002, "message": "Not found", "description": "Active order not found for cancellation"}}
      """
    When nkyc parses it as cancel
    Then the result is nothing

  Scenario: errors carry the server's description, not just "Bad Request"
    Given a 400 response:
      """
      {"error": {"code": 10001, "message": "Bad Request", "description": "Minimum order value for this market is 1 USDT", "errorType": "minimumTradeAmount"}}
      """
    When nkyc parses it as place
    Then it fails with HTTP 400: "Minimum order value for this market is 1 USDT"

  Scenario: bad credentials
    Given a 401 response:
      """
      {"error": {"code": 401, "message": "Not Authorized"}}
      """
    When nkyc parses it as balances
    Then it fails with HTTP 401: "Not Authorized"
