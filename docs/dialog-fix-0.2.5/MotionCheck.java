package motion;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class MotionCheck extends Instrumentation {
    private final List<String> samples = new ArrayList<>();
    private AlertDialog dialog;
    private Activity activity;
    private long started;
    private int[] initial;
    private Throwable failure;
    private boolean drawn;
    private void sample(String phase) {
        View view = dialog.getWindow().getDecorView();
        int[] position = new int[2]; view.getLocationOnScreen(position);
        int[] geometry = {position[0], position[1], view.getWidth(), view.getHeight()};
        if (initial == null) initial = geometry;
        if (!java.util.Arrays.equals(initial, geometry) || view.getTranslationX() != 0 || view.getTranslationY() != 0)
            failure = new AssertionError("Dialog moved after first draw");
        samples.add("{\"phase\":\"" + phase + "\",\"ms\":" + (SystemClock.uptimeMillis()-started)
                + ",\"x\":" + geometry[0] + ",\"y\":" + geometry[1] + ",\"width\":" + geometry[2]
                + ",\"height\":" + geometry[3] + ",\"translationX\":" + view.getTranslationX()
                + ",\"translationY\":" + view.getTranslationY() + "}");
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int code = Activity.RESULT_OK;
        try {
            Intent intent = new Intent().setClassName("io.github.appcommentfilter", "io.github.appcommentfilter.ui.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(intent);
            CountDownLatch done = new CountDownLatch(1);
            runOnMainSync(() -> {
                try {
                    Method edit = activity.getClass().getDeclaredMethod("editRule", String.class, int.class);
                    edit.setAccessible(true); edit.invoke(activity, "blacklist", -1);
                    Field editor = activity.getClass().getDeclaredField("activeEditor"); editor.setAccessible(true);
                    dialog = (AlertDialog) editor.get(activity);
                    View view = dialog.getWindow().getDecorView(); started = SystemClock.uptimeMillis();
                    view.getViewTreeObserver().addOnPreDrawListener(() -> {
                        if (!drawn) { drawn = true; sample("firstDraw"); }
                        return true;
                    });
                    Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
                        int frames;
                        @Override public void doFrame(long time) {
                            if (drawn) { sample("frame"); frames++; }
                            if (frames < 40) Choreographer.getInstance().postFrameCallback(this);
                            else done.countDown();
                        }
                    });
                } catch (Throwable error) { failure = error; done.countDown(); }
            });
            if (!done.await(8, TimeUnit.SECONDS)) throw new AssertionError("No frame completion");
            if (failure != null) throw new AssertionError(failure);
            if (samples.size() < 30) throw new AssertionError("Insufficient frames");
            result.putString("result", "PASS: first draw and 40 following frame geometries stable");
        } catch (Throwable error) { result.putString("error", error.toString()); code = Activity.RESULT_CANCELED; }
        finally {
            result.putString("frames", "[" + String.join(",", samples) + "]");
            runOnMainSync(() -> { if (dialog != null) dialog.dismiss(); if (activity != null) activity.finish(); });
        }
        finish(code, result);
    }
}
