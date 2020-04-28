package com.gruelbox.orko.exchange;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.gruelbox.orko.exchange.MarketDataType.BALANCE;
import static com.gruelbox.orko.exchange.MarketDataType.USER_TRADE;
import static java.time.LocalDateTime.now;
import static java.time.temporal.ChronoUnit.MINUTES;
import static java.util.stream.Collectors.toSet;
import static org.knowm.xchange.dto.Order.OrderType.ASK;
import static org.knowm.xchange.dto.Order.OrderType.BID;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.google.common.util.concurrent.AbstractExecutionThreadService;
import com.google.common.util.concurrent.Service;
import com.gruelbox.orko.notification.NotificationService;
import com.gruelbox.orko.spi.TickerSpec;
import com.gruelbox.orko.util.CheckedExceptions.ThrowingRunnable;
import com.gruelbox.orko.util.SafelyDispose;
import info.bitrich.xchangestream.core.ProductSubscription;
import info.bitrich.xchangestream.core.ProductSubscription.ProductSubscriptionBuilder;
import info.bitrich.xchangestream.core.StreamingExchange;
import io.reactivex.Completable;
import io.reactivex.CompletableEmitter;
import io.reactivex.CompletableOnSubscribe;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.bitfinex.v1.dto.BitfinexExceptionV1;
import org.knowm.xchange.currency.Currency;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.account.Balance;
import org.knowm.xchange.dto.account.Wallet;
import org.knowm.xchange.dto.marketdata.OrderBook;
import org.knowm.xchange.dto.marketdata.Trade;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.dto.trade.OpenOrders;
import org.knowm.xchange.exceptions.ExchangeException;
import org.knowm.xchange.exceptions.ExchangeSecurityException;
import org.knowm.xchange.exceptions.ExchangeUnavailableException;
import org.knowm.xchange.exceptions.FrequencyLimitExceededException;
import org.knowm.xchange.exceptions.NonceException;
import org.knowm.xchange.exceptions.NotAvailableFromExchangeException;
import org.knowm.xchange.exceptions.NotYetImplementedForExchangeException;
import org.knowm.xchange.exceptions.RateLimitExceededException;
import org.knowm.xchange.service.account.AccountService;
import org.knowm.xchange.service.marketdata.MarketDataService;
import org.knowm.xchange.service.trade.TradeService;
import org.knowm.xchange.service.trade.params.TradeHistoryParamCurrencyPair;
import org.knowm.xchange.service.trade.params.TradeHistoryParamLimit;
import org.knowm.xchange.service.trade.params.TradeHistoryParamPaging;
import org.knowm.xchange.service.trade.params.TradeHistoryParams;
import org.knowm.xchange.service.trade.params.orders.DefaultOpenOrdersParamCurrencyPair;
import org.knowm.xchange.service.trade.params.orders.OpenOrdersParamCurrencyPair;
import org.knowm.xchange.service.trade.params.orders.OpenOrdersParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import si.mazi.rescu.HttpStatusIOException;

/**
 * Handles the market data polling and subscription cycle for an exchange.
 *
 * @author Graham Crockford
 */
@Slf4j
final class ExchangePollLoop extends AbstractExecutionThreadService {
  
  private static final int MAX_TRADES = 20;
  private static final int ORDERBOOK_DEPTH = 20;
  private static final int MINUTES_BETWEEN_EXCEPTION_NOTIFICATIONS = 15;

  private final String exchangeName;
  private final Exchange exchange;
  private final StreamingExchange streamingExchange;
  private final Supplier<AccountService> accountServiceSupplier;
  private final Supplier<TradeService> tradeServiceSupplier;
  private final RateController rateController;
  private final NotificationService notificationService;
  private final SubscriptionPublisher publisher;
  private final long blockSeconds;
  private final boolean authenticated;

  private LifecycleListener lifecycleListener = new LifecycleListener() {};
  private AccountService accountService;
  private MarketDataService marketDataService;
  private TradeService tradeService;

  private final Phaser phaser = new Phaser(1);
  private int phase;
  private boolean subscriptionsFailed;
  private Exception lastPollException;
  private LocalDateTime lastPollErrorNotificationTime;

  private AtomicReference<Set<Subscription>> nextSubscriptions = new AtomicReference<>();
  private Set<Subscription> subscriptions = Set.of();
  private Set<Subscription> polls = Set.of();
  private Collection<Disposable> disposables = List.of();
  private Set<Subscription> unavailableSubscriptions = Set.of();

