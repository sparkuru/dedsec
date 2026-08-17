package io.github.wkyuu.dedsec.target;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import io.github.wkyuu.dedsec.target.targets.ClassLoaderScenario;
import io.github.wkyuu.dedsec.target.targets.HookTargets;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    public static final String LOG_TAG = "DedsecTarget";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView outputView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContentView());
        publish("lifecycle", "MainActivity.onCreate");
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        publish("lifecycle", "MainActivity.onDestroy");
        super.onDestroy();
    }

    private View createContentView() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("Dedsec Hook Target");
        title.setTextSize(22);
        content.addView(title);

        addButton(content, "Run main-process targets", view -> runMainProcessTargets());
        addButton(content, "Run exception target", view -> runExceptionTarget());
        addButton(content, "Run background-thread target", view -> runBackgroundThreadTarget());
        addButton(content, "Run isolated ClassLoader target", view -> runClassLoaderTarget());
        addButton(content, "Start :worker process", view -> runWorkerProcessTarget());

        outputView = new TextView(this);
        outputView.setTextIsSelectable(true);
        content.addView(outputView);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(content);
        return scrollView;
    }

    private void addButton(LinearLayout parent, String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        parent.addView(button);
    }

    private void runMainProcessTargets() {
        HookTargets targets = new HookTargets("main");
        String result = String.format(
                Locale.ROOT,
                "greet=%s int=%d text=%s final=%s sync=%d static=%s",
                targets.greet("researcher"),
                targets.merge(7, 9),
                targets.merge("left", "right"),
                targets.finalLabel("value"),
                targets.synchronizedIncrement(10),
                HookTargets.staticToken("input")
        );
        publish("main", result);
    }

    private void runExceptionTarget() {
        HookTargets targets = new HookTargets("exception");
        try {
            targets.failIfBlank("");
            publish("exception", "unexpected success");
        } catch (IllegalArgumentException error) {
            publish("exception", error.getClass().getSimpleName() + ":" + error.getMessage());
        }
    }

    private void runBackgroundThreadTarget() {
        executor.execute(() -> {
            HookTargets targets = new HookTargets("background");
            String result = targets.greet(Thread.currentThread().getName());
            runOnUiThread(() -> publish("background", result));
        });
    }

    private void runClassLoaderTarget() {
        try {
            publish("classloader", ClassLoaderScenario.run(this));
        } catch (ReflectiveOperationException error) {
            publish("classloader", error.getClass().getSimpleName() + ":" + error.getMessage());
        }
    }

    private void runWorkerProcessTarget() {
        startService(new Intent(this, WorkerService.class));
        publish("worker", "start requested");
    }

    private void publish(String scenario, String result) {
        String message = scenario + " => " + result;
        Log.i(LOG_TAG, message);
        if (outputView != null) {
            outputView.setText(message + "\n" + outputView.getText());
        }
    }
}
