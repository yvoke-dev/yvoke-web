package de.palsoftware.yvoke.shared.security;

import java.util.List;

/**
 * Service Provider Interface (SPI) for discovering available knowledge area names.
 *
 * <p>
 * Enables components in {@code shared.security} (such as {@code LoginController}) to discover
 * existing areas without creating a prohibited dependency from {@code shared} into the {@code area}
 * domain.
 * </p>
 */
public interface AvailableAreasProvider {

    /**
     * Lists all existing knowledge area names, typically sorted alphabetically.
     *
     * @return non-null list of area names (empty if none exist)
     */
    List<String> listAreaNames();
}
