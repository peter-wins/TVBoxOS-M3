package com.github.tvbox.osc.ui.fragment;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.text.Html;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.BounceInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.event.ServerEvent;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;
import com.github.tvbox.osc.ui.activity.LivePlayActivity;
import com.github.tvbox.osc.ui.activity.SearchActivity;
import com.github.tvbox.osc.ui.adapter.HomeHotVodAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.ImgUtil;
import com.github.tvbox.osc.util.UA;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * @author pj567
 * @date :2021/3/9
 * @description:
 */
public class UserFragment extends BaseLazyFragment {
    // Hero 信息块
    private LinearLayout heroInfo;
    private TextView tvHeroNote;
    private TextView tvHeroYear;
    private TextView tvHeroType;
    private TextView tvHeroName;
    private TextView tvHeroMeta;
    private TextView tvHeroDesc;
    private TextView btnHeroPlay;
    private TextView btnHeroSearch;
    private Movie.Video heroVideo;
    private boolean showingHistory = false;
    private String hotRateTitle = "豆瓣热播";

    public static HomeHotVodAdapter homeHotVodAdapter;
    private List<Movie.Video> homeSourceRec;
    public static TvRecyclerView tvHotList;
    private TextView tvHotTitle;
    private TextView tvHotSub;
    // 方案A：沉浸式背景
    private android.widget.ImageView ivBackdrop;
    private String lastBackdropUrl = null;
    private SourceViewModel sourceViewModel;

    public static UserFragment newInstance() {
        return new UserFragment();
    }

    // 方案A：更新沉浸式背景
    private void updateBackdrop(int position) {
        if (ivBackdrop == null) return;
        try {
            if (homeHotVodAdapter == null || position < 0 || position >= homeHotVodAdapter.getData().size()) return;
            com.github.tvbox.osc.bean.Movie.Video video = homeHotVodAdapter.getData().get(position);
            if (video == null || video.pic == null || video.pic.isEmpty()) return;
            if (video.pic.equals(lastBackdropUrl)) return;
            lastBackdropUrl = video.pic;
            ivBackdrop.setAlpha(0f);
            ivBackdrop.setScaleX(1.04f);
            ivBackdrop.setScaleY(1.04f);
            com.github.tvbox.osc.util.ImgUtil.load(video.pic, ivBackdrop, 0, 0, 0);
            ivBackdrop.animate().alpha(0.55f).scaleX(1f).scaleY(1f).setDuration(450).start();
        } catch (Throwable ignored) {
        }
    }

    public static UserFragment newInstance(List<Movie.Video> recVod) {
        return new UserFragment().setArguments(recVod);
    }

    public UserFragment setArguments(List<Movie.Video> recVod) {
        this.homeSourceRec = recVod;
        return this;
    }

    @Override
    protected void onFragmentResume() {
        super.onFragmentResume();
        // 方案A：底部固定单行横向海报，不再使用网格样式
        tvHotList.setVisibility(View.VISIBLE);
        tvHotList.setHasFixedSize(true);
        tvHotList.setLayoutManager(new V7LinearLayoutManager(this.mContext, V7LinearLayoutManager.HORIZONTAL, false));
        int halfSpacing = tvHotList.mHorizontalSpacingWithMargins / 2;
        tvHotList.setPadding(-halfSpacing, 0, -halfSpacing, 0);
        loadContinueWatching();
    }

