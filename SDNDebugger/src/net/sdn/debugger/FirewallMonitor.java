package net.sdn.debugger;

/**
 * @author Tomasz Bak
 * @author Da Yu, Yiming Li
 */
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;

import net.sdn.event.Event;
import net.sdn.event.Packet;
import net.sdn.event.EventGenearator;

import io.reactivex.netty.RxNetty;
import io.reactivex.netty.channel.ConnectionHandler;
import io.reactivex.netty.channel.ObservableConnection;
import io.reactivex.netty.pipeline.PipelineConfigurators;
import io.reactivex.netty.server.RxServer;
import rx.Notification;
import rx.Observable;
import rx.functions.Action0;
import rx.functions.Func1;

public class FirewallMonitor implements Runnable {
	public void run() {
		createServer().startAndWait();
	}
	
	public final static double EXPIRE_TIME= 15;

	private final int port;
	// expectedEvents are events that expected to happened in the real network
	// generated by Oracle
	// based on the internal network state and the ideal model
	private LinkedList<Event> expectedEvents = new LinkedList<Event>();
	private LinkedList<Event> notExpectedEvents = new LinkedList<Event>();

	private static String lines = "";

	public FirewallMonitor(int port) {
		this.port = port;
	}

	public RxServer<String, String> createServer() {
		RxServer<String, String> server = RxNetty.createTcpServer(port,
				PipelineConfigurators.textOnlyConfigurator(),
				new ConnectionHandler<String, String>() {
					@Override
					public Observable<Void> handle(
							final ObservableConnection<String, String> connection) {
						System.out.println("Monitor connection established.");
						return connection
								.getInput()
								.flatMap(
										new Func1<String, Observable<Notification<Void>>>() {
											@Override
											public Observable<Notification<Void>> call(
													String msg) {
												// set filters:
												lines += msg;
												String temp[] = lines
														.split("\n");

												char[] chs = lines
														.toCharArray();
												int count = 0;

												if (chs[chs.length - 1] == '\n') {
													// full message line
													count = temp.length;
													lines = "";
												} else {
													// part message line
													count = temp.length - 1;
													lines = temp[temp.length - 1];
												}

												for (int i = 0; i < count; i++) {
													// get pkt
													// System.out.println(temp[i]);
													Event eve = EventGenearator
															.deserialize(temp[i]);
													// Clear expired Events
													timer(eve);
													
													// Oracle

												}

												return Observable.empty();
											}
										})
								.takeWhile(
										new Func1<Notification<Void>, Boolean>() {
											@Override
											public Boolean call(
													Notification<Void> notification) {
												return !notification
														.isOnError();
											}
										}).finallyDo(new Action0() {
									@Override
									public void call() {
										System.out
												.println(" --> Closing StatefulFireWall Monitor handler and stream");
									}
								}).map(new Func1<Notification<Void>, Void>() {
									@Override
									public Void call(
											Notification<Void> notification) {
										return null;
									}
								});
					}
				});

		System.out.println("StatefulFireWall Monitor handler started...");
		return server;
	}
	
	private void timer(Event e){
		// clean expired events in notExpected and raise error in expectedEvent
		Iterator<Event> it = this.notExpectedEvents.iterator();
		while (it.hasNext()){
			// remove expired rules
			if (e.timeStamp - it.next().timeStamp >= EXPIRE_TIME)
				it.remove();
			else
				break;
		}
		
		it = this.expectedEvents.iterator();
		while (it.hasNext()){
			Event ev = it.next();
			if (e.timeStamp - ev.timeStamp >= EXPIRE_TIME){
				System.err.println("Expected Event but Not Happened:");
				System.err.println(ev);
				it.remove();
			} else
				break;
		}
	}

	private void Oracle(Event eve) {
		
	}

	private void verify(Event e) {
		// System.out.println(e);
		// check notExpectedEvent List
		for (Event notExpected : notExpectedEvents) {
			if (notExpected.equals(e)) {
				System.err.println("Not Expected Event Happened:");
				System.err.println(notExpected);
				notExpectedEvents.remove(notExpected);
				// printEvents(notExpectedEvents);
				return;
			}
		}
		// check expectedEvent List
		for (Event expected : expectedEvents) {
			if (expected.equals(e)) {
				System.out.println("Expected Event Happened:");
				System.out.println(expected);
				expectedEvents.remove(expected);
				// printEvents(expectedEvents);
				return;
			}
		}

		System.err.println("Unknown Event:");
		System.err.println(e);
		return;
	}

	// for test only
	private void printEvents(LinkedList<Event> lst) {
		System.out.println("***************************************");
		for (Event e : lst)
			System.out.println(e);
		System.out.println("***************************************");
	}
}
