package com.streamverse.tv.ui.browse;

import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.leanback.widget.ImageCardView;
import androidx.leanback.widget.Presenter;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.streamverse.core.domain.model.Channel;

public class ChannelPresenter extends Presenter {

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent) {
        ImageCardView cardView = new ImageCardView(parent.getContext());
        cardView.setFocusable(true);
        cardView.setFocusableInTouchMode(true);
        cardView.setMainImageDimensions(200, 112);
        return new ViewHolder(cardView);
    }

    @Override
    public void onBindViewHolder(ViewHolder viewHolder, Object item) {
        Channel channel = (Channel) item;
        ImageCardView cardView = (ImageCardView) viewHolder.view;
        cardView.setTitleText(channel.getDisplayName());

        String logoUrl = channel.getLogoUrl();
        if (logoUrl != null) {
            ImageView imageView = cardView.findViewById(androidx.leanback.R.id.main_image);
            if (imageView != null) {
                Glide.with(viewHolder.view.getContext())
                    .load(logoUrl)
                    .apply(RequestOptions.centerInsideTransform())
                    .into(imageView);
            }
        }
    }

    @Override
    public void onUnbindViewHolder(ViewHolder viewHolder) {
        ImageCardView cardView = (ImageCardView) viewHolder.view;
        cardView.setBadgeImage(null);
        cardView.setMainImage(null);
    }
}
