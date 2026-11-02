package com.douai.module.feature;

import com.douai.module.diagnostics.DouAiLog;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostScanReport;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Fetches the signed-in user's native relationship lists without sharing credentials. */
public final class NativeFollowLists implements AutoCloseable {
    public static final String REFRESH_KEY = "relation_lists_refresh_token";
    static final long TTL_MS = 15L * 60 * 1000;
    private static final long RETRY_MS = 60_000;
    private static volatile NativeFollowLists active;
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    interface AccountSource { Account current(); }
    static final class Account {
        final String uid;
        final String secUid;
        Account(String uid, String secUid) {
            this.uid = FollowListSnapshot.normalizeUid(uid);
            this.secUid = secUid == null ? "" : secUid;
        }
    }

    private final AccountSource accounts;
    private final FollowListPager.Fetcher fetcher;
    private final Consumer<String> log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledThreadPoolExecutor worker;
    private ScheduledFuture<?> refreshTimer;
    private long refreshAt = Long.MAX_VALUE;
    private boolean started;
    private final Map<FollowListSnapshot.Kind, Map<String, String>> names = new EnumMap<>(FollowListSnapshot.Kind.class);
    private final Map<FollowListSnapshot.Kind, Map<String, ProfileListEntry>> details = new EnumMap<>(FollowListSnapshot.Kind.class);
    private final Map<FollowListSnapshot.Kind, String> errors = new EnumMap<>(FollowListSnapshot.Kind.class);
    private final Map<FollowListSnapshot.Kind, Long> attempts = new EnumMap<>(FollowListSnapshot.Kind.class);
    private final Map<FollowListSnapshot.Kind, Long> refreshDeadlines = new EnumMap<>(FollowListSnapshot.Kind.class);
    private final List<Runnable> refreshCallbacks = new ArrayList<>();
    private volatile LocalOptions options = new LocalOptions(Collections.emptyMap());
    private volatile boolean closed;
    private volatile FollowListSnapshot.Kind loading;
    private boolean refreshQueued;
    private String refreshToken = "";

    NativeFollowLists(AccountSource accounts, FollowListPager.Fetcher fetcher, Consumer<String> log) {
        this(accounts, fetcher, log, new ScheduledThreadPoolExecutor(1));
    }

    NativeFollowLists(AccountSource accounts, FollowListPager.Fetcher fetcher, Consumer<String> log,
                      ScheduledThreadPoolExecutor worker) {
        this.accounts = accounts;
        this.fetcher = fetcher;
        this.log = log == null ? ignored -> { } : log;
        this.worker = worker;
        worker.setRemoveOnCancelPolicy(true);
    }

    public static NativeFollowLists install(ClassLoader loader, HostScanReport report, LocalOptions options,
                                          Consumer<String> log) {
        NativeFollowLists service = new NativeFollowLists(() -> readAccount(loader),
                new NativeFetcher(loader, report), log);
        NativeFollowLists previous = active;
        if (previous != null) previous.close();
        active = service;
        service.syncAccount(service.accounts.current());
        service.setOptions(options);
        service.start();
        return service;
    }

    synchronized void start() {
        if (closed || started) return;
        started = true;
        // Account changes must still be discovered even when no automation currently needs lists.
        // This probe only compares account state; list work has its own TTL/retry deadline.
        worker.scheduleWithFixedDelay(() -> syncAccount(accounts.current()), 30, 30, TimeUnit.SECONDS);
        syncAccount(accounts.current());
        scheduleRefresh();
    }

    public void setOptions(LocalOptions value) {
        LocalOptions previous = options;
        options = value;
        String token = value.text(REFRESH_KEY, "");
        boolean force = !token.equals(refreshToken) && !token.isEmpty();
        refreshToken = token;
        if (force) refresh(null);
        else {
            for (FollowListSnapshot.Kind kind : FollowListSnapshot.Kind.values()) {
                if (needed(previous, kind) != needed(value, kind)) { scheduleRefresh(); break; }
            }
        }
    }

