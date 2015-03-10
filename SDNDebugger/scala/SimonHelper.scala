import net.sdn.event.packet._;
import net.sdn.event._;

object SimonHelper {

	def isArpNetworkEvents(e: NetworkEvent): Boolean = {
		e.pkt != null && e.pkt.eth != null && e.pkt.eth.arp != null
	}

	def isDHCPNetworkEvents(e: NetworkEvent): Boolean = {
		e.pkt != null && e.pkt.eth != null && e.pkt.eth.ip != null && e.pkt.eth.ip.udp != null && (e.pkt.eth.ip.udp.udp_dst == "67" || e.pkt.eth.ip.udp.udp_dst == "68")
	}

	def isICMPNetworkEvents(e: NetworkEvent): Boolean = {
		e.pkt != null && e.pkt.eth != null && e.pkt.eth.ip != null && e.pkt.eth.ip.icmp != null
	}	

	def isRESTNetworkEvents(e: NetworkEvent): Boolean = {
		e.pkt != null && e.pkt.eth != null && e.pkt.eth.ip != null && e.pkt.eth.ip.tcp != null && e.pkt.eth.ip.tcp.tcp_dst == "8080" && e.pkt.eth.ip.tcp.payload != null
	}

	def isOFNetworkEvents(e: NetworkEvent): Boolean = {
		// OF event has no interface
		e.direction == NetworkEventDirection.CONTROLLER
	}
}
