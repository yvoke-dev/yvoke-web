package de.palsoftware.yvoke.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.area.TestAreas;
import de.palsoftware.yvoke.area.core.AreaService;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfile;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProfileRepository;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.Profile;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.RoleConfig;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.RoleDefaults;
import java.time.Instant;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@code list_profiles} and {@code get_profile} expose multi-agent orchestrator profiles from the
 * database to Yvoke MCP clients with resolved limits and role model/thinking defaults.
 */
class ProfileToolsTest {

    private static final Set<String> EXPECTED_KEYS = Set.of("name", "area", "prototype",
        "orchestratorPlaybook", "reviewerPlaybook", "specialistPlaybooks", "maxReviewRounds",
        "maxSpecialistCalls", "orchestratorModel", "orchestratorThinkingLevel", "reviewerModel",
        "reviewerThinkingLevel", "specialistModel", "specialistThinkingLevel");

    private static final Instant NOW = Instant.parse("2026-05-01T10:00:00Z");

    private final ObjectMapper json = new ObjectMapper();
    private OrchestratorProfileRepository profileRepository;
    private AreaService areaService;
    private OrchestratorProperties defaultProperties;
    private ProfileTools tools;

    @BeforeEach
    void setUp() {
        authenticateAs("AREA_USER_*");
        profileRepository = mock(OrchestratorProfileRepository.class);
        areaService = TestAreas.withAreas("OIM", "PingID");
        defaultProperties = new OrchestratorProperties(null, null,
            new RoleDefaults(new RoleConfig("gemini-2.5-pro", "HIGH"),
                new RoleConfig("gemini-2.5-flash", "MEDIUM"),
                new RoleConfig("gemini-2.5-flash-lite", "LOW")),
            List.of());
        tools = new ProfileTools(profileRepository, defaultProperties, areaService, json);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listAndGetReturnExactFourteenKeysAndExpectedJsonTypesIncludingPrototypeProfiles()
        throws Exception {
        OrchestratorProfile full = new OrchestratorProfile("OIM", 5, 12, "oim-orch", "oim-rev",
            List.of("oim-spec-1", "oim-spec-2"), "Claude-Opus-4.6", "HIGH", "Claude-Sonnet-4.6",
            "MEDIUM", "Claude-Haiku-4.5", "MINIMAL", true, NOW, NOW, "OIM");
        OrchestratorProfile nonProto =
            new OrchestratorProfile("PingID", 2, 6, "ping-orch", "ping-rev", List.of("ping-spec"),
                null, null, null, null, null, null, false, NOW, NOW, "PingID");
        when(profileRepository.findAll()).thenReturn(List.of(full, nonProto));
        when(profileRepository.findByName("OIM")).thenReturn(Optional.of(full));

        JsonNode list = json.readTree(tools.listProfiles(null));
        assertThat(list.isArray()).isTrue();
        assertThat(list).hasSize(2);

        JsonNode first = list.get(0);
        assertExactShapeAndTypes(first);
        assertThat(first.get("name").asText()).isEqualTo("OIM");
        assertThat(first.get("area").asText()).isEqualTo("OIM");
        assertThat(first.get("prototype").isBoolean()).isTrue();
        assertThat(first.get("prototype").booleanValue()).isTrue();
        assertThat(first.get("orchestratorPlaybook").asText()).isEqualTo("oim-orch");
        assertThat(first.get("reviewerPlaybook").asText()).isEqualTo("oim-rev");
        assertThat(json.convertValue(first.get("specialistPlaybooks"), List.class))
            .containsExactly("oim-spec-1", "oim-spec-2");
        assertThat(first.get("maxReviewRounds").intValue()).isEqualTo(5);
        assertThat(first.get("maxSpecialistCalls").intValue()).isEqualTo(12);
        assertThat(first.get("orchestratorModel").asText()).isEqualTo("Claude-Opus-4.6");
        assertThat(first.get("orchestratorThinkingLevel").asText()).isEqualTo("high");
        assertThat(first.get("reviewerModel").asText()).isEqualTo("Claude-Sonnet-4.6");
        assertThat(first.get("reviewerThinkingLevel").asText()).isEqualTo("medium");
        assertThat(first.get("specialistModel").asText()).isEqualTo("Claude-Haiku-4.5");
        assertThat(first.get("specialistThinkingLevel").asText()).isEqualTo("minimal");

        JsonNode second = list.get(1);
        assertExactShapeAndTypes(second);
        assertThat(second.get("prototype").booleanValue()).isFalse();

        JsonNode fetched = json.readTree(tools.getProfile("OIM"));
        assertExactShapeAndTypes(fetched);
        assertThat(fetched).isEqualTo(first);
    }

    @Test
    void readsStrictlyFromDatabaseIgnoringYamlConfiguredProfiles() throws Exception {
        OrchestratorProperties propsWithYaml =
            new OrchestratorProperties(4, 10, defaultProperties.defaults(),
                List.of(new Profile("yaml-only", "yaml-orch", "yaml-rev", List.of("yaml-spec"),
                    new RoleConfig("m1", "high"), new RoleConfig("m2", "low"),
                    new RoleConfig("m3", "minimal"))));
        ProfileTools yamlTools =
            new ProfileTools(profileRepository, propsWithYaml, areaService, json);
        when(profileRepository.findAll()).thenReturn(List.of());
        when(profileRepository.findByName("yaml-only")).thenReturn(Optional.empty());

        JsonNode list = json.readTree(yamlTools.listProfiles(null));
        assertThat(list.isArray()).isTrue();
        assertThat(list).isEmpty();
        assertThat(yamlTools.getProfile("yaml-only"))
            .isEqualTo("ERROR: profile 'yaml-only' not found.");
    }

    @Test
    void maxReviewRoundsAndMaxSpecialistCallsRespectZeroAndFallbackBoundaries() throws Exception {
        OrchestratorProfile zeroRoundsZeroCalls = new OrchestratorProfile("zero-prof", 0, 0, "orch",
            "rev", List.of(), null, null, null, null, null, null, false, NOW, NOW, "OIM");
        OrchestratorProfile negativeRoundsNegativeCalls =
            new OrchestratorProfile("neg-prof", -1, -1, "orch", "rev", List.of(), null, null, null,
                null, null, null, false, NOW, NOW, "OIM");
        OrchestratorProfile explicitPositive = new OrchestratorProfile("pos-prof", 5, 12, "orch",
            "rev", List.of(), null, null, null, null, null, null, false, NOW, NOW, "OIM");

        when(profileRepository.findByName("zero-prof"))
            .thenReturn(Optional.of(zeroRoundsZeroCalls));
        when(profileRepository.findByName("neg-prof"))
            .thenReturn(Optional.of(negativeRoundsNegativeCalls));
        when(profileRepository.findByName("pos-prof")).thenReturn(Optional.of(explicitPositive));

        // Default properties (null maxReviewRounds -> 3, null maxSpecialistCalls -> 8)
        JsonNode zeroNode = json.readTree(tools.getProfile("zero-prof"));
        assertThat(zeroNode.get("maxReviewRounds").isIntegralNumber()).isTrue();
        assertThat(zeroNode.get("maxReviewRounds").intValue()).isZero();
        assertThat(zeroNode.get("maxSpecialistCalls").isIntegralNumber()).isTrue();
        assertThat(zeroNode.get("maxSpecialistCalls").intValue()).isEqualTo(8);

        JsonNode negNode = json.readTree(tools.getProfile("neg-prof"));
        assertThat(negNode.get("maxReviewRounds").isIntegralNumber()).isTrue();
        assertThat(negNode.get("maxReviewRounds").intValue()).isEqualTo(3);
        assertThat(negNode.get("maxSpecialistCalls").isIntegralNumber()).isTrue();
        assertThat(negNode.get("maxSpecialistCalls").intValue()).isEqualTo(8);

        JsonNode posNode = json.readTree(tools.getProfile("pos-prof"));
        assertThat(posNode.get("maxReviewRounds").intValue()).isEqualTo(5);
        assertThat(posNode.get("maxSpecialistCalls").intValue()).isEqualTo(12);

        // Custom properties (4 review rounds, 10 specialist calls)
        ProfileTools customLimitTools = new ProfileTools(profileRepository,
            new OrchestratorProperties(4, 10, null, null), areaService, json);
        JsonNode customNeg = json.readTree(customLimitTools.getProfile("neg-prof"));
        assertThat(customNeg.get("maxReviewRounds").intValue()).isEqualTo(4);
        assertThat(customNeg.get("maxSpecialistCalls").intValue()).isEqualTo(10);

        JsonNode customZero = json.readTree(customLimitTools.getProfile("zero-prof"));
        assertThat(customZero.get("maxReviewRounds").intValue()).isZero();
        assertThat(customZero.get("maxSpecialistCalls").intValue()).isEqualTo(10);
    }

    @Test
    void normalizesPlaybooksNamesAndSpecialistPlaybooksList() throws Exception {
        OrchestratorProfile messy = new OrchestratorProfile("  messy-name  ", 1, 2, "  orch-pb  ",
            "   ", Arrays.asList(" oim-a ", null, "", "   ", "oim-b"), null, null, null, null, null,
            null, false, NOW, NOW, " OIM ");
        OrchestratorProfile nullListAndBlanks = new OrchestratorProfile("   ", 1, 2, "", null, null,
            null, null, null, null, null, null, false, NOW, NOW, "OIM");

        when(profileRepository.findAll()).thenReturn(List.of(messy, nullListAndBlanks));

        JsonNode list = json.readTree(tools.listProfiles(null));
        assertThat(list).hasSize(2);

        JsonNode first = list.get(0);
        assertThat(first.get("name").asText()).isEqualTo("messy-name");
        assertThat(first.get("area").asText()).isEqualTo("OIM");
        assertThat(first.get("orchestratorPlaybook").asText()).isEqualTo("orch-pb");
        assertThat(first.get("reviewerPlaybook").isNull()).isTrue();
        assertThat(first.get("specialistPlaybooks").isArray()).isTrue();
        assertThat(json.convertValue(first.get("specialistPlaybooks"), List.class))
            .containsExactly("oim-a", "oim-b");

        JsonNode second = list.get(1);
        assertThat(second.get("name").isNull()).isTrue();
        assertThat(second.get("orchestratorPlaybook").isNull()).isTrue();
        assertThat(second.get("reviewerPlaybook").isNull()).isTrue();
        assertThat(second.get("specialistPlaybooks").isArray()).isTrue();
        assertThat(second.get("specialistPlaybooks")).isEmpty();
    }

    @Test
    void roleModelsAndThinkingLevelsTrimPreserveModelCaseLowercaseThinkingUnderTurkishLocaleAndFallback()
        throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));

            OrchestratorProperties paddedDefaults = new OrchestratorProperties(null, null,
                new RoleDefaults(new RoleConfig("  GPT-5.6-Luna  ", "  HIGH  "),
                    new RoleConfig("  Gemini-Rev-Model  ", "  MINIMAL  "),
                    new RoleConfig("  Spec-Default-Model  ", "  MEDIUM  ")),
                null);
            ProfileTools trTools =
                new ProfileTools(profileRepository, paddedDefaults, areaService, json);

            // Profile overrides orchestrator (padded + uppercase I), leaves reviewer blank (" ")
            // and specialist null so both fall back to paddedDefaults.
            OrchestratorProfile prof = new OrchestratorProfile("tr-prof", 2, 4, "orch", "rev",
                List.of("spec"), "  Custom-Orch-Model  ", "  MINIMAL  ", "   ", "   ", null, null,
                false, NOW, NOW, "OIM");
            when(profileRepository.findByName("tr-prof")).thenReturn(Optional.of(prof));

            JsonNode node = json.readTree(trTools.getProfile("tr-prof"));
            assertThat(node.get("orchestratorModel").asText()).isEqualTo("Custom-Orch-Model");
            assertThat(node.get("orchestratorThinkingLevel").asText()).isEqualTo("minimal");
            assertThat(node.get("reviewerModel").asText()).isEqualTo("Gemini-Rev-Model");
            assertThat(node.get("reviewerThinkingLevel").asText()).isEqualTo("minimal");
            assertThat(node.get("specialistModel").asText()).isEqualTo("Spec-Default-Model");
            assertThat(node.get("specialistThinkingLevel").asText()).isEqualTo("medium");

            // Also verify HIGH -> "high" (never "hıgh") on profile override directly under tr
            // locale
            OrchestratorProfile highProf = new OrchestratorProfile("high-prof", 2, 4, "orch", "rev",
                List.of(), null, "  HIGH  ", null, null, null, null, false, NOW, NOW, "OIM");
            when(profileRepository.findByName("high-prof")).thenReturn(Optional.of(highProf));
            JsonNode highNode = json.readTree(trTools.getProfile("high-prof"));
            assertThat(highNode.get("orchestratorModel").asText()).isEqualTo("GPT-5.6-Luna");
            assertThat(highNode.get("orchestratorThinkingLevel").asText()).isEqualTo("high");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void partialRoleDefaultsAndNullPropertiesResolveUnsetRoleFieldsToJsonNullWithoutNpe()
        throws Exception {
        OrchestratorProfile bare = new OrchestratorProfile("bare-prof", -1, 0, "orch", "rev",
            List.of(), "   ", "   ", null, null, "", "", false, NOW, NOW, "OIM");
        when(profileRepository.findByName("bare-prof")).thenReturn(Optional.of(bare));

        // Partial RoleDefaults: orchestrator non-null (with blank thinking), reviewer & specialist
        // null
        ProfileTools partialTools = new ProfileTools(profileRepository,
            new OrchestratorProperties(null, null,
                new RoleDefaults(new RoleConfig("  Only-Orch  ", "   "), null, null), null),
            areaService, json);

        JsonNode partialNode = json.readTree(partialTools.getProfile("bare-prof"));
        assertExactShapeAndTypes(partialNode);
        assertThat(partialNode.get("orchestratorModel").asText()).isEqualTo("Only-Orch");
        assertThat(partialNode.get("orchestratorThinkingLevel").isNull()).isTrue();
        assertThat(partialNode.get("reviewerModel").isNull()).isTrue();
        assertThat(partialNode.get("reviewerThinkingLevel").isNull()).isTrue();
        assertThat(partialNode.get("specialistModel").isNull()).isTrue();
        assertThat(partialNode.get("specialistThinkingLevel").isNull()).isTrue();

        // Fully null OrchestratorProperties(null, null, null, null)
        ProfileTools nullPropsTools = new ProfileTools(profileRepository,
            new OrchestratorProperties(null, null, null, null), areaService, json);

        JsonNode nullPropsNode = json.readTree(nullPropsTools.getProfile("bare-prof"));
        assertExactShapeAndTypes(nullPropsNode);
        assertThat(nullPropsNode.get("orchestratorModel").isNull()).isTrue();
        assertThat(nullPropsNode.get("orchestratorThinkingLevel").isNull()).isTrue();
        assertThat(nullPropsNode.get("reviewerModel").isNull()).isTrue();
        assertThat(nullPropsNode.get("reviewerThinkingLevel").isNull()).isTrue();
        assertThat(nullPropsNode.get("specialistModel").isNull()).isTrue();
        assertThat(nullPropsNode.get("specialistThinkingLevel").isNull()).isTrue();
        assertThat(nullPropsNode.get("maxReviewRounds").intValue()).isEqualTo(3);
        assertThat(nullPropsNode.get("maxSpecialistCalls").intValue()).isEqualTo(8);
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
        assertThat(blankAreaJson.get("area").isNull()).isTrue();

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
            .isEqualTo("ERROR: profile 'ping-prof' not found.");
        assertThat(tools.getProfile("null-area-prof"))
            .isEqualTo("ERROR: profile 'null-area-prof' not found.");
        assertThat(tools.getProfile("blank-area-prof"))
            .isEqualTo("ERROR: profile 'blank-area-prof' not found.");

        // Byte-for-byte refusal symmetry when the same name is genuinely absent from DB
        when(profileRepository.findByName("ping-prof")).thenReturn(Optional.empty());
        String absentReply = tools.getProfile("ping-prof");
        when(profileRepository.findByName("ping-prof")).thenReturn(Optional.of(pingProf));
        String unauthorizedReply = tools.getProfile("  ping-prof  ");
        assertThat(unauthorizedReply).isEqualTo(absentReply)
            .isEqualTo("ERROR: profile 'ping-prof' not found.");

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
    void getProfileReReadsRepositoryOnEveryCallAndTrimsLookupName() throws Exception {
        OrchestratorProfile oimProf = profileInArea("OIM", "OIM");
        when(profileRepository.findByName("OIM")).thenReturn(Optional.of(oimProf),
            Optional.empty());

        assertThat(json.readTree(tools.getProfile("  OIM  ")).get("name").asText())
            .isEqualTo("OIM");
        assertThat(tools.getProfile("OIM")).isEqualTo("ERROR: profile 'OIM' not found.");
        verify(profileRepository, times(2)).findByName("OIM");
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
            .thenThrow(new IllegalStateException("jdbc:postgresql://secret-db-host:5432/yvoke"));
        when(profileRepository.findByName("OIM"))
            .thenThrow(new IllegalStateException("jdbc:postgresql://secret-db-host:5432/yvoke"));

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
        ProfileTools areaFailTools =
            new ProfileTools(cleanRepo, defaultProperties, failingAreas, json);
        assertThat(areaFailTools.listProfiles("OIM"))
            .isEqualTo("ERROR: the 'list_profiles' tool failed to complete the request.")
            .doesNotContain("secret-db-host");

        // 3. ObjectMapper serialization failure
        ObjectMapper brokenMapper = mock(ObjectMapper.class);
        when(brokenMapper.writeValueAsString(any()))
            .thenThrow(new JsonProcessingException("secret-db-host serialization boom") {});
        when(cleanRepo.findAll()).thenReturn(List.of(profileInArea("OIM", "OIM")));
        when(cleanRepo.findByName("OIM")).thenReturn(Optional.of(profileInArea("OIM", "OIM")));
        ProfileTools mapperFailTools =
            new ProfileTools(cleanRepo, defaultProperties, areaService, brokenMapper);

        assertThat(mapperFailTools.listProfiles(null))
            .isEqualTo("ERROR: the 'list_profiles' tool failed to complete the request.")
            .doesNotContain("secret-db-host");
        assertThat(mapperFailTools.getProfile("OIM"))
            .isEqualTo("ERROR: the 'get_profile' tool failed to complete the request.")
            .doesNotContain("secret-db-host");
    }

    private static OrchestratorProfile profileInArea(String name, String area) {
        return new OrchestratorProfile(name, 3, 8, "orch-pb", "rev-pb", List.of("spec-pb"), null,
            null, null, null, null, null, false, NOW, NOW, area);
    }

    private static void authenticateAs(String authority) {
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken("user", "pass",
                List.of(new SimpleGrantedAuthority(authority))));
    }

    private static void assertExactShapeAndTypes(JsonNode node) {
        assertThat(keys(node)).isEqualTo(new TreeSet<>(EXPECTED_KEYS));
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
