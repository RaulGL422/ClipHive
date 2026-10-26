package io.github.raulgl422.cliphive.server.publish;

/** Where a platform is with a video it received. {@code detail} explains a failure. */
public record ProcessingResult(State state, String detail) {

    public enum State { PROCESSING, DONE, FAILED }

    public static ProcessingResult processing() { return new ProcessingResult(State.PROCESSING, null); }
    public static ProcessingResult done()       { return new ProcessingResult(State.DONE, null); }
    public static ProcessingResult failed(String detail) { return new ProcessingResult(State.FAILED, detail); }
}
