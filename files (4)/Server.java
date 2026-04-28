import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;

/**
 * PSYCHO LEAGUE — Server.java v4.0
 * Compile : javac Server.java
 * Run     : java -Dfile.encoding=UTF-8 Server
 * Open    : http://localhost:8080
 */
public class Server {

    static final int    PORT;
    static {
        int p = 8080;
        try { p = Integer.parseInt(System.getenv().getOrDefault("PORT","8080")); } catch(Exception ignored) {}
        PORT = p;
    }
    static final String ADMIN_USER = "Panda";
    static final String ADMIN_PASS = "Humoaxi123";
    static final String DATA_DIR   = "data";
    static final String UPLOAD_DIR = "uploads";
    static final long   MAX_UPLOAD = 20 * 1024 * 1024; // 20MB

    static final List<Map<String,Object>> NEWS        = new CopyOnWriteArrayList<>();
    static final List<Map<String,Object>> MATCHES     = new CopyOnWriteArrayList<>();
    static final List<Map<String,Object>> TABLE       = new CopyOnWriteArrayList<>();
    static final List<Map<String,Object>> PLAYERS     = new CopyOnWriteArrayList<>();
    static final List<Map<String,Object>> USERS       = new CopyOnWriteArrayList<>();
    static final List<Map<String,Object>> APPLICATIONS= new CopyOnWriteArrayList<>();
    static final List<HttpExchange>        SSE         = new CopyOnWriteArrayList<>();
    static final Map<String,Long>         TOKENS      = new ConcurrentHashMap<>();

    static final AtomicInteger NID = new AtomicInteger(1);
    static final AtomicInteger MID = new AtomicInteger(1);
    static final AtomicInteger PID = new AtomicInteger(1);
    static final AtomicInteger UID = new AtomicInteger(1);
    static final AtomicInteger AID = new AtomicInteger(1);

    static PrintStream P;
    static {
        try { P = new PrintStream(System.out, true, "UTF-8"); }
        catch (Exception e) { P = System.out; }
        System.setOut(P); System.setErr(P);
    }

