package com.example;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import android.graphics.drawable.Drawable;
import android.text.InputType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Список всех записей с поиском и фильтром по готовности. */
final class LibraryScreen extends BaseScreen {
    private static final int FILTER_ALL = 0;
    private static final int FILTER_DONE = 1;
    private static final int FILTER_PENDING = 2;

    private final LinearLayout root;
    private final ListView list;
    private final TextView empty;
    private final EditText search;
    private final TextView counter;
    private final Adapter adapter = new Adapter();

    private List<Recording> all = new ArrayList<>();
    private final List<Recording> visible = new ArrayList<>();
    private Map<Long, int[]> stats = new java.util.HashMap<>();
    private int filter = FILTER_ALL;

    LibraryScreen(MainActivity host) {
        super(host);
        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(16), dp(20), dp(8));

        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(Ui.bold(ctx, "Записи", 28, Palette.TEXT), Ui.lp(0, -2, 1f));
        counter = Ui.text(ctx, "", 14, Palette.MUTED);
        titleRow.addView(counter);
        header.addView(titleRow);

        search = new EditText(ctx);
        search.setHint("Поиск по названию и тексту");
        search.setHintTextColor(Palette.MUTED);
        search.setTextColor(Palette.TEXT);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setBackground(Ui.box(ctx, Palette.SURFACE, 14, Palette.BORDER));
        search.setPadding(dp(14), dp(12), dp(14), dp(12));
        Drawable glass = ctx.getDrawable(R.drawable.ic_search).mutate();
        glass.setTint(Palette.MUTED);
        glass.setBounds(0, 0, dp(20), dp(20));
        search.setCompoundDrawablesRelative(glass, null, null, null);
        search.setCompoundDrawablePadding(dp(10));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { applyFilter(); }
        });
        search.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                InputMethodManager imm = (InputMethodManager) ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                v.clearFocus();
                return true;
            }
            return false;
        });
        header.addView(search, Ui.margins(Ui.lp(-1, -2), ctx, 0, 14, 0, 0));

        Ui.Segmented filters = new Ui.Segmented(ctx, new String[]{"Все", "Готово", "Не готово"}, filter, index -> {
            filter = index;
            applyFilter();
        });
        header.addView(filters, Ui.margins(Ui.lp(-1, -2), ctx, 0, 12, 0, 0));
        root.addView(header);

        FrameLayout body = new FrameLayout(ctx);
        list = new ListView(ctx);
        Ui.styleList(list);
        list.setPadding(dp(12), dp(4), dp(12), dp(16));
        list.setAdapter(adapter);
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));
        empty = Ui.text(ctx, "", 15, Palette.MUTED);
        empty.setGravity(Gravity.CENTER);
        body.addView(empty, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(body, Ui.lp(-1, 0, 1f));
    }

    @Override View view() {
        return root;
    }

    @Override void refresh() {
        all = db.all();
        stats = db.stats();
        applyFilter();
    }

    private void applyFilter() {
        String query = search.getText().toString().trim().toLowerCase(Locale.getDefault());
        visible.clear();
        for (Recording r : all) {
            boolean done = Status.isFinished(r.status);
            if (filter == FILTER_DONE && !done) continue;
            if (filter == FILTER_PENDING && done) continue;
            if (!query.isEmpty()
                    && !r.title.toLowerCase(Locale.getDefault()).contains(query)
                    && !r.transcript.toLowerCase(Locale.getDefault()).contains(query)) continue;
            visible.add(r);
        }
        adapter.notifyDataSetChanged();
        counter.setText(all.isEmpty() ? "" : String.valueOf(all.size()));
        if (visible.isEmpty()) {
            empty.setText(all.isEmpty() ? "Записей пока нет" : "Ничего не найдено");
            empty.setVisibility(View.VISIBLE);
        } else {
            empty.setVisibility(View.GONE);
        }
    }

    private View card(Recording r) {
        FrameLayout wrapper = new FrameLayout(ctx);
        wrapper.setPadding(0, dp(4), 0, dp(4));

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(Ui.ripple(Ui.box(ctx, Palette.SURFACE, 16, Palette.BORDER)));
        card.setClickable(true);
        card.setOnClickListener(v -> host.openRecording(r.id));
        card.setOnLongClickListener(v -> {
            host.confirmDelete(r);
            return true;
        });

        TextView title = Ui.bold(ctx, r.title, 16, Palette.TEXT);
        Ui.ellipsized(title, 2);
        card.addView(title);

        StringBuilder meta = new StringBuilder(Fmt.shortDate(r.createdAt));
        if (r.durationMs > 0) meta.append(" · ").append(Fmt.duration(r.durationMs));
        card.addView(Ui.text(ctx, meta, 12, Palette.MUTED), Ui.margins(Ui.lp(-2, -2), ctx, 0, 6, 0, 0));

        String preview = r.transcript.replace('\n', ' ').trim();
        if (!preview.isEmpty() && Status.isFinished(r.status)) {
            if (preview.length() > 160) preview = preview.substring(0, 160);
            TextView p = Ui.text(ctx, preview, 13, Palette.alpha(Palette.TEXT, 0xB8));
            Ui.ellipsized(p, 2);
            p.setLineSpacing(dp(2), 1f);
            card.addView(p, Ui.margins(Ui.lp(-1, -2), ctx, 0, 10, 0, 0));
        }

        if (Status.isBusy(r.status)) {
            String label = TranscriptionService.progressLabel(r.id);
            if (label.isEmpty()) label = Status.label(r.status);
            card.addView(Ui.text(ctx, label, 12, Palette.ACCENT_SOFT), Ui.margins(Ui.lp(-2, -2), ctx, 0, 12, 0, 6));
            Ui.ProgressLine line = new Ui.ProgressLine(ctx);
            line.set(TranscriptionService.progressPercent(r.id));
            card.addView(line, new LinearLayout.LayoutParams(-1, dp(4)));
        } else {
            LinearLayout pills = new LinearLayout(ctx);
            pills.setGravity(Gravity.CENTER_VERTICAL);
            int color = Status.color(r.status);
            pills.addView(Ui.pill(ctx, Status.label(r.status), color));
            int[] s = stats.get(r.id);
            if (s != null && s[0] > 0) {
                pills.addView(Ui.pill(ctx, s[0] + " " + Fmt.plural(s[0], "говорящий", "говорящих", "говорящих"),
                        Palette.speaker(0)), Ui.margins(Ui.lp(-2, -2), ctx, 6, 0, 0, 0));
            }
            if (s != null && s[2] > 0) {
                pills.addView(Ui.pill(ctx, s[2] + " " + Fmt.plural(s[2], "задача", "задачи", "задач"),
                        Palette.ORANGE), Ui.margins(Ui.lp(-2, -2), ctx, 6, 0, 0, 0));
            }
            card.addView(pills, Ui.margins(Ui.lp(-2, -2), ctx, 0, 12, 0, 0));
        }
        wrapper.addView(card, new FrameLayout.LayoutParams(-1, -2));
        return wrapper;
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return visible.get(position).id; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            return card(visible.get(position));
        }
    }
}
