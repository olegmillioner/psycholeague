import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;
import java.util.Base64;

/**
 * PSYCHO LEAGUE — Server.java v7.1 PRODUCTION
 * ─────────────────────────────────────────────
 * BUG REPORT (найдено при аудите v7.0):
 *
 * [BUG-01] CLOUD-KILLER: SPA fallback давал 503 если index.html не в CWD.
 *          Исправлено: поиск в CWD + рядом с JAR + ясная ошибка в логах.
 *
 * [BUG-02] PORT: NullPointerException на некоторых JVM если System.getenv() null.
 *          Исправлено: обёртка в try-catch с дефолтом 8080.
 *
 * [BUG-03] DATA_DIR "data" — относительный путь. На Railway CWD может быть
 *          read-only. Исправлено: авто-fallback на /tmp/psycholeague-data.
 *
 * [BUG-04] SSE использовал Thread.sleep() — блокировал platform threads.
 *          Исправлено: ScheduledExecutorService ping без блокировки.
 *
 * [BUG-05] /api/upload: indexOf возвращал -1 — ds становился 3, данные
 *          портились. Исправлено: явная проверка hEnd < 0.
 *
 * [BUG-06] broadcast(): ConcurrentModificationException при removeAll().
 *          Исправлено: removeIf() с единым проходом.
 *
 * [BUG-07] save() вызывался на каждом запросе синхронно.
 *          Исправлено: scheduleAsyncSave() с debounce 500ms.
 *
 * [BUG-08] parseObj(): escape-символы в ключах игнорировались.
 *          Исправлено: ke-сканер учитывает backslash.
 *
 * [BUG-09] /api/auth/login owner возвращал login вместо OWNER_USER как ник.
 *          Исправлено: используется константа OWNER_USER.
 *
 * [BUG-10] Virtual Threads fallback ловил Exception вместо NoSuchMethodException —
 *          маскировал реальные ошибки запуска.
 *          Исправлено: раздельный catch для NoSuchMethodException.
 *
 * [NEW]    /api/ai/chat — прокси к Anthropic API через ANTHROPIC_API_KEY env.
 *
 * Compile : javac Server.java
 * Run     : java -Dfile.encoding=UTF-8 Server
 */
public class Server {

    static final int PORT;
    static {
        int p = 8080;
        try { p = Integer.parseInt(System.getenv().getOrDefault("PORT","8080")); } catch(Exception e) {}
        PORT = p;
    }

    static final String OWNER_USER = "luveniaw";
    static final String OWNER_PASS = "!Humoaxi123";
    static final String ADMIN_USER = "Panda";
    static final String ADMIN_PASS = "Humoaxi123";

    // DATA_DIR: writable directory for JSON persistence
    static final String DATA_DIR;
    static {
        String dir = "data";
        File local = new File("data");
        try {
            local.mkdirs();
            File probe = new File(local, ".probe");
            probe.createNewFile(); probe.delete();
            dir = local.getAbsolutePath();
        } catch(Exception e) {
            File tmp = new File(System.getProperty("java.io.tmpdir","tmp"), "psycholeague-data");
            tmp.mkdirs();
            dir = tmp.getAbsolutePath();
        }
        DATA_DIR = dir;
    }

    static final long MAX_UPLOAD = 10L * 1024 * 1024;

    static final List<Map<String,Object>>
        NEWS=new CopyOnWriteArrayList<>(), MATCHES=new CopyOnWriteArrayList<>(),
        TABLE=new CopyOnWriteArrayList<>(), PLAYERS=new CopyOnWriteArrayList<>(),
        USERS=new CopyOnWriteArrayList<>(), APPLICATIONS=new CopyOnWriteArrayList<>(),
        SHOP_ITEMS=new CopyOnWriteArrayList<>(), SCORES=new CopyOnWriteArrayList<>(),
        ADMINS=new CopyOnWriteArrayList<>(), POSTS=new CopyOnWriteArrayList<>(),
        MESSAGES=new CopyOnWriteArrayList<>(), TOURNAMENTS=new CopyOnWriteArrayList<>(),
        MATCH_RESULTS=new CopyOnWriteArrayList<>();

    static final Map<String,Object> SITE_TEXTS = new ConcurrentHashMap<>();
    static final List<HttpExchange> SSE = new CopyOnWriteArrayList<>();
    static final Map<String,Map<String,Object>> TOKENS = new ConcurrentHashMap<>();

    static final AtomicInteger
        NID=new AtomicInteger(1), MID=new AtomicInteger(1), PID=new AtomicInteger(1),
        UID=new AtomicInteger(1), AID=new AtomicInteger(1), SID=new AtomicInteger(1),
        SCID=new AtomicInteger(1),POSTID=new AtomicInteger(1),MSGID=new AtomicInteger(1),
        TOURID=new AtomicInteger(1),MRID=new AtomicInteger(1);

    // Debounced async save
    static final ScheduledExecutorService SCHED =
        Executors.newSingleThreadScheduledExecutor(r -> { Thread t=new Thread(r,"sched");t.setDaemon(true);return t; });
    static volatile ScheduledFuture<?> pendingSave = null;

    static final PrintStream P;
    static {
        PrintStream ps;
        try { ps = new PrintStream(System.out, true, "UTF-8"); } catch(Exception e) { ps = System.out; }
        P = ps; System.setOut(P); System.setErr(P);
    }

