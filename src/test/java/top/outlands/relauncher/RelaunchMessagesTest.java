package top.outlands.relauncher;

import java.io.IOException;
import java.util.Collections;
import java.util.Locale;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.formdev.flatlaf.FlatDarkLaf;
import org.junit.jupiter.api.Test;

import top.outlands.relauncher.shared.JvmOptions;
import top.outlands.relauncher.shared.PackSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelaunchMessagesTest {
    @Test
    void usesDisplayLocaleAndFallsBackToEnglishForUnsupportedLanguages() {
        Locale oldDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        try {
            Locale.setDefault(Locale.Category.DISPLAY, Locale.SIMPLIFIED_CHINESE);
            assertEquals("Outlands 启动设置", RelaunchMessages.display().text("settings.title"));
            for (String tag : new String[] {"zh", "zh-CN", "zh-SG", "zh-Hans"}) {
                assertEquals("启动 Outlands（L）", new RelaunchMessages(Locale.forLanguageTag(tag)).text("button.launch"));
            }
            for (String tag : new String[] {"en", "fr-FR", "zh-TW", "zh-Hant"}) {
                assertEquals("Launch Outlands", new RelaunchMessages(Locale.forLanguageTag(tag)).text("button.launch"));
            }
        } finally {
            Locale.setDefault(Locale.Category.DISPLAY, oldDisplay);
        }
    }

    @Test
    void localizesTypedValidationFailuresWithoutExposingInputs() {
        RelaunchMessages english = new RelaunchMessages(Locale.ENGLISH);
        RelaunchMessages chinese = new RelaunchMessages(Locale.SIMPLIFIED_CHINESE);
        IOException option = assertThrows(IOException.class,
                () -> JvmOptions.resolve("", "", "-Dexample=valid\n-not-allowed", Collections.emptyList()));
        RelaunchMessages.Message optionMessage = RelaunchMessages.validation(option);
        assertEquals(option.getMessage(), english.text(optionMessage));
        assertEquals("第 2 行的 JVM 参数不受支持。请每行只填写一个参数。", chinese.text(optionMessage));

        IOException version = assertThrows(IOException.class, () -> PackSettings.releaseVersion("bad-version"));
        assertEquals("请从预设列表选择 Outlands 发布版本，或选择本地 MMC ZIP 或安装器 JAR。",
                chinese.text(RelaunchMessages.validation(version)));
        assertEquals("无法检查启动设置，请核对输入后重试。",
                chinese.text(RelaunchMessages.validation(new IOException("Private input"))));
    }

    @Test
    void flatLafChooserUsesTheRelauncherLanguageRatherThanSwingDefault() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LookAndFeel previous = UIManager.getLookAndFeel();
            Locale previousLocale = JComponent.getDefaultLocale();
            try {
                JComponent.setDefaultLocale(Locale.ENGLISH);
                assertTrue(FlatDarkLaf.setup());
                JFileChooser chinese = RelaunchUi.createChooser(new RelaunchMessages(Locale.SIMPLIFIED_CHINESE));
                assertEquals("打开", chinese.getUI().getApproveButtonText(chinese));
                JFileChooser english = RelaunchUi.createChooser(new RelaunchMessages(Locale.ENGLISH));
                assertEquals("Open", english.getUI().getApproveButtonText(english));
            } finally {
                JComponent.setDefaultLocale(previousLocale);
                try {
                    UIManager.setLookAndFeel(previous);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }
        });
    }
}
