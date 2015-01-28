package net.sdn.packet;

public class Event {
	public Packet pkt;
	public String sw;
	public String interf;
	
	public String toString(){
		if (pkt.of_type < 0)
			return "{" + pkt.dl_proto + ":" + pkt.dl_src + "->" + pkt.dl_dst + ","
					+ pkt.dl_proto + ":" + pkt.nw_src + "->" + pkt.nw_dst + "}" 
					+ " -> " + sw + " : " + interf;
		else{
			if (pkt.nw_dst.equals("6633")){
				return "{" + pkt.of_type + ":" + pkt.dl_proto + "," + pkt.nw_proto + "}:" + sw + "->" + "controller";
			} else {
				return "{" + pkt.of_type + ":" + pkt.dl_proto + "," + pkt.nw_proto + "}:" + "controller" + "->" + sw;
			}
		}
	}
}
