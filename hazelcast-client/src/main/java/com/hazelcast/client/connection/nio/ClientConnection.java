/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.client.connection.nio;

import com.hazelcast.client.ClientTypes;
import com.hazelcast.client.spi.EventHandler;
import com.hazelcast.client.spi.impl.ClientCallFuture;
import com.hazelcast.core.HazelcastException;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.nio.*;
import com.hazelcast.nio.serialization.*;
import com.hazelcast.spi.exception.TargetDisconnectedException;
import com.hazelcast.util.ExceptionUtil;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.hazelcast.util.StringUtil.stringToBytes;

/**
 * @author ali 16/12/13
 */
public class ClientConnection implements Connection, Closeable {

    private volatile boolean live = true;

    private final ILogger logger = Logger.getLogger(ClientConnection.class);

    private final ClientWriteHandler writeHandler;

    private final ClientReadHandler readHandler;

    private final ClientConnectionManagerImpl connectionManager;

    private final int connectionId;

    private final SocketChannelWrapper socketChannelWrapper;

    private volatile Address remoteEndpoint;

    private final ConcurrentMap<Integer, ClientCallFuture> callIdMap = new ConcurrentHashMap<Integer, ClientCallFuture>();
    private final ConcurrentMap<Integer, ClientCallFuture> eventHandlerMap = new ConcurrentHashMap<Integer, ClientCallFuture>();
    private final ByteBuffer readBuffer;
    private final SerializationService serializationService;
    private boolean readFromSocket = true;

    public ClientConnection(ClientConnectionManagerImpl connectionManager, IOSelector in, IOSelector out, int connectionId, SocketChannelWrapper socketChannelWrapper) throws IOException {
        final Socket socket = socketChannelWrapper.socket();
        this.connectionManager = connectionManager;
        this.serializationService = connectionManager.getSerializationService();
        this.socketChannelWrapper = socketChannelWrapper;
        this.connectionId = connectionId;
        this.readHandler = new ClientReadHandler(this, in, socket.getReceiveBufferSize());
        this.writeHandler = new ClientWriteHandler(this, out, socket.getSendBufferSize());
        this.readBuffer = ByteBuffer.allocate(socket.getReceiveBufferSize());
    }

    public void registerCallId(ClientCallFuture future){
        final int callId = connectionManager.newCallId();
        future.getRequest().setCallId(callId);
        callIdMap.put(callId, future);
        if (future.getHandler() != null) {
            eventHandlerMap.put(callId, future);
        }
    }

    public ClientCallFuture deRegisterCallId(int callId){
        return callIdMap.remove(callId);
    }

    public ClientCallFuture deRegisterEventHandler(int callId){
        return eventHandlerMap.remove(callId);
    }

    public EventHandler getEventHandler(int callId) {
        final ClientCallFuture future = eventHandlerMap.get(callId);
        if (future == null) {
            return null;
        }
        return future.getHandler();
    }

    public boolean write(SocketWritable packet) {
        if (!live) {
            if (logger.isFinestEnabled()) {
                logger.finest("Connection is closed, won't write packet -> " + packet);
            }
            return false;
        }
        writeHandler.enqueueSocketWritable(packet);
        return true;
    }

    public void init() throws IOException {
        final ByteBuffer buffer = ByteBuffer.allocate(6);
        buffer.put(stringToBytes(Protocols.CLIENT_BINARY));
        buffer.put(stringToBytes(ClientTypes.JAVA));
        buffer.flip();
        socketChannelWrapper.write(buffer);
    }

    public void write(Data data) throws IOException {
        final int totalSize = data.totalSize();
        final int bufferSize = ClientConnectionManagerImpl.BUFFER_SIZE;
        final ByteBuffer buffer = ByteBuffer.allocate(totalSize > bufferSize ? bufferSize : totalSize);
        final DataAdapter packet = new DataAdapter(data);
        boolean complete = false;
        while (!complete) {
            complete = packet.writeTo(buffer);
            buffer.flip();
            try {
                socketChannelWrapper.write(buffer);
            } catch (Exception e) {
                throw ExceptionUtil.rethrow(e);
            }
            buffer.clear();
        }
    }

