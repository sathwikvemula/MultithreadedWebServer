package com.sathwik.webserver;

/**
 * Small character-class helpers shared by the request parser and the response writer.
 */
final class HttpSyntax {

    private static final String TOKEN_SPECIALS = "!#$%&'*+-.^_`|~";

    private HttpSyntax() {
    }

    /** True if the text is a non-empty HTTP "token" (used for methods and header names). */
    static boolean isToken(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean letterOrDigit = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9');
            if (!letterOrDigit && TOKEN_SPECIALS.indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    /** True if the text contains only visible ASCII, spaces and tabs (no CR, LF or other controls). */
    static boolean isHeaderSafe(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\t' && (c < 0x20 || c > 0x7E)) {
                return false;
            }
        }
        return true;
    }
}
