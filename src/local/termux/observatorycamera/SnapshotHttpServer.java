package local.termux.observatorycamera;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Bounded concurrent development server serving only the private snapshot, a read-only viewer page and an MJPEG stream.
 * It never opens the camera and never triggers capture: it only reads the atomically published snapshot file.
 * At most maxConnections sockets (one thread each) exist at once; at most maxStreams of them may be streams, so
 * streams can never occupy all slots needed by snapshot requests. Start and stop are idempotent.
 */
public final class SnapshotHttpServer {
    public static final int DEFAULT_PORT = 8080;
    public static final String BOUNDARY = "observatoryframe";
    private static final int MAX_HEADER = 8192;
    private static final int MAX_IMAGE = 16 * 1024 * 1024;
    private static final int MAX_STREAM_FRAME = 8 * 1024 * 1024;
    private static final long STOP_JOIN_MILLIS = 2000;
    private static final long POLL_MILLIS = 200;
    private static final SecureRandom NONCES = new SecureRandom();
    private static final String VIEWER = "<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
        + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>Observatory camera</title>"
        + "<style>body{margin:0;background:#111;color:#ccc;font-family:sans-serif;overflow-x:hidden}header{display:flex;align-items:center;gap:1em;padding:8px}"
        + "#viewer{display:grid;grid-template-columns:minmax(0,1fr) 260px;grid-template-areas:'image telemetry';gap:12px;padding:12px;align-items:start;min-width:0}"
        + "#viewer[data-telemetry-position=left]{grid-template-columns:260px minmax(0,1fr);grid-template-areas:'telemetry image'}"
        + "img{grid-area:image;display:block;max-width:100%;height:auto;margin:0 auto}#telemetry{grid-area:telemetry;box-sizing:border-box;border:1px solid currentColor;background:#000;color:#f00;padding:12px;white-space:pre-wrap;overflow-wrap:anywhere;min-width:0;font:16px/1.45 monospace}"
        + "p{margin:8px;font-size:small}#settings{padding:1em;background:#222;box-sizing:border-box;max-width:100%}"
        + "#form:not([hidden]){display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;min-width:0}"
        + "#settings label{display:flex;flex-direction:column;gap:6px;min-width:0;margin:0}#settings input:not([type=checkbox]),#settings select{font:inherit;width:100%;min-width:0;box-sizing:border-box}"
        + "#settings .color-control{display:flex;gap:6px;min-width:0}#settings .color-control input[type=color]{width:3em;height:2.5em;flex:none;padding:2px}"
        + "#settings fieldset{grid-column:1/-1;display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;border:1px solid #666;min-width:0;margin:0;padding:10px}"
        + "#settings legend{grid-column:1/-1}"
        + "#settings fieldset label{flex-direction:row;align-items:center;gap:8px}#settings input[type=checkbox]{margin:0}"
        + "#form button{font:inherit;margin:0}#result{grid-column:1/-1;white-space:pre-wrap;overflow-wrap:anywhere}"
        + "@media(max-width:800px){#viewer,#viewer[data-telemetry-position]{grid-template-columns:minmax(0,1fr);grid-template-areas:'image' 'telemetry'}}"
        + "@media(max-width:600px){#form:not([hidden]){grid-template-columns:minmax(0,1fr)}#settings fieldset{grid-template-columns:minmax(0,1fr)}}"
        + "#settings[hidden]{display:none}</style>"
        + "</head><body><header><strong>Observatory camera</strong><button id=\"settings-button\" type=\"button\">Settings</button></header>"
        + "<section id=\"settings\" hidden><h2>Settings</h2><p>Metadata FPS is an informational estimate, not a limit or guaranteed throughput. Requested target and actual rate are separate.</p>"
        + "<label>Admin token <input id=\"token\" type=\"password\" autocomplete=\"off\"></label>"
        + "<button id=\"load\" type=\"button\">Load settings</button><button id=\"clear\" type=\"button\">Forget token</button>"
        + "<form id=\"form\" hidden><label>Overlay <select name=\"overlay\"><option>on</option><option>off</option></select></label>"
        + "<label>Location <input name=\"location\" maxlength=\"24\"></label><label>Position <select name=\"position\">"
        + "<option>bottom-left</option><option>bottom-right</option><option>top-left</option><option>top-right</option></select></label>"
        + "<label>Panel position <select name=\"telemetry_position\"><option value=\"left\">Left</option><option value=\"right\">Right</option></select></label>"
        + "<label>Text size <input name=\"text_size\" type=\"number\" min=\"10\" max=\"96\"></label>"
        + "<fieldset><legend>Visible overlay fields</legend><label><input type=\"checkbox\" name=\"visible_field\" value=\"location\">Location</label>"
        + "<label><input type=\"checkbox\" name=\"visible_field\" value=\"datetime\">Date/time</label>"
        + "<label><input type=\"checkbox\" name=\"visible_field\" value=\"fps\">FPS (actual/requested)</label>"
        + "<label><input type=\"checkbox\" name=\"visible_field\" value=\"temperature\">Environmental temperature</label>"
        + "<label><input type=\"checkbox\" name=\"visible_field\" value=\"humidity\">Environmental humidity</label>"
        + "<label><input type=\"checkbox\" name=\"visible_field\" value=\"pressure\">Environmental pressure</label>"
        + "<label><input type=\"checkbox\" name=\"visible_field\" value=\"battery\">Battery level</label>"
        + "<label><input type=\"checkbox\" name=\"visible_field\" value=\"battery_temperature\">Battery temperature</label>"
        + "<label><input type=\"checkbox\" name=\"visible_field\" value=\"charging\">Charging status</label>"
        + "<label><input type=\"checkbox\" name=\"visible_field\" value=\"thermal\">Thermal status</label></fieldset>"
        + "<label>Show telemetry panel <input name=\"panel_enabled\" type=\"checkbox\"></label>"
        + "<label>Panel text color <span class=\"color-control\"><input name=\"panel_text_color\" type=\"text\" maxlength=\"7\"><input type=\"color\" data-color-picker=\"panel_text_color\" aria-label=\"Panel text color picker\"></span></label>"
        + "<label>Panel background color <span class=\"color-control\"><input name=\"panel_background_color\" type=\"text\" maxlength=\"7\"><input type=\"color\" data-color-picker=\"panel_background_color\" aria-label=\"Panel background color picker\"></span></label>"
        + "<label>Panel text size <input name=\"panel_text_size\" type=\"number\" min=\"10\" max=\"48\"></label>"
        + "<fieldset><legend>Panel groups</legend><label><input type=\"checkbox\" name=\"panel_environment\">Environment</label>"
        + "<label><input type=\"checkbox\" name=\"panel_device\">Device</label>"
        + "<label><input type=\"checkbox\" name=\"panel_capture\">Capture</label></fieldset>"
        + "<label>MQTT broker host (blank disables) <input name=\"mqtt_host\" maxlength=\"253\"></label>"
        + "<label>MQTT port <input name=\"mqtt_port\" type=\"number\" min=\"1\" max=\"65535\"></label>"
        + "<label>MQTT topic <input name=\"mqtt_topic\" maxlength=\"128\"></label>"
        + "<label>MQTT TLS <input name=\"mqtt_tls\" type=\"checkbox\"></label>"
        + "<label>Sensor stale threshold (seconds) <input name=\"mqtt_stale_seconds\" type=\"number\" min=\"10\" max=\"3600\"></label>"
        + "<p>MQTT credentials are configured only in the private device file, never in browser settings.</p>"
        + "<label>Overlay text color <span class=\"color-control\"><input name=\"text_color\" type=\"text\" maxlength=\"7\"><input type=\"color\" data-color-picker=\"text_color\" aria-label=\"Overlay text color picker\"></span></label>"
        + "<label>JPEG size <select name=\"jpeg_size\"></select></label>"
        + "<label>Target FPS <input name=\"target_fps\" type=\"number\" min=\"0.1\" step=\"0.1\"></label>"
        + "<button type=\"submit\">Save settings</button></form><pre id=\"result\" aria-live=\"polite\"></pre></section>"
        + "<main id=\"viewer\"><img src=\"/stream.mjpg\" alt=\"Live camera stream\"><aside id=\"telemetry\" aria-label=\"Camera telemetry\" hidden></aside></main>"
        + "<p>Settings require the device-admin token.</p>"
        + "<script nonce=\"__NONCE__\">(()=>{let token='';const $=id=>document.getElementById(id),form=$('form'),result=$('result');"
        + "$('settings-button').onclick=()=>{$('settings').hidden=!$('settings').hidden};"
        + "$('clear').onclick=()=>{token='';$('token').value='';form.hidden=true;result.textContent='Token forgotten from memory.'};"
        + "async function api(path,method='GET',body){const r=await fetch(path,{method,mode:'same-origin',credentials:'omit',cache:'no-store',headers:{'Authorization':'Bearer '+token,...(body?{'Content-Type':'application/x-www-form-urlencoded'}:{})},body});"
        + "const data=await r.json();if(!r.ok)throw Error(data.error||('HTTP '+r.status));return data}"
        + "function setSettings(s){for(const e of form.elements)if(e.name&&s[e.name]!==undefined){if(e.type==='checkbox')e.checked=s[e.name]==='on';else e.value=s[e.name]}"
        + "for(const p of form.querySelectorAll('[data-color-picker]')){const f=form.elements[p.dataset.colorPicker];p.value=/^#[0-9a-fA-F]{6}$/.test(f.value)?f.value:'#00ff00'}"
        + "const visible=new Set(s.fields&&s.fields!=='none'?s.fields.split(','):[]);for(const e of form.querySelectorAll('input[name=visible_field]'))e.checked=visible.has(e.value)}"
        + "for(const p of form.querySelectorAll('[data-color-picker]')){const f=form.elements[p.dataset.colorPicker];p.oninput=()=>{f.value=p.value};f.onchange=p.oninput;"
        + "f.oninput=()=>{if(/^#[0-9a-fA-F]{6}$/.test(f.value))p.value=f.value}}"
        + "$('load').onclick=async()=>{token=$('token').value;try{const [c,k]=await Promise.all([api('/api/config'),api('/api/capabilities')]);"
        + "const s=c.settings;setSettings(s);"
        + "const size=form.elements.jpeg_size;size.replaceChildren();const a=new Option('auto — nearest to 1280x720 ('+k.auto_size+')','auto');a.disabled=!k.auto_supported;size.append(a);"
        + "for(const x of k.sizes){const o=document.createElement('option');o.value=x.size;o.disabled=!x.application_supported;o.textContent=x.size+(x.application_supported?'':' (not app-supported)')+' — min '+(x.min_frame_duration_ns??'unknown')+' ns, stall '+(x.jpeg_stall_duration_ns??'unknown')+' ns; timing estimate '+x.estimate_fps+' FPS';size.append(o)}"
        + "if(!Array.from(size.options).some(o=>o.value===s.jpeg_size))size.add(new Option(s.jpeg_size+' (unsupported)',s.jpeg_size));size.value=s.jpeg_size;"
        + "form.hidden=false;result.textContent='Requested target FPS: '+s.target_fps+'; effective target: '+c.effective_target_fps+'; metadata estimate: '+c.metadata_estimate_fps+'; actual FPS: '+c.actual_fps+'; desired size: '+c.desired_size+'; effective size: '+c.effective_size+'; '+(c.reload_error||'')"
        + "}catch(e){token='';$('token').value='';form.hidden=true;result.textContent='Could not load settings: '+e.message}};"
        + "form.onsubmit=async e=>{e.preventDefault();const p=new URLSearchParams(new FormData(form));"
        + "for(const x of form.querySelectorAll('input[type=checkbox][name]:not([name=visible_field])'))p.set(x.name,x.checked?'on':'off');"
        + "const visible=Array.from(form.querySelectorAll('input[name=visible_field]:checked'),x=>x.value);p.delete('visible_field');p.set('fields',visible.length?visible.join(','):'none');"
        + "try{const c=await api('/api/config','PUT',p.toString());setSettings(c.settings);result.textContent='Saved.';"
        + "}catch(e){result.textContent='Not saved: '+e.message}};"
        + "async function updateTelemetry(){const panel=$('telemetry');try{const r=await fetch('/api/telemetry',{mode:'same-origin',credentials:'omit',cache:'no-store'});"
        + "if(!r.ok)throw Error();const d=await r.json();panel.hidden=!d.panel_enabled;panel.style.color=d.panel_text_color;panel.style.backgroundColor=d.panel_background_color;"
        + "$('viewer').dataset.telemetryPosition=d.telemetry_position;"
        + "panel.style.fontSize=d.panel_text_size+'px';const lines=[];"
        + "if(d.panel_environment){lines.push('Environment','Temperature: '+d.environment_temperature_c+' C','Humidity: '+d.environment_humidity_pct+' %',"
        + "'Pressure: '+d.environment_pressure_hpa+' hPa','Sensor: '+d.environment_state)}"
        + "if(d.panel_device){lines.push('Device','Battery level: '+d.battery_percent+' %','Battery temperature: '+d.battery_temperature_c+' C',"
        + "'Charging status: '+d.charging,'Thermal status: '+d.thermal_status)}"
        + "if(d.panel_capture){lines.push('Capture','Capture state: '+d.capture_state,'HTTP server: '+d.http_server_state,"
        + "'Latest image age: '+d.latest_image_age_seconds+' s','FPS actual/requested: '+d.actual_fps+' / '+d.requested_fps,"
        + "'Processing ms last/average/maximum: '+d.processing_last_ms+' / '+d.processing_average_ms+' / '+d.processing_maximum_ms)}"
        + "panel.textContent=lines.join('\\n')}catch(e){panel.hidden=false;panel.textContent='Telemetry unavailable'}}"
        + "updateTelemetry();setInterval(updateTelemetry,2000)})()</script></body></html>\n";

