/*
 * Copyright 2009 Charles Chappell.
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
package net.mc_cubed.icedjava;

import net.mc_cubed.icedjava.util.StringUtils;
import net.mc_cubed.icedjava.packet.attribute.MappedAddressAttribute;
import net.mc_cubed.icedjava.packet.attribute.XORMappedAddressAttribute;
import net.mc_cubed.icedjava.packet.header.MessageClass;
import net.mc_cubed.icedjava.packet.header.MessageHeader;
import net.mc_cubed.icedjava.packet.header.MessageMethod;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import junit.framework.Assert;
import junit.framework.TestCase;
import net.mc_cubed.icedjava.packet.StunPacket;
import net.mc_cubed.icedjava.stun.StunUtil;

/**
 *
 * @author Charles Chappell
 */
public class PacketFormTest extends TestCase {

    public PacketFormTest(String testName) {
        super(testName);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    // TODO add test methods here. The name must begin with 'test'. For example:
    public void testBasicPacket() throws UnknownHostException, UnsupportedEncodingException {

        StunPacket packet = StunUtil.createStunRequest(MessageClass.REQUEST, MessageMethod.BINDING);
        packet.getAttributes().add(new MappedAddressAttribute(InetAddress.getLocalHost(), 1234));
        packet.getAttributes().add(new XORMappedAddressAttribute(InetAddress.getLocalHost(), 1234, (byte) 0xB7));

        byte[] result = packet.getBytes();
        Assert.assertEquals(44, result.length);

        System.out.println("Packet: " + StringUtils.getHexString(result));

        // Encoded Message Class and Method
        Assert.assertEquals(0x00, result[0]);
        Assert.assertEquals(0x01, result[1]);

        // Length is 12
        Assert.assertEquals(0, result[2]);
        Assert.assertEquals(24, result[3]);

        // Magic cookie matches
        Assert.assertEquals(MessageHeader.MAGIC_COOKIE[0], result[4]);
        Assert.assertEquals(MessageHeader.MAGIC_COOKIE[1], result[5]);
        Assert.assertEquals(MessageHeader.MAGIC_COOKIE[2], result[6]);
        Assert.assertEquals(MessageHeader.MAGIC_COOKIE[3], result[7]);


    }
}
