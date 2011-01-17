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
import java.net.InetAddress;
import java.util.Collection;
import java.util.LinkedList;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.mc_cubed.icedjava.ice.AddressDiscovery;
import net.mc_cubed.icedjava.ice.AddressDiscoveryMechanism;
import net.mc_cubed.icedjava.ice.Candidate.CandidateType;
import net.mc_cubed.icedjava.ice.LocalCandidate;
import net.mc_cubed.icedjava.stun.TransportType;

/**
 * Collects NAT assisted candidates from a PMP enabled gateway device
 *
 * @author Charles Chappell
 * @since 1.0
 */
@AddressDiscoveryMechanism
@SuppressWarnings("StaticNonFinalUsedInInitialization")
public class IcePMPBridge implements AddressDiscovery {

    static NatPmpDevice pmpDevice;
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

        } catch (NatPmpException ex) {
        }
    }

    @Override
    public Collection<LocalCandidate> getCandidates(Collection<LocalCandidate> lcs) {
        Collection<LocalCandidate> retval = new LinkedList<LocalCandidate>();
        InetAddress natAddress = extIP;
        for (LocalCandidate lc : lcs) {
            if (lc.getType() == CandidateType.LOCAL && lc.getAddress() instanceof Inet4Address) {
                if (pmpDevice != null) {
                    try {
                        // Now, we can set up a port mapping. Refer to the javadoc for
                        // the parameter values. This message sets up a TCP redirect from
                        // a gateway-selected available external port to the local port
                        // 5000. The lifetime is 120 seconds. In implementation, you would
                        // want to consider having a longer lifetime and periodicly sending
                        // a MapRequestMessage to prevent it from expiring.

                        MapRequestMessage map = new MapRequestMessage((lc.getTransport() == TransportType.TCP), lc.getPort(), 0, 300, null);
                        pmpDevice.enqueueMessage(map);
                        pmpDevice.waitUntilQueueEmpty();

                        // Let's find out what the external port is.
                        int extPort = map.getExternalPort();

                        // All set!

                        // Please refer to the javadoc if you run into trouble. As always,
                        // contact a developer on the SourceForge project or post in the
                        // forums if you have questions.

                        retval.add(new LocalCandidate(lc.getOwner(), lc.getIceSocket(), CandidateType.NAT_ASSISTED, natAddress, extPort, lc));
                    } catch (NatPmpException ex) {
                        Logger.getLogger(IcePMPBridge.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
            }
        }

        return retval;
    }
}
