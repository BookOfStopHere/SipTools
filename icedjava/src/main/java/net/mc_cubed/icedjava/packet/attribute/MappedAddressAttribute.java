/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package net.mc_cubed.icedjava.packet.attribute;

import java.net.InetAddress;

/**
 *
 * @author charles
 */
public interface MappedAddressAttribute extends Attribute {

    InetAddress getAddress();

    int getPort();

}
