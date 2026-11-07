package com.example;

import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Задачи, найденные в расшифровках по ключевым словам: отметка выполнения и переход к месту в записи. */
final class TasksScreen extends BaseScreen {
    private final LinearLayout root;
    private final ListView list;
    private final TextView empty;
    private final TextView counter;
    private final Adapter adapter = new Adapter();

    private final List<Task> visible = new ArrayList<>();
    private final Map<Long, Recording> recordings = new HashMap<>();
    private List<Task> all = new ArrayList<>();
    private boolean onlyOpen = true;

    TasksScreen(MainActivity host) {
        super(host);
        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(16), dp(20), dp(8));
        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(Ui.bold(ctx, "Задачи", 28, Palette.TEXT), Ui.lp(0, -2, 1f));
        counter = Ui.text(ctx, "", 14, Palette.MUTED);
        titleRow.addView(counter);
        header.addView(titleRow);

        Ui.Segmented filter = new Ui.Segmented(ctx, new String[]{"Открытые", "Все"}, 0, index -> {
            onlyOpen = index == 0;
            applyFilter();
        });
        header.addView(filter, Ui.margins(Ui.lp(-1, -2), ctx, 0, 14, 0, 0));
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
        all = db.allTasks();
        recordings.clear();
        for (Recording r : db.all()) recordings.put(r.id, r);
        applyFilter();
    }

    private void applyFilter() {
        visible.clear();
        int open = 0;
        for (Task t : all) {
            if (!t.done) open++;
            if (onlyOpen && t.done) continue;
            visible.add(t);
        }
        adapter.notifyDataSetChanged();
        counter.setText(open == 0 ? "" : "Открыто: " + open);
        if (visible.isEmpty()) {
            empty.setText(all.isEmpty() ? "Задач пока нет" : "Открытых задач нет");
            empty.setVisibility(View.VISIBLE);
        } else {
            empty.setVisibility(View.GONE);
        }
    }

    private View row(Task t) {
        FrameLayout wrapper = new FrameLayout(ctx);
        wrapper.setPadding(0, dp(4), 0, dp(4));

        LinearLayout card = new LinearLayout(ctx);
        card.setPadding(dp(12), dp(12), dp(16), dp(12));
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Ui.ripple(Ui.box(ctx, Palette.SURFACE, 16, Palette.BORDER)));
        card.setClickable(true);
        card.setOnClickListener(v -> host.openRecording(t.recordingId, t.startMs));

        // Отметка выполнения: отдельная зона нажатия, чтобы не промахиваться по карточке.
        FrameLayout check = new FrameLayout(ctx);
        android.graphics.drawable.GradientDrawable circle = Ui.oval(t.done ? Palette.GREEN : 0);
        if (!t.done) circle.setStroke(dp(2), Palette.MUTED);
        check.setBackground(circle);
        if (t.done) {
            check.addView(Ui.icon(ctx, R.drawable.ic_check, 0xFFFFFFFF, 16),
                    new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
        }
        FrameLayout hit = new FrameLayout(ctx);
        hit.setClickable(true);
        hit.setContentDescription(t.done ? "Вернуть в открытые" : "Отметить выполненной");
        hit.setOnClickListener(v -> db.setTaskDone(t.id, t.recordingId, !t.done));
        hit.addView(check, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        LinearLayout.LayoutParams hitParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        hitParams.setMargins(0, 0, dp(4), 0);
        card.addView(hit, hitParams);

        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(ctx, t.text, 15, t.done ? Palette.MUTED : Palette.TEXT);
        title.setLineSpacing(dp(2), 1f);
        if (t.done) title.setPaintFlags(title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        text.addView(title);

        Recording r = recordings.get(t.recordingId);
        StringBuilder meta = new StringBuilder();
        if (r != null && t.speaker >= 0) meta.append(r.speakerName(t.speaker)).append(" · ");
        meta.append(Fmt.stamp(t.startMs)).append(" · ").append(t.recordingTitle);
        TextView metaView = Ui.text(ctx, meta, 12, Palette.MUTED);
        Ui.ellipsized(metaView, 1);
        text.addView(metaView, Ui.margins(Ui.lp(-1, -2), ctx, 0, 6, 0, 0));
        if (!t.deadline.isEmpty() && !t.done) {
            text.addView(Ui.pill(ctx, "Срок: " + t.deadline, Palette.ORANGE),
                    Ui.margins(Ui.lp(-2, -2), ctx, 0, 8, 0, 0));
        }
        card.addView(text, Ui.lp(0, -2, 1f));

        wrapper.addView(card, new FrameLayout.LayoutParams(-1, -2));
        return wrapper;
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return visible.get(position).id; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            return row(visible.get(position));
        }
    }
}
