package net.sdn.proxy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.openflow.example.SelectListener;
import org.openflow.example.SelectLoop;
import org.openflow.example.cli.Options;
import org.openflow.example.cli.ParseException;
import org.openflow.example.cli.SimpleCLI;
import org.openflow.io.OFMessageAsyncStream;
import org.openflow.protocol.OFEchoReply;
import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFStatisticsReply;
import org.openflow.protocol.OFFeaturesReply;
import org.openflow.protocol.OFError;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFOXMFieldType;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFPacketOut;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFType;
import org.openflow.protocol.instruction.OFInstruction;
import org.openflow.protocol.instruction.OFInstructionApplyActions;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.factory.BasicFactory;
import org.openflow.util.LRULinkedHashMap;
import org.openflow.util.U16;

import static net.sdn.debugger.Debugger.*;

/**
 * @author Rob Sherwood (rob.sherwood@stanford.edu), David Erickson
 *         (daviderickson@cs.stanford.edu)
 * @author Srini Seetharaman (srini.seetharaman@gmail.com)
 * 
 * @author Da Yu, Yiming Li (modified)
 */

public class Proxy implements SelectListener {
	protected ExecutorService es;
	protected BasicFactory factory;
	protected SelectLoop listenSelectLoop;
	protected ServerSocketChannel listenSock;
	protected SocketChannel debugerSock;
	protected List<SelectLoop> selectLoops;
	protected Map<SocketChannel, OFSwitch> downSockets;
	protected Map<SocketChannel, OFSwitch> upSockets;
	protected Integer threadCount;
	protected int port;
	protected int debugger;

	protected class OFSwitch {
		protected SocketChannel socketDown;
		protected SocketChannel socketUp;
		protected OFMessageAsyncStream streamDown;
		protected OFMessageAsyncStream streamUp;
		protected ByteBuffer debuggerBufferDown;
		protected ByteBuffer debuggerBufferUp;
		protected Lock lock;

		public OFSwitch(SocketChannel socketDown, SocketChannel socketUp,
				OFMessageAsyncStream streamDown, OFMessageAsyncStream streamUp) {
			this.socketDown = socketDown;
			this.socketUp = socketUp;
			this.streamDown = streamDown;
			this.streamUp = streamUp;
			debuggerBufferDown = ByteBuffer
					.allocateDirect(OFMessageAsyncStream.defaultBufferSize);
			debuggerBufferUp = ByteBuffer
					.allocateDirect(OFMessageAsyncStream.defaultBufferSize);
			lock = new ReentrantLock();
		}

		public String toString() {
			return "Proxy: got new connection from "
					+ socketDown.socket().getInetAddress().getHostAddress()
					+ ":" + socketDown.socket().getPort()
					+ ", then, connected to "
					+ socketUp.socket().getInetAddress().getHostAddress() + ":"
					+ socketUp.socket().getPort();
		}

		public String downInf() {
			return socketDown.socket().getInetAddress().getHostAddress() + ":"
					+ socketDown.socket().getPort();
		}

		public String upInf() {
			return socketUp.socket().getInetAddress().getHostAddress() + ":"
					+ socketUp.socket().getPort();
		}

		public SocketChannel getSocketDown() {
			return socketDown;
		}

		public SocketChannel getSocketUp() {
			return socketUp;
		}

		public OFMessageAsyncStream getStreamDown() {
			return streamDown;
		}

		public OFMessageAsyncStream getStreamUp() {
			return streamUp;
		}

		public ByteBuffer getDebuggerBufferDown() {
			return debuggerBufferDown;
		}

		public ByteBuffer getDebuggerBufferUp() {
			return debuggerBufferUp;
		}
	}

	public Proxy(int port, int debugger) throws IOException {
		debugerSock = SocketChannel.open();
		debugerSock.connect(new InetSocketAddress("127.0.0.1", debugger));
		System.out.println("Succesfully connected to the Debugger!");

		listenSock = ServerSocketChannel.open();
		listenSock.configureBlocking(false);
		listenSock.socket().bind(new java.net.InetSocketAddress(port));
		listenSock.socket().setReuseAddress(true);
		this.port = port;
		this.debugger = debugger;
		selectLoops = new ArrayList<SelectLoop>();
		downSockets = new ConcurrentHashMap<SocketChannel, OFSwitch>();
		upSockets = new ConcurrentHashMap<SocketChannel, OFSwitch>();
		threadCount = 1;
		listenSelectLoop = new SelectLoop(this);
		// register this connection for accepting
		listenSelectLoop.register(listenSock, SelectionKey.OP_ACCEPT,
				listenSock);

		this.factory = BasicFactory.getInstance();
	}

