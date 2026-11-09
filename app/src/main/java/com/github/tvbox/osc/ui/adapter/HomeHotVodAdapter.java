package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.ImgUtil;

import java.util.ArrayList;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class HomeHotVodAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {
    private String tvRateValue;
    // 首页是否处于“继续观看”模式（决定来源徽章与删除态）
    private boolean historyMode = false;

    public HomeHotVodAdapter(ImgUtil.Style style, String tvRate) {
        super(R.layout.item_user_hot_vod, new ArrayList<>());
        this.tvRateValue = tvRate;
    }

    public void setHistoryMode(boolean historyMode) {
        this.historyMode = historyMode;
    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {
        FrameLayout tvDel = helper.getView(R.id.delFrameLayout);
        tvDel.setVisibility(historyMode && HawkConfig.hotVodDelete ? View.VISIBLE : View.GONE);

        TextView tvRate = helper.getView(R.id.tvRate);
        if (historyMode) {
            SourceBean bean = ApiConfig.get().getSource(item.sourceKey);
            tvRateValue = bean != null ? bean.getName() : "";
        }
        tvRate.setText(tvRateValue);
        // 仅在“继续观看”模式用左上角徽章标注来源，避免与区块标题重复
        tvRate.setVisibility(historyMode && !TextUtils.isEmpty(tvRateValue) ? View.VISIBLE : View.GONE);

        TextView tvNote = helper.getView(R.id.tvNote);
        if (item.note == null || item.note.isEmpty()) {
            tvNote.setVisibility(View.GONE);
        } else {
            tvNote.setText(item.note);
            tvNote.setVisibility(View.VISIBLE);
        }
        // Fixed text slots and explicit pixels keep early-bound cards readable after AutoSize settles.
        TextView title = helper.getView(R.id.tvName);
        title.setText(item.name);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                mContext.getResources().getDimension(R.dimen.ts_18));

        TextView tvInfo = helper.getView(R.id.tvInfo);
        StringBuilder info = new StringBuilder();
        appendInfo(info, item.type);
        appendInfo(info, item.area);
        if (item.year > 0) appendInfo(info, String.valueOf(item.year));
        String infoText = info.toString();
        tvInfo.setText(infoText);
        tvInfo.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                mContext.getResources().getDimension(R.dimen.ts_14));
        tvInfo.setVisibility(infoText.isEmpty() ? View.INVISIBLE : View.VISIBLE);

        ImageView ivThumb = helper.getView(R.id.ivThumb);
        String pic = item.pic == null ? "" : item.pic.trim();
        if (!TextUtils.isEmpty(pic)) {
            if (ImgUtil.isBase64Image(pic)) {
                ivThumb.setImageBitmap(ImgUtil.decodeBase64ToBitmap(pic));
            } else {
                ImgUtil.load(pic, ivThumb, AutoSizeUtils.mm2px(mContext, 8),
                        mContext.getResources().getDimensionPixelSize(R.dimen.home_poster_width),
                        mContext.getResources().getDimensionPixelSize(R.dimen.home_poster_height), item.name);
            }
        } else {
            ivThumb.setImageDrawable(ImgUtil.createTextDrawable(item.name));
        }
    }

    private void appendInfo(StringBuilder sb, String value) {
        if (TextUtils.isEmpty(value)) return;
        if (sb.length() > 0) sb.append(" · ");
        sb.append(value);
    }
}