  // TODO FIx this
  private final ConcurrentMap<TickerSpec, Instant> mostRecentTrades = Maps.newConcurrentMap();

  ExchangePollLoop(String exchangeName, Exchange exchange,
      Supplier<AccountService> accountServiceSupplier,
      Supplier<TradeService> tradeServiceSupplier,
      RateController rateController,
      NotificationService notificationService,
      SubscriptionPublisher publisher,
      long blockSeconds,
      boolean authenticated) {

    this.exchangeName = exchangeName;
    this.exchange = exchange;
    this.streamingExchange =
        exchange instanceof StreamingExchange
            ? (StreamingExchange) exchange
            : null;
    this.accountServiceSupplier = accountServiceSupplier;
    this.tradeServiceSupplier = tradeServiceSupplier;
    this.rateController = rateController;
    this.notificationService = notificationService;
    this.publisher = publisher;
    this.blockSeconds = blockSeconds;
    this.authenticated = authenticated;
  }

  @VisibleForTesting
  public void setLifecycleListener(LifecycleListener lifecycleListener) {
    this.lifecycleListener = lifecycleListener;
  }

  public String getExchangeName() {
    return exchangeName;
  }

  public void updateSubscriptions(Iterable<Subscription> subscriptions) {
    log.debug("Requesting update of subscriptions for {} to {}", exchangeName, subscriptions);
    nextSubscriptions.set(ImmutableSet.copyOf(subscriptions));
    wake();
  }

  @Override
  protected void run() {
    Thread.currentThread().setName(exchangeName);
    log.info("{} starting", exchangeName);
    try {
      initialise();
      while (!phaser.isTerminated()) {

        // Before we check for the presence of polls, determine which phase
        // we are going to wait for if there's no work to do - i.e. the
        // next wakeup.
        phase = phaser.getPhase();
        if (phase == -1) break;

        loop();
      }
      log.info("{} shutting down due to termination", exchangeName);
    } catch (InterruptedException e) {
      log.info("{} shutting down due to interrupt", exchangeName);
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      log.error(exchangeName + " shutting down due to uncaught exception", e);
    } finally {
      log.debug("{} sending shutdown event", exchangeName);
      lifecycleListener.onStop(exchangeName);
    }
  }

  @Override
  protected void triggerShutdown() {
    log.debug("Triggering shut down of {} poll loop", exchangeName);
    phaser.arriveAndDeregister();
    phaser.forceTermination();
  }

  /**
   * This may fail when the exchange is not available, so keep trying.
   *
   * @throws InterruptedException If interrupted while sleeping.
   */
  private void initialise() throws InterruptedException {
    while (!phaser.isTerminated()) {
      try {
        if (Thread.interrupted()) {
          throw new InterruptedException();
        }
        this.accountService = accountServiceSupplier.get();
        this.marketDataService = exchange.getMarketDataService();
        this.tradeService = tradeServiceSupplier.get();
        break;
      } catch (InterruptedException e) {
        throw e;
      } catch (Exception e) {
        log.error(exchangeName + " - failing initialising. Will retry in one minute.", e);
        Thread.sleep(60000);
      }
    }
  }

  private void loop() throws InterruptedException {

    // Check if there is a queued subscription change.  If so, apply it
    doSubscriptionChanges();

    // Check if we have any polling to do. If not, go to sleep until awoken
    // by a subscription change, unless we failed to process subscriptions,
    // in which case wake ourselves up in a few seconds to try again
    Set<Subscription> polls = activePolls();
    if (polls.isEmpty()) {
      suspend(exchangeName, phase, subscriptionsFailed);
      return;
    }

    log.debug("{} - start poll", exchangeName);
    Set<Currency> balanceCurrencies = new HashSet<>();
    for (Subscription subscription : polls) {
      if (phaser.isTerminated()) break;
      if (subscription.type().equals(BALANCE)) {
        balanceCurrencies.add(subscription.currencyPair().base);
        balanceCurrencies.add(subscription.currencyPair().counter);
      } else {
        fetchAndBroadcast(subscription);
      }
    }

    if (phaser.isTerminated()) return;

    // We'll be extending this sort of batching to more market data types...
    if (!balanceCurrencies.isEmpty()) {
      manageExchangeExceptions(
          "Balances",
          () ->
              fetchBalances(balanceCurrencies)
                  .forEach(b -> publisher.emit(BalanceEvent.create(exchangeName, b))),
          () -> FluentIterable.from(polls).filter(s -> s.type().equals(BALANCE)));
    }
  }

