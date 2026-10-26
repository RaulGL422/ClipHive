package io.github.raulgl422.cliphive.server.publish;

/** Title and description of a publication (the same on every platform — DESIGN.md §11.6). */
public record PostMetadata(String title, String description) {
}
