package tech.cassandre.trading.bot.strategy;

import com.google.common.base.MoreObjects;
import org.ta4j.core.*;
import org.ta4j.core.num.DoubleNum;
import org.ta4j.core.num.Num;
import tech.cassandre.trading.bot.dto.market.TickerDTO;
import tech.cassandre.trading.bot.dto.user.AccountDTO;
import tech.cassandre.trading.bot.dto.util.CurrencyPairDTO;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Basic ta4j strategy.
 */
@SuppressWarnings("unused")
public abstract class BasicTa4jCassandreStrategy extends GenericCassandreStrategy {

    /** Timestamp of the last added bar. */
    private ZonedDateTime lastAddedBarTimestamp;

    /** Series. */
    private final BarSeries series;

    /** Ta4j Strategy. */
    private final Strategy strategy;

    /**
     * Constructor.
     */
    public BasicTa4jCassandreStrategy() {
        // Build the series.
        series = new BaseBarSeriesBuilder()
                .withNumTypeOf(DoubleNum.class)
                .withName(getRequestedCurrencyPair().toString())
                .build();
        series.setMaximumBarCount(getMaximumBarCount());

        // Build the strategy.
        strategy = getStrategy();
    }

    /**
     * Implements this method to tell the bot which currency pair your strategy will receive.
     *
     * @return the list of currency pairs tickers your want to receive
     */
    public abstract CurrencyPairDTO getRequestedCurrencyPair();

    /**
     * Implements this method to tell the bot how many bars you want to keep in your bar series.
     *
     * @return maximum bar count.
     */
    @SuppressWarnings("SameReturnValue")
    public abstract int getMaximumBarCount();

    /**
     * Implements this method to set the time that should separate two bars.
     *
     * @return temporal amount
     */
    public abstract Duration getDelayBetweenTwoBars();

    /**
     * Implements this method to tell the bot which strategy to apply.
     *
     * @return strategy
     */
    public abstract Strategy getStrategy();

    @Override
    public final Set<CurrencyPairDTO> getRequestedCurrencyPairs() {
        // We only support one currency pair with BasicTa4jCassandreStrategy.
        return Set.of(getRequestedCurrencyPair());
    }

    @Override
    public final void tickerUpdate(final TickerDTO ticker) {
        // In multi strategies, all tickers are delivered to all strategies, so we filter in here.
        if (getRequestedCurrencyPairs().contains(ticker.getCurrencyPair())) {
            getLastTickers().put(ticker.getCurrencyPair(), ticker);

            BigDecimal openPrice = MoreObjects.firstNonNull(ticker.getOpen(), ticker.getLast());
            BigDecimal highPrice = MoreObjects.firstNonNull(ticker.getHigh(), ticker.getLast());
            BigDecimal lowPrice = MoreObjects.firstNonNull(ticker.getLow(), ticker.getLast());
            BigDecimal closePrice = MoreObjects.firstNonNull(ticker.getLast(), BigDecimal.ZERO);
            BigDecimal volume = MoreObjects.firstNonNull(ticker.getVolume(), BigDecimal.ZERO);
            // If there is no bar or if the duration between the last bar and the ticker is enough.
            if (isDelayBetweenBarsExceeded(ticker)) {
                series.addBar(Duration.ZERO, ticker.getTimestamp(), openPrice, highPrice, lowPrice, closePrice, volume);
                lastAddedBarTimestamp = ticker.getTimestamp();

                // Ask what to do to the strategy.
                int endIndex = series.getEndIndex();
                if (strategy.shouldEnter(endIndex)) {
                    // Our strategy should enter.
                    shouldEnter();
                } else if (strategy.shouldExit(endIndex)) {
                    // Our strategy should exit.
                    shouldExit();
                }
            } else {
                Bar barToUpdate = series.getLastBar();
                Duration barDuration = Duration.between(barToUpdate.getBeginTime(), ticker.getTimestamp());
                Num newHighPrice = barToUpdate.getHighPrice().max(series.numOf(highPrice));
                Num newLowPrice = barToUpdate.getLowPrice().min(series.numOf(lowPrice));
                Num newVolume = barToUpdate.getVolume().plus(series.numOf(volume));
                Bar updatedBar = new BaseBar(barDuration, ticker.getTimestamp(), barToUpdate.getOpenPrice(), newHighPrice, newLowPrice, series.numOf(closePrice), newVolume, series.numOf(0));
                series.addBar(updatedBar, true);
                lastAddedBarTimestamp = ticker.getTimestamp();
            }
            onTickerUpdate(ticker);
        }
    }

