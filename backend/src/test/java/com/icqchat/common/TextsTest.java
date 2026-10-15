package com.icqchat.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("Texts: пустой текст и символ U+0000")
class TextsTest {

    static Stream<Arguments> blank() {
        return Stream.of(
                arguments("пустая строка", ""),
                arguments("обычные пробелы и табуляция", "  \t \n "),
                arguments("неразрывные пробелы", "\u00A0\u00A0\u00A0"),
                arguments("узкие неразрывные пробелы", "\u2007\u202F"),
                arguments("пробелы нулевой ширины", "\u200B\u200B\u200B"),
                arguments("соединители и BOM", "\u200C\u200D\u2060\uFEFF"),
                arguments("мягкий перенос", "\u00AD"),
                arguments("хангыльский заполнитель", "\u3164\u3164"),
                arguments("пустой символ Брайля", "\u2800"),
                arguments("селектор начертания", "\uFE0F"),
                arguments("всё вперемешку", " \u00A0\u200B\u3164\u2800\uFEFF ")
        );
    }

    static Stream<Arguments> notBlank() {
        return Stream.of(
                arguments("латиница", "a"),
                arguments("кириллица", "я"),
                arguments("эмодзи", "\uD83D\uDE00"),
                arguments("текст в пробелах", "  hi  "),
                arguments("текст с пробелами нулевой ширины", "\u200Bhi\u200B"),
                arguments("эмодзи с селектором начертания", "\u2764\uFE0F"),
                arguments("точка", ".")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("blank")
    @DisplayName("пустой текст")
    void detectsBlank(String description, String text) {
        assertTrue(Texts.isVisuallyBlank(text));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notBlank")
    @DisplayName("текст с видимыми символами")
    void acceptsVisibleText(String description, String text) {
        assertFalse(Texts.isVisuallyBlank(text));
    }

    @Test
    @DisplayName("containsNul находит U+0000 в любом месте строки")
    void detectsNul() {
        assertTrue(Texts.containsNul("\u0000"));
        assertTrue(Texts.containsNul("при\u0000вет"));
        assertFalse(Texts.containsNul("привет"));
        assertFalse(Texts.containsNul(""));
        assertFalse(Texts.containsNul(null));
    }
}
