package com.github.tvbox.osc.ui.fragment;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.text.Html;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;
import com.github.tvbox.osc.ui.activity.HistoryActivity;
import com.github.tvbox.osc.ui.activity.HomeActivity;
import com.github.tvbox.osc.ui.activity.SearchActivity;
import com.github.tvbox.osc.ui.adapter.HomeHotVodAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.ImgUtil;
import com.github.tvbox.osc.util.HomeMetadataLoader;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.UA;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.jessyan.autosize.utils.AutoSizeUtils;

/** Home shelves share a view, but never share their data or browsing position. */
public class UserFragment extends BaseLazyFragment {
    private final Handler homeHandler = new Handler(Looper.getMainLooper());
    private final List<Movie.Video> history = new ArrayList<>();
    private final List<Movie.Video> recommendations = new ArrayList<>();
    private final int[] shelfPositions = new int[2];
    private final Parcelable[] shelfScrollStates = new Parcelable[2];
    private List<Movie.Video> homeSourceRec;
    private HomeHotVodAdapter adapter;
    private TvRecyclerView hotList;
    private TextView tabContinue;
    private TextView tabRecommendations;
    private TextView emptyShelf;
    private TextView heroName;
    private TextView heroMeta;
    private TextView heroDesc;
    private TextView heroDetails;
    private TextView heroSearch;
    private HomeMetadataLoader metadataLoader;
    private HomeMetadataLoader.Pending pendingMetadata;
    private Runnable loadHeroMetadata;
    private int heroGeneration;
    private View heroActions;
    private ImageView backdrop;
    private ImageView heroPoster;
    private Movie.Video heroVideo;
    private SourceViewModel sourceViewModel;
    private boolean showingHistory;
    private boolean shelfChosen;
    private ExecutorService historyWorker;
    private int historyGeneration;
    private boolean historyLoading;
    private boolean recommendationsLoading;
    private boolean recommendationsFailed;
    private boolean destroyed;
    private String lastArtwork;
    private int selectedPosition;
    private final Object recommendationRequestTag = new Object();
    private final Runnable updateArtwork = () -> showArtwork(heroVideo);

    public static UserFragment newInstance() {
        return newInstance(null);
    }

    public static UserFragment newInstance(List<Movie.Video> videos) {
        UserFragment fragment = new UserFragment();
        fragment.homeSourceRec = videos;
        return fragment;
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_user;
    }

