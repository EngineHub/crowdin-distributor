package org.enginehub.crowdin;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.jetbrains.annotations.Nullable;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Map;

public class TranslationValidator {

    private static MessageFormat newMessageFormat(String pattern) {
        return new MessageFormat(pattern.replace("'", "''"));
    }

    private final Map<String, MessageFormat> source;

    public TranslationValidator(Map<String, String> source) {
        this.source = ImmutableMap.copyOf(
            Maps.transformValues(source, TranslationValidator::newMessageFormat)
        );
    }

    /**
     * @return the error or {@code null} if it's all good
     */
    public @Nullable String validate(String context, Map<String, String> other) {
        var failures = new ArrayList<String>();
        for (var entry : other.entrySet()) {
            MessageFormat format;
            try {
                format = newMessageFormat(entry.getValue());
            } catch (IllegalArgumentException e) {
                failures.add("Entry '%s' in %s is invalid: %s".formatted(
                    entry.getKey(), context, e.getMessage()
                ));
                continue;
            }
            var match = source.get(entry.getKey());
            if (match == null) {
                failures.add("Entry '%s' in %s is invalid: %s".formatted(
                    entry.getKey(), context, "No corresponding source[key] entry"
                ));
                continue;
            }
            var expected = match.getFormats().length;
            var actual = format.getFormats().length;
            if (expected != actual) {
                failures.add(
                    String.format("""
                            Entry '%s' in %s has %s formats instead of %s
                            Literal expected: %s
                            Literal actual: %s
                            """,
                        entry.getKey(), context, actual, expected,
                        match.toPattern(), format.toPattern()
                    )
                );
            }
        }
        return failures.isEmpty() ? null : String.join("\n", failures);
    }
}
