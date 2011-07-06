/*
 * Copyright 2011 Charles Chappell.
 *
 * This file is part of IcedJava.
 *
 * IcedJava is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * IcedJava is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with IcedJava.  If not, see
 * <http://www.gnu.org/licenses/>.
 */
package net.mc_cubed.icedjava.ice.pmp;

import com.hoodcomputing.natpmp.ExternalAddressRequestMessage;
import com.hoodcomputing.natpmp.MapRequestMessage;
import com.hoodcomputing.natpmp.NatPmpDevice;
import com.hoodcomputing.natpmp.NatPmpException;
import java.io.IOException;
import java.net.Inet4Address;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedList;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.mc_cubed.icedjava.ice.AddressDiscovery;
import net.mc_cubed.icedjava.ice.Candidate.CandidateType;
import net.mc_cubed.icedjava.ice.DiscoveryMechanism;
import net.mc_cubed.icedjava.ice.KeepaliveHandler;
import net.mc_cubed.icedjava.ice.LocalCandidate;
import net.mc_cubed.icedjava.stun.TransportType;

/**
 * Collects NAT assisted candidates from a PMP enabled gateway device
 *
 * WARNING: This class is dead pending either an LGPL compliant source donation,
 * a new library that works, or me writing a new from-scratch PMP library class.
 * 
 * jNAT-PMPlib is entirely too unstable.
 * 
 * @author Charles Chappell
 * @since 1.0
 */
@DiscoveryMechanism
@SuppressWarnings("StaticNonFinalUsedInInitialization")
public class IcePMPBridge implements AddressDiscovery, KeepaliveHandler {

    public final int KEEPALIVE_INTERVAL = 3600;
    static volatile NatPmpDevice pmpDevice;
    static Inet4Address extIP = null;

    public IcePMPBridge() throws IOException {
    }

    static {
        try {
            // To find the device, simply construct the class. An exception is
            // thrown if the device cannot be located or if the network is not
            // RFC1918.
            // When the device is constructed, you have to tell it whether you
            // want it to automatically shutdown with the JVM or if you'll take
            // the responsibility of shutting it down yourself. Refer to the
            // constructor documentation for the details. In this case, we'll
            // let it shut down with the JVM.
            pmpDevice = new NatPmpDevice(true);

            // The next step is always to determine the external address of
            // the device. This is done by constructing the request message
            // and enqueueing it.
            ExternalAddressRequestMessage extAddr = new ExternalAddressRequestMessage(null);
            pmpDevice.enqueueMessage(extAddr);

            // In this example, we want to purposefully wait until the queue is
            // empty. It is possible to receive notification when the operation
            // is complete. Refer to the documentation for the
            // ExternalAddressRequestMessage constructor.
            pmpDevice.waitUntilQueueEmpty();

            // We can try and get the external address to determine if the
            // gateway is functional.
            // This may throw an exception if there was an error receiving the
            // response. The method getResponseException() would also return an
            // exception object in this case, if you prefer avoiding using
            // try/catch for logic.
            extIP = extAddr.getExternalAddress();

            pmpDevice.setShutdownHookEnabled(false);

        } catch (NatPmpException ex) {
        }
    }

