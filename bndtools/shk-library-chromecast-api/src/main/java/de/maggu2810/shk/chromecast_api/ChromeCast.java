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

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

/**
 * ChromeCast device - main object used for interaction with ChromeCast dongle.
 */
public class ChromeCast {
    public final static String SERVICE_TYPE = "_googlecast._tcp.local.";

    private final EventListenerHolder eventListenerHolder = new EventListenerHolder();

    private String name;
    private final String address;
    private final int port;
    private String appsURL;
    private String application;
    private final Channel channel;

    public ChromeCast(final JmDNS mDNS, final String name) {
        this.name = name;
        final ServiceInfo serviceInfo = mDNS.getServiceInfo(SERVICE_TYPE, name);
        this.address = serviceInfo.getInet4Addresses()[0].getHostAddress();
        this.port = serviceInfo.getPort();
        this.appsURL = serviceInfo.getURLs().length == 0 ? null : serviceInfo.getURLs()[0];
        this.application = serviceInfo.getApplication();
        this.channel = new Channel(address, port, this.eventListenerHolder);
    }

    public ChromeCast(final String address) {
        this(address, 8009);
    }

    public ChromeCast(final String address, final int port) {
        this.address = address;
        this.port = port;
        this.channel = new Channel(address, port, this.eventListenerHolder);
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }

    public String getAppsURL() {
        return appsURL;
    }

    public void setAppsURL(final String appsURL) {
        this.appsURL = appsURL;
    }

    public String getApplication() {
        return application;
    }

    public void setApplication(final String application) {
        this.application = application;
    }

    public synchronized void connect() throws IOException, GeneralSecurityException {
        if (channel.isClosed()) {
            channel.open();
        }
    }

    public synchronized void disconnect() throws IOException {
        channel.close();
    }

    public boolean isConnected() {
        return !channel.isClosed();
    }

    /**
     * @return current chromecast status - volume, running applications, etc.
     * @throws IOException
     */
    public Status getStatus() throws IOException {
        return channel.getStatus();
    }

    /**
     * @return descriptor of currently running application
     * @throws IOException
     */
    public Application getRunningApp() throws IOException {
        final Status status = getStatus();
        return status.getRunningApp();
    }

    /**
     * @param appId application identifier
     * @return true if application is available to this chromecast device, false otherwise
     * @throws IOException
     */
    public boolean isAppAvailable(final String appId) throws IOException {
        return channel.isAppAvailable(appId);
    }

    /**
     * @param appId application identifier
     * @return true if application with specified identifier is running now
     * @throws IOException
     */
    public boolean isAppRunning(final String appId) throws IOException {
        final Status status = getStatus();
        return status.getRunningApp() != null && appId.equals(status.getRunningApp().id);
    }

    /**
     * @param appId application identifier
     * @return application descriptor if app successfully launched, null otherwise
     * @throws IOException
     */
    public Application launchApp(final String appId) throws IOException {
        final Status status = channel.launch(appId);
        return status == null ? null : status.getRunningApp();
    }

    /**
     * Stops currently running application
     *
     * @throws IOException
     */
    public void stopApp() throws IOException {
        channel.stop(getRunningApp().sessionId);
    }

    /**
     * @param level volume level from 0 to 1 to set
     */
    public void setVolume(final float level) throws IOException {
        channel.setVolume(new Volume(level, false, Volume.default_increment, Volume.default_increment.doubleValue(),
                Volume.default_controlType));
    }

    /**
     * @param muted is to mute or not
     */
    public void setMuted(final boolean muted) throws IOException {
        channel.setVolume(new Volume(null, muted, Volume.default_increment, Volume.default_increment.doubleValue(),
                Volume.default_controlType));
    }

    /**
     * @return current media status, state, time, playback rate, etc.
     * @throws IOException
     */
    public MediaStatus getMediaStatus() throws IOException {
        return channel.getMediaStatus(getRunningApp().transportId);
    }

    /**
     * Resume paused media playback
     *
     * @throws IOException
     */
    public void play() throws IOException {
        final Status status = getStatus();
        final MediaStatus mediaStatus = channel.getMediaStatus(status.getRunningApp().transportId);
        if (mediaStatus == null) {
            throw new ChromeCastException("ChromeCast has invalid state to resume media playback");
        }
        channel.play(status.getRunningApp().transportId, status.getRunningApp().sessionId, mediaStatus.mediaSessionId);
    }

