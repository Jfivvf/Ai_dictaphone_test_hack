package com.example;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Одна запись: расшифровка по репликам с таймкодами, конспект в markdown, плеер и действия над записью.
 * Нажатие на реплику или таймкод переносит воспроизведение к этому месту.
 */
final class DetailScreen extends BaseScreen {
    private static final int TAB_TRANSCRIPT = 0;
    private static final int TAB_NOTE = 1;
    private static final float[] SPEEDS = {1f, 1.25f, 1.5f, 2f, 0.75f};
    private static final Pattern STAMP_LABEL = Pattern.compile("(?m)^\\[(\\d\\d:\\d\\d:\\d\\d)\\]");
    private static final Pattern STAMP_TAIL = Pattern.compile("(?<=[—·] )(\\d\\d:\\d\\d:\\d\\d)");

    final long id;

    private Recording recording;
    private List<Segment> segments = new ArrayList<>();
    private List<Task> tasks = new ArrayList<>();
    private int tab = TAB_TRANSCRIPT;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private TextView titleView;
    private TextView metaView;
    private LinearLayout statusBox;
    private FrameLayout body;
    private ListView transcriptList;
    private TextView emptyView;
    private ScrollView noteScroll;
    private TextView noteText;
    private TextView colorChip;
    private TextView cleanChip;
    private TextView denoiseChip;
    private final SegmentAdapter adapter = new SegmentAdapter();

    private LinearLayout playerBar;
    private FrameLayout playButton;
    private SeekBar seek;
    private TextView timeText;
    private TextView speedText;
    private Ui.Segmented tabs;
    private MediaPlayer player;
    private boolean playerTried = false;
    private boolean prepared = false;
    private boolean playing = false;
    private boolean dragging = false;
    private long pendingSeekMs = -1;
    private boolean pendingPlay = false;
    private int speedIndex = 0;
    private int currentSegment = -1;
    private long lastTouch = 0;
    private long focusMs;

    private final Runnable playTick = new Runnable() {
        @Override public void run() {
            if (!playing || player == null) return;
            try {
                long position = player.getCurrentPosition();
                if (!dragging) seek.setProgress((int) position);
                updateTime(position);
                highlight(position);
            } catch (IllegalStateException ignored) {
                return;
            }
            ui.postDelayed(this, 250);
        }
    };

    DetailScreen(MainActivity host, long id, long focusMs) {
        super(host);
        this.id = id;
        this.focusMs = focusMs;

        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setClickable(true);

        // Верхняя панель
        LinearLayout top = new LinearLayout(ctx);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(4), dp(8), 0);
        top.addView(Ui.iconButton(ctx, R.drawable.ic_back, Palette.TEXT, 0, 44, 24, "Назад", v -> host.closeDetail()));
        titleView = Ui.bold(ctx, "", 18, Palette.TEXT);
        Ui.ellipsized(titleView, 1);
        titleView.setOnClickListener(v -> rename());
        top.addView(titleView, Ui.margins(Ui.lp(0, -2, 1f), ctx, 6, 0, 6, 0));
        FrameLayout more = Ui.iconButton(ctx, R.drawable.ic_more, Palette.TEXT, 0, 44, 24, "Меню", v -> {});
        more.setOnClickListener(v -> showMenu(more));
        top.addView(more);
        root.addView(top);

        metaView = Ui.text(ctx, "", 12, Palette.MUTED);
        metaView.setPadding(dp(20), dp(2), dp(20), 0);
        root.addView(metaView);

        statusBox = new LinearLayout(ctx);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        statusBox.setVisibility(View.GONE);
        root.addView(statusBox, Ui.margins(Ui.lp(-1, -2), ctx, 16, 12, 16, 0));

        tabs = new Ui.Segmented(ctx, new String[]{"Расшифровка", "Конспект"}, tab, this::showTab);
        root.addView(tabs, Ui.margins(Ui.lp(-1, -2), ctx, 16, 12, 16, 0));