    /**
     * 首页优先展示“继续观看”：取本地播放记录，最多 20 条。
     * 没有任何记录时回退到原热播逻辑（站点推荐 / 豆瓣热播）。
     */
    private void loadContinueWatching() {
        List<VodInfo> allVodRecord = RoomDataManger.getAllVodRecord(20);
        if (allVodRecord != null && !allVodRecord.isEmpty()) {
            List<Movie.Video> vodList = new ArrayList<>();
            for (VodInfo vodInfo : allVodRecord) {
                Movie.Video vod = new Movie.Video();
                vod.id = vodInfo.id;
                vod.sourceKey = vodInfo.sourceKey;
                vod.name = vodInfo.name;
                vod.pic = vodInfo.pic;
                vod.type = vodInfo.type;
                vod.area = vodInfo.area;
                vod.year = vodInfo.year;
                vod.des = vodInfo.des;
                if (vodInfo.playNote != null && !vodInfo.playNote.isEmpty())
                    vod.note = "上次看到" + vodInfo.playNote;
                vodList.add(vod);
            }
            showingHistory = true;
            homeHotVodAdapter.setHistoryMode(true);
            homeHotVodAdapter.setNewData(vodList);
            if (tvHotTitle != null) tvHotTitle.setText("继续观看");
            if (tvHotSub != null) tvHotSub.setText("共 " + vodList.size() + " 部未看完");
            return;
        }
        // 记录被清空后重新回到热播
        if (showingHistory) {
            showingHistory = false;
            homeHotVodAdapter.setHistoryMode(false);
            if (tvHotTitle != null) tvHotTitle.setText(hotRateTitle);
            if (tvHotSub != null) tvHotSub.setText("长按 OK 搜索同类影片");
            initHomeHotVod(homeHotVodAdapter);
        }
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_user;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        TvRecyclerView hotList = view.findViewById(R.id.tvHotList);
        if (hotList != null && hotList.getLayoutManager() == null) {
            hotList.setLayoutManager(new V7LinearLayoutManager(mContext, V7LinearLayoutManager.HORIZONTAL, false));
        }
    }

