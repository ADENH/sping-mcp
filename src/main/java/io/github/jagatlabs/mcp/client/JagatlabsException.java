package io.github.jagatlabs.mcp.client;

/** A stable public code, with no upstream payload or sensitive exception cause. */
public final class JagatlabsException extends RuntimeException {
    public JagatlabsException(String code) { super(code); }
}
