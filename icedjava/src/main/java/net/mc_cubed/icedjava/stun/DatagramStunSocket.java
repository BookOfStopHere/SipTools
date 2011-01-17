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
package net.mc_cubed.icedjava.stun;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.mc_cubed.icedjava.packet.header.MessageClass;
import net.mc_cubed.icedjava.packet.header.MessageMethod;
import net.mc_cubed.icedjava.util.ExpiringCache;
import java.io.IOException;
import java.math.BigInteger;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.mc_cubed.icedjava.packet.StunPacket;
import net.mc_cubed.icedjava.packet.attribute.FingerprintAttribute;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelDownstreamHandler;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipelineCoverage;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ChannelUpstreamHandler;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;

/**
 *
 * @author Charles Chappell
 */
@ChannelPipelineCoverage(ChannelPipelineCoverage.ONE)
public class DatagramStunSocket extends SimpleChannelHandler implements StunPacketSender, ChannelUpstreamHandler, ChannelDownstreamHandler {

    protected Logger log = Logger.getLogger(getClass().getName());
    ExpiringCache<BigInteger, StunReplyFuture> requestCache = new ExpiringCache<BigInteger, StunReplyFuture>();
    protected StunListener stunListener;
    /**
     * RFC 5389 7.1:
     * All STUN messages sent over UDP SHOULD be less than the path MTU if known.
     * If the path MTU is unknown, messages SHOULD be the smaller of 576 and the
     * first-hop MTU for IPv4 and 1280 bytes for IPv6.
     */
    private int ip4MaxLength = 548;  // IP4 header = 28 bytes
    private int ip6MaxLength = 1232; // IP6 header = 48 bytes fixed
    private int maxRetries = 7; // RFC 5389 7.2.1:  Rc
    private int initialTimeout = 500; // RFC 5389 7.2.1: RTO
    protected Channel channel;

    protected DatagramStunSocket(StunListenerType type) throws SocketException {
        this.stunListener = new GenericStunListener(this, type);
    }

    protected DatagramStunSocket(StunListener stunListener) throws SocketException {
        this.stunListener = stunListener;
    }

    protected DatagramStunSocket() throws SocketException {
        this(StunListenerType.BOTH);
    }

    @Override
    public ChannelFuture send(InetAddress addr, int port, StunPacket packet) throws IOException {
        return send(new InetSocketAddress(addr, port), packet);
    }

    @Override
    public ChannelFuture send(SocketAddress remoteSocket, StunPacket packet) throws IOException {
        /**
         * RFC 5389 7.1:
         * All STUN messages sent over UDP SHOULD be less than the path MTU, if
         * known. If the path MTU is unknown, messages SHOULD be the smaller of
         * 576 bytes and the first-hop MTU for IPv4 and 1280 bytes for IPv6
         */
        if (((InetSocketAddress) remoteSocket).getAddress() instanceof Inet4Address) {
            if (packet.getBytes().length > ip4MaxLength) {
                throw new OversizeStunPacketException(remoteSocket, packet);
            }
        } else if (((InetSocketAddress) remoteSocket).getAddress() instanceof Inet6Address) {
            if (packet.getBytes().length > ip6MaxLength) {
                throw new OversizeStunPacketException(remoteSocket, packet);
            }

        }
        return channel.write(packet, remoteSocket);
    }

    public Future<StunReply> doTest(InetSocketAddress server) throws IOException, InterruptedException {
        return doTest(server.getAddress(), server.getPort());
    }

    public Future<StunReply> doTest(InetAddress server, int port) throws IOException, InterruptedException {
        // Function synchronizes on the request's BigInteger value in the requestCache

        // Create the request object
        StunPacketImpl request = new StunPacketImpl(MessageClass.REQUEST, MessageMethod.BINDING);
        request.getAttributes().add(new FingerprintAttribute());

        return doTest(server, port, request);
    }

    public Future<StunReply> doTest(InetSocketAddress server, StunPacket request) throws InterruptedException, IOException {
        return doTest(server.getAddress(), server.getPort(), request);
    }

