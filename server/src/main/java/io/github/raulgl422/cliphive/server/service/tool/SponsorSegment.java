package io.github.raulgl422.cliphive.server.service.tool;

/**
 * One SponsorBlock segment fetched as data (docs/DESIGN.md §4, step 3).
 * Segments are stored alongside the job and used in step 6 (Claude analysis)
 * to inform which parts to cut — they are never applied at download time.
 *
 * @param start    segment start time in seconds
 * @param end      segment end time in seconds
 * @param category SponsorBlock category (e.g. "sponsor", "intro", "outro", "selfpromo")
 */
public record SponsorSegment(double start, double end, String category) {}
