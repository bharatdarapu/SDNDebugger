package net.sdn.monitor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.UnknownHostException;

import static net.sdn.debugger.Debugger.*;

public class Monitor {
	private final static String TSHARKPARAMS[] = { "/usr/bin/sudo",
			"/usr/bin/tshark", "-l", "-i", "s1-eth1", "-i", "s1-eth2", "-T",
			"fields", "-e", "frame.interface_id", "-e", "eth.type", "-e",
			"eth.src", "-e", "eth.dst", "-e", "ip.src", "-e", "ip.dst" };

	private Socket socket;

	public Monitor(int port) {
		try {
			socket = new Socket("127.0.0.1", port);
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public Socket getSocket() {
		return socket;
	}

	public static void main(String args[]) {
		String line = null;
		try {
			OutputStream outputStream = new Monitor(DEFAULT_MONITOR_PORT)
					.getSocket().getOutputStream();
			PrintWriter out = new PrintWriter(outputStream);

			System.out.println("Start the monitor.");
			Process proc = Runtime.getRuntime().exec(TSHARKPARAMS);

			BufferedReader stdInput = new BufferedReader(new InputStreamReader(
					proc.getInputStream()));

			while ((line = stdInput.readLine()) != null) {
				System.out.println(line);
				out.println(line);
				out.flush();
			}

			proc.waitFor();
			System.out.println("Exit monitor");
			proc.destroy();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}