/*
 * #%L
 * shk :: Bundles :: Library :: Chromecast API
 * %%
 * Copyright (C) 2014 Vitaly Litvak (vitavaque@gmail.com) and others.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

package de.maggu2810.shk.chromecast_api;

import static de.maggu2810.shk.chromecast_api.Util.fromArray;
import static de.maggu2810.shk.chromecast_api.Util.toArray;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.InvalidProtocolBufferException;

/**
 * Internal class for low-level communication with ChromeCast device.
 * Should never be used directly, use {@link de.maggu2810.shk.chromecast_api.ChromeCast} methods instead
 */
class Channel implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(Channel.class);
    /**
     * Period for sending ping requests (in ms)
     */
    private static final long PING_PERIOD = 30 * 1000;
    /**
     * How much time to wait until request is processed
     */
    private static final long REQUEST_TIMEOUT = 30 * 1000;

    private final static String DEFAULT_RECEIVER_ID = "receiver-0";

    private final EventListenerHolder eventListener;

    /**
     * Single socket instance for transfers
     */
    private Socket socket;
    /**
     * Address of ChromeCast
     */
    private final InetSocketAddress address;
    /**
     * Name of sender used in this channel
     */
    private final String name;
    /**
     * Timer for PING requests
     */
    private Timer pingTimer;
    /**
     * Thread for processing incoming requests
     */
    private ReadThread reader;
    /**
     * Counter for producing request numbers
     */
    private final AtomicLong requestCounter = new AtomicLong(1);
    /**
     * Processors of requests by their identifiers
     */
    private final Map<Long, ResultProcessor<? extends Response>> requests = new ConcurrentHashMap<>();
    /**
     * Single mapper object for marshalling JSON
     */
    private final ObjectMapper jsonMapper = new ObjectMapper();
    /**
     * Destination ids of sessions opened within this channel
     */
    private final Set<String> sessions = new HashSet<>();
    /**
     * Indicates that this channel was closed (explicitly, by remote host or for some connectivity issue)
     */
    private volatile boolean closed = true;
    private final Object closedSync = new Object();

    private class PingThread extends TimerTask {
        @Override
        public void run() {
            try {
                write("urn:x-cast:com.google.cast.tp.heartbeat", StandardMessage.ping(), DEFAULT_RECEIVER_ID);
            } catch (final IOException ioex) {
                LOG.warn("Error while sending 'PING': {}", ioex.getLocalizedMessage());
            }
        }
    }

    private class ReadThread extends Thread {
        volatile boolean stop;

        @Override
        public void run() {
            while (!stop) {
                CastChannel.CastMessage message = null;
                try {
                    message = read();
                    if (message.getPayloadType() == CastChannel.CastMessage.PayloadType.STRING) {
                        LOG.debug(" <-- {}", message.getPayloadUtf8());
                        final String jsonMSG = message.getPayloadUtf8().replaceFirst("\"type\"", "\"responseType\"");
                        if (!jsonMSG.contains("responseType")) {
                            LOG.warn(" <-- {Skipping}", jsonMSG);
                            continue;
                        }

                        final JsonNode parsed = jsonMapper.readTree(jsonMSG);
                        if (parsed.has("requestId")) {
                            final Long requestId = parsed.get("requestId").asLong();
                            final ResultProcessor<? extends Response> rp = requests.remove(requestId);
                            if (rp != null) {
                                rp.put(jsonMSG);
                            } else {
                                if (requestId == 0) {
                                    notifyListenersOfMessageEvent(true, parsed);
                                } else {
                                    notifyListenersOfMessageEvent(false, parsed);
                                }
                            }
                        } else if (parsed.has("responseType") && parsed.get("responseType").asText().equals("PING")) {
                            write("urn:x-cast:com.google.cast.tp.heartbeat", StandardMessage.pong(),
                                    DEFAULT_RECEIVER_ID);
                        }
                    } else {
                        LOG.warn("Received unexpected {} message", message.getPayloadType());
                    }
                } catch (final InvalidProtocolBufferException ipbe) {
                    LOG.debug("Error while processing protobuf: {}", ipbe.getLocalizedMessage());
                } catch (final IOException ioex) {
                    LOG.warn("Error while reading: {}", ioex.getLocalizedMessage());
                    String temp;
                    if (message != null && message.getPayloadUtf8() != null) {
                        temp = message.getPayloadUtf8();
                    } else {
                        temp = " null payload in message ";
                    }
                    LOG.warn(" <-- {}", temp);
                    try {
                        close();
                    } catch (final IOException e) {
                        LOG.warn("Error while closing channel: {}", ioex.getLocalizedMessage());
                    }
                }
            }
        }
    }

    private class ResultProcessor<T extends Response> {
        final Class<T> responseClass;
        T result;

        private ResultProcessor(final Class<T> responseClass) {
            if (responseClass == null) {
                throw new NullPointerException();
            }
            this.responseClass = responseClass;
        }

        public void put(final String jsonMSG) throws IOException {
            synchronized (this) {
                this.result = jsonMapper.readValue(jsonMSG, responseClass);
                this.notify();
            }
        }

        public T get() {
            synchronized (this) {
                if (result != null) {
                    return result;
                }
                try {
                    this.wait(REQUEST_TIMEOUT);
                } catch (final InterruptedException ie) {
                    ie.printStackTrace();
                }
                return result;
            }
        }
    }

    Channel(final String host, final EventListenerHolder eventListener) {
        this(host, 8009, eventListener);
    }

    Channel(final String host, final int port, final EventListenerHolder eventListener) {
        this.address = new InetSocketAddress(host, port);
        this.name = "sender-" + new RandomString(10).nextString();
        this.eventListener = eventListener;
    }

    /**
     * Open the channel.
     *
     * <p>
     * This function must be called before any other usage.
     *
     * @throws IOException
     * @throws GeneralSecurityException
     */
    public void open() throws IOException, GeneralSecurityException {
        if (!closed) {
            throw new IOException("Try to open non channel for a non-closed closed.");
        }
        connect();
    }

    /**
     * Establish connection to the ChromeCast device
     */
    private void connect() throws IOException, GeneralSecurityException {
        synchronized (closedSync) {
            if (socket == null || socket.isClosed()) {
                final SSLContext sc = SSLContext.getInstance("SSL");
                sc.init(null, new TrustManager[] { new X509TrustAllManager() }, new SecureRandom());
                socket = sc.getSocketFactory().createSocket();
                socket.connect(address);
            }
            /**
             * Authenticate
             */
            final CastChannel.DeviceAuthMessage authMessage = CastChannel.DeviceAuthMessage.newBuilder()
                    .setChallenge(CastChannel.AuthChallenge.newBuilder().build()).build();

            final CastChannel.CastMessage msg = CastChannel.CastMessage.newBuilder()
                    .setDestinationId(DEFAULT_RECEIVER_ID).setNamespace("urn:x-cast:com.google.cast.tp.deviceauth")
                    .setPayloadType(CastChannel.CastMessage.PayloadType.BINARY)
                    .setProtocolVersion(CastChannel.CastMessage.ProtocolVersion.CASTV2_1_0).setSourceId(name)
                    .setPayloadBinary(authMessage.toByteString()).build();

            write(msg);
            final CastChannel.CastMessage response = read();
            final CastChannel.DeviceAuthMessage authResponse = CastChannel.DeviceAuthMessage.parseFrom(response
                    .getPayloadBinary());
            if (authResponse.hasError()) {
                throw new ChromeCastException("Authentication failed: "
                        + authResponse.getError().getErrorType().toString());
            }

            /**
             * Send 'PING' message
             */
            final PingThread pingThread = new PingThread();
            pingThread.run();

            /**
             * Send 'CONNECT' message to start session
             */
            write("urn:x-cast:com.google.cast.tp.connection", StandardMessage.connect(), DEFAULT_RECEIVER_ID);

            /**
             * Start ping/pong and reader thread
             */
            pingTimer = new Timer(name + " PING");
            pingTimer.schedule(pingThread, 1000, PING_PERIOD);

            reader = new ReadThread();
            reader.start();

            if (closed) {
                closed = false;
                notifyListenerOfConnectionEvent(true);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends StandardResponse> T sendStandard(final String namespace, final StandardRequest message,
            final String destinationId) throws IOException {
        return send(namespace, message, destinationId, (Class<T>) StandardResponse.class);
    }

    private <T extends Response> T send(final String namespace, final Request message, final String destinationId,
            final Class<T> responseClass) throws IOException {
        /**
         * Try to reconnect
         */
        if (isClosed()) {
            try {
                connect();
            } catch (final GeneralSecurityException gse) {
                throw new ChromeCastException("Unexpected security exception", gse);
            }
        }

        final Long requestId = requestCounter.getAndIncrement();
        message.setRequestId(requestId);
        if (!requestId.equals(message.getRequestId())) {
            throw new IllegalStateException("Request Id getter/setter contract violation");
        }

        if (responseClass == null) {
            write(namespace, message, destinationId);
            return null;
        }

        final ResultProcessor<T> rp = new ResultProcessor<>(responseClass);
        requests.put(requestId, rp);

        write(namespace, message, destinationId);
        try {
            final T response = rp.get();
            if (response instanceof StandardResponse.Invalid) {
                final StandardResponse.Invalid invalid = (StandardResponse.Invalid) response;
                throw new ChromeCastException("Invalid request: " + invalid.reason);
            } else if (response instanceof StandardResponse.LoadFailed) {
                throw new ChromeCastException("Unable to load media");
            } else if (response instanceof StandardResponse.LaunchError) {
                final StandardResponse.LaunchError launchError = (StandardResponse.LaunchError) response;
                throw new ChromeCastException("Application launch error: " + launchError.reason);
            }
            return response;
        } finally {
            requests.remove(requestId);
        }
    }

    private void write(final String namespace, final Message message, final String destinationId) throws IOException {
        write(namespace, jsonMapper.writeValueAsString(message), destinationId);
    }

    private void write(final String namespace, final String message, final String destinationId) throws IOException {
        LOG.debug(" --> {}", message);
        final CastChannel.CastMessage msg = CastChannel.CastMessage.newBuilder()
                .setProtocolVersion(CastChannel.CastMessage.ProtocolVersion.CASTV2_1_0).setSourceId(name)
                .setDestinationId(destinationId).setNamespace(namespace)
                .setPayloadType(CastChannel.CastMessage.PayloadType.STRING).setPayloadUtf8(message).build();
        write(msg);
    }

    private void write(final CastChannel.CastMessage message) throws IOException {
        socket.getOutputStream().write(toArray(message.getSerializedSize()));
        message.writeTo(socket.getOutputStream());
    }

    private CastChannel.CastMessage read() throws IOException {
        final InputStream is = socket.getInputStream();
        byte[] buf = new byte[4];

        int read = 0;
        while (read < buf.length) {
            final int nextByte = is.read();
            if (nextByte == -1) {
                throw new ChromeCastException("Remote socket closed");
            }
            buf[read++] = (byte) nextByte;
        }

        final int size = fromArray(buf);
        buf = new byte[size];
        read = 0;
        while (read < size) {
            final int nowRead = is.read(buf, read, buf.length - read);
            if (nowRead == -1) {
                throw new ChromeCastException("Remote socket closed");
            }
            read += nowRead;
        }

        return CastChannel.CastMessage.parseFrom(buf);
    }

    private void notifyListenerOfConnectionEvent(final boolean connected) {
        if (this.eventListener != null) {
            this.eventListener.deliverConnectionEvent(connected);
        }
    }

    private void notifyListenersOfMessageEvent(final boolean spontaneous, final JsonNode json) throws IOException {
        if (this.eventListener != null) {
            this.eventListener.deliverMessageEvent(spontaneous, json);
        }
    }

    public Status getStatus() throws IOException {
        final StandardResponse.Status status = sendStandard("urn:x-cast:com.google.cast.receiver",
                StandardRequest.status(), "receiver-0");
        return status == null ? null : status.status;
    }

    public boolean isAppAvailable(final String appId) throws IOException {
        final StandardResponse.AppAvailability availability = sendStandard("urn:x-cast:com.google.cast.receiver",
                StandardRequest.appAvailability(appId), "receiver-0");
        return availability != null && "APP_AVAILABLE".equals(availability.availability.get(appId));
    }

    public Status launch(final String appId) throws IOException {
        final StandardResponse.Status status = sendStandard("urn:x-cast:com.google.cast.receiver",
                StandardRequest.launch(appId), DEFAULT_RECEIVER_ID);
        return status == null ? null : status.status;
    }

    public Status stop(final String sessionId) throws IOException {
        final StandardResponse.Status status = sendStandard("urn:x-cast:com.google.cast.receiver",
                StandardRequest.stop(sessionId), DEFAULT_RECEIVER_ID);
        return status == null ? null : status.status;
    }

    private void startSession(final String destinationId) throws IOException {
        if (!sessions.contains(destinationId)) {
            write("urn:x-cast:com.google.cast.tp.connection", StandardMessage.connect(), destinationId);
            sessions.add(destinationId);
        }
    }

    public MediaStatus load(final String destinationId, final String sessionId, final Media media,
            final boolean autoplay, final double currentTime, final Map<String, String> customData) throws IOException {
        startSession(destinationId);
        final StandardResponse.MediaStatus status = sendStandard("urn:x-cast:com.google.cast.media",
                StandardRequest.load(sessionId, media, autoplay, currentTime, customData), destinationId);
        return status == null || status.statuses.length == 0 ? null : status.statuses[0];
    }

    public MediaStatus play(final String destinationId, final String sessionId, final long mediaSessionId)
            throws IOException {
        startSession(destinationId);
        final StandardResponse.MediaStatus status = sendStandard("urn:x-cast:com.google.cast.media",
                StandardRequest.play(sessionId, mediaSessionId), destinationId);
        return status == null || status.statuses.length == 0 ? null : status.statuses[0];
    }

    public MediaStatus pause(final String destinationId, final String sessionId, final long mediaSessionId)
            throws IOException {
        startSession(destinationId);
        final StandardResponse.MediaStatus status = sendStandard("urn:x-cast:com.google.cast.media",
                StandardRequest.pause(sessionId, mediaSessionId), destinationId);
        return status == null || status.statuses.length == 0 ? null : status.statuses[0];
    }

    public MediaStatus seek(final String destinationId, final String sessionId, final long mediaSessionId,
            final double currentTime) throws IOException {
        startSession(destinationId);
        final StandardResponse.MediaStatus status = sendStandard("urn:x-cast:com.google.cast.media",
                StandardRequest.seek(sessionId, mediaSessionId, currentTime), destinationId);
        return status == null || status.statuses.length == 0 ? null : status.statuses[0];
    }

    public Status setVolume(final Volume volume) throws IOException {
        final StandardResponse.Status status = sendStandard("urn:x-cast:com.google.cast.receiver",
                StandardRequest.setVolume(volume), DEFAULT_RECEIVER_ID);
        return status == null ? null : status.status;
    }

    public MediaStatus getMediaStatus(final String destinationId) throws IOException {
        startSession(destinationId);
        final StandardResponse.MediaStatus status = sendStandard("urn:x-cast:com.google.cast.media",
                StandardRequest.status(), destinationId);
        return status == null || status.statuses.length == 0 ? null : status.statuses[0];
    }

    public <T extends Response> T sendGenericRequest(final String destinationId, final String namespace,
            final Request request, final Class<T> responseClass) throws IOException {
        startSession(destinationId);
        return send(namespace, request, destinationId, responseClass);
    }

    @Override
    public void close() throws IOException {
        synchronized (closedSync) {
            if (!closed) {
                closed = true;
                notifyListenerOfConnectionEvent(false);
            }
            if (pingTimer != null) {
                pingTimer.cancel();
            }
            if (reader != null) {
                reader.stop = true;
            }
            if (socket != null) {
                socket.close();
            }
        }
    }

    public boolean isClosed() {
        return closed;
    }
}
