package org.enginehub.crowdin.client.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.enginehub.crowdin.jackson.InsideData;

import java.time.Instant;

@InsideData
@JsonIgnoreProperties(ignoreUnknown = true)
public record Project(
    Instant lastActivity
) {
}
