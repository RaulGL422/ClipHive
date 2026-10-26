package io.github.raulgl422.cliphive.server.web.admin;

import io.github.raulgl422.cliphive.protocol.state.VideoJobState;

import java.util.UUID;

/** Response DTO for a newly submitted or queried video job. */
public record VideoJobResponse(UUID id, VideoJobState state) {}