    @Override
    protected void init() {
        destroyed = false;
        historyWorker = Executors.newSingleThreadExecutor();
        historyLoading = true;
        if (!shelfChosen) showingHistory = true;
        hotList = findViewById(R.id.tvHotList);
        tabContinue = findViewById(R.id.tabContinue);
        tabRecommendations = findViewById(R.id.tabRecommendations);
        emptyShelf = findViewById(R.id.emptyHomeShelf);
        heroName = findViewById(R.id.tvHeroName);
        heroMeta = findViewById(R.id.tvHeroMeta);
        heroDesc = findViewById(R.id.tvHeroDesc);
        heroDetails = findViewById(R.id.btnHeroPlay);
        heroSearch = findViewById(R.id.btnHeroSearch);
        heroActions = findViewById(R.id.heroActions);
        backdrop = findViewById(R.id.ivBackdrop);
        heroPoster = findViewById(R.id.ivHeroPoster);
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        metadataLoader = new HomeMetadataLoader(sourceViewModel::resolveHomeDetailExtension);
        sourceViewModel.actionResult.observe(getViewLifecycleOwner(), result -> {
            if (result != null && !result.optString("msg").isEmpty()) {
                Toast.makeText(mContext, result.optString("msg"), Toast.LENGTH_SHORT).show();
            }
        });
        hotList.setLayoutManager(new V7LinearLayoutManager(mContext, V7LinearLayoutManager.HORIZONTAL, false));
        hotList.setHasFixedSize(true);
        adapter = new HomeHotVodAdapter(null, "");
        hotList.setAdapter(adapter);
        tabContinue.setOnClickListener(v -> selectShelf(true));
        tabRecommendations.setOnClickListener(v -> selectShelf(false));
        // Focus selects the shelf; down enters its remembered poster. No extra OK press is required.
        tabContinue.setOnFocusChangeListener((v, focused) -> {
            if (focused && !showingHistory) selectShelf(true);
        });
        tabRecommendations.setOnFocusChangeListener((v, focused) -> {
            if (focused && showingHistory) selectShelf(false);
        });
        findViewById(R.id.btnAllHistory).setOnClickListener(v -> jumpActivity(HistoryActivity.class));
        heroDetails.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            openVideo(heroVideo, true);
        });
        findViewById(R.id.btnHeroSearch).setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            if (heroVideo != null) jumpSearch(heroVideo);
        });
        emptyShelf.setOnClickListener(v -> {
            if (showingHistory) {
                tabRecommendations.requestFocus();
            } else if (recommendationsFailed) {
                loadRecommendations();
            } else {
                ((HomeActivity) mActivity).openHomeSourcePicker();
            }
        });
        View.OnKeyListener shelfKeys = (v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || key != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            requestContentFocus();
            return true;
        };
        tabContinue.setOnKeyListener(shelfKeys);
        tabRecommendations.setOnKeyListener(shelfKeys);
        findViewById(R.id.btnAllHistory).setOnKeyListener(shelfKeys);
        hotList.setOnInBorderKeyEventListener((direction, view) -> {
            if (direction == View.FOCUS_UP) {
                (showingHistory ? tabContinue : tabRecommendations).requestFocus();
                return true;
            }
            return false;
        });
        adapter.setOnItemClickListener((a, v, position) -> {
            Movie.Video video = adapter.getItem(position);
            if (video == null) return;
            if (showingHistory && HawkConfig.hotVodDelete) {
                refreshHistory(video);
            } else {
                openVideo(video);
            }
        });
        adapter.setOnItemLongClickListener((a, v, position) -> {
            Movie.Video video = adapter.getItem(position);
            if (video == null) return false;
            if (showingHistory) {
                HawkConfig.hotVodDelete = !HawkConfig.hotVodDelete;
                adapter.notifyDataSetChanged();
            } else {
                jumpSearch(video);
            }
            return true;
        });
        hotList.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View view, int position) {
                if (view != null) view.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View view, int position) {
                selectedPosition = position;
                shelfPositions[showingHistory ? 0 : 1] = position;
                if (view != null) view.animate().scaleX(1.03f).scaleY(1.03f).setDuration(140).start();
                updateHero(position);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View view, int position) {}
        });
        recommendations.clear();
        if (homeSourceRec != null) recommendations.addAll(homeSourceRec);
        showShelf(showingHistory);
        loadRecommendations();
    }

    @Override
    protected void onFragmentResume() {
        super.onFragmentResume();
        if (adapter == null) return;
        refreshHistory(null);
        if (!showingHistory) updateHero(selectedPosition);
    }

    private void refreshHistory(Movie.Video deletedVideo) {
        int generation = ++historyGeneration;
        historyLoading = true;
        historyWorker.execute(() -> {
            List<Movie.Video> videos;
            try {
                if (deletedVideo != null) {
                    VodInfo record = RoomDataManger.getVodInfo(deletedVideo.sourceKey, deletedVideo.id);
                    if (record != null) RoomDataManger.deleteVodRecord(deletedVideo.sourceKey, record);
                }
                videos = readHistory();
            } catch (RuntimeException error) {
                LOG.i("Home history refresh failed: " + error.getClass().getSimpleName());
                videos = null;
            }
            List<Movie.Video> result = videos;
            homeHandler.post(() -> {
                if (destroyed || generation != historyGeneration) return;
                historyLoading = false;
                if (result != null) {
                    history.clear();
                    history.addAll(result);
                }
                if (!shelfChosen) {
                    shelfChosen = true;
                    showShelf(!history.isEmpty());
                } else if (showingHistory) {
                    showShelf(true);
                }
            });
        });
    }

    private static List<Movie.Video> readHistory() {
        List<Movie.Video> videos = new ArrayList<>();
        List<VodInfo> records = RoomDataManger.getAllVodRecord(20);
        if (records == null) return videos;
        for (VodInfo record : records) {
            Movie.Video video = new Movie.Video();
            video.id = record.id;
            video.sourceKey = record.sourceKey;
            video.name = record.name;
            video.pic = record.pic;
            video.type = record.type;
            video.area = record.area;
            video.year = record.year;
            video.des = record.des;
            if (!TextUtils.isEmpty(record.playNote)) video.note = "上次看到 " + record.playNote;
            videos.add(video);
        }
        return videos;
    }

    private void selectShelf(boolean useHistory) {
        shelfChosen = true;
        showShelf(useHistory);
    }

    private void showShelf(boolean useHistory) {
        if (adapter == null || destroyed) return;
        boolean switched = showingHistory != useHistory;
        RecyclerView.LayoutManager manager = hotList.getLayoutManager();
        if (manager != null && !adapter.getData().isEmpty()) {
            shelfScrollStates[showingHistory ? 0 : 1] = manager.onSaveInstanceState();
        }
        boolean restoreFocus = hotList.hasFocus() || emptyShelf.hasFocus();
        showingHistory = useHistory;
        if (switched) HawkConfig.hotVodDelete = false;
        tabContinue.setSelected(showingHistory);
        tabRecommendations.setSelected(!showingHistory);
        tabContinue.setAlpha(showingHistory ? 1f : .72f);
        tabRecommendations.setAlpha(showingHistory ? .72f : 1f);
        adapter.setHistoryMode(showingHistory);
        List<Movie.Video> data = showingHistory ? history : recommendations;
        adapter.setNewData(new ArrayList<>(data));
        int shelf = showingHistory ? 0 : 1;
        selectedPosition = Math.min(shelfPositions[shelf], Math.max(0, data.size() - 1));
        shelfPositions[shelf] = selectedPosition;
        if (manager != null) {
            if (shelfScrollStates[shelf] != null) manager.onRestoreInstanceState(shelfScrollStates[shelf]);
            else hotList.scrollToPosition(selectedPosition);
        }
        hotList.setVisibility(data.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        emptyShelf.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
        emptyShelf.setText(showingHistory
                ? (historyLoading ? "正在加载观看记录" : "暂无观看记录，按确定查看站点推荐")
                : recommendationsLoading ? "正在加载推荐，按确定可切换片源"
                : recommendationsFailed ? "推荐暂时不可用，按确定重试"
                : "当前片源暂无推荐，按确定切换片源");
        hotList.setNextFocusUpId(showingHistory ? R.id.tabContinue : R.id.tabRecommendations);
        emptyShelf.setNextFocusUpId(showingHistory ? R.id.tabContinue : R.id.tabRecommendations);
        updateHero(selectedPosition);
        if (restoreFocus) hotList.post(this::requestContentFocus);
    }

    public void requestHeroFocus() {
        if (destroyed || !currentVisibleState) return;
        if (heroActions.getVisibility() == View.VISIBLE) heroDetails.requestFocus();
        else (showingHistory ? tabContinue : tabRecommendations).requestFocus();
    }

    public void requestContentFocus() {
        if (destroyed || adapter == null || !currentVisibleState) return;
        if (adapter.getData().isEmpty()) {
            emptyShelf.requestFocus();
            return;
        }
        hotList.setSelectedPosition(selectedPosition);
        hotList.setSelection(selectedPosition);
    }

    public void exitDeleteMode() {
        HawkConfig.hotVodDelete = false;
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void loadRecommendations() {
        if (recommendationsLoading || destroyed) return;
        if (homeSourceRec != null && !homeSourceRec.isEmpty()) {
            recommendations.clear();
            recommendations.addAll(homeSourceRec);
            if (!showingHistory) showShelf(false);
            return;
        }
        // Existing public recommendations remain a fallback when the configured source has none.
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(new java.util.Date());
        String cached = Hawk.get("home_hot", "");
        if (today.equals(Hawk.get("home_hot_day", "")) && !cached.isEmpty()) {
            recommendations.addAll(parseHotMovies(cached));
            if (!recommendations.isEmpty()) {
                tabRecommendations.setText("热播推荐");
                if (!showingHistory) showShelf(false);
                return;
            }
        }
        recommendationsLoading = true;
        recommendationsFailed = false;
        tabRecommendations.setText("热播推荐");
        if (!showingHistory) showShelf(false);
        int year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        OkGo.<String>get("https://movie.douban.com/j/new_search_subjects?sort=U&range=0,10&tags=&playable=1&start=0&year_range=" + year + "," + year)
                .tag(recommendationRequestTag).headers("User-Agent", UA.randomOne()).execute(new AbsCallback<String>() {
                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body() == null ? "" : response.body().string();
                    }

                    @Override
                    public void onSuccess(Response<String> response) {
                        if (destroyed) return;
                        recommendationsLoading = false;
                        recommendations.clear();
                        recommendations.addAll(parseHotMovies(response.body()));
                        recommendationsFailed = recommendations.isEmpty();
                        if (!recommendationsFailed) {
                            Hawk.put("home_hot", response.body());
                            Hawk.put("home_hot_day", today);
                        }
                        if (!showingHistory) showShelf(false);
                    }

                    @Override
                    public void onError(Response<String> response) {
                        if (destroyed) return;
                        recommendationsLoading = false;
                        recommendationsFailed = true;
                        if (!showingHistory) showShelf(false);
                    }
                });
    }

    private List<Movie.Video> parseHotMovies(String json) {
        List<Movie.Video> result = new ArrayList<>();
        try {
            JsonArray data = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("data");
            if (data == null) return result;
            for (JsonElement element : data) {
                if (result.size() >= 25) break;
                JsonObject item = element.getAsJsonObject();
                Movie.Video video = new Movie.Video();
                video.name = item.get("title").getAsString();
                video.id = "msearch:" + video.name;
                if (item.has("id") && !item.get("id").isJsonNull()) video.doubanId = item.get("id").getAsString();
                video.note = item.has("rate") ? item.get("rate").getAsString() + " 分" : "";
                video.pic = item.get("cover").getAsString() + "@User-Agent=" + UA.randomOne() + "@Referer=https://www.douban.com/";
                result.add(video);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private void updateHero(int position) {
        homeHandler.removeCallbacks(updateArtwork);
        cancelHeroMetadata();
        heroVideo = position >= 0 && position < adapter.getData().size() ? adapter.getItem(position) : null;
        if (heroVideo == null) {
            heroName.setText(showingHistory ? "从一部好片开始" : "发现下一部好片");
            heroMeta.setVisibility(View.GONE);
            heroDesc.setVisibility(View.VISIBLE);
            heroDesc.setText(showingHistory ? "你的观看记录会显示在这里" : "切换片源，发现更多精彩内容");
            heroActions.setVisibility(View.GONE);
            showArtwork(null);
            return;
        }
        heroName.setText(heroVideo.name);
        StringBuilder meta = new StringBuilder();
        if (heroVideo.year > 0) appendMeta(meta, String.valueOf(heroVideo.year));
        appendMeta(meta, heroVideo.area);
        appendMeta(meta, heroVideo.type);
        appendMeta(meta, heroVideo.note);
        heroMeta.setText(meta.toString());
        heroMeta.setVisibility(meta.length() == 0 ? View.GONE : View.VISIBLE);
        renderDescription();
        boolean searchableOnly = TextUtils.isEmpty(heroVideo.id) || heroVideo.id.startsWith("msearch:")
                || ApiConfig.get().getSource(heroVideo.sourceKey) == null;
        boolean action = !showingHistory && heroVideo.action != null;
        heroDetails.setText(action ? "打开" : searchableOnly ? "搜索片源" : "立即播放");
        heroDetails.setCompoundDrawablesRelativeWithIntrinsicBounds(searchableOnly && !action
                ? R.drawable.ic_home_search_action : R.drawable.ic_home_play_action, 0, 0, 0);
        heroSearch.setVisibility(searchableOnly && !action ? View.GONE : View.VISIBLE);
        heroActions.setVisibility(View.VISIBLE);
        homeHandler.postDelayed(updateArtwork, 180);
        if (TextUtils.isEmpty(heroDesc.getText()) && !action) scheduleDescription();
    }

    private void renderDescription() {
        heroDesc.setText(stripHtml(heroVideo == null ? null : heroVideo.des));
        // Reserve the same two lines for both shelves while a description is being fetched.
        heroDesc.setVisibility(TextUtils.isEmpty(heroDesc.getText()) ? View.INVISIBLE : View.VISIBLE);
    }

    private void scheduleDescription() {
        final Movie.Video target = heroVideo;
        final int generation = heroGeneration;
        loadHeroMetadata = () -> {
            if (destroyed || !currentVisibleState || heroVideo != target || generation != heroGeneration) return;
            pendingMetadata = metadataLoader.load(target, metadata -> {
                if (destroyed || !currentVisibleState || heroVideo != target || generation != heroGeneration) return;
                pendingMetadata = null;
                if (metadata != null) {
                    target.des = metadata.description;
                    renderDescription();
                    com.github.tvbox.osc.util.LOG.i("Home description ready for focused movie");
                } else {
                    heroDesc.setText("暂无影片简介");
                    heroDesc.setVisibility(View.VISIBLE);
                }
            });
        };
        homeHandler.postDelayed(loadHeroMetadata, 400);
    }

    private void cancelHeroMetadata() {
        heroGeneration++;
        if (loadHeroMetadata != null) homeHandler.removeCallbacks(loadHeroMetadata);
        loadHeroMetadata = null;
        if (pendingMetadata != null) pendingMetadata.cancel();
        pendingMetadata = null;
    }

    @Override
    protected void onFragmentPause() {
        cancelHeroMetadata();
        homeHandler.removeCallbacks(updateArtwork);
        super.onFragmentPause();
    }

    private void showArtwork(Movie.Video video) {
        String picture = video == null || video.pic == null ? "" : video.pic;
        if (picture.equals(lastArtwork)) return;
        lastArtwork = picture;
        if (picture.isEmpty()) {
            com.bumptech.glide.Glide.with(mContext.getApplicationContext()).clear(heroPoster);
            com.bumptech.glide.Glide.with(mContext.getApplicationContext()).clear(backdrop);
            heroPoster.setImageDrawable(null);
            backdrop.setImageDrawable(null);
            return;
        }
        // Preserve the complete portrait; a low-opacity copy only supplies background colour.
        ImgUtil.load(picture, heroPoster, AutoSizeUtils.mm2px(mContext, 10),
                getResources().getDimensionPixelSize(R.dimen.home_hero_poster_width),
                getResources().getDimensionPixelSize(R.dimen.home_hero_poster_height), video.name, ImageView.ScaleType.FIT_CENTER);
        ImgUtil.load(picture, backdrop, 1, AutoSizeUtils.mm2px(mContext, 360),
                AutoSizeUtils.mm2px(mContext, 150), video.name);
    }

    private void openVideo(Movie.Video video) {
        openVideo(video, false);
    }

    private void openVideo(Movie.Video video, boolean immediately) {
        if (video == null) return;
        if (ApiConfig.get().getSourceBeanList().isEmpty()) {
            Toast.makeText(mContext, "请先在设置中配置片源", Toast.LENGTH_SHORT).show();
            return;
        }
        HawkConfig.hotVodDelete = false;
        if (!showingHistory && video.action != null) {
            sourceViewModel.action(video.sourceKey, video.action);
        } else if (TextUtils.isEmpty(video.id) || video.id.startsWith("msearch:")
                || ApiConfig.get().getSource(video.sourceKey) == null) {
            jumpSearch(video);
        } else {
            Bundle bundle = new Bundle();
            bundle.putString("id", video.id);
            bundle.putString("sourceKey", video.sourceKey);
            bundle.putString("title", video.name);
            bundle.putString("picture", video.pic);
            bundle.putBoolean(DetailActivity.EXTRA_HOME_AUTO_PLAY, immediately);
            jumpActivity(DetailActivity.class, bundle);
        }
    }

    private void jumpSearch(Movie.Video video) {
        Intent intent = new Intent(mContext, Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)
                ? FastSearchActivity.class : SearchActivity.class);
        intent.putExtra("title", video.name);
        startActivity(intent);
    }

    private void appendMeta(StringBuilder builder, String value) {
        if (TextUtils.isEmpty(value)) return;
        if (builder.length() > 0) builder.append(" · ");
        builder.append(value);
    }

    private String stripHtml(String html) {
        if (TextUtils.isEmpty(html)) return "";
        return Html.fromHtml(html).toString().replace('\n', ' ').replace('\r', ' ').trim();
    }

    @Override
    public void onDestroyView() {
        destroyed = true;
        historyGeneration++;
        if (historyWorker != null) historyWorker.shutdown();
        cancelHeroMetadata();
        homeHandler.removeCallbacksAndMessages(null);
        OkGo.getInstance().cancelTag(recommendationRequestTag);
        if (heroPoster != null) com.bumptech.glide.Glide.with(mContext.getApplicationContext()).clear(heroPoster);
        if (backdrop != null) com.bumptech.glide.Glide.with(mContext.getApplicationContext()).clear(backdrop);
        recommendationsLoading = false;
        adapter = null;
        rootView = null;
        currentVisibleState = false;
        mIsFirstVisible = true;
        lastArtwork = null;
        super.onDestroyView();
    }
}
