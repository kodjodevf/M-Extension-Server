package android.webkit;

/** The {@link WebResourceError} WebView providers report. */
public final class SimpleWebResourceError extends WebResourceError {
    private final int errorCode;
    private final CharSequence description;

    public SimpleWebResourceError(int errorCode, CharSequence description) {
        this.errorCode = errorCode;
        this.description = description != null ? description : "";
    }

    @Override
    public int getErrorCode() {
        return errorCode;
    }

    @Override
    public CharSequence getDescription() {
        return description;
    }
}
