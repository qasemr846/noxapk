package com.nox.app;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a vless:// vmess:// trojan:// ss:// link into a complete Xray-core JSON config
 * with a local SOCKS inbound on 127.0.0.1:SOCKS_PORT. Pure Java (no Android classes)
 * so it can be tested on any JVM.
 */
public final class XrayConfig {
    public static final int SOCKS_PORT = 10808;

    private XrayConfig() {}

    public static String fromUri(String uri) throws Exception {
        uri = uri.trim();
        int p = uri.indexOf("://");
        if (p < 0) throw new Exception("invalid link");
        String scheme = uri.substring(0, p).toLowerCase();
        String body = uri.substring(p + 3);
        Map<String, Object> outbound;
        switch (scheme) {
            case "vless": outbound = vless(body); break;
            case "vmess": outbound = vmess(body); break;
            case "trojan": outbound = trojan(body); break;
            case "ss": outbound = shadowsocks(body); break;
            default: throw new Exception("unsupported protocol: " + scheme);
        }
        outbound.put("tag", "proxy");

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("log", map("loglevel", "warning"));
        // Go on Android has no /etc/resolv.conf: give Xray its own resolvers.
        Map<String, Object> dns = new LinkedHashMap<>();
        dns.put("servers", list("8.8.8.8", "1.1.1.1"));
        dns.put("queryStrategy", "UseIPv4");
        root.put("dns", dns);

        Map<String, Object> inbound = new LinkedHashMap<>();
        inbound.put("tag", "socks-in");
        inbound.put("listen", "127.0.0.1");
        inbound.put("port", SOCKS_PORT);
        inbound.put("protocol", "socks");
        Map<String, Object> ins = new LinkedHashMap<>();
        ins.put("auth", "noauth");
        ins.put("udp", true);
        ins.put("ip", "127.0.0.1");
        inbound.put("settings", ins);
        Map<String, Object> sniff = new LinkedHashMap<>();
        sniff.put("enabled", true);
        sniff.put("destOverride", list("http", "tls"));
        inbound.put("sniffing", sniff);
        root.put("inbounds", list(inbound));

        List<Object> outs = new ArrayList<>();
        outs.add(outbound);
        outs.add(map2("tag", "direct", "protocol", "freedom"));
        outs.add(map2("tag", "block", "protocol", "blackhole"));
        root.put("outbounds", outs);

        Map<String, Object> routing = new LinkedHashMap<>();
        routing.put("domainStrategy", "AsIs");
        routing.put("rules", new ArrayList<>());
        root.put("routing", routing);
        return Json.write(root);
    }

    /* ---------- protocols ---------- */

    private static Map<String, Object> vless(String body) throws Exception {
        Link l = Link.parse(body);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", l.user);
        user.put("encryption", l.q("encryption", "none"));
        String flow = l.q("flow", "");
        if (!flow.isEmpty()) user.put("flow", flow);
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("address", l.host);
        server.put("port", l.port);
        server.put("users", list(user));
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("protocol", "vless");
        o.put("settings", map("vnext", list(server)));
        o.put("streamSettings", stream(l.query, l.host));
        return o;
    }

    private static Map<String, Object> trojan(String body) throws Exception {
        Link l = Link.parse(body);
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("address", l.host);
        server.put("port", l.port);
        server.put("password", l.user);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("protocol", "trojan");
        o.put("settings", map("servers", list(server)));
        Map<String, String> q = new LinkedHashMap<>(l.query);
        if (!q.containsKey("security")) q.put("security", "tls");
        o.put("streamSettings", stream(q, l.host));
        return o;
    }

    private static Map<String, Object> vmess(String body) throws Exception {
        String b64 = body.split("#")[0];
        Map<String, Object> j = Json.parseFlat(new String(b64dec(b64), StandardCharsets.UTF_8));
        String add = str(j.get("add")), id = str(j.get("id"));
        int port = Integer.parseInt(str(j.get("port")));
        if (add.isEmpty() || id.isEmpty()) throw new Exception("invalid vmess");
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", id);
        String aid = str(j.get("aid"));
        user.put("alterId", aid.isEmpty() ? 0 : Integer.parseInt(aid));
        String scy = str(j.get("scy"));
        user.put("security", scy.isEmpty() ? "auto" : scy);
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("address", add);
        server.put("port", port);
        server.put("users", list(user));
        Map<String, String> q = new LinkedHashMap<>();
        q.put("type", orDef(str(j.get("net")), "tcp"));
        q.put("headerType", str(j.get("type")));
        q.put("host", str(j.get("host")));
        q.put("path", str(j.get("path")));
        String tls = str(j.get("tls"));
        q.put("security", tls.equals("tls") ? "tls" : "none");
        q.put("sni", str(j.get("sni")));
        q.put("alpn", str(j.get("alpn")));
        q.put("fp", str(j.get("fp")));
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("protocol", "vmess");
        o.put("settings", map("vnext", list(server)));
        o.put("streamSettings", stream(q, add));
        return o;
    }

