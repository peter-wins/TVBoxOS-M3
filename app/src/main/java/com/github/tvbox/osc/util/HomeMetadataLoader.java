package com.github.tvbox.osc.util;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;

import java.io.IOException;
import java.io.Reader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Small, cancellable metadata requests, isolated from detail/playback LiveData. */
public final class HomeMetadataLoader {
    public interface ExtensionResolver { String resolve(String extension); }
    public interface Callback { void complete(HomeMetadata metadata); }

    // Plugins may ignore interruption. Bound both running work and queued work in that case.
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), runnable -> {
                Thread thread = new Thread(runnable, "home-metadata");
                thread.setDaemon(true);
                return thread;
            });
    static { WORKERS.allowCoreThreadTimeOut(true); }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExtensionResolver extensionResolver;
    private final Map<String, CacheEntry> cache = new LinkedHashMap<String, CacheEntry>(32, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> entry) { return size() > 32; }
    };

    public HomeMetadataLoader(ExtensionResolver extensionResolver) {
        this.extensionResolver = extensionResolver;
    }

    public static final class Pending {
        private volatile boolean cancelled;
        private volatile boolean started;
        private volatile Future<?> future;
        private volatile Call call;

        public void cancel() {
            cancelled = true;
            if (call != null) call.cancel();
            // A plugin may dispatch into its own JS executor. Do not interrupt the waiting
            // caller and leave an unbounded queue behind it; keep active plugin work bounded.
            if (future != null && !started) future.cancel(false);
            WORKERS.purge();
        }

        private void checkActive() {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException();
        }
    }

    private static final class CacheEntry {
        final HomeMetadata metadata;
        final long expires;
        CacheEntry(HomeMetadata metadata) {
            this.metadata = metadata;
            expires = SystemClock.elapsedRealtime() + (metadata == null ? 45_000L : 30 * 60_000L);
        }
    }

    public Pending load(Movie.Video video, Callback callback) {
        Pending pending = new Pending();
        String key = String.valueOf(video.sourceKey) + '\u0000' + video.id + '\u0000' + video.doubanId;
        synchronized (cache) {
            CacheEntry entry = cache.get(key);
            if (entry != null && SystemClock.elapsedRealtime() < entry.expires) {
                main.post(() -> { if (!pending.cancelled) callback.complete(entry.metadata); });
                return pending;
            }
        }
        try {
            pending.future = WORKERS.submit(() -> {
                pending.started = true;
                HomeMetadata metadata = null;
                try {
                    pending.checkActive();
                    metadata = fetch(video, pending);
                    pending.checkActive();
                    synchronized (cache) { cache.put(key, new CacheEntry(metadata)); }
                } catch (CancellationException ignored) {
                    return;
                } catch (Exception e) {
                    if (pending.cancelled) return;
                    synchronized (cache) { cache.put(key, new CacheEntry(null)); }
                    LOG.i("Home description unavailable: " + e.getClass().getSimpleName());
                }
                HomeMetadata result = metadata;
                main.post(() -> { if (!pending.cancelled) callback.complete(result); });
            });
        } catch (RejectedExecutionException busy) {
            main.post(() -> { if (!pending.cancelled) callback.complete(null); });
        }
        return pending;
    }

    private HomeMetadata fetch(Movie.Video video, Pending pending) throws Exception {
        if (video.doubanId != null && video.doubanId.matches("[0-9]+")) {
            // Public hot cards are not source-bound. Reuse the same data as Continue Watching
            // before attempting a public site that may redirect the TV to a verification page.
            java.util.List<VodInfo> records = RoomDataManger.getAllVodRecord(100);
            if (records != null) {
                for (VodInfo record : records) {
                    pending.checkActive();
                    if (HomeMetadata.sameTitle(video.name, record.name)
                            && (video.year <= 0 || record.year <= 0 || video.year == record.year)) {
                        HomeMetadata metadata = HomeMetadata.fromDescription(record.des);
                        if (metadata != null) return metadata;
                    }
                }
            }
            HomeMetadata metadata = null;
            try {
                metadata = searchConfiguredSource(video, pending);
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (Exception unavailable) {
                LOG.i("Home description: current source did not provide a summary");
            }
            if (metadata != null) return metadata;
            pending.checkActive();
            String html = read(new Request.Builder().url("https://movie.douban.com/subject/" + video.doubanId + "/")
                    .header("User-Agent", UA.randomOne()).build(), pending);
            return HomeMetadata.fromDouban(html, video.doubanId);
        }
        SourceBean source = ApiConfig.get().getSource(video.sourceKey);
        if (source == null || video.id == null || video.id.startsWith("msearch:") || video.action != null) return null;
        return sourceDetail(source, video.id, video.name, pending);
    }

    private HomeMetadata searchConfiguredSource(Movie.Video video, Pending pending) throws Exception {
        SourceBean source = ApiConfig.get().getHomeSourceBean();
        if (source == null) return null;
        String body;
        if (source.getType() == 3) {
            body = ApiConfig.get().getCSP(source).searchContent(video.name, false);
        } else if (source.getType() == 0 || source.getType() == 1 || source.getType() == 4) {
            HttpUrl.Builder url = sourceUrl(source, pending);
            if (url == null) return null;
            body = read(new Request.Builder().url(url.setQueryParameter("wd", video.name).build()).build(), pending);
        } else {
            return null;
        }
        pending.checkActive();
        boolean xml = source.getType() == 0;
        String id = HomeMetadata.findExactSourceId(body, xml, video.name, video.year);
        if (id == null) return null;
        HomeMetadata metadata = HomeMetadata.fromSource(body, xml, id, video.name);
        return metadata != null ? metadata : sourceDetail(source, id, video.name, pending);
    }

    private HomeMetadata sourceDetail(SourceBean source, String id, String name, Pending pending) throws Exception {
        pending.checkActive();
        if (source.getType() == 3) {
            String body = ApiConfig.get().getCSP(source).detailContent(Collections.singletonList(id));
            pending.checkActive();
            return HomeMetadata.fromSource(body, false, id, name);
        }
        HttpUrl.Builder url = sourceUrl(source, pending);
        if (url == null) return null;
        String body = read(new Request.Builder().url(url.setQueryParameter("ids", id).build()).build(), pending);
        return HomeMetadata.fromSource(body, source.getType() == 0, id, name);
    }

    private HttpUrl.Builder sourceUrl(SourceBean source, Pending pending) {
        if (source.getType() != 0 && source.getType() != 1 && source.getType() != 4) return null;
        HttpUrl api = HttpUrl.parse(source.getApi());
        if (api == null) return null;
        HttpUrl.Builder url = api.newBuilder().setQueryParameter("ac", source.getType() == 0 ? "videolist" : "detail");
        String extension = source.getExt();
        if (extension != null && !extension.isEmpty()) {
            extension = extensionResolver.resolve(extension);
            pending.checkActive();
            if (extension != null && !extension.isEmpty()) url.setQueryParameter("extend", extension);
        }
        return url;
    }

    private String read(Request request, Pending pending) throws IOException {
        pending.checkActive();
        OkHttpClient client = OkGoHelper.getDefaultClient();
        if (client == null) client = com.github.catvod.net.OkHttp.client();
        pending.call = client.newBuilder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS).build().newCall(request);
        pending.checkActive();
        try (Response response = pending.call.execute()) {
            if (!response.isSuccessful() || response.body() == null) return "";
            try (Reader reader = response.body().charStream()) {
                StringBuilder body = new StringBuilder();
                char[] buffer = new char[8192];
                int count;
                while ((count = reader.read(buffer)) != -1) {
                    pending.checkActive();
                    if (body.length() + count > 1024 * 1024) throw new IOException("Metadata response too large");
                    body.append(buffer, 0, count);
                }
                return body.toString();
            }
        }
    }
}
