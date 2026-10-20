package com.newtube.mobile.bypass;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;

/**
 * Lenient base64 decoder (standard and URL-safe alphabets, optional padding, whitespace ignored).
 * java.util.Base64 needs API 26 and the app's minSdk is 24, and android.util.Base64 does not exist
 * in plain JVM unit tests, so the few lines live here.
 */
final class B64 {
    private B64() {
    }

    /** @throws IllegalArgumentException on a character outside both alphabets. */
    @NonNull
    static byte[] decode(@NonNull String input) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length() * 3 / 4 + 3);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            int v;
            if (c >= 'A' && c <= 'Z') {
                v = c - 'A';
            } else if (c >= 'a' && c <= 'z') {
                v = c - 'a' + 26;
            } else if (c >= '0' && c <= '9') {
                v = c - '0' + 52;
            } else if (c == '+' || c == '-') {
                v = 62;
            } else if (c == '/' || c == '_') {
                v = 63;
            } else if (c == '=' || c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                continue;
            } else {
                throw new IllegalArgumentException("Bad base64 character: " + c);
            }
            buffer = (buffer << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }
}
