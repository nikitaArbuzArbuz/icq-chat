package com.icqchat.common;

import java.util.Set;

/**
 * Проверка, что в тексте нет ни одного видимого символа.
 * <p>
 * String.isBlank() считает пустыми только символы, для которых Character.isWhitespace = true.
 * Мимо неё проходят неразрывные пробелы (U+00A0, U+2007, U+202F), пробел нулевой ширины (U+200B)
 * и другие форматирующие символы, а также символы, которые формально буквы или знаки, но выглядят
 * как пустое место: хангыльские заполнители, пустой символ Брайля.
 */
public final class Texts {
    /** Невидимые символы, которые не попадают ни в пробельные, ни в форматирующие категории Unicode. */
    private static final Set<Integer> INVISIBLE_FILLERS = Set.of(
            0x034F,  // соединитель графем
            0x115F,  // хангыльский заполнитель начальной согласной
            0x1160,  // хангыльский заполнитель гласной
            0x3164,  // хангыльский заполнитель
            0xFFA0,  // полуширинный хангыльский заполнитель
            0x2800,  // пустой символ Брайля
            0x1D159  // пустая нотная головка
    );

    private Texts() {
    }

    /** true для null, пустой строки и строки, в которой нет ни одного видимого символа. */
    public static boolean isVisuallyBlank(String text) {
        return text == null || text.codePoints().allMatch(Texts::isInvisible);
    }

    private static boolean isInvisible(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)      // в том числе неразрывные пробелы
                || type == Character.FORMAT              // пробел нулевой ширины, соединители, мягкий перенос
                || type == Character.CONTROL
                || isVariationSelector(codePoint)
                || INVISIBLE_FILLERS.contains(codePoint);
    }

    /** Селекторы начертания сами по себе ничего не рисуют, только меняют вид предыдущего символа. */
    private static boolean isVariationSelector(int codePoint) {
        return (codePoint >= 0xFE00 && codePoint <= 0xFE0F) || (codePoint >= 0xE0100 && codePoint <= 0xE01EF);
    }
}
