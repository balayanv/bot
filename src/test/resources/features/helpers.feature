Feature: small pure helpers

  Scenario Outline: numbers sent to exchanges are fixed 8dp strings
    When dec8 is applied to "<value>"
    Then the result is "<sent>"

    Examples:
      | value | sent          |
      | 0.0015 | 0.00150000   |
      | 696.8  | 696.80000000 |
      | 0      | 0.00000000   |

  Scenario Outline: numbers shown to humans drop trailing zeros
    When fmt is applied to "<value>"
    Then the result is "<shown>"

    Examples:
      | value         | shown        |
      | 9999.9999958  | 9999.9999958 |
      | 0.77229431123 | 0.77229431   |
      | 100.0         | 100          |
      | 0             | 0            |

  Scenario Outline: percentages round to 2 decimals
    When pct is applied to "<value>"
    Then the result is "<shown>"

    Examples:
      | value     | shown   |
      | 12.495    | 12.50%  |
      | 0         | 0.00%   |
      | 99.999999 | 100.00% |

  Scenario Outline: deadlines
    Given the clock is at "2026-10-15T15:26:00Z"
    When parseDeadline is applied to "<arg>"
    Then the result is "<deadline>"

    Examples:
      | arg        | deadline                |
      | 2          | 2026-10-17 15:00:00 UTC |
      | 0.5        | 2026-10-16 03:00:00 UTC |
      | 30/10/2026 | 2026-10-31 00:00:00 UTC |
      | 31/02/2026 | null                    |
      | 0          | null                    |
      | soon       | null                    |

  Scenario: json nulls are dropped, numbers keep their kind
    When parseJson is applied to:
      """
      {"a": null, "b": [1, 2.5, null], "c": "3"}
      """
    Then the result is:
      """
      {"b": [1, 2.5], "c": "3"}
      """

  Scenario: json round trip is compact and escapes strings
    When toJson is applied to:
      """
      {"say": "a \"quote\"", "n": [1, 2.5]}
      """
    Then the text is:
      """
      {"say":"a \"quote\"","n":[1,2.5]}
      """

  Scenario Outline: order quantities truncate to the market precision (buy escrows a 0.2% fee)
    When buying with <quote> quote at <price> on a market with <decimals> decimals
    Then the result is "<qty>"

    Examples:
      | quote      | price | decimals | qty       |
      | 0.77229431 | 696.8 | 4        | 0.0011    |
      | 10         | 500   | 4        | 0.0199    |
      | 416.66666667 | 349.4 | 8      | 1.19014023 |

  Scenario: selling truncates the base amount
    When selling 0.123456789 base on a market with 8 decimals
    Then the result is "0.12345678"

  Scenario Outline: the smallest accepted quantity rounds up
    When the minimum order of <notional> quote is priced at <price> with <decimals> decimals
    Then the result is "<qty>"

    Examples:
      | notional | price | decimals | qty    |
      | 1        | 696.8 | 4        | 0.0015 |
      | 1        | 500   | 4        | 0.002  |
      | 0        | 500   | 8        | 0.0    |