  private void suspend(String subTaskName, int phase, boolean failed) throws InterruptedException {
    log.debug("{} - poll going to sleep", subTaskName);
    try {
      if (failed) {
        phaser.awaitAdvanceInterruptibly(phase, blockSeconds * 1000L, TimeUnit.MILLISECONDS);
      } else {
        log.debug("{} - sleeping until phase {}", subTaskName, phase);
        lifecycleListener.onBlocked(subTaskName);
        phaser.awaitAdvanceInterruptibly(phase);
        log.debug("{} - poll woken up on request", subTaskName);
      }
    } catch (TimeoutException e) {
      // fine
    } catch (InterruptedException e) {
      throw e;
    } catch (Exception e) {
      log.error("Failure in phaser wait for " + subTaskName, e);
    }
  }

  private void wake() {
    int phase = phaser.arrive();
    log.debug("Progressing to phase {}", phase);
  }

  private void manageExchangeExceptions(
      String dataDescription,
      ThrowingRunnable runnable,
      Supplier<Iterable<Subscription>> toUnsubscribe)
      throws InterruptedException {
    try {
      runnable.run();

    } catch (InterruptedException e) {
      throw e;

    } catch (UnsupportedOperationException e) {

      // Disable the feature since XChange doesn't provide support for it.
      log.warn(
          "{} not available: {} ({})",
          dataDescription,
          e.getClass().getSimpleName(),
          exceptionMessage(e));
      Iterables.addAll(unavailableSubscriptions, toUnsubscribe.get());

    } catch (SocketTimeoutException
        | SocketException
        | ExchangeUnavailableException
        | NonceException e) {

      // Managed connectivity issues.
      log.warn(
          "Throttling {} - {} ({}) when fetching {}",
          exchangeName,
          e.getClass().getSimpleName(),
          exceptionMessage(e),
          dataDescription);
      rateController.throttle();

    } catch (HttpStatusIOException e) {

      handleHttpStatusException(dataDescription, e);

    } catch (RateLimitExceededException | FrequencyLimitExceededException e) {

      log.error(
          "Hit rate limiting on {} when fetching {}. Backing off", exchangeName, dataDescription);
      notificationService.error(
          "Getting rate limiting errors on "
              + exchangeName
              + ". Pausing access and will "
              + "resume at a lower rate.");
      rateController.backoff();
      rateController.pause();

    } catch (ExchangeException e) {
      if (e.getCause() instanceof HttpStatusIOException) {
        // TODO Bitmex is inappropriately wrapping these and should be fixed
        // for consistency. In the meantime...
        handleHttpStatusException(dataDescription, (HttpStatusIOException) e.getCause());
      } else {
        handleUnknownPollException(e);
      }
    } catch (BitfinexExceptionV1 e) {
      handleUnknownPollException(
          new ExchangeException(
              "Bitfinex exception: " + exceptionMessage(e) + " (error code=" + e.getError() + ")",
              e));
    } catch (Exception e) {
      handleUnknownPollException(e);
    }
  }

  private void handleHttpStatusException(String dataDescription, HttpStatusIOException e) {
    if (e.getHttpStatusCode() == 408
        || e.getHttpStatusCode() == 502
        || e.getHttpStatusCode() == 504
        || e.getHttpStatusCode() == 521) {
      // Usually these are rejections at CloudFlare (Coinbase Pro & Kraken being common cases) or
      // connection timeouts.
      if (log.isWarnEnabled()) {
        log.warn(
            "Throttling {} - failed at gateway ({} - {}) when fetching {}",
            exchangeName,
            e.getHttpStatusCode(),
            exceptionMessage(e),
            dataDescription);
      }
      rateController.throttle();
    } else {
      handleUnknownPollException(e);
    }
  }

  private String exceptionMessage(Throwable e) {
    if (e.getMessage() == null) {
      if (e.getCause() == null) {
        return "No description";
      } else {
        return exceptionMessage(e.getCause());
      }
    } else {
      return e.getMessage();
    }
  }

