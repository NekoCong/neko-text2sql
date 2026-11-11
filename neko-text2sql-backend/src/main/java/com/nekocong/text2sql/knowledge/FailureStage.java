package com.nekocong.text2sql.knowledge;

/** The stage a failed item stopped at, so a retry can resume instead of redoing everything. */
public enum FailureStage {
    PARSE,
    CHUNK,
    EMBED
}
