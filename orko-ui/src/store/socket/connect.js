/*
 * Orko
 * Copyright © 2018-2019 Graham Crockford
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
import * as coinActions from "../coin/actions"
import * as notificationActions from "../notifications/actions"
import * as socketClient from "../../worker/socket.client.js"
import * as tickerActions from "../ticker/actions"
import * as socketActions from "../socket/actions"
import { locationToCoin } from "../../selectors/coins"
import { batchActions } from "redux-batched-actions"
import * as jobActions from "../job/actions"

var store
var actionBuffer = {}
var initialising = true
var jobFetch
var previousCoin

function subscribedCoins() {
  return store.getState().coins.coins
}

function selectedCoin() {
  return locationToCoin(store.getState().router.location)
}

const ACTION_KEY_ORDERBOOK = "orderbook"
const ACTION_KEY_ORDERS = "orders"
const ACTION_KEY_TRADE = "trade"
const ACTION_KEY_BALANCE = "balance"
const ACTION_KEY_TICKER = "ticker"

function bufferAction(key, action) {
  actionBuffer[key] = action
}

export function initialise(s, history) {
  store = s

  // Buffer and dispatch as a batch all the actions from the socket once a second
  const actionDispatch = () => {
    const batch = Object.values(actionBuffer)
    actionBuffer = {}
    store.dispatch(batchActions(batch))
  }
  setInterval(actionDispatch, 1000)

  // When the coin selected changes, send resubscription messages and clear any
  // coin-specific state
  history.listen(location => {
    const coin = locationToCoin(location)
    if (coin !== previousCoin) {
      previousCoin = coin
      console.log("Resubscribing following coin change")
      socketClient.changeSubscriptions(subscribedCoins(), coin)
      socketClient.resubscribe()
      store.dispatch(coinActions.setUserTrades(null))
      bufferAction(ACTION_KEY_ORDERBOOK, coinActions.setOrderBook(null))
      bufferAction(ACTION_KEY_ORDERS, coinActions.setOrders(null))
      bufferAction(ACTION_KEY_TRADE, coinActions.clearTrades())
      bufferAction(ACTION_KEY_BALANCE, coinActions.clearBalances())
      actionDispatch()
    }
  })

  // Sync the store state of the socket with the socket itself
  socketClient.onConnectionStateChange(connected => {
    const prevState = store.getState().socket.connected
    if (prevState !== connected) {
      store.dispatch(socketActions.setConnectionState(connected))
      if (connected) {
        if (initialising) {
          store.dispatch(notificationActions.localMessage("Socket connected"))
          initialising = false
        } else {
          store.dispatch(notificationActions.localAlert("Socket reconnected"))
        }
        resubscribe()
      } else {
        store.dispatch(notificationActions.localError("Socket disconnected"))
      }
    }
  })

  // Dispatch notifications etc to the store
  socketClient.onError(message =>
    store.dispatch(notificationActions.localError(message))
  )
  socketClient.onNotification(message =>
    store.dispatch(notificationActions.add(message))
  )
  socketClient.onStatusUpdate(message =>
    store.dispatch(notificationActions.statusUpdate(message))
  )

  // Dispatch market data to the store
  const sameCoin = (left, right) => left && right && left.key === right.key
  socketClient.onTicker((coin, ticker) =>
    bufferAction(
      ACTION_KEY_TICKER + "/" + coin.key,
      tickerActions.setTicker(coin, ticker)
    )
  )
  socketClient.onBalance((exchange, currency, balance) => {
    const coin = selectedCoin()
    if (
      coin &&
      coin.exchange === exchange &&
      (coin.base === currency || coin.counter === currency)
    ) {
      bufferAction(
        ACTION_KEY_BALANCE + "/" + exchange + "/" + currency,
        coinActions.setBalance(exchange, currency, balance)
      )
    }
  })
  socketClient.onOrderBook((coin, orderBook) => {
    if (sameCoin(coin, selectedCoin()))
      bufferAction(ACTION_KEY_ORDERBOOK, coinActions.setOrderBook(orderBook))
  })
  socketClient.onOrders((coin, orders, timestamp) => {
    if (sameCoin(coin, selectedCoin()))
      bufferAction(
        ACTION_KEY_ORDERS,
        coinActions.setOrders(orders.allOpenOrders, timestamp)
      )
  })
  socketClient.onTrade((coin, trade) => {
    if (sameCoin(coin, selectedCoin()))
      bufferAction(ACTION_KEY_TRADE + trade.id, coinActions.addTrade(trade))
  })
  socketClient.onUserTrade((coin, trade) => {
    if (sameCoin(coin, selectedCoin()))
      store.dispatch(coinActions.addUserTrade(trade))
  })
  socketClient.onUserTradeHistory((coin, trades) => {
    if (sameCoin(coin, selectedCoin()))
      store.dispatch(coinActions.setUserTrades(trades))
  })
  socketClient.onOrderStatusChange((coin, orderStatusChange, timestamp) => {
    if (sameCoin(coin, selectedCoin())) {
      if (orderStatusChange.type === "OPENED") {
        store.dispatch(
          coinActions.orderAdded(
            {
              currencyPair: {
                base: coin.base,
                counter: coin.counter
              },
              id: orderStatusChange.orderId,
              status: "NEW",
              timestamp: orderStatusChange.timestamp
            },
            timestamp
          )
        )
      } else if (orderStatusChange.type === "CLOSED") {
        store.dispatch(
          coinActions.orderRemoved(orderStatusChange.orderId, timestamp)
        )
      }
    }
  })
}

export function connect() {
  // Fetch and dispatch the job details on the server.
  // TODO this should really move to the socket, but for the time being
  // we'll fetch it on an interval.
  jobFetch = setInterval(() => {
    store.dispatch(jobActions.fetchJobs())
  }, 5000)
  socketClient.connect()
}

export function resubscribe() {
  socketClient.changeSubscriptions(subscribedCoins(), selectedCoin())
  socketClient.resubscribe()
}

export function disconnect() {
  socketClient.disconnect()
  clearInterval(jobFetch)
}
