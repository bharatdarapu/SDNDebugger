package net.sdn.debugger;

/**
 * @author Tomasz Bak
 * @author Da Yu, Yiming Li
 */
import java.nio.ByteBuffer;
import java.util.List;

import org.openflow.io.OFMessageAsyncStream;
import org.openflow.protocol.OFError;
import org.openflow.protocol.OFFeaturesReply;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFStatisticsReply;
import org.openflow.protocol.factory.BasicFactory;

import io.netty.buffer.ByteBuf;
import io.reactivex.netty.RxNetty;
import io.reactivex.netty.channel.ConnectionHandler;
import io.reactivex.netty.channel.ObservableConnection;
import io.reactivex.netty.server.RxServer;
import rx.Notification;
import rx.Observable;
import rx.functions.Action0;
import rx.functions.Func1;

public class ProxyHandler implements Runnable {
	public void run() {
		createServer().startAndWait();
	}

	private final int port;

	private BasicFactory factory;

	private List<OFMessage> list;

	private ByteBuffer proxyIncomingBuffer;

	public ProxyHandler(int port) {
		this.port = port;
		this.factory = BasicFactory.getInstance();
		proxyIncomingBuffer = ByteBuffer
				.allocateDirect(OFMessageAsyncStream.defaultBufferSize);
	}

	public RxServer<ByteBuf, ByteBuf> createServer() {
		RxServer<ByteBuf, ByteBuf> server = RxNetty.createTcpServer(port,
				new ConnectionHandler<ByteBuf, ByteBuf>() {
					@Override
					public Observable<Void> handle(
							final ObservableConnection<ByteBuf, ByteBuf> connection) {
						System.out.println("Proxy connection established.");
						return connection
								.getInput()
								.flatMap(
										new Func1<ByteBuf, Observable<Notification<Void>>>() {
											@Override
											public Observable<Notification<Void>> call(
													ByteBuf msg) {
												ByteBuffer inBuf = msg
														.nioBuffer();
												proxyIncomingBuffer.put(inBuf);
												proxyIncomingBuffer.flip();
												list = factory.parseMessages(
														proxyIncomingBuffer, 0);
												if (proxyIncomingBuffer
														.hasRemaining())
													proxyIncomingBuffer
															.compact();
												else
													proxyIncomingBuffer.clear();
												//printOFMessages(list);
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
												.println(" --> Closing proxy handler and stream");
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

		System.out.println("Proxy handler started...");
		return server;
	}

	public void printOFMessages(List<OFMessage> msgs) {
		for (OFMessage m : msgs) {
			switch (m.getType()) {
			case PACKET_IN:
				System.err.println("GOT PACKET_IN");
				System.err.println("--> Data:" + ((OFPacketIn) m).toString());
				break;
			case FEATURES_REPLY:
				System.err.println("GOT FEATURE_REPLY");
				System.err.println("--> Data:"
						+ ((OFFeaturesReply) m).toString());
				break;
			case STATS_REPLY:
				System.err.println("GOT STATS_REPLY");
				System.err.println("--> Data:"
						+ ((OFStatisticsReply) m).toString());
				break;
			case HELLO:
				System.err.println("GOT HELLO");
				break;
			case ERROR:
				System.err.println("GOT ERROR");
				System.err.println("--> Data:" + ((OFError) m).toString());
				break;
			default:
				System.err.println("Unhandled OF message: " + m.getType());
			}
		}
	}
}