  private void handleUnknownPollException(Exception e) {
    LocalDateTime now = now();
    String exceptionMessage = exceptionMessage(e);
    if (lastPollException == null
        || !lastPollException.getClass().equals(e.getClass())
        || !firstNonNull(exceptionMessage(lastPollException), "").equals(exceptionMessage)
        || lastPollErrorNotificationTime.until(now, MINUTES)
            > MINUTES_BETWEEN_EXCEPTION_NOTIFICATIONS) {
      lastPollErrorNotificationTime = now;
      log.error("Error fetching data for {}", exchangeName, e);
      notificationService.error(
          "Throttling access to "
              + exchangeName
              + " due to server error ("
              + e.getClass().getSimpleName()
              + " - "
              + exceptionMessage
              + ")");
    } else {
      log.error("Repeated error fetching data for {} ({})", exchangeName, exceptionMessage);
    }
    lastPollException = e;
    rateController.throttle();
  }

  /** Actually performs the subscription changes. Occurs synchronously in the poll loop. */
  private void doSubscriptionChanges() {
    log.debug("{} - start subscription check", exchangeName);
    subscriptionsFailed = false;

    // Pull the subscription change off the queue. If there isn't one,
    // we're done
    Set<Subscription> newSubscriptions = nextSubscriptions.getAndSet(null);
    if (newSubscriptions == null) return;

    try {

      // Get the current subscriptions
      Set<Subscription> oldSubscriptions =
          Streams.concat(subscriptions.stream(), polls.stream())
              .collect(toSet());

      // If there's no difference, we're good, done
      if (newSubscriptions.equals(oldSubscriptions)) {
        return;
      }

      // Otherwise, let's crack on
      log.info(
          "{} - updating subscriptions to: {} from {}",
          exchangeName,
          newSubscriptions,
          oldSubscriptions);

      // Disconnect any streaming exchanges where the tickers currently
      // subscribed mismatch the ones we want.
      if (!oldSubscriptions.isEmpty()) {
        disconnect();
      }

      // Clear cached tickers and order books for anything we've unsubscribed so that we don't
      // feed out-of-date data
      Sets.difference(oldSubscriptions, newSubscriptions)
          .forEach(s -> publisher.clearCacheForSubscription(toMarketDataSubscription(s)));

      // Add new subscriptions if we have any
      if (newSubscriptions.isEmpty()) {
        polls = Set.of();
        log.debug("{} - polls cleared", exchangeName);
      } else {
        subscribe(newSubscriptions);
      }
    } catch (Exception e) {
      subscriptionsFailed = true;
      log.error("Error updating subscriptions", e);
      if (nextSubscriptions.compareAndSet(null, newSubscriptions)) {
        wake();
      }
      throw e;
    }
  }

  private MarketDataSubscription toMarketDataSubscription(Subscription s) {
    return MarketDataSubscription.create(toSpec(s), s.type());
  }

  private TickerSpec toSpec(Subscription s) {
    return toSpec(s.currencyPair());
}

  private TickerSpec toSpec(CurrencyPair currencyPair) {
    return TickerSpec.builder()
        .exchange(exchangeName)
        .base(currencyPair.base.getCurrencyCode())
        .counter(currencyPair.counter.getCurrencyCode())
        .build();
  }

  private Set<Subscription> activePolls() {
    return polls.stream()
        .filter(s -> !unavailableSubscriptions.contains(s))
        .collect(toSet());
  }

  private void disconnect() {
    if (streamingExchange != null) {
      SafelyDispose.of(disposables);
      disposables = Set.of();
      try {
        streamingExchange.disconnect().blockingAwait();
      } catch (Exception e) {
        log.error("Error disconnecting from " + exchangeName, e);
      }
    } else {
      mostRecentTrades.entrySet().removeIf(
          entry -> entry.getKey().exchange().equals(exchangeName));
    }
  }

  private void subscribe(Set<Subscription> newSubscriptions) {

    Builder<Subscription> pollingBuilder = ImmutableSet.builder();

    if (streamingExchange != null) {
      Set<Subscription> remainingSubscriptions =
          openSubscriptionsWherePossible(newSubscriptions);
      pollingBuilder.addAll(remainingSubscriptions);
    } else {
      pollingBuilder.addAll(newSubscriptions);
    }

    polls = pollingBuilder.build();
    log.debug("{} - polls now set to: {}", exchangeName, polls);
  }

