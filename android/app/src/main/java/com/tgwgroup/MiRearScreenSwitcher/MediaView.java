package com.tgwgroup.MiRearScreenSwitcher;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

/** Album art, track info, clock and transport controls. Payload: packageName, title, artist, albumArtPath, isPlaying. */
public class MediaView implements RearView {
    private static final String TAG = "MediaView";

    private final Context context;
    private final View root;
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private Runnable clockRunnable;

    public MediaView(Context context, ViewGroup parent) {
        this.context = context;
        root = LayoutInflater.from(context).inflate(R.layout.activity_rear_screen_media, parent, false);
        applySafeAreaPadding();
    }

    @Override
    public View getView() {
        return root;
    }

    @Override
    public void bind(Bundle p) {
        String packageName = p.getString("packageName");
        String title = p.getString("title");
        String artist = p.getString("artist");
        String albumArtPath = p.getString("albumArtPath");
        boolean isPlaying = p.getBoolean("isPlaying", true);

        TextView titleView = root.findViewById(R.id.media_title);
        TextView artistView = root.findViewById(R.id.media_artist);
        ImageView albumArtView = root.findViewById(R.id.media_album_art);
        ImageView albumArtBgView = root.findViewById(R.id.media_album_art_bg);
        ImageButton playPauseBtn = root.findViewById(R.id.media_btn_play_pause);
        ImageButton prevBtn = root.findViewById(R.id.media_btn_prev);
        ImageButton nextBtn = root.findViewById(R.id.media_btn_next);

        titleView.setText(title == null ? "" : title);
        artistView.setText(artist == null ? "" : artist);
        playPauseBtn.setImageResource(isPlaying ? R.drawable.ic_media_pause : R.drawable.ic_media_play);

        // Cover file (foreground + blurred background) when present, else the app icon
        android.graphics.Bitmap bmp = albumArtPath == null || albumArtPath.isEmpty()
            ? null : BitmapFactory.decodeFile(albumArtPath);
        if (bmp != null) {
            albumArtView.setImageBitmap(bmp);
            albumArtBgView.setImageBitmap(bmp);
        } else {
            albumArtBgView.setImageDrawable(null);
            setAppIconAsAlbumArt(albumArtView, packageName);
        }

        playPauseBtn.setOnClickListener(v -> {
            MediaController controller = NotificationService.getActiveMediaController();
            if (controller == null) return;
            boolean playing = controller.getPlaybackState() != null
                && controller.getPlaybackState().getState() == PlaybackState.STATE_PLAYING;
            if (playing) {
                controller.getTransportControls().pause();
                playPauseBtn.setImageResource(R.drawable.ic_media_play);
            } else {
                controller.getTransportControls().play();
                playPauseBtn.setImageResource(R.drawable.ic_media_pause);
            }
        });
        prevBtn.setOnClickListener(v -> {
            MediaController controller = NotificationService.getActiveMediaController();
            if (controller != null) controller.getTransportControls().skipToPrevious();
        });
        nextBtn.setOnClickListener(v -> {
            MediaController controller = NotificationService.getActiveMediaController();
            if (controller != null) controller.getTransportControls().skipToNext();
        });

        startClock();
    }

    @Override
    public void unbind() {
        if (clockRunnable != null) {
            clockHandler.removeCallbacks(clockRunnable);
            clockRunnable = null;
        }
    }

    private void setAppIconAsAlbumArt(ImageView albumArtView, String packageName) {
        try {
            if (packageName == null) return;
            Drawable icon = context.getPackageManager().getApplicationIcon(packageName);
            albumArtView.setImageDrawable(icon);
        } catch (Exception e) {
            Log.w(TAG, "Failed to load app icon: " + e.getMessage());
        }
    }

    private void startClock() {
        unbind();
        TextView clockView = root.findViewById(R.id.media_clock);
        clockRunnable = new Runnable() {
            @Override
            public void run() {
                clockView.setText(new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(new java.util.Date()));
                clockHandler.postDelayed(this, 30000);
            }
        };
        clockHandler.post(clockRunnable);
    }

    /** Clear the camera cutout. */
    private void applySafeAreaPadding() {
        try {
            RearDisplayHelper.RearDisplayInfo info = DisplayInfoCache.getInstance().getCachedInfo();
            if (info == null || !info.hasCutout()) return;
            View container = root.findViewById(R.id.media_container);
            if (container != null && container.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) container.getLayoutParams();
                params.leftMargin = info.cutout.left;
                params.topMargin = info.cutout.top;
                params.rightMargin = info.cutout.right;
                params.bottomMargin = info.cutout.bottom;
                container.setLayoutParams(params);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply safe area", e);
        }
    }
}
