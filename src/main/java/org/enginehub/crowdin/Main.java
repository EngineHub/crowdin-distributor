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
import org.enginehub.crowdin.client.SimpleCrowdin;
import org.enginehub.crowdin.client.request.CreateProjectBuild;
import org.enginehub.crowdin.client.response.FileInfo;
import org.enginehub.crowdin.client.response.ProjectBuild;
import org.jfrog.artifactory.client.ArtifactoryClientBuilder;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkState;

public class Main {

    private static final PropOrEnvNamespace ENV_NAMESPACE = PropOrEnvNamespace.create("crowdin")
        .subspace("distributor");
    private static final PropOrEnvConfigOption<String> CROWDIN_TOKEN =
        ENV_NAMESPACE.create("token", Loaders.forString(), "");
    private static final PropOrEnvConfigOption<Long> CROWDIN_PROJECT_ID =
        ENV_NAMESPACE.subspace("project").create("id", Loaders.forLong(), Long.MIN_VALUE);
    private static final PropOrEnvConfigOption<String> MODULE =
        ENV_NAMESPACE.create("module", Loaders.forString(), "");
    private static final PropOrEnvNamespace ARTIFACTORY_NAMESPACE =
        ENV_NAMESPACE.subspace("artifactory");
    private static final PropOrEnvConfigOption<String> ARTIFACTORY_URL =
        ARTIFACTORY_NAMESPACE.create("url", Loaders.forString(), "");
    private static final PropOrEnvConfigOption<String> ARTIFACTORY_REPO =
        ARTIFACTORY_NAMESPACE.create("repo", Loaders.forString(), "");
    private static final PropOrEnvConfigOption<String> ARTIFACTORY_USER =
        ARTIFACTORY_NAMESPACE.create("user", Loaders.forString(), "");
    private static final PropOrEnvConfigOption<String> ARTIFACTORY_PASSWORD =
        ARTIFACTORY_NAMESPACE.create("password", Loaders.forString(), "");
    private static final PropOrEnvConfigOption<Long> BUILD_NUMBER =
        ENV_NAMESPACE.subspace("build").create("number", Loaders.forLong(), Long.MIN_VALUE);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final MediaType MEDIA_ZIP = MediaType.get("application/zip");

    private static <T> T require(String commonName, PropOrEnvConfigOption<T> configOption,
                                 Predicate<T> isValid) {
        var value = configOption.get();
        checkState(
            isValid.test(value),
            "%s must be provided (via -D%s or %s)",
            commonName,
            configOption.getSystemPropertyName(), configOption.getEnvironmentVariableName()
        );
        return value;
    }

    public static void main(String[] args) throws IOException {
        var token = require("Token", CROWDIN_TOKEN, t -> !t.isBlank());
        var projectId = require("Project ID", CROWDIN_PROJECT_ID, id -> id != Long.MIN_VALUE);
        checkState(projectId >= 0, "Invalid project ID %s", projectId);

        var crowdinClient = new SimpleCrowdin(token, projectId);

        ProjectBuild build = buildProjectTranslations(crowdinClient);
        Path temporaryFile = downloadTranslationsBundle(crowdinClient, build);
        patchInSourceFiles(crowdinClient, temporaryFile);
        uploadToArtifactory(temporaryFile);
    }

    private static ProjectBuild buildProjectTranslations(SimpleCrowdin crowdinClient) {
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
        return build;
    }

    private static Path downloadTranslationsBundle(SimpleCrowdin crowdinClient, ProjectBuild build) throws IOException {
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
        return temporaryFile;
    }

    private static void patchInSourceFiles(SimpleCrowdin crowdinClient, Path temporaryFile) throws IOException {
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

    private static void uploadToArtifactory(Path file) throws IOException {
        var module = require("Module", MODULE, m -> !m.isBlank());
        var artifactoryUrl = require("Artifactory URL", ARTIFACTORY_URL, u -> !u.isBlank());
        var artifactoryRepo = require("Artifactory Repo", ARTIFACTORY_REPO, r -> !r.isBlank());
        var artifactoryUser = require("Artifactory User", ARTIFACTORY_USER, u -> !u.isBlank());
        var artifactoryPassword = require("Artifactory Password", ARTIFACTORY_PASSWORD, p -> !p.isBlank());
        var buildNumber = require("Build Number", BUILD_NUMBER, id -> id != Long.MIN_VALUE);
        checkState(buildNumber >= 0, "Invalid project ID %s", buildNumber);
        var gradleData = GradleDerivedData.load();

        var client = ArtifactoryClientBuilder.create()
            .setUrl(artifactoryUrl)
            .setUsername(artifactoryUser)
            .setPassword(artifactoryPassword)
            .build();

        var fixedGroup = gradleData.group().replace('.', '/');
        var version = gradleData.version();

        var path = String.join(
            "/",
            fixedGroup, module, version,
            module + "-" + version + "+" + buildNumber + ".zip"
        );

        client.repository(artifactoryRepo)
            .upload(path, file.toFile())
            .bySha1Checksum()
            .doUpload();
    }
}