    private boolean isDelayBetweenBarsExceeded(TickerDTO ticker) {
        return lastAddedBarTimestamp == null
                || ticker.getTimestamp().isEqual(lastAddedBarTimestamp.plus(getDelayBetweenTwoBars()))
                || ticker.getTimestamp().isAfter(lastAddedBarTimestamp.plus(getDelayBetweenTwoBars()));
    }

    /**
     * Returns true if we have enough assets to buy.
     *
     * @param amount amount
     * @return true if we there is enough assets to buy
     */
    public final boolean canBuy(final BigDecimal amount) {
        final Optional<AccountDTO> tradeAccount = getTradeAccount(new LinkedHashSet<>(getAccounts().values()));
        return tradeAccount.filter(accountDTO -> canBuy(accountDTO, getRequestedCurrencyPair(), amount)).isPresent();
    }

    /**
     * Returns true if we have enough assets to buy.
     *
     * @param amount              amount
     * @param minimumBalanceAfter minimum balance that should be left after buying
     * @return true if we there is enough assets to buy
     */
    public final boolean canBuy(final BigDecimal amount,
                                final BigDecimal minimumBalanceAfter) {
        final Optional<AccountDTO> tradeAccount = getTradeAccount(new LinkedHashSet<>(getAccounts().values()));
        return tradeAccount.filter(accountDTO -> canBuy(accountDTO, getRequestedCurrencyPair(), amount, minimumBalanceAfter)).isPresent();
    }

    /**
     * Returns true if we have enough assets to buy.
     *
     * @param account account
     * @param amount  amount
     * @return true if we there is enough assets to buy
     */
    public final boolean canBuy(final AccountDTO account,
                                final BigDecimal amount) {
        return canBuy(account, getRequestedCurrencyPair(), amount);
    }

    /**
     * Returns true if we have enough assets to buy and if minimumBalanceAfter is left on the account after.
     *
     * @param account             account
     * @param amount              amount
     * @param minimumBalanceAfter minimum balance that should be left after buying
     * @return true if we there is enough assets to buy
     */
    public final boolean canBuy(final AccountDTO account,
                                final BigDecimal amount,
                                final BigDecimal minimumBalanceAfter) {
        return canBuy(account, getRequestedCurrencyPair(), amount, minimumBalanceAfter);
    }

    /**
     * Returns true if we have enough assets to sell.
     *
     * @param amount              amount
     * @param minimumBalanceAfter minimum balance that should be left after buying
     * @return true if we there is enough assets to sell
     */
    public final boolean canSell(final BigDecimal amount,
                                 final BigDecimal minimumBalanceAfter) {
        final Optional<AccountDTO> tradeAccount = getTradeAccount(new LinkedHashSet<>(getAccounts().values()));
        return tradeAccount.filter(accountDTO -> canSell(accountDTO, getRequestedCurrencyPair().getBaseCurrency(), amount, minimumBalanceAfter)).isPresent();
    }

    /**
     * Returns true if we have enough assets to sell.
     *
     * @param amount amount
     * @return true if we there is enough assets to sell
     */
    public final boolean canSell(final BigDecimal amount) {
        final Optional<AccountDTO> tradeAccount = getTradeAccount(new LinkedHashSet<>(getAccounts().values()));
        return tradeAccount.filter(accountDTO -> canSell(accountDTO, getRequestedCurrencyPair().getBaseCurrency(), amount)).isPresent();
    }

    /**
     * Returns true if we have enough assets to sell.
     *
     * @param account account
     * @param amount  amount
     * @return true if we there is enough assets to sell
     */
    public final boolean canSell(final AccountDTO account,
                                 final BigDecimal amount) {
        return canSell(account, getRequestedCurrencyPair().getBaseCurrency(), amount);
    }

    /**
     * Returns true if we have enough assets to sell and if minimumBalanceAfter is left on the account after.
     *
     * @param account             account
     * @param amount              amount
     * @param minimumBalanceAfter minimum balance that should be left after selling
     * @return true if we there is enough assets to sell
     */
    public final boolean canSell(final AccountDTO account,
                                 final BigDecimal amount,
                                 final BigDecimal minimumBalanceAfter) {
        return canSell(account, getRequestedCurrencyPair().getBaseCurrency(), amount, minimumBalanceAfter);
    }

    /**
     * Called when your strategy says you should enter.
     */
    public abstract void shouldEnter();

    /**
     * Called when your strategy says your should exit.
     */
    public abstract void shouldExit();

    /**
     * Getter for series.
     *
     * @return series
     */
    public final BarSeries getSeries() {
        return series;
    }

}
