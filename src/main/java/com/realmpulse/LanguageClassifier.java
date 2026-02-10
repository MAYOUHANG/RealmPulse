package com.realmpulse;

public final class LanguageClassifier {
    public enum Result {
        EN,
        ZH,
        OTHER
    }

    private LanguageClassifier() {
    }

    public static Result classify(String text) {
        if (text == null || text.isBlank()) {
            return Result.OTHER;
        }

        int asciiLetters = 0;
        int chineseChars = 0;
        int letters = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                asciiLetters++;
                letters++;
            } else if (Character.isLetter(c)) {
                letters++;
            }

            if (isChineseChar(c)) {
                chineseChars++;
            }
        }

        if (chineseChars > 0 && asciiLetters == 0) {
            return Result.ZH;
        }

        if (asciiLetters >= 3 && chineseChars == 0) {
            double ratio = letters == 0 ? 1.0 : ((double) asciiLetters / (double) letters);
            if (ratio >= 0.75) {
                return Result.EN;
            }
        }

        if (chineseChars > 0 && asciiLetters > 0) {
            if (chineseChars >= asciiLetters) {
                return Result.ZH;
            }
            if (asciiLetters >= 3 && asciiLetters >= chineseChars * 2) {
                return Result.EN;
            }
        }

        if (chineseChars > 0) {
            return Result.ZH;
        }
        if (asciiLetters >= 3) {
            return Result.EN;
        }
        return Result.OTHER;
    }

    public static boolean isEnglish(String text) {
        return classify(text) == Result.EN;
    }

    public static boolean isChinese(String text) {
        return classify(text) == Result.ZH;
    }

    public static boolean matches(String text, boolean englishExpected) {
        Result result = classify(text);
        return englishExpected ? result == Result.EN : result == Result.ZH;
    }

    private static boolean isChineseChar(char c) {
        return (c >= '\u4e00' && c <= '\u9fff') || (c >= '\u3400' && c <= '\u4dbf');
    }
}
