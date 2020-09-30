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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techshroom.jungle.Loaders;
import com.techshroom.jungle.PropOrEnvConfigOption;
import com.techshroom.jungle.PropOrEnvNamespace;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import org.enginehub.crowdin.client.SimpleCrowdin;
import org.enginehub.crowdin.client.request.CreateProjectBuild;
import org.enginehub.crowdin.client.response.FileInfo;
import org.enginehub.crowdin.client.response.ProjectBuild;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkState;

public class Main {

    private static final PropOrEnvNamespace ENV_NAMESPACE = PropOrEnvNamespace.create("crowdin")
        .subspace("distributor");
    private static final PropOrEnvConfigOption<String> CROWDIN_TOKEN =
        ENV_NAMESPACE.create("token", Loaders.forString(), "");
    private static final PropOrEnvConfigOption<Long> CROWDIN_PROJECT_ID =
        ENV_NAMESPACE.subspace("project").create("id", Loaders.forLong(), Long.MIN_VALUE);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final MediaType MEDIA_ZIP = MediaType.get("application/zip");

    public static void main(String[] args) throws IOException {
        var token = CROWDIN_TOKEN.get().trim();
        checkState(
            !token.isEmpty(),
            "Token must be provided (via -D%s or %s)",
            CROWDIN_TOKEN.getSystemPropertyName(), CROWDIN_TOKEN.getEnvironmentVariableName()
        );
        var projectId = CROWDIN_PROJECT_ID.get();
        checkState(
            projectId != Long.MIN_VALUE,
            "Project ID must be provided (via -D%s or %s)",
            CROWDIN_TOKEN.getSystemPropertyName(), CROWDIN_TOKEN.getEnvironmentVariableName()
        );
        checkState(projectId >= 0, "Invalid project ID %s", projectId);
        var httpClient = new OkHttpClient.Builder().build();
        var crowdinClient = new SimpleCrowdin(token, projectId);

        var build = crowdinClient.buildProjectTranslation(new CreateProjectBuild(
            true
        ));
        int last = -1;
        while (build.status().compareTo(ProjectBuild.Status.IN_PROGRESS) <= 0) {
            if (last != build.progress()) {
                last = build.progress();
                System.err.println("Building... " + last + "% done");
            }
            build = crowdinClient.checkProjectBuildStatus(build.id());
        }
        if (build.status() != ProjectBuild.Status.FINISHED) {
            System.err.println("Build failed :( " + build.status());
            System.exit(1);
        }
        System.err.println("Built translations entirely!");
        System.err.println("Downloading translations bundle...");
        Path temporaryFile;
        try (var response = crowdinClient.downloadProjectTranslations(build.id())) {
            var body = Objects.requireNonNull(response.body());
            checkState(
                MEDIA_ZIP.equals(body.contentType()),
                "Invalid Content-type: %s", body.contentType()
            );
            temporaryFile = Files.createTempFile("crowdin-distributor-package", ".zip");
            try (var output = Files.newOutputStream(temporaryFile)) {
                body.byteStream().transferTo(output);
            }
        }
        System.err.println("Downloaded translations bundle.");
        System.err.println("Patching in source files...");
        try (var zipFs = FileSystems.newFileSystem(temporaryFile)) {
            for (FileInfo fileInfo : crowdinClient.listFiles().collect(Collectors.toList())) {
                String path = fileInfo.path();
                System.err.println("Patching in " + path);
                Path zipFsPath = zipFs.getPath(path);
                try (var response = crowdinClient.downloadFile(fileInfo.id())) {
                    var body = Objects.requireNonNull(response.body());
                    try (var output = zipFs.provider().newOutputStream(zipFsPath)) {
                        body.byteStream().transferTo(output);
                    }
                }
                if (zipFsPath.toString().endsWith(".json")) {
                    System.err.println("Validating JSON language file " + path);
                    Map<String, String> data = MAPPER.readValue(
                        Files.readString(zipFsPath),
                        new TypeReference<>() {
                        }
                    );
                    var validator = new TranslationValidator(data);
                    checkState(
                        validateTree(zipFs.getPath("/"), path, validator),
                        "Validation failures occurred"
                    );
                }
            }
        }
        System.err.println("Patching complete!");
        var dest = Path.of("test.zip");
        Files.move(temporaryFile, dest, StandardCopyOption.REPLACE_EXISTING);
        System.err.println("Output at " + dest);
    }

    private static boolean validateTree(Path root, String sourceFilePath,
                                     TranslationValidator validator) throws IOException {
        var sourcePathRelative = root.getFileSystem().getPath(
            sourceFilePath.replaceFirst("^/+", "")
        );
        var success = true;
        try (var files = Files.list(root)
            .filter(Files::isDirectory)
            .map(p -> p.resolve(sourcePathRelative))
            .filter(Files::exists)) {
            for (var iter = files.iterator(); iter.hasNext(); ) {
                var next = iter.next();
                System.err.println("==> Against " + next);
                Map<String, String> data = MAPPER.readValue(
                    Files.readString(next),
                    new TypeReference<>() {
                    }
                );
                var failure = validator.validate(next.toString(), data);
                if (failure != null) {
                    System.err.println(failure);
                    success = false;
                }
            }
        }
        return success;
    }
}
