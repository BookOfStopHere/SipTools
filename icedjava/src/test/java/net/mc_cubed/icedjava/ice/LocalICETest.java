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
package net.mc_cubed.icedjava.ice;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sdp.MediaDescription;
import javax.sdp.SdpException;
import javax.sdp.SdpFactory;
import javax.swing.SwingUtilities;
import junit.framework.Assert;
import junit.framework.TestCase;
import net.mc_cubed.icedjava.ice.IceStateMachine.AgentRole;
import net.mc_cubed.icedjava.ice.event.BytesAvailableEvent;
import net.mc_cubed.icedjava.ice.event.IceEvent;
import net.mc_cubed.icedjava.ice.event.IceEventListener;
import net.mc_cubed.icedjava.stun.StunUtil;

/**
 *
 * @author Charles Chappell
 */
public class LocalICETest extends TestCase {

    public LocalICETest(String testName) {
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

    public void testICESocket() throws SocketException, SdpException, InterruptedException, IOException, InvocationTargetException {
        final IceNegociationProgressForm form;
        if (!GraphicsEnvironment.isHeadless()) {
            IceNegociationProgressForm iceForm = null;
            try {
                iceForm = new IceNegociationProgressForm();
                iceForm.setTitle("ICESocket");
            } catch (Throwable t) {
            }

            form = iceForm;
        } else {
            form = null;
        }

        IcePeerImpl localPeer = null;
        IcePeerImpl remotePeer = null;

        try {
            MediaDescription[] medias = new MediaDescription[2];
            SdpFactory factory = SdpFactory.getInstance();
            medias[0] = factory.createMediaDescription("video", 0, 2, "RTP/AVP", new String[]{"26"});
            medias[1] = factory.createMediaDescription("audio", 0, 2, "RTP/AVP", new String[]{"8"});
            final IceSocket[] localSockets = new IceSocket[]{IceFactory.createIceSocket(medias[0].getMedia()),
                IceFactory.createIceSocket(medias[1].getMedia())};
            final IceSocket[] remoteSockets = new IceSocket[]{IceFactory.createIceSocket(medias[0].getMedia()),
                IceFactory.createIceSocket(medias[1].getMedia())};

            // Create a local peer for a yet unspecified remote peer
            localPeer = new IcePeerImpl("localPeer", AgentRole.CONTROLLING, localSockets);
            // Set local only mode
            localPeer.setLocalOnly(true);
            // Create a "remote" peer
            remotePeer = new IcePeerImpl("remotePeer", AgentRole.CONTROLLED, remoteSockets);
            // Set local only mode
            remotePeer.setLocalOnly(true);

            // Establish the SDP connection
            localPeer.setSdpListener(remotePeer);
            remotePeer.setSdpListener(localPeer);

            if (form != null) {
                form.getLocalPeer().setModel(new PairStatusTableModel(localPeer.checkPairs));
                form.getRemotePeer().setModel(new PairStatusTableModel(remotePeer.checkPairs));

                SwingUtilities.invokeLater(new Runnable() {

                    @Override
                    public void run() {
                        form.setVisible(true);
                    }
                });
            }

            Assert.assertEquals(IceStatus.NOT_STARTED, localPeer.getStatus());
            Assert.assertEquals(IceStatus.NOT_STARTED, remotePeer.getStatus());

            // Start the state machines
            localPeer.start();
            remotePeer.start();
            // Wait for the threads to run a tiny bit
            Thread.sleep(100);

            Assert.assertEquals(IceStatus.IN_PROGRESS, localPeer.getStatus());
            Assert.assertEquals(IceStatus.IN_PROGRESS, remotePeer.getStatus());


            long startTime = new Date().getTime();
            // Wait for the state machines to die, or 60 seconds to pass
            while (new Date().getTime() - startTime < 60000 && (localPeer.getStatus() == IceStatus.IN_PROGRESS || remotePeer.getStatus() == IceStatus.IN_PROGRESS)) {
                Thread.sleep(500);

                if (form != null) {
                    SwingUtilities.invokeAndWait(new Runnable() {

                        @Override
                        public void run() {
                            form.getLocalPeer().updateUI();
                            form.getRemotePeer().updateUI();
                        }
                    });
                }
            }

            Assert.assertEquals(IceStatus.SUCCESS, localPeer.getStatus());
            //System.out.println(localPeer.createOffer());
            Assert.assertEquals(IceStatus.SUCCESS, remotePeer.getStatus());
            //System.out.println(remotePeer.createOffer());

            // Get the nominated connection
            Assert.assertNotNull(localPeer.getNominated());
            Assert.assertNotNull(remotePeer.getNominated());
            Assert.assertEquals(2, localPeer.getNominated().size());
            Assert.assertEquals(2, remotePeer.getNominated().size());

            final byte[] data = new byte[30];
            // Attach a listener to component 0 of remotesocket 0
            remotePeer.getChannels(remoteSockets[0]).get(0).addEventListener(new IceEventListener() {

                @Override
                public void iceEvent(IceEvent event) {
                    if (event instanceof BytesAvailableEvent) {
                        try {
                            BytesAvailableEvent bytesEvent = (BytesAvailableEvent) event;
                            ByteBuffer buffer = ByteBuffer.allocate(StunUtil.MAX_PACKET_SIZE);
                            bytesEvent.getSocketChannel().read(buffer);
                            System.out.println("Received Datagram: " + buffer);
                            System.arraycopy(buffer.array(), buffer.arrayOffset() + buffer.position(), data, 0, Math.min(buffer.remaining(),data.length));
                        } catch (IOException ex) {
                            Logger.getLogger(LocalICETest.class.getName()).log(Level.SEVERE, null, ex);
                        }

                    }
                }
            });

            ByteBuffer bb = ByteBuffer.allocate(8);
            bb.put("Testing".getBytes());
            bb.flip();
            // Write to component 0 of localsocket 0
            localPeer.getChannels(localSockets[0]).get(0).write(bb);

            // Wait for the data to arrive
            Thread.sleep(100);

            // Test for the presense of the data
            Assert.assertEquals("Testing", new String(data, 0, 7));

            //System.out.println(localPeer.createOffer());
            //System.out.println(remotePeer.createOffer());
        } finally {
            if (localPeer != null) {
                localPeer.setSdpListener(null);
                localPeer.close();
            }
            if (remotePeer != null) {
                remotePeer.setSdpListener(null);
                remotePeer.close();
            }

            if (form != null) {
                form.setVisible(false);
            }
        }

    }

    public void testAggressiveICESocket() throws SocketException, SdpException, InterruptedException, IOException, InvocationTargetException {
        final IceNegociationProgressForm form;
        if (!GraphicsEnvironment.isHeadless()) {
            IceNegociationProgressForm iceForm = null;
            try {
                iceForm = new IceNegociationProgressForm();
                iceForm.setTitle("AggressiveICESocket");
            } catch (Throwable t) {
            }

            form = iceForm;
        } else {
            form = null;
        }

        IcePeerImpl localPeer = null;
        IcePeerImpl remotePeer = null;

        try {
            MediaDescription[] medias = new MediaDescription[2];
            SdpFactory factory = SdpFactory.getInstance();
            medias[0] = factory.createMediaDescription("video", 0, 2, "RTP/AVP", new String[]{"26"});
            medias[1] = factory.createMediaDescription("audio", 0, 2, "RTP/AVP", new String[]{"8"});
            final IceSocket[] localSockets = new IceSocket[]{IceFactory.createIceSocket(medias[0].getMedia()),
                IceFactory.createIceSocket(medias[1].getMedia())};
            final IceSocket[] remoteSockets = new IceSocket[]{IceFactory.createIceSocket(medias[0].getMedia()),
                IceFactory.createIceSocket(medias[1].getMedia())};

            // Create a local peer for a yet unspecified remote peer
            localPeer = new IcePeerImpl("localPeer", AgentRole.CONTROLLING, localSockets);
            // Set local only mode
            localPeer.setLocalOnly(true);
            // Set Aggressive nomination on the controlling peer
            localPeer.setNomination(IceStateMachine.NominationType.AGGRESSIVE);
            // Create a "remote" peer
            remotePeer = new IcePeerImpl("remotePeer", AgentRole.CONTROLLED, remoteSockets);
            // Set local only mode
            remotePeer.setLocalOnly(true);

            // Establish the SDP connection
            localPeer.setSdpListener(remotePeer);
            remotePeer.setSdpListener(localPeer);

            if (form != null) {
                form.getLocalPeer().setModel(new PairStatusTableModel(localPeer.checkPairs));
                form.getRemotePeer().setModel(new PairStatusTableModel(remotePeer.checkPairs));

                SwingUtilities.invokeLater(new Runnable() {

                    @Override
                    public void run() {
                        form.setVisible(true);
                    }
                });
            }

            Assert.assertEquals(IceStatus.NOT_STARTED, localPeer.getStatus());
            Assert.assertEquals(IceStatus.NOT_STARTED, remotePeer.getStatus());

            // Start the state machines
            localPeer.start();
            remotePeer.start();
            // Wait for the threads to run a tiny bit
            Thread.sleep(100);

            Assert.assertEquals(IceStatus.IN_PROGRESS, localPeer.getStatus());
            Assert.assertEquals(IceStatus.IN_PROGRESS, remotePeer.getStatus());


            long startTime = new Date().getTime();
            // Wait for the state machines to die, or 60 seconds to pass
            while (new Date().getTime() - startTime < 60000 && (localPeer.getStatus() == IceStatus.IN_PROGRESS || remotePeer.getStatus() == IceStatus.IN_PROGRESS)) {
                Thread.sleep(500);

                if (form != null) {
                    SwingUtilities.invokeAndWait(new Runnable() {

                        @Override
                        public void run() {
                            form.getLocalPeer().updateUI();
                            form.getRemotePeer().updateUI();
                        }
                    });
                }
            }

            Assert.assertEquals(IceStatus.SUCCESS, localPeer.getStatus());
            //System.out.println(localPeer.createOffer());
            Assert.assertEquals(IceStatus.SUCCESS, remotePeer.getStatus());
            //System.out.println(remotePeer.createOffer());

            // Get the nominated connection
            Assert.assertNotNull(localPeer.getNominated());
            Assert.assertNotNull(remotePeer.getNominated());
            Assert.assertEquals(2, localPeer.getNominated().size());
            Assert.assertEquals(2, remotePeer.getNominated().size());

            final byte[] data = new byte[30];
            // Test the actual data connection
            // Test the actual data connection
            remotePeer.getChannels(remoteSockets[0]).get(0).addEventListener(new IceEventListener() {

                @Override
                public void iceEvent(IceEvent event) {
                    if (event instanceof BytesAvailableEvent) {
                        try {
                            BytesAvailableEvent bytesEvent = (BytesAvailableEvent) event;
                            ByteBuffer buffer = ByteBuffer.allocate(StunUtil.MAX_PACKET_SIZE);
                            bytesEvent.getSocketChannel().read(buffer);
                            System.out.println("Received Datagram: " + buffer);
                            System.arraycopy(buffer.array(), buffer.arrayOffset() + buffer.position(), data, 0, Math.min(buffer.remaining(),data.length));
                        } catch (IOException ex) {
                            Logger.getLogger(LocalICETest.class.getName()).log(Level.SEVERE, null, ex);
                        }

                    }
                }
            });

            ByteBuffer bb = ByteBuffer.allocate(8);
            bb.put("Testing".getBytes());
            bb.flip();
            // Write to component 0 of localsocket 0
            localPeer.getChannels(localSockets[0]).get(0).write(bb);

            // Wait for the data to arrive
            Thread.sleep(100);

            // Test for the presense of the data
            Assert.assertEquals("Testing", new String(data, 0, 7));

            //System.out.println(localPeer.createOffer());
            //System.out.println(remotePeer.createOffer());
        } finally {
            if (localPeer != null) {
                localPeer.setSdpListener(null);
                localPeer.close();
            }
            if (remotePeer != null) {
                remotePeer.setSdpListener(null);
                remotePeer.close();
            }

            if (form != null) {
                form.setVisible(false);
            }
        }

    }

    public void testICESocketConflict() throws SocketException, SdpException, InterruptedException, IOException, InvocationTargetException {
        final IceNegociationProgressForm form;
        if (!GraphicsEnvironment.isHeadless()) {
            IceNegociationProgressForm iceForm = null;
            try {
                iceForm = new IceNegociationProgressForm();
                iceForm.setTitle("ICESocketConflict");
            } catch (Throwable t) {
            }

            form = iceForm;
        } else {
            form = null;
        }

        IcePeerImpl localPeer = null;
        IcePeerImpl remotePeer = null;

        try {
            MediaDescription[] medias = new MediaDescription[2];
            SdpFactory factory = SdpFactory.getInstance();
            medias[0] = factory.createMediaDescription("video", 0, 2, "RTP/AVP", new String[]{"26"});
            medias[1] = factory.createMediaDescription("audio", 0, 2, "RTP/AVP", new String[]{"8"});
            final IceSocket[] localSockets = new IceSocket[]{IceFactory.createIceSocket(medias[0].getMedia()),
                IceFactory.createIceSocket(medias[1].getMedia())};
            final IceSocket[] remoteSockets = new IceSocket[]{IceFactory.createIceSocket(medias[0].getMedia()),
                IceFactory.createIceSocket(medias[1].getMedia())};

            // Create a local peer for a yet unspecified remote peer
            localPeer = new IcePeerImpl("localPeer", AgentRole.CONTROLLING, localSockets);
            // Set local only mode
            localPeer.setLocalOnly(true);
            // Create a "remote" peer
            remotePeer = new IcePeerImpl("remotePeer", AgentRole.CONTROLLING, remoteSockets);
            // Set local only mode
            remotePeer.setLocalOnly(true);

            // Establish the SDP connection
            localPeer.setSdpListener(remotePeer);
            remotePeer.setSdpListener(localPeer);

            if (form != null) {
                form.getLocalPeer().setModel(new PairStatusTableModel(localPeer.checkPairs));
                form.getRemotePeer().setModel(new PairStatusTableModel(remotePeer.checkPairs));

                SwingUtilities.invokeLater(new Runnable() {

                    @Override
                    public void run() {
                        form.setVisible(true);
                    }
                });
            }

            Assert.assertEquals(IceStatus.NOT_STARTED, localPeer.getStatus());
            Assert.assertEquals(IceStatus.NOT_STARTED, remotePeer.getStatus());

            // Start the state machines
            localPeer.start();
            remotePeer.start();
            // Wait for the threads to run a tiny bit
            Thread.sleep(100);

            Assert.assertEquals(IceStatus.IN_PROGRESS, localPeer.getStatus());
            Assert.assertEquals(IceStatus.IN_PROGRESS, remotePeer.getStatus());


            long startTime = new Date().getTime();
            // Wait for the state machines to die, or 30 seconds to pass
            while (new Date().getTime() - startTime < 30000 && (localPeer.getStatus() == IceStatus.IN_PROGRESS || remotePeer.getStatus() == IceStatus.IN_PROGRESS)) {
                Thread.sleep(500);

                if (form != null) {
                    SwingUtilities.invokeAndWait(new Runnable() {

                        @Override
                        public void run() {
                            form.getLocalPeer().updateUI();
                            form.getRemotePeer().updateUI();
                        }
                    });
                }
            }

            Assert.assertEquals(IceStatus.SUCCESS, localPeer.getStatus());
            //System.out.println(localPeer.createOffer());
            Assert.assertEquals(IceStatus.SUCCESS, remotePeer.getStatus());
            //System.out.println(remotePeer.createOffer());

            // Get the nominated connection
            Assert.assertNotNull(localPeer.getNominated());
            Assert.assertNotNull(remotePeer.getNominated());
            Assert.assertEquals(2, localPeer.getNominated().size());
            Assert.assertEquals(2, remotePeer.getNominated().size());

            final byte[] data = new byte[30];
            // Test the actual data connection
            // Test the actual data connection
            remotePeer.getChannels(remoteSockets[0]).get(0).addEventListener(new IceEventListener() {

                @Override
                public void iceEvent(IceEvent event) {
                    if (event instanceof BytesAvailableEvent) {
                        try {
                            BytesAvailableEvent bytesEvent = (BytesAvailableEvent) event;
                            ByteBuffer buffer = ByteBuffer.allocate(StunUtil.MAX_PACKET_SIZE);
                            bytesEvent.getSocketChannel().read(buffer);
                            System.out.println("Received Datagram: " + buffer);
                            System.arraycopy(buffer.array(), buffer.arrayOffset() + buffer.position(), data, 0, Math.min(buffer.remaining(),data.length));
                        } catch (IOException ex) {
                            Logger.getLogger(LocalICETest.class.getName()).log(Level.SEVERE, null, ex);
                        }

                    }
                }
            });

            ByteBuffer bb = ByteBuffer.allocate(8);
            bb.put("Testing".getBytes());
            bb.flip();
            // Write to component 0 of localsocket 0
            localPeer.getChannels(localSockets[0]).get(0).write(bb);

            // Wait for the data to arrive
            Thread.sleep(100);

            // Test for the presense of the data
            Assert.assertEquals("Testing", new String(data, 0, 7));

            //System.out.println(localPeer.createOffer());
            //System.out.println(remotePeer.createOffer());

        } finally {
            if (localPeer != null) {
                localPeer.setSdpListener(null);
                localPeer.close();
            }
            if (remotePeer != null) {
                remotePeer.setSdpListener(null);
                remotePeer.close();
            }

            if (form != null) {
                form.setVisible(false);
            }
        }
    }

    public void testAggressiveICESocketConflict() throws SocketException, SdpException, InterruptedException, IOException, InvocationTargetException {
        final IceNegociationProgressForm form;

        if (!GraphicsEnvironment.isHeadless()) {
            IceNegociationProgressForm iceForm = null;
            try {
                iceForm = new IceNegociationProgressForm();
                iceForm.setTitle("AggressiveICESocketConflict");
            } catch (Throwable t) {
            }

            form = iceForm;
        } else {
            form = null;
        }

        IcePeerImpl localPeer = null;
        IcePeerImpl remotePeer = null;

        try {
            MediaDescription[] medias = new MediaDescription[2];
            SdpFactory factory = SdpFactory.getInstance();
            medias[0] = factory.createMediaDescription("video", 0, 2, "RTP/AVP", new String[]{"26"});
            medias[1] = factory.createMediaDescription("audio", 0, 2, "RTP/AVP", new String[]{"8"});
            final IceSocket[] localSockets = new IceSocket[]{IceFactory.createIceSocket(medias[0].getMedia()),
                IceFactory.createIceSocket(medias[1].getMedia())};
            final IceSocket[] remoteSockets = new IceSocket[]{IceFactory.createIceSocket(medias[0].getMedia()),
                IceFactory.createIceSocket(medias[1].getMedia())};

            // Create a local peer for a yet unspecified remote peer
            localPeer = new IcePeerImpl("localPeer", AgentRole.CONTROLLING, localSockets);
            // Set local only mode
            localPeer.setLocalOnly(true);
            // Set Aggressive Nomination on the local peer
            localPeer.setNomination(IceStateMachine.NominationType.AGGRESSIVE);
            // Create a "remote" peer
            remotePeer = new IcePeerImpl("remotePeer", AgentRole.CONTROLLING, remoteSockets);
            // Set local only mode
            remotePeer.setLocalOnly(true);
            // Set Aggressive nomination on the remote peer
            remotePeer.setNomination(IceStateMachine.NominationType.AGGRESSIVE);

            // Establish the SDP connection
            localPeer.setSdpListener(remotePeer);
            remotePeer.setSdpListener(localPeer);

            if (form != null) {
                form.getLocalPeer().setModel(new PairStatusTableModel(localPeer.checkPairs));
                form.getRemotePeer().setModel(new PairStatusTableModel(remotePeer.checkPairs));

                SwingUtilities.invokeLater(new Runnable() {

                    @Override
                    public void run() {
                        form.setVisible(true);
                    }
                });
            }

            Assert.assertEquals(IceStatus.NOT_STARTED, localPeer.getStatus());
            Assert.assertEquals(IceStatus.NOT_STARTED, remotePeer.getStatus());

            // Start the state machines
            localPeer.start();
            remotePeer.start();
            // Wait for the threads to run a tiny bit
            Thread.sleep(100);

            Assert.assertEquals(IceStatus.IN_PROGRESS, localPeer.getStatus());
            Assert.assertEquals(IceStatus.IN_PROGRESS, remotePeer.getStatus());


            long startTime = new Date().getTime();
            // Wait for the state machines to die, or 60 seconds to pass
            while (new Date().getTime() - startTime < 60000 && (localPeer.getStatus() == IceStatus.IN_PROGRESS || remotePeer.getStatus() == IceStatus.IN_PROGRESS)) {
                Thread.sleep(500);

                if (form != null) {
                    SwingUtilities.invokeAndWait(new Runnable() {

                        @Override
                        public void run() {
                            form.getLocalPeer().updateUI();
                            form.getRemotePeer().updateUI();
                        }
                    });
                }
            }

            Assert.assertEquals(IceStatus.SUCCESS, localPeer.getStatus());
            //System.out.println(localPeer.createOffer());
            Assert.assertEquals(IceStatus.SUCCESS, remotePeer.getStatus());
            //System.out.println(remotePeer.createOffer());

            // Get the nominated connection
            Assert.assertNotNull(localPeer.getNominated());
            Assert.assertNotNull(remotePeer.getNominated());
            Assert.assertEquals(2, localPeer.getNominated().size());
            Assert.assertEquals(2, remotePeer.getNominated().size());

            final byte[] data = new byte[30];
            // Test the actual data connection
            // Test the actual data connection
            remotePeer.getChannels(remoteSockets[0]).get(0).addEventListener(new IceEventListener() {

                @Override
                public void iceEvent(IceEvent event) {
                    if (event instanceof BytesAvailableEvent) {
                        try {
                            BytesAvailableEvent bytesEvent = (BytesAvailableEvent) event;
                            ByteBuffer buffer = ByteBuffer.allocate(StunUtil.MAX_PACKET_SIZE);
                            bytesEvent.getSocketChannel().read(buffer);
                            System.out.println("Received Datagram: " + buffer);
                            System.arraycopy(buffer.array(), buffer.arrayOffset() + buffer.position(), data, 0, Math.min(buffer.remaining(),data.length));
                        } catch (IOException ex) {
                            Logger.getLogger(LocalICETest.class.getName()).log(Level.SEVERE, null, ex);
                        }

                    }
                }
            });

            ByteBuffer bb = ByteBuffer.allocate(8);
            bb.put("Testing".getBytes());
            bb.flip();
            // Write to component 0 of localsocket 0
            localPeer.getChannels(localSockets[0]).get(0).write(bb);

            // Wait for the data to arrive
            Thread.sleep(100);

            // Test for the presense of the data
            Assert.assertEquals("Testing", new String(data, 0, 7));

            //System.out.println(localPeer.createOffer());
            //System.out.println(remotePeer.createOffer());

        } finally {
            if (localPeer != null) {
                localPeer.setSdpListener(null);
                localPeer.close();
            }
            if (remotePeer != null) {
                remotePeer.setSdpListener(null);
                remotePeer.close();
            }

            if (form != null) {
                form.setVisible(false);
            }
        }
    }
}