    public interface AdminApi {
        ApiResult handle(String method, String path, byte[] body);
    }

    /** Public, read-only snapshot endpoint matching the existing public image viewer. */
    public interface PublicTelemetryApi {
        ApiResult handle();
    }

    public static final class ApiResult {
        public final int status;
        public final String reason;
        public final byte[] body;
        public ApiResult(int status, String reason, String body) {
            this.status = status;
            this.reason = reason;
            this.body = body.getBytes(StandardCharsets.UTF_8);
        }
    }
    private final File snapshot;
    private final int port;
    private final Consumer<String> log;
    private final AdminTokenStore tokenStore;
    private final AdminApi adminApi;
    private final PublicTelemetryApi telemetryApi;
    private final Object lock = new Object();
    private Session session;
    private volatile Runnable onTerminated;
    private volatile int maxConnections = 8;
    private volatile int maxStreams = 2;
    private volatile long streamIdleMillis = 60000;
    private volatile long writeTimeoutMillis = 10000;

    public SnapshotHttpServer(File snapshot, Consumer<String> log) {
        this(snapshot, DEFAULT_PORT, log);
    }

    public SnapshotHttpServer(File snapshot, int port, Consumer<String> log) {
        this(snapshot, port, log, null, null);
    }

    public SnapshotHttpServer(File snapshot, int port, Consumer<String> log, AdminTokenStore tokenStore, AdminApi adminApi) {
        this(snapshot, port, log, tokenStore, adminApi, null);
    }

