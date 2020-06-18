/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.globalactions;

import static android.view.WindowInsets.Type.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

@LargeTest
public class GlobalActionsImeTest extends SysuiTestCase {

    @Rule
    public ActivityTestRule<TestActivity> mActivityTestRule = new ActivityTestRule<>(
            TestActivity.class, false, false);

    @After
    public void tearDown() {
        executeShellCommand("input keyevent HOME");
    }

    /**
     * This test verifies that GlobalActions, which is frequently used to capture bugreports,
     * doesn't interfere with the IME, i.e. soft-keyboard state.
     */
    @Test
    public void testGlobalActions_doesntStealImeControl() throws Exception {
        turnScreenOn();
        final TestActivity activity = mActivityTestRule.launchActivity(null);

        waitUntil("Ime is visible", activity::isImeVisible);

        // In some cases, IME is not controllable. e.g., floating IME or fullscreen IME.
        final boolean activityControlledIme = activity.mControlsIme;

        executeShellCommand("input keyevent --longpress POWER");

        waitUntil("activity loses focus", () -> !activity.mHasFocus);
        // Give the dialog time to animate in, and steal IME focus. Unfortunately, there's currently
        // no better way to wait for this.
        SystemClock.sleep(TimeUnit.SECONDS.toMillis(2));

        runAssertionOnMainThread(() -> {
            assertTrue("IME should remain visible behind GlobalActions, but didn't",
                    activity.mImeVisible);
            assertEquals("App behind GlobalActions should remain in control of IME, but didn't",
                    activityControlledIme, activity.mControlsIme);
        });
    }

    private void turnScreenOn() throws Exception {
        PowerManager powerManager = mContext.getSystemService(PowerManager.class);
        assertNotNull(powerManager);
        if (powerManager.isInteractive()) {
            return;
        }
        executeShellCommand("input keyevent KEYCODE_WAKEUP");
        waitUntil("Device not interactive", powerManager::isInteractive);
        executeShellCommand("am wait-for-broadcast-idle");
    }

    private static void waitUntil(String message, BooleanSupplier predicate)
            throws Exception {
        int sleep = 125;
        final long timeout = SystemClock.uptimeMillis() + 10_000;  // 10 second timeout
        while (SystemClock.uptimeMillis() < timeout) {
            if (predicate.getAsBoolean()) {
                return; // okay
            }
            Thread.sleep(sleep);
            sleep *= 5;
            sleep = Math.min(2000, sleep);
        }
        fail(message);
    }

    private static void executeShellCommand(String cmd) {
        InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(cmd);
    }

    /**
     * Like Instrumentation.runOnMainThread(), but forwards AssertionErrors to the caller.
     */
    private static void runAssertionOnMainThread(Runnable r) {
        AssertionError[] t = new AssertionError[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                r.run();
            } catch (AssertionError e) {
                t[0] = e;
                // Ignore assertion - throwing it here would crash the main thread.
            }
        });
        if (t[0] != null) {
            throw t[0];
        }
    }

    public static class TestActivity extends Activity implements
            WindowInsetsController.OnControllableInsetsChangedListener,
            View.OnApplyWindowInsetsListener {

        boolean mHasFocus;
        boolean mControlsIme;
        boolean mImeVisible;

        @Override
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            EditText content = new EditText(this);
            content.setCursorVisible(false);  // Otherwise, main thread doesn't go idle.
            setContentView(content);
            content.requestFocus();

            getWindow().getDecorView().setOnApplyWindowInsetsListener(this);
            WindowInsetsController wic = content.getWindowInsetsController();
            wic.addOnControllableInsetsChangedListener(this);
            wic.show(ime());
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            synchronized (this) {
                mHasFocus = hasFocus;
                notifyAll();
            }
        }

        @Override
        public void onControllableInsetsChanged(@NonNull WindowInsetsController controller,
                int typeMask) {
            synchronized (this) {
                mControlsIme = (typeMask & ime()) != 0;
                notifyAll();
            }
        }

        boolean isImeVisible() {
            return mHasFocus && mImeVisible;
        }

        @Override
        public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
            mImeVisible = insets.isVisible(ime());
            return v.onApplyWindowInsets(insets);
        }
    }
}
