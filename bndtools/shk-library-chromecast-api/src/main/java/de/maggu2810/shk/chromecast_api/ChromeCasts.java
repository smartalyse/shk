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
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceListener;

/**
 * Utility class that discovers ChromeCast devices and holds references to all of them.
 */
public class ChromeCasts extends ArrayList<ChromeCast> implements ServiceListener {
    private static final long serialVersionUID = 1L;

    private final static ChromeCasts INSTANCE = new ChromeCasts();

    private JmDNS mDNS;

    private final List<ChromeCastsListener> listeners = new ArrayList<>();

    private ChromeCasts() {
    }

    private void _startDiscovery(final InetAddress addr) throws IOException {
        if (mDNS == null) {
            if (addr != null) {
                mDNS = JmDNS.create(addr);
            } else {
                mDNS = JmDNS.create();
            }
            mDNS.addServiceListener(ChromeCast.SERVICE_TYPE, this);
        }
    }

    private void _stopDiscovery() throws IOException {
        if (mDNS != null) {
            mDNS.close();
            mDNS = null;
        }
    }

    @Override
    public void serviceAdded(final ServiceEvent event) {
        if (event.getInfo() != null) {
            final ChromeCast device = new ChromeCast(mDNS, event.getInfo().getName());
            add(device);
            for (final ChromeCastsListener listener : listeners) {
                listener.newChromeCastDiscovered(device);
            }
        }
    }

    @Override
    public void serviceRemoved(final ServiceEvent event) {
        if (ChromeCast.SERVICE_TYPE.equals(event.getType())) {
            // We have a ChromeCast device unregistering
            final List<ChromeCast> copy = new ArrayList<>(this);
            ChromeCast deviceRemoved = null;
            // Probably better keep a map to better lookup devices
            for (final ChromeCast device : copy) {
                if (device.getName().equals(event.getInfo().getName())) {
                    deviceRemoved = device;
                    this.remove(device);
                    break;
                }
            }
            if (deviceRemoved != null) {
                for (final ChromeCastsListener listener : listeners) {
                    listener.chromeCastRemoved(deviceRemoved);
                }
            }
        }
    }

    @Override
    public void serviceResolved(final ServiceEvent event) {
    }

    /**
     * Starts ChromeCast device discovery
     */
    public static void startDiscovery() throws IOException {
        INSTANCE._startDiscovery(null);
    }

    /**
     * Starts ChromeCast device discovery
     *
     * @param addr the address / interface that should be used for discovery
     */
    public static void startDiscovery(final InetAddress addr) throws IOException {
        INSTANCE._startDiscovery(addr);
    }

    /**
     * Stops ChromeCast device discovery
     */
    public static void stopDiscovery() throws IOException {
        INSTANCE._stopDiscovery();
    }

    /**
     * Restarts discovery by sequentially calling 'stop' and 'start' methods
     */
    public static void restartDiscovery() throws IOException {
        stopDiscovery();
        startDiscovery();
    }

    /**
     * Restarts discovery by sequentially calling 'stop' and 'start' methods
     *
     * @param addr the address / interface that should be used for discovery
     */
    public static void restartDiscovery(final InetAddress addr) throws IOException {
        stopDiscovery();
        startDiscovery(addr);
    }

    /**
     * @return singleton container holding all discovered devices
     */
    public static ChromeCasts get() {
        return INSTANCE;
    }

    public static void registerListener(final ChromeCastsListener listener) {
        if (listener != null) {
            INSTANCE.listeners.add(listener);
        }
    }

    public static void unregisterListener(final ChromeCastsListener listener) {
        if (listener != null) {
            INSTANCE.listeners.remove(listener);
        }
    }
}
