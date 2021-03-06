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

import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.annotate.JsonProperty;

/**
 * Current ChromeCast device status
 */
public class Status {
    public final Volume volume;
    public final List<Application> applications;
    public final boolean activeInput;
    public final boolean standBy;

    Status(@JsonProperty("volume") final Volume volume,
            @JsonProperty("applications") final List<Application> applications,
            @JsonProperty("isActiveInput") final boolean activeInput, @JsonProperty("isStandBy") final boolean standBy) {
        this.volume = volume;
        this.applications = applications == null ? Collections.<Application> emptyList() : applications;
        this.activeInput = activeInput;
        this.standBy = standBy;
    }

    @JsonIgnore
    public Application getRunningApp() {
        return applications.isEmpty() ? null : applications.get(0);
    }

    public boolean isAppRunning(final String appId) {
        return getRunningApp() != null && getRunningApp().id.equals(appId);
    }

    @Override
    public String toString() {
        return "Status{" + "volume=" + volume + ", applications=" + applications + ", activeInput=" + activeInput
                + ", standBy=" + standBy + '}';
    }

}
