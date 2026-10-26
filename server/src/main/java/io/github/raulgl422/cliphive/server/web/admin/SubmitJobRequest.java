package io.github.raulgl422.cliphive.server.web.admin;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /admin/jobs} (docs/DESIGN.md section 4, step 1).
 * A null language/fpsMode is stored as null: unlike the superadmin form, this
 * endpoint doesn't fill them from the superadmin settings.
 */
public record SubmitJobRequest(
        @NotBlank String url,
        String language,
        String fpsMode,
        boolean reviewMode
) {}
