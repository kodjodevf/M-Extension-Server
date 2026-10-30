package android.webkit;

/**
 * Encapsulates information about errors that occurred during loading of web
 * resources. See {@link WebViewClient#onReceivedError(WebView, WebResourceRequest, WebResourceError)}.
 *
 * Implemented here because the stub's constructor throws, which made it
 * impossible to report an error to an extension's WebViewClient.
 */
public abstract class WebResourceError {
    /**
     * Gets the error code of the error. The code corresponds to one
     * of the {@code ERROR_*} constants in {@link WebViewClient}.
     */
    public abstract int getErrorCode();

    /** Gets the string describing the error. */
    public abstract CharSequence getDescription();

    /** @hide Only for use by WebView implementations. */
    public WebResourceError() {}
}
