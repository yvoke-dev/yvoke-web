package de.palsoftware.yvoke.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SsrfAddressValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254",
        "100.64.0.1", "100.100.100.200", "0.0.0.0", "0.1.2.3", "198.18.0.1", "224.0.0.1",
        "255.255.255.255", "::1", "fc00::1", "fd12:3456:789a::1", "fe80::1", "ff02::1",
        "::ffff:127.0.0.1", "::ffff:169.254.169.254", "::ffff:10.0.0.5"})
    void blocksPrivateLoopbackMetadataCgnatUlaAndMappedAddresses(String literal) throws Exception {
        InetAddress addr = InetAddress.getByName(literal);
        assertThat(SsrfAddressValidator.isBlockedAddress(addr))
            .as("address %s must be blocked", literal).isTrue();
        assertThatThrownBy(() -> SsrfAddressValidator.resolveAndValidate(literal))
            .isInstanceOf(UnknownHostException.class).hasMessageContaining("blocked address");
    }

    @ParameterizedTest
    @ValueSource(strings = {"203.0.113.5", "198.51.100.7", "8.8.8.8", "2001:db8::1"})
    void allowsPublicRoutableAddresses(String literal) throws Exception {
        InetAddress addr = InetAddress.getByName(literal);
        assertThat(SsrfAddressValidator.isBlockedAddress(addr))
            .as("public address %s must be allowed", literal).isFalse();
        assertThat(SsrfAddressValidator.resolveAndValidate(literal)).isNotEmpty();
    }

    @Test
    void rejectsNullAddressAndBlankHost() {
        assertThat(SsrfAddressValidator.isBlockedAddress(null)).isTrue();
        assertThatThrownBy(() -> SsrfAddressValidator.resolveAndValidate("  "))
            .isInstanceOf(UnknownHostException.class);
    }
}
