package local.termux.observatorycamera;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Validates the bounded JSON payload and source-time freshness of the BME280 producer. */
final class EnvironmentalTelemetry {
    static final long DEFAULT_STALE_MILLIS = 120_000L;
    static final long MAX_FUTURE_MILLIS = 60_000L;
    static final int MAX_PAYLOAD_BYTES = 4096;

    static final class Reading {
        final double temperatureC;
        final double humidityPct;
        final double pressureHpa;
        final long timestampEpochMillis;

        Reading(double temperatureC, double humidityPct, double pressureHpa, long timestampEpochMillis) {
            this.temperatureC = temperatureC;
            this.humidityPct = humidityPct;
            this.pressureHpa = pressureHpa;
            this.timestampEpochMillis = timestampEpochMillis;
        }

        String freshness(long nowMillis, long staleMillis) {
            long age = nowMillis - timestampEpochMillis;
            if (age < -MAX_FUTURE_MILLIS) return "error";
            return age <= staleMillis ? "fresh" : "stale";
        }
    }

    private Reading latest;
    private String connectionState = "disabled";
    private String error;

    synchronized void setConnectionState(String state, String error) {
        if (!state.equals("disabled") && !state.equals("connecting") && !state.equals("disconnected")
            && !state.equals("waiting") && !state.equals("error")) throw new IllegalArgumentException("Invalid MQTT state.");
        connectionState = state;
        this.error = error;
    }

    synchronized String state(long nowMillis, long staleMillis) {
        if (connectionState.equals("disabled") || connectionState.equals("connecting")
            || connectionState.equals("disconnected") || connectionState.equals("error")) return connectionState;
        if (latest == null) return "waiting";
        return latest.freshness(nowMillis, staleMillis);
    }

    synchronized String error() { return error; }
    synchronized Reading latest() { return latest; }

    synchronized boolean accept(byte[] payload, boolean retained, long nowMillis) {
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) return false;
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                .decode(java.nio.ByteBuffer.wrap(payload)).toString();
            Map<String, Object> values = new JsonObject(text).parse();
            double timestamp = number(values, "timestamp_epoch");
            double temperature = number(values, "temperature_c");
            double humidity = number(values, "humidity_pct");
            double pressure = number(values, "pressure_hpa");
            if (timestamp <= 0 || timestamp > Long.MAX_VALUE / 1000.0
                || timestamp * 1000.0 > nowMillis + MAX_FUTURE_MILLIS
                || temperature < -40 || temperature > 85
                || humidity < 0 || humidity > 100
                || pressure < 300 || pressure > 1100) return false;
            long timestampMillis = Math.round(timestamp * 1000.0);
            if (latest != null && timestampMillis < latest.timestampEpochMillis) return true;
            if (retained && latest != null && timestampMillis == latest.timestampEpochMillis) return true;
            latest = new Reading(temperature, humidity, pressure, timestampMillis);
            connectionState = latest.freshness(nowMillis, DEFAULT_STALE_MILLIS);
            error = null;
            return true;
        } catch (RuntimeException e) {
            return false;
        } catch (java.nio.charset.CharacterCodingException e) {
            return false;
        }
    }

    private static double number(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (!(value instanceof Double)) throw new IllegalArgumentException("Missing numeric field.");
        double number = (Double) value;
        if (!Double.isFinite(number)) throw new IllegalArgumentException("Non-finite number.");
        return number;
    }

    private static final class JsonObject {
        private final String text;
        private int index;
        private int tokens;

        JsonObject(String text) {
            this.text = text;
            if (text.length() > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("Payload too large.");
        }

        Map<String, Object> parse() {
            whitespace();
            expect('{');
            Map<String, Object> values = new HashMap<>();
            Set<String> keys = new HashSet<>();
            whitespace();
            if (take('}')) { end(); return values; }
            do {
                whitespace();
                String key = string();
                if (!keys.add(key)) throw new IllegalArgumentException("Duplicate key.");
                whitespace();
                expect(':');
                whitespace();
                Object value = value(0);
                values.put(key, value);
                whitespace();
                if (take('}')) break;
                expect(',');
            } while (true);
            end();
            return values;
        }

        private Object value(int depth) {
            if (++tokens > 128 || depth > 8) throw new IllegalArgumentException("JSON complexity limit.");
            whitespace();
            if (index >= text.length()) throw new IllegalArgumentException("Missing value.");
            char c = text.charAt(index);
            if (c == '"') return string();
            if (c == '{') {
                index++;
                whitespace();
                if (take('}')) return null;
                do {
                    whitespace(); string(); whitespace(); expect(':'); value(depth + 1); whitespace();
                    if (take('}')) return null;
                    expect(',');
                } while (true);
            }
            if (c == '[') {
                index++;
                whitespace();
                if (take(']')) return null;
                do {
                    value(depth + 1); whitespace();
                    if (take(']')) return null;
                    expect(',');
                } while (true);
            }
            if (text.startsWith("true", index)) { index += 4; return null; }
            if (text.startsWith("false", index)) { index += 5; return null; }
            if (text.startsWith("null", index)) { index += 4; return null; }
            return numeric();
        }

        private Double numeric() {
            int start = index;
            if (take('-') && index >= text.length()) throw new IllegalArgumentException("Bad number.");
            if (take('0')) {
                if (index < text.length() && Character.isDigit(text.charAt(index))) throw new IllegalArgumentException("Bad number.");
            } else {
                digits(true);
            }
            if (take('.')) digits(true);
            if (take('e') || take('E')) {
                if (!take('+')) take('-');
                digits(true);
            }
            if (index == start) throw new IllegalArgumentException("Bad number.");
            return Double.valueOf(text.substring(start, index));
        }

        private void digits(boolean required) {
            int start = index;
            while (index < text.length() && text.charAt(index) >= '0' && text.charAt(index) <= '9') index++;
            if (required && start == index) throw new IllegalArgumentException("Bad number.");
        }

        private String string() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == '"') return result.toString();
                if (c < 0x20) throw new IllegalArgumentException("Control character in string.");
                if (c != '\\') { result.append(c); continue; }
                if (index >= text.length()) throw new IllegalArgumentException("Bad escape.");
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"': case '\\': case '/': result.append(escaped); break;
                    case 'b': result.append('\b'); break;
                    case 'f': result.append('\f'); break;
                    case 'n': result.append('\n'); break;
                    case 'r': result.append('\r'); break;
                    case 't': result.append('\t'); break;
                    case 'u':
                        if (index + 4 > text.length()) throw new IllegalArgumentException("Bad unicode escape.");
                        try { result.append((char) Integer.parseInt(text.substring(index, index + 4), 16)); }
                        catch (NumberFormatException e) { throw new IllegalArgumentException("Bad unicode escape."); }
                        index += 4;
                        break;
                    default: throw new IllegalArgumentException("Bad escape.");
                }
            }
            throw new IllegalArgumentException("Unterminated string.");
        }

        private void whitespace() {
            while (index < text.length() && (text.charAt(index) == ' ' || text.charAt(index) == '\n'
                || text.charAt(index) == '\r' || text.charAt(index) == '\t')) index++;
        }

        private boolean take(char expected) {
            if (index < text.length() && text.charAt(index) == expected) { index++; return true; }
            return false;
        }

        private void expect(char expected) {
            if (!take(expected)) throw new IllegalArgumentException("Malformed JSON.");
        }

        private void end() {
            whitespace();
            if (index != text.length()) throw new IllegalArgumentException("Trailing JSON data.");
        }
    }
}
