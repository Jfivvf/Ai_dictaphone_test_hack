package com.example;

/** Цвета интерфейса и цвета говорящих. Значения совпадают с res/values/colors.xml. */
final class Palette {
    static final int BG = 0xFF0E0E11;
    static final int SURFACE = 0xFF17171B;
    static final int SURFACE_2 = 0xFF1F1F24;
    static final int BORDER = 0xFF2B2B32;
    static final int TEXT = 0xFFF2F2F6;
    static final int MUTED = 0xFF8F8F9C;
    static final int ACCENT = 0xFF5B4CF0;
    static final int ACCENT_SOFT = 0xFF9B90FF;
    static final int RECORD = 0xFFE8173F;
    static final int GREEN = 0xFF2FBF7B;
    static final int ORANGE = 0xFFF5A524;
    static final int RED = 0xFFFF5A6E;

    private static final int[] SPEAKERS = {
            0xFF9B90FF, 0xFF35C9B0, 0xFFF5A524, 0xFFFF6FA8,
            0xFF4FB3FF, 0xFF9BDB4D, 0xFFFF8A4C, 0xFFC27BFF
    };

    /** Те же оттенки, затемнённые для белой бумаги в PDF. */
    private static final int[] SPEAKERS_PRINT = {
            0xFF4F46E5, 0xFF0F8F7C, 0xFFB46A00, 0xFFC2275F,
            0xFF1D74C9, 0xFF4F8A0F, 0xFFC4561A, 0xFF8A3FD1
    };

    private Palette() {}

    static int speaker(int index) {
        return index < 0 ? MUTED : SPEAKERS[index % SPEAKERS.length];
    }

    static int speakerPrint(int index) {
        return index < 0 ? 0xFF555560 : SPEAKERS_PRINT[index % SPEAKERS_PRINT.length];
    }

    /** Цвет с заданной прозрачностью (0..255). */
    static int alpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }
}