        // Чипы настроек: каждый view добавляется ровно один раз, весь ряд прокручивается по горизонтали.
        LinearLayout options = new LinearLayout(ctx);
        colorChip = Ui.toggleChip(ctx, "Цвета говорящих", settings.speakerColors(), v -> {
            settings.setSpeakerColors(!settings.speakerColors());
            Ui.styleToggleChip(ctx, colorChip, settings.speakerColors());
            rebuildViews();
        });
        cleanChip = Ui.toggleChip(ctx, "Убрать слова-паразиты", settings.cleanText(), v -> {
            settings.setCleanText(!settings.cleanText());
            Ui.styleToggleChip(ctx, cleanChip, settings.cleanText());
            rebuildViews();
        });
        denoiseChip = Ui.toggleChip(ctx, "Шумоподавление", settings.denoise(), v -> toggleDenoise());
        options.addView(colorChip);
        options.addView(cleanChip, Ui.margins(Ui.lp(-2, -2), ctx, 8, 0, 0, 0));
        options.addView(denoiseChip, Ui.margins(Ui.lp(-2, -2), ctx, 8, 0, 0, 0));
        HorizontalScrollView optionsScroll = new HorizontalScrollView(ctx);
        optionsScroll.setHorizontalScrollBarEnabled(false);
        optionsScroll.addView(options);
        root.addView(optionsScroll, Ui.margins(Ui.lp(-1, -2), ctx, 16, 10, 16, 0));

        body = new FrameLayout(ctx);
        transcriptList = new ListView(ctx);
        Ui.styleList(transcriptList);
        transcriptList.setPadding(dp(10), dp(8), dp(10), dp(12));
        transcriptList.setAdapter(adapter);
        transcriptList.setOnTouchListener((v, event) -> {
            lastTouch = SystemClock.uptimeMillis();
            return false;
        });
        body.addView(transcriptList, new FrameLayout.LayoutParams(-1, -1));

