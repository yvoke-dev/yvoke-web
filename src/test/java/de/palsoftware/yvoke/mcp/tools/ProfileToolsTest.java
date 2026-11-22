package de.palsoftware.yvoke.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.area.TestAreas;
import de.palsoftware.yvoke.area.core.AreaService;
import de.palsoftware.yvoke.chat.core.ChatProperties;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfile;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfileRepository;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfileService;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.Profile;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.RoleConfig;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.RoleDefaults;
import de.palsoftware.yvoke.chat.orchestration.ResolvedProfile;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@code list_profiles} and {@code get_profile} expose multi-agent orchestrator profiles from the
 * database to Yvoke MCP clients with resolved limits via {@link OrchestratorProfileService#resolve}
 * and without leaking server-side operator model or thinking-level bindings.
 */
class ProfileToolsTest {

    private static final Set<String> EXPECTED_KEYS =
            Set.of(
                    "name",
                    "area",
                    "prototype",
                    "orchestratorPlaybook",
                    "reviewerPlaybook",
                    "specialistPlaybooks",
                    "maxReviewRounds",
                    "maxSpecialistCalls");

    private static final Set<String> OMITTED_OPERATOR_KEYS =
            Set.of(
                    "orchestratorModel",
                    "orchestratorThinkingLevel",
                    "reviewerModel",
                    "reviewerThinkingLevel",
                    "specialistModel",
                    "specialistThinkingLevel");

    private static final Instant NOW = Instant.parse("2026-05-01T10:00:00Z");

    private final ObjectMapper json = new ObjectMapper();
    private OrchestratorProfileRepository profileRepository;
    private AreaService areaService;
    private OrchestratorProperties defaultProperties;
    private ChatProperties chatProperties;
    private OrchestratorProfileService profileService;
    private ProfileTools tools;

    @BeforeEach
    void setUp() {
        authenticateAs("AREA_USER_*");
        profileRepository = mock(OrchestratorProfileRepository.class);
        areaService = TestAreas.withAreas("OIM", "PingID");
        defaultProperties =
                new OrchestratorProperties(
                        null,
                        null,
                        10,
                        50,
                        new RoleDefaults(
                                new RoleConfig("gemini-2.5-pro", "HIGH"),
                                new RoleConfig("gemini-2.5-flash", "MEDIUM"),
                                new RoleConfig("gemini-2.5-flash-lite", "LOW")),
                        List.of());
        chatProperties =
                new ChatProperties(
                        true,
                        List.of("gemini-2.5-pro"),
                        true,
                        false,
                        "",
                        Duration.ofMinutes(5),
                        100_000,
                        400_000);
        profileService =
                new OrchestratorProfileService(
                        profileRepository, defaultProperties, areaService, chatProperties);
        tools = new ProfileTools(profileService, areaService, json);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listAndGetReturnExactEightKeysOmittingOperatorModelsAndIncludingPrototypeProfiles()
            throws Exception {
        OrchestratorProfile full =
                new OrchestratorProfile(
                        "OIM",
                        5,
                        12,
                        "oim-orch",
                        "oim-rev",
                        List.of("oim-spec-1", "oim-spec-2"),
                        "Claude-Opus-4.6",
                        "HIGH",
                        "Claude-Sonnet-4.6",
                        "MEDIUM",
                        "Claude-Haiku-4.5",
                        "MINIMAL",
                        true,
                        NOW,
                        NOW,
                        "OIM");
        OrchestratorProfile nonProto =
                new OrchestratorProfile(
                        "PingID",
                        2,
                        6,
                        "ping-orch",
                        "ping-rev",
                        List.of("ping-spec"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        NOW,
                        NOW,
                        "PingID");
        when(profileRepository.findAll()).thenReturn(List.of(full, nonProto));
        when(profileRepository.findByName("OIM")).thenReturn(Optional.of(full));

        String rawList = tools.listProfiles(null);
        assertThat(rawList).doesNotContain("Claude-Opus-4.6").doesNotContain("gemini-2.5-pro");
        verify(profileRepository, never()).findByName(any());

        JsonNode list = json.readTree(rawList);
        assertThat(list.isArray()).isTrue();
        assertThat(list).hasSize(2);

        JsonNode first = list.get(0);
        assertExactShapeAndTypes(first);
        assertThat(first.get("name").asText()).isEqualTo("OIM");
        assertThat(first.get("area").asText()).isEqualTo("OIM");
        assertThat(first.get("prototype").booleanValue()).isTrue();
        assertThat(first.get("orchestratorPlaybook").asText()).isEqualTo("oim-orch");
        assertThat(first.get("reviewerPlaybook").asText()).isEqualTo("oim-rev");
        assertThat(json.convertValue(first.get("specialistPlaybooks"), List.class))
                .containsExactly("oim-spec-1", "oim-spec-2");
        assertThat(first.get("maxReviewRounds").intValue()).isEqualTo(5);
        assertThat(first.get("maxSpecialistCalls").intValue()).isEqualTo(12);

        JsonNode second = list.get(1);
        assertExactShapeAndTypes(second);
        assertThat(second.get("prototype").booleanValue()).isFalse();

        String rawGet = tools.getProfile("OIM");
        assertThat(rawGet).doesNotContain("Claude-Opus-4.6").doesNotContain("gemini-2.5-pro");
        JsonNode fetched = json.readTree(rawGet);
        assertExactShapeAndTypes(fetched);
        assertThat(fetched).isEqualTo(first);
    }

    @Test
    void toolDescriptionsAdvertiseExactEightFieldsWithoutModelOrThinkingNames() throws Exception {
        Method listMethod = ProfileTools.class.getDeclaredMethod("listProfiles", String.class);
        Method getMethod = ProfileTools.class.getDeclaredMethod("getProfile", String.class);

        for (String desc :
                List.of(
                        listMethod.getAnnotation(Tool.class).description(),
                        getMethod.getAnnotation(Tool.class).description())) {
            for (String expected : EXPECTED_KEYS) {
                assertThat(desc).contains(expected);
            }
            for (String omitted : OMITTED_OPERATOR_KEYS) {
                assertThat(desc).doesNotContain(omitted);
            }
        }
    }

    @Test
    void readsStrictlyFromDatabaseIgnoringYamlConfiguredProfiles() throws Exception {
        OrchestratorProperties propsWithYaml =
                new OrchestratorProperties(
                        4,
                        10,
                        10,
                        50,
                        defaultProperties.defaults(),
                        List.of(
                                new Profile(
                                        "yaml-only",
                                        "yaml-orch",
                                        "yaml-rev",
                                        List.of("yaml-spec"),
                                        new RoleConfig("m1", "high"),
                                        new RoleConfig("m2", "low"),
                                        new RoleConfig("m3", "minimal"))));
        OrchestratorProfileService yamlService =
                new OrchestratorProfileService(
                        profileRepository, propsWithYaml, areaService, chatProperties);
        ProfileTools yamlTools = new ProfileTools(yamlService, areaService, json);
        when(profileRepository.findAll()).thenReturn(List.of());
        when(profileRepository.findByName("yaml-only")).thenReturn(Optional.empty());

        JsonNode list = json.readTree(yamlTools.listProfiles(null));
        assertThat(list.isArray()).isTrue();
        assertThat(list).isEmpty();
        assertThat(yamlTools.getProfile("yaml-only"))
                .isEqualTo("ERROR: profile 'yaml-only' not found.");
    }

    @Test
    void resolvedLimitsAndPlaybooksMatchOrchestratorProfileServiceResolve() throws Exception {
        OrchestratorProfile zeroCaps =
                new OrchestratorProfile(
                        "zero-prof",
                        0,
                        0,
                        "orch-z",
                        "rev-z",
                        List.of("spec-z"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        NOW,
                        NOW,
                        "OIM");
        OrchestratorProfile negativeCaps =
                new OrchestratorProfile(
                        "neg-prof",
                        -1,
                        -5,
                        "orch-n",
                        "rev-n",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        NOW,
                        NOW,
                        "OIM");
        OrchestratorProfile positiveCaps =
                new OrchestratorProfile(
                        "pos-prof",
                        4,
                        12,
                        "orch-p",
                        "rev-p",
                        List.of("spec-1", "spec-2"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        true,
                        NOW,
                        NOW,
                        "OIM");
        List<OrchestratorProfile> all = List.of(zeroCaps, negativeCaps, positiveCaps);

        when(profileRepository.findAll()).thenReturn(all);
        for (OrchestratorProfile p : all) {
            when(profileRepository.findByName(p.name())).thenReturn(Optional.of(p));
        }

        // 1. Default properties (null maxReviewRounds -> 3, null maxSpecialistCalls -> 8)
        assertParityWithService(profileService, tools, all);
        assertThat(json.readTree(tools.getProfile("zero-prof")).get("maxReviewRounds").intValue())
                .isEqualTo(3);
        assertThat(
                        json.readTree(tools.getProfile("zero-prof"))
                                .get("maxSpecialistCalls")
                                .intValue())
                .isEqualTo(8);

        // 2. Custom properties (5 review rounds, 10 specialist calls)
        OrchestratorProfileService customService =
                new OrchestratorProfileService(
                        profileRepository,
                        new OrchestratorProperties(5, 10, 10, 50, null, null),
                        areaService,
                        chatProperties);
        ProfileTools customTools = new ProfileTools(customService, areaService, json);
        assertParityWithService(customService, customTools, all);
        assertThat(
                        json.readTree(customTools.getProfile("zero-prof"))
                                .get("maxReviewRounds")
                                .intValue())
                .isEqualTo(5);
        assertThat(
                        json.readTree(customTools.getProfile("zero-prof"))
                                .get("maxSpecialistCalls")
                                .intValue())
                .isEqualTo(10);
    }

    @Test
    void storedIdentifiersAreReturnedUnchangedWithoutTrimming() throws Exception {
        OrchestratorProfile untrimmed =
                new OrchestratorProfile(
                        "OIM ",
                        2,
                        6,
                        "  orch-pb  ",
                        "   ",
                        List.of(" oim-a ", ""),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        NOW,
                        NOW,
                        " OIM ");
        OrchestratorProfile trimmedCollision =
                new OrchestratorProfile(
                        "OIM",
                        5,
                        10,
                        "clean-orch",
                        "clean-rev",
                        List.of("clean-spec"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        NOW,
                        NOW,
                        "OIM");
        OrchestratorProfile nullSpecialists =
                new OrchestratorProfile(
                        "Bare", 1, 4, null, "", null, null, null, null, null, null, null, false,
                        NOW, NOW, "OIM");
        when(profileRepository.findAll())
                .thenReturn(List.of(untrimmed, trimmedCollision, nullSpecialists));
        when(profileRepository.findByName("OIM ")).thenReturn(Optional.of(untrimmed));
        when(profileRepository.findByName("OIM")).thenReturn(Optional.of(trimmedCollision));

        JsonNode list = json.readTree(tools.listProfiles(null));
        assertThat(list).hasSize(3);

        JsonNode first = list.get(0);
        assertExactShapeAndTypes(first);
        String listedName = first.get("name").asText();
        assertThat(listedName).isEqualTo("OIM ");
        assertThat(first.get("area").asText()).isEqualTo(" OIM ");
        assertThat(first.get("orchestratorPlaybook").asText()).isEqualTo("  orch-pb  ");
        assertThat(first.get("reviewerPlaybook").isTextual()).isTrue();
        assertThat(first.get("reviewerPlaybook").asText()).isEqualTo("   ");
        assertThat(json.convertValue(first.get("specialistPlaybooks"), List.class))
                .containsExactly(" oim-a ", "");

        // Round-trip: passing the exact listed name ("OIM ") to get_profile must resolve "OIM "
        // rather than colliding with "OIM" or failing with not-found.
        JsonNode roundTrip = json.readTree(tools.getProfile(listedName));
        assertExactShapeAndTypes(roundTrip);
        assertThat(roundTrip.get("name").asText()).isEqualTo("OIM ");
        assertThat(roundTrip).isEqualTo(first);

        JsonNode third = list.get(2);
        assertExactShapeAndTypes(third);
        assertThat(third.get("name").asText()).isEqualTo("Bare");
        assertThat(third.get("orchestratorPlaybook").isNull()).isTrue();
        assertThat(third.get("reviewerPlaybook").isTextual()).isTrue();
        assertThat(third.get("reviewerPlaybook").asText()).isEmpty();
        assertThat(third.get("specialistPlaybooks")).isEmpty();
    }

    @Test
    void listProfilesMatchesAreaLenientlyReturnsAllWhenBlankOrNullAndEmptyWhenUnknown()
            throws Exception {
        OrchestratorProfile oimProf = profileInArea("oim-prof", "OIM");
        OrchestratorProfile pingProf = profileInArea("ping-prof", "PingID");
        when(profileRepository.findAll()).thenReturn(List.of(oimProf, pingProf));

        JsonNode oimFiltered = json.readTree(tools.listProfiles(" oim "));
        assertThat(oimFiltered).hasSize(1);
        assertThat(oimFiltered.get(0).get("name").asText()).isEqualTo("oim-prof");

        JsonNode pingFiltered = json.readTree(tools.listProfiles("PingID"));
        assertThat(pingFiltered).hasSize(1);
        assertThat(pingFiltered.get(0).get("name").asText()).isEqualTo("ping-prof");

        assertThat(json.readTree(tools.listProfiles(null))).hasSize(2);
        assertThat(json.readTree(tools.listProfiles(""))).hasSize(2);
        assertThat(json.readTree(tools.listProfiles("   "))).hasSize(2);

        JsonNode unknown = json.readTree(tools.listProfiles("SAP"));
        assertThat(unknown.isArray()).isTrue();
        assertThat(unknown).isEmpty();
    }

    @Test
    void scopedCallerIntersectingWithAreaFilterSeesOnlyPermittedArea() throws Exception {
        OrchestratorProfile oimProf = profileInArea("oim-prof", "OIM");
        OrchestratorProfile pingProf = profileInArea("ping-prof", "PingID");
        when(profileRepository.findAll()).thenReturn(List.of(oimProf, pingProf));

        authenticateAs("AREA_USER_OIM");

        assertThat(tools.listProfiles("PingID")).isEqualTo("[]");
        assertThat(tools.listProfiles(" pingid ")).isEqualTo("[]");

        JsonNode oimList = json.readTree(tools.listProfiles(" oim "));
        assertThat(oimList).hasSize(1);
        assertThat(oimList.get(0).get("name").asText()).isEqualTo("oim-prof");
    }

    @Test
    void areaScopingEnforcesOpaqueRefusalAndBlocksNullAndBlankAreaProfilesForNonWildcardCallers()
            throws Exception {
        OrchestratorProfile oimProf = profileInArea("oim-prof", "OIM");
        OrchestratorProfile pingProf = profileInArea("ping-prof", "PingID");
        OrchestratorProfile nullAreaProf = profileInArea("null-area-prof", null);
        OrchestratorProfile blankAreaProf = profileInArea("blank-area-prof", "   ");

        when(profileRepository.findAll())
                .thenReturn(List.of(oimProf, pingProf, nullAreaProf, blankAreaProf));
        when(profileRepository.findByName("oim-prof")).thenReturn(Optional.of(oimProf));
        when(profileRepository.findByName("ping-prof")).thenReturn(Optional.of(pingProf));
        when(profileRepository.findByName("null-area-prof")).thenReturn(Optional.of(nullAreaProf));
        when(profileRepository.findByName("blank-area-prof"))
                .thenReturn(Optional.of(blankAreaProf));
        when(profileRepository.findByName("missing-prof")).thenReturn(Optional.empty());

        // 1. Positive control under AREA_USER_*
        assertThat(json.readTree(tools.listProfiles(null))).hasSize(4);
        assertThat(json.readTree(tools.getProfile("ping-prof")).get("name").asText())
                .isEqualTo("ping-prof");
        JsonNode nullAreaJson = json.readTree(tools.getProfile("null-area-prof"));
        assertThat(nullAreaJson.get("name").asText()).isEqualTo("null-area-prof");
        assertThat(nullAreaJson.get("area").isNull()).isTrue();
        JsonNode blankAreaJson = json.readTree(tools.getProfile("blank-area-prof"));
        assertThat(blankAreaJson.get("name").asText()).isEqualTo("blank-area-prof");
        assertThat(blankAreaJson.get("area").asText()).isEqualTo("   ");

        // 2. Scoped user AREA_USER_OIM
        authenticateAs("AREA_USER_OIM");
        JsonNode oimOnly = json.readTree(tools.listProfiles(null));
        assertThat(oimOnly).hasSize(1);
        assertThat(oimOnly.get(0).get("name").asText()).isEqualTo("oim-prof");

        assertThat(tools.getProfile("oim-prof")).doesNotStartWith("ERROR:");
        assertThat(tools.getProfile("missing-prof"))
                .isEqualTo("ERROR: profile 'missing-prof' not found.");
        assertThat(tools.getProfile("ping-prof"))
                .isEqualTo("ERROR: profile 'ping-prof' not found.");
        assertThat(tools.getProfile("  ping-prof  "))
                .isEqualTo("ERROR: profile '  ping-prof  ' not found.");
        assertThat(tools.getProfile("null-area-prof"))
                .isEqualTo("ERROR: profile 'null-area-prof' not found.");
        assertThat(tools.getProfile("blank-area-prof"))
                .isEqualTo("ERROR: profile 'blank-area-prof' not found.");

        // Byte-for-byte refusal symmetry when the same name (exact or padded) is absent vs
        // unauthorized in DB
        when(profileRepository.findByName("ping-prof")).thenReturn(Optional.empty());
        String absentReply = tools.getProfile("ping-prof");
        when(profileRepository.findByName("ping-prof")).thenReturn(Optional.of(pingProf));
        String unauthorizedReply = tools.getProfile("ping-prof");
        assertThat(unauthorizedReply)
                .isEqualTo(absentReply)
                .isEqualTo("ERROR: profile 'ping-prof' not found.");

        String absentPaddedReply = tools.getProfile("  ping-prof  ");
        when(profileRepository.findByName("  ping-prof  "))
                .thenReturn(Optional.of(profileInArea("  ping-prof  ", "PingID")));
        String unauthorizedPaddedReply = tools.getProfile("  ping-prof  ");
        assertThat(unauthorizedPaddedReply)
                .isEqualTo(absentPaddedReply)
                .isEqualTo("ERROR: profile '  ping-prof  ' not found.");

        // 3. Zero-area caller (SCOPE_mcp.read only)
        authenticateAs("SCOPE_mcp.read");
        assertThat(tools.listProfiles(null)).isEqualTo("[]");
        assertThat(tools.getProfile("oim-prof")).isEqualTo("ERROR: profile 'oim-prof' not found.");
        assertThat(tools.getProfile("ping-prof"))
                .isEqualTo("ERROR: profile 'ping-prof' not found.");
        assertThat(tools.getProfile("null-area-prof"))
                .isEqualTo("ERROR: profile 'null-area-prof' not found.");
        assertThat(tools.getProfile("blank-area-prof"))
                .isEqualTo("ERROR: profile 'blank-area-prof' not found.");

        // 4. Cleared SecurityContext (unauthenticated)
        SecurityContextHolder.clearContext();
        assertThat(tools.listProfiles(null)).isEqualTo("[]");
        assertThat(tools.getProfile("oim-prof")).isEqualTo("ERROR: profile 'oim-prof' not found.");
        assertThat(tools.getProfile("null-area-prof"))
                .isEqualTo("ERROR: profile 'null-area-prof' not found.");
        assertThat(tools.getProfile("blank-area-prof"))
                .isEqualTo("ERROR: profile 'blank-area-prof' not found.");
    }

    @Test
    void getProfileReReadsRepositoryOnEveryCallAndLooksUpExactNameWithoutTrimming()
            throws Exception {
        OrchestratorProfile oimProf = profileInArea("oim-default", "OIM");
        when(profileRepository.findByName("oim-default"))
                .thenReturn(Optional.of(oimProf), Optional.empty());
        when(profileRepository.findByName("  oim-default  ")).thenReturn(Optional.empty());

        assertThat(json.readTree(tools.getProfile("oim-default")).get("name").asText())
                .isEqualTo("oim-default");
        assertThat(tools.getProfile("oim-default"))
                .isEqualTo("ERROR: profile 'oim-default' not found.");
        assertThat(tools.getProfile("  oim-default  "))
                .isEqualTo("ERROR: profile '  oim-default  ' not found.");
        verify(profileRepository, times(2)).findByName("oim-default");
        verify(profileRepository, times(1)).findByName("  oim-default  ");
    }

    @Test
    void getProfileRejectsMissingOrBlankNameBeforeTouchingRepository() {
        assertThat(tools.getProfile(null)).isEqualTo("ERROR: 'name' is required for get_profile.");
        assertThat(tools.getProfile("")).isEqualTo("ERROR: 'name' is required for get_profile.");
        assertThat(tools.getProfile("   ")).isEqualTo("ERROR: 'name' is required for get_profile.");
        verifyNoInteractions(profileRepository);
    }

    @Test
    void infrastructureAndSerializationFailuresReturnSanitizedToolErrorStrings() throws Exception {
        // 1. Repository failures
        when(profileRepository.findAll())
                .thenThrow(
                        new IllegalStateException("jdbc:postgresql://secret-db-host:5432/yvoke"));
        when(profileRepository.findByName("OIM"))
                .thenThrow(
                        new IllegalStateException("jdbc:postgresql://secret-db-host:5432/yvoke"));

        assertThat(tools.listProfiles(null))
                .isEqualTo("ERROR: the 'list_profiles' tool failed to complete the request.")
                .doesNotContain("secret-db-host");
        assertThat(tools.getProfile("OIM"))
                .isEqualTo("ERROR: the 'get_profile' tool failed to complete the request.")
                .doesNotContain("secret-db-host");

        // 2. AreaService failure
        AreaService failingAreas = mock(AreaService.class);
        when(failingAreas.findArea("OIM"))
                .thenThrow(new RuntimeException("secret-db-host area lookup failed"));
        OrchestratorProfileRepository cleanRepo = mock(OrchestratorProfileRepository.class);
        OrchestratorProfileService areaFailService =
                new OrchestratorProfileService(
                        cleanRepo, defaultProperties, failingAreas, chatProperties);
        ProfileTools areaFailTools = new ProfileTools(areaFailService, failingAreas, json);
        assertThat(areaFailTools.listProfiles("OIM"))
                .isEqualTo("ERROR: the 'list_profiles' tool failed to complete the request.")
                .doesNotContain("secret-db-host");

        // 3. ObjectMapper serialization failure
        ObjectMapper brokenMapper = mock(ObjectMapper.class);
        when(brokenMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("secret-db-host serialization boom") {});
        when(cleanRepo.findAll()).thenReturn(List.of(profileInArea("OIM", "OIM")));
        when(cleanRepo.findByName("OIM")).thenReturn(Optional.of(profileInArea("OIM", "OIM")));
        OrchestratorProfileService cleanService =
                new OrchestratorProfileService(
                        cleanRepo, defaultProperties, areaService, chatProperties);
        ProfileTools mapperFailTools = new ProfileTools(cleanService, areaService, brokenMapper);

        assertThat(mapperFailTools.listProfiles(null))
                .isEqualTo("ERROR: the 'list_profiles' tool failed to complete the request.")
                .doesNotContain("secret-db-host");
        assertThat(mapperFailTools.getProfile("OIM"))
                .isEqualTo("ERROR: the 'get_profile' tool failed to complete the request.")
                .doesNotContain("secret-db-host");
    }

    private void assertParityWithService(
            OrchestratorProfileService service,
            ProfileTools target,
            List<OrchestratorProfile> profiles)
            throws Exception {
        JsonNode listed = json.readTree(target.listProfiles(null));
        assertThat(listed).hasSize(profiles.size());
        for (int i = 0; i < profiles.size(); i++) {
            OrchestratorProfile profile = profiles.get(i);
            ResolvedProfile expected = service.resolve(profile);
            JsonNode fromList = listed.get(i);
            JsonNode fromGet = json.readTree(target.getProfile(profile.name()));
            for (JsonNode node : List.of(fromList, fromGet)) {
                assertExactShapeAndTypes(node);
                assertThat(node.get("name").asText()).isEqualTo(expected.name());
                assertThat(node.get("orchestratorPlaybook").asText())
                        .isEqualTo(expected.orchestratorPlaybook());
                assertThat(node.get("reviewerPlaybook").asText())
                        .isEqualTo(expected.reviewerPlaybook());
                assertThat(json.convertValue(node.get("specialistPlaybooks"), List.class))
                        .isEqualTo(expected.specialistPlaybooks());
                assertThat(node.get("maxReviewRounds").intValue())
                        .isEqualTo(expected.maxReviewRounds());
                assertThat(node.get("maxSpecialistCalls").intValue())
                        .isEqualTo(expected.maxSpecialistCalls());
            }
        }
    }

    private static OrchestratorProfile profileInArea(String name, String area) {
        return new OrchestratorProfile(
                name,
                3,
                8,
                "orch-pb",
                "rev-pb",
                List.of("spec-pb"),
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                NOW,
                NOW,
                area);
    }

    private static void authenticateAs(String authority) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "user", "pass", List.of(new SimpleGrantedAuthority(authority))));
    }

    private static void assertExactShapeAndTypes(JsonNode node) {
        Set<String> actualKeys = keys(node);
        assertThat(actualKeys).doesNotContainAnyElementsOf(OMITTED_OPERATOR_KEYS);
        assertThat(actualKeys).isEqualTo(new TreeSet<>(EXPECTED_KEYS));
        assertThat(node.get("prototype").isBoolean()).isTrue();
        assertThat(node.get("specialistPlaybooks").isArray()).isTrue();
        assertThat(node.get("maxReviewRounds").isIntegralNumber()).isTrue();
        assertThat(node.get("maxSpecialistCalls").isIntegralNumber()).isTrue();
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> keys = new TreeSet<>();
        Iterator<String> names = node.fieldNames();
        names.forEachRemaining(keys::add);
        return keys;
    }
}
