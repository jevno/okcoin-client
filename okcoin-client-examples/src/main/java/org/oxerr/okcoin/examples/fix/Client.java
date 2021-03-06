package org.oxerr.okcoin.examples.fix;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.account.AccountInfo;
import org.knowm.xchange.dto.marketdata.OrderBook;
import org.knowm.xchange.dto.marketdata.Trade;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.oxerr.okcoin.fix.fix44.ExceptionResponseMessage;
import org.oxerr.okcoin.fix.fix44.OKCoinMessageFactory;
import org.oxerr.okcoin.xchange.service.fix.OKCoinXChangeApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import quickfix.ConfigError;
import quickfix.FieldNotFound;
import quickfix.FileLogFactory;
import quickfix.FileStoreFactory;
import quickfix.IncorrectTagValue;
import quickfix.Initiator;
import quickfix.LogFactory;
import quickfix.MessageFactory;
import quickfix.MessageStoreFactory;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;
import quickfix.UnsupportedMessageType;
import quickfix.field.MassStatusReqType;
import quickfix.fix44.ExecutionReport;

/**
 * Demonstration of FIX API.
 */
public class Client {

	private static final Logger log = LoggerFactory.getLogger(Client.class);

	private final OKCoinXChangeApplication app;
	private final SessionID sessionId;
	private final Initiator initiator;

	public Client(String apiKey, String secretKey) throws IOException,
			ConfigError, InterruptedException {
		app = new OKCoinXChangeApplication(apiKey, secretKey) {

			@Override
			public void onLogon(SessionID sessionId) {
				super.onLogon(sessionId);

				String massStatusReqId = UUID.randomUUID().toString();
				this.requestOrderMassStatus(massStatusReqId, MassStatusReqType.STATUS_FOR_ALL_ORDERS, sessionId);
			}

			@Override
			public void onOrderBook(OrderBook orderBook, SessionID sessionId) {
				log.info("asks: {}, bids: {}", orderBook.getAsks().size(), orderBook.getBids().size());

				// bids should be sorted by limit price descending
				LimitOrder preOrder = null;
				for (LimitOrder order : orderBook.getBids()) {
					log.info("Bid: {}, {}", order.getLimitPrice(), order.getTradableAmount());

					if (preOrder != null && preOrder.compareTo(order) >= 0) {
						log.error("bids should be sorted by limit price descending");
					}
					preOrder = order;
				}

				// asks should be sorted by limit price ascending
				preOrder = null;
				for (LimitOrder order : orderBook.getAsks()) {
					log.info("Ask: {}, {}", order.getLimitPrice(), order.getTradableAmount());

					if (preOrder != null && preOrder.compareTo(order) >= 0) {
						log.error("asks should be sorted by limit price ascending");
					}
					preOrder = order;
				}

				LimitOrder ask = orderBook.getAsks().get(0);
				LimitOrder bid = orderBook.getBids().get(0);
				log.info("lowest  ask: {}, {}", ask.getLimitPrice(), ask.getTradableAmount());
				log.info("highest bid: {}, {}", bid.getLimitPrice(), bid.getTradableAmount());

				if (ask.getLimitPrice().compareTo(bid.getLimitPrice()) <= 0) {
					throw new IllegalStateException(String.format("Lowest ask %s is not higher than the highest bid %s.",
							ask.getLimitPrice(), bid.getLimitPrice()));
				}
			}

			@Override
			public void onTrades(List<Trade> trades, SessionID sessionId) {
				for (Trade trade : trades) {
					log.info("{}", trade);
				}
			}

			@Override
			public void onAccountInfo(AccountInfo accountInfo,
					SessionID sessionId) {
				log.info("AccountInfo: {}", accountInfo);
			}

			@Override
			public void onMessage(ExecutionReport message, SessionID sessionId)
					throws FieldNotFound, UnsupportedMessageType,
					IncorrectTagValue {
				log.info(message.toXML(getDataDictionary()));
			}

			@Override
			public void onMessage(ExceptionResponseMessage message,
					SessionID sessionId) throws FieldNotFound,
							UnsupportedMessageType, IncorrectTagValue {
				log.error(message.toXML(getDataDictionary()));
			}

		};

		SessionSettings settings;
		try (InputStream inputStream = getClass().getResourceAsStream("client.cfg")) {
			settings = new SessionSettings(inputStream);
		}

		MessageStoreFactory storeFactory = new FileStoreFactory(settings);
		LogFactory logFactory = new FileLogFactory(settings);
		MessageFactory messageFactory = new OKCoinMessageFactory();
		initiator = new SocketInitiator(app, storeFactory, settings,
				logFactory, messageFactory);
		initiator.start();

		while (!initiator.isLoggedOn()) {
			log.info("Waiting for logged on...");
			TimeUnit.SECONDS.sleep(1);
		}

		sessionId = initiator.getSessions().get(0);
	}

	public void demo() {
		String mdReqId = UUID.randomUUID().toString();
		String symbol = "BTC/CNY";
		app.subscribeOrderBook(CurrencyPair.BTC_CNY, sessionId);

		mdReqId = UUID.randomUUID().toString();
		app.requestLiveTrades(mdReqId, symbol, sessionId);

		mdReqId = UUID.randomUUID().toString();
		app.request24HTicker(mdReqId, symbol, sessionId);

		String accReqId = UUID.randomUUID().toString();
		app.requestAccountInfo(accReqId, sessionId);

		String tradeRequestId = UUID.randomUUID().toString();
		long orderId = 1;
		char ordStatus = '0';
		app.requestOrdersInfoAfterSomeID(tradeRequestId, symbol, orderId, ordStatus, sessionId);

		// to check order id > Integer.MAX_VALUE
		app.requestOrderMassStatus("2147488076",
				MassStatusReqType.STATUS_FOR_ORDERS_FOR_A_SECURITY, sessionId);

	}

	public static void main(String[] args) throws IOException, ConfigError,
			InterruptedException {
		String apiKey = args[0], secretKey = args[1];
		Client client = new Client(apiKey, secretKey);
		client.demo();

		log.info("Waiting a moment and exiting.");
		TimeUnit.SECONDS.sleep(30);
		client.initiator.stop();
	}

}
