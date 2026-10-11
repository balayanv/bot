Feature: neoxa.exchange venue
  Plain key/secret headers, BASE_QUOTE pairs, post-only enforced by the server.
  Response shapes follow the fields the client reads (not captured from the live api).

  Background:
    Given the input:
      """
      {"creds": {"NEOXA_API_KEY": "test-key", "NEOXA_API_SECRET": "test-secret"},
       "nonce": 1700000000000, "pair": "BTCB2_USDC"}
      """

  # ---- requests

  Scenario: listing our orders sends the credentials as headers
    When nexa builds the orders request
    Then the result is:
      """
      {"method": "GET",
       "url": "https://neoxa.exchange/api/exchange/orders?status=active&pair=BTCB2_USDC",
       "headers": {"X-API-Key": "test-key", "X-API-Secret": "test-secret"}}
      """

  Scenario: placing is always post-only, quantities as 8dp strings
    Given the input:
      """
      {"creds": {"NEOXA_API_KEY": "test-key", "NEOXA_API_SECRET": "test-secret"},
       "pair": "BTCB2_USDC", "side": "sell", "qty": 0.04166666, "price": 350.6}
      """
    When nexa builds the place request
    Then the result is:
      """
      {"method": "POST",
       "url": "https://neoxa.exchange/api/exchange/order",
       "headers": {"X-API-Key": "test-key", "X-API-Secret": "test-secret"},
       "body": "{\"pair\":\"BTCB2_USDC\",\"side\":\"sell\",\"quantity\":\"0.04166666\",\"price\":\"350.60000000\",\"postOnly\":true}"}
      """

  Scenario: cancelling is a DELETE on the order
    Given the input:
      """
      {"creds": {"NEOXA_API_KEY": "test-key", "NEOXA_API_SECRET": "test-secret"}, "id": "ORD-MV3WV3JW-6F1AAFBF"}
      """
    When nexa builds the cancel request
    Then the result has:
      """
      {"method": "DELETE", "url": "https://neoxa.exchange/api/exchange/order/ORD-MV3WV3JW-6F1AAFBF"}
      """

  Scenario: the book is public
    When nexa builds the book request
    Then the result is:
      """
      {"method": "GET", "url": "https://neoxa.exchange/api/exchange/orderbook/BTCB2_USDC?depth=1", "headers": {}}
      """

  Scenario: market rules are known without asking
    When nexa builds the market request
    Then the result is nothing
    When nexa parses it as market
    Then the result is:
      """
      {"qtyDecimals": 8, "minNotional": 0}
      """

  # ---- responses

  Scenario: balances become asset -> available
    Given a 200 response:
      """
      {"balances": {"USDC": {"available": "952.30254489", "locked": "9.93965781", "total": "962.2422027"},
                    "BTCB2": {"available": "0.19019001", "locked": "0", "total": "0.19019001"}}}
      """
    When nexa parses it as balances
    Then the result is:
      """
      {"USDC": 952.30254489, "BTCB2": 0.19019001}
      """

  Scenario: orders are normalized and empty ones dropped
    Given a 200 response:
      """
      {"orders": [
        {"order_id": "ORD-MV3WV3JW-6F1AAFBF", "pair": "BTCB2_USDC", "side": "buy", "price": "832.1", "quantity": "0.01189763",
         "quantity_filled": "0.00189763", "quantity_remaining": "0.01", "status": "active"},
        {"order_id": "ORD-EMPTY", "pair": "BTCB2_USDC", "side": "buy", "price": "832.0", "quantity": "0.01",
         "quantity_filled": "0.01", "quantity_remaining": "0", "status": "active"}]}
      """
    When nexa parses it as orders
    Then the result is:
      """
      [{"id": "ORD-MV3WV3JW-6F1AAFBF", "price": 832.1, "qty": 0.01189763, "remaining": 0.01}]
      """

  Scenario: a placed order comes back wrapped
    Given a 200 response:
      """
      {"order": {"order_id": "ORD-MV3WWFA6-A35274E7", "pair": "BTCB2_USDC", "side": "buy", "price": "834.98",
                 "quantity": "0.01183292", "quantity_filled": "0", "quantity_remaining": "0.01183292", "status": "active"}}
      """
    When nexa parses it as place
    Then the result is:
      """
      {"id": "ORD-MV3WWFA6-A35274E7", "price": 834.98, "qty": 0.01183292, "remaining": 0.01183292}
      """

  Scenario: the book keeps price and quantity of each level
    Given a 200 response:
      """
      {"bids": [{"price": "832.1", "quantity": "0.30375966"}], "asks": [{"price": "834.99", "quantity": "0.1"}]}
      """
    When nexa parses it as book
    Then the result is:
      """
      {"bids": [{"price": 832.1, "qty": 0.30375966}], "asks": [{"price": 834.99, "qty": 0.1}]}
      """

  Scenario: a post-only order that would cross is rejected
    Given a 400 response:
      """
      {"error": "post-only order would cross"}
      """
    When nexa parses it as place
    Then it fails with HTTP 400: "post-only order would cross"

  Scenario: bad credentials
    Given a 401 response:
      """
      {"error": "Invalid API key"}
      """
    When nexa parses it as balances
    Then it fails with HTTP 401: "Invalid API key"
