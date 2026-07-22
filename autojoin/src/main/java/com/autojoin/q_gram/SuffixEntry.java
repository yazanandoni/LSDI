package com.autojoin.q_gram;


/** An entry of the suffix index. */
class SuffixEntry {
    String suffixText;
    int originalRowIndex;

    SuffixEntry(String suffixText, int originalRowIndex) {
        this.suffixText = suffixText;
        this.originalRowIndex = originalRowIndex;
    }
}
