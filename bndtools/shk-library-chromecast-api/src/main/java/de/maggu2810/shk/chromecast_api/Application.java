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

import java.util.Collections;
import java.util.List;

import org.codehaus.jackson.annotate.JsonProperty;

/**
 * Application descriptor
 */
public class Application {
    public final String id;
    public final String name;
    public final String sessionId;
    public final String statusText;
    public final boolean isIdleScreen;
    public final String transportId;
    public final List<Namespace> namespaces;

    public Application(@JsonProperty("appId") final String id, @JsonProperty("displayName") final String name,
            @JsonProperty("sessionId") final String sessionId, @JsonProperty("statusText") final String statusText,
            @JsonProperty("isIdleScreen") final boolean isIdleScreen,
            @JsonProperty("transportId") final String transportId,
            @JsonProperty("namespaces") final List<Namespace> namespaces) {
        this.id = id;
        this.name = name;
        this.sessionId = sessionId;
        this.statusText = statusText;
        this.isIdleScreen = isIdleScreen;
        this.transportId = transportId;
        this.namespaces = namespaces == null ? Collections.<Namespace> emptyList() : namespaces;
    }

    @Override
    public String toString() {
        return "Application [id=" + id + ", name=" + name + ", sessionId=" + sessionId + ", statusText=" + statusText
                + ", isIdleScreen=" + isIdleScreen + ", transportId=" + transportId + ", namespaces=" + namespaces
                + "]";
    }

}
