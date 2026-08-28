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

        UiObject2 appointmentTab = waitForTextOrDescription(device, "예약 관리");
        if (appointmentTab != null) {
            Rect tabBounds = appointmentTab.getVisibleBounds();
            assertTrue("bottom tab must expose at least a 44px touch target", tabBounds.width() >= 44);
            assertTrue("bottom tab must expose at least a 44px touch target", tabBounds.height() >= 44);
            assertTrue("native accessibility tap must be dispatched", device.click(tabBounds.centerX(), tabBounds.centerY()));
        } else {
            UiObject2 webView = waitForWebView(device);
            assertNotNull("Capacitor WebView host must be available for native coordinate input", webView);
            Rect webViewBounds = webView.getVisibleBounds();
            assertTrue("WebView host must expose a usable touch surface", webViewBounds.width() >= 44);
            assertTrue("WebView host must expose a usable touch surface", webViewBounds.height() >= 44);

            float density = InstrumentationRegistry.getInstrumentation()
                .getTargetContext()
                .getResources()
                .getDisplayMetrics()
                .density;
            int bottomNavigationCenterY = webViewBounds.bottom - Math.round(28 * density);
            assertTrue(
                "native WebView host coordinate tap must be dispatched",
                device.click(webViewBounds.centerX(), bottomNavigationCenterY)
            );
        }

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

    private static UiObject2 waitForTextOrDescription(UiDevice device, String text) {
        UiObject2 textNode = device.wait(Until.findObject(By.textContains(text)), 7_500);
        if (textNode != null) {
            return textNode;
        }
        return device.wait(Until.findObject(By.descContains(text)), 7_500);
    }

    private static UiObject2 waitForWebView(UiDevice device) {
        UiObject2 webView = device.wait(Until.findObject(By.clazz(".*WebView")), 10_000);
        if (webView != null) {
            return webView;
        }
        return device.wait(Until.findObject(By.clazz("android.webkit.WebView")), 2_500);
    }
}