    public Data read() throws IOException {
        ClientPacket packet = new ClientPacket(serializationService.getSerializationContext());
        while (true){
            if (readFromSocket) {
                int readBytes = socketChannelWrapper.read(readBuffer);
                if (readBytes == -1) {
                    throw new EOFException("Remote socket closed!");
                }
                readBuffer.flip();
            }
            boolean complete = packet.readFrom(readBuffer);
            if (complete) {
                if (readBuffer.hasRemaining()) {
                    readFromSocket = false;
                } else {
                    readBuffer.compact();
                }
                return packet.getData();
            }
            readFromSocket = true;
            readBuffer.clear();
        }
    }

    public Address getEndPoint() {
        return remoteEndpoint;
    }

    public boolean live() {
        return live;
    }

    public long lastReadTime() {
        return readHandler.getLastHandle();
    }

    public long lastWriteTime() {
        return writeHandler.getLastHandle();
    }

    public void close() {
        close(null);
    }

    public ConnectionType getType() {
        return ConnectionType.JAVA_CLIENT;
    }

    public boolean isClient() {
        return true;
    }

    public InetAddress getInetAddress() {
        return socketChannelWrapper.socket().getInetAddress();
    }

    public InetSocketAddress getRemoteSocketAddress() {
        return (InetSocketAddress) socketChannelWrapper.socket().getRemoteSocketAddress();
    }

    public int getPort() {
        return socketChannelWrapper.socket().getPort();
    }

    public SocketChannelWrapper getSocketChannelWrapper() {
        return socketChannelWrapper;
    }

    public ClientConnectionManagerImpl getConnectionManager() {
        return connectionManager;
    }

    public ClientReadHandler getReadHandler() {
        return readHandler;
    }

    public void setRemoteEndpoint(Address remoteEndpoint) {
        this.remoteEndpoint = remoteEndpoint;
    }

    public Address getRemoteEndpoint() {
        return remoteEndpoint;
    }

    public InetSocketAddress getLocalSocketAddress() {
        return (InetSocketAddress) socketChannelWrapper.socket().getLocalSocketAddress();
    }

    private void innerClose() throws IOException {
        if (!live) {
            return;
        }
        live = false;
        if (socketChannelWrapper != null && socketChannelWrapper.isOpen()) {
            socketChannelWrapper.close();
        }
        readHandler.shutdown();
        writeHandler.shutdown();

        final HazelcastException response;
        if (connectionManager.isLive()) {
            response = new TargetDisconnectedException(remoteEndpoint);
        } else {
            response = new HazelcastException("Client is shutting down!!!");
        }

        for (Map.Entry<Integer, ClientCallFuture> entry : callIdMap.entrySet()) {
            entry.getValue().notify(response);
        }
        callIdMap.clear();
        eventHandlerMap.clear();
    }

    public void close(Throwable t) {
        if (!live) {
            return;
        }
        try {
            innerClose();
        } catch (Exception e) {
            logger.warning(e);
        }
        String message = "Connection [" + socketChannelWrapper.socket().getRemoteSocketAddress() + "] lost. Reason: ";
        if (t != null) {
            message += t.getClass().getName() + "[" + t.getMessage() + "]";
        } else {
            message += "Socket explicitly closed";
        }

        logger.warning(message);
        connectionManager.destroyConnection(this);
    }

    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ClientConnection)) return false;

        ClientConnection that = (ClientConnection) o;

        if (connectionId != that.connectionId) return false;

        return true;
    }

    public int hashCode() {
        return connectionId;
    }

    public String toString() {
        final StringBuilder sb = new StringBuilder("ClientConnection{");
        sb.append("live=").append(live);
        sb.append(", writeHandler=").append(writeHandler);
        sb.append(", readHandler=").append(readHandler);
        sb.append(", connectionId=").append(connectionId);
        sb.append(", socketChannel=").append(socketChannelWrapper);
        sb.append(", remoteEndpoint=").append(remoteEndpoint);
        sb.append('}');
        return sb.toString();
    }

}