    private synchronized void scheduleRefresh() {
        if (closed || !started) return;
        long next = Long.MAX_VALUE;
        long now = SystemClock.elapsedRealtime();
        FollowListSnapshot.State state = FollowListSnapshot.current();
        if (!state.accountUid.isEmpty()) {
            for (FollowListSnapshot.Kind kind : FollowListSnapshot.Kind.values()) {
                if (!needed(options, kind)) continue;
                boolean following = kind == FollowListSnapshot.Kind.FOLLOWING;
                boolean known = following ? state.followingKnown : state.followersKnown;
                long due = known ? refreshDeadlines.getOrDefault(kind, now + TTL_MS)
                        : attempts.getOrDefault(kind, now - RETRY_MS) + RETRY_MS;
                next = Math.min(next, Math.max(now, due));
            }
        }
        if (refreshTimer != null && !refreshTimer.isDone() && next == refreshAt) return;
        if (refreshTimer != null) refreshTimer.cancel(false);
        refreshTimer = null;
        refreshAt = next;
        if (next == Long.MAX_VALUE) return;
        refreshTimer = worker.schedule(() -> {
            synchronized (NativeFollowLists.this) { refreshTimer = null; refreshAt = Long.MAX_VALUE; }
            try { refreshNow(false); }
            finally { scheduleRefresh(); }
        }, Math.max(0, next - now), TimeUnit.MILLISECONDS);
    }

