package net.sdn.debugger;

/**
 * @author Tomasz Bak
 * @author Da Yu, Yiming Li
 */
import io.reactivex.netty.RxNetty;
import io.reactivex.netty.channel.ConnectionHandler;
import io.reactivex.netty.channel.ObservableConnection;
import io.reactivex.netty.pipeline.PipelineConfigurators;
import io.reactivex.netty.server.RxServer;
import rx.Notification;
import rx.Observable;
import rx.functions.Action0;
import rx.functions.Func1;

public class ICMPMonitor implements Runnable {
	
	// For ICMP Monitor count use only
	private int count = 0;
	
	public void run() {
		createServer().startAndWait();
	}

	private final int port;

	public ICMPMonitor(int port) {
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
												//System.out.println(msg);
												// Oracle Logic Here
												if (count % 10 == 0) {
													System.out.println("Oracle: ");
													System.out.println("This is the " + count + " ICMP message pass through s1-eth0");
													System.out.println(msg);
													count++;
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
}