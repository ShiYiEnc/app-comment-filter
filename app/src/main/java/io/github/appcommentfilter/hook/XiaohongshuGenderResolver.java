package io.github.appcommentfilter.hook;

import android.os.Handler;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Uses only the verified 9.52.0 host profile service; never opens a profile screen. */
final class XiaohongshuGenderResolver {
    private final ScheduledThreadPoolExecutor worker;
    private final XiaohongshuGenderCache cache;
    private final XiaohongshuGenderTasks requests;
    private final Handler main;
    private final Runnable changed;
    private final Object notificationLock = new Object();
    private boolean enabled, closed;
    private long generation, notificationGeneration = -1;

    XiaohongshuGenderResolver(ClassLoader loader, Handler main, Runnable changed) throws ReflectiveOperationException {
        HostTransport transport = new HostTransport(loader);
        this.main = main; this.changed = changed;
        worker = new ScheduledThreadPoolExecutor(1, command -> {
            Thread thread = new Thread(command, "xhs-profile-gender"); thread.setDaemon(true); return thread;
        });
        worker.setRemoveOnCancelPolicy(true);
        requests = new XiaohongshuGenderTasks();
        cache = new XiaohongshuGenderCache(new XiaohongshuGenderCache.Scheduler() {
            @Override public long now() { return SystemClock.elapsedRealtime(); }
            @Override public XiaohongshuGenderCache.Cancel schedule(Runnable command, long delayMillis) {
                java.util.concurrent.ScheduledFuture<?> future = worker.schedule(command, delayMillis, TimeUnit.MILLISECONDS);
                return () -> future.cancel(false);
            }
        }, (userId, completion) -> requests.submit(() -> transport.request(userId, completion), completion::failure), this::notifyChanged);
    }

    void setEnabled(boolean value) {
        synchronized (notificationLock) {
            if (closed || enabled == value) return;
            enabled = value; generation++; notificationGeneration = -1;
            cache.setEnabled(value);
        }
    }

    XiaohongshuGenderCache.Result lookup(String userId) { return cache.lookup(userId); }
    XiaohongshuGenderCache.Result lookup(String userId, boolean mayRequest) { return cache.lookup(userId, mayRequest); }

    void close() {
        setEnabled(false);
        synchronized (notificationLock) { closed = true; }
        cache.close(); worker.shutdownNow(); requests.close();
    }

    private void notifyChanged() {
        synchronized (notificationLock) {
            if (!enabled || closed || notificationGeneration == generation) return;
            final long expected = generation;
            notificationGeneration = expected;
            main.post(() -> {
                synchronized (notificationLock) {
                    if (notificationGeneration == expected) notificationGeneration = -1;
                    if (enabled && !closed && generation == expected) changed.run();
                }
            });
        }
    }

    /** Reflection binds the actual obfuscated Rx2 interfaces shipped in the supplied APK. */
    private static final class HostTransport implements XiaohongshuGenderCache.Transport {
        private final Class<?> serviceType, profileType, observableType, consumerType, actionType;
        private final Field factoryInstance;
        private final Method createService, getUserInfo, getUserid, getGender, subscribe, dispose;
        private Field preloadInstance;
        private Method preloadRead;

        HostTransport(ClassLoader loader) throws ReflectiveOperationException {
            serviceType = Class.forName("com.xingin.matrix.profile.services.UserServices", false, loader);
            profileType = Class.forName("com.xingin.account.entities.UserInfo", false, loader);
            observableType = Class.forName("io.reactivex.Observable", false, loader);
            consumerType = Class.forName("iab.g", false, loader);
            actionType = Class.forName("iab.a", false, loader);
            Class<?> disposableType = Class.forName("fab.b", false, loader);
            Class<?> factory = Class.forName("gn8.c", false, loader);
            factoryInstance = factory.getField("a");
            createService = factory.getMethod("b", Class.class);
            getUserInfo = serviceType.getMethod("getUserInfo", Map.class);
            getUserid = profileType.getMethod("getUserid");
            getGender = profileType.getMethod("getGender");
            consumerType.getMethod("accept", Object.class);
            actionType.getMethod("run");
            subscribe = observableType.getMethod("subscribe", consumerType, consumerType, actionType);
            dispose = disposableType.getMethod("dispose");
            if (getUserInfo.getReturnType() != observableType || getUserid.getReturnType() != String.class
                    || getGender.getReturnType() != int.class || subscribe.getReturnType() != disposableType
                    || !consumerType.isInterface() || !actionType.isInterface()) {
                throw new NoSuchMethodException("Unexpected profile service/Rx schema");
            }
            // This cache is optional: an unavailable preload optimization cannot disable
            // the separately verified profile request path.
            try {
                Class<?> preload = Class.forName("f95.b", false, loader);
                preloadInstance = preload.getField("a");
                preloadRead = preload.getMethod("a", String.class, String.class, String.class);
            } catch (ReflectiveOperationException ignored) { preloadInstance = null; preloadRead = null; }
        }

        @Override public XiaohongshuGenderCache.Cancel request(String userId, XiaohongshuGenderCache.Completion completion)
                throws ReflectiveOperationException {
            AtomicBoolean finished = new AtomicBoolean();
            if (preloadRead != null) {
                try {
                    Object existing = preloadRead.invoke(preloadInstance.get(null), userId,
                            "ProfilePreloadUserInfo", "CommentFilter");
                    if (profileType.isInstance(existing) && userId.equals(getUserid.invoke(existing))) {
                        completion.success(userId, ((Number) getGender.invoke(existing)).intValue());
                        return () -> { };
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) { /* Fall through to host service. */ }
            }
            Object service = createService.invoke(factoryInstance.get(null), serviceType);
            Map<String, String> parameters = new LinkedHashMap<>();
            parameters.put("user_id", userId);
            parameters.put("profile_page_head_exp", "1");
            parameters.put("new_page_exp", "0");
            parameters.put("load_widgets_opt_exp", "0");
            parameters.put("ext_params", "");
            Object observable = getUserInfo.invoke(service, parameters);
            if (!observableType.isInstance(observable)) throw new IllegalStateException("No profile observable");
            Object next = callback(consumerType, "accept", value -> {
                if (!finished.compareAndSet(false, true)) return;
                try {
                    if (!profileType.isInstance(value)) { completion.failure(); return; }
                    completion.success((String) getUserid.invoke(value), ((Number) getGender.invoke(value)).intValue());
                } catch (ReflectiveOperationException | RuntimeException ignored) { completion.failure(); }
            });
            Object error = callback(consumerType, "accept", ignored -> {
                if (finished.compareAndSet(false, true)) completion.failure();
            });
            Object complete = callback(actionType, "run", ignored -> {
                if (finished.compareAndSet(false, true)) completion.failure();
            });
            Object subscription = subscribe.invoke(observable, next, error, complete);
            return () -> {
                finished.set(true);
                try { dispose.invoke(subscription); } catch (ReflectiveOperationException | RuntimeException ignored) { /* Stale result is ignored by generation checks. */ }
            };
        }

        private static Object callback(Class<?> type, String operation, java.util.function.Consumer<Object> body) {
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "CommentFilterProfileCallback";
                        default -> null;
                    };
                }
                if (operation.equals(method.getName())) body.accept(args == null || args.length == 0 ? null : args[0]);
                return null;
            });
        }
    }
}
