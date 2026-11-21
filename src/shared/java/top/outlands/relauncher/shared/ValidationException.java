package top.outlands.relauncher.shared;

import java.io.IOException;

/** A validation failure with a stable localization key and an English fallback message. */
public final class ValidationException extends IOException {
    private static final long serialVersionUID = 1L;

    private final String key;
    private final Object[] arguments;

    public ValidationException(String key, String message, Object... arguments) {
        super(message);
        this.key = key;
        this.arguments = arguments.clone();
    }

    public ValidationException(String key, String message, Throwable cause, Object... arguments) {
        super(message, cause);
        this.key = key;
        this.arguments = arguments.clone();
    }

    public String key() {
        return key;
    }

    /** Returns a copy of the positional arguments, in template placeholder order. */
    public Object[] arguments() {
        return arguments.clone();
    }
}
