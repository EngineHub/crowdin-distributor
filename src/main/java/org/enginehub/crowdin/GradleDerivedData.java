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

package org.enginehub.crowdin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

public record GradleDerivedData(
    String group,
    String version
) {
    public static GradleDerivedData load() throws IOException {
        var props = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("gradle.properties"))) {
            props.load(reader);
        }
        var group = props.getProperty("group");
        Objects.requireNonNull(group, "No group in gradle.properties");
        var version = props.getProperty("version");
        Objects.requireNonNull(group, "No version in gradle.properties");
        return new GradleDerivedData(group, version);
    }
}
