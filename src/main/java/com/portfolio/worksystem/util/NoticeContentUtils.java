package com.portfolio.worksystem.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

public final class NoticeContentUtils {
    private static final int MAX_NOTICE_BODY_LENGTH = 20_000;
    private static final Pattern ESCAPED_ALLOWED_OPEN_TAG = Pattern.compile("&lt;(p|strong|b|em|i|u|del|s|ul|ol|li|blockquote|h1|h2|h3|h4)&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern ESCAPED_ALLOWED_CLOSE_TAG = Pattern.compile("&lt;/(p|strong|b|em|i|u|del|s|ul|ol|li|blockquote|h1|h2|h3|h4|a)&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern ESCAPED_BR_TAG = Pattern.compile("&lt;br\\s*/?&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern ESCAPED_HR_TAG = Pattern.compile("&lt;hr\\s*/?&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern ESCAPED_IMG_TAG = Pattern.compile("&lt;img\\s+src=&quot;([^\"<>]{1,300}?)&quot;(?:\\s+alt=&quot;([^\"<>]{0,200}?)&quot;)?\\s*/?&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOTICE_IMAGE_SRC = Pattern.compile("^/notice-images/notice-[0-9a-fA-F-]{36}\\.(jpg|png|webp)$");
    private static final Pattern ESCAPED_ANCHOR_TAG = Pattern.compile("&lt;a\\s+href=&quot;([^\"<>]{1,600}?)&quot;\\s*&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern RENDERED_IMG_TAG = Pattern.compile("<img\\s", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern BLOCK_END_TAG = Pattern.compile("(?i)</(p|div|li|h3|h4|blockquote)>");
    private static final Pattern LINE_BREAK_TAG = Pattern.compile("(?i)<br\\s*/?>");

    private NoticeContentUtils() {
    }

    public static String sanitizeHtml(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String decoded = HtmlUtils.htmlUnescape(value);
        String normalized = decoded.length() > MAX_NOTICE_BODY_LENGTH
                ? decoded.substring(0, MAX_NOTICE_BODY_LENGTH)
                : decoded;
        String escaped = HtmlUtils.htmlEscape(normalized.trim().replace("\r\n", "\n").replace("\r", "\n"));
        escaped = escaped.replace("\n", "<br>");
        escaped = replaceSimpleTags(escaped, ESCAPED_BR_TAG, "br", false);
        escaped = replaceSimpleTags(escaped, ESCAPED_HR_TAG, "hr", false);
        escaped = replaceOpenTags(escaped);
        escaped = replaceCloseTags(escaped);
        escaped = replaceImages(escaped);
        return replaceAnchors(escaped);
    }

    public static String toPlainText(String html) {
        if (!StringUtils.hasText(html)) {
            return "";
        }
        String withLines = LINE_BREAK_TAG.matcher(html).replaceAll("\n");
        withLines = BLOCK_END_TAG.matcher(withLines).replaceAll("\n");
        withLines = withLines.replaceAll("(?i)<li>", "- ");
        String withoutTags = HTML_TAG.matcher(withLines).replaceAll(" ");
        return HtmlUtils.htmlUnescape(withoutTags)
                .replace('\u00a0', ' ')
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n\\s+", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    public static boolean hasContent(String sanitizedHtml) {
        if (!StringUtils.hasText(sanitizedHtml)) {
            return false;
        }
        if (StringUtils.hasText(toPlainText(sanitizedHtml))) {
            return true;
        }
        return RENDERED_IMG_TAG.matcher(sanitizedHtml).find();
    }

    private static String replaceOpenTags(String input) {
        Matcher matcher = ESCAPED_ALLOWED_OPEN_TAG.matcher(input);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String tag = canonicalTag(matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement("<" + tag + ">"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String replaceCloseTags(String input) {
        Matcher matcher = ESCAPED_ALLOWED_CLOSE_TAG.matcher(input);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String tag = canonicalTag(matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement("</" + tag + ">"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String replaceSimpleTags(String input, Pattern pattern, String tag, boolean closing) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer result = new StringBuffer();
        String replacement = closing ? "</" + tag + ">" : "<" + tag + ">";
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String replaceAnchors(String input) {
        Matcher matcher = ESCAPED_ANCHOR_TAG.matcher(input);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String url = sanitizeUrl(HtmlUtils.htmlUnescape(matcher.group(1)));
            String replacement = "";
            if (url != null) {
                String href = HtmlUtils.htmlEscape(url);
                replacement = "<a href=\"" + href + "\">";
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String replaceImages(String input) {
        Matcher matcher = ESCAPED_IMG_TAG.matcher(input);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String src = HtmlUtils.htmlUnescape(matcher.group(1)).trim();
            String replacement = "";
            if (NOTICE_IMAGE_SRC.matcher(src).matches()) {
                String alt = matcher.group(2) == null ? "" : HtmlUtils.htmlUnescape(matcher.group(2)).trim();
                replacement = "<img src=\"" + HtmlUtils.htmlEscape(src)
                        + "\" alt=\"" + HtmlUtils.htmlEscape(alt) + "\">";
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String sanitizeUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (trimmed.startsWith("/") && !trimmed.startsWith("//")) {
            return trimmed;
        }
        if (lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("mailto:")) {
            return trimmed;
        }
        return null;
    }

    private static String canonicalTag(String tag) {
        String lower = tag.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "b" -> "strong";
            case "i" -> "em";
            default -> lower;
        };
    }
}