    private void jumpSearch(Movie.Video vod) {
        Intent newIntent;
        if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) {
            newIntent = new Intent(mContext, FastSearchActivity.class);
        } else {
            newIntent = new Intent(mContext, SearchActivity.class);
        }
        newIntent.putExtra("title", vod.name);
        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mActivity.startActivity(newIntent);
    }

    private ImgUtil.Style style;
    @Override
    protected void init() {
        EventBus.getDefault().register(this);
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        tvHotList = findViewById(R.id.tvHotList);
        ivBackdrop = findViewById(R.id.ivBackdrop);
        tvHotTitle = findViewById(R.id.tvHotTitle);
        tvHotSub = findViewById(R.id.tvHotSub);
        heroInfo = findViewById(R.id.heroInfo);
        tvHeroNote = findViewById(R.id.tvHeroNote);
        tvHeroYear = findViewById(R.id.tvHeroYear);
        tvHeroType = findViewById(R.id.tvHeroType);
        tvHeroName = findViewById(R.id.tvHeroName);
        tvHeroMeta = findViewById(R.id.tvHeroMeta);
        tvHeroDesc = findViewById(R.id.tvHeroDesc);
        btnHeroPlay = findViewById(R.id.btnHeroPlay);
        btnHeroSearch = findViewById(R.id.btnHeroSearch);
        TextView btnHeroLive = findViewById(R.id.btnHeroLive);
        if (btnHeroPlay != null) {
            btnHeroPlay.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    HawkConfig.hotVodDelete = false;
                    openHeroVideo();
                }
            });
        }
        if (btnHeroSearch != null) {
            btnHeroSearch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    if (heroVideo != null) jumpSearch(heroVideo);
                }
            });
        }
        if (btnHeroLive != null) {
            btnHeroLive.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    jumpActivity(LivePlayActivity.class);
                }
            });
        }
        // 按钮焦点放大，弥补纯色底在电视上的焦点区分度
        View.OnFocusChangeListener heroFocusListener = new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.animate().scaleX(hasFocus ? 1.06f : 1.0f).scaleY(hasFocus ? 1.06f : 1.0f)
                        .setDuration(300).setInterpolator(new BounceInterpolator()).start();
            }
        };
        if (btnHeroPlay != null) btnHeroPlay.setOnFocusChangeListener(heroFocusListener);
        if (btnHeroSearch != null) btnHeroSearch.setOnFocusChangeListener(heroFocusListener);
        if (btnHeroLive != null) btnHeroLive.setOnFocusChangeListener(heroFocusListener);
        if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1 && homeSourceRec!=null) {
            style=ImgUtil.initStyle();
        }
        hotRateTitle = "豆瓣热播";
        if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1) {
            hotRateTitle = homeSourceRec != null ? "站点推荐" : "豆瓣热播";
        }
        if (tvHotTitle != null) tvHotTitle.setText(hotRateTitle);
        homeHotVodAdapter = new HomeHotVodAdapter(style, hotRateTitle);
        homeHotVodAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                if (ApiConfig.get().getSourceBeanList().isEmpty())
                    return;
                Movie.Video vod = ((Movie.Video) adapter.getItem(position));

                if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1 && homeSourceRec != null && vod.action != null) {
                    sourceViewModel.action(vod.sourceKey, vod.action);
                    return;
                }

                if ((vod.id != null && !vod.id.isEmpty()) && showingHistory && HawkConfig.hotVodDelete) {
                    homeHotVodAdapter.remove(position);
                    VodInfo vodInfo = RoomDataManger.getVodInfo(vod.sourceKey, vod.id);
                    assert vodInfo != null;
                    RoomDataManger.deleteVodRecord(vod.sourceKey, vodInfo);
                    Toast.makeText(mContext, "已删除当前记录", Toast.LENGTH_SHORT).show();
                } else if (vod.id != null && vod.id.startsWith("msearch:")) {
                    jumpSearch(vod);
                } else {
                    Bundle bundle = new Bundle();
                    bundle.putString("id", vod.id);
                    bundle.putString("sourceKey", vod.sourceKey);
                    bundle.putString("title", vod.name);
                    bundle.putString("picture", vod.pic);
                    jumpActivity(DetailActivity.class, bundle);
                }
            }
        });
        
        homeHotVodAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @SuppressLint("NotifyDataSetChanged")
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                if (ApiConfig.get().getSourceBeanList().isEmpty()) return false;
                Movie.Video vod = ((Movie.Video) adapter.getItem(position));
                // Additional Check if : Home Rec 0=豆瓣, 1=推荐, 2=历史
                assert vod != null;
                if (vod.id != null && vod.id.startsWith("msearch:")) {
                    Bundle bundle = new Bundle();
                    bundle.putString("id", vod.id);
                    bundle.putString("sourceKey", vod.sourceKey);
                    bundle.putString("title", vod.name);
                    bundle.putString("picture", vod.pic);
                    jumpActivity(DetailActivity.class, bundle);
                } else if ((vod.id != null && !vod.id.isEmpty()) && showingHistory) {
                    HawkConfig.hotVodDelete = !HawkConfig.hotVodDelete;
                    homeHotVodAdapter.notifyDataSetChanged();
                } else {
                    Bundle bundle = new Bundle();
                    bundle.putString("title", vod.name);
                    jumpActivity(FastSearchActivity.class, bundle);                    
                }
                return true;
            }    
        });

        tvHotList.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(300).setInterpolator(new BounceInterpolator()).start();
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                itemView.animate().scaleX(1.05f).scaleY(1.05f).setDuration(300).setInterpolator(new BounceInterpolator()).start();
                updateHeroInfo(position);
                updateBackdrop(position);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {

            }
        });
        tvHotList.setAdapter(homeHotVodAdapter);
        // 数据变化（含外部刷新）时同步 Hero 与背景
        homeHotVodAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                updateHeroInfo(0);
                updateBackdrop(0);
            }
        });

        initHomeHotVod(homeHotVodAdapter);
        sourceViewModel.actionResult.observe(this, new Observer<JSONObject>() {
            @Override
            public void onChanged(JSONObject jsonObject) {
                if (jsonObject == null) return;
                String msg = jsonObject.optString("msg");
                if (!msg.isEmpty()) Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void initHomeHotVod(HomeHotVodAdapter adapter) {
        if (showingHistory) return;
        if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1) {
            if (homeSourceRec != null) {
                adapter.setNewData(homeSourceRec);
                return;
            }
        }
        setDouBanData(adapter);
    }

    private void setDouBanData(HomeHotVodAdapter adapter) {
        try {
            Calendar cal = Calendar.getInstance();
            int year = cal.get(Calendar.YEAR);
            int month = cal.get(Calendar.MONTH) + 1;
            int day = cal.get(Calendar.DATE);
            String today = String.format("%d%d%d", year, month, day);
            String requestDay = Hawk.get("home_hot_day", "");
            if (requestDay.equals(today)) {
                String json = Hawk.get("home_hot", "");
                if (!json.isEmpty()) {
                    ArrayList<Movie.Video> hotMovies = loadHots(json);
                    if (hotMovies != null && hotMovies.size() > 0) {
                        if (showingHistory) return;
                        adapter.setNewData(hotMovies);
                        return;
                    }
                }
            }
            String doubanUrl = "https://movie.douban.com/j/new_search_subjects?sort=U&range=0,10&tags=&playable=1&start=0&year_range=" + year + "," + year;
            OkGo.<String>get(doubanUrl)
                    .headers("User-Agent", UA.randomOne())
                    .execute(new AbsCallback<String>() {
                        @Override
                        public void onSuccess(Response<String> response) {
                            String netJson = response.body();
                            Hawk.put("home_hot_day", today);
                            Hawk.put("home_hot", netJson);
                            mActivity.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (showingHistory) return;
                                    adapter.setNewData(loadHots(netJson));
                                }
                            });
                        }

                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            return response.body().string();
                        }
                    });
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private ArrayList<Movie.Video> loadHots(String json) {
        ArrayList<Movie.Video> result = new ArrayList<>();
        try {
            JsonObject infoJson = new Gson().fromJson(json, JsonObject.class);
            JsonArray array = infoJson.getAsJsonArray("data");
            int limit = Math.min(array.size(), 25);
            for (int i = 0; i < limit; i++) {  // 改用索引循环
                JsonElement ele = array.get(i);
                JsonObject obj = ele.getAsJsonObject();
                Movie.Video vod = new Movie.Video();
                vod.name = obj.get("title").getAsString();
                vod.note = obj.get("rate").getAsString();
                if (!vod.note.isEmpty()) vod.note += " 分";
                vod.pic = obj.get("cover").getAsString()
                        + "@User-Agent=" + UA.randomOne()
                        + "@Referer=https://www.douban.com/";

                result.add(vod);
            }
        } catch (Throwable th) {

        }
        return result;
    }

    // 根据当前焦点卡片刷新 Hero 信息块
    private void updateHeroInfo(int position) {
        if (heroInfo == null || tvHeroName == null) return;
        if (homeHotVodAdapter == null) {
            heroVideo = null;
            heroInfo.setVisibility(View.GONE);
            return;
        }
        List<Movie.Video> data = homeHotVodAdapter.getData();
        if (position < 0 || position >= data.size()) {
            heroVideo = null;
            heroInfo.setVisibility(View.GONE);
            return;
        }
        Movie.Video video = data.get(position);
        heroVideo = video;
        heroInfo.setVisibility(View.VISIBLE);
        tvHeroName.setText(video.name == null ? "" : video.name);

        setHeroChip(tvHeroNote, video.note);
        setHeroChip(tvHeroYear, video.year > 0 ? String.valueOf(video.year) : null);
        setHeroChip(tvHeroType, video.area);

        if (tvHeroMeta != null) {
            StringBuilder meta = new StringBuilder();
            appendMeta(meta, video.type);
            appendMeta(meta, video.lang);
            appendMeta(meta, video.state);
            String metaText = meta.toString();
            tvHeroMeta.setText(metaText);
            tvHeroMeta.setVisibility(metaText.isEmpty() ? View.GONE : View.VISIBLE);
        }

        if (tvHeroDesc != null) {
            String desc = stripHtml(video.des);
            tvHeroDesc.setText(desc);
            tvHeroDesc.setVisibility(desc.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private void setHeroChip(TextView tv, String text) {
        if (tv == null) return;
        if (TextUtils.isEmpty(text)) {
            tv.setVisibility(View.GONE);
            return;
        }
        tv.setText(text);
        tv.setVisibility(View.VISIBLE);
    }

    private void appendMeta(StringBuilder sb, String value) {
        if (TextUtils.isEmpty(value)) return;
        if (sb.length() > 0) sb.append(" · ");
        sb.append(value);
    }

    private String stripHtml(String html) {
        if (TextUtils.isEmpty(html)) return "";
        String text;
        try {
            text = Html.fromHtml(html).toString();
        } catch (Throwable th) {
            text = html;
        }
        return text.replace('\n', ' ').replace('\r', ' ').trim();
    }

    // Hero 主按钮：与卡片点击保持一致的跳转逻辑
    private void openHeroVideo() {
        if (heroVideo == null) return;
        if (ApiConfig.get().getSourceBeanList().isEmpty()) return;
        if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1 && heroVideo.action != null) {
            sourceViewModel.action(heroVideo.sourceKey, heroVideo.action);
            return;
        }
        if (heroVideo.id != null && heroVideo.id.startsWith("msearch:")) {
            jumpSearch(heroVideo);
            return;
        }
        Bundle bundle = new Bundle();
        bundle.putString("id", heroVideo.id);
        bundle.putString("sourceKey", heroVideo.sourceKey);
        bundle.putString("title", heroVideo.name);
        bundle.putString("picture", heroVideo.pic);
        jumpActivity(DetailActivity.class, bundle);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void server(ServerEvent event) {
        if (event.type == ServerEvent.SERVER_CONNECTION) {
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
    }
}
