package com.douai.module.feature;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Loads a complete native relationship list before exposing any membership. */
public final class FollowListPager {
    static final int MAX_PAGES = 5000;
    private static final java.util.concurrent.atomic.AtomicBoolean PROBED =
            new java.util.concurrent.atomic.AtomicBoolean();
    static final int MAX_USERS = 100000;

    public enum Kind { FOLLOWING, FOLLOWERS }

    @FunctionalInterface
    public interface Fetcher {
        Object fetch(Kind kind, Map<String, String> query) throws Exception;
    }

    private FollowListPager() { }

    public static Map<String, String> load(Kind kind, String accountUid, String secUid,
                                           Fetcher fetcher, BooleanSupplier accountCurrent) throws Exception {
        Map<String, ProfileListEntry> details = loadDetails(kind, accountUid, secUid, fetcher, accountCurrent);
        Map<String, String> names = new LinkedHashMap<>();
        for (ProfileListEntry entry : details.values()) names.put(entry.uid, entry.name);
        return Collections.unmodifiableMap(names);
    }

    public static Map<String, ProfileListEntry> loadDetails(Kind kind, String accountUid, String secUid,
                                           Fetcher fetcher, BooleanSupplier accountCurrent) throws Exception {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(fetcher, "fetcher");
        Objects.requireNonNull(accountCurrent, "accountCurrent");
        String uid = uid(accountUid);
        if (uid.isEmpty()) throw new IllegalArgumentException("Invalid account UID");
        if (secUid == null || secUid.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing account sec UID");
        }
        Map<String, ProfileListEntry> users = new LinkedHashMap<>();
        Set<Cursor> seen = new HashSet<>();
        Cursor cursor = new Cursor(0, 0, 0, "", 0);
        seen.add(cursor);
        for (int page = 0; page < MAX_PAGES; page++) {
            requireCurrent(accountCurrent);
            Object response = fetcher.fetch(kind, query(kind, uid, secUid.trim(), cursor));
            requireCurrent(accountCurrent);
            requireSuccess(response);
            boolean hasMore = hasMore(response);
            Object items = value(response, kind == Kind.FOLLOWING ? "followings" : "followers",
                    new String[]{"items"}, new String[]{"getItems"});
            if (!(items instanceof Iterable<?>)) throw failure("Missing relationship users");
            int rows = 0;
            for (Object user : (Iterable<?>) items) {
                if (++rows > MAX_USERS) throw failure("Relationship page exceeds user limit");
                requireCurrent(accountCurrent);
                if (user == null) throw failure("Null relationship user");
                if (!member(kind, user)) continue;
                String memberUid = uid(value(user, "uid", new String[]{"uid"}, new String[]{"getUid"}));
                if (memberUid.isEmpty()) throw failure("Relationship user has no valid UID");
                Object nameValue = value(user, "nickname", new String[]{"nickname"}, new String[]{"getNickname"});
                String name = nameValue instanceof String ? ((String) nameValue).trim() : "";
                if (!users.containsKey(memberUid) || users.get(memberUid).name.isEmpty()) {
                    users.put(memberUid, new ProfileListEntry(memberUid, name, ProfileListEntry.avatar(user)));
                }
                if (users.size() > MAX_USERS) throw failure("Relationship list exceeds user limit");
            }
            if (kind == Kind.FOLLOWING && page == 0 && PROBED.compareAndSet(false, true)) {
                probeFollowing(kind, uid, secUid.trim(), fetcher, response, users.keySet());
            }
            requireCurrent(accountCurrent);
            // Each page is recorded so a premature stop can be told apart from a short list.
            com.douai.module.diagnostics.DouAiLog.event("auto_reply", "follow_paging", "page",
                    "kind=" + kind + ";n=" + page + ";rows=" + rows + ";more=" + hasMore
                            + ";got=" + users.size() + ";max=" + cursor.maxTime + ";off=" + cursor.offset);
            if (!hasMore) return Collections.unmodifiableMap(new LinkedHashMap<>(users));
            if (rows == 0) throw failure("Empty relationship page still has more users");
            cursor = next(kind, response, cursor, rows);
            if (!seen.add(cursor)) throw failure("Relationship pagination repeated a cursor");
        }
        throw failure("Relationship pagination exceeds page limit");
    }

