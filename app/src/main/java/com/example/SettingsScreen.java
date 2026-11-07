package com.example;

import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.Locale;

/** Настройки распознавания, отображения и моделей. Параметры звука находятся на экране записи. */
final class SettingsScreen extends BaseScreen {
    private static final String[] LANGUAGE_VALUES = {"ru", "en", ""};
    private static final long MODELS_BYTES = 639387718L + 6958444L + 39593761L;

    private final ScrollView scroll;
    private final ModelRepository models;
    private LinearLayout modelsBody;

    SettingsScreen(MainActivity host) {
        super(host);
        models = new ModelRepository(ctx);
        scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.addView(build(), new FrameLayout.LayoutParams(-1, -2));
    }

    @Override View view() {
        return scroll;
    }

    private View build() {
        LinearLayout page = new LinearLayout(ctx);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(16), dp(20), dp(24));
        page.addView(Ui.bold(ctx, "Настройки", 28, Palette.TEXT));
        page.addView(Ui.space(ctx, 18));

        // Распознавание
        LinearLayout recognition = section("Распознавание");
        recognition.addView(Ui.text(ctx, "Язык речи", 12, Palette.MUTED), Ui.margins(Ui.lp(-2, -2), ctx, 0, 14, 0, 8));
        Ui.Segmented language = new Ui.Segmented(ctx, new String[]{"Русский", "English", "Авто"},
                languageIndex(settings.language()), index -> settings.setLanguage(LANGUAGE_VALUES[index]));
        recognition.addView(language);

