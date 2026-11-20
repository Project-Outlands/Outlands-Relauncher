package top.outlands.relauncher;

import com.formdev.flatlaf.FlatSystemProperties;
import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.geom.AffineTransform;
import java.util.Locale;

/** Supplies a scoped display-scale fallback when Java 8 reports undersized Windows UI fonts. */
final class WindowsUiScale {
    private static final int BASE_DPI = 96;
    private static final Runnable NO_CHANGE = () -> {};

    private WindowsUiScale() {}

    /** Call on the EDT before installing FlatLaf; run the returned cleanup before restoring the old LAF. */
    static Runnable applyIfNeeded() {
        try {
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
                    || !"1.8".equals(System.getProperty("java.specification.version"))
                    || System.getProperty(FlatSystemProperties.UI_SCALE) != null
                    || !FlatSystemProperties.getBoolean(FlatSystemProperties.UI_SCALE_ENABLED, true)
                    || System.getProperty("flatlaf.uiScale.fontSizeDivider") != null
                    || GraphicsEnvironment.isHeadless()) {
                return NO_CHANGE;
            }

            AffineTransform transform = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration().getDefaultTransform();
            if (transform.getScaleX() != 1 || transform.getScaleY() != 1) {
                return NO_CHANGE;
            }

            Toolkit toolkit = Toolkit.getDefaultToolkit();
            int dpi = Win32.systemDpi(toolkit);
            String hint = scaleHint(dpi, desktopFont(toolkit, "win.defaultGUI.font"),
                    desktopFont(toolkit, "win.messagebox.font"));
            if (hint == null) {
                return NO_CHANGE;
            }
            System.setProperty(FlatSystemProperties.UI_SCALE, hint);
            return () -> {
                try {
                    if (hint.equals(System.getProperty(FlatSystemProperties.UI_SCALE))) {
                        System.clearProperty(FlatSystemProperties.UI_SCALE);
                    }
                } catch (SecurityException ignored) {
                    // A denied property write must not prevent restoring the original LAF.
                }
            };
        } catch (LinkageError | RuntimeException unavailable) {
            // JNA and the native API are optional: keep FlatLaf's default behavior on failure.
            return NO_CHANGE;
        }
    }

    static String scaleHint(int dpi, Font layoutFont, Font textFont) {
        if (dpi <= BASE_DPI) {
            return null;
        }
        if (layoutFont == null) {
            layoutFont = textFont;
        }
        float target = dpi / (float) BASE_DPI;
        int targetTextSize = Math.round(baseSize(textFont) * target);
        // FlatLaf 3.8 rounds the automatically inferred layout scale to quarter steps.
        float layoutScale = layoutFont == null ? 1f
                : Math.round(4f * layoutFont.getSize() / baseSize(layoutFont)) / 4f;
        int currentTextSize = textFont == null ? 12 : textFont.getSize();
        // The explicit FlatLaf scale replaces the font size, so never shrink accessibility text.
        if (currentTextSize > targetTextSize || (currentTextSize == targetTextSize && layoutScale >= target)) {
            return null;
        }
        return dpi + "dpi";
    }

    private static int baseSize(Font font) {
        return font != null && "Tahoma".equals(font.getFamily()) ? 11 : 12;
    }

    private static Font desktopFont(Toolkit toolkit, String property) {
        Object value = toolkit.getDesktopProperty(property);
        return value instanceof Font ? (Font) value : null;
    }

    // Isolate JNA references so loading the helper does not require JNA on a non-client classpath.
    private static final class Win32 {
        private static int systemDpi(Toolkit toolkit) {
            NativeLibrary user32 = NativeLibrary.getInstance("user32");
            try {
                // DPI-unaware threads get virtualized 96 DPI; Windows scales their windows already.
                return call(user32, "GetDpiForSystem");
            } catch (UnsatisfiedLinkError missingOnOlderWindows) {
                return call(user32, "IsProcessDPIAware") != 0 ? toolkit.getScreenResolution() : BASE_DPI;
            }
        }

        private static int call(NativeLibrary library, String name) {
            return library.getFunction(name, Function.ALT_CONVENTION).invokeInt(new Object[0]);
        }
    }
}
