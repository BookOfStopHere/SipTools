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
package net.mc_cubed.icedjava.ice;

import net.mc_cubed.icedjava.ice.event.IceBytesAvailableEvent;

/**
 *
 * @author charles
 */
class BytesAvailableEventImpl implements IceBytesAvailableEvent {

    final IceSocketChannel socketChannel;
    final IcePeer peer;

    BytesAvailableEventImpl(IceSocketChannel socketChannel,IcePeer peer) {
        this.socketChannel = socketChannel;
        this.peer = peer;
    }

    @Override
    public IceSocketChannel getSocketChannel() {
        return socketChannel;
    }

    @Override
    public IcePeer getIcePeer() {
        return peer;
    }
}