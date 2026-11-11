package com.nekocong.text2sql.nl2sql;

import com.nekocong.text2sql.knowledge.Citation;
import java.util.List;

/**
 * The original-source evidence used to ground an answer, streamed so the client can render numbered
 * citation badges that open the highlighted original.
 *
 * @param citations the citations, in hit order
 */
public record CitationEvent(List<Citation> citations) implements Text2SqlEvent {
}
