Feature: whole runs against the simulated market
  The simulator answers real venue requests (signing, parsing and all) on a virtual
  clock, starting with 10000 quote and 2 base, price swinging 350..450.

  Scenario: a one-day buy on nonkyc spends everything above the exchange minimum
    When I simulate "nkyc zit buy BTCB2/USDT 1"
    Then the result has:
      """
      {"code": 0}
      """
    And the result's quote is between -10000 and -9999

  Scenario: a one-day sell on neoxa sells everything
    When I simulate "nexa zit sell BTCB2_USDC 1"
    Then the result has:
      """
      {"code": 0}
      """
    And the result's base is between -2 and -1.9999

  Scenario: cycles worth less than the exchange minimum still trade
    When I simulate "nkyc zit buy BTCB2/USDT 20 2"
    Then the result has:
      """
      {"code": 0}
      """
    And the result's quote is between -10000 and -9950
