package android.webkit;

/**
 * Public class representing a JavaScript console message from WebCore. This
 * could be an issued warning, an exception, or any other message that WebCore
 * logs to the console.
 *
 * Implemented here because every member of the stub throws.
 */
public class ConsoleMessage {
    public enum MessageLevel {
        TIP,
        LOG,
        WARNING,
        ERROR,
        DEBUG,
    }

    private final MessageLevel mLevel;
    private final String mMessage;
    private final String mSourceId;
    private final int mLineNumber;

    public ConsoleMessage(String message, String sourceId, int lineNumber, MessageLevel msgLevel) {
        mMessage = message;
        mSourceId = sourceId;
        mLineNumber = lineNumber;
        mLevel = msgLevel;
    }

    public MessageLevel messageLevel() {
        return mLevel;
    }

    public String message() {
        return mMessage;
    }

    public String sourceId() {
        return mSourceId;
    }

    public int lineNumber() {
        return mLineNumber;
    }
}