  private Set<Subscription> openSubscriptionsWherePossible(
      Set<Subscription> newSubscriptions) {

    connectExchange(newSubscriptions);

    HashSet<Subscription> connected = new HashSet<>(newSubscriptions);
    Builder<Subscription> remainder = ImmutableSet.builder();
    List<Disposable> disposables = new ArrayList<>();

    Consumer<Subscription> markAsNotSubscribed =
        s -> {
          remainder.add(s);
          connected.remove(s);
        };

    Set<Currency> balanceCurrencies = new HashSet<>();
    for (Subscription s : newSubscriptions) {

      // User trade and balance subscriptions, for now, we will poll even if we are
      // already getting them from the socket. This will persist until we can
      // safely detect and correct ordering/missed messages on the socket streams.
      if (s.type().equals(USER_TRADE) || s.type().equals(BALANCE)) {
        remainder.add(s);
      }

      if (s.type().equals(BALANCE)) {
        // Aggregate the currencies and do these next
        balanceCurrencies.add(s.currencyPair().base);
        balanceCurrencies.add(s.currencyPair().counter);
      } else {
        try {
          disposables.add(connectSubscription(s));
        } catch (UnsupportedOperationException | ExchangeSecurityException e) {
          log.debug(
              "Not subscribing to {} on socket due to {}: {}",
              s.key(),
              e.getClass().getSimpleName(),
              e.getMessage());
          markAsNotSubscribed.accept(s);
        }
      }
    }

    try {
      for (Currency currency : balanceCurrencies) {
        disposables.add(
            streamingExchange
                .getStreamingAccountService()
                .getBalanceChanges(currency, "exchange") // TODO bitfinex walletId. Should manage multiple wallets properly
                .map(b -> BalanceEvent.create(exchangeName, b)) // TODO consider timestamping?
                .subscribe(
                    publisher::emit,
                    e ->
                        log.error(
                            "Error in balance stream for " + exchangeName + "/" + currency, e)));
      }
    } catch (NotAvailableFromExchangeException e) {
      newSubscriptions.stream().filter(s -> s.type().equals(BALANCE)).forEach(markAsNotSubscribed);
    } catch (ExchangeSecurityException | NotYetImplementedForExchangeException e) {
      log.debug(
          "Not subscribing to {}/{} on socket due to {}: {}",
          exchangeName,
          "Balances",
          e.getClass().getSimpleName(),
          e.getMessage());
      newSubscriptions.stream().filter(s -> s.type().equals(BALANCE)).forEach(markAsNotSubscribed);
    }

    this.subscriptions = Collections.unmodifiableSet(connected);
    this.disposables = disposables;
    return remainder.build();
  }

  private Disposable connectSubscription(Subscription sub) {
    TickerSpec spec = toSpec(sub);
    switch (sub.type()) {
      case ORDERBOOK:
        return streamingExchange
            .getStreamingMarketDataService()
            .getOrderBook(sub.currencyPair())
            .map(t -> OrderBookEvent.create(spec, t))
            .subscribe(
                publisher::emit, e -> log.error("Error in order book stream for " + sub, e));
      case TICKER:
        return streamingExchange
            .getStreamingMarketDataService()
            .getTicker(sub.currencyPair())
            .map(t -> TickerEvent.create(spec, t))
            .subscribe(
                publisher::emit, e -> log.error("Error in ticker stream for " + sub, e));
      case TRADES:
        return streamingExchange
            .getStreamingMarketDataService()
            .getTrades(sub.currencyPair())
            .map(t -> convertBinanceOrderType(sub, t))
            .map(t -> TradeEvent.create(spec, t))
            .subscribe(publisher::emit, e -> log.error("Error in trade stream for " + sub, e));
      case USER_TRADE:
        return streamingExchange
            .getStreamingTradeService()
            .getUserTrades(sub.currencyPair())
            .map(t -> UserTradeEvent.create(spec, t))
            .subscribe(publisher::emit, e -> log.error("Error in trade stream for " + sub, e));
      case ORDER:
        return streamingExchange
            .getStreamingTradeService()
            .getOrderChanges(sub.currencyPair())
            .map(
                t ->
                    OrderChangeEvent.create(
                        spec, t, new Date())) // TODO need server side timestamping
            .subscribe(publisher::emit, e -> log.error("Error in order stream for " + sub, e));
      default:
        throw new NotAvailableFromExchangeException();
    }
  }

