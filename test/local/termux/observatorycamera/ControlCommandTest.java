package local.termux.observatorycamera;

/** Dependency-free test. Run: bash test/run.sh */
public final class ControlCommandTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    public static void main(String[] args) {
        for (String c : new String[]{"start", "stop", "status"}) check(ControlCommand.parse(c, "a-1_B").name.equals(c), c);
        check(ControlCommand.parse(null, "x") == null, "null command");
        check(ControlCommand.parse("START", "x") == null, "case sensitive");
        check(ControlCommand.parse("restart", "x") == null, "unknown");
        check(ControlCommand.parse("start", "bad id") == null, "space in id");
        check(ControlCommand.parse("start", "a\nb") == null, "newline in id");
        check(ControlCommand.parse("start", "") == null, "empty id");
        check(ControlCommand.parse("start", new String(new char[41]).replace('\0', 'a')) == null, "long id");
        check(ControlCommand.parse("status", null).requestId.equals("-"), "default id");
        check(ControlCommand.line("i", "start", "error", "a\nb\r\nc").equals("CONTROL id=i cmd=start result=error a b c"), "line");
        System.out.println("ControlCommandTest passed");
    }
}
