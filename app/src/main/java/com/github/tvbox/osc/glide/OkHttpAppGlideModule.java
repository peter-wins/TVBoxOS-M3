package com.github.tvbox.osc.glide;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;

import java.io.InputStream;

@GlideModule
public class OkHttpAppGlideModule extends AppGlideModule {
    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
        registry.replace(GlideUrl.class, InputStream.class, new OkHttpUrlLoader.Factory(com.github.catvod.net.OkHttp.client()));
    }

    // 内存优化：限制 Glide 内存缓存，避免低内存电视被系统杀死
    @Override
    public void applyOptions(@NonNull Context context, @NonNull com.bumptech.glide.GlideBuilder builder) {
        builder.setMemoryCache(new com.bumptech.glide.load.engine.cache.LruResourceCache(16 * 1024 * 1024));
    }

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
