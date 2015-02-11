package net.sdn.debugger;

/**
 * @author Da Yu, Yiming Li
 */
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;

import com.google.gson.Gson;

import net.sdn.event.Event;
import net.sdn.event.packet.PacketType;
import net.sdn.phytopo.PhyTopo;
import io.reactivex.netty.RxNetty;
import io.reactivex.netty.channel.ConnectionHandler;
import io.reactivex.netty.channel.ObservableConnection;
import io.reactivex.netty.pipeline.PipelineConfigurators;
import io.reactivex.netty.server.RxServer;
import rx.Notification;
import rx.Observable;
import rx.functions.Action0;
import rx.functions.Func1;

abstract public class Verifier implements Runnable {
	public void run() {
		createServer().startAndWait();
	}

	private LinkedList<Event> expectedEvents = new LinkedList<Event>();
	private LinkedList<Event> notExpectedEvents = new LinkedList<Event>();
	private HashSet<PacketType> interestedEvents = new HashSet<PacketType>();

	private PhyTopo phyTopo = null;

	private final int port = 8200;
	private final long EXPIRE_TIME = 1000 * 1000000; // nano seconds
	private String lines = "";

	private void timer(Event e) {
		// clean expired events in notExpected and raise error in expectedEvent
		Iterator<Event> it = this.notExpectedEvents.iterator();
		while (it.hasNext()) {
			// remove expired rules
			if (e.timeStamp - it.next().timeStamp >= EXPIRE_TIME)
				it.remove();
			else
				break;
		}

		it = this.expectedEvents.iterator();
		while (it.hasNext()) {
			Event ev = it.next();
			if (e.timeStamp - ev.timeStamp >= EXPIRE_TIME) {
				System.err.println("Expected Event but Not Happened:");
				System.err.println(ev);
				it.remove();
			} else
				break;
		}
	}

	protected RxServer<String, String> createServer() {
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
													// get event
													// deserialize
													Gson gson = new Gson();
													Event eve = gson.fromJson(
															temp[i],
															Event.class);
													// check expired rules and
													// gc
													timer(eve);
													if (isInterestedEvent(eve))
														verifier(eve);
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
												.println(" --> Closing monitor handler and stream");
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

		System.out.println("Monitor handler started...");
		return server;
	}

	abstract public void verifier(Event event);

	protected void addExpectedEvents(Event eve) {
		for (int i = 0; i <= expectedEvents.size(); i++){
			if (expectedEvents.get(i).priority <= eve.priority) {
				expectedEvents.add(i, eve);
				return;
			}
		}
		expectedEvents.add(eve);
	}

	protected void addNotExpectedEvents(Event eve) {
		for (int i = 0; i <= notExpectedEvents.size(); i++){
			if (notExpectedEvents.get(i).priority <= eve.priority) {
				notExpectedEvents.add(i, eve);
				return;
			}
		}
		notExpectedEvents.add(eve);
	}

	protected void addInterestedEvents(PacketType t) {
		interestedEvents.add(t);
	}

	// always allow heartbeat for rule expriations
	private boolean isInterestedEvent(Event e) {
		if ((interestedEvents.contains(PacketType.ARP) && e.pkt.eth.arp != null)
				|| (interestedEvents.contains(PacketType.IP) && e.pkt.eth.ip != null)
				|| (interestedEvents.contains(PacketType.ICMP) && e.pkt.eth.ip.icmp != null)
				|| (interestedEvents.contains(PacketType.TCP)
						&& e.pkt.eth.ip != null && e.pkt.eth.ip.tcp != null && e.pkt.eth.ip.tcp.of_packet == null)
				|| (interestedEvents.contains(PacketType.UDP)
						&& e.pkt.eth.ip != null && e.pkt.eth.ip.udp != null)
				|| (interestedEvents.contains(PacketType.OF)
						&& e.pkt.eth.ip != null && e.pkt.eth.ip.tcp != null && e.pkt.eth.ip.tcp.of_packet != null)
				|| (e.pkt.eth.ip != null && e.pkt.eth.ip.tcp != null
						&& e.pkt.eth.ip.tcp.of_packet != null && (e.pkt.eth.ip.tcp.of_packet.type
						.equals("echo_reply") || e.pkt.eth.ip.tcp.of_packet.type
						.equals("echo_request")))) {
			return true;
		}
		return false;
	}

	protected void addPhyTopo(PhyTopo pt) {
		phyTopo = pt;
	}

	protected PhyTopo getPhyTopo() {
		return phyTopo;
	}
	
	protected void verify(Event e) {
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

}