    public Future<StunReply> doTest(final InetAddress server, final int port, final StunPacket request) throws InterruptedException, IOException {
        log.log(Level.FINER, "Sending: {0}", request);


        final StunReplyFuture replyFuture = new StunReplyFuture(new InetSocketAddress(server, port));
        requestCache.admit(request.getId(), replyFuture);

        Thread t = new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    /**
                     * RFC 5389 7.2.1:
                     * Retransmissions continue until a response is received, or
                     * until a total of Rc requests have been sent. Rc SHOULD be
                     * configurable and SHOULD have a default of 7. If, after
                     * the last request, a duration equal to Rm times the RTO
                     * has passed without a response (providing ample time to
                     * get a response if only this final request actually
                     * succeeds), the client SHOULD consider the transaction to
                     * have failed.
                     */
                    for (int i = 0; i < maxRetries; i++) {
                        send(server, port, request);

                        /**
                         * RFC 5389 7.2.1:
                         * A client SHOULD retransmit a STUN request message
                         * starting with an interval of RTO ("Retransmission TimeOut"),
                         * doubling after each retransmission. The RTO is an
                         * estimate of the round-trip time (RTT) and is computed
                         * as described in RFC 2988
                         */
                        int timeout = (int) Math.round(initialTimeout * Math.pow(2, i));
                        if (replyFuture.get(timeout, TimeUnit.MILLISECONDS) != null) {
                            break;
                        }
                    }
                } catch (InterruptedException ex) {
                    Logger.getLogger(DatagramStunSocket.class.getName()).log(Level.SEVERE, null, ex);
                    replyFuture.setReply(new StunReplyImpl(ex));
                } catch (ExecutionException ex) {
                    Logger.getLogger(DatagramStunSocket.class.getName()).log(Level.SEVERE, null, ex);
                    replyFuture.setReply(new StunReplyImpl(ex));
                } catch (TimeoutException ex) {
                    Logger.getLogger(DatagramStunSocket.class.getName()).log(Level.SEVERE, null, ex);
                    replyFuture.setReply(new StunReplyImpl(ex));
                } catch (IOException ex) {
                    Logger.getLogger(DatagramStunSocket.class.getName()).log(Level.SEVERE, null, ex);
                    replyFuture.setReply(new StunReplyImpl(ex));
                } finally {
                    if (!replyFuture.isDone()) {
                        replyFuture.cancel(true);
                    }
                }
            }
        });
        t.setName("STUN Test: " + request.getId());
        t.start();

        return replyFuture;
    }

    @Override
    public void storeAndNotify(StunPacket packet) {
        StunReplyFuture requestFuture = requestCache.get(packet.getId());

        if (requestFuture != null) {
            requestFuture.setReply(new StunReplyImpl(packet));
        } else {
            log.log(Level.INFO, "Got an unexpected reply: {0}", packet);
        }
    }

    protected void notStunPacket(DatagramPacket p) {
        log.log(Level.INFO, "Received a non-STUN packet on a STUN only socket.  Dropping packet from: {0}", p.getSocketAddress());
    }

    public InetAddress getLocalAddress() {
        return ((InetSocketAddress) channel.getLocalAddress()).getAddress();
    }

    @Override
    public String toString() {
        return getClass().getName() + "[socketAddress=" + channel.getLocalAddress() + "]";
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        try {
            //DatagramPacket p = new DatagramPacket(new byte[MAX_PACKET_LENGTH], MAX_PACKET_LENGTH);

            //super.receive(p);
            if (e.getMessage() instanceof StunPacket) {
                stunListener.processPacket((StunPacket) e.getMessage(), e.getRemoteAddress());
            } else if (e.getMessage() instanceof ChannelBuffer) {
                ChannelBuffer buf = (ChannelBuffer) e.getMessage();
                InetSocketAddress remoteAddr = (InetSocketAddress) e.getRemoteAddress();

                byte[] buffer = new byte[buf.readableBytes()];
                buf.getBytes(0, buffer);
                DatagramPacket p = new DatagramPacket(buffer, buffer.length, remoteAddr);
                if (!stunListener.processPacket(p)) {
                    notStunPacket(p);
                }
            } else {
                log.log(Level.INFO, "Received buffer of unknown type: {0}", e.getMessage().getClass().getName());
            }
        } catch (SocketException se) {
            // We expect this when the socket is closed, so ignore it,
            // as no problem is occuring.
        } catch (IOException ex) {
            Logger.getLogger(DatagramStunSocket.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
        for (StunReplyFuture replyFuture : requestCache.values()) {
            if (!replyFuture.isDone() && !replyFuture.isCancelled()) {
                replyFuture.setReply(new StunReplyImpl(e.getCause()));
            }
        }
        ctx.sendUpstream(e);
    }

    @Override
    public void channelBound(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        channel = ctx.getChannel();
        log.log(Level.FINEST, "Got new channel: {0}", channel);
        ctx.sendUpstream(e);
    }

    public void close() {
        channel.close();
    }

    public int getLocalPort() {
        return ((InetSocketAddress) channel.getLocalAddress()).getPort();
    }

    public InetSocketAddress getLocalSocketAddress() {
        return (InetSocketAddress) channel.getLocalAddress();
    }

    class StunReplyFuture implements Future<StunReply> {

        private final InetSocketAddress sockAddr;
        private StunReply stunReply = null;
        private boolean timeout = false;

        public StunReplyFuture(InetSocketAddress sockAddr) {
            this.sockAddr = sockAddr;
        }

        public InetSocketAddress getSockAddr() {
            return sockAddr;
        }

        @Override
        public synchronized boolean cancel(boolean notify) {
            this.timeout = true;
            if (notify) {
                this.notifyAll();
            }
            return timeout;
        }

        @Override
        public boolean isCancelled() {
            return timeout;
        }

        @Override
        public boolean isDone() {
            return stunReply != null || timeout;
        }

        @Override
        public synchronized StunReply get() throws InterruptedException, ExecutionException {
            if (stunReply == null && !timeout) {
                this.wait();
            }

            return stunReply;
        }

        @Override
        public synchronized StunReply get(long l, TimeUnit tu) throws InterruptedException, ExecutionException, TimeoutException {
            if (stunReply == null && !timeout) {
                this.wait(tu.toMillis(l));
            }

            return stunReply;
        }

        protected synchronized void setReply(StunReply reply) {
            this.stunReply = reply;
            notifyAll();
        }
    }

    public int getInitialTimeout() {
        return initialTimeout;
    }

    public void setInitialTimeout(int initialTimeout) {
        this.initialTimeout = initialTimeout;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }
}