    public SnapshotHttpServer(File snapshot, int port, Consumer<String> log, AdminTokenStore tokenStore,
                              AdminApi adminApi, PublicTelemetryApi telemetryApi) {
        this.snapshot = snapshot;
        this.port = port;
        this.log = log;
        this.tokenStore = tokenStore;
        this.adminApi = adminApi;
        this.telemetryApi = telemetryApi;
    }

    /** Test hook; applies to the next start(). */
    void setLimits(int connections, int streams, long idleMillis, long writeMillis) {
        maxConnections = connections;
        maxStreams = streams;
        streamIdleMillis = idleMillis;
        writeTimeoutMillis = writeMillis;
    }

    /** Called from the accept thread if it ends without stop() having been requested. */
    public void setOnTerminated(Runnable callback) { onTerminated = callback; }

    /** Binds synchronously so failures (such as an occupied port) are reported. Does nothing if already running. */
    public void start() throws IOException {
        synchronized (lock) {
            if (session != null && session.acceptor.isAlive()) return;
            ServerSocket socket = new ServerSocket();
            try {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 8);
            } catch (IOException | RuntimeException e) {
                closeQuietly(socket);
                throw e;
            }
            log.accept("Listening on 0.0.0.0:" + socket.getLocalPort() + "; endpoints: /, /snapshot.jpg, /stream.mjpg");
            Session created = new Session(socket, maxConnections, maxStreams, streamIdleMillis, writeTimeoutMillis);
            session = created;
            created.begin();
        }
    }

    /** Closes the listener and every client socket, then waits briefly for all threads to end. */
    public void stop() {
        Session current;
        synchronized (lock) {
            current = session;
            session = null;
        }
        if (current == null) return;
        current.shutdown();
        log.accept("HTTP server stopped");
    }

    public boolean isRunning() {
        synchronized (lock) { return session != null && session.acceptor.isAlive(); }
    }

    public int getLocalPort() {
        synchronized (lock) { return session == null ? -1 : session.listener.getLocalPort(); }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (IOException ignored) { }
    }

    /** One start()..stop() generation: its own listener, threads, connection set and counters. */
    private final class Session {
        final ServerSocket listener;
        final Thread acceptor;
        final Thread watchdog;
        final Set<Conn> conns = ConcurrentHashMap.newKeySet();
        final AtomicInteger streams = new AtomicInteger();
        final int connLimit, streamLimit;
        final long idleMillis, writeMillis;
        volatile boolean closing;

        Session(ServerSocket listener, int connLimit, int streamLimit, long idleMillis, long writeMillis) {
            this.listener = listener;
            this.connLimit = connLimit;
            this.streamLimit = streamLimit;
            this.idleMillis = idleMillis;
            this.writeMillis = writeMillis;
            acceptor = new Thread(this::acceptLoop, "snapshot-http");
            acceptor.setUncaughtExceptionHandler((t, error) -> log.accept("HTTP thread crashed: " + error));
            acceptor.setDaemon(true);
            watchdog = new Thread(this::watchLoop, "snapshot-http-watchdog");
            watchdog.setDaemon(true);
        }

        void begin() {
            watchdog.start();
            acceptor.start();
        }

        void shutdown() {
            closing = true;
            closeQuietly(listener);
            List<Conn> open = new ArrayList<>(conns);
            for (Conn c : open) closeQuietly(c.socket);
            if (acceptor != Thread.currentThread()) {
                long deadline = System.nanoTime() + STOP_JOIN_MILLIS * 1000000L;
                join(acceptor, deadline);
                join(watchdog, deadline);
                for (Conn c : open) join(c.thread, deadline);
            }
            watchdog.interrupt();
        }

        void join(Thread thread, long deadline) {
            if (thread == null) return;
            long millis = Math.max(1, (deadline - System.nanoTime()) / 1000000L);
            try { thread.join(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        void acceptLoop() {
            try {
                while (!closing && !listener.isClosed()) {
                    Socket client;
                    try {
                        client = listener.accept();
                    } catch (IOException e) {
                        if (closing || listener.isClosed()) break;
                        log.accept("Accept error: " + e);
                        continue;
                    }
                    if (closing) { closeQuietly(client); break; }
                    if (conns.size() >= connLimit) {
                        try { rejectBusy(client); } catch (IOException | RuntimeException ignored) { }
                        closeQuietly(client);
                        continue;
                    }
                    Conn conn = new Conn(client);
                    conns.add(conn);
                    try {
                        conn.thread.start();
                    } catch (Throwable t) {
                        conns.remove(conn);
                        closeQuietly(client);
                    }
                    if (closing) closeQuietly(client);
                }
            } finally {
                closeQuietly(listener);
                boolean deliberate = closing;
                Runnable callback = onTerminated;
                if (!deliberate && callback != null) callback.run();
            }
        }

        void rejectBusy(Socket client) throws IOException {
            byte[] body = bytes("Too many connections.\n");
            OutputStream out = client.getOutputStream();
            String header = "HTTP/1.1 503 Service Unavailable\r\nServer: ObservatoryNative/0.3\r\nContent-Type: text/plain\r\nContent-Length: "
                + body.length + "\r\nCache-Control: no-store\r\nRetry-After: 2\r\n" + securityHeaders("")
                + "Connection: close\r\n\r\n";
            out.write(header.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        }

        /** Closes sockets whose current write has not completed in time (slow or stalled readers). */
        void watchLoop() {
            try {
                while (!closing) {
                    Thread.sleep(100);
                    long now = System.nanoTime();
                    for (Conn c : conns) {
                        long deadline = c.writeDeadline;
                        if (deadline != 0 && now - deadline > 0) {
                            log.accept("Closing slow client: " + c.socket.getRemoteSocketAddress());
                            closeQuietly(c.socket);
                        }
                    }
                }
            } catch (InterruptedException ignored) { }
        }

        final class Conn {
            final Socket socket;
            final Thread thread;
            volatile long writeDeadline;

            Conn(Socket socket) {
                this.socket = socket;
                this.thread = new Thread(this::run, "snapshot-http-client");
                this.thread.setDaemon(true);
            }

            void run() {
                try {
                    handle(this);
                } catch (IOException | RuntimeException e) {
                    if (!closing) log.accept("Client error: " + e);
                } finally {
                    closeQuietly(socket);
                    conns.remove(this);
                }
            }

            void write(OutputStream out, byte[] data) throws IOException {
                writeDeadline = System.nanoTime() + writeMillis * 1000000L;
                try {
                    out.write(data);
                    out.flush();
                } finally {
                    writeDeadline = 0;
                }
            }
        }

        void handle(Conn conn) throws IOException {
            Socket client = conn.socket;
            InputStream input = client.getInputStream();
            OutputStream output = client.getOutputStream();
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            long deadline = System.nanoTime() + 5000000000L;
            int suffix = 0;
            boolean complete = false;
            for (int i = 0; i < MAX_HEADER; i++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new SocketTimeoutException("Header deadline exceeded.");
                client.setSoTimeout((int) Math.max(1, remaining / 1000000L));
                int value = input.read();
                if (value == -1) return;
                header.write(value);
                suffix = (suffix << 8) | value;
                if (suffix == 0x0d0a0d0a) { complete = true; break; }
            }
            if (!complete) {
                reply(conn, output, 431, "Request Header Fields Too Large", "text/plain", bytes("Header too large.\n"), false);
                return;
            }
            String[] lines = new String(header.toByteArray(), StandardCharsets.US_ASCII).split("\r\n", -1);
            String first = lines[0];
            String[] parts = first.split(" ");
            if (parts.length != 3 || !(parts[2].equals("HTTP/1.0") || parts[2].equals("HTTP/1.1"))) {
                reply(conn, output, 400, "Bad Request", "text/plain", bytes("Invalid request.\n"), false);
                return;
            }
            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length - 2; i++) {
                int colon = lines[i].indexOf(':');
                if (colon <= 0 || lines[i].charAt(0) == ' ' || lines[i].charAt(0) == '\t') {
                    reply(conn, output, 400, "Bad Request", "text/plain", bytes("Invalid header.\n"), false);
                    return;
                }
                String name = lines[i].substring(0, colon).toLowerCase(java.util.Locale.ROOT);
                String value = lines[i].substring(colon + 1).trim();
                if (!name.matches("[a-z0-9!#$%&'*+.^_`|~-]+") || headers.put(name, value) != null) {
                    reply(conn, output, 400, "Bad Request", "text/plain", bytes("Invalid or duplicate header.\n"), false);
                    return;
                }
            }
            String host = headers.get("host");
            if (host == null || !validAuthority(host)) {
                reply(conn, output, 400, "Bad Request", "text/plain", bytes("Invalid Host.\n"), false);
                return;
            }
            boolean head = parts[0].equals("HEAD");
            if (!head && !parts[0].equals("GET") && !parts[0].equals("PUT")) {
                reply(conn, output, 405, "Method Not Allowed", "text/plain", bytes("Use GET, HEAD or PUT.\n"), false);
                return;
            }
            String path = parts[1].split("\\?", 2)[0];
            if (path.startsWith("/api/")) {
                if (path.equals("/api/telemetry")) {
                    if (!parts[0].equals("GET") || telemetryApi == null) {
                        reply(conn, output, telemetryApi == null ? 404 : 405,
                            telemetryApi == null ? "Not Found" : "Method Not Allowed",
                            "application/json; charset=utf-8", bytes("{\"error\":\"not-found\"}"), head, "");
                    } else {
                        ApiResult result = telemetryApi.handle();
                        reply(conn, output, result.status, result.reason, "application/json; charset=utf-8",
                            result.body, false, "");
                    }
                    return;
                }
                handleApi(conn, output, parts[0], path, headers, host, deadline);
                return;
            }
            if (!parts[0].equals("GET") && !head) {
                reply(conn, output, 405, "Method Not Allowed", "text/plain", bytes("Use GET or HEAD.\n"), false);
                return;
            }
            if (path.equals("/snapshot.jpg")) {
                serveSnapshot(conn, output, head, parts[0]);
            } else if (path.equals("/")) {
                byte[] nonceBytes = new byte[18];
                NONCES.nextBytes(nonceBytes);
                String nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
                String viewer = VIEWER.replace("__NONCE__", nonce);
                reply(conn, output, 200, "OK", "text/html; charset=utf-8", bytes(viewer), head,
                    "default-src 'none'; img-src 'self'; connect-src 'self'; style-src 'unsafe-inline'; script-src 'nonce-"
                        + nonce + "'; form-action 'none'; base-uri 'none'; frame-ancestors 'none'");
            } else if (path.equals("/stream.mjpg")) {
                serveStream(conn, input, output, head);
            } else {
                reply(conn, output, 404, "Not Found", "text/plain", bytes("Available: /, /snapshot.jpg, /stream.mjpg.\n"), head);
            }
        }

        private boolean validAuthority(String authority) {
            try {
                URI uri = new URI("http://" + authority);
                String host = uri.getHost();
                if (host == null || uri.getRawUserInfo() != null || uri.getRawPath().length() != 0
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() < 1 || uri.getPort() > 65535 || !isIpv4(host)) return false;
                return true;
            } catch (URISyntaxException | RuntimeException e) {
                return false;
            }
        }

        private boolean sameOrigin(String origin, String host) {
            try {
                URI uri = new URI(origin);
                URI authority = new URI("http://" + host);
                return "http".equals(uri.getScheme()) && uri.getRawUserInfo() == null
                    && uri.getRawPath().isEmpty() && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && uri.getPort() == authority.getPort() && isIpv4(uri.getHost())
                    && uri.getHost().equalsIgnoreCase(authority.getHost());
            } catch (URISyntaxException | RuntimeException e) {
                return false;
            }
        }

        private boolean isIpv4(String host) {
            if (host == null || !host.matches("[0-9.]+")) return false;
            String[] octets = host.split("\\.", -1);
            if (octets.length != 4) return false;
            for (String octet : octets) {
                if (octet.isEmpty() || octet.length() > 3) return false;
                int value;
                try { value = Integer.parseInt(octet); } catch (NumberFormatException e) { return false; }
                if (value > 255 || (octet.length() > 1 && octet.charAt(0) == '0')) return false;
            }
            return true;
        }

        private void handleApi(Conn conn, OutputStream output, String method, String path,
                              Map<String, String> headers, String host, long requestDeadline) throws IOException {
            if (!method.equals("GET") && !method.equals("PUT")) {
                reply(conn, output, 405, "Method Not Allowed", "application/json; charset=utf-8",
                    bytes("{\"error\":\"method-not-allowed\"}"), false, "");
                return;
            }
            if (tokenStore == null || adminApi == null || tokenStore.read() == null) {
                reply(conn, output, 503, "Service Unavailable", "application/json; charset=utf-8",
                    bytes("{\"error\":\"admin-token-unavailable\"}"), false, "");
                return;
            }
            String authorization = headers.get("authorization");
            if (authorization == null || !authorization.startsWith("Bearer ")
                || !tokenStore.matches(authorization.substring(7))) {
                reply(conn, output, 401, "Unauthorized", "application/json; charset=utf-8",
                    bytes("{\"error\":\"unauthorized\"}"), false,
                    "");
                return;
            }
            String origin = headers.get("origin");
            if ((origin != null && !sameOrigin(origin, host))
                || (method.equals("PUT") && (origin == null || !sameOrigin(origin, host)
                    || "cross-site".equalsIgnoreCase(headers.get("sec-fetch-site"))))) {
                reply(conn, output, 403, "Forbidden", "application/json; charset=utf-8",
                    bytes("{\"error\":\"same-origin-required\"}"), false, "");
                return;
            }
            byte[] body = new byte[0];
            if (method.equals("PUT")) {
                String lengthValue = headers.get("content-length");
                if (headers.containsKey("transfer-encoding") || lengthValue == null
                    || !lengthValue.matches("[0-9]{1,5}")) {
                    reply(conn, output, 400, "Bad Request", "application/json; charset=utf-8",
                        bytes("{\"error\":\"content-length-required\"}"), false, "");
                    return;
                }
                int length = Integer.parseInt(lengthValue);
                if (length > 4096) {
                    reply(conn, output, 413, "Content Too Large", "application/json; charset=utf-8",
                        bytes("{\"error\":\"body-too-large\"}"), false, "");
                    return;
                }
                if (!"application/x-www-form-urlencoded".equalsIgnoreCase(headers.get("content-type"))) {
                    reply(conn, output, 415, "Unsupported Media Type", "application/json; charset=utf-8",
                        bytes("{\"error\":\"form-urlencoded-required\"}"), false, "");
                    return;
                }
                body = new byte[length];
                int offset = 0;
                while (offset < length) {
                    long remaining = requestDeadline - System.nanoTime();
                    if (remaining <= 0) throw new SocketTimeoutException("Request deadline exceeded.");
                    conn.socket.setSoTimeout((int) Math.max(1, remaining / 1000000L));
                    int count = conn.socket.getInputStream().read(body, offset, length - offset);
                    if (count < 0) throw new IOException("Incomplete request body.");
                    offset += count;
                }
            } else if (headers.containsKey("content-length") || headers.containsKey("transfer-encoding")) {
                reply(conn, output, 400, "Bad Request", "application/json; charset=utf-8",
                    bytes("{\"error\":\"unexpected-request-body\"}"), false, "");
                return;
            }
            ApiResult result = adminApi.handle(method, path, body);
            reply(conn, output, result.status, result.reason, "application/json; charset=utf-8",
                result.body, false, "");
        }

        void serveSnapshot(Conn conn, OutputStream output, boolean head, String method) throws IOException {
            byte[] image;
            try {
                image = readImage(MAX_IMAGE);
            } catch (FileNotFoundException e) {
                reply(conn, output, 404, "Not Found", "text/plain", bytes("No snapshot available. Capture an image first.\n"), head);
                return;
            } catch (IOException e) {
                reply(conn, output, 503, "Service Unavailable", "text/plain", bytes("Snapshot unavailable.\n"), head);
                return;
            }
            reply(conn, output, 200, "OK", "image/jpeg", image, head);
            log.accept("Snapshot response: 200, " + image.length + " bytes, method=" + method);
        }

        /**
         * Behavior: headers are sent at once; a frame is sent only when the snapshot file was republished after the previous frame
         * (or, for the first frame, after the client connected). Nothing is repeated. If no new publication arrives within
         * the idle limit (capture stopped, failed or slower than the limit) the stream ends with the closing boundary.
         */
        void serveStream(Conn conn, InputStream input, OutputStream output, boolean head) throws IOException {
            String headers = "HTTP/1.1 200 OK\r\nServer: ObservatoryNative/0.3\r\nContent-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY
                + "\r\nCache-Control: no-store, no-cache, must-revalidate, max-age=0\r\nPragma: no-cache\r\nExpires: 0\r\n"
                + securityHeaders("") + "Allow: GET, HEAD\r\nConnection: close\r\n\r\n";
            if (head) {
                conn.write(output, headers.getBytes(StandardCharsets.US_ASCII));
                return;
            }
            if (streams.incrementAndGet() > streamLimit) {
                streams.decrementAndGet();
                rejectBusy(conn.socket);
                return;
            }
            try {
                log.accept("Stream client connected: " + conn.socket.getRemoteSocketAddress());
                conn.write(output, headers.getBytes(StandardCharsets.US_ASCII));
                String seen = signature();
                long lastNew = System.nanoTime();
                while (!closing) {
                    String current = signature();
                    if (current != null && !current.equals(seen)) {
                        byte[] frame = null;
                        try { frame = readImage(MAX_STREAM_FRAME); } catch (IOException ignored) { }
                        String after = signature();
                        if (after != null && !current.equals(after)) continue; // republished while reading; take the newer file
                        seen = current;
                        if (frame != null) {
                            String part = "--" + BOUNDARY + "\r\nContent-Type: image/jpeg\r\nContent-Length: " + frame.length + "\r\n\r\n";
                            conn.write(output, part.getBytes(StandardCharsets.US_ASCII));
                            conn.write(output, frame);
                            conn.write(output, new byte[] {'\r', '\n'});
                            lastNew = System.nanoTime();
                        }
                    }
                    if ((System.nanoTime() - lastNew) / 1000000L >= idleMillis) {
                        conn.write(output, ("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
                        log.accept("Stream ended: no new frame for " + idleMillis + " ms");
                        return;
                    }
                    try {
                        conn.socket.setSoTimeout((int) POLL_MILLIS);
                        if (input.read() == -1) return; // client disconnected
                    } catch (SocketTimeoutException poll) { }
                }
            } finally {
                streams.decrementAndGet();
                log.accept("Stream client finished");
            }
        }

        /** Identity of the currently published file: a rename publication changes the file key even if the size and time match. */
        String signature() {
            try {
                BasicFileAttributes a = Files.readAttributes(snapshot.toPath(), BasicFileAttributes.class);
                return a.fileKey() + "/" + a.lastModifiedTime().toMillis() + "/" + a.size();
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        byte[] readImage(int limit) throws IOException {
            try (FileInputStream source = new FileInputStream(snapshot)) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] block = new byte[8192];
                int count;
                while ((count = source.read(block)) != -1) {
                    if (buffer.size() + count > limit) throw new IOException("Snapshot exceeds size limit.");
                    buffer.write(block, 0, count);
                }
                byte[] image = buffer.toByteArray();
                if (image.length < 4 || image[0] != (byte) 0xff || image[1] != (byte) 0xd8 || image[image.length - 2] != (byte) 0xff || image[image.length - 1] != (byte) 0xd9) throw new IOException("Snapshot is incomplete.");
                return image;
            }
        }
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static String securityHeaders(String csp) {
        String policy = csp.isEmpty()
            ? "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'" : csp;
        return "X-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\n"
            + "Permissions-Policy: camera=(), microphone=(), geolocation=()\r\n"
            + "Cross-Origin-Resource-Policy: same-origin\r\n"
            + "Content-Security-Policy: " + policy + "\r\n";
    }

    private void reply(Session.Conn conn, OutputStream output, int code, String reason, String type, byte[] body, boolean head) throws IOException {
        reply(conn, output, code, reason, type, body, head, "");
    }

    private void reply(Session.Conn conn, OutputStream output, int code, String reason, String type, byte[] body, boolean head, String extra) throws IOException {
        String header = "HTTP/1.1 " + code + " " + reason + "\r\nServer: ObservatoryNative/0.3\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length + "\r\nCache-Control: no-store\r\n" + securityHeaders(extra) + "Allow: GET, HEAD, PUT\r\nConnection: close\r\n\r\n";
        conn.write(output, header.getBytes(StandardCharsets.US_ASCII));
        if (!head) conn.write(output, body);
    }
}