	@Override
	public void handleEvent(SelectionKey key, Object arg) throws IOException {
		if (arg instanceof ServerSocketChannel) {
			handleListenEvent(key, (ServerSocketChannel) arg);
		} else {
			if (downSockets.containsKey((SocketChannel) arg)) {
				handleDownEvent(key, (SocketChannel) arg);
			} else {
				handleUpEvent(key, (SocketChannel) arg);
			}
		}
	}

	protected void handleListenEvent(SelectionKey key, ServerSocketChannel ssc)
			throws IOException {
		// listen to switch
		SocketChannel socketDown = listenSock.accept();
		OFMessageAsyncStream streamDown = new OFMessageAsyncStream(socketDown,
				factory);
		// connect to controller
		SocketChannel socketUp = SocketChannel.open();
		socketUp.connect(new InetSocketAddress("127.0.0.1", 6633));
		OFMessageAsyncStream streamUp = new OFMessageAsyncStream(socketUp,
				factory);

		OFSwitch ofSwitch = new OFSwitch(socketDown, socketUp, streamDown,
				streamUp);
		downSockets.put(socketDown, ofSwitch);
		upSockets.put(socketUp, ofSwitch);
		System.err.println(downSockets.get(socketDown));

		int ops = SelectionKey.OP_READ | SelectionKey.OP_WRITE;

		// hash this switch into a thread
		SelectLoop down = selectLoops.get(socketDown.hashCode()
				% selectLoops.size());
		down.register(socketDown, ops, socketDown);

		// hash this switch into a thread
		SelectLoop up = selectLoops.get(socketUp.hashCode()
				% selectLoops.size());
		up.register(socketUp, ops, socketUp);

		// force select to return and re-enter using the new set of keys
		up.wakeup();
		down.wakeup();
	}

	protected void handleDownEvent(SelectionKey key, SocketChannel sock) {
		OFSwitch sw = downSockets.get(sock);
		OFMessageAsyncStream streamDown = sw.getStreamDown();
		OFMessageAsyncStream streamUp = sw.getStreamUp();
		try {
			if (key.isReadable()) {
				List<OFMessage> msgs = streamDown.read();
				if (msgs == null) {
					key.cancel();
					downSockets.remove(sock);
					return;
				}

				for (OFMessage m : msgs) {
					// send to up
					streamUp.write(m);
					// write to debugger
					sw.lock.lock();
					m.writeTo(sw.getDebuggerBufferDown());
					sw.lock.unlock();

					switch (m.getType()) {
					case PACKET_IN:
						System.err
								.println("GOT PACKET_IN from " + sw.downInf());
						System.err.println("--> Data:"
								+ ((OFPacketIn) m).toString());
						break;
					case FEATURES_REPLY:
						System.err.println("GOT FEATURE_REPLY from "
								+ sw.downInf());
						System.err.println("--> Data:"
								+ ((OFFeaturesReply) m).toString());
						break;
					case STATS_REPLY:
						System.err.println("GOT STATS_REPLY from "
								+ sw.downInf());
						System.err.println("--> Data:"
								+ ((OFStatisticsReply) m).toString());
						break;
					case HELLO:
						System.err.println("GOT HELLO from " + sw.downInf());
						break;
					case ERROR:
						System.err.println("GOT ERROR from " + sw.downInf());
						System.err.println("--> Data:"
								+ ((OFError) m).toString());
						break;
					default:
						System.err.println("Unhandled OF message: "
								+ m.getType() + " from "
								+ sock.socket().getInetAddress());
					}
				}
			}

			streamUp.flush();

		} catch (IOException e) {
			// if we have an exception, disconnect the switch
			key.cancel();
			downSockets.remove(sock);
		}
	}

