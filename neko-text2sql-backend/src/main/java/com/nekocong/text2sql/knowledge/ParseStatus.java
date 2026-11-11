package com.nekocong.text2sql.knowledge;

/** Parse state of the original document (a subset of the item lifecycle). */
public enum ParseStatus {
    PENDING,
    PARSING,
    PARSED,
    FAILED
}
