package com.github.tvbox.osc.base;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.os.SystemClock;
import androidx.multidex.MultiDexApplication;

import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.callback.EmptyCallback;
import com.github.tvbox.osc.callback.LoadingCallback;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.AppManager;
import com.github.tvbox.osc.util.EpgUtil;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.PlayerHelper;
import com.kingja.loadsir.core.LoadSir;
import com.orhanobut.hawk.Hawk;
import com.p2p.P2PClass;
import com.whl.quickjs.android.QuickJSLoader;
import com.github.catvod.crawler.JsLoader;

import me.jessyan.autosize.AutoSizeConfig;
import me.jessyan.autosize.unit.Subunits;

/**
 * @author pj567
 * @date :2020/12/17
 * @description:
 */
public class App extends MultiDexApplication {
    private static App instance;

    private static P2PClass p;
    public static String burl;
    private static String dashData;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        long stageStarted = SystemClock.elapsedRealtime();
        initParams();
        stageStarted = logStartupStage("Hawk", stageStarted);
        // OKGo
        OkGoHelper.init(); //台标获取
        EpgUtil.init();
        stageStarted = logStartupStage("HTTP and EPG", stageStarted);
        // 探测 FFmpeg 软解是否可用（用于日志诊断）
        try {
            LOG.i("FFmpeg available=" + xyz.doikki.videoplayer.exo.FfmpegSupport.isAvailable()
                    + " version=" + xyz.doikki.videoplayer.exo.FfmpegSupport.version());
        } catch (Throwable th) {
            LOG.i("FFmpeg probe failed: " + th.getClass().getSimpleName());
        }
        stageStarted = logStartupStage("FFmpeg probe", stageStarted);
        // Media3 升级后的一次性配置迁移
        migratePlayerConfigOnce();
        stageStarted = logStartupStage("player config migrate", stageStarted);
        // 初始化Web服务器
        ControlManager.init(this);
        //初始化数据库
        AppDataManager.init();
        stageStarted = logStartupStage("Control server and Room", stageStarted);
        LoadSir.beginBuilder()
                .addCallback(new EmptyCallback())
                .addCallback(new LoadingCallback())
                .commit();
        AutoSizeConfig.getInstance().setCustomFragment(true).getUnitsManager()
                .setSupportDP(false)
                .setSupportSP(false)
                .setSupportSubunits(Subunits.MM);
        PlayerHelper.init();
        stageStarted = logStartupStage("UI and player", stageStarted);
        QuickJSLoader.init();
        stageStarted = logStartupStage("QuickJS", stageStarted);
        FileUtils.cleanPlayerCache();
        logStartupStage("Player cache cleanup", stageStarted);
    }

    private long logStartupStage(String stage, long started) {
        long now = SystemClock.elapsedRealtime();
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            LOG.i("Startup " + stage + " " + (now - started) + " ms");
        }
        return now;
    }

    /**
     * Media3 升级后的一次性配置迁移。
     *
     * 升级前用户可能把播放器切到了 IJK（1），但 Media3 才是本次升级的主力，
     * 且 IJK 的 native 内存管理有缺陷。这里在升级后的首次启动把点播/直播
     * 播放器统一重置为 EXO（2）、渲染方式重置为 SurfaceView（1），
     * 避免老配置让新能力失效。只执行一次，之后用户仍可自行修改。
     */
    private void migratePlayerConfigOnce() {
        try {
            final int CURRENT_CONFIG_VERSION = 2;
            int version = Hawk.get(HawkConfig.CONFIG_VERSION, 0);
            if (version >= CURRENT_CONFIG_VERSION) return;

            Hawk.put(HawkConfig.PLAY_TYPE, 2);          // EXO（Media3）
            Hawk.put(HawkConfig.LIVE_PLAY_TYPE, 2);     // 直播也用 EXO
            Hawk.put(HawkConfig.PLAY_RENDER, 1);        // SurfaceView（画质更好的直通渲染）
            Hawk.put(HawkConfig.CONFIG_VERSION, CURRENT_CONFIG_VERSION);

            LOG.i("player config migrated to Media3: playType=EXO render=SurfaceView");
        } catch (Throwable th) {
            LOG.i("player config migrate failed: " + th.getClass().getSimpleName());
        }
    }

    private void initParams() {
        // Hawk
        Hawk.init(this).build();
        Hawk.put(HawkConfig.DEBUG_OPEN, false);
        Hawk.put(HawkConfig.PLAYER_IS_LIVE, false);
        if (!Hawk.contains(HawkConfig.PLAY_TYPE)) {
            Hawk.put(HawkConfig.PLAY_TYPE, 2);
        } else if (Hawk.get(HawkConfig.PLAY_TYPE, 2) == 0) {
            Hawk.put(HawkConfig.PLAY_TYPE, 2);
        }
    }

    public static App getInstance() {
        return instance;
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        JsLoader.destroy();
    }


    private VodInfo vodInfo;
    public void setVodInfo(VodInfo vodinfo){
        this.vodInfo = vodinfo;
    }
    public VodInfo getVodInfo(){
        return this.vodInfo;
    }

    public static P2PClass getp2p() {
        try {
            if (p == null) {
                p = new P2PClass(FileUtils.getExternalCachePath());
            }
            return p;
        } catch (Exception e) {
            LOG.e(e.toString());
            return null;
        }
    }

    public Activity getCurrentActivity() {
        return AppManager.getInstance().currentActivity();
    }

    public void setDashData(String data) {
        dashData = data;
    }
    public String getDashData() {
        return dashData;
    }
}