	protected void handleUpEvent(SelectionKey key, SocketChannel sock) {
		OFSwitch sw = upSockets.get(sock);
		OFMessageAsyncStream streamDown = sw.getStreamDown();
		OFMessageAsyncStream streamUp = sw.getStreamUp();
		try {
			if (key.isReadable()) {
				List<OFMessage> msgs = streamUp.read();
				if (msgs == null) {
					key.cancel();
					upSockets.remove(sock);
					return;
				}

				for (OFMessage m : msgs) {
					// send to up
					streamDown.write(m);
					// write to debugger
					sw.lock.lock();
					m.writeTo(sw.getDebuggerBufferUp());
					sw.lock.unlock();

					switch (m.getType()) {
					case PACKET_IN:
						System.err.println("GOT PACKET_IN from " + sw.upInf());
						System.err.println("--> Data:"
								+ ((OFPacketIn) m).toString());
						break;
					case FEATURES_REPLY:
						System.err.println("GOT FEATURE_REPLY from "
								+ sw.upInf());
						System.err.println("--> Data:"
								+ ((OFFeaturesReply) m).toString());
						break;
					case STATS_REPLY:
						System.err
								.println("GOT STATS_REPLY from " + sw.upInf());
						System.err.println("--> Data:"
								+ ((OFStatisticsReply) m).toString());
						break;
					case HELLO:
						System.err.println("GOT HELLO from " + sw.upInf());
						break;
					case ERROR:
						System.err.println("GOT ERROR from " + sw.upInf());
						System.err.println("--> Data:"
								+ ((OFError) m).toString());
						break;
					default:
						System.err.println("Unhandled OF message: "
								+ m.getType() + " from "
								+ sock.socket().getInetAddress());
					}
				}
			}

			streamDown.flush();

		} catch (IOException e) {
			// if we have an exception, disconnect the switch
			key.cancel();
			upSockets.remove(sock);
		}
	}

	public void run() throws IOException {
		System.err.println("Starting " + this.getClass().getCanonicalName()
				+ " on port " + this.port + " with " + this.threadCount
				+ " threads");
		// Static number of threads equal to processor cores
		es = Executors.newFixedThreadPool(threadCount);

		// Launch one select loop per threadCount and start running
		for (int i = 0; i < threadCount; ++i) {
			final SelectLoop sl = new SelectLoop(this);
			selectLoops.add(sl);
			es.execute(new Runnable() {
				@Override
				public void run() {
					try {
						sl.doLoop();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			});
		}

		// Start the listen loop
		listenSelectLoop.doLoop();
	}

	public static void main(String[] args) throws IOException {
		SimpleCLI cmd = parseArgs(args);
		int port = Integer.valueOf(cmd.getOptionValue("p"));
		int debugger = Integer.valueOf(cmd.getOptionValue("d"));
		Proxy sc = new Proxy(port, debugger);
		sc.threadCount = Integer.valueOf(cmd.getOptionValue("t"));
		// run round robin to send message to debugger
		ConnectToDebugger connect = sc.new ConnectToDebugger();
		new Thread(connect).start();
		sc.run();
	}

	public static SimpleCLI parseArgs(String[] args) {
		Options options = new Options();
		options.addOption("h", "help", "print help");
		// unused?
		// options.addOption("n", true, "the number of packets to send");
		options.addOption("p", "port", 8000, "the port to listen on");
		options.addOption("t", "threads", 10, "the number of threads to run");
		options.addOption("d", "debugger", DEFAULT_PROXY_PORT,
				"port number the debugger listen on");
		try {
			SimpleCLI cmd = SimpleCLI.parse(options, args);
			if (cmd.hasOption("h")) {
				printUsage(options);
				System.exit(0);
			}
			return cmd;
		} catch (ParseException e) {
			System.err.println(e);
			printUsage(options);
		}

		System.exit(-1);
		return null;
	}

	public static void printUsage(Options options) {
		SimpleCLI.printHelp("Usage: " + Proxy.class.getCanonicalName()
				+ " [options]", options);
	}

	/**
	 * Round robin and send to debugger
	 * 
	 */
	public class ConnectToDebugger implements Runnable {
		private boolean done = true;

		public void run() {
			try {
				while (done) {
					for (OFSwitch down : downSockets.values()) {
						down.lock.lock();
						if (down.getDebuggerBufferDown().remaining() != OFMessageAsyncStream.defaultBufferSize) {
							down.getDebuggerBufferDown().flip();
							debugerSock.write(down.getDebuggerBufferDown());
							down.getDebuggerBufferDown().compact();
						}
						down.lock.unlock();
					}

					for (OFSwitch up : upSockets.values()) {
						up.lock.lock();
						if (up.getDebuggerBufferUp().remaining() != OFMessageAsyncStream.defaultBufferSize) {
							up.getDebuggerBufferUp().flip();
							debugerSock.write(up.getDebuggerBufferUp());
							up.getDebuggerBufferUp().compact();
						}
						up.lock.unlock();
					}
				}
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		public void cancel() {
			done = false;
		}
	}
}