    public static void main(String[] args) throws Exception {
        P.println("╔══════════════════════════════════════════╗");
        P.println("║    PSYCHO LEAGUE SERVER  v4.0            ║");
        P.println("║    Java · UTF-8 · Port " + PORT + "              ║");
        P.println("╚══════════════════════════════════════════╝");
        new File(DATA_DIR).mkdirs();
        new File(UPLOAD_DIR).mkdirs();
        new File("players").mkdirs();
        load();
        seedIfEmpty();

        HttpServer srv = HttpServer.create(new InetSocketAddress(PORT), 256);

        // Static + uploads
        srv.createContext("/", ex -> {
            String p = ex.getRequestURI().getPath();
            if (p.startsWith("/api")) { respond(ex,404,"text/plain","Not found"); return; }
            if (p.equals("/") || p.equals("/index.html")) p = "/index.html";
            if (p.contains("..")) { respond(ex,403,"text/plain","Forbidden"); return; }
            File f = new File("."+p);
            if (f.exists() && f.isFile()) {
                respond(ex, 200, mime(p), Files.readAllBytes(f.toPath()));
            } else {
                File idx = new File("./index.html");
                if (idx.exists()) respond(ex, 200, "text/html; charset=utf-8", Files.readAllBytes(idx.toPath()));
                else respond(ex,404,"text/plain","404");
            }
        });

        // ── PUBLIC API ──────────────────────────────────
        srv.createContext("/api/news",         ex -> { cors(ex); respondJson(ex,200,arrJson(NEWS)); });
        srv.createContext("/api/results",      ex -> { cors(ex); respondJson(ex,200,"{\"table\":"+arrJson(TABLE)+",\"matches\":"+arrJson(MATCHES)+"}"); });
        srv.createContext("/api/players",      ex -> { cors(ex); respondJson(ex,200,arrJson(PLAYERS)); });
        srv.createContext("/api/applications", ex -> {
            cors(ex);
            if ("OPTIONS".equals(ex.getRequestMethod())) { respond(ex,200,"text/plain",""); return; }
            if ("POST".equals(ex.getRequestMethod())) {
                try {
                    Map<String,Object> a = parseObj(body(ex));
                    a.put("id", AID.getAndIncrement());
                    a.put("date", LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")));
                    a.put("status", "pending");
                    APPLICATIONS.add(a);
                    save();
                    respondJson(ex,200,"{\"ok\":true}");
                    broadcast("application", "{\"name\":\""+jesc(s(a,"name"))+"\"}");
                    P.println("[APP] Заявка от: "+a.get("name"));
                } catch(Exception e){ respondJson(ex,400,"{\"error\":\"bad\"}"); }
            } else {
                respondJson(ex,405,"{\"error\":\"method\"}");
            }
        });
        srv.createContext("/api/register",     ex -> {
            cors(ex);
            if ("OPTIONS".equals(ex.getRequestMethod())) { respond(ex,200,"text/plain",""); return; }
            try {
                Map<String,Object> u = parseObj(body(ex));
                u.put("id", UID.getAndIncrement());
                u.put("date", LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")));
                USERS.add(u);
                save();
                respondJson(ex,200,"{\"ok\":true}");
            } catch(Exception e){ respondJson(ex,400,"{\"error\":\"bad\"}"); }
        });
        srv.createContext("/api/events", ex -> {
            ex.getResponseHeaders().set("Content-Type","text/event-stream; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control","no-cache");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin","*");
            ex.sendResponseHeaders(200,0);
            SSE.add(ex);
            try { while(!Thread.currentThread().isInterrupted()){ Thread.sleep(25000); ex.getResponseBody().write(": ping\n\n".getBytes(StandardCharsets.UTF_8)); ex.getResponseBody().flush(); } }
            catch(Exception e){}
            SSE.remove(ex);
        });

        // ── UPLOAD ──────────────────────────────────────
        srv.createContext("/api/upload", ex -> {
            cors(ex);
            if ("OPTIONS".equals(ex.getRequestMethod())) { respond(ex,200,"text/plain",""); return; }
            if (!auth(ex)) { respondJson(ex,401,"{\"error\":\"unauth\"}"); return; }
            try {
                String ct = ex.getRequestHeaders().getFirst("Content-Type");
                if (ct == null || !ct.contains("multipart/form-data")) {
                    respondJson(ex,400,"{\"error\":\"need multipart\"}"); return;
                }
                String boundary = ct.split("boundary=")[1].trim();
                byte[] rawBody = ex.getRequestBody().readAllBytes();
                if (rawBody.length > MAX_UPLOAD) { respondJson(ex,413,"{\"error\":\"too large\"}"); return; }

                // Parse multipart — find filename from headers (search full header section)
                byte[] headerSep = "\r\n\r\n".getBytes(StandardCharsets.UTF_8);
                int headerEnd = indexOf(rawBody, headerSep);
                String headerSection = headerEnd > 0
                    ? new String(rawBody, 0, headerEnd, StandardCharsets.UTF_8)
                    : new String(rawBody, 0, Math.min(2048, rawBody.length), StandardCharsets.UTF_8);

                String filename = "upload_"+System.currentTimeMillis();
                if (headerSection.contains("filename=\"")) {
                    int fs = headerSection.indexOf("filename=\"")+10;
                    int fe = headerSection.indexOf("\"", fs);
                    if (fe > fs) filename = headerSection.substring(fs, fe).replaceAll("[^a-zA-Z0-9._-]","_");
                }
                // Find data start (after \r\n\r\n)
                int dataStart = (headerEnd >= 0 ? headerEnd : 0) + headerSep.length;
                // Find data end (before last boundary)
                byte[] endBound = ("--"+boundary+"--").getBytes(StandardCharsets.UTF_8);
                int dataEnd = lastIndexOf(rawBody, endBound) - 2; // -2 for \r\n
                if (headerEnd < 0 || dataEnd <= dataStart) { respondJson(ex,400,"{\"error\":\"parse error\"}"); return; }
                byte[] fileData = Arrays.copyOfRange(rawBody, dataStart, dataEnd);

                // Save
                String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.')) : "";
                String saved = "upload_"+System.currentTimeMillis()+ext;
                Files.write(Path.of(UPLOAD_DIR+"/"+saved), fileData);
                String url = "/uploads/"+saved;
                respondJson(ex,200,"{\"ok\":true,\"url\":\""+url+"\"}");
                P.println("[UPLOAD] "+saved+" ("+fileData.length+" bytes)");
            } catch(Exception e){
                P.println("[UPLOAD] Error: "+e.getMessage());
                respondJson(ex,500,"{\"error\":\""+jesc(e.getMessage())+"\"}");
            }
        });

        // ── ADMIN LOGIN ─────────────────────────────────
        srv.createContext("/api/admin/login", ex -> {
            cors(ex);
            if ("OPTIONS".equals(ex.getRequestMethod())) { respond(ex,200,"text/plain",""); return; }
            String b = body(ex);
            if (ADMIN_USER.equals(strVal(b,"user")) && ADMIN_PASS.equals(strVal(b,"pass"))) {
                String tok = UUID.randomUUID().toString();
                TOKENS.put(tok, System.currentTimeMillis());
                respondJson(ex,200,"{\"token\":\""+tok+"\"}");
                P.println("[ADMIN] Login OK");
            } else {
                respondJson(ex,401,"{\"error\":\"wrong\"}");
            }
        });

        // ── ADMIN NEWS ──────────────────────────────────
        srv.createContext("/api/admin/news", ex -> {
            cors(ex);
            if ("OPTIONS".equals(ex.getRequestMethod())) { respond(ex,200,"text/plain",""); return; }
            if (!auth(ex)) { respondJson(ex,401,"{\"error\":\"unauth\"}"); return; }
            String method = ex.getRequestMethod(), path = ex.getRequestURI().getPath();
            if ("POST".equals(method)) {
                Map<String,Object> n = parseObj(body(ex));
                int id = NID.getAndIncrement();
                n.put("id",id);
                if (!n.containsKey("date")) n.put("date", LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")));
                NEWS.add(0,n); save();
                broadcast("news","{\"title\":\""+jesc(s(n,"title"))+"\",\"id\":"+id+"}");
                respondJson(ex,200,"{\"ok\":true,\"id\":"+id+"}");
            } else if ("PUT".equals(method)) {
                // Edit existing news safely
                String[] pts = path.split("/"); int id = toInt(pts[pts.length-1]);
                Map<String,Object> upd = parseObj(body(ex));
                upd.put("id", id); // preserve original id
                for (int idx = 0; idx < NEWS.size(); idx++) {
                    if (toInt(NEWS.get(idx).get("id")) == id) {
                        Map<String,Object> merged = new LinkedHashMap<>(NEWS.get(idx));
                        merged.putAll(upd);
                        NEWS.set(idx, merged);
                        break;
                    }
                }
                save(); respondJson(ex,200,"{\"ok\":true}");
            } else if ("DELETE".equals(method)) {
                String[] pts = path.split("/"); int id = toInt(pts[pts.length-1]);
                NEWS.removeIf(n -> toInt(n.get("id"))==id); save();
                respondJson(ex,200,"{\"ok\":true}");
            }
        });

        // ── ADMIN RESULT ────────────────────────────────
        srv.createContext("/api/admin/result", ex -> {
            cors(ex);
            if ("OPTIONS".equals(ex.getRequestMethod())) { respond(ex,200,"text/plain",""); return; }
            if (!auth(ex)) { respondJson(ex,401,"{\"error\":\"unauth\"}"); return; }
            String method = ex.getRequestMethod(), path = ex.getRequestURI().getPath();
            if ("POST".equals(method)) {
                Map<String,Object> m = parseObj(body(ex)); m.put("id",MID.getAndIncrement());
                MATCHES.add(0,m); recalc(); save(); respondJson(ex,200,"{\"ok\":true}");
            } else if ("DELETE".equals(method)) {
                String[] pts = path.split("/"); int id = toInt(pts[pts.length-1]);
                MATCHES.removeIf(m -> toInt(m.get("id"))==id); recalc(); save();
                respondJson(ex,200,"{\"ok\":true}");
            }
        });

        // ── ADMIN PLAYER ────────────────────────────────
        srv.createContext("/api/admin/player", ex -> {
            cors(ex);
            if ("OPTIONS".equals(ex.getRequestMethod())) { respond(ex,200,"text/plain",""); return; }
            if (!auth(ex)) { respondJson(ex,401,"{\"error\":\"unauth\"}"); return; }
            String method = ex.getRequestMethod(), path = ex.getRequestURI().getPath();
            if ("POST".equals(method)) {
                Map<String,Object> p = parseObj(body(ex));
                if(!p.containsKey("id")) p.put("id","p"+PID.getAndIncrement());
                PLAYERS.removeIf(x -> s(x,"id").equals(s(p,"id")));
                PLAYERS.add(p); save(); respondJson(ex,200,"{\"ok\":true}");
            } else if ("PUT".equals(method)) {
                String[] pts = path.split("/"); String id = pts[pts.length-1];
                Map<String,Object> upd = parseObj(body(ex));
                upd.put("id", id);
                for (int idx = 0; idx < PLAYERS.size(); idx++) {
                    if (s(PLAYERS.get(idx),"id").equals(id)) {
                        Map<String,Object> merged = new LinkedHashMap<>(PLAYERS.get(idx));
                        merged.putAll(upd);
                        PLAYERS.set(idx, merged);
                        break;
                    }
                }
                save(); respondJson(ex,200,"{\"ok\":true}");
            } else if ("DELETE".equals(method)) {
                String[] pts = path.split("/"); String id = pts[pts.length-1];
                PLAYERS.removeIf(p -> id.equals(s(p,"id"))); save();
                respondJson(ex,200,"{\"ok\":true}");
            }
        });

        // ── ADMIN APPLICATIONS ──────────────────────────
        srv.createContext("/api/admin/applications", ex -> {
            cors(ex);
            if ("OPTIONS".equals(ex.getRequestMethod())) { respond(ex,200,"text/plain",""); return; }
            if (!auth(ex)) { respondJson(ex,401,"{\"error\":\"unauth\"}"); return; }
            String method = ex.getRequestMethod(), path = ex.getRequestURI().getPath();
            if ("GET".equals(method)) {
                respondJson(ex,200,arrJson(APPLICATIONS));
            } else if ("PUT".equals(method)) {
                // Accept or reject
                String[] pts = path.split("/"); int id = toInt(pts[pts.length-2]);
                String action = pts[pts.length-1]; // "accept" or "reject"
                String newStatus = action.equals("accept") ? "accepted" : "rejected";
                for (int idx = 0; idx < APPLICATIONS.size(); idx++) {
                    if (toInt(APPLICATIONS.get(idx).get("id")) == id) {
                        Map<String,Object> updated = new LinkedHashMap<>(APPLICATIONS.get(idx));
                        updated.put("status", newStatus);
                        APPLICATIONS.set(idx, updated);
                        break;
                    }
                }
                save();
                broadcast("app_update","{\"id\":"+id+",\"status\":\""+action+"\"}");
                respondJson(ex,200,"{\"ok\":true}");
            } else if ("DELETE".equals(method)) {
                String[] pts = path.split("/"); int id = toInt(pts[pts.length-1]);
                APPLICATIONS.removeIf(a -> toInt(a.get("id"))==id); save();
                respondJson(ex,200,"{\"ok\":true}");
            }
        });

        // ── ADMIN USERS ─────────────────────────────────
        srv.createContext("/api/admin/users", ex -> {
            cors(ex);
            if (!auth(ex)) { respondJson(ex,401,"{\"error\":\"unauth\"}"); return; }
            respondJson(ex,200,arrJson(USERS));
        });

        // Thread pool
        try {
            Executor vt = (Executor) Executors.class.getMethod("newVirtualThreadPerTaskExecutor").invoke(null);
            srv.setExecutor(vt);
        } catch(Exception e) {
            srv.setExecutor(Executors.newFixedThreadPool(Math.max(4,Runtime.getRuntime().availableProcessors()*2)));
        }

        srv.start();
        P.println("[HTTP] ✅ http://localhost:" + PORT);
        P.println("[AUTH] Login: " + ADMIN_USER + " / " + ADMIN_PASS);
    }

    static void broadcast(String event, String data) {
        byte[] b = ("event:"+event+"\ndata:"+data+"\n\n").getBytes(StandardCharsets.UTF_8);
        List<HttpExchange> dead = new ArrayList<>();
        for(HttpExchange ex:SSE){ try{ ex.getResponseBody().write(b); ex.getResponseBody().flush(); }catch(Exception e){ dead.add(ex); } }
        SSE.removeAll(dead);
    }

    static void recalc() {
        LinkedHashMap<String,int[]> st=new LinkedHashMap<>();
        for(Map<String,Object> m:MATCHES){
            String t1=s(m,"team1"),t2=s(m,"team2"); int s1=toInt(m.get("score1")),s2=toInt(m.get("score2"));
            st.putIfAbsent(t1,new int[7]); st.putIfAbsent(t2,new int[7]);
            int[] a=st.get(t1),b=st.get(t2);
            a[0]++; b[0]++; a[4]+=s1; a[5]+=s2; b[4]+=s2; b[5]+=s1;
            if(s1>s2){a[1]++;a[6]+=3;b[3]++;}else if(s1<s2){b[1]++;b[6]+=3;a[3]++;}else{a[2]++;b[2]++;a[6]++;b[6]++;}
        }
        TABLE.clear();
        st.entrySet().stream().sorted((x,y)->Integer.compare(y.getValue()[6],x.getValue()[6])).forEach(e->{
            int[] v=e.getValue(); Map<String,Object> r=new LinkedHashMap<>();
            r.put("name",e.getKey());r.put("played",v[0]);r.put("won",v[1]);r.put("draw",v[2]);r.put("lost",v[3]);r.put("gf",v[4]);r.put("ga",v[5]);r.put("pts",v[6]);
            TABLE.add(r);
        });
    }

    static void save() {
        try {
            write(DATA_DIR+"/news.json",    arrJson(NEWS));
            write(DATA_DIR+"/matches.json", arrJson(MATCHES));
            write(DATA_DIR+"/players.json", arrJson(PLAYERS));
            write(DATA_DIR+"/users.json",   arrJson(USERS));
            write(DATA_DIR+"/apps.json",    arrJson(APPLICATIONS));
        } catch(Exception e){ P.println("[SAVE] "+e.getMessage()); }
    }

    static void load() {
        try{ loadFile(DATA_DIR+"/news.json",    NEWS);    }catch(Exception ignored){}
        try{ loadFile(DATA_DIR+"/matches.json", MATCHES); recalc(); }catch(Exception ignored){}
        try{ loadFile(DATA_DIR+"/players.json", PLAYERS); }catch(Exception ignored){}
        try{ loadFile(DATA_DIR+"/users.json",   USERS);   }catch(Exception ignored){}
        try{ loadFile(DATA_DIR+"/apps.json",    APPLICATIONS); }catch(Exception ignored){}
        NEWS.forEach(n->{int id=toInt(n.get("id"));if(id>=NID.get())NID.set(id+1);});
        MATCHES.forEach(m->{int id=toInt(m.get("id"));if(id>=MID.get())MID.set(id+1);});
        APPLICATIONS.forEach(a->{int id=toInt(a.get("id"));if(id>=AID.get())AID.set(id+1);});
        P.println("[LOAD] news="+NEWS.size()+" matches="+MATCHES.size()+" players="+PLAYERS.size()+" users="+USERS.size()+" apps="+APPLICATIONS.size());
    }

    static void loadFile(String path, List<Map<String,Object>> list) throws Exception {
        String txt=Files.readString(Path.of(path),StandardCharsets.UTF_8).trim();
        if(!txt.startsWith("[")||txt.length()<2) return;
        String inner=txt.substring(1,txt.length()-1).trim();
        if(inner.isEmpty()) return;
        for(String chunk:topObjects(inner)){ Map<String,Object> m=parseObj(chunk); if(!m.isEmpty()) list.add(m); }
    }

    static void seedIfEmpty() {
        if(NEWS.isEmpty()){
            news("🏆 Новый сезон начался!","Psycho League открывает новый турнирный сезон. Записывайтесь!","АНОНС","PaNdA",null,null);
            news("⚡ Рекорд лиги!","240,712 голов — абсолютный рекорд Psycho League!","РЕКОРД","PaNdA",null,null);
            news("👥 185 участников","Обе лиги суммарно насчитывают 185 активных игроков!","НОВОСТЬ","Psycho",null,null);
        }
        if(MATCHES.isEmpty()){
            match("PaNdA","Psycho",5,2,"20.04.2026"); match("Zenith","Kaiser",3,3,"21.04.2026");
            match("Titan","PaNdA",1,4,"22.04.2026");  match("Psycho","Titan",6,0,"23.04.2026");
            match("Kaiser","Zenith",2,1,"24.04.2026"); recalc();
        }
        save();
    }
    static void news(String title,String body,String cat,String author,String img,String video){
        Map<String,Object> n=new LinkedHashMap<>();
        n.put("id",NID.getAndIncrement());n.put("title",title);n.put("body",body);
        n.put("category",cat);n.put("author",author);
        n.put("date",LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")));
        if(img!=null) n.put("img",img);
        if(video!=null) n.put("video",video);
        NEWS.add(n);
    }
    static void match(String t1,String t2,int s1,int s2,String date){
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("id",MID.getAndIncrement());m.put("team1",t1);m.put("team2",t2);m.put("score1",s1);m.put("score2",s2);m.put("date",date);
        MATCHES.add(m);
    }

    // ── HTTP HELPERS ────────────────────────────────────
    static void respond(HttpExchange ex,int code,String ct,String body) throws IOException { respond(ex,code,ct,body.getBytes(StandardCharsets.UTF_8)); }
    static void respond(HttpExchange ex,int code,String ct,byte[] body) throws IOException {
        Headers h=ex.getResponseHeaders();
        h.set("Content-Type",ct);h.set("Access-Control-Allow-Origin","*");
        h.set("Access-Control-Allow-Headers","Content-Type,Authorization");
        h.set("Access-Control-Allow-Methods","GET,POST,PUT,DELETE,OPTIONS");
        ex.sendResponseHeaders(code,body.length);
        try(OutputStream os=ex.getResponseBody()){os.write(body);}
    }
    static void respondJson(HttpExchange ex,int code,String json) throws IOException { respond(ex,code,"application/json; charset=utf-8",json); }
    static void cors(HttpExchange ex){ ex.getResponseHeaders().set("Access-Control-Allow-Origin","*"); ex.getResponseHeaders().set("Access-Control-Allow-Headers","Content-Type,Authorization"); ex.getResponseHeaders().set("Access-Control-Allow-Methods","GET,POST,PUT,DELETE,OPTIONS"); }
    static boolean auth(HttpExchange ex){ String tok=ex.getRequestHeaders().getFirst("Authorization"); if(tok==null||tok.isEmpty()) return false; Long ts=TOKENS.get(tok); if(ts==null) return false; if(System.currentTimeMillis()-ts>86_400_000L){TOKENS.remove(tok);return false;} return true; }
    static String body(HttpExchange ex) throws IOException { return new String(ex.getRequestBody().readAllBytes(),StandardCharsets.UTF_8); }
    static String mime(String p){ if(p.endsWith(".html"))return "text/html; charset=utf-8"; if(p.endsWith(".css"))return "text/css"; if(p.endsWith(".js"))return "application/javascript"; if(p.endsWith(".png"))return "image/png"; if(p.endsWith(".jpg")||p.endsWith(".jpeg"))return "image/jpeg"; if(p.endsWith(".gif"))return "image/gif"; if(p.endsWith(".mp4"))return "video/mp4"; if(p.endsWith(".svg"))return "image/svg+xml"; if(p.endsWith(".ico"))return "image/x-icon"; return "application/octet-stream"; }

    // ── JSON ────────────────────────────────────────────
    static String arrJson(List<Map<String,Object>> list){ return "["+list.stream().map(Server::objJson).collect(Collectors.joining(","))+"]"; }
    @SuppressWarnings("unchecked")
    static String objJson(Map<String,Object> map){
        StringBuilder sb=new StringBuilder("{"); boolean first=true;
        for(var e:map.entrySet()){ if(!first)sb.append(","); first=false; sb.append("\"").append(jesc(e.getKey())).append("\":"); Object v=e.getValue();
            if(v==null)sb.append("null"); else if(v instanceof Boolean)sb.append(v); else if(v instanceof Number)sb.append(v);
            else if(v instanceof List){ sb.append("["); boolean f2=true; for(Object item:(List<?>)v){ if(!f2)sb.append(","); f2=false;
                if(item instanceof List)sb.append(innerArr((List<?>)item)); else if(item instanceof Map)sb.append(objJson((Map<String,Object>)item)); else if(item instanceof Number)sb.append(item); else sb.append("\"").append(jesc(String.valueOf(item))).append("\""); } sb.append("]"); }
            else sb.append("\"").append(jesc(String.valueOf(v))).append("\""); }
        return sb.append("}").toString();
    }
    static String innerArr(List<?> list){ StringBuilder sb=new StringBuilder("["); boolean f=true; for(Object x:list){if(!f)sb.append(",");f=false;if(x instanceof Number)sb.append(x);else sb.append("\"").append(jesc(String.valueOf(x))).append("\"");} return sb.append("]").toString(); }
    static Map<String,Object> parseObj(String raw){
        Map<String,Object> map=new LinkedHashMap<>(); raw=raw.trim();
        if(!raw.startsWith("{"))return map; raw=raw.substring(1,raw.length()-1); int i=0,len=raw.length();
        while(i<len){ while(i<len&&" \t\n\r,".indexOf(raw.charAt(i))>=0)i++; if(i>=len||raw.charAt(i)!='"')break;
            int ks=i+1,ke=ks; while(ke<len&&raw.charAt(ke)!='"')ke++; String key=raw.substring(ks,ke); i=ke+1;
            while(i<len&&raw.charAt(i)!=':')i++; i++; while(i<len&&" \t".indexOf(raw.charAt(i))>=0)i++;
            char c=raw.charAt(i);
            if(c=='"'){int vs=i+1,ve=vs; while(ve<len){if(raw.charAt(ve)=='\\'){ ve+=2;continue;}if(raw.charAt(ve)=='"')break;ve++;} map.put(key,unesc(raw.substring(vs,ve)));i=ve+1;}
            else if(c=='{'){int e=mc(raw,i,'{','}');map.put(key,parseObj(raw.substring(i,e+1)));i=e+1;}
            else if(c=='['){int e=mc(raw,i,'[',']');map.put(key,parseArr(raw.substring(i,e+1)));i=e+1;}
            else if(c=='t'){map.put(key,true);i+=4;}else if(c=='f'){map.put(key,false);i+=5;}else if(c=='n'){map.put(key,null);i+=4;}
            else{int ne=i;while(ne<len&&",}]".indexOf(raw.charAt(ne))<0)ne++;String ns=raw.substring(i,ne).trim();try{map.put(key,Integer.parseInt(ns));}catch(NumberFormatException ex2){try{map.put(key,Long.parseLong(ns));}catch(NumberFormatException ex3){try{map.put(key,Double.parseDouble(ns));}catch(NumberFormatException ex4){map.put(key,ns);}}}i=ne;} }
        return map;
    }
    static List<Object> parseArr(String raw){
        List<Object> list=new ArrayList<>(); raw=raw.trim(); if(!raw.startsWith("["))return list; raw=raw.substring(1,raw.length()-1).trim(); if(raw.isEmpty())return list;
        int i=0,len=raw.length();
        while(i<len){ while(i<len&&" ,\t\n\r".indexOf(raw.charAt(i))>=0)i++; if(i>=len)break; char c=raw.charAt(i);
            if(c=='"'){int vs=i+1,ve=vs;while(ve<len){if(raw.charAt(ve)=='\\'){ ve+=2;continue;}if(raw.charAt(ve)=='"')break;ve++;}list.add(unesc(raw.substring(vs,ve)));i=ve+1;}
            else if(c=='['){int e=mc(raw,i,'[',']');list.add(parseArr(raw.substring(i,e+1)));i=e+1;}
            else if(c=='{'){int e=mc(raw,i,'{','}');list.add(parseObj(raw.substring(i,e+1)));i=e+1;}
            else{int ne=i;while(ne<len&&",]".indexOf(raw.charAt(ne))<0)ne++;String ns=raw.substring(i,ne).trim();if(ns.equals("true"))list.add(true);else if(ns.equals("false"))list.add(false);else if(ns.equals("null"))list.add(null);else try{list.add(Integer.parseInt(ns));}catch(NumberFormatException e){try{list.add(Long.parseLong(ns));}catch(NumberFormatException e2){list.add(ns);}}i=ne;} }
        return list;
    }
    static int mc(String s,int start,char open,char close){int d=0;boolean inStr=false;for(int i=start;i<s.length();i++){char c=s.charAt(i);if(!inStr){if(c==open)d++;else if(c==close){if(--d==0)return i;}else if(c=='"')inStr=true;}else{if(c=='\\')i++;else if(c=='"')inStr=false;}}return s.length()-1;}
    static List<String> topObjects(String s){List<String> list=new ArrayList<>();int i=0,d=0,start=-1;boolean inStr=false;while(i<s.length()){char c=s.charAt(i);if(!inStr){if(c=='{'){if(d++==0)start=i;}else if(c=='}'){if(--d==0&&start>=0){list.add(s.substring(start,i+1));start=-1;}}else if(c=='"')inStr=true;}else{if(c=='\\')i++;else if(c=='"')inStr=false;}i++;}return list;}
    static String strVal(String json,String key){String n="\""+key+"\":\"";int s=json.indexOf(n);if(s<0)return "";s+=n.length();int e=s;while(e<json.length()){if(json.charAt(e)=='\\'){ e+=2;continue;}if(json.charAt(e)=='"')break;e++;}return json.substring(s,e);}
    static String s(Map<String,Object> m,String k){Object v=m.get(k);return v==null?"":String.valueOf(v);}
    static int toInt(Object o){if(o==null)return -1;if(o instanceof Number)return((Number)o).intValue();try{return Integer.parseInt(String.valueOf(o).trim());}catch(Exception e){return -1;}}
    static int toInt(String s){try{return Integer.parseInt(s.trim());}catch(Exception e){return -1;}}
    static String jesc(String s){if(s==null)return "";return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t");}
    static String unesc(String s){if(!s.contains("\\"))return s;StringBuilder sb=new StringBuilder();for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='\\'&&i+1<s.length()){char n=s.charAt(++i);if(n=='n')sb.append('\n');else if(n=='r')sb.append('\r');else if(n=='t')sb.append('\t');else sb.append(n);}else sb.append(c);}return sb.toString();}
    static void write(String path,String content) throws IOException{Files.writeString(Path.of(path),content,StandardCharsets.UTF_8);}
    static int indexOf(byte[] data,byte[] pattern){outer:for(int i=0;i<=data.length-pattern.length;i++){for(int j=0;j<pattern.length;j++)if(data[i+j]!=pattern[j])continue outer;return i;}return -1;}
    static int lastIndexOf(byte[] data,byte[] pattern){outer:for(int i=data.length-pattern.length;i>=0;i--){for(int j=0;j<pattern.length;j++)if(data[i+j]!=pattern[j])continue outer;return i;}return -1;}
}
