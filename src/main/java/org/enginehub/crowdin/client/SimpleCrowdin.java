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

package org.enginehub.crowdin.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Streams;
import com.google.common.net.HttpHeaders;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.enginehub.crowdin.client.request.CreateProjectBuild;
import org.enginehub.crowdin.client.response.FileDownload;
import org.enginehub.crowdin.client.response.FileInfo;
import org.enginehub.crowdin.client.response.Page;
import org.enginehub.crowdin.client.response.ProjectBuild;
import org.enginehub.crowdin.jackson.InsideDataModule;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkState;
import static org.enginehub.crowdin.client.HttpMethod.GET;
import static org.enginehub.crowdin.client.HttpMethod.POST;

/**
 * Actual Crowdin SDK is really bad. This is a tiny replacement.
 */
public class SimpleCrowdin {

    private static final String BASE_URL = "https://api.crowdin.com/api/v2";

    private static long computeBackoff(int attempt) {
        return ThreadLocalRandom.current().nextInt(0, 50 * attempt) + 50;
    }

    private final ObjectMapper mapper = new ObjectMapper()
        .registerModules(new InsideDataModule());
    private final long projectId;
    private final OkHttpClient httpClient;
    private final String authorizationHeaderValue;

    public SimpleCrowdin(String token, long projectId) {
        this.projectId = projectId;
        this.authorizationHeaderValue = "Bearer " + token;
        this.httpClient = new OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(false)
            // Default Headers handler
            .addNetworkInterceptor(chain -> {
                var request = chain.request();
                var builder = request.newBuilder();
                // content type is omitted here, the body should carry it already if needed
                if (request.header(HttpHeaders.ACCEPT) == null) {
                    builder.header(HttpHeaders.ACCEPT, "application/json");
                }
                return chain.proceed(builder.build());
            })
            // 429 Too Many Requests handler
            .addInterceptor(chain -> {
                Response response = null;
                for (var counter = 0; counter < 10; counter++) {
                    response = chain.proceed(chain.request());
                    if (response.code() != 429) {
                        return response;
                    }
                    try {
                        Thread.sleep(computeBackoff(counter));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                }
                // after a few attempts, just propagate the error
                // it's unlikely we'll actually be rate-limited anyways
                return response;
            })
            .build();
    }

    private HttpUrl baseRelativeUrl(String url) {
        return HttpUrl.get(BASE_URL + url);
    }

    private HttpUrl projectRelativeUrl(String url) {
        return baseRelativeUrl("/projects/" + projectId + url);
    }

    public Stream<FileInfo> listFiles() {
        return executePaginated(projectRelativeUrl("/files"), new TypeReference<>() {
        });
    }

    public Response downloadFile(long fileId) {
        return executeDownload(projectRelativeUrl("/files/" + fileId + "/download"));
    }

    public ProjectBuild buildProjectTranslation(CreateProjectBuild request) {
        return executeStandard(
            POST, projectRelativeUrl("/translations/builds"),
            request, new TypeReference<>() {
            }
        );
    }

    public ProjectBuild checkProjectBuildStatus(long buildId) {
        return executeStandard(
            GET, projectRelativeUrl("/translations/builds/" + buildId),
            null, new TypeReference<>() {
            }
        );
    }

    public Response downloadProjectTranslations(long buildId) {
        return executeDownload(projectRelativeUrl("/translations/builds/" + buildId + "/download"));
    }

    private <O> Stream<O> executePaginated(HttpUrl url, TypeReference<O> responseType) {
        var typeFactory = mapper.getTypeFactory();
        var pageResponseType = typeFactory.constructParametricType(
            Page.class,
            typeFactory.constructType(responseType)
        );
        return Streams.stream(new AbstractIterator<Stream<O>>() {
            private int offset = 0;

            @Override
            protected Stream<O> computeNext() {
                Page<O> page = executeStandard(
                    GET,
                    url.newBuilder()
                        .addQueryParameter("offset", String.valueOf(offset))
                        .build(),
                    null,
                    pageResponseType
                );
                if (page.data().isEmpty()) {
                    return endOfData();
                }
                offset++;
                return page.data().stream();
            }
        })
            .flatMap(Function.identity());
    }

    private Response executeDownload(HttpUrl url) {
        FileDownload downloadLink = executeStandard(
            GET, url, null, new TypeReference<>() {
            }
        );
        try {
            var response = httpClient.newCall(new Request.Builder()
                .get().url(downloadLink.url())
                .build())
                .execute();
            try {
                handleResponseFail(response);
                Objects.requireNonNull(response.body(), "No response body");
                return response;
            } catch (Throwable t) {
                response.close();
                throw t;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Contract("_, _, _, null -> null; _, _, _, !null -> !null")
    private <I, O> @Nullable O executeStandard(HttpMethod method, HttpUrl url, @Nullable I requestBody,
                                               @Nullable TypeReference<O> responseType) {
        JavaType responseJavaType = null;
        if (responseType != null) {
            responseJavaType = mapper.constructType(responseType.getType());
        }
        return executeStandard(
            method, url, requestBody, responseJavaType
        );
    }

    // Nullability of return is driven by nullability of responseType
    // If there is a mismatch, an error will be raised
    @Contract("_, _, _, null -> null; _, _, _, !null -> !null")
    private <I, O> @Nullable O executeStandard(HttpMethod method, HttpUrl url, @Nullable I requestBody,
                                               @Nullable JavaType responseType) {
        RequestBody mappedRequestBody = null;
        if (requestBody != null) {
            try {
                mappedRequestBody = RequestBody.create(
                    mapper.writeValueAsBytes(requestBody), MediaType.get("application/json")
                );
            } catch (JsonProcessingException e) {
                throw new UncheckedIOException(e);
            }
        }
        var request = new Request.Builder()
            .url(url)
            .method(method.name(), mappedRequestBody)
            .header(HttpHeaders.AUTHORIZATION, authorizationHeaderValue)
            .build();
        try (var response = httpClient.newCall(request).execute()) {
            var body = response.body();
            handleResponseFail(response);

            if (body == null) {
                checkState(responseType == null, "A response body was expected, but none was given");
                return null;
            }

            checkState(responseType != null, "A response body was not expected, but one was given");

            return Objects.requireNonNull(
                mapper.readValue(body.charStream(), responseType),
                // Forbid this for now, it's a weird edge-case
                "Literal null was deserialized from the JSON"
            );
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void handleResponseFail(Response response) throws IOException {
        if (!response.isSuccessful()) {
            String text = "";
            var body = response.body();
            if (body != null) {
                text = body.string();
            }
            var request = response.request();
            throw new IllegalStateException(
                "%s %s failed: %s %s".formatted(request.method(), request.url(), response.code(), text)
            );
        }
    }
}
