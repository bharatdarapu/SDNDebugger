package net.sdn.packet;

public class Packet {
	/*
		public Ethernet eth;
		public ARP arp;
		public IP ip;
		public ICMP icmp;
		public TCP tcp;
		public UDP udp;
	*/
	// default is null
	public String dl_src;
	public String dl_dst;
	public String dl_proto;
	public String nw_src;
	public String nw_dst;
	public String nw_proto;
	public String tp_src_port;
	public String tp_dst_port;
	public int of_type; //  -e mean 
	
	public String toString() {
		return  dl_src + "\t" +
				dl_dst + "\t" +
				dl_proto + "\t" +
				nw_src + "\t" +
				nw_dst + "\t" +
				nw_proto + "\t" +
				tp_src_port + "\t" +
				tp_dst_port + "\t" +
				of_type;
	}
}
