package net.sdn.event.packet;

public class Ethernet {
	public long timeStamp;
	public String dl_src;
	public String dl_dst;
	public String dl_type;
	// choose one
	public Arp arp;
	public Ip ip;
}
