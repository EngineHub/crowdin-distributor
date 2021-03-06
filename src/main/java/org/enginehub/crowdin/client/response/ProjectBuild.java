/*
 * This file is part of crowdin-distributor, licensed under GPLv3.
 *
 * Copyright (c) EngineHub <https://enginehub.org/>
 * Copyright (c) contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.enginehub.crowdin.client.response;

import com.fasterxml.jackson.annotation.JsonValue;
import org.enginehub.crowdin.jackson.InsideData;

@InsideData
public record ProjectBuild(
    long id,
    Status status,
    int progress
) {
    public enum Status {
        CREATED("created"),
        IN_PROGRESS("inProgress"),
        CANCELED("canceled"),
        FAILED("failed"),
        FINISHED("finished");

        private final String wireText;

        Status(String wireText) {
            this.wireText = wireText;
        }

        @JsonValue
        public String getWireText() {
            return wireText;
        }
    }
}
