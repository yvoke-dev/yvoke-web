package de.palsoftware.yvoke.chat.orchestration;

import java.util.List;

/**
 * The review agent's structured verdict on a candidate answer.
 *
 * <p>
 * The two rejection lists are separate because they have different remedies, and the difference is
 * worth far more than the prose in {@code feedback} could carry. A <b>citation fix</b> is
 * repairable from evidence the orchestrator is already holding — the right source was retrieved,
 * the wrong number was put on it — so it needs no retrieval at all. An <b>unsupported claim</b> may
 * genuinely have nothing behind it, and delegating is then the only remedy.
 *
 * <p>
 * Collapsing them into one list is what a live run cost: the reviewer named the exact swap to make
 * ("the correct name IS supported by [4]") and the orchestrator answered by running another
 * specialist for 347,969 prompt tokens, because nothing in its input distinguished "renumber this"
 * from "go and find this".
 */
public record Verdict(boolean approved,String feedback,List<String>unsupportedClaims,List<String>citationFixes){

public Verdict{unsupportedClaims=unsupportedClaims==null?List.of():List.copyOf(unsupportedClaims);citationFixes=citationFixes==null?List.of():List.copyOf(citationFixes);}

public static Verdict reject(String feedback){return new Verdict(false,feedback,List.of(),List.of());}

/**
 * Whether every objection is repairable by re-citing. Deliberately false when the reviewer listed
 * nothing at all: a rejection whose objections are only in the prose could be anything, and
 * forbidding delegation on that basis could strand a run that genuinely needs a search.
 */
public boolean isCitationOnly(){return!citationFixes.isEmpty()&&unsupportedClaims.isEmpty();}}
