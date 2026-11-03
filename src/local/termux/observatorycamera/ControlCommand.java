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
    public static final String CONTINUOUS_START = "continuous-start";
    public static final String CONTINUOUS_STOP = "continuous-stop";
    public static final String CONTINUOUS_STATUS = "continuous-status";
    public static final String CONFIG_SHOW = "config-show";
    public static final String CONFIG_SET = "config-set";
    public static final String CONFIG_RESET = "config-reset";
    public static final String CONFIG_EXPORT = "config-export";
    public static final String CONFIG_IMPORT = "config-import";
    public static final String ADMIN_TOKEN_SHOW = "admin-token-show";
    public static final String ADMIN_TOKEN_ROTATE = "admin-token-rotate";
    public static final String EXTRA_CONFIG = "config";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");

    public final String name;
    public final String requestId;
    /** key=value assignments for config-set (validated later by CaptureConfig.parse); null otherwise. */
    public final String config;

    private ControlCommand(String name, String requestId, String config) {
        this.name = name;
        this.requestId = requestId;
        this.config = config;
    }

    private static boolean known(String c) {
        return c.equals(START) || c.equals(STOP) || c.equals(STATUS) || c.equals(CONTINUOUS_START) || c.equals(CONTINUOUS_STOP)
            || c.equals(CONTINUOUS_STATUS) || c.equals(CONFIG_SHOW) || c.equals(CONFIG_SET) || c.equals(CONFIG_RESET)
            || c.equals(CONFIG_EXPORT) || c.equals(CONFIG_IMPORT) || c.equals(ADMIN_TOKEN_SHOW) || c.equals(ADMIN_TOKEN_ROTATE);
    }

    /** Commands that start the camera service and therefore need a resumed activity. */
    public boolean needsResumedActivity() { return name.equals(START) || name.equals(CONTINUOUS_START); }

    /** Returns null for a missing or unknown command or an invalid request id. */
    public static ControlCommand parse(String command, String requestId) { return parse(command, requestId, null); }

    /** config is required for config-set and ignored for every other command. */
    public static ControlCommand parse(String command, String requestId, String config) {
        if (command == null || !known(command)) return null;
        if (requestId == null) requestId = "-";
        else if (!ID.matcher(requestId).matches()) return null;
        if (command.equals(CONFIG_SET)) {
            if (config == null || config.isEmpty()) return null;
        } else {
            config = null;
        }
        return new ControlCommand(command, requestId, config);
    }

    public static boolean isValidRequestId(String id) { return id != null && ID.matcher(id).matches(); }

    /** One greppable line: CONTROL id=ID cmd=CMD result=RESULT [detail]. Detail is sanitized to one line. */
    public static String line(String id, String cmd, String result, String detail) {
        String d = detail == null ? "" : " " + detail.replaceAll("[\\r\\n]+", " ");
        return "CONTROL id=" + id + " cmd=" + cmd + " result=" + result + d;
    }
}
