package com.example;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class NoteBuilder {
    private NoteBuilder() {}

    static String text(Segment segment, boolean clean) {
        return clean ? TextCleaner.clean(segment.text) : segment.text;
    }

    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("*", "\\*").replace("`", "\\`");
    }

    static String markdown(Recording recording, List<Segment> segments, List<Task> tasks, boolean clean) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(esc(recording.title)).append("\n\n");

        StringBuilder meta = new StringBuilder(Fmt.date(recording.createdAt));
        if (recording.durationMs > 0) meta.append(" · ").append(Fmt.duration(recording.durationMs));
        md.append('*').append(meta).append("*\n\n");

        List<Integer> speakers = speakersInOrder(segments);
        if (!speakers.isEmpty()) {
            md.append("**Участники:** ");
            for (int i = 0; i < speakers.size(); i++) {
                if (i > 0) md.append(", ");
                md.append(esc(recording.speakerName(speakers.get(i))));
            }
            md.append("\n\n");
        }

        md.append("## Расшифровка\n\n");
        for (Segment s : segments) {
            md.append("**[").append(Fmt.stamp(s.startMs)).append(']');
            if (s.speaker >= 0) md.append(' ').append(esc(recording.speakerName(s.speaker))).append(':');
            md.append("** ").append(esc(text(s, clean))).append("\n\n");
        }

        String summary = summary(segments, clean);
        if (!summary.isEmpty()) {
            md.append("## Краткое содержание\n\n").append(esc(summary)).append("\n\n");
        }

        if (!tasks.isEmpty()) {
            md.append("## Задачи\n\n");
            for (Task t : tasks) {
                md.append(t.done ? "- [x] " : "- [ ] ").append(esc(t.text));
                StringBuilder tail = new StringBuilder();
                if (t.speaker >= 0) tail.append(esc(recording.speakerName(t.speaker)));
                if (tail.length() > 0) tail.append(" · ");
                tail.append(Fmt.stamp(t.startMs));
                if (!t.deadline.isEmpty()) tail.append(" · срок: ").append(t.deadline);
                md.append(" — ").append(tail).append('\n');
            }
            md.append('\n');
        }
        return md.toString().trim() + "\n";
    }

    static String json(List<Segment> segments) throws org.json.JSONException {
        org.json.JSONArray array = new org.json.JSONArray();
        for (Segment s : segments) {
            org.json.JSONObject item = new org.json.JSONObject();
            item.put("speaker_id", s.speaker >= 0 ? "speaker_" + (s.speaker + 1) : "unknown");
            item.put("start_ms", s.startMs);
            item.put("end_ms", s.endMs);
            item.put("text", text(s, clean));
            array.put(item);
        }
        org.json.JSONObject root = new org.json.JSONObject();
        root.put("transcript", array);
        return root.toString(2).replace("\\/", "/") + "\n";
    }

    static List<Integer> speakersInOrder(List<Segment> segments) {
        Set<Integer> seen = new LinkedHashSet<>();
        for (Segment s : segments) if (s.speaker >= 0) seen.add(s.speaker);
        return new ArrayList<>(seen);
    }

    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "этот", "этого", "этом", "этой", "этим", "эти", "этих", "этому", "такой", "такая", "такое", "такие",
            "который", "которая", "которое", "которые", "которых", "которого", "которой", "котором",
            "когда", "потому", "почему", "чтобы", "потом", "тогда", "если", "только", "очень", "просто", "вообще",
            "может", "можно", "нужно", "надо", "есть", "быть", "было", "были", "будет", "будут", "была", "буду",
            "себя", "тоже", "ещё", "уже", "даже", "здесь", "там", "тут", "сейчас", "сегодня", "всё", "все",
            "всего", "всех", "этом", "какой", "какая", "какие", "каких", "значит", "типа", "вроде", "конечно",
            "наверное", "кстати", "короче", "ладно", "хорошо", "давай", "давайте", "смотри", "смотрите",
            "говорю", "говорит", "сказал", "сказала", "скажем", "понял", "поняла", "понятно", "знаешь", "знаете",
            "один", "одна", "одно", "два", "три", "свой", "своё", "свои", "мой", "моя", "мои", "твой", "наш",
            "наши", "ваш", "ваши", "они", "него", "неё", "него", "нами", "вами", "ними", "тебя", "меня",
            "нас", "вас", "чего", "кого", "кому", "чему", "через", "после", "перед", "между", "более",
            "that", "this", "with", "have", "from", "they", "will", "would", "there", "their", "what", "when"));

    static String summary(List<Segment> segments, boolean clean) {
        List<String> sentences = new ArrayList<>();
        for (Segment s : segments) {
            for (String part : splitSentences(text(s, clean))) {
                int words = wordCount(part);
                if (words >= 5 && words <= 60) sentences.add(part.trim());
            }
        }
        if (sentences.size() < 4) return "";

        Map<String, Integer> frequency = new HashMap<>();
        List<List<String>> keys = new ArrayList<>();
        for (String sentence : sentences) {
            List<String> k = contentKeys(sentence);
            keys.add(k);
            for (String key : new HashSet<>(k)) {
                Integer old = frequency.get(key);
                frequency.put(key, old == null ? 1 : old + 1);
            }
        }

        final double[] scores = new double[sentences.size()];
        Integer[] order = new Integer[sentences.size()];
        for (int i = 0; i < sentences.size(); i++) {
            double sum = 0;
            for (String key : keys.get(i)) {
                Integer f = frequency.get(key);
                if (f != null && f > 1) sum += f;
            }
            scores[i] = keys.get(i).isEmpty() ? 0 : sum / Math.sqrt(keys.get(i).size());
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Double.compare(scores[b], scores[a]));

        int pick = Math.min(5, Math.max(3, sentences.size() / 10));
        List<Integer> chosen = new ArrayList<>();
        for (int i = 0; i < order.length && chosen.size() < pick; i++) {
            if (scores[order[i]] > 0) chosen.add(order[i]);
        }
        if (chosen.isEmpty()) return "";
        Collections.sort(chosen);
        StringBuilder out = new StringBuilder();
        for (int index : chosen) {
            if (out.length() > 0) out.append(' ');
            out.append(sentences.get(index));
        }
        return out.toString();
    }

    private static List<String> contentKeys(String sentence) {
        List<String> keys = new ArrayList<>();
        for (String raw : sentence.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (raw.length() < 4 || STOP.contains(raw)) continue;
            keys.add(raw.length() > 5 ? raw.substring(0, 5) : raw);
        }
        return keys;
    }

    private static int wordCount(String sentence) {
        int count = 0;
        for (String w : sentence.trim().split("\\s+")) if (!w.isEmpty()) count++;
        return count;
    }

    private static List<String> splitSentences(String text) {
        List<String> result = new ArrayList<>();
        if (text == null) return result;
        for (String part : text.trim().split("(?<=[.!?…])\\s+")) {
            if (!part.trim().isEmpty()) result.add(part.trim());
        }
        return result;
    }

    private static final String B = "(?<![\\p{L}\\p{N}])";
    private static final String E = "(?![\\p{L}\\p{N}])";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern TASK = Pattern.compile(B + "(?:" +
            "нужно|надо|необходимо|должен|должна|должны|следует|придётся|придется|обязательно|" +
            "не\\s+забуд(?:ь|ьте)|напомни(?:те)?|договорились|задач[аиуе]|дедлайн|срок|" +
            "сделаю|сделаем|подготовлю|подготовим|отправлю|отправим|напишу|напишем|позвоню|позвоним|" +
            "проверю|проверим|пришлю|пришлём|пришлем|скину|скинем|закончу|закончим|займусь|займёмся|займемся|" +
            "организую|организуем|согласую|согласуем|уточню|уточним|выясню|выясним|договорюсь|договоримся|" +
            "созвонимся|встретимся|оформлю|оформим|исправлю|исправим|доделаю|доделаем|запланирую|запланируем|" +
            "назначу|назначим|отвечу|ответим|куплю|купим|закажу|закажем|заберу|заберём|заберем|" +
            "подпишу|подпишем|посмотрю|посмотрим|разберусь|разберёмся|разберемся|обсудим|решим|составлю|составим" +
            ")" + E, FLAGS);

    private static final Pattern REQUEST = Pattern.compile(B + "давай(?:те)?" + E, FLAGS);

    private static final String DAYS = "понедельник[а]?|вторник[а]?|сред[ыу]|четверг[а]?|пятниц[ыу]|суббот[ыу]|воскресень[яе]";
    private static final String MONTHS = "января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря";
    private static final Pattern DEADLINE = Pattern.compile(B + "(?:" +
            "до\\s+(?:конца\\s+(?:дня|недели|месяца|года)|(?:" + DAYS + ")|завтра|послезавтра|\\d{1,2}(?:[.]\\d{1,2}(?:[.]\\d{2,4})?|\\s+(?:" + MONTHS + ")))" +
            "|(?:завтра|послезавтра|на\\s+следующей\\s+неделе|в\\s+(?:понедельник|вторник|среду|четверг|пятницу|субботу|воскресенье))" +
            ")" + E, FLAGS);

    static List<Task> tasks(List<Segment> segments) {
        List<Task> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            for (String sentence : splitSentences(segment.text)) {
                int words = wordCount(sentence);
                if (words < 3 || words > 60) continue;
                boolean question = sentence.endsWith("?");
                boolean matches = REQUEST.matcher(sentence).find()
                        || (!question && TASK.matcher(sentence).find());
                if (!matches) continue;
                if (!seen.add(sentence.toLowerCase(Locale.ROOT))) continue;
                Matcher d = DEADLINE.matcher(sentence);
                String deadline = d.find() ? d.group().trim() : "";
                result.add(Task.found(i, segment.startMs, segment.speaker, sentence, deadline));
                if (result.size() >= 30) return result;
            }
        }
        return result;
    }
}
