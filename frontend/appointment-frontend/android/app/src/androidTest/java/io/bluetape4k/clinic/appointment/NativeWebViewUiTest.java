package io.bluetape4k.clinic.appointment;

import static androidx.test.espresso.web.sugar.Web.onWebView;
import static androidx.test.espresso.web.model.Atoms.script;

import android.graphics.Rect;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class NativeWebViewUiTest {

    @Rule
    public ActivityScenarioRule<MainActivity> activityRule =
        new ActivityScenarioRule<>(MainActivity.class);

    @Test
    public void nativeTapFocusKeyboardViewportAndOrientationRemainUsable() throws Exception {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

        UiObject2 appointmentTab = waitForText(device, "예약 관리");
        assertNotNull("bottom tab must be exposed through the native accessibility tree", appointmentTab);
        Rect tabBounds = appointmentTab.getVisibleBounds();
        assertTrue("bottom tab must expose at least a 44px touch target", tabBounds.width() >= 44);
        assertTrue("bottom tab must expose at least a 44px touch target", tabBounds.height() >= 44);
        device.click(tabBounds.centerX(), tabBounds.centerY());

        UiObject2 appointmentTitle = waitForText(device, "예약 목록");
        assertNotNull("native tap must navigate to the appointment route", appointmentTitle);
        onWebView().perform(script(
            "if (!document.body.innerText.includes('예약 목록')) "
                + "throw new Error('appointment route title is missing');"
        ));
        onWebView().perform(script(
            "const tab = document.querySelector('a[href=\\\"/appointments\\\"]'); "
                + "if (!tab || tab.getBoundingClientRect().width < 44 || "
                + "tab.getBoundingClientRect().height < 44) "
                + "throw new Error('bottom tab CSS touch target is smaller than 44px');"
        ));

        UiObject2 dateInput = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000);
        assertNotNull("date input must be accessible for keyboard/focus evidence", dateInput);
        dateInput.click();
        onWebView().perform(script(
            "const input = document.querySelector('input:focus'); "
                + "if (!input) throw new Error('date input did not receive focus'); "
                + "if (!window.visualViewport) throw new Error('visualViewport is unavailable'); "
                + "if (window.visualViewport.height <= 0) throw new Error('visualViewport height is invalid');"
        ));

        try {
            device.setOrientationLeft();
            device.waitForIdle();
            onWebView().perform(script(
                "const viewport = window.visualViewport; "
                    + "const content = document.querySelector('.mobile-content'); "
                    + "if (!viewport || !content) throw new Error('landscape viewport/content is unavailable'); "
                    + "if (content.getBoundingClientRect().bottom > viewport.height + 1) "
                    + "throw new Error('content is obscured beyond the landscape viewport');"
            ));
        } finally {
            device.setOrientationNatural();
            device.waitForIdle();
        }
    }

    private static UiObject2 waitForText(UiDevice device, String text) {
        return device.wait(Until.findObject(By.textContains(text)), 15_000);
    }
}
