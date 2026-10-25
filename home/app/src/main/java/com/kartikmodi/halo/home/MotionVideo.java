package com.kartikmodi.halo.home;

import android.content.Context;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.File;

/**
 * Glass Home's "video" backdrop: the motion video looping straight onto the TV's video hardware layer, with
 * no OpenGL in between. The glass above is plain (no bending), which costs far less than the lens.
 */
final class MotionVideo extends SurfaceView implements SurfaceHolder.Callback {

    private final File video;
    private MediaPlayer player;
    private boolean prepared, wanted;

    MotionVideo(Context context, File video) {
        super(context);
        this.video = video;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
        getHolder().addCallback(this);
    }

    void play() {
        wanted = true;
        if (player != null && prepared) player.start();
    }

    void pause() {
        wanted = false;
        if (player != null && prepared && player.isPlaying()) player.pause();
    }

    void release() {
        wanted = false;
        if (player != null) player.release();
        player = null;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            if (player == null) {
                player = new MediaPlayer();
                player.setDataSource(video.getAbsolutePath());
                player.setLooping(true);
                player.setVolume(0f, 0f);
                player.setOnPreparedListener(p -> {
                    prepared = true;
                    if (wanted) p.start();
                });
                player.setDisplay(holder);
                player.prepareAsync();
            } else {
                player.setDisplay(holder);
            }
        } catch (Exception e) {
            Log.w("MotionVideo", "motion video failed", e);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (player != null) player.setDisplay(null);
    }
}
