package com.projects.tradingMachine.services;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.jms.JMSException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.projects.tradingMachine.services.database.DatabaseProperties;
import com.projects.tradingMachine.services.database.noSql.MongoDBConnection;
import com.projects.tradingMachine.services.database.noSql.MongoDBManager;
import com.projects.tradingMachine.utility.Utility;
import com.projects.tradingMachine.utility.order.OrderSide;
import com.projects.tradingMachine.utility.order.OrderType;
import com.projects.tradingMachine.utility.order.SimpleOrder;

/**
 * Extracts some statistics out of the filled orders stored to the MongoDB database.
 * 
 * */
public final class StatsRunner implements Runnable {
	private static Logger logger = LoggerFactory.getLogger(StatsRunner.class);
	
	private final Properties properties;
	private final MongoDBManager mongoDBManager;
	
	public StatsRunner(final Properties properties) {
		this.properties = properties;
		mongoDBManager = new MongoDBManager(new MongoDBConnection(new DatabaseProperties(properties.getProperty("mongoDB.host"), 
				Integer.valueOf(properties.getProperty("mongoDB.port")), properties.getProperty("mongoDB.database"))), properties.getProperty("mongoDB.filledOrdersCollection"));
	}
	
	@Override
	public void run() {
		while (!Thread.currentThread().isInterrupted()) {
			try {
				logger.info(getOrderDetailsByType()+getOrderDetailsBySide(5));
				TimeUnit.SECONDS.sleep(Integer.valueOf(properties.getProperty("statsPublishingPeriod")));
			}
			catch(final InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			catch (final Exception e) {
				logger.warn("Unable to produce marked data, due to: "+e.getMessage());
			}
		}
		try {
			mongoDBManager.close();
		} catch (final Exception e) {
			logger.warn("Unable to close the MongoDB connection: "+e.getMessage());
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * Statistics based on the order type.
	 * */
	private String getOrderDetailsByType() {
		final StringBuilder sb = new StringBuilder("\n");
		final Map<OrderType, List<SimpleOrder>> orders = mongoDBManager.getOrders(Optional.ofNullable(null)).stream().collect(Collectors.groupingBy(SimpleOrder::getType));
		for (final Map.Entry<OrderType, List<SimpleOrder>> ot : orders.entrySet()) {
			final String avgMarketPrice = String.valueOf(Utility.roundDouble(ot.getValue().stream().mapToDouble(SimpleOrder::getAvgPx).average().getAsDouble(), 2)); 
			switch(ot.getKey()) {
				case MARKET: 
					sb.append("Market Orders: \n\t").append("Average price: ").append(avgMarketPrice).append("\n\t");
					break;
				case LIMIT: 
					sb.append("Limit Orders: \n\t").append("Average market price: ").append(avgMarketPrice).append("\n\t").
					append("Average limit price: ").append(String.valueOf(Utility.roundDouble(ot.getValue().stream().mapToDouble(SimpleOrder::getLimit).average().getAsDouble(), 2))).append("\n\t");
					break;
				case STOP: 
					sb.append("Stop Orders: \n\t").append("Average market price: ").append(avgMarketPrice).append("\n\t").
					append("Average stop price: ").append(String.valueOf(Utility.roundDouble(ot.getValue().stream().mapToDouble(SimpleOrder::getStop).average().getAsDouble(), 2))).append("\n\t");
					break;
			}
			sb.append("Average quantity: ").append(String.valueOf(Utility.roundDouble(ot.getValue().stream().mapToDouble(SimpleOrder::getQuantity).average().getAsDouble(), 2))).append("\n\t");
			sb.append("Orders number: ").append(String.valueOf(ot.getValue().size())).append("\n\n");
		}
		return sb.toString();
	}

	/**
	 * Statistics based on the order side.
	 * */
	private String getOrderDetailsBySide(int topOrdersLimit) {
		final StringBuilder sb = new StringBuilder("\n");
		final Map<OrderSide, List<SimpleOrder>> orders = mongoDBManager.getOrders(Optional.ofNullable(null)).stream().collect(Collectors.groupingBy(SimpleOrder::getSide));
		final Comparator<SimpleOrder> ordersQuantityComparator = (c1, c2) -> Integer.compare(c1.getQuantity(), c2.getQuantity());
		for (final Map.Entry<OrderSide, List<SimpleOrder>> ot : orders.entrySet()) {
			switch(ot.getKey()) {
				case BUY: 
					sb.append("BUY orders number: ").append(String.valueOf(ot.getValue().size())).append("\n");
					sb.append("Top "+topOrdersLimit+" biggest quantity BUY orders: \n\t").append(ot.getValue().stream().sorted(ordersQuantityComparator.reversed()).limit(topOrdersLimit).map(so -> so.getSymbol()+"/ "+so.getQuantity()+"/ "+so.getFillDate()).collect(Collectors.joining("\n\t"))).append("\n\n");
					break;
				case SELL: 
					sb.append("SELL orders number: ").append(String.valueOf(ot.getValue().size())).append("\n");
					sb.append("Top "+topOrdersLimit+" smallest quantity SELL orders: \n\t").append(ot.getValue().stream().sorted(ordersQuantityComparator).limit(topOrdersLimit).map(so -> so.getSymbol()+"/ "+so.getQuantity()+"/ "+so.getFillDate()).collect(Collectors.joining("\n\t"))).append("\n\n");
					break;
			}
		}
		return sb.toString();
	}
	
	public static void main(final String[] args) throws JMSException, Exception {
		final ExecutorService es = Executors.newFixedThreadPool(1);
		final Future<?> f = es.submit(new StatsRunner(Utility.getApplicationProperties("tradingMachineServices.properties")));
		TimeUnit.SECONDS.sleep(60);
		f.cancel(true);
		Utility.shutdownExecutorService(es, 1, TimeUnit.SECONDS);
	}
}