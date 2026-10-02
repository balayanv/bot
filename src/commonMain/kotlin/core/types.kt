package core

typealias Data = MutableMap<String, Any>
typealias Rows = MutableList<Any>

typealias Percentage = Double
typealias Price = Double
typealias Qty = Double
typealias Money = Double

typealias Request = Data
typealias Response = Data
typealias http = suspend (Request) -> Response
typealias Credentials = Map<String, String>

typealias Level = Data
typealias OrderBook = Data
typealias Order = Data
typealias Balances = Data