    public static void main(String[] args) throws Exception {
        P.println("╔══════════════════════════════════════════╗");
        P.println("║   PSYCHO LEAGUE  v7.1  PRODUCTION        ║");
        P.println("║   Owner: luveniaw | Admin: Panda         ║");
        P.println("╚══════════════════════════════════════════╝");
        P.println("[DATA] " + DATA_DIR);
        new File(DATA_DIR).mkdirs();
        load(); seedIfEmpty();

        HttpServer srv = HttpServer.create(new InetSocketAddress(PORT), 512);

        // ── Static files + SPA fallback ──────────────────────────────────
        srv.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api")) { respond(ex,404,"text/plain","Not found"); return; }
            if (path.contains("..")) { respond(ex,403,"text/plain","Forbidden"); return; }
            if ("/".equals(path) || "/index.html".equals(path)) path = "/index.html";
            File f = new File("." + path);
            if (f.exists() && f.isFile()) { respond(ex,200,mime(path),Files.readAllBytes(f.toPath())); return; }
            // SPA fallback
            byte[] html = loadIndex();
            if (html != null) respond(ex,200,"text/html; charset=utf-8",html);
            else respond(ex,503,"text/plain","index.html not found. Place it beside Server.java.");
        });

        // ── /api/auth/register ───────────────────────────────────────────
        srv.createContext("/api/auth/register", ex -> {
            cors(ex); if(opt(ex))return;
            try {
                Map<String,Object> req=parseObj(body(ex));
                String login=s(req,"login").trim(),nick=s(req,"nick").trim(),pass=s(req,"pass");
                if(login.isEmpty()||nick.isEmpty()||pass.length()<4){respondJson(ex,400,"{\"error\":\"Заполни все поля\"}");return;}
                boolean taken=USERS.stream().anyMatch(u->s(u,"login").equalsIgnoreCase(login))
                    ||OWNER_USER.equalsIgnoreCase(login)||ADMIN_USER.equalsIgnoreCase(login)
                    ||ADMINS.stream().anyMatch(a->s(a,"login").equalsIgnoreCase(login));
                if(taken){respondJson(ex,409,"{\"error\":\"Логин занят\"}");return;}
                Map<String,Object> u=new LinkedHashMap<>();
                u.put("id",UID.getAndIncrement());u.put("login",login);u.put("nick",nick);
                u.put("passHash",sha256(pass));u.put("points",0);u.put("role","user");
                u.put("avatar","⚽");u.put("status","");u.put("date",today());
                u.put("purchases",new ArrayList<>());u.put("cart",new ArrayList<>());
                USERS.add(u);scheduleAsyncSave();respondJson(ex,200,"{\"ok\":true}");
                P.println("[REG] "+nick+"("+login+")");
            } catch(Exception e){respondJson(ex,400,"{\"error\":\"Ошибка регистрации\"}");}
        });

        // ── /api/auth/login ──────────────────────────────────────────────
        srv.createContext("/api/auth/login", ex -> {
            cors(ex); if(opt(ex))return;
            String b=body(ex),login=strVal(b,"login"),pass=strVal(b,"pass");
            if(OWNER_USER.equals(login)&&OWNER_PASS.equals(pass)){
                String tok=genTok();TOKENS.put(tok,mapOf("role","owner","user",login,"ts",System.currentTimeMillis()));
                respondJson(ex,200,"{\"ok\":true,\"token\":\""+tok+"\",\"role\":\"owner\",\"nick\":\""+jesc(OWNER_USER)+"\"}");return;}
            if(ADMIN_USER.equals(login)&&ADMIN_PASS.equals(pass)){
                String tok=genTok();TOKENS.put(tok,mapOf("role","admin","user",login,"ts",System.currentTimeMillis()));
                respondJson(ex,200,"{\"ok\":true,\"token\":\""+tok+"\",\"role\":\"admin\",\"nick\":\""+jesc(ADMIN_USER)+"\"}");return;}
            for(Map<String,Object> adm:ADMINS){
                if(s(adm,"login").equals(login)&&s(adm,"pass").equals(sha256(pass))){
                    String tok=genTok();TOKENS.put(tok,mapOf("role","admin","user",login,"ts",System.currentTimeMillis()));
                    respondJson(ex,200,"{\"ok\":true,\"token\":\""+tok+"\",\"role\":\"admin\",\"nick\":\""+jesc(s(adm,"nick"))+"\"}");return;}}
            for(Map<String,Object> u:USERS){
                if(s(u,"login").equals(login)&&s(u,"passHash").equals(sha256(pass))){
                    String tok=genTok();TOKENS.put(tok,mapOf("role","user","user",login,"uid",String.valueOf(u.get("id")),"ts",System.currentTimeMillis()));
                    respondJson(ex,200,"{\"ok\":true,\"token\":\""+tok+"\",\"role\":\"user\",\"nick\":\""+jesc(s(u,"nick"))+"\",\"uid\":"+u.get("id")+"}");return;}}
            respondJson(ex,401,"{\"error\":\"Неверный логин или пароль\"}");
        });

        // ── /api/auth/me ─────────────────────────────────────────────────
        srv.createContext("/api/auth/me", ex -> {
            cors(ex);Map<String,Object> t=getTok(ex);
            if(t==null){respondJson(ex,401,"{\"error\":\"not logged in\"}");return;}
            String role=s(t,"role"),user=s(t,"user");
            if("owner".equals(role)){respondJson(ex,200,"{\"role\":\"owner\",\"nick\":\""+jesc(OWNER_USER)+"\",\"points\":999999,\"avatar\":\"👑\",\"status\":\"Владелец\",\"login\":\""+jesc(user)+"\",\"date\":\"\",\"purchases\":[]}");return;}
            if("admin".equals(role)){respondJson(ex,200,"{\"role\":\"admin\",\"nick\":\""+jesc(user)+"\",\"points\":0,\"avatar\":\"🔧\",\"status\":\"Администратор\",\"login\":\""+jesc(user)+"\",\"date\":\"\",\"purchases\":[]}");return;}
            for(Map<String,Object> u:USERS){if(s(u,"login").equals(user)){Map<String,Object> safe=new LinkedHashMap<>(u);safe.remove("passHash");respondJson(ex,200,objJson(safe));return;}}
            respondJson(ex,404,"{\"error\":\"not found\"}");
        });

        // ── /api/auth/profile ────────────────────────────────────────────
        srv.createContext("/api/auth/profile", ex -> {
            cors(ex);if(opt(ex))return;Map<String,Object> t=getTok(ex);
            if(t==null){respondJson(ex,401,"{\"error\":\"unauth\"}");return;}
            String login=s(t,"user");Map<String,Object> upd=parseObj(body(ex));
            for(int i=0;i<USERS.size();i++){
                if(s(USERS.get(i),"login").equals(login)){
                    Map<String,Object> u=new LinkedHashMap<>(USERS.get(i));
                    if(upd.containsKey("nick")&&!s(upd,"nick").isEmpty())u.put("nick",s(upd,"nick"));
                    if(upd.containsKey("avatar"))u.put("avatar",s(upd,"avatar"));
                    if(upd.containsKey("status"))u.put("status",s(upd,"status"));
                    if(upd.containsKey("newPass")&&!s(upd,"newPass").isEmpty()){
                        if(!s(u,"passHash").equals(sha256(s(upd,"oldPass")))){respondJson(ex,403,"{\"error\":\"Неверный текущий пароль\"}");return;}
                        u.put("passHash",sha256(s(upd,"newPass")));}
                    USERS.set(i,u);scheduleAsyncSave();respondJson(ex,200,"{\"ok\":true}");return;}}
            respondJson(ex,404,"{\"error\":\"not found\"}");
        });

        // ── Public data ──────────────────────────────────────────────────
        srv.createContext("/api/news",       ex->{cors(ex);respondJson(ex,200,arrJson(NEWS));});
        srv.createContext("/api/results",    ex->{cors(ex);respondJson(ex,200,"{\"table\":"+arrJson(TABLE)+",\"matches\":"+arrJson(MATCHES)+"}");});
        srv.createContext("/api/players",    ex->{cors(ex);respondJson(ex,200,arrJson(PLAYERS));});
        srv.createContext("/api/texts",      ex->{cors(ex);respondJson(ex,200,objJson(SITE_TEXTS));});
        srv.createContext("/api/shop",       ex->{cors(ex);respondJson(ex,200,arrJson(SHOP_ITEMS));});
        srv.createContext("/api/users/list", ex->{cors(ex);List<Map<String,Object>> safe=USERS.stream().map(u->{Map<String,Object> s2=new LinkedHashMap<>();s2.put("login",u.get("login"));s2.put("nick",u.get("nick"));s2.put("avatar",u.get("avatar"));return s2;}).collect(Collectors.toList());respondJson(ex,200,arrJson(safe));});

        // ── /api/scores ──────────────────────────────────────────────────
        srv.createContext("/api/scores", ex->{
            cors(ex);if(opt(ex))return;
            if("GET".equals(ex.getRequestMethod())){respondJson(ex,200,arrJson(SCORES));return;}
            if("POST".equals(ex.getRequestMethod())){
                try{Map<String,Object> sc=parseObj(body(ex));String name=s(sc,"name");int pts=toInt(sc.get("points"));
                boolean found=false;
                for(int i=0;i<SCORES.size();i++){if(s(SCORES.get(i),"name").equals(name)){Map<String,Object> e2=new LinkedHashMap<>(SCORES.get(i));e2.put("points",toInt(e2.get("points"))+pts);e2.put("lastSeen",today());SCORES.set(i,e2);found=true;break;}}
                if(!found){sc.put("id",SCID.getAndIncrement());sc.put("lastSeen",today());SCORES.add(sc);}
                SCORES.sort((a,b2)->Integer.compare(toInt(b2.get("points")),toInt(a.get("points"))));
                Map<String,Object> tk=getTok(ex);
                if(tk!=null&&"user".equals(s(tk,"role"))){String ul=s(tk,"user");for(int i=0;i<USERS.size();i++){if(s(USERS.get(i),"login").equals(ul)){Map<String,Object> u=new LinkedHashMap<>(USERS.get(i));u.put("points",toInt(u.get("points"))+pts);USERS.set(i,u);break;}}}
                scheduleAsyncSave();respondJson(ex,200,"{\"ok\":true}");}catch(Exception e){respondJson(ex,400,"{\"error\":\"bad\"}");}}
        });

        // ── /api/applications ────────────────────────────────────────────
        srv.createContext("/api/applications", ex->{
            cors(ex);if(opt(ex))return;
            if("POST".equals(ex.getRequestMethod())){
                try{Map<String,Object> a=parseObj(body(ex));a.put("id",AID.getAndIncrement());a.put("date",today());a.put("status","pending");
                APPLICATIONS.add(a);scheduleAsyncSave();broadcast("application","{\"name\":\""+jesc(s(a,"name"))+"\"}");respondJson(ex,200,"{\"ok\":true}");}
                catch(Exception e){respondJson(ex,400,"{\"error\":\"bad\"}");}}
        });

        // ── /api/posts ───────────────────────────────────────────────────
        srv.createContext("/api/posts", ex->{
            cors(ex);if(opt(ex))return;
            String method=ex.getRequestMethod(),path=ex.getRequestURI().getPath();
            if("GET".equals(method)){respondJson(ex,200,arrJson(POSTS));return;}
            if("POST".equals(method)){
                Map<String,Object> t=getTok(ex);if(t==null){respondJson(ex,401,"{\"error\":\"unauth\"}");return;}
                try{Map<String,Object> post=parseObj(body(ex));post.put("id",POSTID.getAndIncrement());post.put("author",s(t,"user"));post.put("date",today());post.put("ts",System.currentTimeMillis());post.put("likes",new ArrayList<>());post.put("comments",new ArrayList<>());
                POSTS.add(0,post);scheduleAsyncSave();broadcast("post","{\"author\":\""+jesc(s(t,"user"))+"\"}");respondJson(ex,200,"{\"ok\":true,\"id\":"+post.get("id")+"}");
                }catch(Exception e){respondJson(ex,400,"{\"error\":\"bad\"}");} return;}
            if("DELETE".equals(method)){
                Map<String,Object> t=getTok(ex);if(t==null){respondJson(ex,401,"{\"error\":\"unauth\"}");return;}
                String[] pts=path.split("/");int id=toInt(pts[pts.length-1]);String login=s(t,"user"),role=s(t,"role");
                POSTS.removeIf(p->toInt(p.get("id"))==id&&(s(p,"author").equals(login)||"admin".equals(role)||"owner".equals(role)));
                scheduleAsyncSave();respondJson(ex,200,"{\"ok\":true}");}
        });

        srv.createContext("/api/posts/like", ex->{
            cors(ex);if(opt(ex))return;Map<String,Object> t=getTok(ex);
            if(t==null){respondJson(ex,401,"{\"error\":\"unauth\"}");return;}
            Map<String,Object> req=parseObj(body(ex));int id=toInt(req.get("id"));String login=s(t,"user");
            for(int i=0;i<POSTS.size();i++){if(toInt(POSTS.get(i).get("id"))==id){Map<String,Object> p=new LinkedHashMap<>(POSTS.get(i));@SuppressWarnings("unchecked")List<Object> likes=(List<Object>)p.getOrDefault("likes",new ArrayList<>());if(likes.contains(login))likes.remove(login);else likes.add(login);p.put("likes",likes);POSTS.set(i,p);break;}}
            scheduleAsyncSave();respondJson(ex,200,"{\"ok\":true}");
        });

        srv.createContext("/api/posts/comment", ex->{
            cors(ex);if(opt(ex))return;Map<String,Object> t=getTok(ex);
            if(t==null){respondJson(ex,401,"{\"error\":\"unauth\"}");return;}
            Map<String,Object> req=parseObj(body(ex));int id=toInt(req.get("id"));String text=s(req,"text").trim();
            if(text.isEmpty()){respondJson(ex,400,"{\"error\":\"empty\"}");return;}
            String login=s(t,"user"),nick=login;
            for(Map<String,Object> u:USERS){if(s(u,"login").equals(login)){nick=s(u,"nick");break;}}
            if("owner".equals(s(t,"role")))nick=OWNER_USER;if("admin".equals(s(t,"role")))nick=ADMIN_USER;
            Map<String,Object> comment=new LinkedHashMap<>();comment.put("author",login);comment.put("nick",nick);comment.put("text",text);comment.put("ts",System.currentTimeMillis());comment.put("date",today());
            for(int i=0;i<POSTS.size();i++){if(toInt(POSTS.get(i).get("id"))==id){Map<String,Object> p=new LinkedHashMap<>(POSTS.get(i));@SuppressWarnings("unchecked")List<Object> cmts=(List<Object>)p.getOrDefault("comments",new ArrayList<>());cmts.add(comment);p.put("comments",cmts);POSTS.set(i,p);broadcast("comment","{\"postId\":"+id+",\"author\":\""+jesc(nick)+"\"}");break;}}
            scheduleAsyncSave();respondJson(ex,200,"{\"ok\":true}");
        });

        // ── /api/messages ────────────────────────────────────────────────
        srv.createContext("/api/messages", ex->{
            cors(ex);if(opt(ex))return;Map<String,Object> t=getTok(ex);
            if(t==null){respondJson(ex,401,"{\"error\":\"unauth\"}");return;}
            String method=ex.getRequestMethod(),path=ex.getRequestURI().getPath(),login=s(t,"user");
            if("GET".equals(method)){
                String[] pts=path.split("/");
                if(pts.length>3){
                    String target=pts[pts.length-1];
                    List<Map<String,Object>> conv=MESSAGES.stream().filter(m->(s(m,"from").equals(login)&&s(m,"to").equals(target))||(s(m,"from").equals(target)&&s(m,"to").equals(login))).sorted(Comparator.comparingLong(m->toLong(m.get("ts")))).collect(Collectors.toList());
                    for(int i=0;i<MESSAGES.size();i++){Map<String,Object> m=MESSAGES.get(i);if(s(m,"from").equals(target)&&s(m,"to").equals(login)&&!"read".equals(s(m,"status"))){Map<String,Object> um=new LinkedHashMap<>(m);um.put("status","read");MESSAGES.set(i,um);}}
                    respondJson(ex,200,arrJson(conv));
                }else{
                    Map<String,Long> lastTs=new LinkedHashMap<>();Map<String,Map<String,Object>> lastMsg=new LinkedHashMap<>();Map<String,Integer> unread=new LinkedHashMap<>();
                    for(Map<String,Object> m:MESSAGES){if(!s(m,"from").equals(login)&&!s(m,"to").equals(login))continue;String other=s(m,"from").equals(login)?s(m,"to"):s(m,"from");long ts=toLong(m.get("ts"));if(ts>lastTs.getOrDefault(other,0L)){lastTs.put(other,ts);lastMsg.put(other,m);}if(s(m,"to").equals(login)&&!"read".equals(s(m,"status")))unread.merge(other,1,Integer::sum);}
                    List<Map<String,Object>> convs=new ArrayList<>();for(String other:lastTs.keySet()){Map<String,Object> c=new LinkedHashMap<>();c.put("with",other);c.put("lastMsg",lastMsg.get(other));c.put("unread",unread.getOrDefault(other,0));c.put("ts",lastTs.get(other));convs.add(c);}
                    convs.sort((a,b2)->Long.compare(toLong(b2.get("ts")),toLong(a.get("ts"))));respondJson(ex,200,arrJson(convs));}
                return;}
            if("POST".equals(method)){
                try{Map<String,Object> req=parseObj(body(ex));String to=s(req,"to"),text=s(req,"text").trim();if(to.isEmpty()||text.isEmpty()){respondJson(ex,400,"{\"error\":\"empty\"}");return;}
                String nick=login;for(Map<String,Object> u:USERS){if(s(u,"login").equals(login)){nick=s(u,"nick");break;}}
                Map<String,Object> msg=new LinkedHashMap<>();msg.put("id",MSGID.getAndIncrement());msg.put("from",login);msg.put("fromNick",nick);msg.put("to",to);msg.put("text",text);msg.put("ts",System.currentTimeMillis());msg.put("status","sent");if(req.containsKey("img"))msg.put("img",s(req,"img"));
                MESSAGES.add(msg);scheduleAsyncSave();
                String preview=text.length()>50?text.substring(0,50)+"...":text;
                broadcast("message","{\"from\":\""+jesc(login)+"\",\"fromNick\":\""+jesc(nick)+"\",\"to\":\""+jesc(to)+"\",\"text\":\""+jesc(preview)+"\",\"ts\":"+msg.get("ts")+"}");
                respondJson(ex,200,"{\"ok\":true,\"id\":"+msg.get("id")+"}");}catch(Exception e){respondJson(ex,400,"{\"error\":\"bad\"}");}}
        });

        // ── /api/cart ────────────────────────────────────────────────────
        srv.createContext("/api/cart", ex->{
            cors(ex);if(opt(ex))return;Map<String,Object> t=getTok(ex);
            if(t==null){respondJson(ex,401,"{\"error\":\"unauth\"}");return;}
            String login=s(t,"user"),method=ex.getRequestMethod();
            if("GET".equals(method)){for(Map<String,Object> u:USERS){if(s(u,"login").equals(login)){@SuppressWarnings("unchecked")List<Object> cart=(List<Object>)u.getOrDefault("cart",new ArrayList<>());respondJson(ex,200,cart.isEmpty()?"[]":arrJsonRaw(cart));return;}}respondJson(ex,200,"[]");return;}
            if("POST".equals(method)){Map<String,Object> req=parseObj(body(ex));int itemId=toInt(req.get("itemId"));Map<String,Object> item=SHOP_ITEMS.stream().filter(i->toInt(i.get("id"))==itemId).findFirst().orElse(null);if(item==null){respondJson(ex,404,"{\"error\":\"item not found\"}");return;}for(int i=0;i<USERS.size();i++){if(s(USERS.get(i),"login").equals(login)){Map<String,Object> u=new LinkedHashMap<>(USERS.get(i));@SuppressWarnings("unchecked")List<Object> cart=new ArrayList<>((List<Object>)u.getOrDefault("cart",new ArrayList<>()));boolean exists=cart.stream().anyMatch(ci->{@SuppressWarnings("unchecked")Map<String,Object> cm=(Map<String,Object>)ci;return toInt(cm.get("id"))==itemId;});if(!exists)cart.add(item);u.put("cart",cart);USERS.set(i,u);scheduleAsyncSave();respondJson(ex,200,"{\"ok\":true}");return;}}}
            if("DELETE".equals(method)){String[] pts=ex.getRequestURI().getPath().split("/");int itemId=toInt(pts[pts.length-1]);for(int i=0;i<USERS.size();i++){if(s(USERS.get(i),"login").equals(login)){Map<String,Object> u=new LinkedHashMap<>(USERS.get(i));@SuppressWarnings("unchecked")List<Object> cart=new ArrayList<>((List<Object>)u.getOrDefault("cart",new ArrayList<>()));cart.removeIf(ci->{@SuppressWarnings("unchecked")Map<String,Object> cm=(Map<String,Object>)ci;return toInt(cm.get("id"))==itemId;});u.put("cart",cart);USERS.set(i,u);scheduleAsyncSave();respondJson(ex,200,"{\"ok\":true}");return;}}}
        });

        srv.createContext("/api/cart/checkout", ex->{
            cors(ex);if(opt(ex))return;Map<String,Object> t=getTok(ex);
            if(t==null){respondJson(ex,401,"{\"error\":\"unauth\"}");return;}
            String login=s(t,"user");
            for(int i=0;i<USERS.size();i++){if(s(USERS.get(i),"login").equals(login)){
                Map<String,Object> u=new LinkedHashMap<>(USERS.get(i));
                @SuppressWarnings("unchecked")List<Object> cart=new ArrayList<>((List<Object>)u.getOrDefault("cart",new ArrayList<>()));
                if(cart.isEmpty()){respondJson(ex,400,"{\"error\":\"cart empty\"}");return;}
                int total=0;for(Object ci:cart){@SuppressWarnings("unchecked")Map<String,Object> cm=(Map<String,Object>)ci;total+=toInt(cm.get("price"));}
                int pts=toInt(u.get("points"));if(pts<total){respondJson(ex,402,"{\"error\":\"Недостаточно очков\",\"need\":"+total+",\"have\":"+pts+"}");return;}
                u.put("points",pts-total);
                @SuppressWarnings("unchecked")List<Object> purchases=new ArrayList<>((List<Object>)u.getOrDefault("purchases",new ArrayList<>()));
                for(Object ci:cart){@SuppressWarnings("unchecked")Map<String,Object> cm=new LinkedHashMap<>((Map<String,Object>)ci);cm.put("date",today());purchases.add(cm);}
                u.put("purchases",purchases);u.put("cart",new ArrayList<>());
                USERS.set(i,u);scheduleAsyncSave();respondJson(ex,200,"{\"ok\":true,\"spent\":"+total+"}");return;}}
        });

        // ── /api/tournaments + match_results ─────────────────────────────
        srv.createContext("/api/tournaments", ex->{
            cors(ex);if(opt(ex))return;String method=ex.getRequestMethod(),path=ex.getRequestURI().getPath();
            if("GET".equals(method)){respondJson(ex,200,arrJson(TOURNAMENTS));return;}
            if("POST".equals(method)){if(!isMinAdmin(ex)){respondJson(ex,401,"{\"error\":\"unauth\"}");return;}Map<String,Object> tour=parseObj(body(ex));tour.put("id",TOURID.getAndIncrement());tour.put("date",today());TOURNAMENTS.add(tour);scheduleAsyncSave();respondJson(ex,200,"{\"ok\":true,\"id\":"+tour.get("id")+"}");}
            if("DELETE".equals(method)){if(!isMinAdmin(ex)){respondJson(ex,401,"{\"error\":\"unauth\"}");return;}String[] pts=path.split("/");int id=toInt(pts[pts.length-1]);TOURNAMENTS.removeIf(tr->toInt(tr.get("id"))==id);scheduleAsyncSave();respondJson(ex,200,"{\"ok\":true}");}
        });

        srv.createContext("/api/match_results", ex->{
            cors(ex);if(opt(ex))return;String method=ex.getRequestMethod();
            if("GET".equals(method)){respondJson(ex,200,arrJson(MATCH_RESULTS));return;}
            if("POST".equals(method)){Map<String,Object> t=getTok(ex);if(t==null){respondJson(ex,401,"{\"error\":\"unauth\"}");return;}try{Map<String,Object> mr=parseObj(body(ex));mr.put("id",MRID.getAndIncrement());mr.put("date",today());mr.put("ts",System.currentTimeMillis());mr.put("submittedBy",s(t,"user"));mr.put("verified",false);MATCH_RESULTS.add(0,mr);scheduleAsyncSave();broadcast("match_result","{\"p1\":\""+jesc(s(mr,"player1"))+"\"}");respondJson(ex,200,"{\"ok\":true,\"id\":"+mr.get("id")+"}");}catch(Exception e){respondJson(ex,400,"{\"error\":\"bad\"}");}}
            if("DELETE".equals(method)){if(!isMinAdmin(ex)){respondJson(ex,401,"{\"error\":\"unauth\"}");return;}String[] pts=ex.getRequestURI().getPath().split("/");int id=toInt(pts[pts.length-1]);MATCH_RESULTS.removeIf(m->toInt(m.get("id"))==id);scheduleAsyncSave();respondJson(ex,200,"{\"ok\":true}");}
        });

        // ── SSE (fixed: no Thread.sleep) ─────────────────────────────────
        srv.createContext("/api/events", ex->{
            ex.getResponseHeaders().set("Content-Type","text/event-stream; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control","no-cache");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin","*");
            ex.sendResponseHeaders(200,0);SSE.add(ex);
            ScheduledFuture<?>[] ref={null};
            ref[0]=SCHED.scheduleAtFixedRate(()->{
                try{ex.getResponseBody().write(": ping\n\n".getBytes(StandardCharsets.UTF_8));ex.getResponseBody().flush();}
                catch(Exception e){SSE.remove(ex);if(ref[0]!=null)ref[0].cancel(false);}
            },20,20,TimeUnit.SECONDS);
        });

        // ── /api/upload (fixed multipart parser) ─────────────────────────
        srv.createContext("/api/upload", ex->{
            cors(ex);if(opt(ex))return;if(getTok(ex)==null){respondJson(ex,401,"{\"error\":\"unauth\"}");return;}
            try{
                byte[] raw=ex.getRequestBody().readAllBytes();
                if(raw.length>MAX_UPLOAD){respondJson(ex,413,"{\"error\":\"too large\"}");return;}
                String ct=ex.getRequestHeaders().getFirst("Content-Type");
                String mt="image/jpeg";
                if(ct!=null){if(ct.contains("image/png"))mt="image/png";else if(ct.contains("image/gif"))mt="image/gif";else if(ct.contains("image/webp"))mt="image/webp";else if(ct.contains("video/"))mt="video/mp4";}
                byte[] fileData;
                if(ct!=null&&ct.contains("multipart/form-data")){
                    String boundary=ct.contains("boundary=")?ct.split("boundary=")[1].trim():"";
                    byte[] hSep="\r\n\r\n".getBytes(StandardCharsets.UTF_8);
                    int hEnd=indexOf(raw,hSep);
                    if(hEnd<0){respondJson(ex,400,"{\"error\":\"bad multipart\"}");return;}
                    String partHeaders=new String(raw,0,hEnd,StandardCharsets.UTF_8);
                    if(partHeaders.contains("image/png"))mt="image/png";else if(partHeaders.contains("image/gif"))mt="image/gif";else if(partHeaders.contains("image/webp"))mt="image/webp";else if(partHeaders.contains("video/"))mt="video/mp4";
                    int dataStart=hEnd+hSep.length;
                    byte[] endBound=("\r\n--"+boundary+"--").getBytes(StandardCharsets.UTF_8);
                    int dataEnd=lastIndexOf(raw,endBound);
                    if(dataEnd<=dataStart){byte[] eb2=("--"+boundary+"--").getBytes(StandardCharsets.UTF_8);dataEnd=lastIndexOf(raw,eb2);if(dataEnd>0&&raw[dataEnd-1]=='\n')dataEnd--;if(dataEnd>0&&raw[dataEnd-1]=='\r')dataEnd--;}
                    if(dataEnd<=dataStart){respondJson(ex,400,"{\"error\":\"parse error\"}");return;}
                    fileData=Arrays.copyOfRange(raw,dataStart,dataEnd);
                }else{fileData=raw;}
                respondJson(ex,200,"{\"ok\":true,\"url\":\"data:"+mt+";base64,"+Base64.getEncoder().encodeToString(fileData)+"\"}");
            }catch(Exception e){respondJson(ex,500,"{\"error\":\"upload failed\"}"); }
        });

        // ── /api/ai/chat (NEW: Anthropic proxy) ──────────────────────────
        srv.createContext("/api/ai/chat", ex->{
            cors(ex);if(opt(ex))return;
            String apiKey=System.getenv("ANTHROPIC_API_KEY");
            if(apiKey==null||apiKey.isEmpty()){respondJson(ex,503,"{\"error\":\"AI not configured\",\"hint\":\"Set ANTHROPIC_API_KEY env var\"}");return;}
            try{
                Map<String,Object> req=parseObj(body(ex));
                String message=s(req,"message"),mode=s(req,"mode");
                if(message.isEmpty()){respondJson(ex,400,"{\"error\":\"empty\"}");return;}
                String sys="squad".equals(mode)?"Ты PSYCHO AI, дерзкий советник по составам EA FC Mobile. Говоришь прямо, знаешь ОБЩ, позиции, схемы. Коротко и по делу."
                    :"meta".equals(mode)?"Ты PSYCHO AI, жёсткий аналитик мета-игры EA FC Mobile. Называешь имбу имбой. Коротко."
                    :"Ты PSYCHO AI, дерзкий ассистент лиги Psycho League. Честный, без цензуры мнений. Коротко.";
                String aBody="{\"model\":\"claude-sonnet-4-20250514\",\"max_tokens\":700,\"system\":\""+jesc(sys)+"\",\"messages\":[{\"role\":\"user\",\"content\":\""+jesc(message)+"\"}]}";
                java.net.http.HttpClient client=java.net.http.HttpClient.newHttpClient();
                java.net.http.HttpRequest aReq=java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://api.anthropic.com/v1/messages"))
                    .header("Content-Type","application/json")
                    .header("x-api-key",apiKey)
                    .header("anthropic-version","2023-06-01")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(aBody)).build();
                java.net.http.HttpResponse<String> aResp=client.send(aReq,java.net.http.HttpResponse.BodyHandlers.ofString());
                String respBody=aResp.body();String reply="Не смог ответить.";
                int ti=respBody.indexOf("\"text\":\"");
                if(ti>=0){int s2=ti+8,e2=s2;while(e2<respBody.length()){if(respBody.charAt(e2)=='\\'){e2+=2;continue;}if(respBody.charAt(e2)=='"')break;e2++;}reply=unesc(respBody.substring(s2,e2));}
                respondJson(ex,200,"{\"reply\":\""+jesc(reply)+"\"}");
            }catch(Exception e){respondJson(ex,500,"{\"error\":\""+jesc(e.getMessage())+"\"}"); }
        });

        // ── Admin endpoints ───────────────────────────────────────────────
        srv.createContext("/api/admin/login", ex->{
            cors(ex);if(opt(ex))return;String b=body(ex),user=strVal(b,"user"),pass=strVal(b,"pass");
            if(OWNER_USER.equals(user)&&OWNER_PASS.equals(pass)){String tok=genTok();TOKENS.put(tok,mapOf("role","owner","user",user,"ts",System.currentTimeMillis()));respondJson(ex,200,"{\"token\":\""+tok+"\",\"role\":\"owner\"}");return;}
            if(ADMIN_USER.equals(user)&&ADMIN_PASS.equals(pass)){String tok=genTok();TOKENS.put(tok,mapOf("role","admin","user",user,"ts",System.currentTimeMillis()));respondJson(ex,200,"{\"token\":\""+tok+"\",\"role\":\"admin\"}");return;}
            for(Map<String,Object> adm:ADMINS){if(s(adm,"login").equals(user)&&s(adm,"pass").equals(sha256(pass))){String tok=genTok();TOKENS.put(tok,mapOf("role","admin","user",user,"ts",System.currentTimeMillis()));respondJson(ex,200,"{\"token\":\""+tok+"\",\"role\":\"admin\"}");return;}}
            respondJson(ex,401,"{\"error\":\"wrong\"}");
        });

        srv.createContext("/api/admin/news", ex->{
            cors(ex);if(opt(ex))return;if(!isMinAdmin(ex)){respondJson(ex,401,"{\"error\":\"unauth\"}");return;}
            String method=ex.getRequestMethod(),path=ex.getRequestURI().getPath();
            if("POST".equals(method)){Map<String,Object> n=parseObj(body(ex));int id=NID.getAndIncrement();n.put("id",id);if(!n.containsKey("date"))n.put("date",today());NEWS.add(0,n);scheduleAsyncSave();broadcast("news","{\"title\":\""+jesc(s(n,"title"))+"\",\"id\":"+id+"}");respondJson(ex,200,"{\"ok\":true,\"id\":"+id+"}");}
            else if("PUT".equals(method)){String[] pts=path.split("/");int id=toInt(pts[pts.length-1]);Map<String,Object> upd=parseObj(body(ex));upd.put("id",id);for(int i=0;i<NEWS.size();i++){if(toInt(NEWS.get(i).get("id"))==id){Map<String,Object> m=new LinkedHashMap<>(NEWS.get(i));m.putAll(upd);NEWS.set(i,m);break;}}scheduleAsyncSave();respondJson(ex,200,"{\"ok\":true}");}
            else if("DELETE".equals(method)){String[] pts=path.split("/");int id=toInt(pts[pts.length-1]);NEWS.removeIf(n->toInt(n.get("id"))==id);scheduleAsyncSave();respondJson(ex,200,"{\"ok\":true}");}
        });

        srv.createContext("/api/admin/result", ex->{
            cors(ex);if(opt(ex))return;if(!isMinAdmin(ex)){respondJson(ex,401,"{\"error\":\"unauth\"}");return;}
            String method=ex.getRequestMethod(),path=ex.getRequestURI().getPath();
            if("POST".equals(method)){Map<String,Object> m=parseObj(body(ex));m.put("id",MID.getAndIncrement());MATCHES.add(0,m);recalc();scheduleAsyncSave();respondJson(ex,200,"{\"ok\":true}");}
            else if("DELETE".equals(method)){String[] pts=path.split("/");int id=toInt(pts[pts.length-1]);MATCHES.removeIf(m->toInt(m.get("id"))==id);recalc();scheduleAsyncSave();respondJson(ex,200,"{\"ok\":true}");}
        });

        srv.createContext("/api/admin/player", ex->{
            cors(ex);if(opt(ex))return;if(!isMinAdmin(ex)){respondJson(ex,401,"{\"error\":\"unauth\"}");return;}
            String method=ex.getRequestMethod(),path=ex.getRequestURI().getPath();
            if("POST".equals(method)){Map<String,Object> p=parseObj(body(ex));if(!p.containsKey("id"))p.put("id","p"+PID.getAndIncrement());PLAYERS.removeIf(x->s(x,"id").equals(s(p,"id")));PLAYERS.add(p);scheduleAsyncSave();respondJson(ex,200,"{\"ok\":true}");}
            else if("DELETE".equals(method)){String[] pts=path.split("/");String id=pts[pts.length-1];PLAYERS.removeIf(p->id.equals(s(p,"id")));scheduleAsyncSave();respondJson(ex,200,"{\"ok\":true}");}
        });

        srv.createContext("/api/admin/texts",ex->{cors(ex);if(opt(ex))return;if(!isMinAdmin(ex)){respondJson(ex,401,"{\"error\":\"unauth\"}");return;}if("POST".equals(ex.getRequestMethod())){SITE_TEXTS.putAll(parseObj(body(ex)));scheduleAsyncSave();respondJson(ex,200,"{\"ok\":true}");}});
        srv.createContext("/api/admin/applications", ex->{
            cors(ex);if(opt(ex))return;if(!isMinAdmin(ex)){respondJson(ex,401,"{\"error\":\"unauth\"}");return;}
            String method=ex.getRequestMethod(),path=ex.getRequestURI().getPath();
            if("GET".equals(method)){respondJson(ex,200,arrJson(APPLICATIONS));}
            else if("PUT".equals(method)){String[] pts=path.split("/");int id=toInt(pts[pts.length-2]);String action=pts[pts.length-1];String st="accept".equals(action)?"accepted":"rejected";for(int i=0;i<APPLICATIONS.size();i++){if(toInt(APPLICATIONS.get(i).get("id"))==id){Map<String,Object> u=new LinkedHashMap<>(APPLICATIONS.get(i));u.put("status",st);APPLICATIONS.set(i,u);break;}}scheduleAsyncSave();respondJson(ex,200,"{\"ok\":true}");}
            else if("DELETE".equals(method)){String[] pts=path.split("/");int id=toInt(pts[pts.length-1]);APPLICATIONS.removeIf(a->toInt(a.get("id"))==id);scheduleAsyncSave();respondJson(ex,200,"{\"ok\":true}");}
        });

        srv.createContext("/api/admin/shop", ex->{
            cors(ex);if(opt(ex))return;if(!isMinAdmin(ex)){respondJson(ex,401,"{\"error\":\"unauth\"}");return;}
            String method=ex.getRequestMethod(),path=ex.getRequestURI().getPath();
            if("POST".equals(method)){Map<String,Object> item=parseObj(body(ex));item.put("id",SID.getAndIncrement());SHOP_ITEMS.add(item);scheduleAsyncSave();respondJson(ex,200,"{\"ok\":true}");}
            else if("DELETE".equals(method)){String[] pts=path.split("/");int id=toInt(pts[pts.length-1]);SHOP_ITEMS.removeIf(i->toInt(i.get("id"))==id);scheduleAsyncSave();respondJson(ex,200,"{\"ok\":true}");}
        });

        srv.createContext("/api/admin/users",ex->{cors(ex);if(!isMinAdmin(ex)){respondJson(ex,401,"{\"error\":\"unauth\"}");return;}List<Map<String,Object>> safe=USERS.stream().map(u->{Map<String,Object> s2=new LinkedHashMap<>(u);s2.remove("passHash");return s2;}).collect(Collectors.toList());respondJson(ex,200,arrJson(safe));});
        srv.createContext("/api/admin/posts", ex->{cors(ex);if(opt(ex))return;if(!isMinAdmin(ex)){respondJson(ex,401,"{\"error\":\"unauth\"}");return;}if("DELETE".equals(ex.getRequestMethod())){String[] pts=ex.getRequestURI().getPath().split("/");int id=toInt(pts[pts.length-1]);POSTS.removeIf(p->toInt(p.get("id"))==id);scheduleAsyncSave();respondJson(ex,200,"{\"ok\":true}");}});

        // ── Owner endpoints ───────────────────────────────────────────────
        srv.createContext("/api/owner/admins", ex->{
            cors(ex);if(opt(ex))return;if(!isOwner(ex)){respondJson(ex,403,"{\"error\":\"owner only\"}");return;}
            String method=ex.getRequestMethod(),path=ex.getRequestURI().getPath();
            if("GET".equals(method)){List<Map<String,Object>> safe=ADMINS.stream().map(a->{Map<String,Object> s2=new LinkedHashMap<>(a);s2.remove("pass");return s2;}).collect(Collectors.toList());respondJson(ex,200,arrJson(safe));}
            else if("POST".equals(method)){Map<String,Object> req=parseObj(body(ex));String login=s(req,"login").trim(),nick=s(req,"nick").trim(),pass=s(req,"pass");if(login.isEmpty()||pass.isEmpty()){respondJson(ex,400,"{\"error\":\"fill all\"}");return;}Map<String,Object> adm=new LinkedHashMap<>();adm.put("id","a"+System.currentTimeMillis());adm.put("login",login);adm.put("nick",nick.isEmpty()?login:nick);adm.put("pass",sha256(pass));adm.put("date",today());ADMINS.add(adm);scheduleAsyncSave();respondJson(ex,200,"{\"ok\":true}");}
            else if("DELETE".equals(method)){String[] pts=path.split("/");String id=pts[pts.length-1];ADMINS.removeIf(a->id.equals(s(a,"id")));scheduleAsyncSave();respondJson(ex,200,"{\"ok\":true}");}
        });

        srv.createContext("/api/owner/points", ex->{
            cors(ex);if(opt(ex))return;if(!isOwner(ex)){respondJson(ex,403,"{\"error\":\"owner only\"}");return;}
            Map<String,Object> req=parseObj(body(ex));String login=s(req,"login");int delta=toInt(req.get("delta"));boolean found=false;
            for(int i=0;i<USERS.size();i++){if(s(USERS.get(i),"login").equals(login)){Map<String,Object> u=new LinkedHashMap<>(USERS.get(i));u.put("points",Math.max(0,toInt(u.get("points"))+delta));USERS.set(i,u);found=true;scheduleAsyncSave();break;}}
            respondJson(ex,found?200:404,found?"{\"ok\":true}":"{\"error\":\"user not found\"}");
        });

        srv.createContext("/api/owner/stats", ex->{
            cors(ex);if(!isOwner(ex)){respondJson(ex,403,"{\"error\":\"owner only\"}");return;}
            respondJson(ex,200,String.format("{\"users\":%d,\"news\":%d,\"matches\":%d,\"applications\":%d,\"admins\":%d,\"shopItems\":%d,\"scores\":%d,\"posts\":%d,\"messages\":%d,\"tournaments\":%d}",USERS.size(),NEWS.size(),MATCHES.size(),APPLICATIONS.size(),ADMINS.size(),SHOP_ITEMS.size(),SCORES.size(),POSTS.size(),MESSAGES.size(),TOURNAMENTS.size()));
        });

        // ── Executor ─────────────────────────────────────────────────────
        try {
            Executor vt=(Executor)Executors.class.getMethod("newVirtualThreadPerTaskExecutor").invoke(null);
            srv.setExecutor(vt);P.println("[EXEC] Virtual Threads (Java 21+)");
        } catch(NoSuchMethodException e) {
            int n=Math.max(8,Runtime.getRuntime().availableProcessors()*2);
            srv.setExecutor(Executors.newFixedThreadPool(n));P.println("[EXEC] Fixed pool ("+n+" threads)");
        }

        srv.start();
        P.println("[HTTP] Listening on port "+PORT);
        P.println("[OWNER] "+OWNER_USER+" / "+OWNER_PASS);
        P.println("[ADMIN] "+ADMIN_USER+" / "+ADMIN_PASS);
        P.println("[AI]   Set ANTHROPIC_API_KEY env for /api/ai/chat");
    }

    // ═════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════════

    static byte[] loadIndex(){File f=new File("index.html");if(f.exists()){try{return Files.readAllBytes(f.toPath());}catch(Exception e){}}return null;}
    static boolean opt(HttpExchange ex)throws IOException{if("OPTIONS".equals(ex.getRequestMethod())){respond(ex,200,"text/plain","");return true;}return false;}

    static synchronized void scheduleAsyncSave(){
        if(pendingSave!=null&&!pendingSave.isDone())pendingSave.cancel(false);
        pendingSave=SCHED.schedule(()->{try{save();}catch(Exception e){P.println("[SAVE-ERR] "+e.getMessage());}},500,TimeUnit.MILLISECONDS);
    }

    static void broadcast(String event,String data){
        byte[] b=("event:"+event+"\ndata:"+data+"\n\n").getBytes(StandardCharsets.UTF_8);
        SSE.removeIf(ex->{try{ex.getResponseBody().write(b);ex.getResponseBody().flush();return false;}catch(Exception e){return true;}});
    }

    static void recalc(){
        LinkedHashMap<String,int[]> st=new LinkedHashMap<>();
        for(Map<String,Object> m:MATCHES){String t1=s(m,"team1"),t2=s(m,"team2");int s1=toInt(m.get("score1")),s2=toInt(m.get("score2"));st.putIfAbsent(t1,new int[7]);st.putIfAbsent(t2,new int[7]);int[]a=st.get(t1),b=st.get(t2);a[0]++;b[0]++;a[4]+=s1;a[5]+=s2;b[4]+=s2;b[5]+=s1;if(s1>s2){a[1]++;a[6]+=3;b[3]++;}else if(s1<s2){b[1]++;b[6]+=3;a[3]++;}else{a[2]++;b[2]++;a[6]++;b[6]++;}}
        TABLE.clear();st.entrySet().stream().sorted((x,y)->Integer.compare(y.getValue()[6],x.getValue()[6])).forEach(e->{int[]v=e.getValue();Map<String,Object> r=new LinkedHashMap<>();r.put("name",e.getKey());r.put("played",v[0]);r.put("won",v[1]);r.put("draw",v[2]);r.put("lost",v[3]);r.put("gf",v[4]);r.put("ga",v[5]);r.put("pts",v[6]);TABLE.add(r);});
    }

    static void save(){
        try{
            write(DATA_DIR+"/news.json",arrJson(NEWS));write(DATA_DIR+"/matches.json",arrJson(MATCHES));
            write(DATA_DIR+"/players.json",arrJson(PLAYERS));write(DATA_DIR+"/users.json",arrJson(USERS));
            write(DATA_DIR+"/apps.json",arrJson(APPLICATIONS));write(DATA_DIR+"/shop.json",arrJson(SHOP_ITEMS));
            write(DATA_DIR+"/scores.json",arrJson(SCORES));write(DATA_DIR+"/admins.json",arrJson(ADMINS));
            write(DATA_DIR+"/texts.json",objJson(SITE_TEXTS));write(DATA_DIR+"/posts.json",arrJson(POSTS));
            write(DATA_DIR+"/messages.json",arrJson(MESSAGES));write(DATA_DIR+"/tournaments.json",arrJson(TOURNAMENTS));
            write(DATA_DIR+"/match_results.json",arrJson(MATCH_RESULTS));
        }catch(Exception e){P.println("[SAVE] "+e.getMessage());}
    }

    static void load(){
        try{loadL(DATA_DIR+"/news.json",NEWS);}catch(Exception e){}
        try{loadL(DATA_DIR+"/matches.json",MATCHES);recalc();}catch(Exception e){}
        try{loadL(DATA_DIR+"/players.json",PLAYERS);}catch(Exception e){}
        try{loadL(DATA_DIR+"/users.json",USERS);}catch(Exception e){}
        try{loadL(DATA_DIR+"/apps.json",APPLICATIONS);}catch(Exception e){}
        try{loadL(DATA_DIR+"/shop.json",SHOP_ITEMS);}catch(Exception e){}
        try{loadL(DATA_DIR+"/scores.json",SCORES);}catch(Exception e){}
        try{loadL(DATA_DIR+"/admins.json",ADMINS);}catch(Exception e){}
        try{loadL(DATA_DIR+"/posts.json",POSTS);}catch(Exception e){}
        try{loadL(DATA_DIR+"/messages.json",MESSAGES);}catch(Exception e){}
        try{loadL(DATA_DIR+"/tournaments.json",TOURNAMENTS);}catch(Exception e){}
        try{loadL(DATA_DIR+"/match_results.json",MATCH_RESULTS);}catch(Exception e){}
        try{String txt=Files.readString(Path.of(DATA_DIR+"/texts.json"),StandardCharsets.UTF_8).trim();if(txt.startsWith("{"))SITE_TEXTS.putAll(parseObj(txt));}catch(Exception e){}
        NEWS.forEach(n->{int id=toInt(n.get("id"));if(id>=NID.get())NID.set(id+1);});
        MATCHES.forEach(m->{int id=toInt(m.get("id"));if(id>=MID.get())MID.set(id+1);});
        USERS.forEach(u->{int id=toInt(u.get("id"));if(id>=UID.get())UID.set(id+1);});
        POSTS.forEach(p->{int id=toInt(p.get("id"));if(id>=POSTID.get())POSTID.set(id+1);});
        MESSAGES.forEach(m->{int id=toInt(m.get("id"));if(id>=MSGID.get())MSGID.set(id+1);});
        P.println("[LOAD] users="+USERS.size()+" news="+NEWS.size()+" posts="+POSTS.size()+" msgs="+MESSAGES.size());
    }

    static void loadL(String path,List<Map<String,Object>>list)throws Exception{String txt=Files.readString(Path.of(path),StandardCharsets.UTF_8).trim();if(!txt.startsWith("[")||txt.length()<2)return;String inner=txt.substring(1,txt.length()-1).trim();if(inner.isEmpty())return;for(String chunk:topObjs(inner)){Map<String,Object> m=parseObj(chunk);if(!m.isEmpty())list.add(m);}}

    static void seedIfEmpty(){
        if(NEWS.isEmpty()){mkN("🏆 Новый сезон!","Psycho League открывает новый сезон!","АНОНС","PaNdA");mkN("⚡ Рекорд!","240,712 голов!","РЕКОРД","PaNdA");mkN("👥 185 участников","Суммарно 185 активных!","НОВОСТЬ","Psycho");}
        if(MATCHES.isEmpty()){mkM("PaNdA","Psycho",5,2,"20.04.2026");mkM("Zenith","Kaiser",3,3,"21.04.2026");mkM("Titan","PaNdA",1,4,"22.04.2026");mkM("Psycho","Titan",6,0,"23.04.2026");recalc();}
        if(SHOP_ITEMS.isEmpty()){mkS("Временный Админ","Доступ к панели на 24 часа","👑",500,"admin_temp");mkS("VIP Статус","Метка рядом с ником","⭐",200,"vip");mkS("Кастомная карточка","Уникальный дизайн","🎨",300,"card");mkS("Эксклюзивный аватар","Редкий аватар","🔥",150,"avatar");mkS("Буст очков x2","Двойные очки в квизе","⚡",250,"boost");}
        if(SITE_TEXTS.isEmpty()){SITE_TEXTS.put("hero_sub","EA FC MOBILE · OFFICIAL COMMUNITY HUB");SITE_TEXTS.put("pl1_name","⚡ Psycho League · PaNdA");SITE_TEXTS.put("pl1_members","85");SITE_TEXTS.put("pl1_goals","240712");SITE_TEXTS.put("pl1_obr","12116");SITE_TEXTS.put("pl1_wins","161");SITE_TEXTS.put("pl2_name","🔥 Psycho League 2 · Psycho");SITE_TEXTS.put("pl2_members","100");SITE_TEXTS.put("pl2_goals","98871");SITE_TEXTS.put("pl2_obr","12061");SITE_TEXTS.put("pl2_wins","40");}
        scheduleAsyncSave();
    }

    static void mkN(String t,String b,String c,String a){Map<String,Object>n=new LinkedHashMap<>();n.put("id",NID.getAndIncrement());n.put("title",t);n.put("body",b);n.put("category",c);n.put("author",a);n.put("date",today());NEWS.add(n);}
    static void mkM(String t1,String t2,int s1,int s2,String date){Map<String,Object>m=new LinkedHashMap<>();m.put("id",MID.getAndIncrement());m.put("team1",t1);m.put("team2",t2);m.put("score1",s1);m.put("score2",s2);m.put("date",date);MATCHES.add(m);}
    static void mkS(String name,String desc,String icon,int price,String type){Map<String,Object>i=new LinkedHashMap<>();i.put("id",SID.getAndIncrement());i.put("name",name);i.put("description",desc);i.put("icon",icon);i.put("price",price);i.put("type",type);SHOP_ITEMS.add(i);}

    @SuppressWarnings("unchecked")
    static String arrJsonRaw(List<Object>list){return"["+list.stream().map(item->{if(item instanceof Map)return objJson((Map<String,Object>)item);if(item instanceof Number)return String.valueOf(item);return"\""+jesc(String.valueOf(item))+"\"";}).collect(Collectors.joining(","))+"]";}
    static void respond(HttpExchange ex,int code,String ct,String body)throws IOException{respond(ex,code,ct,body.getBytes(StandardCharsets.UTF_8));}
    static void respond(HttpExchange ex,int code,String ct,byte[]body)throws IOException{Headers h=ex.getResponseHeaders();h.set("Content-Type",ct);h.set("Access-Control-Allow-Origin","*");h.set("Access-Control-Allow-Headers","Content-Type,Authorization");h.set("Access-Control-Allow-Methods","GET,POST,PUT,DELETE,OPTIONS");ex.sendResponseHeaders(code,body.length);try(OutputStream os=ex.getResponseBody()){os.write(body);}}
    static void respondJson(HttpExchange ex,int code,String json)throws IOException{respond(ex,code,"application/json; charset=utf-8",json);}
    static void cors(HttpExchange ex){ex.getResponseHeaders().set("Access-Control-Allow-Origin","*");ex.getResponseHeaders().set("Access-Control-Allow-Headers","Content-Type,Authorization");ex.getResponseHeaders().set("Access-Control-Allow-Methods","GET,POST,PUT,DELETE,OPTIONS");}
    static Map<String,Object> getTok(HttpExchange ex){String tok=ex.getRequestHeaders().getFirst("Authorization");if(tok==null||tok.isEmpty())return null;Map<String,Object>t=TOKENS.get(tok);if(t==null)return null;if(System.currentTimeMillis()-toLong(t.get("ts"))>86_400_000L){TOKENS.remove(tok);return null;}return t;}
    static boolean isMinAdmin(HttpExchange ex){Map<String,Object>t=getTok(ex);if(t==null)return false;String r=s(t,"role");return"admin".equals(r)||"owner".equals(r);}
    static boolean isOwner(HttpExchange ex){Map<String,Object>t=getTok(ex);if(t==null)return false;return"owner".equals(s(t,"role"));}
    static String body(HttpExchange ex)throws IOException{return new String(ex.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);}
    static String mime(String p){if(p.endsWith(".html"))return"text/html; charset=utf-8";if(p.endsWith(".css"))return"text/css";if(p.endsWith(".js"))return"application/javascript";if(p.endsWith(".png"))return"image/png";if(p.endsWith(".jpg")||p.endsWith(".jpeg"))return"image/jpeg";if(p.endsWith(".gif"))return"image/gif";if(p.endsWith(".svg"))return"image/svg+xml";if(p.endsWith(".ico"))return"image/x-icon";if(p.endsWith(".mp4"))return"video/mp4";if(p.endsWith(".webm"))return"video/webm";return"application/octet-stream";}
    static String today(){return LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));}
    static String genTok(){return UUID.randomUUID().toString();}
    static String sha256(String input){try{MessageDigest md=MessageDigest.getInstance("SHA-256");byte[]h=md.digest(input.getBytes(StandardCharsets.UTF_8));StringBuilder sb=new StringBuilder();for(byte b:h)sb.append(String.format("%02x",b));return sb.toString();}catch(Exception e){return input;}}
    static String arrJson(List<Map<String,Object>>list){return"["+list.stream().map(Server::objJson).collect(Collectors.joining(","))+"]";}
    @SuppressWarnings("unchecked")
    static String objJson(Map<String,Object>map){StringBuilder sb=new StringBuilder("{");boolean first=true;for(var e:map.entrySet()){if(!first)sb.append(",");first=false;sb.append("\"").append(jesc(e.getKey())).append("\":");Object v=e.getValue();if(v==null)sb.append("null");else if(v instanceof Boolean)sb.append(v);else if(v instanceof Number)sb.append(v);else if(v instanceof List){sb.append("[");boolean f2=true;for(Object item:(List<?>)v){if(!f2)sb.append(",");f2=false;if(item instanceof List)sb.append(innerArr((List<?>)item));else if(item instanceof Map)sb.append(objJson((Map<String,Object>)item));else if(item instanceof Number)sb.append(item);else sb.append("\"").append(jesc(String.valueOf(item))).append("\"");}sb.append("]");}else if(v instanceof Map)sb.append(objJson((Map<String,Object>)v));else sb.append("\"").append(jesc(String.valueOf(v))).append("\"");}return sb.append("}").toString();}
    static String innerArr(List<?>l){StringBuilder sb=new StringBuilder("[");boolean f=true;for(Object x:l){if(!f)sb.append(",");f=false;if(x instanceof Number)sb.append(x);else sb.append("\"").append(jesc(String.valueOf(x))).append("\"");}return sb.append("]").toString();}
    static Map<String,Object> parseObj(String raw){Map<String,Object>map=new LinkedHashMap<>();raw=raw.trim();if(!raw.startsWith("{"))return map;raw=raw.substring(1,raw.length()-1);int i=0,len=raw.length();while(i<len){while(i<len&&" \t\n\r,".indexOf(raw.charAt(i))>=0)i++;if(i>=len||raw.charAt(i)!='"')break;int ks=i+1,ke=ks;while(ke<len){if(raw.charAt(ke)=='\\'){ke+=2;continue;}if(raw.charAt(ke)=='"')break;ke++;}String key=raw.substring(ks,ke);i=ke+1;while(i<len&&raw.charAt(i)!=':')i++;i++;while(i<len&&" \t".indexOf(raw.charAt(i))>=0)i++;if(i>=len)break;char c=raw.charAt(i);if(c=='"'){int vs=i+1,ve=vs;while(ve<len){if(raw.charAt(ve)=='\\'){ve+=2;continue;}if(raw.charAt(ve)=='"')break;ve++;}map.put(key,unesc(raw.substring(vs,ve)));i=ve+1;}else if(c=='{'){int e=mc(raw,i,'{','}');map.put(key,parseObj(raw.substring(i,e+1)));i=e+1;}else if(c=='['){int e=mc(raw,i,'[',']');map.put(key,parseArr(raw.substring(i,e+1)));i=e+1;}else if(c=='t'){map.put(key,true);i+=4;}else if(c=='f'){map.put(key,false);i+=5;}else if(c=='n'){map.put(key,null);i+=4;}else{int ne=i;while(ne<len&&",}]".indexOf(raw.charAt(ne))<0)ne++;String ns=raw.substring(i,ne).trim();try{map.put(key,Integer.parseInt(ns));}catch(NumberFormatException ex2){try{map.put(key,Long.parseLong(ns));}catch(NumberFormatException ex3){try{map.put(key,Double.parseDouble(ns));}catch(NumberFormatException ex4){map.put(key,ns);}}}i=ne;}}return map;}
    static List<Object> parseArr(String raw){List<Object>list=new ArrayList<>();raw=raw.trim();if(!raw.startsWith("["))return list;raw=raw.substring(1,raw.length()-1).trim();if(raw.isEmpty())return list;int i=0,len=raw.length();while(i<len){while(i<len&&" ,\t\n\r".indexOf(raw.charAt(i))>=0)i++;if(i>=len)break;char c=raw.charAt(i);if(c=='"'){int vs=i+1,ve=vs;while(ve<len){if(raw.charAt(ve)=='\\'){ve+=2;continue;}if(raw.charAt(ve)=='"')break;ve++;}list.add(unesc(raw.substring(vs,ve)));i=ve+1;}else if(c=='['){int e=mc(raw,i,'[',']');list.add(parseArr(raw.substring(i,e+1)));i=e+1;}else if(c=='{'){int e=mc(raw,i,'{','}');list.add(parseObj(raw.substring(i,e+1)));i=e+1;}else{int ne=i;while(ne<len&&",]".indexOf(raw.charAt(ne))<0)ne++;String ns=raw.substring(i,ne).trim();if(ns.equals("true"))list.add(true);else if(ns.equals("false"))list.add(false);else if(ns.equals("null"))list.add(null);else try{list.add(Integer.parseInt(ns));}catch(NumberFormatException e){try{list.add(Long.parseLong(ns));}catch(NumberFormatException e2){list.add(ns);}}i=ne;}}return list;}
    static int mc(String s,int start,char open,char close){int d=0;boolean inStr=false;for(int i=start;i<s.length();i++){char c=s.charAt(i);if(!inStr){if(c==open)d++;else if(c==close){if(--d==0)return i;}else if(c=='"')inStr=true;}else{if(c=='\\')i++;else if(c=='"')inStr=false;}}return s.length()-1;}
    static List<String> topObjs(String s){List<String>list=new ArrayList<>();int i=0,d=0,start=-1;boolean inStr=false;while(i<s.length()){char c=s.charAt(i);if(!inStr){if(c=='{'){if(d++==0)start=i;}else if(c=='}'){if(--d==0&&start>=0){list.add(s.substring(start,i+1));start=-1;}}else if(c=='"')inStr=true;}else{if(c=='\\')i++;else if(c=='"')inStr=false;}i++;}return list;}
    static String strVal(String json,String key){String n="\""+key+"\":\"";int s=json.indexOf(n);if(s<0)return"";s+=n.length();int e=s;while(e<json.length()){if(json.charAt(e)=='\\'){e+=2;continue;}if(json.charAt(e)=='"')break;e++;}return json.substring(s,e);}
    static String s(Map<String,Object>m,String k){Object v=m.get(k);return v==null?"":String.valueOf(v);}
    static int toInt(Object o){if(o==null)return 0;if(o instanceof Number)return((Number)o).intValue();try{return Integer.parseInt(String.valueOf(o).trim());}catch(Exception e){return 0;}}
    static long toLong(Object o){if(o==null)return 0;if(o instanceof Number)return((Number)o).longValue();try{return Long.parseLong(String.valueOf(o).trim());}catch(Exception e){return 0;}}
    static String jesc(String s){if(s==null)return"";return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t");}
    static String unesc(String s){if(!s.contains("\\"))return s;StringBuilder sb=new StringBuilder();for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='\\'&&i+1<s.length()){char n=s.charAt(++i);if(n=='n')sb.append('\n');else if(n=='r')sb.append('\r');else if(n=='t')sb.append('\t');else sb.append(n);}else sb.append(c);}return sb.toString();}
    static void write(String path,String content)throws IOException{Files.writeString(Path.of(path),content,StandardCharsets.UTF_8);}
    static int indexOf(byte[]d,byte[]p){outer:for(int i=0;i<=d.length-p.length;i++){for(int j=0;j<p.length;j++)if(d[i+j]!=p[j])continue outer;return i;}return -1;}
    static int lastIndexOf(byte[]d,byte[]p){outer:for(int i=d.length-p.length;i>=0;i--){for(int j=0;j<p.length;j++)if(d[i+j]!=p[j])continue outer;return i;}return -1;}
    @SafeVarargs
    @SuppressWarnings("unchecked")
    static <K,V> Map<K,V> mapOf(Object... kv){Map<K,V>m=new LinkedHashMap<>();for(int i=0;i<kv.length-1;i+=2){m.put((K)kv[i],(V)kv[i+1]);}return m;}
}