    /**
     * Pause current playback
     *
     * @throws IOException
     */
    public void pause() throws IOException {
        final Status status = getStatus();
        final MediaStatus mediaStatus = channel.getMediaStatus(status.getRunningApp().transportId);
        if (mediaStatus == null) {
            throw new ChromeCastException("ChromeCast has invalid state to pause media playback");
        }
        channel.pause(status.getRunningApp().transportId, status.getRunningApp().sessionId, mediaStatus.mediaSessionId);
    }

    /**
     * Moves current playback time point to specified value
     *
     * @param time time point between zero and media duration
     * @throws IOException
     */
    public void seek(final double time) throws IOException {
        final Status status = getStatus();
        final MediaStatus mediaStatus = channel.getMediaStatus(status.getRunningApp().transportId);
        if (mediaStatus == null) {
            throw new ChromeCastException("ChromeCast has invalid state to seek media playback");
        }
        channel.seek(status.getRunningApp().transportId, status.getRunningApp().sessionId, mediaStatus.mediaSessionId,
                time);
    }

    /**
     * Loads and starts playing media in specified URL
     *
     * @param url media url
     * @return The new media status that resulted from loading the media.
     * @throws IOException
     */
    public MediaStatus load(final String url) throws IOException {
        return load(url.substring(url.lastIndexOf('/') + 1, url.lastIndexOf('.')), null, url, null);
    }

    /**
     * Loads and starts playing specified media
     *
     * @param title name to be displayed
     * @param thumb url of video thumbnail to be displayed, relative to media url
     * @param url media url
     * @param contentType MIME content type
     * @return The new media status that resulted from loading the media.
     * @throws IOException
     */
    public MediaStatus load(final String title, final String thumb, final String url, final String contentType)
            throws IOException {
        final Status status = getStatus();
        final Map<String, String> customData = new HashMap<>(2);
        customData.put("title:", title);
        customData.put("thumb", thumb);
        return channel.load(status.getRunningApp().transportId, status.getRunningApp().sessionId, new Media(url,
                contentType), true, 0d, customData);
    }

    /**
     * Loads and starts playing specified media
     *
     * @param media The media to load and play. See https://developers.google.com/cast/docs/reference/messages#Load for
     *            further details.
     * @return The new media status that resulted from loading the media.
     * @throws IOException
     */
    public MediaStatus load(final Media media) throws IOException {
        final Status status = getStatus();
        return channel
                .load(status.getRunningApp().transportId, status.getRunningApp().sessionId, media, true, 0d, null);
    }

    /**
     * <p>
     * Sends some generic request to the currently running application.
     * </p>
     *
     * <p>
     * If no application is running at the moment then exception is thrown.
     * </p>
     *
     * @param namespace request namespace
     * @param request request object
     * @param responseClass class of the response for proper deserialization
     * @param <T> type of response
     * @return deserialized response
     * @throws IOException
     */
    public <T extends Response> T send(final String namespace, final Request request, final Class<T> responseClass)
            throws IOException {
        final Status status = getStatus();
        final Application runningApp = status.getRunningApp();
        if (runningApp == null) {
            throw new ChromeCastException("No application is running in ChromeCast");
        }
        return channel.sendGenericRequest(runningApp.transportId, namespace, request, responseClass);
    }

    /**
     * <p>
     * Sends some generic request to the currently running application. No response is expected as a result of this
     * call.
     * </p>
     *
     * <p>
     * If no application is running at the moment then exception is thrown.
     * </p>
     *
     * @param namespace request namespace
     * @param request request object
     * @throws IOException
     */
    public void send(final String namespace, final Request request) throws IOException {
        send(namespace, request, null);
    }

    public void registerConnectionListener(final ChromeCastConnectionEventListener listener) {
        this.eventListenerHolder.registerConnectionListener(listener);
    }

    public void unregisterConnectionListener(final ChromeCastConnectionEventListener listener) {
        this.eventListenerHolder.unregisterConnectionListener(listener);
    }

    public void registerMessageListener(final ChromeCastMessageEventListener listener) {
        this.eventListenerHolder.registerMessageListener(listener);
    }

    public void unregisterMessageListener(final ChromeCastMessageEventListener listener) {
        this.eventListenerHolder.unregisterMessageListener(listener);
    }

}
