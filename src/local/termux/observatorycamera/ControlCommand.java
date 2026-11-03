package local.termux.observatorycamera;

import java.util.regex.Pattern;

/**
 * Validation and log formatting for non-touch control commands. The request id is only a log
 * correlation token chosen by the caller; it is not authentication.
 */
public final class ControlCommand {
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_REQUEST_ID = "request_id";
    public static final String LOG_TAG = "ObservatoryControl";
    public static final String START = "start";
    public static final String STOP = "stop";
    public static final String STATUS = "status";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");

    public final String name;
    public final String requestId;

    private ControlCommand(String name, String requestId) {
        this.name = name;
        this.requestId = requestId;
    }

    /** Returns null for a missing or unknown command or an invalid request id. */
    public static ControlCommand parse(String command, String requestId) {
        if (command == null || !(command.equals(START) || command.equals(STOP) || command.equals(STATUS))) return null;
        if (requestId == null) requestId = "-";
        else if (!ID.matcher(requestId).matches()) return null;
        return new ControlCommand(command, requestId);
    }

    public static boolean isValidRequestId(String id) { return id != null && ID.matcher(id).matches(); }

    /** One greppable line: CONTROL id=ID cmd=CMD result=RESULT [detail]. Detail is sanitized to one line. */
    public static String line(String id, String cmd, String result, String detail) {
        String d = detail == null ? "" : " " + detail.replaceAll("[\\r\\n]+", " ");
        return "CONTROL id=" + id + " cmd=" + cmd + " result=" + result + d;
    }
}