    private static Map<String, String> query(Kind kind, String uid, String secUid, Cursor cursor) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("user_id", uid);
        query.put("sec_user_id", secUid);
        query.put("max_time", Long.toString(cursor.maxTime));
        query.put("count", "20");
        query.put("offset", Integer.toString(cursor.offset));
        query.put("source_type", "1");
        query.put("address_book_access", "0");
        query.put("gps_access", "0");
        query.put("vcd_count", Integer.toString(cursor.vcdCount));
        query.put("store_page", cursor.storePage);
        if (kind == Kind.FOLLOWING) {
            query.put("min_time", Long.toString(cursor.minTime));
            query.put("vcd_auth_first_time", "0");
            query.put("relation_filter_type", "0");
            query.put("scene", "0");
            query.put("filter_type", "0");
            query.put("insert_unread_uids", "");
        }
        return Collections.unmodifiableMap(query);
    }

    private static Cursor next(Kind kind, Object response, Cursor previous, int rows) {
        // FOLLOWING pages on max_time, FOLLOWERS on min_time; swapping them sends a zero cursor
        // on the second page and the endpoint answers with an empty list.
        long maxTime = kind == Kind.FOLLOWING
                ? requiredNonnegative(value(response, "max_time", new String[]{"maxTime"},
                new String[]{"getMaxTime"}), "max_time")
                : requiredNonnegative(value(response, "min_time", new String[]{"minTime"},
                new String[]{"getMinTime"}), "min_time");
        long minTime = kind == Kind.FOLLOWING
                ? requiredNonnegative(value(response, "min_time", new String[]{"minTime"},
                new String[]{"getMinTime"}), "min_time") : 0;
        // The endpoint reports offset 0 on every page, so the running total is the only usable
        // cursor when a time based one repeats the previous page.
        int reported = requiredInt(value(response, "offset", new String[]{"offset"},
                new String[]{"getOffset"}), "offset");
        int offset = reported > 0 ? reported : previous.offset + rows;
        Object storeValue = value(response, "store_page", new String[]{"storePage"},
                new String[]{"getStorePage"});
        if (storeValue != null && !(storeValue instanceof String)) throw failure("Invalid store_page");
        String storePage = storeValue instanceof String && !((String) storeValue).isEmpty()
                ? (String) storeValue : previous.storePage;
        Object vcdValue = value(response, "vcd_count", new String[]{"vcdCount"},
                new String[]{"getVcdCount"});
        int vcdCount = vcdValue == null ? previous.vcdCount : requiredInt(vcdValue, "vcd_count");
        com.douai.module.diagnostics.DouAiLog.event("auto_reply", "follow_paging", "cursor",
                "kind=" + kind + ";max=" + maxTime + ";min=" + minTime + ";off=" + offset);
        return new Cursor(maxTime, minTime, offset, storePage, vcdCount);
    }

    private static boolean member(Kind kind, Object user) {
        Long follow = integer(value(user, "follow_status", new String[]{"followStatus"},
                new String[]{"getFollowStatus"}));
        if (follow != null && (follow < 0 || follow > 2)) throw failure("Invalid relationship follow_status");
        if (kind == Kind.FOLLOWING) {
            if (follow == null) throw failure("Missing relationship follow_status");
            return follow == 1 || follow == 2;
        }
        Long follower = integer(value(user, "follower_status", new String[]{"followerStatus"},
                new String[]{"getFollowerStatus"}));
        if (follower != null && (follower < 0 || follower > 1)) throw failure("Invalid relationship follower_status");
        if (follow != null && follow == 2) return true;
        if (follower == null) throw failure("Missing relationship follower_status");
        return follower == 1;
    }

    private static void requireSuccess(Object response) {
        if (response == null) throw failure("Missing relationship response");
        Object status = value(response, "status_code", new String[]{"status_code", "statusCode"},
                new String[]{"getStatusCode"});
        Object error = value(response, "error_code", new String[]{"error_code", "errorCode"},
                new String[]{"getErrorCode"});
        if (status == null && error == null) throw failure("Missing relationship response status");
        if (status != null && requiredInteger(status, "status_code") != 0
                || error != null && requiredInteger(error, "error_code") != 0) {
            throw failure("Relationship API returned an error");
        }
    }

    /** Asks the endpoint the same second page four ways so one run identifies the real cursor. */
    private static void probeFollowing(Kind kind, String uid, String secUid, Fetcher fetcher,
                                       Object first, Set<String> seen) {
        try {
            long respMax = requiredNonnegative(value(first, "max_time", new String[]{"maxTime"},
                    new String[]{"getMaxTime"}), "max_time");
            long respMin = requiredNonnegative(value(first, "min_time", new String[]{"minTime"},
                    new String[]{"getMinTime"}), "min_time");
            Object storeValue = value(first, "store_page", new String[]{"storePage"}, new String[]{"getStorePage"});
            String storePage = storeValue instanceof String ? (String) storeValue : "";
            long[][] times = {{respMax, respMin}, {respMin, 0}, {0, 0}, {respMax, respMin}};
            int[] offsets = {0, 30, 30, 30};
            for (int index = 0; index < times.length; index++) {
                Object response;
                int rows = 0;
                int fresh = 0;
                try {
                    response = fetcher.fetch(kind, query(kind, uid, secUid,
                            new Cursor(times[index][0], times[index][1], offsets[index], storePage, 0)));
                    Object items = value(response, "followings", new String[]{"items"}, new String[]{"getItems"});
                    if (items instanceof Iterable<?>) {
                        for (Object user : (Iterable<?>) items) {
                            rows++;
                            String memberUid = uid(value(user, "uid", new String[]{"uid"}, new String[]{"getUid"}));
                            if (!memberUid.isEmpty() && !seen.contains(memberUid)) fresh++;
                        }
                    }
                } catch (Throwable failure) {
                    com.douai.module.diagnostics.DouAiLog.event("auto_reply", "follow_paging", "probe",
                            "v=" + index + ";error=" + failure.getClass().getSimpleName());
                    continue;
                }
                com.douai.module.diagnostics.DouAiLog.event("auto_reply", "follow_paging", "probe",
                        "v=" + index + ";max=" + times[index][0] + ";min=" + times[index][1]
                                + ";off=" + offsets[index] + ";rows=" + rows + ";fresh=" + fresh);
            }
        } catch (Throwable failure) {
            com.douai.module.diagnostics.DouAiLog.event("auto_reply", "follow_paging", "probe_failed",
                    failure.getClass().getSimpleName());
        }
    }

    private static boolean hasMore(Object response) {
        Object value = value(response, "has_more", new String[]{"hasMore", "isHasMore"},
                new String[]{"isHasMore", "getHasMore"});
        if (value instanceof Boolean) return (Boolean) value;
        Long number = integer(value);
        if (number != null && (number == 0 || number == 1)) return number == 1;
        throw failure("Missing or invalid relationship has_more");
    }

    private static Object value(Object model, String jsonName, String[] fields, String[] getters) {
        Object result = DownloadActionHook.jsonValue(model, jsonName);
        if (result != null) return result;
        for (String field : fields) {
            result = HostTargets.read(model, field);
            if (result != null) return result;
        }
        for (String getter : getters) {
            result = HostTargets.invoke(model, getter);
            if (result != null) return result;
        }
        return null;
    }

    private static long requiredInteger(Object value, String field) {
        Long number = integer(value);
        if (number == null) throw failure("Missing or invalid relationship " + field);
        return number;
    }

    private static long requiredNonnegative(Object value, String field) {
        long number = requiredInteger(value, field);
        if (number < 0) throw failure("Negative relationship " + field);
        return number;
    }

    private static int requiredInt(Object value, String field) {
        long number = requiredNonnegative(value, field);
        if (number > Integer.MAX_VALUE) throw failure("Relationship " + field + " exceeds integer limit");
        return (int) number;
    }

    private static Long integer(Object value) {
        return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                ? ((Number) value).longValue() : null;
    }

    private static String uid(Object value) {
        String uid = value instanceof String ? ((String) value).trim() : "";
        return uid.matches("[0-9]{1,20}") ? uid : "";
    }

    private static void requireCurrent(BooleanSupplier accountCurrent) {
        if (Thread.currentThread().isInterrupted() || !accountCurrent.getAsBoolean()) {
            throw failure("Relationship loading cancelled or account changed");
        }
    }

    private static IllegalStateException failure(String message) { return new IllegalStateException(message); }

    private static final class Cursor {
        final long maxTime;
        final long minTime;
        final int offset;
        final String storePage;
        final int vcdCount;
        Cursor(long maxTime, long minTime, int offset, String storePage, int vcdCount) {
            this.maxTime = maxTime; this.minTime = minTime; this.offset = offset;
            this.storePage = storePage; this.vcdCount = vcdCount;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Cursor)) return false;
            Cursor cursor = (Cursor) other;
            return maxTime == cursor.maxTime && minTime == cursor.minTime && offset == cursor.offset
                    && storePage.equals(cursor.storePage);
        }
        @Override public int hashCode() { return Objects.hash(maxTime, minTime, offset, storePage); }
    }
}