        emptyView = Ui.text(ctx, "", 15, Palette.MUTED);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        body.addView(emptyView, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        noteScroll = new ScrollView(ctx);
        noteScroll.setVerticalScrollBarEnabled(false);
        noteScroll.setVisibility(View.GONE);
        noteText = Ui.text(ctx, "", 15, Palette.TEXT);
        noteText.setLineSpacing(dp(3), 1f);
        noteText.setMovementMethod(LinkMovementMethod.getInstance());
        noteText.setHighlightColor(0);
        noteText.setPadding(dp(20), dp(14), dp(20), dp(20));
        noteScroll.addView(noteText, new FrameLayout.LayoutParams(-1, -2));
        body.addView(noteScroll, new FrameLayout.LayoutParams(-1, -1));
        root.addView(body, Ui.lp(-1, 0, 1f));

        // Плеер
        playerBar = new LinearLayout(ctx);
        playerBar.setGravity(Gravity.CENTER_VERTICAL);
        playerBar.setPadding(dp(8), dp(6), dp(12), dp(6));
        playerBar.setBackground(Ui.box(ctx, Palette.SURFACE, 20, Palette.BORDER));
        playButton = Ui.iconButton(ctx, R.drawable.ic_play, 0xFFFFFFFF, Palette.ACCENT, 44, 22,
                "Воспроизвести", v -> togglePlay());
        playerBar.addView(playButton);
        seek = new SeekBar(ctx);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(Palette.ACCENT_SOFT));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(Palette.ACCENT_SOFT));
        seek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Palette.BORDER));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) updateTime(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                if (player != null && prepared) {
                    seekPlayer(bar.getProgress());
                    highlight(bar.getProgress());
                }
            }
        });
        playerBar.addView(seek, Ui.margins(Ui.lp(0, -2, 1f), ctx, 4, 0, 4, 0));
        timeText = Ui.mono(ctx, "00:00", 12, Palette.MUTED);
        playerBar.addView(timeText);
        speedText = Ui.bold(ctx, "1×", 13, Palette.ACCENT_SOFT);
        speedText.setGravity(Gravity.CENTER);
        speedText.setPadding(dp(10), dp(8), dp(10), dp(8));
        speedText.setBackground(Ui.ripple(Ui.box(ctx, Palette.SURFACE_2, 12)));
        speedText.setOnClickListener(v -> cycleSpeed());
        playerBar.addView(speedText, Ui.margins(Ui.lp(-2, -2), ctx, 8, 0, 0, 0));
        playerBar.setVisibility(View.GONE);
        root.addView(playerBar, Ui.margins(Ui.lp(-1, -2), ctx, 12, 4, 12, 10));

        onShow();
    }

    @Override View view() {
        return root;
    }

    @Override void onDataChanged(long recordingId) {
        if (recordingId >= 0 && recordingId != id) return;
        super.onDataChanged(recordingId);
    }

    // ---- данные -------------------------------------------------------------------------------

    @Override void refresh() {
        Recording fresh = db.get(id);
        if (fresh == null) {
            host.closeDetail();
            return;
        }
        boolean statusChanged = recording == null || !fresh.status.equals(recording.status);
        recording = fresh;
        titleView.setText(fresh.title);
        StringBuilder meta = new StringBuilder(Fmt.date(fresh.createdAt));
        if (fresh.durationMs > 0) meta.append(" · ").append(Fmt.duration(fresh.durationMs));
        metaView.setText(meta);
        renderStatus();

        if (statusChanged || !Status.isBusy(fresh.status)) {
            segments = db.segments(id);
            tasks = db.tasksFor(id);
            rebuildViews();
        }
        preparePlayer();
        if (focusMs >= 0 && !segments.isEmpty()) {
            long target = focusMs;
            focusMs = -1;
            focus(target);
        }
    }

    private void rebuildViews() {
        if (recording == null) return;
        adapter.notifyDataSetChanged();
        renderNote();
        boolean finished = Status.isFinished(recording.status);
        if (segments.isEmpty() && finished) {
            emptyView.setText("Речь в записи не найдена");
            emptyView.setVisibility(tab == TAB_TRANSCRIPT ? View.VISIBLE : View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    private void renderStatus() {
        statusBox.removeAllViews();
        String status = recording.status;
        boolean visible = true;
        statusBox.setBackground(Ui.box(ctx, Palette.SURFACE, 16, Palette.BORDER));
        statusBox.setPadding(dp(16), dp(14), dp(16), dp(14));

        if (Status.isBusy(status)) {
            String label = TranscriptionService.progressLabel(id);
            if (label.isEmpty()) label = Status.label(status);
            statusBox.addView(Ui.text(ctx, label, 14, Palette.ACCENT_SOFT));
            Ui.ProgressLine line = new Ui.ProgressLine(ctx);
            line.set(TranscriptionService.progressPercent(id));
            statusBox.addView(line, Ui.margins(Ui.lp(-1, dp(4)), ctx, 0, 10, 0, 0));
        } else if (Status.ERROR.equals(status)) {
            statusBox.addView(Ui.text(ctx, recording.error.isEmpty() ? "Не удалось расшифровать запись" : recording.error,
                    14, Palette.RED));
            statusBox.addView(Ui.secondaryButton(ctx, "Повторить", v -> host.transcribe(id)),
                    Ui.margins(Ui.lp(-1, -2), ctx, 0, 12, 0, 0));
        } else if (Status.RECORDED.equals(status)) {
            statusBox.addView(Ui.primaryButton(ctx, "Расшифровать", v -> host.transcribe(id)));
        } else if (Status.DONE_NO_SPEAKERS.equals(status) && !recording.error.isEmpty()) {
            statusBox.addView(Ui.text(ctx, recording.error, 13, Palette.ORANGE));
        } else {
            visible = false;
        }
        statusBox.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    // ---- вкладки и отображение ----------------------------------------------------------------

    private void showTab(int index) {
        tab = index;
        transcriptList.setVisibility(index == TAB_TRANSCRIPT ? View.VISIBLE : View.GONE);
        noteScroll.setVisibility(index == TAB_NOTE ? View.VISIBLE : View.GONE);
        rebuildViews();
    }

    private Map<String, Integer> speakerColors() {
        if (!settings.speakerColors()) return null;
        Map<String, Integer> colors = new LinkedHashMap<>();
        for (int speaker : NoteBuilder.speakersInOrder(segments)) {
            colors.put(recording.speakerName(speaker), Palette.speaker(speaker));
        }
        return colors;
    }

    private void renderNote() {
        if (segments.isEmpty()) {
            noteText.setText(Status.isFinished(recording.status) ? "Конспект пуст: в записи нет речи"
                    : "Конспект появится после расшифровки");
            noteText.setTextColor(Palette.MUTED);
            return;
        }
        noteText.setTextColor(Palette.TEXT);
        MarkdownRenderer renderer = new MarkdownRenderer(speakerColors(), Palette.TEXT, Palette.MUTED,
                Palette.ACCENT_SOFT, ctx.getResources().getDisplayMetrics().density);
        String markdown = NoteBuilder.markdown(recording, segments, tasks, settings.cleanText());
        SpannableStringBuilder out = new SpannableStringBuilder(renderer.render(markdown));
        linkTimecodes(out, STAMP_LABEL);
        linkTimecodes(out, STAMP_TAIL);
        noteText.setText(out);
    }

    /** Делает таймкоды в конспекте нажимаемыми: нажатие запускает воспроизведение с этого места. */
    private void linkTimecodes(SpannableStringBuilder out, Pattern pattern) {
        Matcher matcher = pattern.matcher(out);
        while (matcher.find()) {
            final long ms = parseStamp(matcher.group(1));
            if (ms < 0) continue;
            out.setSpan(new ClickableSpan() {
                @Override public void onClick(View widget) { playFrom(ms); }
                @Override public void updateDrawState(TextPaint paint) {
                    paint.setColor(Palette.ACCENT_SOFT);
                    paint.setUnderlineText(true);
                }
            }, matcher.start(1), matcher.end(1), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static long parseStamp(String value) {
        try {
            String[] parts = value.split(":");
            if (parts.length != 3) return -1;
            return ((Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1])) * 60 + Long.parseLong(parts[2])) * 1000;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private View row(int position) {
        final Segment s = segments.get(position);
        boolean colored = settings.speakerColors();
        int accent = colored && s.speaker >= 0 ? Palette.speaker(s.speaker) : Palette.MUTED;
        boolean current = position == currentSegment;

        FrameLayout wrapper = new FrameLayout(ctx);
        wrapper.setPadding(0, dp(2), 0, dp(2));

        LinearLayout row = new LinearLayout(ctx);
        row.setPadding(dp(10), dp(10), dp(4), dp(10));
        row.setBackground(Ui.ripple(current
                ? Ui.box(ctx, Palette.alpha(Palette.ACCENT, 0x2E), 14, Palette.alpha(Palette.ACCENT, 0x80))
                : Ui.box(ctx, 0, 14)));
        row.setOnClickListener(v -> playFrom(s.startMs));
        row.setOnLongClickListener(v -> {
            editSegment(s);
            return true;
        });

        View bar = new View(ctx);
        bar.setBackground(Ui.box(ctx, accent, 2));
        row.addView(bar, new LinearLayout.LayoutParams(dp(3), -1));

        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(ctx);
        head.setGravity(Gravity.CENTER_VERTICAL);
        if (s.speaker >= 0) {
            head.addView(Ui.bold(ctx, recording.speakerName(s.speaker), 13, colored ? accent : Palette.TEXT));
        }
        TextView stamp = Ui.mono(ctx, "▶ " + Fmt.stamp(s.startMs), 12, Palette.ACCENT_SOFT);
        stamp.setPadding(dp(8), dp(5), dp(8), dp(5));
        stamp.setBackground(Ui.ripple(Ui.box(ctx, Palette.alpha(Palette.ACCENT, 0x2E), 10)));
        stamp.setContentDescription("Перейти к " + Fmt.stamp(s.startMs));
        stamp.setOnClickListener(v -> playFrom(s.startMs));
        head.addView(stamp, Ui.margins(Ui.lp(-2, -2), ctx, s.speaker >= 0 ? 8 : 0, 0, 0, 0));
        column.addView(head);
        TextView text = Ui.text(ctx, NoteBuilder.text(s, settings.cleanText()), 15, Palette.TEXT);
        text.setLineSpacing(dp(2), 1f);
        column.addView(text, Ui.margins(Ui.lp(-1, -2), ctx, 0, 5, 0, 0));
        row.addView(column, Ui.margins(Ui.lp(0, -2, 1f), ctx, 12, 0, 4, 0));

        row.addView(Ui.iconButton(ctx, R.drawable.ic_edit, Palette.MUTED, 0, 36, 18, "Править реплику",
                v -> editSegment(s)));
        wrapper.addView(row, new FrameLayout.LayoutParams(-1, -2));
        return wrapper;
    }

    private final class SegmentAdapter extends BaseAdapter {
        @Override public int getCount() { return segments.size(); }
        @Override public Object getItem(int position) { return segments.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            return row(position);
        }
    }

    // ---- плеер --------------------------------------------------------------------------------

    private void preparePlayer() {
        if (playerTried || recording == null) return;
        playerTried = true;
        File file = new File(recording.audioPath);
        if (!file.isFile()) return;
        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setDataSource(recording.audioPath);
            mp.setOnPreparedListener(p -> {
                prepared = true;
                seek.setMax(Math.max(1, p.getDuration()));
                playerBar.setVisibility(View.VISIBLE);
                updateTime(0);
                if (pendingSeekMs >= 0) {
                    seekPlayer(pendingSeekMs);
                    pendingSeekMs = -1;
                }
                if (pendingPlay) {
                    pendingPlay = false;
                    startPlayer();
                }
            });
            mp.setOnCompletionListener(p -> {
                playing = false;
                Ui.setButtonIcon(playButton, R.drawable.ic_play);
                seek.setProgress(seek.getMax());
            });
            mp.setOnErrorListener((p, what, extra) -> {
                playing = false;
                prepared = false;
                playerBar.setVisibility(View.GONE);
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            releasePlayer();
        }
    }

    private void togglePlay() {
        if (player == null || !prepared) return;
        if (playing) pausePlayer();
        else startPlayer();
    }

    private void startPlayer() {
        if (player == null || !prepared) return;
        try {
            player.start();
            playing = true;
            applySpeed();
            Ui.setButtonIcon(playButton, R.drawable.ic_pause);
            ui.removeCallbacks(playTick);
            ui.post(playTick);
        } catch (IllegalStateException ignored) {
            playing = false;
        }
    }

    void pausePlayer() {
        if (player == null || !prepared || !playing) return;
        try {
            player.pause();
        } catch (IllegalStateException ignored) {
        }
        playing = false;
        Ui.setButtonIcon(playButton, R.drawable.ic_play);
        ui.removeCallbacks(playTick);
    }

    private void seekPlayer(long ms) {
        if (player == null || !prepared) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) player.seekTo(ms, MediaPlayer.SEEK_CLOSEST);
            else player.seekTo((int) ms);
        } catch (IllegalStateException ignored) {
        }
        seek.setProgress((int) ms);
        updateTime(ms);
    }

    /** Переходит к таймкоду и запускает воспроизведение. */
    void playFrom(long ms) {
        if (player == null) {
            host.toast("Аудиофайл недоступен");
            return;
        }
        highlight(ms);
        if (!prepared) {
            pendingSeekMs = ms;
            pendingPlay = true;
            return;
        }
        seekPlayer(ms);
        startPlayer();
    }

    /** Показывает место в записи без запуска звука: переход из списка задач. */
    private void focus(long ms) {
        showTabProgrammatically();
        int index = segmentAt(ms);
        currentSegment = index;
        adapter.notifyDataSetChanged();
        if (index >= 0) transcriptList.setSelectionFromTop(index, dp(60));
        if (player != null) {
            if (prepared) seekPlayer(ms);
            else pendingSeekMs = ms;
        }
    }

    private void showTabProgrammatically() {
        if (tab == TAB_TRANSCRIPT) return;
        tabs.select(TAB_TRANSCRIPT);
        showTab(TAB_TRANSCRIPT);
    }

    private void cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.length;
        float speed = SPEEDS[speedIndex];
        speedText.setText((speed == (int) speed ? String.valueOf((int) speed) : String.valueOf(speed)) + "×");
        if (playing) applySpeed();
    }

    private void applySpeed() {
        if (player == null) return;
        try {
            PlaybackParams params = player.getPlaybackParams();
            params.setSpeed(SPEEDS[speedIndex]);
            player.setPlaybackParams(params);
        } catch (RuntimeException ignored) {
            // Часть устройств не умеет менять скорость: остаётся обычная.
        }
    }

    private void updateTime(long position) {
        long total = prepared && player != null ? seek.getMax() : recording == null ? 0 : recording.durationMs;
        timeText.setText(Fmt.clock(position) + " / " + Fmt.clock(total));
    }

    private int segmentAt(long ms) {
        int found = -1;
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).startMs <= ms) found = i;
            else break;
        }
        return found;
    }

    /** Подсвечивает реплику, которая звучит сейчас, и подводит к ней список, если его не листают вручную. */
    private void highlight(long position) {
        int index = segmentAt(position);
        if (index == currentSegment) return;
        currentSegment = index;
        adapter.notifyDataSetChanged();
        boolean idle = SystemClock.uptimeMillis() - lastTouch > 4000;
        if (index >= 0 && playing && idle && tab == TAB_TRANSCRIPT) {
            transcriptList.smoothScrollToPositionFromTop(index, dp(60));
        }
    }

    // ---- действия -----------------------------------------------------------------------------

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(ctx, anchor);
        menu.getMenu().add(0, 1, 0, "Переименовать");
        if (!NoteBuilder.speakersInOrder(segments).isEmpty()) menu.getMenu().add(0, 2, 1, "Имена говорящих");
        if (!segments.isEmpty()) {
            menu.getMenu().add(0, 3, 2, "Сохранить PDF");
            menu.getMenu().add(0, 4, 3, "Сохранить markdown");
            menu.getMenu().add(0, 5, 4, "Копировать конспект");
            menu.getMenu().add(0, 8, 5, "Сохранить JSON");
        }
        if (recording != null && !Status.isBusy(recording.status)) menu.getMenu().add(0, 6, 5, "Расшифровать заново");
        menu.getMenu().add(0, 7, 6, "Удалить");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: rename(); return true;
                case 2: editSpeakerNames(); return true;
                case 3: host.exportPdf(id); return true;
                case 4: host.exportMarkdown(id); return true;
                case 5: copyNote(); return true;
                case 6: retranscribe(); return true;
                case 7: host.confirmDelete(recording); return true;
                case 8: host.exportJson(id); return true;
                default: return false;
            }
        });
        menu.show();
    }

    private void rename() {
        if (recording == null) return;
        host.prompt("Название записи", recording.title, "Название", value -> host.rename(id, value));
    }

    private void retranscribe() {
        if (segments.isEmpty()) {
            host.transcribe(id);
            return;
        }
        Ui.dialog(ctx).setTitle("Расшифровать заново?")
                .setMessage("Правки в репликах и имена говорящих в тексте будут заменены новой расшифровкой.")
                .setPositiveButton("Расшифровать", (d, w) -> host.transcribe(id))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void toggleDenoise() {
        boolean on = !settings.denoise();
        settings.setDenoise(on);
        Ui.styleToggleChip(ctx, denoiseChip, on);
        if (recording == null || segments.isEmpty() || Status.isBusy(recording.status)) return;
        Ui.dialog(ctx).setTitle(on ? "Включить шумоподавление?" : "Выключить шумоподавление?")
                .setMessage("Шумоподавление работает со звуком до расшифровки. Чтобы оно применилось к этой записи, "
                        + "нужно расшифровать её заново: правки в репликах и имена говорящих будут заменены.")
                .setPositiveButton("Расшифровать заново", (d, w) -> host.transcribe(id))
                .setNegativeButton("Позже", null)
                .show();
    }

    private void copyNote() {
        String markdown = NoteBuilder.markdown(recording, segments, tasks, settings.cleanText());
        ClipboardManager clipboard = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(recording.title, markdown));
            host.toast("Конспект скопирован");
        }
    }

    private EditText field(String text, String hint, boolean multiline) {
        EditText edit = new EditText(ctx);
        edit.setText(text);
        edit.setHint(hint);
        edit.setTextColor(Palette.TEXT);
        edit.setHintTextColor(Palette.MUTED);
        edit.setTextSize(15);
        edit.setBackground(Ui.box(ctx, Palette.SURFACE_2, 12, Palette.BORDER));
        edit.setPadding(dp(12), dp(10), dp(12), dp(10));
        if (multiline) {
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            edit.setMinLines(4);
            edit.setGravity(Gravity.TOP | Gravity.START);
        } else {
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            edit.setSingleLine(true);
        }
        edit.setSelection(edit.getText().length());
        return edit;
    }

    private void editSpeakerNames() {
        List<Integer> speakers = NoteBuilder.speakersInOrder(segments);
        if (speakers.isEmpty()) return;
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final List<EditText> fields = new ArrayList<>();
        for (int speaker : speakers) {
            String current = recording.speakerName(speaker);
            String fallback = "Спикер " + (speaker + 1);
            EditText edit = field(current.equals(fallback) ? "" : current, fallback, false);
            fields.add(edit);
            box.addView(edit, Ui.margins(Ui.lp(-1, -2), ctx, 0, 6, 0, 6));
        }
        Ui.dialog(ctx).setTitle("Имена говорящих").setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    for (int i = 0; i < speakers.size(); i++) {
                        db.setSpeakerName(id, speakers.get(i), fields.get(i).getText().toString());
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void editSegment(Segment segment) {
        final int[] speaker = {segment.speaker};
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        List<Integer> speakers = NoteBuilder.speakersInOrder(segments);
        if (!speakers.isEmpty()) {
            box.addView(Ui.text(ctx, "Говорящий", 12, Palette.MUTED), Ui.margins(Ui.lp(-2, -2), ctx, 0, 0, 0, 8));
            HorizontalScrollView scroller = new HorizontalScrollView(ctx);
            scroller.setHorizontalScrollBarEnabled(false);
            LinearLayout chips = new LinearLayout(ctx);
            scroller.addView(chips);
            final List<TextView> views = new ArrayList<>();
            final List<Integer> values = new ArrayList<>();
            int next = speakers.get(speakers.size() - 1) + 1;
            for (int s : speakers) {
                values.add(s);
            }
            values.add(next);
            values.add(-1);
            for (int i = 0; i < values.size(); i++) {
                final int value = values.get(i);
                String label = value == next ? "Новый" : value < 0 ? "Без метки" : recording.speakerName(value);
                TextView chip = Ui.toggleChip(ctx, label, value == speaker[0], null);
                chip.setOnClickListener(v -> {
                    speaker[0] = value;
                    for (int k = 0; k < views.size(); k++) {
                        Ui.styleToggleChip(ctx, views.get(k), values.get(k) == speaker[0]);
                    }
                });
                views.add(chip);
                chips.addView(chip, Ui.margins(Ui.lp(-2, -2), ctx, 0, 0, 8, 0));
            }
            box.addView(scroller, Ui.margins(Ui.lp(-1, -2), ctx, 0, 0, 0, 14));
        }

        box.addView(Ui.text(ctx, "Текст", 12, Palette.MUTED), Ui.margins(Ui.lp(-2, -2), ctx, 0, 0, 0, 8));
        final EditText edit = field(segment.text, "Текст реплики", true);
        box.addView(edit, Ui.lp(-1, -2));

        Ui.dialog(ctx).setTitle(Fmt.stamp(segment.startMs)).setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String value = edit.getText().toString().trim();
                    if (value.isEmpty()) {
                        host.toast("Текст реплики не может быть пустым");
                        return;
                    }
                    host.updateSegment(id, segment.idx, speaker[0], value);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // ---- жизненный цикл -----------------------------------------------------------------------

    private void releasePlayer() {
        ui.removeCallbacks(playTick);
        if (player != null) {
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        prepared = false;
        playing = false;
    }

    @Override void release() {
        releasePlayer();
        super.release();
    }
}
