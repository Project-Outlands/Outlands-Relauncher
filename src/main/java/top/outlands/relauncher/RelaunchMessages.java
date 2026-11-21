package top.outlands.relauncher;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

import top.outlands.relauncher.shared.ValidationException;

/** Translates the pre-Minecraft UI without depending on Forge or the preparation JVM. */
final class RelaunchMessages {
    private static final String BUNDLE = "top.outlands.relauncher.i18n.Messages";
    private static final ResourceBundle.Control UTF8 = new ResourceBundle.Control() {
        @Override
        public List<String> getFormats(String baseName) {
            return FORMAT_PROPERTIES;
        }

        @Override
        public Locale getFallbackLocale(String baseName, Locale locale) {
            return null;
        }

        @Override
        public ResourceBundle newBundle(String baseName, Locale locale, String format, ClassLoader loader,
                                        boolean reload) throws IOException {
            URL url = loader.getResource(toResourceName(toBundleName(baseName, locale), "properties"));
            if (url == null) return null;
            URLConnection connection = url.openConnection();
            if (reload) connection.setUseCaches(false);
            try (InputStream stream = connection.getInputStream();
                    InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return new PropertyResourceBundle(reader);
            }
        }
    };

    private final Locale locale;
    private final ResourceBundle bundle;

    RelaunchMessages(Locale locale) {
        // Prefer Simplified Chinese for a bare zh request. Explicit Hant locales fall back to English.
        Locale lookup = Locale.CHINESE.equals(locale.stripExtensions()) ? Locale.SIMPLIFIED_CHINESE : locale;
        bundle = load(lookup);
        this.locale = bundle.getLocale().equals(Locale.ROOT) ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE;
    }

    static RelaunchMessages display() {
        return new RelaunchMessages(Locale.getDefault(Locale.Category.DISPLAY));
    }

    private static ResourceBundle load(Locale locale) {
        return ResourceBundle.getBundle(BUNDLE, locale, RelaunchMessages.class.getClassLoader(), UTF8);
    }

    Locale locale() {
        return locale;
    }

    String text(String key, Object... args) {
        String pattern = bundle.getString(key);
        return args.length == 0 ? pattern : new MessageFormat(pattern, locale).format(args);
    }

    String text(Message message) {
        Object[] args = message.arguments.clone();
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Message) args[i] = text((Message) args[i]);
        }
        return text(message.key, args);
    }

    static Message validation(Throwable failure) {
        if (failure instanceof ValidationException) {
            ValidationException invalid = (ValidationException) failure;
            return new Message(invalid.key(), invalid.arguments());
        }
        return new Message(failure instanceof InterruptedException ? "validation.interrupted" : "validation.failed");
    }

    static final class Message {
        private final String key;
        private final Object[] arguments;

        Message(String key, Object... arguments) {
            this.key = key;
            this.arguments = arguments.clone();
        }
    }
}