    private static Map<String, Object> shadowsocks(String body) throws Exception {
        int h = body.indexOf('#');
        String main = h >= 0 ? body.substring(0, h) : body;
        int qm = main.indexOf('?');
        if (qm >= 0) main = main.substring(0, qm);
        main = main.replaceAll("/+$", "");
        String method, password, host;
        int port;
        if (main.indexOf('@') < 0) {
            main = new String(b64dec(main), StandardCharsets.UTF_8);
        }
        int at = main.lastIndexOf('@');
        if (at < 0) throw new Exception("invalid ss");
        String userinfo = main.substring(0, at), hp = main.substring(at + 1);
        if (userinfo.indexOf(':') < 0) userinfo = new String(b64dec(userinfo), StandardCharsets.UTF_8);
        else userinfo = urlDecode(userinfo);
        int c = userinfo.indexOf(':');
        if (c < 0) throw new Exception("invalid ss");
        method = userinfo.substring(0, c);
        password = userinfo.substring(c + 1);
        int pc = hp.lastIndexOf(':');
        host = hp.substring(0, pc).replaceAll("^\\[|\\]$", "");
        port = Integer.parseInt(hp.substring(pc + 1));
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("address", host);
        server.put("port", port);
        server.put("method", method);
        server.put("password", password);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("protocol", "shadowsocks");
        o.put("settings", map("servers", list(server)));
        return o;
    }

    /* ---------- transport / security ---------- */

