package com.newsreader.capture;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import java.lang.reflect.Field;

/** Supplies explicitly synthetic phone state to the real watch UI for documentation. */
public final class WatchCaptureRunner extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        try {
            Intent intent = new Intent();
            intent.setClassName("com.newsreader", "com.newsreader.wear.WearMainActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity activity = startActivitySync(intent);
            waitForIdleSync();
            runOnMainSync(() -> {
                try {
                    Field field = activity.getClass().getDeclaredField("viewModel");
                    field.setAccessible(true);
                    Object vm = field.get(activity);
                    String state = "{\"isPlaying\":true,\"currentIndex\":2,\"totalItems\":10,\"title\":\"Sample story: New discoveries in space\",\"topic\":\"Science\",\"progress\":42,\"volume\":7,\"maxVolume\":15}";
                    String topics = "[{\"name\":\"WORLD\",\"display\":\"World\"},{\"name\":\"TECHNOLOGY\",\"display\":\"Tech\"},{\"name\":\"BUSINESS\",\"display\":\"Business\"},{\"name\":\"SPORTS\",\"display\":\"Sports\"},{\"name\":\"HEALTH\",\"display\":\"Health\"},{\"name\":\"SCIENCE\",\"display\":\"Science\"},{\"name\":\"ENTERTAINMENT\",\"display\":\"Entertainment\"},{\"name\":\"INDIA\",\"display\":\"India\"}]";
                    vm.getClass().getMethod("updateState", String.class).invoke(vm, state);
                    vm.getClass().getMethod("updateTopics", String.class).invoke(vm, topics);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            waitForIdleSync();
            // Keep the instrumented app available for manual/Computer Use capture.
            Thread.sleep(600000);
            finish(0, new Bundle());
        } catch (Exception e) {
            Bundle result = new Bundle();
            result.putString("capture_error", e.toString());
            finish(1, result);
        }
    }
}