  /**
   * TODO Temporary fix for https://github.com/knowm/XChange/issues/2468#issuecomment-441440035
   */
  private Trade convertBinanceOrderType(Subscription sub, Trade t) {
    if (exchangeName.equals(Exchanges.BINANCE)) {
      return Trade.Builder.from(t).type(t.getType() == BID ? ASK : BID).build();
    } else {
      return t;
    }
  }

  private void connectExchange(Collection<Subscription> subscriptionsForExchange) {
    if (subscriptionsForExchange.isEmpty()) return;
    log.info("Connecting to exchange: {}", exchangeName);
    ProductSubscriptionBuilder builder = ProductSubscription.create();
    subscriptionsForExchange.forEach(
        s -> {
          switch (s.type()) {
            case TICKER:
              builder.addTicker(s.currencyPair());
              break;
            case ORDERBOOK:
              builder.addOrderbook(s.currencyPair());
              break;
            case TRADES:
              builder.addTrades(s.currencyPair());
              break;
            case ORDER:
              if (authenticated) {
                builder.addOrders(s.currencyPair());
              }
              break;
            case USER_TRADE:
              if (authenticated) {
                builder.addUserTrades(s.currencyPair());
              }
              break;
            case BALANCE:
              if (authenticated) {
                builder.addBalances(s.currencyPair().base);
                builder.addBalances(s.currencyPair().counter);
              }
              break;
            default:
              // Not available from socket
          }
        });
    rateController.acquire();
    streamingExchange.connect(builder.build()).blockingAwait();
    log.info("Connected to exchange: {}", exchangeName);
  }

  private Iterable<Balance> fetchBalances(Collection<Currency> currencies) throws IOException {
    Map<Currency, Balance> result = new HashMap<>();
    currencies.stream()
        .forEach(currency -> result.put(currency, Balance.zero(currency)));
    wallet().getBalances().entrySet().stream()
        .map(Entry::getValue)
        .filter(balance -> currencies.contains(balance.getCurrency()))
        .forEach(balance -> result.put(balance.getCurrency(), balance));
    return result.values();
  }

  private Wallet wallet() throws IOException {
    rateController.acquire();
    Wallet wallet;
    if (exchangeName.equals(Exchanges.BITFINEX)) {
      wallet = accountService.getAccountInfo().getWallet("exchange");
    } else if (exchangeName.equals(Exchanges.KUCOIN)) {
      wallet = accountService.getAccountInfo().getWallet("trade");
      if (wallet == null) wallet = accountService.getAccountInfo().getWallet();
    } else {
      wallet = accountService.getAccountInfo().getWallet();
    }
    if (wallet == null) {
      throw new IllegalStateException("No wallet returned");
    }
    return wallet;
  }

  private void fetchAndBroadcast(Subscription subscription)
      throws InterruptedException {
    rateController.acquire();
    manageExchangeExceptions(
        subscription.key(),
        () -> {
          switch (subscription.type()) {
            case TICKER:
              pollAndEmitTicker(subscription.currencyPair());
              break;
            case ORDERBOOK:
              pollAndEmitOrderbook(subscription.currencyPair());
              break;
            case TRADES:
              pollAndEmitTrades(subscription.currencyPair());
              break;
            case OPEN_ORDERS:
              pollAndEmitOpenOrders(subscription.currencyPair());
              break;
            case USER_TRADE:
              pollAndEmitUserTradeHistory(subscription.currencyPair());
              break;
            case ORDER:
              // Not currently supported by polling
              break;
            default:
              throw new IllegalStateException(
                  "Market data type " + subscription.type() + " not supported in this way");
          }
        },
        () -> ImmutableList.of(subscription));
  }

  private void pollAndEmitUserTradeHistory(CurrencyPair currencyPair) throws IOException {
    TradeHistoryParams tradeHistoryParams = tradeHistoryParams(currencyPair);
    tradeService
        .getTradeHistory(tradeHistoryParams)
        .getUserTrades()
        .forEach(trade -> publisher.emit(UserTradeEvent.create(toSpec(currencyPair), trade)));
  }