    /** Every profile learned from the following and follower snapshots, for name and avatar lookups. */
    public static Map<String, ProfileListEntry> knownProfiles() {
        NativeFollowLists current = active;
        if (current == null) return Collections.emptyMap();
        Map<String, ProfileListEntry> merged = new java.util.LinkedHashMap<>();
        for (Map<String, ProfileListEntry> map : current.details.values()) {
            for (Map.Entry<String, ProfileListEntry> entry : map.entrySet()) {
                merged.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        return merged;
    }

    public static boolean isAvailable() { return active != null && !active.closed; }

    public static String currentAccountUid() {
        NativeFollowLists service = active;
        if (service == null || service.closed) return "";
        Account account = service.accounts.current();
        service.syncAccount(account);
        return account == null ? "" : account.uid;
    }

    /** Check at each automation entry and again before sending, including during a long fetch. */
    public static boolean ensureAccount(String uid) {
        NativeFollowLists service = active;
        if (service != null) {
            Account current = service.accounts.current();
            service.syncAccount(current);
            service.expire(System.currentTimeMillis());
            return current != null && current.uid.equals(FollowListSnapshot.normalizeUid(uid));
        }
        return true;
    }

    public static AutoCloseable listen(Runnable action) {
        listeners.add(action);
        return () -> listeners.remove(action);
    }

    public static boolean refresh(Runnable completed) {
        NativeFollowLists service = active;
        if (service == null || service.closed) return false;
        synchronized (service) {
            if (completed != null) service.refreshCallbacks.add(completed);
            if (service.refreshQueued) return true;
            service.refreshQueued = true;
        }
        service.worker.execute(() -> {
            try { service.refreshNow(true); }
            finally {
                service.scheduleRefresh();
                List<Runnable> callbacks;
                synchronized (service) {
                    service.refreshQueued = false;
                    callbacks = new ArrayList<>(service.refreshCallbacks);
                    service.refreshCallbacks.clear();
                }
                callbacks.forEach(service.main::post);
            }
        });
        return true;
    }

    public static Map<String, String> members(FollowListSnapshot.Kind kind) {
        NativeFollowLists service = active;
        if (service == null) return Collections.emptyMap();
        service.syncAccount(service.accounts.current());
        service.expire(System.currentTimeMillis());
        synchronized (service) {
            FollowListSnapshot.State state = FollowListSnapshot.current();
            boolean known = kind == FollowListSnapshot.Kind.FOLLOWING ? state.followingKnown : state.followersKnown;
            return known ? service.names.getOrDefault(kind, Collections.emptyMap()) : Collections.emptyMap();
        }
    }

    public static String memberName(String uid) {
        NativeFollowLists service = active;
        if (service == null) return "";
        service.syncAccount(service.accounts.current());
        service.expire(System.currentTimeMillis());
        synchronized (service) {
            for (Map<String, String> list : service.names.values()) {
                String name = list.get(uid);
                if (name != null && !name.isEmpty()) return name;
            }
            return "";
        }
    }

    public static Map<String, ProfileListEntry> memberDetails(FollowListSnapshot.Kind kind) {
        NativeFollowLists service = active;
        if (service == null) return Collections.emptyMap();
        service.syncAccount(service.accounts.current());
        service.expire(System.currentTimeMillis());
        synchronized (service) {
            Map<String, ProfileListEntry> result = service.details.get(kind);
            if (result != null) return result;
            Map<String, ProfileListEntry> fallback = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : service.names.getOrDefault(kind, Collections.emptyMap()).entrySet()) {
                fallback.put(entry.getKey(), new ProfileListEntry(entry.getKey(), entry.getValue(), ""));
            }
            return Collections.unmodifiableMap(fallback);
        }
    }

    public static ProfileListEntry findMember(String uid) {
        for (FollowListSnapshot.Kind kind : FollowListSnapshot.Kind.values()) {
            ProfileListEntry entry = memberDetails(kind).get(uid);
            if (entry != null) return entry;
        }
        return new ProfileListEntry(uid, AutomationScopePolicy.cachedName(uid), "");
    }

    public static String statusText() {
        NativeFollowLists service = active;
        if (service == null) return "请在抖音内查看关注与粉丝名单";
        service.syncAccount(service.accounts.current());
        service.expire(System.currentTimeMillis());
        synchronized (service) {
            FollowListSnapshot.State state = FollowListSnapshot.current();
            if (state.accountUid.isEmpty()) return "未登录抖音账号";
            return "账号 " + state.accountUid + "\n" + service.listStatus(FollowListSnapshot.Kind.FOLLOWING, state)
                    + "\n" + service.listStatus(FollowListSnapshot.Kind.FOLLOWERS, state);
        }
    }

    private String listStatus(FollowListSnapshot.Kind kind, FollowListSnapshot.State state) {
        boolean following = kind == FollowListSnapshot.Kind.FOLLOWING;
        boolean known = following ? state.followingKnown : state.followersKnown;
        Set<String> ids = following ? state.following : state.followers;
        long updated = following ? state.followingUpdatedAt : state.followersUpdatedAt;
        String text = following ? "我关注的：" : "关注我的：";
        if (known) text += ids.size() + " 人 · " + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(new java.util.Date(updated)) + " 更新";
        else text += "尚未加载";
        if (kind == loading || refreshQueued && loading == null) text += " · 拉取中";
        else if (errors.containsKey(kind)) text += " · " + errors.get(kind);
        return text;
    }

    private synchronized void syncAccount(Account account) {
        if (closed) return;
        String uid = account == null ? "" : account.uid;
        FollowListSnapshot.State previous = FollowListSnapshot.current();
        FollowListSnapshot.State current = FollowListSnapshot.activateAccount(uid);
        if (previous.accountEpoch != current.accountEpoch) {
            names.clear(); details.clear(); errors.clear(); attempts.clear(); refreshDeadlines.clear();
            notifyChanged();
            scheduleRefresh();
        }
    }

    private synchronized void expire(long now) {
        if (closed) return;
        FollowListSnapshot.State state = FollowListSnapshot.current();
        long elapsed = SystemClock.elapsedRealtime();
        if (state.followingKnown && (now - state.followingUpdatedAt >= TTL_MS
                || elapsed >= refreshDeadlines.getOrDefault(FollowListSnapshot.Kind.FOLLOWING, Long.MAX_VALUE))) {
            FollowListSnapshot.invalidate(FollowListSnapshot.Kind.FOLLOWING);
            names.remove(FollowListSnapshot.Kind.FOLLOWING);
            details.remove(FollowListSnapshot.Kind.FOLLOWING);
            refreshDeadlines.remove(FollowListSnapshot.Kind.FOLLOWING);
        }
        if (state.followersKnown && (now - state.followersUpdatedAt >= TTL_MS
                || elapsed >= refreshDeadlines.getOrDefault(FollowListSnapshot.Kind.FOLLOWERS, Long.MAX_VALUE))) {
            FollowListSnapshot.invalidate(FollowListSnapshot.Kind.FOLLOWERS);
            names.remove(FollowListSnapshot.Kind.FOLLOWERS);
            details.remove(FollowListSnapshot.Kind.FOLLOWERS);
            refreshDeadlines.remove(FollowListSnapshot.Kind.FOLLOWERS);
        }
    }

    void refreshNow(boolean force) {
        if (closed) return;
        Account account = accounts.current();
        syncAccount(account);
        long now = SystemClock.elapsedRealtime();
        expire(System.currentTimeMillis());
        if (account == null || account.uid.isEmpty()) { DouAiLog.event("relationship_lists", "eligibility", "account_unavailable", "ready=false"); return; }
        for (FollowListSnapshot.Kind kind : FollowListSnapshot.Kind.values()) {
            FollowListSnapshot.State state = FollowListSnapshot.current();
            boolean known = kind == FollowListSnapshot.Kind.FOLLOWING ? state.followingKnown : state.followersKnown;
            synchronized (this) {
                if (!force && (!needed(options, kind) || known || attempts.containsKey(kind) && now - attempts.get(kind) < RETRY_MS)) { DouAiLog.event("relationship_lists", "eligibility", "not_needed_cached_or_retry_delay", "kind=" + kind + ";cached=" + known + ";retry_delay_ms=" + RETRY_MS); continue; }
                attempts.put(kind, now);
                errors.remove(kind);
                loading = kind;
            }
            FollowListSnapshot.Refresh token = FollowListSnapshot.beginRefresh(account.uid, kind);
            if (token == null) { DouAiLog.event("relationship_lists", "eligibility", "refresh_token_unavailable", "kind=" + kind); loading = null; return; }
            DouAiLog.Operation trace = DouAiLog.begin("relationship_lists", force ? "manual_refresh" : "policy_refresh");
            trace.stage("fetch_pages", "kind=" + kind + ";account_ref=" + DouAiLog.reference(account.uid) + ";deadline_minutes=10");
            try {
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
                Map<String, ProfileListEntry> result = FollowListPager.loadDetails(FollowListPager.Kind.valueOf(kind.name()),
                        account.uid, account.secUid, fetcher,
                        () -> !closed && account.uid.equals(accounts.current().uid)
                                && account.uid.equals(FollowListSnapshot.current().accountUid)
                                && System.nanoTime() < deadline);
                syncAccount(accounts.current());
                synchronized (this) {
                    if (!closed && FollowListSnapshot.publish(token, result.keySet())) {
                        Map<String, String> loadedNames = new LinkedHashMap<>();
                        for (ProfileListEntry entry : result.values()) loadedNames.put(entry.uid, entry.name);
                        names.put(kind, Collections.unmodifiableMap(loadedNames));
                        details.put(kind, result);
                        refreshDeadlines.put(kind, SystemClock.elapsedRealtime() + TTL_MS);
                        errors.remove(kind);
                        trace.finish(DouAiLog.Outcome.SUCCESS, "snapshot_published", "kind=" + kind + ";members=" + result.size() + ";account_epoch_verified=true");
                        log.accept("Relationship list fetched: " + kind + " members=" + result.size());
                    } else {
                        trace.finish(DouAiLog.Outcome.CANCELLED, "stale_refresh_token", "kind=" + kind + ";snapshot_published=false");
                    }
                }
            } catch (Exception failure) {
                syncAccount(accounts.current());
                synchronized (this) {
                    if (account.uid.equals(FollowListSnapshot.current().accountUid)) errors.put(kind, "拉取失败，请重试");
                }
                trace.error("fetch_pages", failure);
                trace.finish(DouAiLog.Outcome.FAILED, "relationship_fetch_failed", "kind=" + kind + ";snapshot_published=false");
                log.accept("Relationship list fetch failed: " + kind + " " + failure.getClass().getSimpleName());
            } finally {
                loading = null;
                notifyChanged();
            }
            if (closed || !account.uid.equals(accounts.current().uid)) return;
        }
    }

    static boolean needed(LocalOptions options, FollowListSnapshot.Kind kind) {
        for (String prefix : new String[]{"comment", "reply"}) {
            if (!options.enabled("comment".equals(prefix) ? "native_auto_comment" : "native_auto_reply")) continue;
            if ("reply".equals(prefix) && !options.enabled("reply_private_chats", true)) continue;
            String mode = "reply".equals(prefix) ? ReplyScopePolicy.scopeMode(options)
                    : ContentScopePolicy.scopeMode(prefix, options);
            if ("all".equals(mode)) continue;
            Set<String> selected = AutomationScopePolicy.targets(options.text(prefix + "_scope_relations", ""));
            if (selected.contains(AutomationScopePolicy.STRANGERS)
                    || selected.contains(kind == FollowListSnapshot.Kind.FOLLOWING
                    ? AutomationScopePolicy.FOLLOWING : AutomationScopePolicy.FOLLOWERS)) return true;
        }
        return false;
    }

    private void notifyChanged() { for (Runnable action : listeners) main.post(action); }

    private static Account readAccount(ClassLoader loader) {
        try {
            Object user = Class.forName("com.ss.android.ugc.aweme.account.AccountProxyService", false, loader)
                    .getMethod("userService").invoke(null);
            if (!Boolean.TRUE.equals(HostTargets.invoke(user, "isLogin"))) return new Account("", "");
            Object uid = HostTargets.invoke(user, "getCurUserId");
            Object sec = HostTargets.invoke(user, "getCurSecUserId");
            return new Account(uid instanceof String ? (String) uid : "", sec instanceof String ? (String) sec : "");
        } catch (ReflectiveOperationException | RuntimeException failure) { return new Account("", ""); }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        worker.shutdownNow();
        if (active == this) { active = null; FollowListSnapshot.activateAccount(""); }
    }

    private static final class NativeFetcher implements FollowListPager.Fetcher {
        private final ClassLoader loader;
        private final HostScanReport report;
        private final Map<FollowListPager.Kind, Object> services = new EnumMap<>(FollowListPager.Kind.class);
        private final Map<FollowListPager.Kind, Method> methods = new EnumMap<>(FollowListPager.Kind.class);

        NativeFetcher(ClassLoader loader, HostScanReport report) { this.loader = loader; this.report = report; }

        @Override public Object fetch(FollowListPager.Kind kind, Map<String, String> parameters) throws Exception {
            if (!services.containsKey(kind)) {
                boolean following = kind == FollowListPager.Kind.FOLLOWING;
                Method method = HostTargets.method(report, following ? "relation_following_list" : "relation_follower_list", loader);
                String endpoint = following ? "/aweme/v1/user/following/list/" : "/aweme/v1/user/follower/list/";
                if (!hasGetEndpoint(method, endpoint) || !method.getDeclaringClass().isInterface()
                        || method.getParameterCount() != 1 || method.getParameterTypes()[0] != Map.class
                        || !"io.reactivex.Observable".equals(method.getReturnType().getName())) {
                    throw new IllegalStateException("Relationship API contract changed");
                }
                Object provider = HostTargets.method(report, "relation_retrofit_service", loader).invoke(null);
                Object prefix = Class.forName("com.ss.android.ugc.aweme.app.api.Api", false, loader)
                        .getField("API_URL_PREFIX_SI").get(null);
                Class<?> factory = Class.forName("com.ss.android.ugc.aweme.framework.services.IRetrofitService", false, loader);
                Object retrofit = factory.getMethod("createNewRetrofit", String.class).invoke(provider, prefix);
                Object service = Class.forName("com.ss.android.ugc.aweme.framework.services.IRetrofit", false, loader)
                        .getMethod("create", Class.class).invoke(retrofit, method.getDeclaringClass());
                if (!method.getDeclaringClass().isInstance(service)) throw new IllegalStateException("Empty relationship API");
                services.put(kind, service);
                methods.put(kind, method);
            }
            Object observable = methods.get(kind).invoke(services.get(kind), parameters);
            Class<?> type = Class.forName("io.reactivex.Observable", false, loader);
            Object bounded = type.getMethod("timeout", long.class, TimeUnit.class).invoke(observable, 20L, TimeUnit.SECONDS);
            return type.getMethod("blockingFirst").invoke(bounded);
        }

        private static boolean hasGetEndpoint(Method method, String endpoint) throws Exception {
            for (Annotation annotation : method.getAnnotations()) {
                if ("retrofit2.http.GET".equals(annotation.annotationType().getName())
                        && endpoint.equals(annotation.annotationType().getMethod("value").invoke(annotation))) return true;
            }
            return false;
        }
    }
}