    /**
     * Performs NAT-PMP discovery of candidates based on the LocalCandidates with
     * IPv4 addresses. IPv6 addresses are not, in general, modified by gateways
     * and the NAT libraries used do not support IPv6 discovery.
     *
     * WARNING: This class is dead pending either an LGPL compliant source donation,
     * a new library that works, or me writing a new from-scratch PMP library class.
     * 
     * jNAT-PMPlib is too unstable.
     * 
     * @param localCandidates Candidates to use as a base for NAT-PMP discovery.
     * This plugin filters all non-local candidates.
     * @return a list of NAT-PMP discovered candidates based on the LOCAL
     * candidates supplied in the parameter
     */
    @Override
    public Collection<LocalCandidate> getCandidates(Collection<LocalCandidate> lcs) {
        Collection<LocalCandidate> retval = new LinkedList<LocalCandidate>();

        // Fast fail if a NAT-PMP device wasn't discovered during static
        // initialization
        if (pmpDevice != null) {
            // Loop through the Local Candidates, looking for LOCAL type candidates
            for (LocalCandidate lc : lcs) {
                // Only try NAT-PMP for Host Local IPv4 candidates
                if (lc.getType() == CandidateType.LOCAL && lc.getAddress() instanceof Inet4Address) {
                    /** 
                     * Check whether the router and local address are on the same
                     * network.  If not, there's little point in attempting this
                     * mapping, as it's highly unlikely to work.
                     * 
                     * Currently, there doesn't seem to be a good way to do this
                     * TODO: Find a good way to check whether the gateway and 
                     * IP are on the same network/network interface
                     */
                    //NetworkInterface iface = NetworkInterface.getByInetAddress(lc.getAddress());
                    try {
                        // Now, we can set up a port mapping. Refer to the javadoc for
                        // the parameter values. This message sets up a TCP redirect from
                        // a gateway-selected available external port to the local port
                        // 5000. The lifetime is 120 seconds. In implementation, you would
                        // want to consider having a longer lifetime and periodicly sending
                        // a MapRequestMessage to prevent it from expiring.

                        MapRequestMessage map = new MapRequestMessage((lc.getTransport() == TransportType.TCP), lc.getPort(), 0, KEEPALIVE_INTERVAL, null);
                        pmpDevice.enqueueMessage(map);
                        pmpDevice.waitUntilQueueEmpty();

                        Long mapLifetime = map.getPortMappingLifetime();

                        // Let's find out what the external port is.
                        int extPort = map.getExternalPort();

                        // All set!

                        // Please refer to the javadoc if you run into trouble. As always,
                        // contact a developer on the SourceForge project or post in the
                        // forums if you have questions.

                        LocalCandidate newLc = new LocalCandidate(lc.getOwner(), lc.getIceSocket(), CandidateType.NAT_ASSISTED, extIP, extPort, lc);


                        // Set the next keepalive time
                        newLc.setNextKeepalive(nextKeepaliveTime(mapLifetime));

                        // Make sure this bridge is called for keepalives
                        newLc.setKeepaliveHandler(this);

                        retval.add(newLc);
                    } catch (NatPmpException ex) {
                        /**
                         * In general, we're not too concerned about this exception.
                         * Usually this means the mapping failed for some reason,
                         * and we will go on to the next entry.
                         */
                    }
                }
            }
        }
        return retval;
    }

    @Override
    public void doKeepalive(LocalCandidate lc) {
        try {
            MapRequestMessage map = new MapRequestMessage((lc.getTransport() == TransportType.TCP), lc.getPort(), 0, KEEPALIVE_INTERVAL, null);
            pmpDevice.enqueueMessage(map);
            pmpDevice.waitUntilQueueEmpty();
            Long mapLifetime = map.getPortMappingLifetime();

            lc.setNextKeepalive(nextKeepaliveTime(mapLifetime));
        } catch (NatPmpException npe) {
            Logger.getLogger(getClass().getName()).log(Level.WARNING,"Caught an exception refreshing a LC.  Setting it to retry in 10 seconds",npe);
            lc.setNextKeepalive(nextKeepaliveTime(Long.valueOf(20)));
        }
    }

    /**
     * Calculate the next keep-alive time based on the given timeout interval
     * 
     * @param mapLifetime
     * @return 
     */
    Date nextKeepaliveTime(Long mapLifetime) {
        // Calculate the next keepalive time                        
        Date nextKeepalive;
        if (mapLifetime > 600) {
            nextKeepalive = new Date(new Date().getTime() + mapLifetime * 900);
        } else {
            nextKeepalive = new Date(new Date().getTime() + mapLifetime * 500);
        }

        return nextKeepalive;
    }
}