        recognition.addView(Ui.text(ctx, "Число говорящих", 12, Palette.MUTED), Ui.margins(Ui.lp(-2, -2), ctx, 0, 18, 0, 8));
        final EditText speakerInput = new EditText(ctx);
        speakerInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        speakerInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2)});
        speakerInput.setSingleLine(true);
        speakerInput.setText(String.valueOf(Math.max(2, settings.speakerCount())));
        speakerInput.setTextColor(Palette.TEXT);
        speakerInput.setHintTextColor(Palette.MUTED);
        speakerInput.setTextSize(15);
        speakerInput.setBackground(Ui.box(ctx, Palette.SURFACE_2, 12, Palette.BORDER));
        speakerInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        speakerInput.setVisibility(settings.speakerCount() > 0 ? View.VISIBLE : View.GONE);
        speakerInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                if (speakerInput.getVisibility() != View.VISIBLE) return;
                settings.setSpeakerCount(Math.max(1, parseOr(speakerInput, 2)));
            }
        });
        Ui.Segmented speakers = new Ui.Segmented(ctx, new String[]{"Авто", "Указать"},
                settings.speakerCount() > 0 ? 1 : 0, index -> {
            boolean manual = index == 1;
            speakerInput.setVisibility(manual ? View.VISIBLE : View.GONE);
            settings.setSpeakerCount(manual ? Math.max(1, parseOr(speakerInput, 2)) : 0);
        });
        recognition.addView(speakers);
        recognition.addView(speakerInput, Ui.margins(Ui.lp(dp(96), -2), ctx, 0, 10, 0, 0));
        TextView speakersHint = Ui.text(ctx, "«Авто» подходит для большинства записей. Если знаете точное число "
                + "(от 1 до " + Settings.MAX_SPEAKERS + "), укажите его: разделение станет заметно точнее. "
                + "Действует на новые расшифровки.", 12, Palette.MUTED);
        speakersHint.setLineSpacing(dp(1), 1f);
        recognition.addView(speakersHint, Ui.margins(Ui.lp(-1, -2), ctx, 2, 8, 0, 0));

        recognition.addView(switchRow("Шумоподавление",
                "Очищать звук от шума перед расшифровкой. Небольшая модель (около 0,5 МБ) скачается при первом использовании.",
                settings.denoise(), settings::setDenoise));
        recognition.addView(switchRow("Расшифровать после записи",
                "Запускается в фоне сразу после остановки записи.",
                settings.autoTranscribe(), settings::setAutoTranscribe));
        page.addView(recognition);

        // Отображение
        page.addView(Ui.space(ctx, 16));
        LinearLayout display = section("Отображение");
        display.addView(switchRow("Цвета говорящих",
                "Выделять реплики разных людей цветом. Выключено — чистый markdown.",
                settings.speakerColors(), settings::setSpeakerColors));
        page.addView(display);

        // Модели
        page.addView(Ui.space(ctx, 16));
        LinearLayout modelsCard = section("Модели распознавания");
        modelsBody = new LinearLayout(ctx);
        modelsBody.setOrientation(LinearLayout.VERTICAL);
        modelsCard.addView(modelsBody, Ui.margins(Ui.lp(-1, -2), ctx, 0, 10, 0, 0));
        page.addView(modelsCard);
        return page;
    }

    private LinearLayout section(String title) {
        LinearLayout card = Ui.card(ctx);
        card.addView(Ui.bold(ctx, title, 16, Palette.TEXT));
        return card;
    }

    private View switchRow(String title, String subtitle, boolean checked, java.util.function.Consumer<Boolean> change) {
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(Ui.text(ctx, title, 15, Palette.TEXT));
        TextView sub = Ui.text(ctx, subtitle, 12, Palette.MUTED);
        sub.setLineSpacing(dp(1), 1f);
        text.addView(sub, Ui.margins(Ui.lp(-1, -2), ctx, 0, 3, 0, 0));
        row.addView(text, Ui.margins(Ui.lp(0, -2, 1f), ctx, 0, 0, 12, 0));
        row.addView(Ui.toggle(ctx, checked, (b, on) -> change.accept(on)));
        row.setLayoutParams(Ui.margins(Ui.lp(-1, -2), ctx, 0, 18, 0, 0));
        return row;
    }

    private static int parseOr(EditText field, int fallback) {
        try {
            return Integer.parseInt(field.getText().toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int languageIndex(String code) {
        for (int i = 0; i < LANGUAGE_VALUES.length; i++) if (LANGUAGE_VALUES[i].equals(code)) return i;
        return 0;
    }

    // ---- модели -------------------------------------------------------------------------------

    @Override void refresh() {
        modelsBody.removeAllViews();
        boolean installed = models.installed();
        boolean busy = TranscriptionService.isDownloadingModels();
        String error = TranscriptionService.modelsError();

        if (busy) {
            String label = TranscriptionService.modelsLabel();
            modelsBody.addView(Ui.text(ctx, label.isEmpty() ? "Загрузка" : label, 13, Palette.ACCENT_SOFT));
            Ui.ProgressLine line = new Ui.ProgressLine(ctx);
            line.set(TranscriptionService.modelsPercent());
            modelsBody.addView(line, Ui.margins(Ui.lp(-1, dp(4)), ctx, 0, 10, 0, 0));
            return;
        }
        if (installed) {
            modelsBody.addView(Ui.text(ctx, "Установлены · " + sizeOnDisk(), 14, Palette.GREEN));
            modelsBody.addView(Ui.text(ctx, "Whisper Small, сегментация и распознавание голосов. Работают без интернета.",
                    12, Palette.MUTED), Ui.margins(Ui.lp(-1, -2), ctx, 0, 6, 0, 0));
            return;
        }
        modelsBody.addView(Ui.text(ctx, "Не установлены", 14, Palette.ORANGE));
        modelsBody.addView(Ui.text(ctx, String.format(Locale.ROOT,
                "Нужны для расшифровки. Загрузка один раз, около %.0f МБ; дальше всё работает без сети.",
                MODELS_BYTES / (1024.0 * 1024.0)), 12, Palette.MUTED), Ui.margins(Ui.lp(-1, -2), ctx, 0, 6, 0, 0));
        if (!error.isEmpty()) {
            modelsBody.addView(Ui.text(ctx, error, 12, Palette.RED), Ui.margins(Ui.lp(-1, -2), ctx, 0, 8, 0, 0));
        }
        modelsBody.addView(Ui.primaryButton(ctx, error.isEmpty() ? "Скачать модели" : "Повторить загрузку",
                v -> host.downloadModels()), Ui.margins(Ui.lp(-1, -2), ctx, 0, 14, 0, 0));
    }

    private String sizeOnDisk() {
        long bytes = directorySize(new File(ctx.getFilesDir(), "models"));
        return String.format(Locale.ROOT, "%.0f МБ", bytes / (1024.0 * 1024.0));
    }

    private static long directorySize(File dir) {
        long total = 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        for (File child : children) total += child.isDirectory() ? directorySize(child) : child.length();
        return total;
    }
}
