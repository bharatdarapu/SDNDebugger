package edu.brown.cs.sdn.apps.sps;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFOXMFieldType;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.action.OFActionSetField;
import org.openflow.protocol.instruction.OFInstruction;
import org.openflow.protocol.instruction.OFInstructionApplyActions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.brown.cs.sdn.apps.util.Host;
import edu.brown.cs.sdn.apps.util.SwitchCommands;

import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitch.PortChangeType;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.ImmutablePort;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceListener;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryListener;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.routing.Link;
import net.floodlightcontroller.util.MACAddress;

public class ShortestPathSwitching implements IFloodlightModule,
		IOFSwitchListener, ILinkDiscoveryListener, IDeviceListener,
		InterfaceShortestPathSwitching {
	public static final String MODULE_NAME = ShortestPathSwitching.class
			.getSimpleName();

	// Interface to the logging system
	private static Logger log = LoggerFactory.getLogger(MODULE_NAME);

	// Interface to Floodlight core for interacting with connected switches
	private IFloodlightProviderService floodlightProv;

	// Interface to link discovery service
	private ILinkDiscoveryService linkDiscProv;

	// Interface to device manager service
	private IDeviceService deviceProv;

	// Switch table in which rules should be installed
	private byte table;

	// Map of hosts to devices
	private Map<IDevice, Host> knownHosts;

	// record the previous openflow of each active switch
	// <Switch_pdid, <host, distance>>
	private Map<Long, Map<Host, BellmanFord.Distance>> preOpenflow;

	/**
	 * Loads dependencies and initializes data structures.
	 */
	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		log.info(String.format("Initializing %s...", MODULE_NAME));
		Map<String, String> config = context.getConfigParams(this);
		this.table = Byte.parseByte(config.get("table"));

		this.floodlightProv = context
				.getServiceImpl(IFloodlightProviderService.class);
		this.linkDiscProv = context.getServiceImpl(ILinkDiscoveryService.class);
		this.deviceProv = context.getServiceImpl(IDeviceService.class);

		this.knownHosts = new ConcurrentHashMap<IDevice, Host>();

		/*********************************************************************/
		/* TODO: Initialize other class variables, if necessary */
		preOpenflow = new ConcurrentHashMap<Long, Map<Host, BellmanFord.Distance>>();

		/*********************************************************************/
	}

	/**
	 * Subscribes to events and performs other startup tasks.
	 */
	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException {
		log.info(String.format("Starting %s...", MODULE_NAME));
		this.floodlightProv.addOFSwitchListener(this);
		this.linkDiscProv.addListener(this);
		this.deviceProv.addListener(this);

		/*********************************************************************/
		/* TODO: Perform other tasks, if necessary */

		/*********************************************************************/
	}

	/**
	 * Get the table in which this application installs rules.
	 */
	public byte getTable() {
		return this.table;
	}

	/**
	 * Get a list of all known hosts in the network.
	 */
	private Collection<Host> getHosts() {
		return this.knownHosts.values();
	}

	/**
	 * Get a map of all active switches in the network. Switch DPID is used as
	 * the key.
	 */
	private Map<Long, IOFSwitch> getSwitches() {
		return floodlightProv.getAllSwitchMap();
	}

	/**
	 * Get a list of all active links in the network.
	 */
	private Collection<Link> getLinks() {
		return linkDiscProv.getLinks().keySet();
	}

	/**
	 * Helper function to install rule.
	 */
	private void installRuleWrapper(IOFSwitch sw, int nw, int dstPort) {
		OFMatch matchCriteria = new OFMatch();
		matchCriteria.setDataLayerType(new Ethernet().TYPE_IPv4);
		// matchCriteria.setDataLayerDestination(MACAddress.valueOf(mac).toString());
		matchCriteria.setNetworkDestination(nw);

		List<OFInstruction> instructions = new LinkedList<OFInstruction>();
		List<OFAction> list = new ArrayList<OFAction>();
		// Modify dl_address
		Iterator<? extends IDevice> deviceIterator = this.deviceProv
				.queryDevices(null, null, nw, null, null);
		if (deviceIterator.hasNext()) {
			IDevice device = deviceIterator.next();
			byte[] deviceMac = MACAddress.valueOf(device.getMACAddress()).toBytes();
			OFAction actionDL = new OFActionSetField(OFOXMFieldType.ETH_DST, deviceMac);
			list.add(actionDL);
		}
		
		OFAction action = new OFActionOutput(dstPort);
		list.add(action);
		
		OFInstructionApplyActions applyAction = new OFInstructionApplyActions();
		applyAction.setActions(list);
		instructions.add(applyAction);

		SwitchCommands.installRule(sw, table, SwitchCommands.DEFAULT_PRIORITY,
				matchCriteria, instructions);
	}

	/**
	 * Helper function to remove rule.
	 */
	private void removeRuleWrapper(IOFSwitch sw, int nw) {
		OFMatch matchCriteria = new OFMatch();
		matchCriteria.setDataLayerType(new Ethernet().TYPE_IPv4);
		// matchCriteria.setDataLayerDestination(MACAddress.valueOf(mac).toString());
		matchCriteria.setNetworkDestination(nw);
		SwitchCommands.removeRules(sw, table, matchCriteria);
	}

	/**
	 * Helper function to compute the shortest path and update the Openflow.
	 */
	private void hostAdded(Host host) {
		// avoid Scheduled host
		if (host.getIPv4Address() != null) {
			Map<Long, BellmanFord.Distance> distances = BellmanFord
					.bellmanFordCompute(host, getSwitches(), getLinks());
			for (long dpid : distances.keySet()) {
				if (preOpenflow.containsKey(dpid)
						&& distances.get(dpid).d != Long.MAX_VALUE) {
					preOpenflow.get(dpid).put(host, distances.get(dpid));

					installRuleWrapper(getSwitches().get(dpid),
							host.getIPv4Address(), distances.get(dpid).dstPort);
				}
			}
		}
	}

	/**
	 * Helper function to remove host in each switches.
	 */
	private void hostRemoved(Host host) {
		// avoid Scheduled host
		if (host.getIPv4Address() != null) {
			for (IOFSwitch sw : getSwitches().values()) {
				if (preOpenflow.containsKey(sw.getId())
						&& preOpenflow.get(sw.getId()).containsKey(host)) {
					preOpenflow.get(sw.getId()).remove(host);

					removeRuleWrapper(sw, host.getIPv4Address());
				}
			}
		}
	}

	/**
	 * Helper function for updating switches or links.
	 */
	private void updateSwitchOrLink() {
		for (Host host : getHosts()) {
			// avoid Scheduled host
			if (host.isAttachedToSwitch() && host.getIPv4Address() != null) {
				Map<Long, BellmanFord.Distance> distances = BellmanFord
						.bellmanFordCompute(host, getSwitches(), getLinks());
				for (long dpid : distances.keySet()) {
					if (preOpenflow.containsKey(dpid)) {
						BellmanFord.Distance distance = preOpenflow.get(dpid)
								.get(host);
						if (distance != null) {
							if (distance.dstPort == distances.get(dpid).dstPort) {
								// no update for this host in that switch
								continue;
							} else {
								// remove the previous one
								removeRuleWrapper(getSwitches().get(dpid),
										host.getIPv4Address());
							}
						}
					} else {
						preOpenflow
								.put(dpid,
										new ConcurrentHashMap<Host, BellmanFord.Distance>());
					}

					// just update the openflow
					if (distances.get(dpid).d == Long.MAX_VALUE
							&& preOpenflow.get(dpid).containsKey(host)) {
						preOpenflow.get(dpid).remove(host);

						removeRuleWrapper(getSwitches().get(dpid),
								host.getIPv4Address());
					} else if (distances.get(dpid).d != Long.MAX_VALUE) {
						preOpenflow.get(dpid).put(host, distances.get(dpid));

						installRuleWrapper(getSwitches().get(dpid),
								host.getIPv4Address(),
								distances.get(dpid).dstPort);
					}
				}
			}
		}
	}

	/**
	 * Event handler called when a host joins the network.
	 * 
	 * @param device
	 *            information about the host
	 */
	@Override
	public void deviceAdded(IDevice device) {
		Host host = new Host(device, this.floodlightProv);
		// We only care about a new host if we know its IP
		if (host.getIPv4Address() != null) {
			log.info(String.format("Host %s added", host.getName()));
			this.knownHosts.put(device, host);

			/*****************************************************************/
			/* TODO: Update routing: add rules to route to new host */
			System.out
					.println("=======================deviceAdded=======================");
			hostAdded(host);
			/*****************************************************************/
		}
	}

	/**
	 * Event handler called when a host is no longer attached to a switch.
	 * 
	 * @param device
	 *            information about the host
	 */
	@Override
	public void deviceRemoved(IDevice device) {
		Host host = this.knownHosts.get(device);
		if (null == host) {
			host = new Host(device, this.floodlightProv);
			this.knownHosts.put(device, host);
		}

		log.info(String.format("Host %s is no longer attached to a switch",
				host.getName()));

		/*********************************************************************/
		/* TODO: Update routing: remove rules to route to host */
		System.out
				.println("=======================deviceRemoved=======================");
		hostRemoved(host);
		/*********************************************************************/
	}

	/**
	 * Event handler called when a host moves within the network.
	 * 
	 * @param device
	 *            information about the host
	 */
	@Override
	public void deviceMoved(IDevice device) {
		Host host = this.knownHosts.get(device);
		if (null == host) {
			host = new Host(device, this.floodlightProv);
			this.knownHosts.put(device, host);
		}

		if (!host.isAttachedToSwitch()) {
			this.deviceRemoved(device);
			return;
		}
		log.info(String.format("Host %s moved to s%d:%d", host.getName(), host
				.getSwitch().getId(), host.getPort()));

		/*********************************************************************/
		/* TODO: Update routing: change rules to route to host */
		System.out
				.println("=======================deviceMoved=======================");
		hostAdded(host);
		/*********************************************************************/
	}

	/**
	 * Event handler called when a switch joins the network.
	 * 
	 * @param DPID
	 *            for the switch
	 */
	@Override
	public void switchAdded(long switchId) {
		IOFSwitch sw = this.floodlightProv.getSwitch(switchId);
		log.info(String.format("Switch s%d added", switchId));

		/*********************************************************************/
		/* TODO: Update routing: change routing rules for all hosts */
		System.out
				.println("=======================switchAdded=======================");
		preOpenflow.put(switchId,
				new ConcurrentHashMap<Host, BellmanFord.Distance>());
		updateSwitchOrLink();
		/*********************************************************************/
	}

	/**
	 * Event handler called when a switch leaves the network.
	 * 
	 * @param DPID
	 *            for the switch
	 */
	@Override
	public void switchRemoved(long switchId) {
		IOFSwitch sw = this.floodlightProv.getSwitch(switchId);
		log.info(String.format("Switch s%d removed", switchId));

		/*********************************************************************/
		/* TODO: Update routing: change routing rules for all hosts */
		System.out
				.println("=======================switchRemoved=======================");
		if (preOpenflow.containsKey(switchId)) {
			preOpenflow.remove(switchId);
			updateSwitchOrLink();
		}
		/*********************************************************************/
	}

	/**
	 * Event handler called when multiple links go up or down.
	 * 
	 * @param updateList
	 *            information about the change in each link's state
	 */
	@Override
	public void linkDiscoveryUpdate(List<LDUpdate> updateList) {
		for (LDUpdate update : updateList) {
			// If we only know the switch & port for one end of the link, then
			// the link must be from a switch to a host
			if (0 == update.getDst()) {
				log.info(String.format("Link s%s:%d -> host updated",
						update.getSrc(), update.getSrcPort()));
			}
			// Otherwise, the link is between two switches
			else {
				log.info(String.format("Link s%s:%d -> s%s:%d updated",
						update.getSrc(), update.getSrcPort(), update.getDst(),
						update.getDstPort()));
			}
		}

		/*********************************************************************/
		/* TODO: Update routing: change routing rules for all hosts */
		System.out
				.println("=======================linkDiscoveryUpdate=======================");
		updateSwitchOrLink();
		/*********************************************************************/
	}

	/**
	 * Event handler called when link goes up or down.
	 * 
	 * @param update
	 *            information about the change in link state
	 */
	@Override
	public void linkDiscoveryUpdate(LDUpdate update) {
		this.linkDiscoveryUpdate(Arrays.asList(update));
	}

	/**
	 * Event handler called when the IP address of a host changes.
	 * 
	 * @param device
	 *            information about the host
	 */
	@Override
	public void deviceIPV4AddrChanged(IDevice device) {
		this.deviceAdded(device);
	}

	/**
	 * Event handler called when the VLAN of a host changes.
	 * 
	 * @param device
	 *            information about the host
	 */
	@Override
	public void deviceVlanChanged(IDevice device) { /*
													 * Nothing we need to do,
													 * since we're not using
													 * VLANs
													 */
	}

	/**
	 * Event handler called when the controller becomes the master for a switch.
	 * 
	 * @param DPID
	 *            for the switch
	 */
	@Override
	public void switchActivated(long switchId) { /*
												 * Nothing we need to do, since
												 * we're not switching
												 * controller roles
												 */
	}

	/**
	 * Event handler called when some attribute of a switch changes.
	 * 
	 * @param DPID
	 *            for the switch
	 */
	@Override
	public void switchChanged(long switchId) { /* Nothing we need to do */
	}

	/**
	 * Event handler called when a port on a switch goes up or down, or is added
	 * or removed.
	 * 
	 * @param DPID
	 *            for the switch
	 * @param port
	 *            the port on the switch whose status changed
	 * @param type
	 *            the type of status change (up, down, add, remove)
	 */
	@Override
	public void switchPortChanged(long switchId, ImmutablePort port,
			PortChangeType type) { /*
									 * Nothing we need to do, since we'll get a
									 * linkDiscoveryUpdate event
									 */
	}

	/**
	 * Gets a name for this module.
	 * 
	 * @return name for this module
	 */
	@Override
	public String getName() {
		return this.MODULE_NAME;
	}

	/**
	 * Check if events must be passed to another module before this module is
	 * notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPrereq(String type, String name) {
		return false;
	}

	/**
	 * Check if events must be passed to another module after this module has
	 * been notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPostreq(String type, String name) {
		return false;
	}

	/**
	 * Tell the module system which services we provide.
	 */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		Collection<Class<? extends IFloodlightService>> services = new ArrayList<Class<? extends IFloodlightService>>();
		services.add(InterfaceShortestPathSwitching.class);
		return services;
	}

	/**
	 * Tell the module system which services we implement.
	 */
	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		Map<Class<? extends IFloodlightService>, IFloodlightService> services = new HashMap<Class<? extends IFloodlightService>, IFloodlightService>();
		// We are the class that implements the service
		services.put(InterfaceShortestPathSwitching.class, this);
		return services;
	}

	/**
	 * Tell the module system which modules we depend on.
	 */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> modules = new ArrayList<Class<? extends IFloodlightService>>();
		modules.add(IFloodlightProviderService.class);
		modules.add(ILinkDiscoveryService.class);
		modules.add(IDeviceService.class);
		return modules;
	}
}
