package com.autojoin.operator;

public enum Casing {

    NONE {
        @Override public String apply(String s) { return s; }
    },
    LOWER {
        @Override public String apply(String s) { return s.toLowerCase(); }
    },
    UPPER {
        @Override public String apply(String s) { return s.toUpperCase(); }
    },
    TITLE {
        @Override public String apply(String s) {
            if (s.isEmpty()) return s;
            StringBuilder sb = new StringBuilder(s.length());
            boolean capitalizeNext = true;
            for (char c : s.toCharArray()) {
                if (Character.isWhitespace(c)) {
                    capitalizeNext = true;
                    sb.append(c);
                } else if (capitalizeNext) {
                    sb.append(Character.toUpperCase(c));
                    capitalizeNext = false;
                } else {
                    sb.append(Character.toLowerCase(c));
                }
            }
            return sb.toString();
        }
    };

    public abstract String apply(String s);
}