package de.palsoftware.yvoke.area.core;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AreaService {

    private final AreaRepository areaRepository;

    public AreaService(AreaRepository areaRepository) {
        this.areaRepository = areaRepository;
    }

    public List<Area> listAreas() {
        return areaRepository.findAll();
    }

    public List<String> listAreaNames() {
        return areaRepository.findAllNames();
    }

    /**
     * Finds an area by name, ignoring case and surrounding spaces, and returns it with its STORED
     * name. Callers query with that stored name rather than the caller's spelling, so a lenient
     * match can never be followed by a strict query that finds nothing.
     */
    public Optional<Area> findArea(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.trim();
        return areaRepository.findAll().stream().filter(a -> a.name().equalsIgnoreCase(wanted))
            .findFirst();
    }

    /**
     * The stored name of an existing area. Everything belongs to exactly one area, so a missing or
     * unknown area is refused here with a message naming it, before the database's foreign key
     * would refuse it with a constraint name.
     */
    public String requireArea(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("An area is required.");
        }
        return findArea(name).map(Area::name).orElseThrow(
            () -> new IllegalArgumentException("Area '" + name.trim() + "' does not exist."));
    }

    /** Every area with its members, sorted by name. */
    public List<AreaWithMembers> listAreasWithMembers() {
        Map<String, AreaMembers> members = areaRepository.findAllMembers();
        return areaRepository.findAll().stream()
            .map(a -> new AreaWithMembers(a, members.getOrDefault(a.name(), AreaMembers.NONE)))
            .toList();
    }

    /** One area with its members, matched as {@link #findArea(String)} matches. */
    public Optional<AreaWithMembers> findAreaWithMembers(String name) {
        return findArea(name).map(a -> new AreaWithMembers(a,
            areaRepository.findAllMembers().getOrDefault(a.name(), AreaMembers.NONE)));
    }

    /**
     * Creates an area ({@code originalName} blank) or saves an existing one, renaming it when
     * {@code name} differs. A rename goes through {@code ON UPDATE CASCADE}, so every member
     * follows it. Each default must be a member of the area that a client can pick. One
     * transaction, so a failed upsert also undoes the rename.
     */
    @Transactional
    public void saveArea(String originalName, String name, String title, String description,
        boolean prototype, String defaultSystemPrompt, String defaultPlaybook,
        String defaultProfile) {
        String newName = blankToNull(name);
        if (newName == null) {
            throw new IllegalArgumentException("An area name is required.");
        }
        String stored = null;
        if (blankToNull(originalName) == null) {
            findArea(newName).ifPresent(existing -> {
                throw new IllegalArgumentException(
                    "Area '" + existing.name() + "' already exists.");
            });
        } else {
            String current = requireArea(originalName);
            findArea(newName).filter(other -> !other.name().equals(current)).ifPresent(other -> {
                throw new IllegalArgumentException("Area '" + other.name() + "' already exists.");
            });
            stored = current;
        }
        AreaMembers members = stored == null ? AreaMembers.NONE
            : areaRepository.findAllMembers().getOrDefault(stored, AreaMembers.NONE);
        // Before any rename, so a refused default leaves the area as it was.
        checkDefaults(newName, members, blankToNull(defaultSystemPrompt),
            blankToNull(defaultPlaybook), blankToNull(defaultProfile));
        if (stored != null && !stored.equals(newName)) {
            areaRepository.rename(stored, newName);
        }
        String storedTitle = blankToNull(title);
        areaRepository.upsert(new Area(newName, storedTitle != null ? storedTitle : newName,
            blankToNull(description), prototype, blankToNull(defaultSystemPrompt),
            blankToNull(defaultPlaybook), blankToNull(defaultProfile), null, null));
    }

    /**
     * Deletes an empty area and returns its stored name. One that still has members is refused by
     * the database.
     */
    @Transactional
    public String deleteArea(String name) {
        String stored = requireArea(name);
        try {
            areaRepository.delete(stored);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Area '" + stored
                + "' still has system prompts, collections, playbooks or profiles. "
                + "Move or delete them first.");
        }
        return stored;
    }

    private static void checkDefaults(String area, AreaMembers members, String systemPrompt,
        String playbook, String profile) {
        if (systemPrompt != null && !members.systemPrompts().contains(systemPrompt)) {
            throw new IllegalArgumentException("System prompt '" + systemPrompt
                + "' is not a chat prompt of area '" + area + "'.");
        }
        if (playbook != null && !members.playbooks().contains(playbook)) {
            throw new IllegalArgumentException(
                "Playbook '" + playbook + "' is not a pickable playbook of area '" + area + "'.");
        }
        if (profile != null && !members.profiles().contains(profile)) {
            throw new IllegalArgumentException(
                "Profile '" + profile + "' is not a profile of area '" + area + "'.");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