    private static Map<String, Object> stream(Map<String, String> q, String host) {
        Map<String, Object> s = new LinkedHashMap<>();
        String net = orDef(q.get("type"), "tcp");
        if (net.equals("http")) net = "h2";
        s.put("network", net);
        String sec = orDef(q.get("security"), "none");
        s.put("security", sec);
        String sni = orDef(q.get("sni"), orDef(q.get("host"), host));
        String fp = orDef(q.get("fp"), "chrome");
        if (sec.equals("tls")) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("serverName", sni);
            t.put("fingerprint", fp);
            String alpn = q.get("alpn");
            if (alpn != null && !alpn.isEmpty()) {
                List<Object> a = new ArrayList<>();
                for (String x : alpn.split(",")) if (!x.trim().isEmpty()) a.add(x.trim());
                t.put("alpn", a);
            }
            t.put("allowInsecure", false);
            s.put("tlsSettings", t);
        } else if (sec.equals("reality")) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("serverName", orDef(q.get("sni"), host));
            r.put("fingerprint", fp);
            r.put("publicKey", orDef(q.get("pbk"), ""));
            r.put("shortId", orDef(q.get("sid"), ""));
            r.put("spiderX", orDef(q.get("spx"), ""));
            s.put("realitySettings", r);
        }
        String path = orDef(q.get("path"), "");
        String hostHdr = orDef(q.get("host"), "");
        switch (net) {
            case "ws": {
                Map<String, Object> w = new LinkedHashMap<>();
                w.put("path", path.isEmpty() ? "/" : path);
                if (!hostHdr.isEmpty()) w.put("headers", map("Host", hostHdr));
                s.put("wsSettings", w);
                break;
            }
            case "grpc": {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("serviceName", orDef(q.get("serviceName"), path));
                g.put("multiMode", "multi".equals(q.get("mode")));
                s.put("grpcSettings", g);
                break;
            }
            case "h2": {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("path", path.isEmpty() ? "/" : path);
                if (!hostHdr.isEmpty()) h.put("host", list(hostHdr));
                s.put("httpSettings", h);
                break;
            }
            case "httpupgrade": {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("path", path.isEmpty() ? "/" : path);
                if (!hostHdr.isEmpty()) h.put("host", hostHdr);
                s.put("httpupgradeSettings", h);
                break;
            }
            case "xhttp":
            case "splithttp": {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("path", path.isEmpty() ? "/" : path);
                if (!hostHdr.isEmpty()) h.put("host", hostHdr);
                String mode = q.get("mode");
                if (mode != null && !mode.isEmpty()) h.put("mode", mode);
                s.put("xhttpSettings", h);
                s.put("network", "xhttp");
                break;
            }
            default: {
                if ("http".equals(q.get("headerType"))) {
                    Map<String, Object> req = new LinkedHashMap<>();
                    req.put("path", list(path.isEmpty() ? "/" : path));
                    if (!hostHdr.isEmpty()) req.put("headers", map("Host", list(hostHdr.split(","))));
                    Map<String, Object> hd = new LinkedHashMap<>();
                    hd.put("type", "http");
                    hd.put("request", req);
                    s.put("tcpSettings", map("header", hd));
                }
            }
        }
        return s;
    }

    /* ---------- helpers ---------- */

    static final class Link {
        String user = "", host = "";
        int port;
        Map<String, String> query = new LinkedHashMap<>();

        String q(String k, String d) { return orDef(query.get(k), d); }

        static Link parse(String body) throws Exception {
            int h = body.indexOf('#');
            if (h >= 0) body = body.substring(0, h);
            String qs = "";
            int qm = body.indexOf('?');
            if (qm >= 0) { qs = body.substring(qm + 1); body = body.substring(0, qm); }
            body = body.replaceAll("/+$", "");
            Link l = new Link();
            int at = body.lastIndexOf('@');
            if (at < 0) throw new Exception("missing user");
            l.user = urlDecode(body.substring(0, at));
            String hp = body.substring(at + 1);
            int c = hp.lastIndexOf(':');
            if (c < 0) throw new Exception("missing port");
            l.host = hp.substring(0, c).replaceAll("^\\[|\\]$", "");
            l.port = Integer.parseInt(hp.substring(c + 1));
            if (l.host.isEmpty() || l.port < 1 || l.port > 65535) throw new Exception("bad address");
            for (String kv : qs.split("&")) {
                if (kv.isEmpty()) continue;
                int e = kv.indexOf('=');
                String k = e >= 0 ? kv.substring(0, e) : kv, v = e >= 0 ? kv.substring(e + 1) : "";
                l.query.put(urlDecode(k), urlDecode(v));
            }
            return l;
        }
    }

    static String urlDecode(String s) {
        try { return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8"); } catch (Exception e) { return s; }
    }

    static byte[] b64dec(String s) {
        s = s.replace('-', '+').replace('_', '/').replaceAll("\\s+", "").replaceAll("=+$", "");
        return Base64.getDecoder().decode(s);
    }

    static String orDef(String v, String d) { return v == null || v.isEmpty() ? d : v; }
    static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    @SafeVarargs
    static <T> List<Object> list(T... a) {
        List<Object> l = new ArrayList<>();
        for (T x : a) l.add(x);
        return l;
    }

    static Map<String, Object> map(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    static Map<String, Object> map2(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    /* ---------- tiny JSON (no org.json so this runs on a plain JVM) ---------- */
    static final class Json {
        static String write(Object o) {
            StringBuilder sb = new StringBuilder();
            w(sb, o);
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        private static void w(StringBuilder sb, Object o) {
            if (o == null) sb.append("null");
            else if (o instanceof String) quote(sb, (String) o);
            else if (o instanceof Number || o instanceof Boolean) sb.append(o);
            else if (o instanceof Map) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    quote(sb, e.getKey());
                    sb.append(':');
                    w(sb, e.getValue());
                }
                sb.append('}');
            } else if (o instanceof List) {
                sb.append('[');
                boolean first = true;
                for (Object x : (List<Object>) o) {
                    if (!first) sb.append(',');
                    first = false;
                    w(sb, x);
                }
                sb.append(']');
            } else quote(sb, String.valueOf(o));
        }

        private static void quote(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
            sb.append('"');
        }

        /** Parses a flat JSON object of string/number/bool values (what vmess:// links contain). */
        static Map<String, Object> parseFlat(String s) throws Exception {
            Map<String, Object> m = new LinkedHashMap<>();
            int[] i = {0};
            ws(s, i);
            expect(s, i, '{');
            ws(s, i);
            if (s.charAt(i[0]) == '}') return m;
            while (true) {
                ws(s, i);
                String k = readString(s, i);
                ws(s, i);
                expect(s, i, ':');
                ws(s, i);
                char c = s.charAt(i[0]);
                Object v;
                if (c == '"') v = readString(s, i);
                else {
                    int st = i[0];
                    while (i[0] < s.length() && ",}".indexOf(s.charAt(i[0])) < 0) i[0]++;
                    v = s.substring(st, i[0]).trim();
                }
                m.put(k, v);
                ws(s, i);
                char d = s.charAt(i[0]++);
                if (d == '}') break;
                if (d != ',') throw new Exception("bad json");
            }
            return m;
        }

        private static void ws(String s, int[] i) { while (i[0] < s.length() && Character.isWhitespace(s.charAt(i[0]))) i[0]++; }
        private static void expect(String s, int[] i, char c) throws Exception { if (s.charAt(i[0]++) != c) throw new Exception("bad json"); }

        private static String readString(String s, int[] i) throws Exception {
            expect(s, i, '"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i[0]++);
                if (c == '"') break;
                if (c == '\\') {
                    char e = s.charAt(i[0]++);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'u': sb.append((char) Integer.parseInt(s.substring(i[0], i[0] + 4), 16)); i[0] += 4; break;
                        default: sb.append(e);
                    }
                } else sb.append(c);
            }
            return sb.toString();
        }
    }
}