  @SuppressWarnings("unchecked")
  private void pollAndEmitOpenOrders(CurrencyPair currencyPair) throws IOException {
    OpenOrdersParams openOrdersParams = openOrdersParams(currencyPair);

    Date originatingTimestamp = new Date();
    OpenOrders fetched = tradeService.getOpenOrders(openOrdersParams);

    // TODO GDAX PR required
    if (exchangeName.equals(Exchanges.GDAX)) {
      ImmutableList<LimitOrder> filteredOpen =
          FluentIterable.from(fetched.getOpenOrders()).filter(openOrdersParams::accept).toList();
      ImmutableList<? extends Order> filteredHidden =
          FluentIterable.from(fetched.getHiddenOrders()).toList();
      fetched = new OpenOrders(filteredOpen, (List<Order>) filteredHidden);
    }

    publisher.emit(OpenOrdersEvent.create(toSpec(currencyPair), fetched, originatingTimestamp));
  }

  private void pollAndEmitTrades(CurrencyPair currencyPair) throws IOException {
    var spec = toSpec(currencyPair);
    marketDataService
        .getTrades(currencyPair)
        .getTrades()
        .forEach(
            t ->
                mostRecentTrades.compute(
                    spec,
                    (k, previousTiming) -> {
                      Instant thisTradeTiming = t.getTimestamp().toInstant();
                      Instant newMostRecent = previousTiming;
                      if (previousTiming == null) {
                        newMostRecent = thisTradeTiming;
                      } else if (thisTradeTiming.isAfter(previousTiming)) {
                        newMostRecent = thisTradeTiming;
                        publisher.emit(TradeEvent.create(spec, t));
                      }
                      return newMostRecent;
                    }));
  }

  private void pollAndEmitOrderbook(CurrencyPair currencyPair) throws IOException {
    OrderBook orderBook =
        marketDataService.getOrderBook(currencyPair, exchangeOrderbookArgs());
    publisher.emit(OrderBookEvent.create(toSpec(currencyPair), orderBook));
  }

  private Object[] exchangeOrderbookArgs() {
    if (exchangeName.equals(Exchanges.BITMEX)) {
      return new Object[] {};
    } else {
      return new Object[] {ORDERBOOK_DEPTH, ORDERBOOK_DEPTH};
    }
  }

  private void pollAndEmitTicker(CurrencyPair currencyPair) throws IOException {
    publisher.emit(TickerEvent.create(toSpec(currencyPair), marketDataService.getTicker(currencyPair)));
  }

  private TradeHistoryParams tradeHistoryParams(CurrencyPair currencyPair) {
    TradeHistoryParams params;

    // TODO fix with pull requests
    if (exchangeName.equals(Exchanges.BITMEX) || exchangeName.equals(Exchanges.GDAX)) {
      params =
          new TradeHistoryParamCurrencyPair() {

            private CurrencyPair pair;

            @Override
            public void setCurrencyPair(CurrencyPair pair) {
              this.pair = pair;
            }

            @Override
            public CurrencyPair getCurrencyPair() {
              return pair;
            }
          };
    } else {
      params = tradeService.createTradeHistoryParams();
    }

    if (params instanceof TradeHistoryParamCurrencyPair) {
      ((TradeHistoryParamCurrencyPair) params)
          .setCurrencyPair(currencyPair);
    } else {
      throw new UnsupportedOperationException(
          "Don't know how to read user trades on this exchange: "
              + exchangeName);
    }
    if (params instanceof TradeHistoryParamLimit) {
      ((TradeHistoryParamLimit) params).setLimit(MAX_TRADES);
    }
    if (params instanceof TradeHistoryParamPaging) {
      ((TradeHistoryParamPaging) params).setPageLength(MAX_TRADES);
      ((TradeHistoryParamPaging) params).setPageNumber(0);
    }
    return params;
  }

  private OpenOrdersParams openOrdersParams(CurrencyPair currencyPair) {
    OpenOrdersParams params = null;
    try {
      params = tradeService.createOpenOrdersParams();
    } catch (NotYetImplementedForExchangeException e) {
      // Fiiiiine Bitmex
    }
    if (params == null) {
      // Bitfinex & Bitmex
      params = new DefaultOpenOrdersParamCurrencyPair(currencyPair);
    } else if (params instanceof OpenOrdersParamCurrencyPair) {
      ((OpenOrdersParamCurrencyPair) params).setCurrencyPair(currencyPair);
    } else {
      throw new UnsupportedOperationException(
          "Don't know how to read open orders on this exchange: "
              + exchangeName);
    }
    return params;
  }
}