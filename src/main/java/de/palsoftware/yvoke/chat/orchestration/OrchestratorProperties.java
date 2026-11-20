package de.palsoftware.yvoke.chat.orchestration;

import java.util.List;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Configuration for multi-agent orchestrator mode. Global {@code defaults} supply the model +
 * thinking level for each role (orchestrator / reviewer / specialist); each named {@code profile}
 * (a knowledge base such as OIM or PingID) names its three playbooks and MAY override any role's
 * model/thinking. A profile that doesn't override inherits the defaults.
 */
@ConfigurationProperties(prefix="app.ai.orchestrator")public record OrchestratorProperties(Integer maxReviewRounds,Integer maxSpecialistCalls,Integer maxReviewRoundsLimit,Integer maxSpecialistCallsLimit,RoleDefaults defaults,List<Profile>profiles){

public static final int DEFAULT_MAX_REVIEW_ROUNDS_LIMIT=10;public static final int DEFAULT_MAX_SPECIALIST_CALLS_LIMIT=50;

@ConstructorBinding public OrchestratorProperties(Integer maxReviewRounds,Integer maxSpecialistCalls,Integer maxReviewRoundsLimit,Integer maxSpecialistCallsLimit,RoleDefaults defaults,List<Profile>profiles){this.maxReviewRounds=maxReviewRounds;this.maxSpecialistCalls=maxSpecialistCalls;this.maxReviewRoundsLimit=maxReviewRoundsLimit;this.maxSpecialistCallsLimit=maxSpecialistCallsLimit;this.defaults=defaults;this.profiles=profiles;}

public OrchestratorProperties(Integer maxReviewRounds,Integer maxSpecialistCalls,RoleDefaults defaults,List<Profile>profiles){this(maxReviewRounds,maxSpecialistCalls,DEFAULT_MAX_REVIEW_ROUNDS_LIMIT,DEFAULT_MAX_SPECIALIST_CALLS_LIMIT,defaults,profiles);}

/** Model + thinking level for one agent role. Either field may be null (inherit the default). */
public record RoleConfig(String model,String thinkingLevel){RoleConfig mergeOver(RoleConfig fallback){RoleConfig base=fallback!=null?fallback:new RoleConfig(null,null);String m=(model!=null&&!model.isBlank())?model:base.model();String t=(thinkingLevel!=null&&!thinkingLevel.isBlank())?thinkingLevel:base.thinkingLevel();return new RoleConfig(m,t);}}

public record RoleDefaults(RoleConfig orchestrator,RoleConfig reviewer,RoleConfig specialist){}

/** One knowledge base: its three playbooks + optional per-role model/thinking overrides. */
public record Profile(String name,String orchestratorPlaybook,String reviewerPlaybook,List<String>specialistPlaybooks,RoleConfig orchestrator,RoleConfig reviewer,RoleConfig specialist){}

public int resolvedMaxReviewRounds(){return maxReviewRounds!=null?maxReviewRounds:3;}

public int resolvedMaxSpecialistCalls(){return maxSpecialistCalls!=null?maxSpecialistCalls:8;}

public int resolvedMaxReviewRoundsLimit(){return(maxReviewRoundsLimit!=null&&maxReviewRoundsLimit>0)?maxReviewRoundsLimit:DEFAULT_MAX_REVIEW_ROUNDS_LIMIT;}

public int resolvedMaxSpecialistCallsLimit(){return(maxSpecialistCallsLimit!=null&&maxSpecialistCallsLimit>0)?maxSpecialistCallsLimit:DEFAULT_MAX_SPECIALIST_CALLS_LIMIT;}

public List<String>profileNames(){return profiles==null?List.of():profiles.stream().map(Profile::name).toList();}

public Optional<Profile>profile(String name){if(name==null||name.isBlank()||profiles==null){return Optional.empty();}return profiles.stream().filter(p->name.equals(p.name())).findFirst();}

/** Resolves a profile by name, merging each role's overrides over the global defaults. */
public ResolvedProfile resolve(String name){Profile p=profile(name).orElseThrow(()->new IllegalArgumentException("Unknown orchestrator profile: '"+name+"'. Configured: "+profileNames()));RoleDefaults d=defaults!=null?defaults:new RoleDefaults(null,null,null);RoleConfig orch=orNull(p.orchestrator()).mergeOver(d.orchestrator());RoleConfig rev=orNull(p.reviewer()).mergeOver(d.reviewer());RoleConfig spec=orNull(p.specialist()).mergeOver(d.specialist());return new ResolvedProfile(p.name(),p.orchestratorPlaybook(),p.reviewerPlaybook(),p.specialistPlaybooks()==null?List.of():p.specialistPlaybooks(),orch,rev,spec,resolvedMaxReviewRounds(),resolvedMaxSpecialistCalls());}

private static RoleConfig orNull(RoleConfig c){return c!=null?c:new RoleConfig(null,null);}}
