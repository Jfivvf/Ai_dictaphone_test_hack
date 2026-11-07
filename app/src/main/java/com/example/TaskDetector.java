package com.example;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Поиск задач в расшифровке по правилам, без нейросети.
 *
 * Одного слова «надо» или «посмотрим» мало: в лекции и обсуждении так говорят постоянно
 * («надо подумать над этим», «сейчас проверим другие стороны вопроса»). Поэтому каждое предложение,
 * а длинное — каждая его часть, оценивается баллами. За признак поручения баллы начисляются
 * (обязательство, обещание, конкретное действие над конкретным предметом, срок, исполнитель),
 * за признак рассуждения снимаются (мыслительные глаголы, вводные слова, условность, вопрос).
 * Задачей считается фраза, набравшая не меньше {@link #THRESHOLD} баллов.
 */
final class TaskDetector {
    static final int THRESHOLD = 4;
    private static final int MAX_TASKS = 30;
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final String B = "(?<![\\p{L}\\p{N}])";
    private static final String E = "(?![\\p{L}\\p{N}])";

    /** Явное обязательство. */
    private static final Pattern OBLIGATION = Pattern.compile(B + "(?:нужно|надо|необходимо|должен|должна|должны|"
            + "следует|придётся|придется|обязательно|не\\s+забуд(?:ь|ьте)|напомни(?:те)?|договорились|"
            + "поручаю|поручено)" + E, FLAGS);
    private static final Pattern REQUEST = Pattern.compile(B + "давай(?:те)?" + E, FLAGS);
    /** «Не надо», «не нужно» — запрет, а не поручение. */
    private static final Pattern NEGATED = Pattern.compile(B + "не\\s+(?:надо|нужно|стоит|следует|необходимо|должен|должна|должны)" + E, FLAGS);
    /** «Надо было» — про прошлое. */
    private static final Pattern PAST_OBLIGATION = Pattern.compile(B + "(?:надо|нужно|необходимо)\\s+было" + E, FLAGS);

    /** Мыслительные и речевые глаголы: после «надо» это рассуждение, а не действие. */
    private static final Pattern MENTAL = Pattern.compile(B + "(?:подума|понима|поня[тв]|отмет|учитыва|учест|учт[еи]|"
            + "помни|запомн|замет|призна|сказа|упомян|расска|объясн|осозна|представ|предполож|различа|выдел|"
            + "вспомн|задума|задат[ьс]|рассмотр|иметь\\s+в\\s+виду|обрат(?:ить|им)\\s+внимани|отдава(?:ть|я)\\s+себе)"
            + "\\p{L}*", FLAGS);
    /** Слова-связки лекции и объяснения. */
    private static final Pattern EXPLAINING = Pattern.compile(B + "(?:например|допустим|предположим|то\\s+есть|"
            + "т\\.\\s?е\\.|значит|для\\s+этого|тогда|поэтому|как\\s+видим|как\\s+видите|таким\\s+образом)" + E, FLAGS);
    /** «Сейчас», «дальше»: ход разговора, если за ними нет настоящего обещания. */
    private static final Pattern FLOW = Pattern.compile(B + "(?:сейчас|теперь|дальше|далее|итак|смотрим|смотрите)" + E, FLAGS);
    private static final Pattern HYPOTHETICAL = Pattern.compile(B + "(?:бы|если)" + E, FLAGS);
    private static final Pattern GENERIC = Pattern.compile(B + "(?:всегда|обычно|как\\s+правило|в\\s+целом|вообще|иногда|"
            + "часто|любой|любое|любая|каждый|каждая|каждое|в\\s+принципе)" + E, FLAGS);

    /** Обещание, за которым стоит конкретное действие. */
    private static final String STRONG_STEMS = "сдел|подготов|отправ|напиш|позвон|пришл|скин|законч|займ|организ|согласов|"
            + "договор|созвон|оформ|исправ|довед|доделa|запланир|назнач|закаж|заб[еёи]р|подпиш|состав|принес|"
            + "купл|куп|сда[мдш]|загруз|выложу|выслать|перенес|переслa|заполн|оплач|оплат|забронир|зарегистр|обнов|"
            + "доработ|переделa|встрет|напомн|свяж|ответ|верн";
    /** Обещание, которое само по себе может быть пустым («посмотрим», «обсудим»). */
    private static final String WEAK_STEMS = "посмотр|обсуд|провер|реш|разбер|выясн|уточн|поговор";
    private static final Pattern STRONG_WORD = Pattern.compile("^(?:" + STRONG_STEMS + ")\\p{L}*$", FLAGS);
    private static final Pattern WEAK_WORD = Pattern.compile("^(?:" + WEAK_STEMS + ")\\p{L}*$", FLAGS);
    private static final String[] FUTURE_ENDINGS = {"ю", "у", "ем", "им", "ешь", "ишь", "ёшь", "ет", "ит", "ёт",
            "ете", "ите", "ёте", "ут", "ют", "ат", "ят"};

    /** Действие в начальной форме: «сделать», «отправить». */
    private static final Pattern ACTION_INFINITIVE = Pattern.compile(B + "(?:сдела|подготов|отправ|написа|позвони|купи|"
            + "оформи|закончи|сдат|принест|провери|исправ|довест|заказа|забра|подписа|составит|организова|согласова|"
            + "связа|встрети|назначи|запланирова|загрузи|перенест|выслат|переслат|прислат|скинут|созвони|заполни|"
            + "оплати|забронирова|зарегистрирова|обнови|доработа|переделат|отве|напомни|выложи|вернут|сделат)\\p{L}*", FLAGS);
    /** Предмет поручения: то, что обычно «сдают», «пишут» и «отправляют». */
    private static final Pattern DELIVERABLE = Pattern.compile(B + "(?:проект|отч[её]т|презентац|документ|письм|"
            + "домашн|задани|дз|курсов|лабораторн|реферат|доклад|договор|заявк|сч[её]т|резюме|встреч|звон|файл|"
            + "таблиц|статью|статья|сайт|макет|план|оплат|билет|заказ|задач|сообщени|ответ|протокол|расч[её]т|"
            + "тест|анкет|форм|справк|подпис)\\p{L}*", FLAGS);

    private static final String DAYS = "понедельник[а]?|вторник[а]?|сред[ыуе]|четверг[а]?|пятниц[ыуе]|суббот[ыуе]|воскресень[яюе]";
    private static final String DAYS_DATIVE = "понедельнику|вторнику|среде|четвергу|пятнице|субботе|воскресенью";
    private static final String MONTHS = "января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря";
    static final Pattern DEADLINE = Pattern.compile(B + "(?:"
            + "(?:до|к)\\s+(?:конца\\s+(?:дня|недели|месяца|года)|вечера|обеда|утра|" + DAYS + "|" + DAYS_DATIVE
            + "|завтра|послезавтра|\\d{1,2}(?:[.]\\d{1,2}(?:[.]\\d{2,4})?|\\s+(?:" + MONTHS + ")))"
            + "|(?:сегодня|завтра|послезавтра|на\\s+(?:этой|следующей)\\s+неделе|в\\s+следующем\\s+месяце|"
            + "в\\s+(?:понедельник|вторник|среду|четверг|пятницу|субботу|воскресенье))"
            + "|через\\s+(?:\\d+|пару|несколько)\\s+(?:дн|недел|час|минут|месяц)\\p{L}*"
            + ")" + E, FLAGS);

    private static final Pattern PRONOUN = Pattern.compile(B + "(?:я|мы|ты|вы|мне|нам|тебе|вам|меня|тебя|нас|вас)" + E, FLAGS);
    private static final Pattern CLAUSE_START = Pattern.compile("^(?:и\\s+|а\\s+|ещ[её]\\s+|потом\\s+|затем\\s+|также\\s+)?"
            + "(?:нужно|надо|необходимо|должен|должна|должны|следует|придётся|придется|обязательно|давай|давайте|не\\s+забуд)",
            FLAGS);

    private TaskDetector() {}

    static List<Task> detect(List<Segment> segments) {
        List<Task> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            for (String sentence : splitSentences(segment.text)) {
                for (String clause : splitClauses(sentence)) {
                    int words = wordCount(clause);
                    if (words < 3 || words > 45) continue;
                    if (score(clause) < THRESHOLD) continue;
                    String text = tidy(clause);
                    if (!seen.add(text.toLowerCase(Locale.ROOT))) continue;
                    Matcher d = DEADLINE.matcher(clause);
                    String deadline = d.find() ? d.group().trim() : "";
                    result.add(Task.found(i, segment.startMs, segment.speaker, text, deadline));
                    if (result.size() >= MAX_TASKS) return result;
                }
            }
        }
        return result;
    }

    /** Оценка фразы: чем выше, тем больше она похожа на поручение. Доступна для проверки в тестах. */
    static int score(String clause) {
        if (NEGATED.matcher(clause).find()) return -99;

        int score = 0;
        boolean obligation = OBLIGATION.matcher(clause).find();
        if (obligation) score += 2;
        if (REQUEST.matcher(clause).find()) score += 1;

        boolean strong = false;
        boolean weak = false;
        for (String word : clause.split("[^\\p{L}]+")) {
            if (word.length() < 4) continue;
            if (STRONG_WORD.matcher(word).matches() && looksFuture(word)) strong = true;
            else if (WEAK_WORD.matcher(word).matches() && looksFuture(word)) weak = true;
        }
        if (strong) score += 2;
        else if (weak) score += 1;

        boolean deliverable = DELIVERABLE.matcher(clause).find();
        if (ACTION_INFINITIVE.matcher(clause).find()) score += deliverable ? 2 : 1;
        else if (strong && deliverable) score += 1;

        if (DEADLINE.matcher(clause).find()) score += 2;
        if (PRONOUN.matcher(clause).find() || hasName(clause)) score += 1;

        if (MENTAL.matcher(clause).find()) score -= 3;
        if (EXPLAINING.matcher(clause).find()) score -= 3;
        if (!strong && FLOW.matcher(clause).find()) score -= 2;
        if (HYPOTHETICAL.matcher(clause).find()) score -= 2;
        if (GENERIC.matcher(clause).find()) score -= 2;
        if (PAST_OBLIGATION.matcher(clause).find()) score -= 3;
        if (clause.trim().endsWith("?")) score -= 3;
        return score;
    }

    /** Форма будущего времени или повелительная, но не прошедшее и не страдательное: «сделаю», а не «сделал». */
    private static boolean looksFuture(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        if (w.endsWith("ся") || w.endsWith("сь")) w = w.substring(0, w.length() - 2);
        for (String ending : FUTURE_ENDINGS) {
            if (w.endsWith(ending)) return true;
        }
        return false;
    }

    /** Имя в середине фразы: слово с заглавной буквы не в начале предложения. */
    private static boolean hasName(String clause) {
        String[] words = clause.trim().split("\\s+");
        for (int i = 1; i < words.length; i++) {
            String w = words[i];
            if (w.length() >= 3 && Character.isUpperCase(w.charAt(0)) && Character.isLowerCase(w.charAt(1))) return true;
        }
        return false;
    }

    /** Режет предложение на части там, где начинается новое обязательство: «нужно X, нужно Y». */
    static List<String> splitClauses(String sentence) {
        List<String> clauses = new ArrayList<>();
        String[] pieces = sentence.split("\\s*[,;]\\s*");
        StringBuilder current = new StringBuilder(pieces[0]);
        for (int i = 1; i < pieces.length; i++) {
            if (CLAUSE_START.matcher(pieces[i]).find()) {
                clauses.add(current.toString());
                current = new StringBuilder(pieces[i]);
            } else {
                current.append(", ").append(pieces[i]);
            }
        }
        clauses.add(current.toString());
        return clauses;
    }

    private static String tidy(String clause) {
        String text = clause.trim().replaceFirst("^(?:и|а|ещ[её]|потом|затем|также)\\s+", "");
        text = text.replaceAll("[,;:\\s]+$", "");
        if (!text.isEmpty()) text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
        return text;
    }

    private static int wordCount(String text) {
        int count = 0;
        for (String w : text.trim().split("\\s+")) if (!w.isEmpty()) count++;
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
}
